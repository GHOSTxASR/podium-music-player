package app.podium.poc

import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.util.concurrent.atomic.AtomicBoolean

data class ExtractedAudioTrack(
    val id: String,
    val title: String,
    val uploader: String,
    val durationSeconds: Long,
    val thumbnailUrl: String?,
    val bestAudioStreamUrl: String,
    val format: String,
    val averageBitrate: Int,
    val itag: Int,
    val expiresAtEpochSeconds: Long?,
    val hasPoToken: Boolean,
    val hasNParameter: Boolean,
    val allAudioStreamsCount: Int
)

object PocExtractor {

    private val initialized = AtomicBoolean(false)

    fun init(downloader: PocDownloader = PocDownloader.getInstance()) {
        if (initialized.compareAndSet(false, true)) {
            NewPipe.init(downloader, Localization("US", "en"))
        }
    }

    /**
     * Resolves a track ID (e.g., "kJQP7kiw5Fk") or full YouTube Music URL into a standard YouTube URL.
     */
    fun normalizeUrl(input: String): String {
        return if (input.startsWith("http://") || input.startsWith("https://")) {
            val videoId = YoutubeStreamLinkHandlerFactory.getInstance().getId(input)
            YoutubeStreamLinkHandlerFactory.getInstance().getUrl(videoId)
        } else {
            // Bare video ID
            YoutubeStreamLinkHandlerFactory.getInstance().getUrl(input)
        }
    }

    /**
     * Extracts track metadata and playable audio streams for the given track identifier.
     */
    fun extract(trackIdentifier: String): ExtractedAudioTrack {
        init()
        val normalizedUrl = normalizeUrl(trackIdentifier)
        val info = StreamInfo.getInfo(normalizedUrl)

        val audioStreams = info.audioStreams.orEmpty()
        if (audioStreams.isEmpty()) {
            throw IllegalStateException("No audio streams found for $trackIdentifier")
        }

        // Prioritize m4a/aac or opus based on quality/compatibility
        // ExoPlayer plays both natively, let's select highest bitrate audio stream
        val bestStream: AudioStream = audioStreams.maxByOrNull { it.averageBitrate }
            ?: audioStreams.first()

        val streamUrl = bestStream.content
        val queryParams = streamUrl.substringAfter('?', "")
            .split('&')
            .mapNotNull {
                val idx = it.indexOf('=')
                if (idx > 0) it.substring(0, idx) to it.substring(idx + 1) else null
            }.toMap()

        val expireParam = queryParams["expire"]
        val expiresAtSeconds = expireParam?.toLongOrNull()
        val potParam = queryParams["pot"]
        val nParam = queryParams["n"]

        val thumbnailUrl = info.thumbnails?.lastOrNull()?.url

        return ExtractedAudioTrack(
            id = info.id,
            title = info.name,
            uploader = info.uploaderName,
            durationSeconds = info.duration,
            thumbnailUrl = thumbnailUrl,
            bestAudioStreamUrl = streamUrl,
            format = bestStream.format?.name ?: "UNKNOWN",
            averageBitrate = bestStream.averageBitrate,
            itag = bestStream.itag,
            expiresAtEpochSeconds = expiresAtSeconds,
            hasPoToken = !potParam.isNullOrBlank(),
            hasNParameter = !nParam.isNullOrBlank(),
            allAudioStreamsCount = audioStreams.size
        )
    }
}
