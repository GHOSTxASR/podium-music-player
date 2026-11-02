package app.podium.core.database

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.withWriteTransaction
import app.podium.core.model.ArtworkRef
import app.podium.core.model.SourceId
import app.podium.core.model.Track
import app.podium.core.model.TrackId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import java.util.UUID

/** A playlist of the listener's own music (D-106): LOCAL's, kept apart from ONLINE's (D-34). */
@Entity(tableName = "local_playlist")
data class LocalPlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/**
 * One song of a local playlist. [position] is fractional, so adding and moving never renumber.
 * The song is kept by its id: one that leaves the library (removed, or its folder no longer read)
 * hides from the playlist until it's back.
 */
@Entity(
    tableName = "local_playlist_track",
    foreignKeys = [
        ForeignKey(entity = LocalPlaylistEntity::class, parentColumns = ["id"], childColumns = ["playlist_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["playlist_id", "position"]), Index(value = ["track_id"])],
)
data class LocalPlaylistTrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "playlist_id") val playlistId: String,
    @ColumnInfo(name = "track_id") val trackId: String,
    val position: Double,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)

/** A playlist with how many of its songs are in the library now. */
data class LocalPlaylistRow(
    val id: String,
    val name: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "track_count") val trackCount: Int,
)

/** A cover of a playlist's song, in the playlist's order (only what the covers need, nothing decoded). */
data class LocalPlaylistCoverRow(
    @ColumnInfo(name = "playlist_id") val playlistId: String,
    @ColumnInfo(name = "artwork_source_id") val artworkSourceId: String,
    @ColumnInfo(name = "artwork_key") val artworkKey: String,
)

/** A playlist entry joined with its song. */
data class LocalPlaylistEntryRow(
    @ColumnInfo(name = "entry_id") val entryId: Long,
    @ColumnInfo(name = "playlist_id") val playlistId: String,
    val position: Double,
    @Embedded val track: TrackEntity,
)

@Dao
interface LocalPlaylistDao {
    @Query(
        "SELECT p.id, p.name, p.updated_at, " +
            "(SELECT COUNT(*) FROM local_playlist_track t JOIN track ON track.id = t.track_id " +
            "WHERE t.playlist_id = p.id AND track.in_library = 1) AS track_count " +
            "FROM local_playlist p ORDER BY p.updated_at DESC",
    )
    fun playlists(): Flow<List<LocalPlaylistRow>>

    @Query("SELECT * FROM local_playlist WHERE id = :id")
    fun playlist(id: String): Flow<LocalPlaylistEntity?>

    @Query(
        "SELECT t.id AS entry_id, t.playlist_id, t.position, track.* FROM local_playlist_track t JOIN track ON track.id = t.track_id " +
            "WHERE t.playlist_id = :id AND track.in_library = 1 ORDER BY t.position, t.id",
    )
    fun entries(id: String): Flow<List<LocalPlaylistEntryRow>>

    /** Every playlist's covers in its order, for the playlists list. */
    @Query(
        "SELECT t.playlist_id, track.artwork_source_id, track.artwork_key FROM local_playlist_track t JOIN track ON track.id = t.track_id " +
            "WHERE track.in_library = 1 AND track.artwork_source_id IS NOT NULL AND track.artwork_key IS NOT NULL " +
            "ORDER BY t.playlist_id, t.position, t.id",
    )
    fun covers(): Flow<List<LocalPlaylistCoverRow>>

    /** Every entry, its song in the library or not (adding goes after them all). */
    @Query("SELECT id, position FROM local_playlist_track WHERE playlist_id = :id ORDER BY position, id")
    suspend fun positions(id: String): List<EntryPosition>

    /** The entries the playlist shows: those whose song is in the library (moves count among them). */
    @Query(
        "SELECT t.id, t.position FROM local_playlist_track t JOIN track ON track.id = t.track_id " +
            "WHERE t.playlist_id = :id AND track.in_library = 1 ORDER BY t.position, t.id",
    )
    suspend fun shownPositions(id: String): List<EntryPosition>

    @Insert
    suspend fun insertPlaylist(playlist: LocalPlaylistEntity)

    @Query("UPDATE local_playlist SET name = :name, updated_at = :now WHERE id = :id")
    suspend fun rename(id: String, name: String, now: Long)

    @Query("UPDATE local_playlist SET updated_at = :now WHERE id = :id")
    suspend fun touch(id: String, now: Long)

    @Query("DELETE FROM local_playlist WHERE id = :id")
    suspend fun delete(id: String)

    @Insert
    suspend fun insertEntries(entries: List<LocalPlaylistTrackEntity>)

