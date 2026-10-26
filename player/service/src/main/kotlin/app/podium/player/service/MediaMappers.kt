@file:OptIn(UnstableApi::class)

package app.podium.player.service

import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import app.podium.core.model.MediaKind
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
import app.podium.core.model.SourceId
import app.podium.player.api.QueueItem
import app.podium.sources.api.HealthOutcome
import app.podium.sources.api.PlayableMedia

/**
 * How the player must open a queue item's stream (D-91): a file, or a DASH or HLS manifest. The
 * player picks the kind of source when an item is added, before its stream is known, so an item whose
 * stream turns out to be a manifest is rebuilt for it ([QueueMediaItems.withKind]) before it opens.
 */
enum class StreamKind(internal val query: String?, val mimeType: String?) {
    FILE(null, null),
    DASH("dash", MimeTypes.APPLICATION_MPD),
    HLS("hls", MimeTypes.APPLICATION_M3U8);

    companion object {
        fun of(media: PlayableMedia?): StreamKind {
            val mime = media?.mimeType?.lowercase() ?: return FILE
            return when {
                mime == MimeTypes.APPLICATION_MPD -> DASH
                mime.endsWith("mpegurl") -> HLS
                else -> FILE
            }
        }
    }
}

/** The URI ExoPlayer sees for a queue item; resolved to real media just before it is opened. */
object QueueMediaItems {
    private const val SCHEME = "podium"
    private const val HOST = "queue"
    private const val CARRY_HOST = "carry"
    private const val KIND = "as"
    private const val SOURCE = "from"

    /**
     * [kind]: the kind of stream the player must open. [servedBy]: set when an item is rebuilt for a
     * new stream from another source (D-92), so the player never mistakes it for the one it had
     * already started loading.
     */
    fun uriFor(uid: QueueUid, kind: StreamKind = StreamKind.FILE, servedBy: SourceId? = null): Uri =
        Uri.Builder().scheme(SCHEME).authority(HOST).appendPath(uid.value)
            .apply {
                kind.query?.let { appendQueryParameter(KIND, it) }
                servedBy?.let { appendQueryParameter(SOURCE, it.value) }
            }
            .build()

    fun uidOf(uri: Uri): QueueUid? =
        if (uri.scheme == SCHEME && uri.authority == HOST) uri.lastPathSegment?.let(::QueueUid) else null

    /** The source the item at [uri] was rebuilt for, if it was (D-92). */
    fun servedByOf(uri: Uri): SourceId? = uri.getQueryParameter(SOURCE)?.takeIf { it.isNotBlank() }?.let(::SourceId)

    /**
     * The stream that carries the rest of the song playing on crossfade's second player while the
     * first switches over (D-92): another copy of the same recording, from a custom source.
     */
    fun carryItem(uid: QueueUid, kind: StreamKind): MediaItem = MediaItem.Builder()
        .setMediaId(uid.value)
        .setUri(Uri.Builder().scheme(SCHEME).authority(CARRY_HOST).appendPath(uid.value).build())
        .setMimeType(kind.mimeType)
        .build()

    fun carryUidOf(uri: Uri): QueueUid? =
        if (uri.scheme == SCHEME && uri.authority == CARRY_HOST) uri.lastPathSegment?.let(::QueueUid) else null

    /** The kind of stream the item at [uri] was built to open. */
    fun kindOf(uri: Uri): StreamKind = when (uri.getQueryParameter(KIND)) {
        StreamKind.DASH.query -> StreamKind.DASH
        StreamKind.HLS.query -> StreamKind.HLS
        else -> StreamKind.FILE
    }

    /** [mediaItem] (one of the queue's, as the player holds it) rebuilt to open a [kind] stream. */
    fun withKind(mediaItem: MediaItem, kind: StreamKind, servedBy: SourceId? = null): MediaItem =
        mediaItem.buildUpon().setUri(uriFor(QueueUid(mediaItem.mediaId), kind, servedBy)).setMimeType(kind.mimeType).build()

    /** Whether the item at [uri] was built for [selection]: the same kind of stream, and the same source if it names one. */
    fun fits(uri: Uri, selection: app.podium.sources.api.resolve.Selection): Boolean =
        kindOf(uri) == StreamKind.of(selection.media) && servedByOf(uri).let { it == null || it == selection.servedBy }

