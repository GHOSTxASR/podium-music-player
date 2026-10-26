package app.podium.sources.youtubemusic

import app.podium.core.model.Codec
import app.podium.sources.api.MissReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A song's stream, found the way BitChord finds one (D-97), so no one change on YouTube's side stops
 * every song:
 *
 * - **Several apps.** The player is asked as each of YouTube's own apps in turn ([PlayerIdentities]):
 *   each gets streams under its own rules, so when one app's are refused another's may play. The app
 *   that last served a stream is asked first.
 * - **A guest visitor.** Every request carries a guest visitor id, as the apps' own do. When YouTube
 *   asks to confirm "you're not a bot", a fresh visitor id is fetched and the app asked once more.
 * - **Proven before it plays.** A stream is handed to the player only after its first two bytes came
 *   back. A refused stream (HTTP 403) is never handed over; the next format, or the next app, is
 *   tried instead. The app's own user agent goes with the stream ([Stream.userAgent]).
 * - **What can't play, and what can't play yet.** A song that is gone, private or age-restricted is a
 *   [Result.Unplayable] (asking again won't help); refused streams, bot checks and an unreachable
 *   service are worth trying again, and the caller has a failsafe for them (NewPipe).
 *
 * Addresses and answers never leave the walk; nothing is logged.
 */
internal class YouTubeStreamWalker(
    private val http: StreamHttp,
    private val region: () -> String = { "US" },
    private val now: () -> Long = System::currentTimeMillis,
    identities: List<PlayerIdentity> = PlayerIdentities.ALL,
    private val budgetMillis: Long = WALK_BUDGET_MS,
) {
    sealed interface Result {
        data class Found(val stream: Stream) : Result

        /** The song can't play from YouTube for anyone signed out: gone, private, age-restricted. */
        data class Unplayable(val reason: MissReason) : Result

        /** YouTube asked for fewer requests (HTTP 429): no more are sent this time. */
        data class Limited(val retryAfterMillis: Long?) : Result

        /** The song is there, but every app's stream was refused. */
        data object Refused : Result

        /** Every app was asked to confirm it isn't a bot, even with a fresh visitor id. */
        data object BotCheck : Result

        /** YouTube couldn't be reached, or answered with errors, or took too long. */
        data object Unreachable : Result
    }

    data class Stream(
        val url: String,
        val identity: String,
        val userAgent: String,
        val itag: Int,
        val mimeType: String,
        val codec: Codec?,
        val bitrateKbps: Int?,
        val sampleRateHz: Int?,
        val channels: Int?,
        val durationMs: Long?,
        val expiresAtMillis: Long?,
    )

    private sealed interface Verdict {
        data class Plays(val stream: Stream) : Verdict
        data object Refused : Verdict
        data object BotCheck : Verdict
        data class Unavailable(val reason: MissReason, val final: Boolean) : Verdict
        data class Limited(val retryAfterMillis: Long?) : Verdict
        data object Unreachable : Verdict
    }

    /** The apps in the order they're asked: the last to serve a stream first. */
    private val order = CopyOnWriteArrayList(identities)

    private val visitorLock = Mutex()
    @Volatile private var visitor: String? = null
    @Volatile private var visitorAt = 0L

    /** The order the apps are asked in now. */
    fun order(): List<String> = order.map { it.key }

    suspend fun walk(videoId: String): Result {
        val verdicts = mutableListOf<Verdict>()
        var rotated = false
        val decided = withTimeoutOrNull(budgetMillis) {
            for (identity in order.toList()) {
                var verdict = ask(identity, videoId)
                if (verdict == Verdict.BotCheck && !rotated) {
                    rotated = true
                    visitor(identity, fresh = true)
                    verdict = ask(identity, videoId)
                }
                when (verdict) {
                    is Verdict.Plays -> {
                        promote(identity.key)
                        return@withTimeoutOrNull Result.Found(verdict.stream)
                    }
                    is Verdict.Limited -> return@withTimeoutOrNull Result.Limited(verdict.retryAfterMillis)
                    is Verdict.Unavailable -> if (verdict.final) return@withTimeoutOrNull Result.Unplayable(verdict.reason)
                    else -> Unit
                }
                verdicts += verdict
            }
            null
        }
        return decided ?: conclude(verdicts)
    }

    /** The stream at [url] serves bytes when asked as [userAgent] (for the failsafe's streams). */
    suspend fun serves(url: String, userAgent: String?): Boolean = try {
        http.probe(url, userAgent?.let { mapOf("User-Agent" to it) }.orEmpty()) in 200..299
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        false
    }

    /** A stream from the app [identity] was refused while playing: that app goes last for now. */
    fun demote(identity: String) {
        val found = order.firstOrNull { it.key == identity } ?: return
        order.remove(found)
        order.add(found)
    }

    private fun promote(identity: String) {
        val found = order.firstOrNull { it.key == identity } ?: return
        if (order.firstOrNull() == found) return
        order.remove(found)
        order.add(0, found)
    }

    /** What the apps said, taken together: a refusal or a bot check beats "not available". */
    private fun conclude(verdicts: List<Verdict>): Result {
        if (verdicts.any { it == Verdict.Refused }) return Result.Refused
        if (verdicts.any { it == Verdict.BotCheck }) return Result.BotCheck
        val reached = verdicts.filter { it != Verdict.Unreachable }
        val unavailable = reached.filterIsInstance<Verdict.Unavailable>()
        if (reached.isNotEmpty() && unavailable.size == reached.size) return Result.Unplayable(unavailable.first().reason)
        return Result.Unreachable
    }

    // --- One app -----------------------------------------------------------------------------------------

    private suspend fun ask(identity: PlayerIdentity, videoId: String): Verdict {
        val visitorData = visitor(identity)
        val response = try {
            http.post(PLAYER_URL, headers(identity, visitorData), playerBody(identity, visitorData, videoId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return Verdict.Unreachable
        }
        if (response.code == 429) return Verdict.Limited(response.retryAfterSeconds?.takeIf { it > 0 }?.times(1000))
        if (response.code !in 200..299) return Verdict.Unreachable
        val json = runCatching { Json.parseToJsonElement(response.body.decodeToString()) as? JsonObject }.getOrNull()
            ?: return Verdict.Unreachable
        val playability = json.obj("playabilityStatus")
        val status = playability?.str("status")
        if (status != null && status != "OK") return unavailable(status, playability.str("reason").orEmpty())
        // An answer about another video: the app was turned away with a stand-in.
        if (json.obj("videoDetails")?.str("videoId") != videoId) return Verdict.Refused
        val candidates = streams(json, identity)
        for (candidate in candidates.take(PROBES_PER_APP)) {
            val code = try {
                http.probe(candidate.url, mapOf("User-Agent" to identity.userAgent))
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                return Verdict.Unreachable
            }
            if (code in 200..299) return Verdict.Plays(candidate)
        }
        return Verdict.Refused
    }

    private fun unavailable(status: String, reason: String): Verdict = when (status) {
        "LOGIN_REQUIRED" -> when {
            reason.contains("bot", ignoreCase = true) -> Verdict.BotCheck
            reason.contains("age", ignoreCase = true) || reason.contains("inappropriate", ignoreCase = true) ->
                Verdict.Unavailable(MissReason.NOT_PERMITTED, final = true)
            reason.contains("private", ignoreCase = true) -> Verdict.Unavailable(MissReason.NOT_FOUND, final = true)
            // This app needs a sign-in; another may not.
            else -> Verdict.Refused
        }
        "AGE_CHECK_REQUIRED", "AGE_VERIFICATION_REQUIRED", "CONTENT_CHECK_REQUIRED" -> Verdict.Unavailable(MissReason.NOT_PERMITTED, final = true)
        "LIVE_STREAM_OFFLINE" -> Verdict.Unavailable(MissReason.NOT_FOUND, final = true)
        "UNPLAYABLE", "ERROR" -> when {
            // The app itself is out of favour, not the song.
            reason.contains("reload", ignoreCase = true) || reason.contains("no longer supported", ignoreCase = true) -> Verdict.Refused
            reason.contains("bot", ignoreCase = true) -> Verdict.BotCheck
            reason.contains("Premium", ignoreCase = true) || reason.contains("country", ignoreCase = true) ->
                Verdict.Unavailable(MissReason.NOT_PERMITTED, final = false)
            else -> Verdict.Unavailable(MissReason.NOT_FOUND, final = false)
        }
        else -> Verdict.Refused
    }

    /** The app's audio streams with plain addresses, best first: Opus, then AAC, higher bitrate first. */
    private fun streams(json: JsonObject, identity: PlayerIdentity): List<Stream> {
        val streaming = json.obj("streamingData") ?: return emptyList()
        val expiresIn = streaming.str("expiresInSeconds")?.toLongOrNull()
        val duration = json.obj("videoDetails")?.str("lengthSeconds")?.toLongOrNull()?.takeIf { it > 0 }?.times(1000)
        val formats = (streaming["adaptiveFormats"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        return formats.mapNotNull { f ->
            val mime = f.str("mimeType") ?: return@mapNotNull null
            if (!mime.startsWith("audio/") || f.bool("isDrc") == true) return@mapNotNull null
            val url = f.str("url") ?: return@mapNotNull null
            val itag = f.str("itag")?.toIntOrNull() ?: return@mapNotNull null
            val bitrate = (f.str("averageBitrate") ?: f.str("bitrate"))?.toIntOrNull()?.takeIf { it > 0 }
            Stream(
                url = url,
                identity = identity.key,
                userAgent = identity.userAgent,
                itag = itag,
                mimeType = mime.substringBefore(';').trim(),
                codec = when {
                    "opus" in mime -> Codec.OPUS
                    "mp4a" in mime -> Codec.AAC
                    else -> null
                },
                bitrateKbps = bitrate?.let { (it + 500) / 1000 },
                sampleRateHz = f.str("audioSampleRate")?.toIntOrNull(),
                channels = f.str("audioChannels")?.toIntOrNull(),
                durationMs = duration ?: f.str("approxDurationMs")?.toLongOrNull(),
                expiresAtMillis = expireOf(url) ?: expiresIn?.let { now() + it * 1000 },
            )
        }.sortedWith(compareBy<Stream> { if (it.codec == Codec.OPUS) 0 else if (it.codec == Codec.AAC) 1 else 2 }.thenByDescending { it.bitrateKbps ?: 0 })
    }

    // --- The guest visitor -------------------------------------------------------------------------------

    private suspend fun visitor(identity: PlayerIdentity, fresh: Boolean = false): String? = visitorLock.withLock {
        if (!fresh) visitor?.let { if (now() - visitorAt < VISITOR_TTL_MS) return@withLock it }
        val fetched = try {
            val body = buildJsonObject { putJsonObject("context") { put("client", clientContext(identity, null)) } }.toString().encodeToByteArray()
            val response = http.post(VISITOR_URL, headers(identity, null), body)
            if (response.code in 200..299) {
                (Json.parseToJsonElement(response.body.decodeToString()) as? JsonObject)?.obj("responseContext")?.str("visitorData")
            } else {
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (fetched != null) {
            visitor = fetched
            visitorAt = now()
        }
        fetched ?: visitor
    }

    // --- Requests ---------------------------------------------------------------------------------------

    private fun headers(identity: PlayerIdentity, visitorData: String?): Map<String, String> = buildMap {
        put("Content-Type", "application/json")
        put("User-Agent", identity.userAgent)
        put("X-Youtube-Client-Name", identity.clientId.toString())
        put("X-Youtube-Client-Version", identity.version)
        put("Origin", "https://www.youtube.com")
        visitorData?.let { put("X-Goog-Visitor-Id", it) }
    }

    private fun clientContext(identity: PlayerIdentity, visitorData: String?): JsonObject = buildJsonObject {
        put("clientName", identity.key)
        put("clientVersion", identity.version)
        identity.device.forEach { (k, v) ->
            when (v) {
                is Number -> put(k, v)
                is Boolean -> put(k, v)
                else -> put(k, v.toString())
            }
        }
        put("hl", "en")
        put("gl", region().takeIf { it.length == 2 }?.uppercase() ?: "US")
        visitorData?.let { put("visitorData", it) }
    }

    private fun playerBody(identity: PlayerIdentity, visitorData: String?, videoId: String): ByteArray = buildJsonObject {
        putJsonObject("context") {
            put("client", clientContext(identity, visitorData))
            putJsonObject("user") { put("lockedSafetyMode", false) }
        }
        put("videoId", videoId)
        put("contentCheckOk", true)
        put("racyCheckOk", true)
        putJsonObject("playbackContext") { putJsonObject("contentPlaybackContext") { put("html5Preference", "HTML5_PREF_WANTS") } }
    }.toString().encodeToByteArray()

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

    internal companion object {
        const val PLAYER_URL = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
        const val VISITOR_URL = "https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false"

        /** All the apps together, before the failsafe gets its turn (inside the source's own time). */
        const val WALK_BUDGET_MS = 12_000L

        /** Formats tried per app: a refusal is usually the app's, not the format's. */
        const val PROBES_PER_APP = 2
        const val VISITOR_TTL_MS = 6 * 60 * 60_000L

        /** A stream address's own expiry (`expire=` seconds), in milliseconds. */
        fun expireOf(url: String): Long? = url.substringAfter('?', "").split('&')
            .firstOrNull { it.startsWith("expire=") }?.substringAfter("expire=")?.toLongOrNull()?.times(1000)
    }
}
