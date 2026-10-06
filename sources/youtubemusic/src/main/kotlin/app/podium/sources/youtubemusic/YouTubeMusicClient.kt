package app.podium.sources.youtubemusic

import app.podium.core.common.Outcome
import app.podium.core.common.PodiumError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.URLEncoder
import java.net.UnknownHostException

/**
 * The YouTube Music web catalogue (D-20 Y1, `Basis.UNOFFICIAL_API`): the same JSON endpoints the
 * music.youtube.com page uses for browsing — search, browse, the up-next/radio list, likes and the
 * account menu. It never asks for streams: there is no player call here, and nothing that would
 * unlock media (ADR-013, CLAUDE.md). Playback belongs to the official app (YOUTUBE_MUSIC_ARCHITECTURE §8).
 *
 * The client identifies as the music web client, at the version the page itself reports (read from
 * the page at most every few hours), with the device's own browser user agent.
 */
internal class YouTubeMusicClient(
    private val transport: HttpTransport,
    private val userAgent: () -> String,
    private val session: () -> WebSession?,
    private val now: () -> Long = System::currentTimeMillis,
    private val language: String = "en",
    private val region: () -> String = { "US" },
) {
    private data class PageConfig(val clientVersion: String, val visitorData: String?, val loadedAt: Long)

    private val configLock = Mutex()
    @Volatile private var config: PageConfig? = null

    /** A continuation as the catalogue hands it over: the newer style travels in the body. */
    data class Continuation(val token: String, val inBody: Boolean)

    // --- Endpoints -----------------------------------------------------------------------------------

    suspend fun search(query: String, params: String? = null, continuation: Continuation? = null): Outcome<JsonObject> =
        call("search", continuation) {
            if (continuation == null) {
                put("query", query)
                params?.let { put("params", it) }
            }
        }

    suspend fun browse(browseId: String, params: String? = null, continuation: Continuation? = null): Outcome<JsonObject> =
        call("browse", continuation) {
            if (continuation == null) {
                put("browseId", browseId)
                params?.let { put("params", it) }
            }
        }

    /** The watch-next list: a song's details and the queue (or radio) that follows it. */
    suspend fun next(videoId: String?, playlistId: String?, params: String? = null, continuation: Continuation? = null): Outcome<JsonObject> =
        call("next", continuation) {
            if (continuation == null) {
                videoId?.let { put("videoId", it) }
                playlistId?.let { put("playlistId", it) }
                params?.let { put("params", it) }
                put("isAudioOnly", true)
                put("enablePersistentPlaylistPanel", true)
            }
        }

    suspend fun like(videoId: String, liked: Boolean): Outcome<JsonObject> =
        call(if (liked) "like/like" else "like/removelike", null) {
            putJsonObject("target") { put("videoId", videoId) }
        }

    suspend fun accountMenu(): Outcome<JsonObject> = call("account/account_menu", null) {}

    /** Artwork bytes from the catalogue's image hosts. No session goes with these. */
    suspend fun image(url: String): ByteArray? = try {
        val r = transport.send("GET", url, mapOf("User-Agent" to userAgent()), null)
        r.body.takeIf { r.code in 200..299 && it.isNotEmpty() }
    } catch (e: CancellationException) {
        throw e
    } catch (_: IOException) {
        null
    }

    // --- Requests ------------------------------------------------------------------------------------

    private suspend fun call(
        endpoint: String,
        continuation: Continuation?,
        fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ): Outcome<JsonObject> {
        val first = attempt(endpoint, continuation, fields)
        // A 400 usually means the page has moved on to a newer client version: learn it, try once more.
        if (first is Outcome.Failure && first.error == STALE_CLIENT) {
            config = null
            return attempt(endpoint, continuation, fields).let { if (it is Outcome.Failure && it.error == STALE_CLIENT) Outcome.Failure(PodiumError.Server(400)) else it }
        }
        return first
    }

    private suspend fun attempt(
        endpoint: String,
        continuation: Continuation?,
        fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ): Outcome<JsonObject> {
        val cfg = pageConfig()
        val signedIn = session()
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", CLIENT_NAME)
                    put("clientVersion", cfg.clientVersion)
                    put("hl", language)
                    put("gl", region())
                    cfg.visitorData?.let { put("visitorData", it) }
                }
                putJsonObject("user") {}
            }
            if (continuation?.inBody == true) put("continuation", continuation.token)
            fields()
        }
        val url = buildString {
            append(API).append(endpoint).append("?prettyPrint=false")
            if (continuation != null && !continuation.inBody) {
                val t = URLEncoder.encode(continuation.token, "UTF-8")
                append("&ctoken=").append(t).append("&continuation=").append(t).append("&type=next")
            }
        }
        val headers = buildMap {
            put("Content-Type", "application/json")
            put("Accept", "application/json")
            put("Accept-Language", language)
            put("User-Agent", userAgent())
            put("Origin", ORIGIN)
            put("X-Origin", ORIGIN)
            put("X-Youtube-Client-Name", CLIENT_ID)
            put("X-Youtube-Client-Version", cfg.clientVersion)
            cfg.visitorData?.let { put("X-Goog-Visitor-Id", it) }
            if (signedIn != null) {
                put("Cookie", signedIn.cookieHeader)
                signedIn.authorization(now() / 1000, ORIGIN)?.let { put("Authorization", it) }
                put("X-Goog-AuthUser", "0")
            }
        }
        val response = try {
            transport.send("POST", url, headers, body.toString().toByteArray(Charsets.UTF_8))
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return Outcome.Failure(if (e is UnknownHostException || e.message == "UnknownHostException") PodiumError.Offline else PodiumError.Network())
        }
        return interpret(response, signedIn != null)
    }

    private fun interpret(response: HttpResponse, signedIn: Boolean): Outcome<JsonObject> {
        when (response.code) {
            in 200..299 -> Unit
            400 -> return Outcome.Failure(STALE_CLIENT)
            401 -> return Outcome.Failure(if (signedIn) PodiumError.AuthExpired() else PodiumError.AuthRequired())
            403 -> return Outcome.Failure(if (signedIn) PodiumError.AuthExpired() else PodiumError.Server(403))
            404 -> return Outcome.Failure(PodiumError.NotFound("page"))
            429 -> return Outcome.Failure(PodiumError.RateLimited(response.retryAfterSeconds?.times(1000)))
            in 500..599 -> return Outcome.Failure(PodiumError.Server(response.code))
            else -> return Outcome.Failure(PodiumError.Server(response.code))
        }
        val json = parseJson(response.body) ?: return Outcome.Failure(PodiumError.Server(null))
        // An answer that says it isn't signed in, to a signed-in request: the session has ended.
        if (signedIn && loggedInFlag(json) == "0") return Outcome.Failure(PodiumError.AuthExpired("signed out by the service"))
        return Outcome.Success(json)
    }

    private fun loggedInFlag(json: JsonObject): String? =
        json.at("responseContext", "serviceTrackingParams").arr
            .flatMap { it.at("params").arr }
            .firstOrNull { it.at("key").str == "logged_in" }
            ?.at("value").str

    // --- The page's own configuration ------------------------------------------------------------------

    private suspend fun pageConfig(): PageConfig {
        config?.takeIf { now() - it.loadedAt < CONFIG_TTL_MS }?.let { return it }
        return configLock.withLock {
            config?.takeIf { now() - it.loadedAt < CONFIG_TTL_MS }?.let { return@withLock it }
            val loaded = loadPageConfig() ?: PageConfig(FALLBACK_CLIENT_VERSION, null, now() - CONFIG_TTL_MS + CONFIG_RETRY_MS)
            config = loaded
            loaded
        }
    }

    private suspend fun loadPageConfig(): PageConfig? {
        val page = try {
            transport.send("GET", "$ORIGIN/", mapOf("User-Agent" to userAgent(), "Accept-Language" to language), null)
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            return null
        }
        if (page.code !in 200..299) return null
        return PageConfigParser.parse(page.body.toString(Charsets.UTF_8))?.let { (version, visitor) -> PageConfig(version, visitor, now()) }
    }

    companion object {
        const val ORIGIN = "https://music.youtube.com"
        private const val API = "$ORIGIN/youtubei/v1/"
        private const val CLIENT_NAME = "WEB_REMIX"
        private const val CLIENT_ID = "67"

        /** Used only until the page says otherwise (and when it can't be read). */
        const val FALLBACK_CLIENT_VERSION = "1.20261001.01.00"
        private const val CONFIG_TTL_MS = 6 * 60 * 60 * 1000L
        private const val CONFIG_RETRY_MS = 5 * 60 * 1000L

        private val STALE_CLIENT = PodiumError.Server(-400)

        private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

        fun parseJson(bytes: ByteArray): JsonObject? =
            runCatching { lenient.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull()
    }
}

/** Reads the client version and visitor id the music page was served with. */
internal object PageConfigParser {
    private val version = Regex("\"INNERTUBE_CLIENT_VERSION\"\\s*:\\s*\"([0-9][0-9.]{4,40})\"")
    private val visitor = Regex("\"VISITOR_DATA\"\\s*:\\s*\"([A-Za-z0-9%_=\\-]{8,200})\"")

    fun parse(html: String): Pair<String, String?>? {
        val v = version.find(html)?.groupValues?.get(1) ?: return null
        return v to visitor.find(html)?.groupValues?.get(1)
    }
}

/** For tests and parsers: a JSON string literal element. */
internal fun jsonString(value: String): JsonElement = JsonPrimitive(value)
