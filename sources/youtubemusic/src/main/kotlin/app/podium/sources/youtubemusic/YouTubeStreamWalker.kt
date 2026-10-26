package app.podium.sources.youtubemusic

import app.podium.core.model.Codec
import app.podium.sources.api.MissReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
 * - **Backups that need the player's script (D-98).** Apps whose streams come enciphered
 *   ([PlayerIdentity.needsPlayerScript], `TVHTML5`) are asked like the rest, with the player's
 *   signature timestamp; their signatures and "n" are solved in one batch by YouTube's own script
 *   ([PlayerScripts]). Without a script engine they're passed over.
 * - **The pre-roll ad's wait.** An answer that comes with an ad before the song serves the song only
 *   once the ad could have been skipped; the check waits for that (up to [MAX_AD_WAIT_MS]).
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
    /** YouTube's player script and its solver, for the apps that need them; null passes them over. */
    private val scripts: PlayerScripts? = null,
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
        settleBackup()
        val verdicts = mutableListOf<Verdict>()
        var rotated = false
        val started = now()
        val decided = withTimeoutOrNull(budgetMillis) {
            for (identity in order.toList() + lastResort) {
                if (identity.key in StreamWalkDebug.skipped) {
                    StreamWalkDebug.note { "walk $videoId ${identity.key}: skipped (debug)" }
                    continue
                }
                if (identity.needsPlayerScript && scripts == null) continue
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
        order.firstOrNull { !it.needsPlayerScript }?.let { memory.served(it.key) }
    }

    /**
     * [identity] served: it goes first. A backup that needs the player's script (slower: the TV app
     * waits out an ad) goes first only for [BACKUP_FIRST_MS], then the usual apps are asked first
     * again, so the working path doesn't stay slow once they serve again. Only the usual apps are
     * remembered across restarts.
     */
    private fun promote(identity: String) {
        val found = order.firstOrNull { it.key == identity } ?: return
        if (order.firstOrNull() != found) {
            order.remove(found)
            order.add(0, found)
        }
        if (found.needsPlayerScript) backupFirstUntil = now() + BACKUP_FIRST_MS else memory.served(identity)
    }

    /** Until when a backup that served stays first ([promote]). */
    @Volatile private var backupFirstUntil = 0L

    /** A backup promoted for longer than it may be goes back behind the usual apps. */
    private fun settleBackup() {
        val first = order.firstOrNull() ?: return
        if (!first.needsPlayerScript || backupFirstUntil == 0L || now() < backupFirstUntil) return
        backupFirstUntil = 0L
        order.remove(first)
        val after = order.indexOfLast { !it.needsPlayerScript } + 1
        order.add(after, first)
    }

    /** The app that served last before a restart, first again. */
    private fun restore() {
        if (restored) return
        restored = true
        val remembered = runCatching { memory.lastServed() }.getOrNull() ?: return
        val found = order.firstOrNull { it.key == remembered && !it.needsPlayerScript } ?: return
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
            val notes = if (seen.notes.isEmpty()) "" else " (${seen.notes.joinToString(", ")})"
            "walk $videoId ${identity.key}: http ${seen.http ?: "-"} status ${seen.status ?: "-"}$reason " +
                "streams ${seen.streams}$probes$notes -> ${verdict.javaClass.simpleName} in ${now() - started} ms"
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
        val notes = mutableListOf<String>()
    }

    private suspend fun ask(identity: PlayerIdentity, videoId: String, seen: Seen): Verdict {
        val player = if (identity.needsPlayerScript) {
            scripts?.current() ?: return Verdict.Unreachable.also { seen.notes += "no player script" }
        } else {
            null
        }
        val visitorData = visitor(identity)
        val response = try {
            http.post(playerUrl(identity), headers(identity, visitorData), playerBody(identity, visitorData, videoId, player?.signatureTimestamp))
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return Verdict.Unreachable
        }
        val answeredAt = now()
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
        val formats = formats(json, identity)
        seen.streams = formats.size
        val tried = formats.take(PROBES_PER_APP)
        val candidates = if (player != null) {
            val started = now()
            val solved = scripts!!.solve(player, tried.mapNotNull { it.n }.toSet(), tried.mapNotNull { it.s }.toSet())
            if (solved == null) {
                seen.notes += "solver failed in ${now() - started} ms"
                return Verdict.Unreachable
            }
            seen.notes += "solved in ${now() - started} ms"
            tried.mapNotNull { it.deciphered(solved) }
        } else {
            tried.map { it.stream }
        }
        // An ad before the song: its stream serves once the ad could have been skipped.
        val adWait = adWaitMillis(json)
        if (adWait > MAX_AD_WAIT_MS) {
            seen.notes += "ad ${adWait / 1000} s"
            return Verdict.Refused
        }
        val servesAt = answeredAt + adWait
        if (adWait > 0) {
            val early = servesAt - AD_EARLY_MS - now()
            seen.notes += "ad ${adWait / 1000} s"
            if (early > 0) delay(early)
        }
        for ((i, candidate) in candidates.withIndex()) {
            var code = check(candidate, identity, seen) ?: return Verdict.Unreachable
            // Checked a little early for the ad: once more when it could be skipped.
            if (code == 403 && i == 0 && adWait > 0 && now() < servesAt + AD_EARLY_MS) {
                delay((servesAt + AD_SETTLE_MS - now()).coerceAtLeast(0))
                code = check(candidate, identity, seen) ?: return Verdict.Unreachable
            }
            if (code in 200..299) return Verdict.Plays(candidate)
        }
        return Verdict.Refused
    }

    /** One stream's check, its codes noted for the trace; null when YouTube couldn't be reached. */
    private suspend fun check(candidate: Stream, identity: PlayerIdentity, seen: Seen): Int? {
        val codes = mutableListOf<String>()
        return try {
            proves(candidate.url, mapOf("User-Agent" to identity.userAgent), candidate.contentLength, codes).also {
                seen.probes += "${candidate.itag} ${codes.joinToString(" ")}"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            seen.probes += "${candidate.itag} ${(codes + e.message.orEmpty()).joinToString(" ")}"
            null
        }
    }

    /**
     * How long an ad before the song holds the song's stream back, in milliseconds: each pre-roll's
     * skip point, or its length when it can't be skipped (as yt-dlp reads it).
     */
    private fun adWaitMillis(json: JsonObject): Long {
        var total = 0L
        fun ads(node: kotlinx.serialization.json.JsonElement?) {
            when (node) {
                is JsonObject -> {
                    (node["instreamVideoAdRenderer"] as? JsonObject)?.let { ad ->
                        val skip = ad.str("skipOffsetMilliseconds")?.toLongOrNull()
                        val length = ad.str("playerVars")?.split('&')?.firstOrNull { it.startsWith("length_seconds=") }
                            ?.substringAfter('=')?.toLongOrNull()?.times(1000)
                        total += skip ?: length ?: 0L
                        return
                    }
                    node.values.forEach(::ads)
                }
                is JsonArray -> node.forEach(::ads)
                else -> Unit
            }
        }
        (json["adPlacements"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.forEach { p ->
            val kind = p.obj("adPlacementRenderer")?.obj("config")?.obj("adPlacementConfig")?.str("kind")
            if (kind == "AD_PLACEMENT_KIND_START") ads(p.obj("adPlacementRenderer")?.get("renderer"))
        }
        (json["adSlots"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.forEach { s ->
            val trigger = s.obj("adSlotRenderer")?.obj("adSlotMetadata")?.str("triggerEvent")
            if (trigger == "SLOT_TRIGGER_EVENT_BEFORE_CONTENT") ads(s.obj("adSlotRenderer")?.get("fulfillmentContent"))
        }
        return total
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

    /**
     * A format as the app handed it: a plain address, or (apps that need the player's script) an
     * enciphered signature `s` to put in `sp`, and the address's `n` to solve.
     */
    private class Format(val stream: Stream, val s: String?, val sp: String?, val n: String?) {
        /** The address with its challenges answered, or null when one wasn't. */
        fun deciphered(solved: Solutions): Stream? {
            var url = stream.url
            if (s != null) url = withParam(url, sp ?: "signature", solved.sig[s] ?: return null)
            if (n != null) url = withParam(url, "n", solved.n[n] ?: return null)
            return stream.copy(url = url)
        }
    }

    /**
     * The app's audio formats, best first: Opus, then AAC, higher bitrate first. Plain addresses
     * only, unless the app's player script can be run.
     */
    private fun formats(json: JsonObject, identity: PlayerIdentity): List<Format> {
        val streaming = json.obj("streamingData") ?: return emptyList()
        val expiresIn = streaming.str("expiresInSeconds")?.toLongOrNull()
        val duration = json.obj("videoDetails")?.str("lengthSeconds")?.toLongOrNull()?.takeIf { it > 0 }?.times(1000)
        val formats = (streaming["adaptiveFormats"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        return formats.mapNotNull { f ->
            val mime = f.str("mimeType") ?: return@mapNotNull null
            if (!mime.startsWith("audio/") || f.bool("isDrc") == true) return@mapNotNull null
            val cipher = f.str("signatureCipher")?.takeIf { identity.needsPlayerScript }?.let(::query)
            val url = f.str("url") ?: cipher?.get("url") ?: return@mapNotNull null
            val itag = f.str("itag")?.toIntOrNull() ?: return@mapNotNull null
            if (cipher != null && cipher["s"] == null) return@mapNotNull null
            val bitrate = (f.str("averageBitrate") ?: f.str("bitrate"))?.toIntOrNull()?.takeIf { it > 0 }
            Format(Stream(
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
            ), s = cipher?.get("s"), sp = cipher?.get("sp"), n = if (identity.needsPlayerScript) param(url, "n")?.let(::decoded) else null)
        }.sortedWith(
            compareBy<Format> { if (it.stream.codec == Codec.OPUS) 0 else if (it.stream.codec == Codec.AAC) 1 else 2 }
                .thenByDescending { it.stream.bitrateKbps ?: 0 },
        )
    }

    // --- The guest visitor -------------------------------------------------------------------------------

    private suspend fun visitor(asking: PlayerIdentity, fresh: Boolean = false): String? = visitorLock.withLock {
        if (!fresh) visitor?.let { if (now() - visitorAt < VISITOR_TTL_MS) return@withLock it }
        // The visitor id is asked as an app with plain streams: the TV app's request is refused (HTTP 400).
        val identity = if (asking.needsPlayerScript) PlayerIdentities.ANDROID_VR else asking
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

    private fun playerUrl(identity: PlayerIdentity) = "https://${identity.host}/youtubei/v1/player?prettyPrint=false"

    private fun headers(identity: PlayerIdentity, visitorData: String?): Map<String, String> = buildMap {
        put("Content-Type", "application/json")
        put("User-Agent", identity.userAgent)
        put("X-Youtube-Client-Name", identity.clientId.toString())
        put("X-Youtube-Client-Version", identity.version)
        put("Origin", "https://${identity.host}")
        visitorData?.let { put("X-Goog-Visitor-Id", it) }
    }

    private fun clientContext(identity: PlayerIdentity, visitorData: String?): JsonObject = buildJsonObject {
        put("clientName", identity.clientName)
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

    private fun playerBody(identity: PlayerIdentity, visitorData: String?, videoId: String, signatureTimestamp: Int?): ByteArray = buildJsonObject {
        putJsonObject("context") {
            put("client", clientContext(identity, visitorData))
            putJsonObject("user") { put("lockedSafetyMode", false) }
        }
        put("videoId", videoId)
        put("contentCheckOk", true)
        put("racyCheckOk", true)
        putJsonObject("playbackContext") {
            putJsonObject("contentPlaybackContext") {
                put("html5Preference", "HTML5_PREF_WANTS")
                signatureTimestamp?.let { put("signatureTimestamp", it) }
            }
        }
    }.toString().encodeToByteArray()

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

    internal companion object {
        const val VISITOR_URL = "https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false"

        /** All the apps together, before the failsafe gets its turn (inside the source's own time). */
        const val WALK_BUDGET_MS = 12_000L

        /** Formats tried per app: a refusal is usually the app's, not the format's. */
        const val PROBES_PER_APP = 2
        const val VISITOR_TTL_MS = 6 * 60 * 60_000L

        /** YouTube's own reason, shortened, in the debug trace. */
        const val TRACE_REASON_CHARS = 80

        /** How long a backup that served stays first before the usual apps are asked first again. */
        const val BACKUP_FIRST_MS = 10 * 60_000L

        /** The longest wait for a pre-roll ad worth taking: a song shouldn't wait through an unskippable ad. */
        const val MAX_AD_WAIT_MS = 6_000L

        /** A stream held back for an ad is first checked this much before the ad could be skipped. */
        const val AD_EARLY_MS = 1_500L

        /** Then once more this long after. */
        const val AD_SETTLE_MS = 250L

        /** A stream address's own expiry (`expire=` seconds), in milliseconds. */
        fun expireOf(url: String): Long? = param(url, "expire")?.toLongOrNull()?.times(1000)

        /** A stream address's own size in bytes (`clen=`). */
        fun clenOf(url: String): Long? = param(url, "clen")?.toLongOrNull()?.takeIf { it > 0 }

        private fun param(url: String, name: String): String? =
            url.substringAfter('?', "").split('&').firstOrNull { it.startsWith("$name=") }?.substringAfter('=')

        /** A form-encoded query (`a=1&b=2`), decoded. */
        fun query(q: String): Map<String, String> = q.split('&').filter { '=' in it }
            .associate { decoded(it.substringBefore('=')) to decoded(it.substringAfter('=')) }

        fun decoded(s: String): String = java.net.URLDecoder.decode(s, "UTF-8")

        /** [url] with its [name] parameter set to [value] (replaced where it was, else added). */
        fun withParam(url: String, name: String, value: String): String {
            val encoded = java.net.URLEncoder.encode(value, "UTF-8")
            val base = url.substringBefore('?')
            val parts = url.substringAfter('?', "").split('&').filter { it.isNotEmpty() }
            val replaced = parts.map { if (it.substringBefore('=') == name) "$name=$encoded" else it }
            val all = if (parts.any { it.substringBefore('=') == name }) replaced else parts + "$name=$encoded"
            return "$base?${all.joinToString("&")}"
        }
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
