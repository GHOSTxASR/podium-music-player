package app.podium.sources.local

import app.podium.core.model.AlbumId
import app.podium.core.model.AlbumRef
import app.podium.core.model.ArtistCredit
import app.podium.core.model.ArtistId
import app.podium.core.model.ArtworkRef
import app.podium.core.model.AudioQuality
import app.podium.core.model.Availability
import app.podium.core.model.Codec
import app.podium.core.model.PartialDate
import app.podium.core.model.PlaybackRoute
import app.podium.core.model.SourceId
import app.podium.core.model.SourceRef
import app.podium.core.model.Track
import app.podium.core.model.TrackId

/** One MediaStore audio row, as plain data (no Android types — mapping is unit-testable). */
data class MediaStoreRow(
    val id: Long,
    val title: String?,
    val artist: String?,
    val album: String?,
    val albumId: Long,
    val artistId: Long,
    val albumArtist: String?,
    val durationMs: Long?,
    /** Legacy TRACK column: often disc * 1000 + track. */
    val trackColumn: Int?,
    /** API 30+: "3" or "3/12". */
    val cdTrackNumber: String?,
    /** API 30+: "1" or "1/2". */
    val discNumber: String?,
    val year: Int?,
    val mimeType: String?,
    val bitrate: Int?,
    val sizeBytes: Long?,
)

/** MediaStore row → provider-neutral Track. Everything MediaStore reports is treated as a *claim*. */
object LocalTrackMapper {
    val SOURCE_ID = SourceId("local")

    private const val UNKNOWN = "<unknown>"

    fun toTrack(row: MediaStoreRow): Track {
        val artist = row.artist?.takeUnless { it.isBlank() || it == UNKNOWN } ?: "Unknown artist"
        val albumTitle = row.album?.takeUnless { it.isBlank() || it == UNKNOWN }
        val title = row.title?.takeUnless { it.isBlank() } ?: "Untitled"
        val key = row.id.toString()
        return Track(
            id = TrackId.of(SOURCE_ID, key),
            source = SourceRef(SOURCE_ID, key, providerUri = null),
            title = title,
            artists = listOf(ArtistCredit(artist, id = ArtistId.of(SOURCE_ID, row.artistId.toString()))),
            artistDisplay = artist,
            album = albumTitle?.let {
                AlbumRef(it, AlbumId.of(SOURCE_ID, row.albumId.toString()), row.albumArtist?.takeUnless { a -> a.isBlank() || a == UNKNOWN })
            },
            durationMs = row.durationMs?.takeIf { it > 0 },
            artwork = ArtworkRef(SOURCE_ID, artworkKey(row.albumId, row.id)),
            releaseDate = row.year?.takeIf { it in 1000..9999 }?.let { PartialDate(it) },
            trackNumber = trackNumber(row),
            discNumber = discNumber(row),
            advertisedQualities = listOfNotNull(claimedQuality(row.mimeType, row.bitrate)),
            availability = Availability.Playable,
            routes = setOf(PlaybackRoute.DIRECT),
        )
    }

    /** Artwork for an album, extracted from one of its audio files. */
    fun artworkKey(albumId: Long, audioId: Long) = "album/$albumId/$audioId"

    fun parseArtworkKey(key: String): Pair<Long, Long>? {
        val parts = key.split('/')
        if (parts.size != 3 || parts[0] != "album") return null
        return parts[1].toLongOrNull()?.let { a -> parts[2].toLongOrNull()?.let { a to it } }
    }

    fun trackNumber(row: MediaStoreRow): Int? =
        row.cdTrackNumber?.substringBefore('/')?.trim()?.toIntOrNull()?.takeIf { it > 0 }
            ?: row.trackColumn?.let { it % 1000 }?.takeIf { it > 0 }

    fun discNumber(row: MediaStoreRow): Int? =
        row.discNumber?.substringBefore('/')?.trim()?.toIntOrNull()?.takeIf { it > 0 }
            ?: row.trackColumn?.let { it / 1000 }?.takeIf { it > 0 }

    /**
     * What the file's MIME type and MediaStore's bitrate *claim*. Containers that can hold more than
     * one codec (MP4: AAC or ALAC; Ogg: Vorbis or Opus) yield no codec claim rather than a guess.
     */
    fun claimedQuality(mimeType: String?, bitrateBps: Int?): AudioQuality? {
        val codec = when (mimeType?.lowercase()) {
            "audio/flac", "audio/x-flac" -> Codec.FLAC
            "audio/mpeg", "audio/mp3" -> Codec.MP3
            "audio/aac", "audio/aacp" -> Codec.AAC
            "audio/opus" -> Codec.OPUS
            "audio/wav", "audio/x-wav", "audio/wave" -> Codec.PCM
            "audio/alac" -> Codec.ALAC
            else -> null
        }
        val kbps = bitrateBps?.takeIf { it > 0 }?.let { it / 1000 }
        if (codec == null && kbps == null) return null
        return AudioQuality(codec = codec, container = mimeType, bitrateKbps = kbps)
    }
}
