package app.podium.feature.library

import app.podium.core.model.AlbumId
import app.podium.core.model.ArtistId
import app.podium.core.model.LibraryGrouping
import app.podium.core.model.Track

/** An album as the library lists it. */
data class LibraryAlbum(
    val id: AlbumId,
    val title: String,
    val artist: String,
    val artistId: ArtistId,
    val artworkUri: String?,
    val year: Int?,
    val trackCount: Int,
)

/** An artist as the library lists it; [covers] are a few of their album covers, for previews. */
data class LibraryArtist(
    val id: ArtistId,
    val name: String,
    val artworkUri: String?,
    val albumCount: Int,
    val trackCount: Int,
    val covers: List<String>,
)

/** One album's page: the album and its songs in disc and track order. */
data class AlbumDetail(val album: LibraryAlbum, val tracks: List<Track>)

/** One artist's page: their albums and all their songs. */
data class ArtistDetail(val artist: LibraryArtist, val albums: List<LibraryAlbum>, val tracks: List<Track>)

/**
 * Albums and artists derived in memory from a song list, by the library's grouping rules
 * ([LibraryGrouping]). The app reads them from the library database instead (D-31); this is the
 * default for repositories without one (tests, previews), and it gives the same answers.
 */
object LibraryIndex {

    private fun ordered(songs: List<Track>) =
        songs.sortedWith(compareBy<Track>({ it.discNumber ?: 1 }, { it.trackNumber ?: Int.MAX_VALUE }, { LibraryGrouping.sortKey(it.title) }))

    private fun album(id: AlbumId, songs: List<Track>): LibraryAlbum {
        val tracks = ordered(songs)
        val first = tracks.first()
        return LibraryAlbum(
            id = id,
            title = first.album?.title?.takeIf { it.isNotBlank() } ?: "Unknown album",
            artist = first.album?.albumArtist?.takeIf { it.isNotBlank() } ?: first.artistDisplay,
            artistId = LibraryGrouping.artistIdOf(first),
            artworkUri = tracks.firstNotNullOfOrNull { it.artwork?.uri },
            year = tracks.firstNotNullOfOrNull { it.releaseDate?.year },
            trackCount = songs.size,
        )
    }

    fun albums(tracks: List<Track>): List<LibraryAlbum> =
        tracks.groupBy(LibraryGrouping::albumIdOf).map { (id, songs) -> album(id, songs) }
            .sortedWith(compareBy({ LibraryGrouping.sortKey(it.title) }, { it.id.value }))

    fun album(tracks: List<Track>, id: AlbumId): AlbumDetail? {
        val songs = tracks.filter { LibraryGrouping.albumIdOf(it) == id }
        if (songs.isEmpty()) return null
        return AlbumDetail(album(id, songs), ordered(songs))
    }

    /** Artists, with their pictures where a source has one ([images]); otherwise none — the UI draws a portrait monogram. */
    fun artists(tracks: List<Track>, images: Map<ArtistId, String> = emptyMap()): List<LibraryArtist> {
        val albums = albums(tracks).groupBy { it.artistId }
        return tracks.groupBy(LibraryGrouping::artistIdOf).map { (id, songs) -> artist(id, songs, images, albums[id].orEmpty()) }
            .sortedWith(compareBy({ LibraryGrouping.sortKey(it.name) }, { it.id.value }))
    }

    fun artist(tracks: List<Track>, id: ArtistId, images: Map<ArtistId, String> = emptyMap()): ArtistDetail? {
        val songs = tracks.filter { LibraryGrouping.artistIdOf(it) == id }
        if (songs.isEmpty()) return null
        val albums = songs.groupBy(LibraryGrouping::albumIdOf).map { (albumId, s) -> album(albumId, s) }
            .sortedWith(compareBy({ it.year ?: Int.MAX_VALUE }, { LibraryGrouping.sortKey(it.title) }))
        return ArtistDetail(
            artist = artist(id, songs, images, albums),
            albums = albums,
            tracks = songs.sortedWith(compareBy({ LibraryGrouping.sortKey(it.title) }, { it.id.value })),
        )
    }

    private fun artist(id: ArtistId, songs: List<Track>, images: Map<ArtistId, String>, theirAlbums: List<LibraryAlbum>) = LibraryArtist(
        id = id,
        name = LibraryGrouping.artistNameOf(songs.first()),
        artworkUri = images[id],
        albumCount = songs.map(LibraryGrouping::albumIdOf).distinct().size,
        trackCount = songs.size,
        covers = theirAlbums.mapNotNull { it.artworkUri }.distinct().take(6),
    )

    /** Distinct artwork, in library order, for previews. */
    fun artwork(tracks: List<Track>, limit: Int = 8): List<String> =
        tracks.mapNotNull { it.artwork?.uri }.distinct().take(limit)
}
