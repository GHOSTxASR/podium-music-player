@file:OptIn(UnstableApi::class)

package app.podium.player.service

import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import app.podium.core.common.PodiumError
import app.podium.core.model.AudioQuality
import app.podium.core.model.Codec
import app.podium.core.model.QueueUid
import app.podium.player.api.QueueItem
import app.podium.sources.api.HealthOutcome

/** The URI ExoPlayer sees for a queue item; resolved to real media just before it is opened. */
object QueueMediaItems {
    private const val SCHEME = "podium"
    private const val HOST = "queue"

    fun uriFor(uid: QueueUid): Uri = Uri.Builder().scheme(SCHEME).authority(HOST).appendPath(uid.value).build()

    fun uidOf(uri: Uri): QueueUid? =
        if (uri.scheme == SCHEME && uri.authority == HOST) uri.lastPathSegment?.let(::QueueUid) else null

    fun toMediaItem(item: QueueItem): MediaItem {
        val track = item.track
        val extras = Bundle().apply {
            putString(PodiumExtras.TRACK_ID, track.id.value)
            putString(PodiumExtras.ORIGIN, item.origin.name)
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artistDisplay)
            .setAlbumTitle(track.album?.title)
            .setAlbumArtist(track.album?.albumArtist)
            .setArtworkUri(track.artwork?.uri?.let(Uri::parse))
            .setDurationMs(track.durationMs)
            .setTrackNumber(track.trackNumber)
            .setDiscNumber(track.discNumber)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(extras)
            .build()
        return MediaItem.Builder()
            .setMediaId(item.uid.value)
            .setUri(uriFor(item.uid))
            .setMediaMetadata(metadata)
            .build()
    }
}

/** Actual media quality, measured from the decoder's selected audio format. */
object AudioQualityMapper {

    fun fromTracks(tracks: Tracks): AudioQuality? {
        val group = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected } ?: return null
        val index = (0 until group.length).firstOrNull { group.isTrackSelected(it) } ?: return null
        return fromFormat(group.getTrackFormat(index))
    }

    fun fromFormat(format: Format): AudioQuality {
        val codec = codecOf(format)
        val bitDepth = bitDepthOf(format.pcmEncoding)
        val sampleRate = format.sampleRate.takeIf { it != Format.NO_VALUE }
        val channels = format.channelCount.takeIf { it != Format.NO_VALUE }
        val reportedBitrate = listOf(format.averageBitrate, format.bitrate, format.peakBitrate)
            .firstOrNull { it != Format.NO_VALUE }?.let { it / 1000 }
        val bitrate = reportedBitrate ?: if (codec == Codec.PCM && sampleRate != null && channels != null && bitDepth != null) {
            sampleRate * channels * bitDepth / 1000
        } else null
        return AudioQuality(
            codec = codec,
            container = format.containerMimeType,
            bitrateKbps = bitrate,
            sampleRateHz = sampleRate,
            bitDepth = bitDepth?.takeIf { codec?.isLossless == true },
            channels = channels,
        )
    }

    fun codecOf(format: Format): Codec? = when (format.sampleMimeType) {
        MimeTypes.AUDIO_FLAC -> Codec.FLAC
        MimeTypes.AUDIO_ALAC -> Codec.ALAC
        MimeTypes.AUDIO_RAW, MimeTypes.AUDIO_WAV -> Codec.PCM
        MimeTypes.AUDIO_AAC -> Codec.AAC
        MimeTypes.AUDIO_OPUS -> Codec.OPUS
        MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_MPEG_L1, MimeTypes.AUDIO_MPEG_L2 -> Codec.MP3
        MimeTypes.AUDIO_VORBIS -> Codec.VORBIS
        MimeTypes.AUDIO_AC3 -> Codec.AC3
        MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> Codec.EAC3
        null -> null
        else -> Codec.OTHER
    }

    private fun bitDepthOf(pcmEncoding: Int): Int? = when (pcmEncoding) {
        C.ENCODING_PCM_8BIT -> 8
        C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 16
        C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 24
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT -> 32
        else -> null
    }
}

/** Raised by the data-source resolver when a queue item couldn't be resolved before opening. */
class PodiumResolveException(val healthOutcome: HealthOutcome?, message: String) : java.io.IOException(message)

