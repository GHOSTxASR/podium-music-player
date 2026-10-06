package app.podium.sources.subsonic

import app.podium.core.model.AlbumId
import app.podium.core.model.AlbumRef
import app.podium.core.model.ArtistCredit
import app.podium.core.model.ArtistId
import app.podium.core.model.ArtistRole
import app.podium.core.model.ArtworkRef
import app.podium.core.model.AudioQuality
import app.podium.core.model.Availability
import app.podium.core.model.Codec
import app.podium.core.model.Explicitness
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

/**
 * (Open)Subsonic objects → Podium's provider-neutral model. Ids stay inside source-qualified ids;
 * albums and playlists share the PlaylistId space, told apart by a prefix only this module reads.
 * Identifiers are taken only when the server states them — never derived.
 */
internal class SubsonicMapper(private val source: SourceId) {

    fun track(s: SongDto): Track {
        val primary = s.artists.filter { it.name.isNotBlank() }.map { ArtistCredit(it.name, ArtistRole.PRIMARY, it.id?.let { id -> ArtistId.of(source, id) }) }
            .ifEmpty { listOfNotNull(s.artist?.takeIf { it.isNotBlank() }?.let { ArtistCredit(it, ArtistRole.PRIMARY, s.artistId?.let { id -> ArtistId.of(source, id) }) }) }
        return Track(
            id = TrackId.of(source, s.id),
            source = SourceRef(source, s.id),
            title = s.title,
            artists = primary,
            artistDisplay = s.displayArtist ?: s.artist ?: primary.joinToString(", ") { it.name },
            album = s.album?.let { AlbumRef(it, s.albumId?.let { id -> AlbumId.of(source, ALBUM + id) }, s.displayAlbumArtist ?: s.albumArtists.firstOrNull()?.name) },
            durationMs = s.duration?.takeIf { it > 0 }?.let { it * 1000L },
            artwork = s.coverArt?.let { ArtworkRef(source, it) },
            identifiers = RecordingIdentifiers(isrc = s.isrc.firstText(), musicBrainzRecordingId = s.musicBrainzId?.takeIf { it.isNotBlank() }),
            explicitness = when (s.explicitStatus?.lowercase()) {
                "explicit" -> Explicitness.EXPLICIT
                "clean" -> Explicitness.CLEAN
                else -> Explicitness.UNKNOWN
            },
            releaseDate = s.year?.takeIf { it > 0 }?.let { PartialDate(it) },
            discNumber = s.discNumber,
            trackNumber = s.track,
            advertisedQualities = listOf(quality(s)),
            availability = if (s.isVideo) Availability.Unavailable("Not a song") else Availability.Playable,
            routes = setOf(PlaybackRoute.DIRECT),
        )
    }

    /** What the server says the file is. A claim: the decoder measures the truth (D-24). */
    fun quality(s: SongDto) = AudioQuality(
        codec = codec(s.suffix, s.contentType),
        container = s.contentType,
        bitrateKbps = s.bitRate?.takeIf { it > 0 },
        sampleRateHz = s.samplingRate?.takeIf { it > 0 },
        bitDepth = s.bitDepth?.takeIf { it > 0 },
        channels = s.channelCount?.takeIf { it > 0 },
    )

    fun album(a: AlbumDto) = AlbumSummary(
        id = AlbumId.of(source, ALBUM + a.id),
        title = a.name,
        artistDisplay = a.displayArtist ?: a.artist.orEmpty(),
        artwork = a.coverArt?.let { ArtworkRef(source, it) },
        year = a.year?.takeIf { it > 0 },
        trackCount = a.songCount,
    )

    /** An album, presented the way ONLINE opens collections. */
    fun albumAsPlaylist(a: AlbumDto) = PlaylistSummary(
        id = PlaylistId.of(source, ALBUM + a.id),
        title = a.name,
        ownerName = a.displayArtist ?: a.artist.orEmpty(),
        artwork = a.coverArt?.let { ArtworkRef(source, it) },
        trackCount = a.songCount,
        isAlbum = true,
        year = a.year?.takeIf { it > 0 },
    )

    fun playlist(p: PlaylistDto) = PlaylistSummary(
        id = PlaylistId.of(source, PLAYLIST + p.id),
        title = p.name,
        ownerName = p.owner.orEmpty(),
        artwork = p.coverArt?.let { ArtworkRef(source, it) },
        trackCount = p.songCount,
    )

    fun artist(a: ArtistDto) = ArtistSummary(
        id = ArtistId.of(source, a.id),
        name = a.name,
        artwork = a.coverArt?.let { ArtworkRef(source, it) },
    )

    companion object {
        /** Key prefixes for the shared collection space (module-private). */
        const val ALBUM = "al."
        const val PLAYLIST = "pl."

        fun codec(suffix: String?, contentType: String?): Codec? {
            val s = suffix?.lowercase()
            val type = contentType?.lowercase().orEmpty()
            return when {
                s == "flac" || "flac" in type -> Codec.FLAC
                s == "mp3" || "mpeg" in type -> Codec.MP3
                s == "opus" || "opus" in type -> Codec.OPUS
                s == "ogg" || "vorbis" in type -> Codec.VORBIS
                s == "alac" -> Codec.ALAC
                s == "aac" || "aac" in type -> Codec.AAC
                // An .m4a / audio/mp4 file may hold AAC or ALAC: claim nothing, the decoder will say.
                s == "m4a" || "mp4" in type -> null
                s == "wav" || "wav" in type -> Codec.PCM
                s == null && type.isEmpty() -> null
                else -> Codec.OTHER
            }
        }
    }
}