    fun toMediaItem(item: QueueItem): MediaItem {
        val track = item.track
        val extras = Bundle().apply {
            putString(PodiumExtras.TRACK_ID, track.id.value)
            putString(PodiumExtras.ORIGIN, item.origin.name)
            putString(PodiumExtras.MEDIA_KIND, track.kind.name)
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
            .setMediaType(if (track.kind == MediaKind.LIVE) MediaMetadata.MEDIA_TYPE_RADIO_STATION else MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(extras)
            .build()
        // An item already resolved to a manifest (a custom source's DASH, D-91) is built for it.
        val kind = if (track.kind == MediaKind.LIVE) StreamKind.FILE else StreamKind.of(item.selection?.media)
        return MediaItem.Builder()
            .setMediaId(item.uid.value)
            .setUri(uriFor(item.uid, kind))
            .setMediaMetadata(metadata)
            .apply {
                // A live station (D-90) is a live HLS playlist: the player must know before it loads.
                if (track.kind == MediaKind.LIVE) {
                    setMimeType(MimeTypes.APPLICATION_M3U8)
                    setLiveConfiguration(MediaItem.LiveConfiguration.Builder().build())
                } else {
                    kind.mimeType?.let(::setMimeType)
                }
            }
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

/**
 * Raised by the data-source resolver when a queue item couldn't be resolved before opening: a
 * failure ([healthOutcome]) or, when that is null, a miss — why, when known ([missReason]).
 */
class PodiumResolveException(
    val healthOutcome: HealthOutcome?,
    message: String,
    val missReason: app.podium.sources.api.MissReason? = null,
) : java.io.IOException(message)

/**
 * Raised by the data-source resolver when an item built for one kind of stream resolved to another
 * (a file's source handed a DASH manifest, D-91). Not a failure: the engine rebuilds the item for
 * [kind] and it plays. Never let a source misread a stream it can't open.
 */
class PodiumReopenException(val uid: QueueUid, val kind: StreamKind) : java.io.IOException("reopen as $kind")

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
     * The engine's classification as a stable name, for the session ([PodiumExtras.LAST_ERROR]).
     * Never the class's own name: release builds rename classes, and the UI could no longer read
     * it back (issue #5: every error then read "Something went wrong").
     */
    fun kindOf(error: PodiumError): String = when (error) {
        PodiumError.Offline -> "Offline"
        is PodiumError.Network -> "Network"
        is PodiumError.Server -> "Server"
        is PodiumError.AuthRequired -> "AuthRequired"
        is PodiumError.AuthExpired -> "AuthExpired"
        is PodiumError.RateLimited -> "RateLimited"
        is PodiumError.NotFound -> "NotFound"
        is PodiumError.InvalidMedia -> "InvalidMedia"
        is PodiumError.UnsupportedFormat -> "UnsupportedFormat"
        is PodiumError.PolicyDisabled -> "PolicyDisabled"
        is PodiumError.PermissionRequired -> "PermissionRequired"
        is PodiumError.Unexpected -> "Unexpected"
        is PodiumError.Unsupported -> "Unsupported"
        is PodiumError.NotPlayable -> "NotPlayable"
        is PodiumError.UserActionRequired -> "UserActionRequired"
    }

    /**
     * The engine's own classification (published by kind, [kindOf]) back as an error, so the UI
     * says what actually happened rather than what an error code suggests.
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
        "PermissionRequired" -> PodiumError.PermissionRequired("storage")
        "Unsupported" -> PodiumError.Unsupported("stream")
        "UserActionRequired" -> PodiumError.UserActionRequired("source")
        else -> null
    }

    /** The item that has to be rebuilt for another kind of stream, when that's all [error] is. */
    fun reopenOf(error: PlaybackException): PodiumReopenException? {
        var cause: Throwable? = error.cause
        while (cause != null) {
            if (cause is PodiumReopenException) return cause
            cause = cause.cause
        }
        return null
    }

    fun classify(error: PlaybackException): PodiumError {
        var cause: Throwable? = error.cause
        while (cause != null) {
            if (cause is PodiumResolveException) return cause.healthOutcome?.let(::fromHealth) ?: fromMiss(cause.missReason)
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
        // Only reached when the engine's own classification isn't at hand: a refused stream.
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> PodiumError.NotPlayable("stream refused")
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

    /** Why no source had a stream: refused or not allowed here, the source resting, or not there. */
    private fun fromMiss(reason: app.podium.sources.api.MissReason?): PodiumError = when (reason) {
        app.podium.sources.api.MissReason.NOT_STREAMABLE -> PodiumError.NotPlayable("stream refused")
        app.podium.sources.api.MissReason.NOT_PERMITTED -> PodiumError.NotPlayable("not permitted")
        app.podium.sources.api.MissReason.SOURCE_UNAVAILABLE -> PodiumError.Server()
        else -> PodiumError.NotFound("stream")
    }
}
