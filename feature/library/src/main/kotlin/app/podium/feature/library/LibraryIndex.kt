package app.podium.feature.library

import app.podium.core.model.AlbumId
import app.podium.core.model.ArtistId
import app.podium.core.model.Track
import java.text.Collator

/** An album as the library shows it, assembled from its songs. */
data class LibraryAlbum(
    val id: AlbumId,
    val title: String,
    val artist: String,
    val artistId: ArtistId,
    val artworkUri: String?,
    val year: Int?,
    val tracks: List<Track>,
)

/** An artist, with their albums and songs. */
data class LibraryArtist(
    val id: ArtistId,
    val name: String,
    val artworkUri: String?,
    val albums: List<LibraryAlbum>,
    val tracks: List<Track>,
)

/**
 * Albums and artists derived from the merged song list (D-29): provider-neutral, and good enough
 * until the library database (S4) indexes them. Songs without album metadata are grouped under an
 * "Unknown album" per artist and source, never merged across sources by title.
 */
object LibraryIndex {
    private val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }

    fun artistIdOf(track: Track): ArtistId =
        track.artists.firstOrNull()?.id ?: ArtistId.of(track.source.sourceId, track.artistDisplay.trim().lowercase())

    fun albumIdOf(track: Track): AlbumId =
        track.album?.id ?: AlbumId.of(track.source.sourceId, "unknown/" + artistIdOf(track).value)

    fun albums(tracks: List<Track>): List<LibraryAlbum> =
        tracks.groupBy(::albumIdOf).map { (id, songs) ->
            val ordered = songs.sortedWith(compareBy<Track>({ it.discNumber ?: 1 }, { it.trackNumber ?: Int.MAX_VALUE }).thenBy(collator) { it.title })
            val first = ordered.first()
            LibraryAlbum(
                id = id,
                title = first.album?.title?.takeIf { it.isNotBlank() } ?: "Unknown album",
                artist = first.album?.albumArtist?.takeIf { it.isNotBlank() } ?: first.artistDisplay,
                artistId = artistIdOf(first),
                artworkUri = ordered.firstNotNullOfOrNull { it.artwork?.uri },
                year = ordered.firstNotNullOfOrNull { it.releaseDate?.year },
                tracks = ordered,
            )
        }.sortedWith(compareBy(collator) { it.title })

    fun artists(tracks: List<Track>): List<LibraryArtist> {
        val albums = albums(tracks)
        return tracks.groupBy(::artistIdOf).map { (id, songs) ->
            val theirAlbums = albums.filter { album -> album.tracks.any { artistIdOf(it) == id } }
            LibraryArtist(
                id = id,
                name = songs.first().artists.firstOrNull()?.name ?: songs.first().artistDisplay,
                artworkUri = theirAlbums.firstNotNullOfOrNull { it.artworkUri },
                albums = theirAlbums,
                tracks = songs.sortedWith(compareBy(collator) { it.title }),
            )
        }.sortedWith(compareBy(collator) { it.name })
    }

    /** Distinct artwork, in library order, for previews. */
    fun artwork(tracks: List<Track>, limit: Int = 8): List<String> =
        tracks.mapNotNull { it.artwork?.uri }.distinct().take(limit)
}
