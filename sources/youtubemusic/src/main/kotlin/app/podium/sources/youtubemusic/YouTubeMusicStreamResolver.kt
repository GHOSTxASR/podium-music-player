package app.podium.sources.youtubemusic

import app.podium.core.model.AudioQuality
import app.podium.core.model.Codec
import app.podium.core.model.MediaKind
import app.podium.core.model.SourceId
import app.podium.core.model.Track
import app.podium.sources.api.FacetResolution
import app.podium.sources.api.HealthOutcome
import app.podium.sources.api.MissReason
import app.podium.sources.api.PlayableMedia
import app.podium.sources.api.PlaybackTarget
import app.podium.sources.api.QualityRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Production stream resolver for YouTube Music tracks (D-48, D-97), the way BitChord does it:
 *
 * 1. **The walk** ([YouTubeStreamWalker]): the player asked as several of YouTube's own apps in
 *    turn, each stream proven to serve audio before the player gets it.
 * 2. **The failsafe**: NewPipeExtractor, when no app's stream played (refused, bot checks, the
 *    service unreachable) — its stream proven the same way. Live stations (D-90) come from it.
 *
 * A song that can't play for anyone signed out (gone, private, age-restricted) is a miss at once;
 * one whose every stream was refused is a miss "not streamable" (Now Playing says it can't play
 * right now); bot checks everywhere count as the service asking for fewer requests.
 *
 * An extraction is real work (the first one of a run is the slowest), so it runs in the resolver's
 * own scope: a caller that stops waiting doesn't throw the work away, and callers asking for the
 * same song share one extraction. At most [MAX_PARALLEL] run at once (the song playing and the
 * next), and a result is kept for a few minutes, so asking again right after a timeout, a skip
 * back or a retry costs nothing. A stream that was refused is forgotten ([forget]).
 */