/** Maps player errors to Podium's classified errors — raw exception text never reaches the UI. */
object PlaybackErrors {
    /**
     * The server refused the stream URL (401/403/404/410) — for a signed, expiring URL that means
     * it went stale or was revoked, and the same source can be asked for a fresh one.
     */
    fun isRefusedStream(error: PlaybackException): Boolean {
        var cause: Throwable? = error.cause
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException) return cause.responseCode in REFUSED_CODES
            cause = cause.cause
        }
        return false
    }

    private val REFUSED_CODES = setOf(401, 403, 404, 410)

    /**
     * The engine's own classification (published by kind, [PodiumExtras.LAST_ERROR]) back as an
     * error, so the UI says what actually happened rather than what an error code suggests.
     */
    fun fromKind(kind: String?): PodiumError? = when (kind) {
        "Offline" -> PodiumError.Offline
        "Network" -> PodiumError.Network()
        "Server" -> PodiumError.Server()
        "AuthRequired" -> PodiumError.AuthRequired()
        "AuthExpired" -> PodiumError.AuthExpired()
        "RateLimited" -> PodiumError.RateLimited()
        "NotFound" -> PodiumError.NotFound("stream")
        "InvalidMedia" -> PodiumError.InvalidMedia()
        "UnsupportedFormat" -> PodiumError.UnsupportedFormat()
        "PolicyDisabled" -> PodiumError.PolicyDisabled()
        "NotPlayable" -> PodiumError.NotPlayable("stream")
        "Unexpected" -> PodiumError.Unexpected()
        else -> null
    }

    fun classify(error: PlaybackException): PodiumError {
        var cause: Throwable? = error.cause
        while (cause != null) {
            if (cause is PodiumResolveException) return fromHealth(cause.healthOutcome)
            if (cause is HttpDataSource.InvalidResponseCodeException) {
                return when (cause.responseCode) {
                    401, 403 -> PodiumError.AuthRequired()
                    404, 410 -> PodiumError.NotFound("stream")
                    429 -> PodiumError.RateLimited()
                    else -> PodiumError.Server(cause.responseCode)
                }
            }
            cause = cause.cause
        }
        return fromCode(error.errorCode)
    }

    fun fromCode(code: Int): PodiumError = when (code) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> PodiumError.Network()
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> PodiumError.NotFound("file")
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> PodiumError.PermissionRequired("storage")
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_DECODING_FAILED -> PodiumError.InvalidMedia()
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> PodiumError.UnsupportedFormat()
        else -> PodiumError.Unexpected("code $code")
    }

    /** Which health outcome a player error means for the source that served the media. */
    fun healthOutcomeOf(error: PodiumError): HealthOutcome = when (error) {
        is PodiumError.Network, PodiumError.Offline -> HealthOutcome.NETWORK_FAILURE
        is PodiumError.AuthRequired -> HealthOutcome.AUTH_FAILURE
        is PodiumError.RateLimited -> HealthOutcome.RATE_LIMIT
        is PodiumError.Server -> HealthOutcome.SERVER_ERROR
        is PodiumError.InvalidMedia, is PodiumError.UnsupportedFormat -> HealthOutcome.INVALID_MEDIA
        is PodiumError.NotFound -> HealthOutcome.MISS
        is PodiumError.PolicyDisabled -> HealthOutcome.POLICY_DISABLED
        else -> HealthOutcome.UNKNOWN
    }

    private fun fromHealth(outcome: HealthOutcome?): PodiumError = when (outcome) {
        HealthOutcome.NETWORK_FAILURE -> PodiumError.Network()
        HealthOutcome.AUTH_FAILURE -> PodiumError.AuthRequired()
        HealthOutcome.RATE_LIMIT -> PodiumError.RateLimited()
        HealthOutcome.SERVER_ERROR -> PodiumError.Server()
        HealthOutcome.INVALID_MEDIA -> PodiumError.InvalidMedia()
        HealthOutcome.POLICY_DISABLED -> PodiumError.PolicyDisabled()
        null, HealthOutcome.MISS -> PodiumError.NotFound("stream")
        else -> PodiumError.Unexpected()
    }
}
