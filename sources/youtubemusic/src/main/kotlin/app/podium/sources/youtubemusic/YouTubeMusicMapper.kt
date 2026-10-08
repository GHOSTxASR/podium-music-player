package app.podium.sources.youtubemusic

import app.podium.core.model.AlbumId
import app.podium.core.model.AlbumRef
import app.podium.core.model.ArtistCredit
import app.podium.core.model.ArtistId
import app.podium.core.model.ArtworkRef
import app.podium.core.model.Availability
import app.podium.core.model.Explicitness
import app.podium.core.model.MediaKind
import app.podium.core.model.PartialDate
import app.podium.core.model.PlaybackRoute
import app.podium.core.model.PlaylistId
import app.podium.core.model.SourceId
import app.podium.core.model.SourceRef
import app.podium.core.model.Track
import app.podium.core.model.TrackId
import app.podium.sources.api.AlbumSummary
import app.podium.sources.api.ArtistSummary
import app.podium.sources.api.PlaylistSummary

/**
 * The catalogue's items as Podium's provider-neutral models. Ids are source-qualified
 * (`ytmusic|<id>`) and the provider's own ids never travel further raw (CLAUDE.md "providers never
 * leak"). Everything is metadata: a song plays only when playback resolves it (§8), so rows say
 * `Availability.Unknown` unless the catalogue marked them unplayable.
 */
internal class YouTubeMusicMapper(private val source: SourceId) {

    /** What a song inherits from the album page it was listed on (album tracks list little). */
    data class AlbumContext(val ref: AlbumRef, val artists: List<YtmRef>, val thumbnails: List<Thumb>, val year: Int?)

    fun track(song: YtmSong, context: AlbumContext? = null): Track {
        val artistRefs = song.artists.ifEmpty { context?.artists.orEmpty() }
        val credits = artistRefs.map { ArtistCredit(it.name, id = it.id?.let(::artistId)) }
        val album = song.album?.let { AlbumRef(it.name, it.id?.takeIf(::isAlbumBrowseId)?.let { id -> AlbumId.of(source, id) }) } ?: context?.ref
        return Track(
            id = TrackId.of(source, song.videoId),
            source = SourceRef(
                sourceId = source,
                providerKey = song.videoId,
                providerUri = "$ORIGIN/watch?v=${song.videoId}",
                providerData = ProviderData(song.kind, song.setVideoId).encode(),
            ),
            title = song.title,
            artists = credits,
            artistDisplay = credits.joinToString(", ") { it.name },
            album = album,
            durationMs = song.durationMs,
            artwork = artwork(song.thumbnails.ifEmpty { context?.thumbnails.orEmpty() }),
            explicitness = if (song.explicit) Explicitness.EXPLICIT else Explicitness.UNKNOWN,
            releaseDate = (song.year ?: context?.year)?.let { PartialDate(it) },
            trackNumber = song.trackNumber,
            availability = if (song.playable) Availability.Unknown else Availability.Unavailable("Not available in your region or account"),
            routes = setOf(PlaybackRoute.DIRECT),
            kind = song.kind,
        )
    }

    fun album(album: YtmAlbum): AlbumSummary = AlbumSummary(
        id = AlbumId.of(source, album.browseId),
        title = album.title,
        artistDisplay = album.artists.joinToString(", ") { it.name },
        artwork = artwork(album.thumbnails),
        year = album.year,
    )

    /** An album where a list of playlists is expected (shelves, artist pages): a playlist marked as an album. */
    fun albumAsPlaylist(album: YtmAlbum): PlaylistSummary = PlaylistSummary(
        id = PlaylistId.of(source, album.browseId),
        title = album.title,
        ownerName = album.artists.joinToString(", ") { it.name },
        artwork = artwork(album.thumbnails),
        isAlbum = true,
        year = album.year,
    )

    fun artist(artist: YtmArtist): ArtistSummary = ArtistSummary(
        id = artistId(artist.browseId),
        name = artist.name,
        artwork = artwork(artist.thumbnails),
    )

    fun playlist(playlist: YtmPlaylist): PlaylistSummary = PlaylistSummary(
        id = PlaylistId.of(source, playlist.playlistId),
        title = playlist.title,
        ownerName = playlist.author.orEmpty(),
        artwork = artwork(playlist.thumbnails),
        trackCount = playlist.trackCount,
    )

    fun artistId(browseId: String): ArtistId = ArtistId.of(source, browseId.removePrefix("MPLA"))

    /** The biggest picture the catalogue offered; the artwork facet asks for the size it needs. */
    fun artwork(thumbs: List<Thumb>): ArtworkRef? = thumbs.maxByOrNull { it.width }?.let { ArtworkRef(source, it.url) }

    /** Adapter-private data carried on a song's [SourceRef]: its kind and its entry in a playlist. */
    data class ProviderData(val kind: MediaKind, val setVideoId: String?) {
        fun encode(): String = buildString {
            append("k=").append(kind.name)
            setVideoId?.let { append(";e=").append(it) }
        }

        companion object {
            fun decode(text: String?): ProviderData? {
                if (text == null) return null
                val fields = text.split(';').mapNotNull { part -> part.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
                val kind = fields["k"]?.let { runCatching { MediaKind.valueOf(it) }.getOrNull() } ?: return null
                return ProviderData(kind, fields["e"])
            }
        }
    }

    companion object {
        const val ORIGIN = YouTubeMusicClient.ORIGIN

        fun isAlbumBrowseId(id: String) = id.startsWith("MPRE")

        /** A video id as the catalogue and the app's media session both use it. */
        private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

        fun isVideoId(id: String) = VIDEO_ID.matches(id)

        /**
         * The artwork URL at about [sizePx]: the image hosts that resize (`=w…-h…` / `=s…`) are asked
         * for that size; other pictures are used as they come.
         */
        fun sizedArtworkUrl(url: String, sizePx: Int): String {
            val size = sizePx.coerceIn(60, 1200)
            val eq = url.lastIndexOf('=')
            if (eq < 0) return url
            val host = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")
            if (!host.endsWith("googleusercontent.com") && !host.endsWith("ggpht.com")) return url
            val params = url.substring(eq + 1)
            if (!params.startsWith("w") && !params.startsWith("s")) return url
            return url.substring(0, eq + 1) + "w$size-h$size-l90-rj"
        }
    }
}
