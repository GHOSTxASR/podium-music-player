package app.podium.core.database

import androidx.room3.withWriteTransaction
import app.podium.core.model.AlbumId
import app.podium.core.model.ArtistId
import app.podium.core.model.LibraryGrouping
import app.podium.core.model.SourceId
import app.podium.core.model.Track
import app.podium.core.model.TrackId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap

/** An album as the library lists it. */
data class StoredAlbum(
    val id: AlbumId,
    val title: String,
    val artist: String,
    val artistId: ArtistId,
    val artworkUri: String?,
    val year: Int?,
    val trackCount: Int,
)

/** An artist as the library lists it; [covers] are a few of their album covers, for previews. */
data class StoredArtist(
    val id: ArtistId,
    val name: String,
    val artworkUri: String?,
    val albumCount: Int,
    val trackCount: Int,
    val covers: List<String>,
)

data class StoredAlbumDetail(val album: StoredAlbum, val tracks: List<Track>)

data class StoredArtistDetail(val artist: StoredArtist, val albums: List<StoredAlbum>, val tracks: List<Track>)

/** What one sync changed. */
data class SyncResult(val added: Int, val updated: Int, val removed: Int) {
    val changed: Boolean get() = added + updated + removed > 0
}

/**
 * The library cache (D-31): each library source's songs, with albums and artists derived from them,
 * stored so the library is there the moment Podium opens and stays queryable at any size.
 *
 * A sync replaces one source's library with what the source reports now. Rows that didn't change
 * aren't rewritten; songs the source no longer has are marked removed (kept while something like
 * the queue refers to them), never silently merged with another source's.
 */
