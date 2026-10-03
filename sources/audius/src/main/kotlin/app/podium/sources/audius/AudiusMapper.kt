package app.podium.sources.audius

import app.podium.core.model.AlbumId
import app.podium.core.model.AlbumRef
import app.podium.core.model.ArtistCredit
import app.podium.core.model.ArtistId
import app.podium.core.model.ArtistRole
import app.podium.core.model.ArtworkRef
import app.podium.core.model.AudioQuality
import app.podium.core.model.Availability
import app.podium.core.model.Codec
import app.podium.core.model.PartialDate
import app.podium.core.model.PlaybackRoute
import app.podium.core.model.PlaylistId
import app.podium.core.model.RecordingIdentifiers
import app.podium.core.model.SourceId
import app.podium.core.model.SourceRef
import app.podium.core.model.Track
import app.podium.core.model.TrackId
import app.podium.sources.api.AlbumSummary
import app.podium.sources.api.ArtistSummary
import app.podium.sources.api.PlaylistSummary
import app.podium.sources.api.matching.TrackNormalizer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull

/**
 * Audius JSON → Podium's canonical model. Provider ids never leave this module except inside
 * source-qualified ids; anything only this adapter needs later (genre, the artist's id) rides in
 * [SourceRef.providerData], which nothing else parses.
 */
internal object AudiusMapper {
    val SOURCE = SourceId("audius")
    private val json = Json { ignoreUnknownKeys = true }

    /** Adapter-private data carried on each track. */
    @Serializable
    data class Private(val genre: String? = null, val mood: String? = null, val user: String? = null)

    fun privateOf(track: Track): Private? = track.source.providerData?.let { runCatching { json.decodeFromString(Private.serializer(), it) }.getOrNull() }

    fun track(t: TrackDto): Track {
        val artist = t.user
        val artistName = artist?.name?.takeIf { it.isNotBlank() } ?: artist?.handle ?: "Unknown artist"
        val analysis = TrackNormalizer.analyze(t.title, artistName)
        return Track(
            id = TrackId.of(SOURCE, t.id),
            source = SourceRef(
                SOURCE,
                t.id,
                providerUri = t.permalink?.let { "https://audius.co$it" },
                providerData = json.encodeToString(Private.serializer(), Private(t.genre, t.mood, artist?.id)),
            ),
            title = t.title,
            artists = listOf(ArtistCredit(artistName, ArtistRole.PRIMARY, artist?.id?.let { ArtistId.of(SOURCE, it) })),
            album = t.album_backlink?.playlist_name?.let { AlbumRef(it) },
            durationMs = t.duration?.times(1000),
            artwork = artworkRef(t.artwork),
            identifiers = RecordingIdentifiers(isrc = t.isrc),
            releaseDate = date(t.release_date),
            version = analysis.version,
            // What Audius serves is an MP3 stream; the decoder measures the rest (never "lossless").
            advertisedQualities = listOf(AudioQuality(codec = Codec.MP3)),
            availability = availability(t),
            routes = setOf(PlaybackRoute.DIRECT),
        )
    }

    fun availability(t: TrackDto): Availability = when {
        !t.is_streamable -> Availability.Unavailable("Not available to stream")
        t.access?.stream == false || (t.stream_conditions != null && t.stream_conditions != JsonNull) ->
            Availability.Unavailable("The artist limits who can stream this")
        else -> Availability.Playable
    }

    fun artist(u: UserDto) = ArtistSummary(
        id = ArtistId.of(SOURCE, u.id),
        name = u.name.ifBlank { u.handle },
        artwork = artworkRef(u.profile_picture),
        trackCount = u.track_count,
    )

    fun playlist(p: PlaylistDto) = PlaylistSummary(
        id = PlaylistId.of(SOURCE, p.id),
        title = p.playlist_name,
        ownerName = p.user?.name?.ifBlank { p.user.handle }.orEmpty(),
        artwork = artworkRef(p.artwork),
        trackCount = p.track_count,
        isAlbum = p.is_album,
        year = date(p.release_date)?.year,
    )

    fun album(p: PlaylistDto) = AlbumSummary(
        id = AlbumId.of(SOURCE, p.id),
        title = p.playlist_name,
        artistDisplay = p.user?.name?.ifBlank { p.user.handle }.orEmpty(),
        artwork = artworkRef(p.artwork),
        year = date(p.release_date)?.year,
        trackCount = p.track_count,
    )

    /** The 480 px image's URL is the key; the artwork facet picks the size it needs from it. */
    private fun artworkRef(sizes: Map<String, String>?): ArtworkRef? {
        val url = sizes?.get("480x480") ?: sizes?.get("1000x1000") ?: sizes?.get("150x150") ?: return null
        return ArtworkRef(SOURCE, url)
    }

    /** "2026-09-26T23:00:27Z" (or a bare date) → year, month, day. */
    fun date(s: String?): PartialDate? {
        if (s.isNullOrBlank()) return null
        val parts = s.take(10).split('-').mapNotNull { it.toIntOrNull() }
        if (parts.isEmpty() || parts[0] < 1000) return null
        return PartialDate(parts[0], parts.getOrNull(1), parts.getOrNull(2))
    }

    /** The image URL at the size closest to [sizePx] (Audius offers 150, 480 and 1000). */
    fun sizedArtwork(url: String, sizePx: Int): String {
        val size = when {
            sizePx <= 150 -> "150x150"
            sizePx <= 480 -> "480x480"
            else -> "1000x1000"
        }
        return url.replace(Regex("(150x150|480x480|1000x1000)"), size)
    }
}