internal open class YouTubeMusicStreamResolver(
    private val sourceId: SourceId,
    private val downloader: YouTubeMusicDownloader,
    private val region: () -> String = { "US" },
    private val now: () -> Long = System::currentTimeMillis,
    /** The app that last served, kept across restarts (D-98). */
    memory: StreamWalkMemory = StreamWalkMemory.None,
    /** The walk; null leaves NewPipe alone (tests that script extraction themselves). */
    private val walker: YouTubeStreamWalker? = YouTubeStreamWalker(OkHttpStreamHttp(), region, now, memory = memory),
) {
    private val initialized = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val permits = Semaphore(MAX_PARALLEL)

    private val inFlight = HashMap<String, Deferred<FacetResolution>>()
    private val recent = object : LinkedHashMap<String, Pair<FacetResolution.Resolved, Long>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<FacetResolution.Resolved, Long>>) = size > RECENT_LIMIT
    }

    private fun ensureInitialized() {
        if (initialized.compareAndSet(false, true)) {
            val countryCode = region().takeIf { it.length == 2 }?.uppercase() ?: "US"
            // Localization is (language, country); the content country decides regional availability.
            NewPipe.init(downloader, Localization("en", countryCode), ContentCountry(countryCode))
        }
    }

    /**
     * Resolves a Podium track into a [FacetResolution.Resolved] holding a [PlaybackTarget.DirectStream],
     * or a clean [FacetResolution.Miss] / [FacetResolution.Failed] upon failure.
     */
    open suspend fun resolve(track: Track, quality: QualityRequest): FacetResolution {
        if (track.source.sourceId != sourceId) return FacetResolution.Miss(MissReason.NO_SOURCE)
        val videoId = track.source.providerKey
        if (videoId.isBlank()) return FacetResolution.Miss(MissReason.NOT_FOUND)

        val key = track.id.value
        val shared = synchronized(inFlight) {
            recent[key]?.let { (resolution, until) ->
                if (now() < until) return resolution
                recent.remove(key)
            }
            inFlight[key] ?: scope.async {
                var result: FacetResolution? = null
                try {
                    permits.withPermit { extract(track, videoId, quality) }.also { result = it }
                } finally {
                    // Only this job can be in flight for the key, so it's safe to clear by key.
                    synchronized(inFlight) {
                        inFlight.remove(key)
                        (result as? FacetResolution.Resolved)?.let { recent[key] = it to keepUntil(it) }
                    }
                }
            }.also { inFlight[key] = it }
        }
        return shared.await()
    }

    /**
     * The stream handed out for [track] was refused: the next request extracts afresh, and the app
     * that stream came from is asked last for now (D-97).
     */
    fun forget(track: Track) {
        val refused = synchronized(inFlight) { recent.remove(track.id.value) }?.first
        val uri = ((refused?.target as? PlaybackTarget.DirectStream)?.media?.uri)
        val identity = uri?.let(PlayerIdentities::of)
        identity?.let { walker?.demote(it.key) }
        StreamWalkDebug.note { "forget ${track.source.providerKey}: refused while playing (${identity?.key ?: "app unknown"})" }
    }

    /** Kept a few minutes, and never past the stream's own expiry (less a margin). */
    private fun keepUntil(result: FacetResolution.Resolved): Long {
        val expires = (result.target as? PlaybackTarget.DirectStream)?.media?.expiresAtMillis
        val byAge = now() + RECENT_MILLIS
        return if (expires == null) byAge else minOf(byAge, expires - PlayableMedia.EXPIRY_MARGIN_MILLIS * 5)
    }

    /** One extraction, blocking (run on the resolver's IO scope). Open for tests. */
    protected open fun extract(track: Track, videoId: String, quality: QualityRequest): FacetResolution {
        val walk = walker?.takeIf { track.kind != MediaKind.LIVE } ?: return failsafe(track, videoId, quality)
        val walked = runBlocking { walk.walk(videoId) }
        when (walked) {
            is YouTubeStreamWalker.Result.Found -> return resolution(track, walked.stream)
            is YouTubeStreamWalker.Result.Unplayable -> return FacetResolution.Miss(walked.reason)
            is YouTubeStreamWalker.Result.Limited ->
                return FacetResolution.Failed(HealthOutcome.RATE_LIMIT, "YouTube asked for fewer requests", walked.retryAfterMillis)
            else -> Unit
        }
        // The failsafe: NewPipe, its stream proven like the walk's before the player gets it.
        val started = now()
        val fallback = failsafe(track, videoId, quality)
        if (fallback is FacetResolution.Resolved) {
            val media = (fallback.target as? PlaybackTarget.DirectStream)?.media
            val serves = media != null && runBlocking { walk.serves(media.uri, media.headers["User-Agent"]) }
            StreamWalkDebug.note { "failsafe $videoId: a stream, ${if (serves) "serves" else "refused"} in ${now() - started} ms" }
            if (serves) return fallback
        } else {
            StreamWalkDebug.note { "failsafe $videoId: $fallback in ${now() - started} ms" }
        }
        return when (walked) {
            YouTubeStreamWalker.Result.Refused -> FacetResolution.Miss(MissReason.NOT_STREAMABLE)
            YouTubeStreamWalker.Result.BotCheck ->
                FacetResolution.Failed(HealthOutcome.RATE_LIMIT, "YouTube asked to confirm this isn't a bot", BOT_CHECK_REST_MS)
            else -> fallback.takeIf { it !is FacetResolution.Resolved } ?: FacetResolution.Miss(MissReason.NOT_STREAMABLE)
        }
    }

    /** A stream the walk proved, as the player will open it: asked as the app it was handed to. */
    private fun resolution(track: Track, stream: YouTubeStreamWalker.Stream): FacetResolution.Resolved {
        val opus = stream.codec == Codec.OPUS
        val media = PlayableMedia(
            uri = stream.url,
            mimeType = stream.mimeType,
            claimedQuality = AudioQuality(
                codec = stream.codec,
                container = if (opus) "webm" else if (stream.codec == Codec.AAC) "m4a" else null,
                bitrateKbps = stream.bitrateKbps,
                sampleRateHz = stream.sampleRateHz,
                channels = stream.channels,
            ),
            durationMs = stream.durationMs ?: track.durationMs,
            expiresAtMillis = stream.expiresAtMillis,
            headers = mapOf("User-Agent" to stream.userAgent),
            sourceId = sourceId,
            cacheKey = "${track.id.value}|${stream.itag}",
        )
        return FacetResolution.Resolved(PlaybackTarget.DirectStream(trackId = track.id, media = media))
    }

    /** The order the walk asks YouTube's apps in now (the last to serve a stream first). */
    internal fun walkOrder(): List<String> = walker?.order().orEmpty()

    /** NewPipeExtractor: the failsafe for songs, and live stations' source (D-90). Open for tests. */
    protected open fun failsafe(track: Track, videoId: String, quality: QualityRequest): FacetResolution {
        ensureInitialized()

        val normalizedUrl = try {
            val canonicalId = if (videoId.startsWith("http://") || videoId.startsWith("https://")) {
                YoutubeStreamLinkHandlerFactory.getInstance().getId(videoId)
            } else {
                videoId
            }
            YoutubeStreamLinkHandlerFactory.getInstance().getUrl(canonicalId)
        } catch (e: Exception) {
            return FacetResolution.Miss(MissReason.NOT_FOUND)
        }

        return try {
            val info = StreamInfo.getInfo(normalizedUrl)
            if (track.kind == MediaKind.LIVE) return live(track, info.hlsUrl)
            val audioStreams = info.audioStreams.orEmpty()
            if (audioStreams.isEmpty()) return FacetResolution.Miss(MissReason.NOT_STREAMABLE)

            val selectedStream = selectStream(audioStreams, quality)
                ?: return FacetResolution.Miss(MissReason.NOT_STREAMABLE)

            val streamUrl = selectedStream.content
            val mimeType = when {
                selectedStream.format == MediaFormat.WEBMA_OPUS || selectedStream.itag == 251 -> "audio/webm"
                selectedStream.format == MediaFormat.M4A || selectedStream.itag == 140 -> "audio/mp4"
                else -> selectedStream.format?.mimeType ?: "audio/*"
            }

            val media = PlayableMedia(
                uri = streamUrl,
                mimeType = mimeType,
                claimedQuality = mapQuality(selectedStream),
                durationMs = info.duration.takeIf { it > 0 }?.let { it * 1000L } ?: track.durationMs,
                expiresAtMillis = extractExpiryMillis(streamUrl),
                // Asked as the app the stream was handed to, when it says (D-97).
                headers = PlayerIdentities.of(streamUrl)?.let { mapOf("User-Agent" to it.userAgent) }.orEmpty(),
                sourceId = sourceId,
                cacheKey = "${track.id.value}|${selectedStream.itag}",
            )

            FacetResolution.Resolved(PlaybackTarget.DirectStream(trackId = track.id, media = media))
        } catch (e: AgeRestrictedContentException) {
            FacetResolution.Miss(MissReason.NOT_PERMITTED)
        } catch (e: YoutubeMusicPremiumContentException) {
            FacetResolution.Miss(MissReason.NOT_PERMITTED)
        } catch (e: PrivateContentException) {
            FacetResolution.Miss(MissReason.NOT_FOUND)
        } catch (e: ContentNotAvailableException) {
            FacetResolution.Miss(MissReason.NOT_FOUND)
        } catch (e: ReCaptchaException) {
            FacetResolution.Failed(HealthOutcome.RATE_LIMIT, "YouTube rate limit challenge")
        } catch (e: IOException) {
            FacetResolution.Failed(HealthOutcome.NETWORK_FAILURE, e.javaClass.simpleName)
        } catch (e: ExtractionException) {
            FacetResolution.Miss(MissReason.NOT_STREAMABLE)
        } catch (e: Throwable) {
            FacetResolution.Failed(HealthOutcome.UNKNOWN, e.javaClass.simpleName)
        }
    }

    /**
     * A live station (D-90): the stream's HLS master playlist, read for its audio-only rendition
     * ([HlsAudio]). The player plays that playlist as live HLS; its address lasts hours, and is
     * asked for afresh like a song's when it runs out.
     */
    private fun live(track: Track, master: String?): FacetResolution {
        if (master.isNullOrBlank()) return FacetResolution.Miss(MissReason.NOT_STREAMABLE)
        val playlist = downloader.get(master)
        if (playlist.responseCode() !in 200..299) return FacetResolution.Failed(HealthOutcome.NETWORK_FAILURE, "HTTP ${playlist.responseCode()}")
        val pick = HlsAudio.pick(playlist.responseBody()) ?: return FacetResolution.Miss(MissReason.NOT_STREAMABLE)
        val media = PlayableMedia(
            uri = pick.uri,
            mimeType = HLS_MIME,
            claimedQuality = if (pick.itag == 140) AudioQuality(codec = Codec.AAC, container = "hls", bitrateKbps = 128, sampleRateHz = 44_100, channels = 2) else null,
            durationMs = null,
            expiresAtMillis = HlsAudio.expiresAtMillis(pick.uri) ?: HlsAudio.expiresAtMillis(master),
            headers = emptyMap(),
            sourceId = sourceId,
            cacheKey = "${track.id.value}|live",
        )
        return FacetResolution.Resolved(PlaybackTarget.DirectStream(trackId = track.id, media = media))
    }

    /**
     * Deterministic stream selection policy:
     * 1. Prioritize WebM Opus (itag 251, ~160 kbps) for highest fidelity on Android.
     * 2. Fallback to M4A AAC (itag 140, ~128 kbps).
     * 3. Fallback to highest average bitrate audio stream available.
     */
    internal fun selectStream(streams: List<AudioStream>, quality: QualityRequest): AudioStream? {
        val validStreams = streams.filter { !it.content.isNullOrBlank() }
        if (validStreams.isEmpty()) return null

        val opusStreams = validStreams.filter { it.format == MediaFormat.WEBMA_OPUS || it.itag == 251 }
        if (opusStreams.isNotEmpty()) {
            return opusStreams.maxByOrNull { it.averageBitrate } ?: opusStreams.first()
        }

        val aacStreams = validStreams.filter { it.format == MediaFormat.M4A || it.itag == 140 }
        if (aacStreams.isNotEmpty()) {
            return aacStreams.maxByOrNull { it.averageBitrate } ?: aacStreams.first()
        }

        return validStreams.maxByOrNull { it.averageBitrate } ?: validStreams.first()
    }

    internal fun extractExpiryMillis(url: String): Long? {
        val query = url.substringAfter('?', "")
        if (query.isEmpty()) return null
        val expireParam = query.split('&')
            .firstOrNull { it.startsWith("expire=") }
            ?.substringAfter("expire=")
            ?.toLongOrNull()
            ?: return null

        return expireParam * 1000L
    }

    private fun mapQuality(stream: AudioStream): AudioQuality {
        val isOpus = stream.format == MediaFormat.WEBMA_OPUS || stream.itag == 251
        val codec = if (isOpus) Codec.OPUS else Codec.AAC
        val container = if (isOpus) "webm" else "m4a"
        val bitrate = kbpsOf(stream.averageBitrate, stream.bitrate) ?: if (isOpus) 160 else 128

        return AudioQuality(
            codec = codec,
            container = container,
            bitrateKbps = bitrate,
            sampleRateHz = if (isOpus) 48_000 else 44_100,
            channels = 2,
        )
    }

    internal companion object {
        /** The song playing and the next one; more would only slow both down. */
        const val MAX_PARALLEL = 2
        const val RECENT_MILLIS = 5 * 60_000L
        const val RECENT_LIMIT = 64

        /** How long the service rests after asking every app to confirm it isn't a bot. */
        const val BOT_CHECK_REST_MS = 30_000L

        /**
         * The stream's bitrate in kbps: the extractor's average bitrate is already in kbps; its
         * plain bitrate comes from the stream's metadata in bits per second.
         */
        fun kbpsOf(averageKbps: Int, bitsPerSecond: Int): Int? =
            averageKbps.takeIf { it > 0 } ?: bitsPerSecond.takeIf { it > 0 }?.let { (it + 500) / 1000 }
    }
}

/** HLS playlists' media type. */
internal const val HLS_MIME = "application/x-mpegURL"