class LibraryStore(
    private val db: PodiumDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.library()

    /** Decoded songs by id, reused while their row is unchanged (decoding is the expensive part). */
    private val decoded = ConcurrentHashMap<String, Pair<Int, Track>>()

    suspend fun sync(source: SourceId, displayName: String, tracks: List<Track>, artistPictures: Map<ArtistId, String>): SyncResult {
        val timestamp = now()
        val incoming = tracks.filter { it.source.sourceId == source }.distinctBy { it.id }
            .map { TrackMapping.toEntity(it, inLibrary = true, now = timestamp) }
        val albums = deriveAlbums(source, tracks)
        val artists = deriveArtists(source, tracks, artistPictures)
        return db.withWriteTransaction {
            dao.upsertSource(SourceAccountEntity(source.value, displayName, timestamp))
            val existing = dao.libraryHashes(source.value).associate { it.id to it.contentHash }
            val changed = incoming.filter { existing[it.id] != it.contentHash }
            if (changed.isNotEmpty()) changed.chunked(CHUNK).forEach { dao.upsertTracks(it) }
            val incomingIds = incoming.mapTo(HashSet()) { it.id }
            val gone = existing.keys.filter { it !in incomingIds }
            gone.chunked(CHUNK).forEach { dao.markRemoved(source.value, it, timestamp) }

            dao.deleteAlbums(source.value)
            dao.upsertAlbums(albums)
            dao.deleteArtists(source.value)
            dao.upsertArtists(artists)
            SyncResult(
                added = changed.count { it.id !in existing },
                updated = changed.count { it.id in existing },
                removed = gone.size,
            )
        }
    }

    fun songs(sources: Collection<SourceId>): Flow<List<Track>> =
        dao.songs(sources.map { it.value }).map { rows -> rows.map(::decode) }

    fun albums(sources: Collection<SourceId>): Flow<List<StoredAlbum>> =
        dao.albums(sources.map { it.value }).map { rows -> rows.map(::album) }

    fun album(id: AlbumId): Flow<StoredAlbumDetail?> =
        combine(dao.album(id.value), dao.albumTracks(id.value)) { album, tracks ->
            album?.let { StoredAlbumDetail(album(it), tracks.map(::decode)) }
        }

    fun artists(sources: Collection<SourceId>): Flow<List<StoredArtist>> {
        val ids = sources.map { it.value }
        return combine(dao.artists(ids), dao.albums(ids)) { artists, albums ->
            val covers = albums.groupBy { it.artistId }
            artists.map { artist(it, covers[it.id].orEmpty()) }
        }
    }

    fun artist(id: ArtistId): Flow<StoredArtistDetail?> =
        combine(dao.artist(id.value), dao.artistAlbums(id.value), dao.artistTracks(id.value)) { artist, albums, tracks ->
            artist?.let { StoredArtistDetail(artist(it, albums), albums.map(::album), tracks.map(::decode)) }
        }

    suspend fun track(id: TrackId): Track? = dao.track(id.value)?.let(::decode)

    /** How many songs the library holds, across sources. */
    fun songCount(): Flow<Int> = dao.librarySongCount()

    suspend fun tracks(ids: Collection<TrackId>): Map<TrackId, Track> =
        ids.map { it.value }.distinct().chunked(CHUNK).flatMap { dao.tracks(it) }.associate { TrackId(it.id) to decode(it) }

    /** Songs whose title, artist or album has words starting with what was typed, best matches first. */
    suspend fun search(text: String, sources: Collection<SourceId>, limit: Int = 50): List<Track> {
        val match = Fts.matchExpression(text) ?: return emptyList()
        if (sources.isEmpty()) return emptyList()
        return dao.search(Fts.searchQuery(match, sources.map { it.value }, limit)).map(::decode)
    }

    private fun decode(row: TrackEntity): Track {
        decoded[row.id]?.let { (hash, track) -> if (hash == row.contentHash) return track }
        return TrackMapping.toTrack(row).also { decoded[row.id] = row.contentHash to it }
    }

    private fun album(e: AlbumEntity) = StoredAlbum(AlbumId(e.id), e.title, e.artistDisplay, ArtistId(e.artistId), e.artworkUri, e.year, e.trackCount)

    private fun artist(e: ArtistEntity, albums: List<AlbumEntity>) = StoredArtist(
        id = ArtistId(e.id),
        name = e.name,
        artworkUri = e.artworkUri,
        albumCount = e.albumCount,
        trackCount = e.trackCount,
        covers = albums.mapNotNull { it.artworkUri }.distinct().take(6),
    )

    private companion object {
        /** Keeps every statement well under SQLite's bound-variable limit. */
        const val CHUNK = 500

        fun deriveAlbums(source: SourceId, tracks: List<Track>): List<AlbumEntity> =
            tracks.filter { it.source.sourceId == source }.groupBy(LibraryGrouping::albumIdOf).map { (id, songs) ->
                val ordered = songs.sortedWith(compareBy<Track>({ it.discNumber ?: 1 }, { it.trackNumber ?: Int.MAX_VALUE }, { LibraryGrouping.sortKey(it.title) }))
                val first = ordered.first()
                val title = first.album?.title?.takeIf { it.isNotBlank() } ?: "Unknown album"
                AlbumEntity(
                    id = id.value,
                    sourceId = source.value,
                    title = title,
                    titleSort = LibraryGrouping.sortKey(title),
                    artistDisplay = first.album?.albumArtist?.takeIf { it.isNotBlank() } ?: first.artistDisplay,
                    artistId = LibraryGrouping.artistIdOf(first).value,
                    year = ordered.firstNotNullOfOrNull { it.releaseDate?.year },
                    trackCount = songs.size,
                    durationMs = songs.sumOf { it.durationMs ?: 0L },
                    artworkUri = ordered.firstNotNullOfOrNull { it.artwork?.uri },
                )
            }

        fun deriveArtists(source: SourceId, tracks: List<Track>, pictures: Map<ArtistId, String>): List<ArtistEntity> =
            tracks.filter { it.source.sourceId == source }.groupBy(LibraryGrouping::artistIdOf).map { (id, songs) ->
                val name = LibraryGrouping.artistNameOf(songs.first())
                ArtistEntity(
                    id = id.value,
                    sourceId = source.value,
                    name = name,
                    nameSort = LibraryGrouping.sortKey(name),
                    artworkUri = pictures[id],
                    albumCount = songs.map(LibraryGrouping::albumIdOf).distinct().size,
                    trackCount = songs.size,
                )
            }
    }
}
