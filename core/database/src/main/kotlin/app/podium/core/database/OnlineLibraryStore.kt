package app.podium.core.database

import androidx.room3.withWriteTransaction
import app.podium.core.model.SourceId
import app.podium.core.model.Track
import app.podium.core.model.TrackId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.util.UUID

/** An online playlist as lists show it. */
data class OnlinePlaylistInfo(val id: String, val sourceId: SourceId, val name: String, val trackCount: Int, val updatedAt: Long)

/** One entry of an online playlist (the same song may appear twice; [entryId] tells them apart). */
data class OnlinePlaylistEntry(val entryId: Long, val track: Track)

data class OnlinePlaylistDetail(val info: OnlinePlaylistInfo, val entries: List<OnlinePlaylistEntry>)

/**
 * The ONLINE environment's library (D-34): liked songs, playlists and listening history, kept apart
 * from the local library's favorites and never mixed with them. Rows are per account; until a
 * source is signed in, the account is "on this device" ([DEVICE]).
 */
class OnlineLibraryStore(
    private val db: PodiumDatabase,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val dao = db.online()

    // --- Songs ------------------------------------------------------------------------------------------

    /** Keep online songs' metadata so likes, playlists, history and the queue survive a restart. */
    suspend fun remember(tracks: Collection<Track>) {
        if (tracks.isEmpty()) return
        val unique = tracks.distinctBy { it.id }
        db.withWriteTransaction {
            db.library().insertSourcesIfMissing(unique.map { it.source.sourceId.value }.distinct().map { SourceAccountEntity(it, it, lastSyncAt = null) })
            // A song a library source owns stays the library's row; only non-library rows are refreshed.
            unique.chunked(500).forEach { chunk ->
                val owned = dao.libraryIds(chunk.map { it.id.value }).toSet()
                val at = now()
                dao.cacheTracks(chunk.filter { it.id.value !in owned }.map { TrackMapping.toEntity(it, inLibrary = false, now = at) })
            }
        }
    }

    // --- Likes --------------------------------------------------------------------------------------------

    fun likedIds(account: String = DEVICE): Flow<Set<TrackId>> = dao.likedIds(account).map { ids -> ids.mapTo(LinkedHashSet()) { TrackId(it) } }

    fun likedTracks(account: String = DEVICE): Flow<List<Track>> = dao.likedTracks(account).map { rows -> rows.map(TrackMapping::toTrack) }

    suspend fun setLiked(track: Track, liked: Boolean, account: String = DEVICE) {
        if (liked) {
            remember(listOf(track))
            dao.like(OnlineLikedTrackEntity(account, track.id.value, track.source.sourceId.value, track.source.providerKey, now()))
        } else {
            dao.unlike(account, track.id.value)
        }
    }

    // --- Playlists ----------------------------------------------------------------------------------------

    fun playlists(account: String = DEVICE): Flow<List<OnlinePlaylistInfo>> =
        dao.playlists(account).map { rows -> rows.map { OnlinePlaylistInfo(it.id, SourceId(it.sourceId), it.name, it.trackCount, it.updatedAt) } }

    fun playlist(id: String): Flow<OnlinePlaylistDetail?> = combine(dao.playlist(id), dao.entries(id)) { p, entries ->
        p?.let {
            OnlinePlaylistDetail(
                OnlinePlaylistInfo(it.id, SourceId(it.sourceId), it.name, entries.size, it.updatedAt),
                entries.map { e -> OnlinePlaylistEntry(e.entryId, TrackMapping.toTrack(e.track)) },
            )
        }
    }

    suspend fun createPlaylist(name: String, source: SourceId, tracks: List<Track> = emptyList(), account: String = DEVICE): String {
        val id = newId()
        val at = now()
        dao.insertPlaylist(OnlinePlaylistEntity(id, source.value, account, remotePlaylistId = null, name = name.trim().ifEmpty { "New playlist" }, createdAt = at, updatedAt = at))
        if (tracks.isNotEmpty()) add(id, tracks)
        return id
    }

    suspend fun rename(id: String, name: String) {
        if (name.isBlank()) return
        dao.rename(id, name.trim(), now())
    }

    suspend fun delete(id: String) = dao.deletePlaylist(id)

    /** Add songs at the end, in order. Duplicates are allowed (the UI asks first). */
    suspend fun add(id: String, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        remember(tracks)
        db.withWriteTransaction {
            val last = dao.positions(id).lastOrNull()?.position ?: 0.0
            val at = now()
            dao.insertEntries(tracks.mapIndexed { i, t -> OnlinePlaylistTrackEntity(playlistId = id, trackId = t.id.value, position = last + i + 1, addedAt = at) })
            dao.touch(id, at)
        }
    }

    suspend fun remove(id: String, entryId: Long) {
        dao.deleteEntry(entryId)
        dao.touch(id, now())
    }

    /** Move an entry to [toIndex], placing it between its new neighbours (fractional positions). */
    suspend fun move(id: String, entryId: Long, toIndex: Int) = db.withWriteTransaction {
        val others = dao.positions(id).filter { it.id != entryId }
        val index = toIndex.coerceIn(0, others.size)
        val before = others.getOrNull(index - 1)?.position
        val after = others.getOrNull(index)?.position
        val position = when {
            before == null && after == null -> 1.0
            before == null -> after!! - 1.0
            after == null -> before + 1.0
            else -> (before + after) / 2.0
        }
        if (after != null && before != null && after - before < MIN_GAP) {
            // Gaps exhausted: renumber everyone, then place.
            val renumbered = others.toMutableList()
            renumbered.forEachIndexed { i, e -> dao.setPosition(e.id, (i + 1).toDouble()) }
            dao.setPosition(entryId, index + 0.5)
        } else {
            dao.setPosition(entryId, position)
        }
        dao.touch(id, now())
    }

    // --- History -------------------------------------------------------------------------------------------

    /**
     * One listen to [track] — the song the listener chose, with its own source and provider id —
     * and, when another source's EXACT copy played it, [servedBy] (D-35).
     */
    suspend fun record(track: Track, startedAt: Long, playedMs: Long, servedBy: SourceId? = null, account: String = DEVICE) {
        if (playedMs <= 0) return
        remember(listOf(track))
        val duration = track.durationMs
        dao.record(
            OnlineHistoryEntity(
                sourceId = track.source.sourceId.value,
                accountKey = account,
                providerId = track.source.providerKey,
                trackId = track.id.value,
                startedAt = startedAt,
                playedMs = playedMs,
                durationMs = duration,
                completion = duration?.takeIf { it > 0 }?.let { (playedMs.toFloat() / it).coerceIn(0f, 1f) },
                servedBy = servedBy?.value?.takeIf { it != track.source.sourceId.value },
            ),
        )
    }

    /** The listens, newest first, as stored (for checks and diagnostics). */
    suspend fun listens(limit: Int = 50, account: String = DEVICE): List<OnlineHistoryEntity> = dao.listens(account, limit)

    fun recentlyPlayed(limit: Int = 50, account: String = DEVICE): Flow<List<Track>> =
        dao.recentTracks(account, limit).map { rows -> rows.map(TrackMapping::toTrack) }

    suspend fun clearHistory(account: String = DEVICE) = dao.clearHistory(account)

    suspend fun playedSince(since: Long): Set<TrackId> = dao.playedSince(since).mapTo(HashSet()) { TrackId(it) }

    companion object {
        /** The account of a source nobody has signed in to: the listener, on this device. */
        const val DEVICE = ""
        private const val MIN_GAP = 1e-6
    }
}
