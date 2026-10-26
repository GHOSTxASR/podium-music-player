package app.podium.sources.youtubemusic

import app.podium.core.model.Codec
import app.podium.sources.api.MissReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
 * A song's stream, found the way BitChord finds one (D-97, D-98), so no one change on YouTube's side
 * stops every song:
 *
 * - **Several apps.** The player is asked as each of YouTube's own apps in turn ([PlayerIdentities]):
 *   each gets streams under its own rules, so when one app's are refused another's may play. The app
 *   that last served a stream is asked first, after a restart too ([StreamWalkMemory]); the last
 *   resort ([PlayerIdentity.lastResort]) is always asked last.
 * - **A guest visitor.** Every request carries a guest visitor id, as the apps' own do. When YouTube
 *   asks to confirm "you're not a bot", a fresh visitor id is fetched and the app asked once more.
 * - **Proven before it plays.** A stream is handed to the player only once it has answered the way the
 *   player asks for it: the open-ended request the player sends (`bytes=0-`), and the stream's last two
 *   bytes, so seeking works too. Some apps' streams serve only their first part (D-98); a check of the
 *   first bytes alone passed them. A refused stream (HTTP 403) is never handed over; the next format,
 *   or the next app, is tried instead. The app's own user agent goes with the stream
 *   ([Stream.userAgent]).
 * - **What can't play, and what can't play yet.** A song that is gone, private or age-restricted is a
 *   [Result.Unplayable] (asking again won't help); refused streams, bot checks and an unreachable
 *   service are worth trying again, and the caller has a failsafe for them (NewPipe).
 *
 * Addresses and answers never leave the walk. Debug builds can watch it ([StreamWalkDebug]).
 */
internal class YouTubeStreamWalker(
    private val http: StreamHttp,
    private val region: () -> String = { "US" },
    private val now: () -> Long = System::currentTimeMillis,
    identities: List<PlayerIdentity> = PlayerIdentities.ALL,
    private val budgetMillis: Long = WALK_BUDGET_MS,
    private val memory: StreamWalkMemory = StreamWalkMemory.None,
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
        /** The stream's size in bytes, when the app said (the address's own `clen` comes first). */
        val contentLength: Long? = null,
    )

    private sealed interface Verdict {
        data class Plays(val stream: Stream) : Verdict
        data object Refused : Verdict
        data object BotCheck : Verdict
        data class Unavailable(val reason: MissReason, val final: Boolean) : Verdict
        data class Limited(val retryAfterMillis: Long?) : Verdict
        data object Unreachable : Verdict
    }

    /** The apps in the order they're asked, the last to serve a stream first; the last resort after them. */
    private val order = CopyOnWriteArrayList(identities.filterNot { it.lastResort })
    private val lastResort = identities.filter { it.lastResort }

    /** Whether the app remembered from before a restart has been put first yet (read off the caller's thread). */
    @Volatile private var restored = false

    private val visitorLock = Mutex()
    @Volatile private var visitor: String? = null
    @Volatile private var visitorAt = 0L

    /** The order the apps are asked in now. */
    fun order(): List<String> {
        restore()
        return (order + lastResort).map { it.key }
    }

    suspend fun walk(videoId: String): Result {
        restore()
        val verdicts = mutableListOf<Verdict>()
        var rotated = false
        val started = now()
        val decided = withTimeoutOrNull(budgetMillis) {
            for (identity in order.toList() + lastResort) {
                if (identity.key in StreamWalkDebug.skipped) {
                    StreamWalkDebug.note { "walk $videoId ${identity.key}: skipped (debug)" }
                    continue
                }
                var verdict = ask(identity, videoId)
                if (verdict == Verdict.BotCheck && !rotated) {
                    rotated = true
                    visitor(identity, fresh = true)
                    StreamWalkDebug.note { "walk $videoId ${identity.key}: bot check, asking again with a fresh visitor" }
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
        val result = decided ?: conclude(verdicts)
        StreamWalkDebug.note {
            val what = (result as? Result.Found)?.stream?.let { "Found ${it.identity} itag ${it.itag}" } ?: result.toString()
            "walk $videoId -> $what in ${now() - started} ms"
        }
        return result
    }

    /** The stream at [url] serves the player when asked as [userAgent] (for the failsafe's streams). */
    suspend fun serves(url: String, userAgent: String?): Boolean = try {
        proves(url, userAgent?.let { mapOf("User-Agent" to it) }.orEmpty(), null, null) in 200..299
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        false
    }

    /** A stream from the app [identity] was refused while playing: that app goes last for now (before the last resort). */
    fun demote(identity: String) {
        restore()
        val found = order.firstOrNull { it.key == identity } ?: return
        order.remove(found)
        order.add(found)
        order.firstOrNull()?.let { memory.served(it.key) }
    }

    private fun promote(identity: String) {
        val found = order.firstOrNull { it.key == identity } ?: return
        if (order.firstOrNull() != found) {
            order.remove(found)
            order.add(0, found)
        }
        memory.served(identity)
    }

    /** The app that served last before a restart, first again. */
    private fun restore() {
        if (restored) return
        restored = true
        val remembered = runCatching { memory.lastServed() }.getOrNull() ?: return
        val found = order.firstOrNull { it.key == remembered } ?: return
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

    // --- Proving a stream ----------------------------------------------------------------------------------

    /**
     * Whether the stream at [url] serves the player, as the HTTP status that decided it: the
     * open-ended request the player itself sends (`bytes=0-`, two bytes read), and the last two bytes
     * of the stream, so a seek to the end is served too. Both are asked at once (the working path
     * shouldn't wait twice), and a stream counts only when both answer 2xx. The size comes from the
     * address's `clen`, else from [length]; without either, the first check decides. [seen] collects
     * the codes for the debug trace.
     */
    private suspend fun proves(url: String, headers: Map<String, String>, length: Long?, seen: MutableList<String>?): Int {
        val size = (clenOf(url) ?: length)?.takeIf { it >= 3 }
        if (size == null) {
            val head = http.probe(url, headers, "0-")
            seen?.add("0-:$head")
            return head
        }
        return coroutineScope {
            val head = async { http.probe(url, headers, "0-") }
            val tail = async { http.probe(url, headers, "${size - 2}-${size - 1}") }
            val headCode = head.await()
            val tailCode = tail.await()
            seen?.add("0-:$headCode end:$tailCode")
            if (headCode !in 200..299) headCode else tailCode
        }
    }

    // --- One app -----------------------------------------------------------------------------------------

    private suspend fun ask(identity: PlayerIdentity, videoId: String): Verdict {
        val started = now()
        val seen = Seen()
        val verdict = ask(identity, videoId, seen)
        StreamWalkDebug.note {
            val reason = seen.reason?.let { " \"${it.take(TRACE_REASON_CHARS)}\"" }.orEmpty()
            val probes = if (seen.probes.isEmpty()) "" else " probe ${seen.probes.joinToString(", ")}"
            "walk $videoId ${identity.key}: http ${seen.http ?: "-"} status ${seen.status ?: "-"}$reason " +
                "streams ${seen.streams}$probes -> ${verdict.javaClass.simpleName} in ${now() - started} ms"
        }
        return verdict
    }

    /** What one app said, for the debug trace ([StreamWalkDebug]): codes and YouTube's own words, never an address. */
    private class Seen {
        var http: Int? = null
        var status: String? = null
        var reason: String? = null
        var streams = 0
        val probes = mutableListOf<String>()
    }

    private suspend fun ask(identity: PlayerIdentity, videoId: String, seen: Seen): Verdict {
        val visitorData = visitor(identity)
        val response = try {
            http.post(PLAYER_URL, headers(identity, visitorData), playerBody(identity, visitorData, videoId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return Verdict.Unreachable
        }
        seen.http = response.code
        if (response.code == 429) return Verdict.Limited(response.retryAfterSeconds?.takeIf { it > 0 }?.times(1000))
        if (response.code !in 200..299) return Verdict.Unreachable
        val json = runCatching { Json.parseToJsonElement(response.body.decodeToString()) as? JsonObject }.getOrNull()
            ?: return Verdict.Unreachable
        val playability = json.obj("playabilityStatus")
        val status = playability?.str("status")
        seen.status = status
        seen.reason = playability?.str("reason")
        if (status != null && status != "OK") return unavailable(status, playability.str("reason").orEmpty())
        // An answer about another video: the app was turned away with a stand-in.
        if (json.obj("videoDetails")?.str("videoId") != videoId) return Verdict.Refused
        val candidates = streams(json, identity)
        seen.streams = candidates.size
        for (candidate in candidates.take(PROBES_PER_APP)) {
            val codes = mutableListOf<String>()
            val code = try {
                proves(candidate.url, mapOf("User-Agent" to identity.userAgent), candidate.contentLength, codes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                seen.probes += "${candidate.itag} ${(codes + e.message.orEmpty()).joinToString(" ")}"
                return Verdict.Unreachable
            }
            seen.probes += "${candidate.itag} ${codes.joinToString(" ")}"
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
                contentLength = f.str("contentLength")?.toLongOrNull()?.takeIf { it > 0 },
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
        StreamWalkDebug.note { "visitor: ${if (fetched != null) "fetched" else "not fetched"}${if (fresh) " (fresh)" else ""}" }
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

        /** YouTube's own reason, shortened, in the debug trace. */
        const val TRACE_REASON_CHARS = 80

        /** A stream address's own expiry (`expire=` seconds), in milliseconds. */
        fun expireOf(url: String): Long? = param(url, "expire")?.toLongOrNull()?.times(1000)

        /** A stream address's own size in bytes (`clen=`). */
        fun clenOf(url: String): Long? = param(url, "clen")?.toLongOrNull()?.takeIf { it > 0 }

        private fun param(url: String, name: String): String? =
            url.substringAfter('?', "").split('&').firstOrNull { it.startsWith("$name=") }?.substringAfter('=')
    }
}

/**
 * Where the walk keeps the app that last served a stream, so the first song after a restart doesn't
 * start with an app that has stopped serving (D-98). One app's name, nothing about the listener.
 */
interface StreamWalkMemory {
    fun lastServed(): String?

    fun served(identity: String)

    companion object {
        /** Nothing kept: every run starts in the default order. */
        val None: StreamWalkMemory = object : StreamWalkMemory {
            override fun lastServed(): String? = null
            override fun served(identity: String) = Unit
        }
    }
}

/**
 * The walk, as debug builds watch and steer it (D-98). Release builds never set either.
 *
 * - [trace]: one line per app asked (HTTP code, playability status, YouTube's reason, the check's
 *   codes per format) and one for how the walk ended. Never an address, a cookie or a visitor id.
 * - [skipped]: apps the walk passes over, so a device test can prove the others serve songs.
 */
object StreamWalkDebug {
    @Volatile
    var trace: ((String) -> Unit)? = null

    @Volatile
    var skipped: Set<String> = emptySet()

    internal inline fun note(line: () -> String) {
        trace?.invoke(line())
    }
}