    @Query("DELETE FROM local_playlist_track WHERE id = :entryId")
    suspend fun deleteEntry(entryId: Long)

    @Query("UPDATE local_playlist_track SET position = :position WHERE id = :entryId")
    suspend fun setPosition(entryId: Long, position: Double)
}

/** A local playlist as lists show it: [covers] are its first songs' distinct covers. */
data class LocalPlaylistInfo(val id: String, val name: String, val trackCount: Int, val updatedAt: Long, val covers: List<ArtworkRef>)

data class LocalPlaylistEntry(val entryId: Long, val track: Track)

data class LocalPlaylistDetail(val info: LocalPlaylistInfo, val entries: List<LocalPlaylistEntry>)

/**
 * The listener's local playlists (D-106), over [LocalPlaylistDao]. Songs are added by id (they're
 * the library's own); positions are fractional, renumbered only when two neighbours get too close.
 */
class LocalPlaylistStore(private val db: PodiumDatabase, private val now: () -> Long = System::currentTimeMillis) {
    private val dao = db.localPlaylists()

    fun playlists(): Flow<List<LocalPlaylistInfo>> = combine(dao.playlists(), dao.covers()) { rows, covers ->
        val byPlaylist = covers.groupBy { it.playlistId }
        rows.map { row ->
            val art = byPlaylist[row.id].orEmpty().asSequence()
                .map { ArtworkRef(SourceId(it.artworkSourceId), it.artworkKey) }
                .distinct()
                .take(COVERS)
                .toList()
            LocalPlaylistInfo(row.id, row.name, row.trackCount, row.updatedAt, art)
        }
    }.flowOn(Dispatchers.Default)

    fun playlist(id: String): Flow<LocalPlaylistDetail?> = combine(dao.playlist(id), dao.entries(id)) { p, entries ->
        p?.let {
            val tracks = entries.map { e -> LocalPlaylistEntry(e.entryId, TrackMapping.toTrack(e.track)) }
            val art = tracks.mapNotNull { e -> e.track.artwork }.distinct().take(COVERS)
            LocalPlaylistDetail(LocalPlaylistInfo(it.id, it.name, tracks.size, it.updatedAt, art), tracks)
        }
    }.flowOn(Dispatchers.Default)

    suspend fun create(name: String, tracks: List<TrackId> = emptyList()): String {
        val id = UUID.randomUUID().toString()
        val at = now()
        dao.insertPlaylist(LocalPlaylistEntity(id, name.trim().ifEmpty { "New playlist" }, createdAt = at, updatedAt = at))
        if (tracks.isNotEmpty()) add(id, tracks)
        return id
    }

    suspend fun rename(id: String, name: String) {
        if (name.isBlank()) return
        dao.rename(id, name.trim(), now())
    }

    suspend fun delete(id: String) = dao.delete(id)

    /** Adds songs at the end, in order; a song already there is added again (the listener chose it). */
    suspend fun add(id: String, tracks: List<TrackId>) {
        if (tracks.isEmpty()) return
        db.withWriteTransaction {
            val last = dao.positions(id).lastOrNull()?.position ?: 0.0
            val at = now()
            dao.insertEntries(tracks.mapIndexed { i, t -> LocalPlaylistTrackEntity(playlistId = id, trackId = t.value, position = last + i + 1, addedAt = at) })
            dao.touch(id, at)
        }
    }

    suspend fun remove(id: String, entryId: Long) {
        dao.deleteEntry(entryId)
        dao.touch(id, now())
    }

    /**
     * Moves an entry so it ends up at [toIndex] among the entries the playlist shows, between its new
     * neighbours there (an entry whose song has left the library keeps its own place).
     */
    suspend fun move(id: String, entryId: Long, toIndex: Int) = db.withWriteTransaction {
        val others = dao.shownPositions(id).filter { it.id != entryId }
        val index = toIndex.coerceIn(0, others.size)
        val before = others.getOrNull(index - 1)?.position
        val after = others.getOrNull(index)?.position
        if (before != null && after != null && after - before < MIN_GAP) {
            // The gap is exhausted: renumber everyone, then place.
            others.forEachIndexed { i, e -> dao.setPosition(e.id, (i + 1).toDouble()) }
            dao.setPosition(entryId, index + 0.5)
        } else {
            dao.setPosition(
                entryId,
                when {
                    before == null && after == null -> 1.0
                    before == null -> after!! - 1.0
                    after == null -> before + 1.0
                    else -> (before + after) / 2.0
                },
            )
        }
        dao.touch(id, now())
    }

    private companion object {
        const val COVERS = 4
        const val MIN_GAP = 1e-6
    }
}
