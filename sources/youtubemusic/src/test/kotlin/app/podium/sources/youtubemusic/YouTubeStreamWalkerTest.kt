package app.podium.sources.youtubemusic

import app.podium.core.model.Codec
import app.podium.core.model.PlaybackRoute
import app.podium.core.model.SourceRef
import app.podium.core.model.Track
import app.podium.core.model.TrackId
import app.podium.sources.api.FacetResolution
import app.podium.sources.api.HealthOutcome
import app.podium.sources.api.MissReason
import app.podium.sources.api.PlaybackTarget
import app.podium.sources.api.QualityRequest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Finding a song's stream like BitChord (D-97, issue #5): several of YouTube's apps asked in turn,
 * every stream proven before the player gets it (as the player asks, and at its end, D-98), refusals
 * and bot checks never handed over.
 */
class YouTubeStreamWalkerTest {

    private val video = "vid00000001"

    /**
     * YouTube, scripted: the player's answer per app, and which stream addresses serve bytes —
     * [serving] all of them, [firstPart] only their first 256 KB (ANDROID_VR's and IOS's streams
     * without a PoToken, measured on a phone 2026-10-26).
     */
    private class Script : StreamHttp {
        val players = mutableMapOf<String, () -> HttpResponse>()
        val serving = mutableSetOf<String>()
        val firstPart = mutableSetOf<String>()
        val asked = mutableListOf<String>()
        val probed = mutableListOf<Pair<String, String?>>()
        val ranges = mutableListOf<String>()
        var visitors = 0
        val visitorSeen = mutableListOf<String?>()

        override suspend fun post(url: String, headers: Map<String, String>, body: ByteArray): HttpResponse {
            val json = Json.parseToJsonElement(body.decodeToString()).jsonObject
            val client = json["context"]!!.jsonObject["client"]!!.jsonObject
            val app = client["clientName"]!!.jsonPrimitive.content
            if (url == YouTubeStreamWalker.VISITOR_URL) {
                visitors++
                return HttpResponse(200, """{"responseContext":{"visitorData":"visitor$visitors"}}""".toByteArray())
            }
            asked += app
            visitorSeen += headers["X-Goog-Visitor-Id"]
            assertEquals(json["videoId"]!!.jsonPrimitive.content, "vid00000001")
            return players[app]?.invoke() ?: HttpResponse(200, unavailable("UNPLAYABLE", "This video is unavailable"))
        }

        val pages = mutableMapOf<String, () -> HttpResponse>()

        override suspend fun get(url: String, headers: Map<String, String>, maxBytes: Int): HttpResponse =
            pages[url]?.invoke() ?: HttpResponse(404, ByteArray(0))

        override suspend fun probe(url: String, headers: Map<String, String>, range: String): Int {
            probed += url to headers["User-Agent"]
            ranges += range
            if (url in serving) return 206
            if (url !in firstPart) return 403
            // Open-ended ("0-") runs to the end of the stream; only ranges inside the first part serve.
            val end = range.substringAfter('-').toLongOrNull() ?: ((YouTubeStreamWalker.clenOf(url) ?: Long.MAX_VALUE) - 1)
            return if (end < FIRST_PART) 206 else 403
        }
    }

    private val script = Script()

    /** The order D-97 shipped with, so these tests read as they were written; [defaultWalker] has today's. */
    private val scriptedOrder = listOf(PlayerIdentities.ANDROID_VR, PlayerIdentities.VISIONOS, PlayerIdentities.IOS, PlayerIdentities.ANDROID)

    private fun walker() = YouTubeStreamWalker(script, region = { "IN" }, now = { 1_000_000L }, identities = scriptedOrder)

    private fun defaultWalker() = YouTubeStreamWalker(script, region = { "IN" }, now = { 1_000_000L })

    private fun ok(app: String, vararg itags: Int, videoId: String = video): ByteArray {
        val formats = itags.joinToString(",") { itag ->
            val mime = if (itag in listOf(249, 250, 251)) "audio/webm; codecs=\\\"opus\\\"" else "audio/mp4; codecs=\\\"mp4a.40.2\\\""
            val bitrate = mapOf(251 to 135000, 250 to 70000, 249 to 50000, 140 to 130000, 139 to 48000)[itag] ?: 1000
            """{"itag":$itag,"url":"${url(app, itag)}","mimeType":"$mime","bitrate":$bitrate,"averageBitrate":$bitrate,"audioSampleRate":"48000","audioChannels":2}"""
        }
        return """{"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"$videoId","lengthSeconds":"213"},
            "streamingData":{"expiresInSeconds":"21540","adaptiveFormats":[{"itag":136,"url":"https://v.googlevideo.com/video","mimeType":"video/mp4"},$formats]}}""".toByteArray()
    }

    private fun url(app: String, itag: Int) = "https://rr1.googlevideo.com/videoplayback?expire=1021600&itag=$itag&c=$app&clen=$CLEN"

    private companion object {
        const val CLEN = 4_695_894L
        const val FIRST_PART = 262_144L

        fun unavailable(status: String, reason: String) = """{"playabilityStatus":{"status":"$status","reason":"$reason"}}""".toByteArray()
    }

    @Test
    fun `the first app's best stream plays once it has proven it serves audio`() = runTest {
        script.players["ANDROID_VR"] = { HttpResponse(200, ok("ANDROID_VR", 140, 251, 250)) }
        script.serving += url("ANDROID_VR", 251)
        val found = assertIs<YouTubeStreamWalker.Result.Found>(walker().walk(video)).stream
        assertEquals(251, found.itag, "Opus, the highest bitrate, first")
        assertEquals(Codec.OPUS, found.codec)
        assertEquals("audio/webm", found.mimeType)
        assertEquals(135, found.bitrateKbps)
        assertEquals(213_000L, found.durationMs)
        assertEquals(1_021_600_000L, found.expiresAtMillis)
        assertEquals(PlayerIdentities.ANDROID_VR.userAgent, found.userAgent, "asked as the app it was handed to")
        assertEquals(List<Pair<String, String?>>(2) { url("ANDROID_VR", 251) to PlayerIdentities.ANDROID_VR.userAgent }, script.probed)
        assertEquals(listOf("0-", "${CLEN - 2}-${CLEN - 1}"), script.ranges, "as the player asks, then the end of the stream")
        assertEquals(listOf<String?>("visitor1"), script.visitorSeen, "a guest visitor id goes with the request")
    }

    @Test
    fun `a refused stream is never handed over, the next app's is, and that app goes first next time`() = runTest {
        script.players["ANDROID_VR"] = { HttpResponse(200, ok("ANDROID_VR", 251, 140)) }
        script.players["VISIONOS"] = { HttpResponse(200, ok("VISIONOS", 251)) }
        script.serving += url("VISIONOS", 251)
        val walker = walker()
        val found = assertIs<YouTubeStreamWalker.Result.Found>(walker.walk(video)).stream
        assertEquals("VISIONOS", found.identity)
        // Both checks for every format tried (asked at once).
        assertEquals(listOf(url("ANDROID_VR", 251), url("ANDROID_VR", 140), url("VISIONOS", 251)).flatMap { listOf(it, it) }, script.probed.map { it.first })
        assertEquals("VISIONOS", walker.order().first())
        script.asked.clear()
        walker.walk(video)
        assertEquals(listOf("VISIONOS"), script.asked)
    }

    @Test
    fun `a bot check gets a fresh visitor id and the app is asked once more`() = runTest {
        var first = true
        script.players["ANDROID_VR"] = {
            if (first) {
                first = false
                HttpResponse(200, unavailable("LOGIN_REQUIRED", "Sign in to confirm you’re not a bot"))
            } else {
                HttpResponse(200, ok("ANDROID_VR", 251))
            }
        }
        script.serving += url("ANDROID_VR", 251)
        assertIs<YouTubeStreamWalker.Result.Found>(walker().walk(video))
        assertEquals(listOf<String?>("visitor1", "visitor2"), script.visitorSeen)
    }

    @Test
    fun `bot checks everywhere, refusals everywhere, and a song no app has are told apart`() = runTest {
        PlayerIdentities.ALL.forEach { app -> script.players[app.key] = { HttpResponse(200, unavailable("LOGIN_REQUIRED", "Sign in to confirm you're not a bot")) } }
        assertEquals(YouTubeStreamWalker.Result.BotCheck, walker().walk(video))

        PlayerIdentities.ALL.forEach { app -> script.players[app.key] = { HttpResponse(200, ok(app.key, 251)) } }
        assertEquals(YouTubeStreamWalker.Result.Refused, walker().walk(video))

        script.players.clear() // every app: "This video is unavailable"
        assertEquals(YouTubeStreamWalker.Result.Unplayable(MissReason.NOT_FOUND), walker().walk(video))
    }

    @Test
    fun `one app's not-available answer isn't the last word`() = runTest {
        script.players["ANDROID_VR"] = { HttpResponse(200, unavailable("UNPLAYABLE", "This video is not available")) }
        script.players["VISIONOS"] = { HttpResponse(200, ok("VISIONOS", 251)) }
        script.serving += url("VISIONOS", 251)
        assertEquals("VISIONOS", assertIs<YouTubeStreamWalker.Result.Found>(walker().walk(video)).stream.identity)
    }

    @Test
    fun `an age-restricted or private song stops the walk, as no app here can sign in`() = runTest {
        script.players["ANDROID_VR"] = { HttpResponse(200, unavailable("LOGIN_REQUIRED", "Sign in to confirm your age. This video may be inappropriate for some users.")) }
        assertEquals(YouTubeStreamWalker.Result.Unplayable(MissReason.NOT_PERMITTED), walker().walk(video))
        assertEquals(listOf("ANDROID_VR"), script.asked)
    }

    @Test
    fun `too many requests stops the walk at once`() = runTest {
        script.players["ANDROID_VR"] = { HttpResponse(429, ByteArray(0), retryAfterSeconds = 40) }
        assertEquals(YouTubeStreamWalker.Result.Limited(40_000), walker().walk(video))
        assertEquals(listOf("ANDROID_VR"), script.asked)
    }

    @Test
    fun `an answer about another video, or none at all, isn't used`() = runTest {
        script.players["ANDROID_VR"] = { HttpResponse(200, ok("ANDROID_VR", 251, videoId = "standin0001")) }
        script.players["VISIONOS"] = { throw IOException("timeout") }
        script.players["IOS"] = { HttpResponse(500, ByteArray(0)) }
        script.players["ANDROID"] = { HttpResponse(200, ok("ANDROID", 140)) }
        script.serving += listOf(url("ANDROID_VR", 251), url("ANDROID", 140))
        val found = assertIs<YouTubeStreamWalker.Result.Found>(walker().walk(video)).stream
        assertEquals("ANDROID", found.identity)
        assertEquals(Codec.AAC, found.codec)
    }

    @Test
    fun `an app whose stream was refused while playing goes last, but never behind the last resort`() {
        val walker = walker()
        walker.demote("ANDROID_VR")
        assertEquals(listOf("VISIONOS", "IOS", "ANDROID_VR", "ANDROID"), walker.order())
    }

    // --- D-98: proven as the player asks, the order, the memory --------------------------------------

    @Test
    fun `a stream that serves only its first part is never handed over`() = runTest {
        // ANDROID_VR and IOS: the first bytes come back, the player's own request and the end don't.
        listOf("ANDROID_VR", "IOS").forEach { app ->
            script.players[app] = { HttpResponse(200, ok(app, 251, 250)) }
            script.firstPart += listOf(url(app, 251), url(app, 250))
        }
        script.players["VISIONOS"] = { HttpResponse(200, ok("VISIONOS", 251)) }
        script.serving += url("VISIONOS", 251)
        val walker = YouTubeStreamWalker(
            script, region = { "IN" }, now = { 1_000_000L },
            identities = listOf(PlayerIdentities.ANDROID_VR, PlayerIdentities.IOS, PlayerIdentities.VISIONOS, PlayerIdentities.ANDROID),
        )
        val found = assertIs<YouTubeStreamWalker.Result.Found>(walker.walk(video)).stream
        assertEquals("VISIONOS", found.identity)
        assertEquals(listOf("ANDROID_VR", "IOS", "VISIONOS"), script.asked)
        // Every first-part stream was asked as the player asks it, and refused there.
        assertEquals(8, script.probed.count { (u, _) -> u in script.firstPart })
    }

    @Test
    fun `a short stream inside the first part passes both checks`() = runTest {
        val short = "https://rr1.googlevideo.com/videoplayback?itag=251&c=IOS&clen=200000"
        script.players["IOS"] = {
            HttpResponse(200, """{"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"$video"},"streamingData":{"adaptiveFormats":[
                {"itag":251,"url":"$short","mimeType":"audio/webm","bitrate":135000}]}}""".toByteArray())
        }
        script.firstPart += short
        val walker = YouTubeStreamWalker(script, now = { 1_000_000L }, identities = listOf(PlayerIdentities.IOS))
        assertIs<YouTubeStreamWalker.Result.Found>(walker.walk(video))
        assertEquals(listOf("0-", "199998-199999"), script.ranges)
    }

    @Test
    fun `without clen the size the app gave is used, and without either the player's request decides`() = runTest {
        val bare = "https://rr1.googlevideo.com/videoplayback?itag=251&c=VISIONOS"
        script.players["VISIONOS"] = {
            HttpResponse(200, """{"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"$video"},"streamingData":{"adaptiveFormats":[
                {"itag":251,"url":"$bare","mimeType":"audio/webm","bitrate":135000,"contentLength":"1000000"}]}}""".toByteArray())
        }
        script.serving += bare
        assertIs<YouTubeStreamWalker.Result.Found>(YouTubeStreamWalker(script, now = { 1L }, identities = listOf(PlayerIdentities.VISIONOS)).walk(video))
        assertEquals(listOf("0-", "999998-999999"), script.ranges)

        script.ranges.clear()
        assertTrue(YouTubeStreamWalker(script, now = { 1L }).serves(bare, null), "the failsafe's stream, no size known")
        assertEquals(listOf("0-"), script.ranges)
    }

    @Test
    fun `VISIONOS goes first by default, the TV app next, and the last resort stays last`() {
        val walker = defaultWalker()
        assertEquals(listOf("VISIONOS", "TVHTML5", "ANDROID_VR", "IOS", "ANDROID"), walker.order())
        walker.demote("VISIONOS")
        assertEquals(listOf("TVHTML5", "ANDROID_VR", "IOS", "VISIONOS", "ANDROID"), walker.order())
    }

    @Test
    fun `the app that served last is asked first after a restart`() = runTest {
        var kept: String? = "IOS"
        val memory = object : StreamWalkMemory {
            override fun lastServed() = kept
            override fun served(identity: String) { kept = identity }
        }
        script.players["IOS"] = { HttpResponse(200, ok("IOS", 251)) }
        script.serving += url("IOS", 251)
        val walker = YouTubeStreamWalker(script, now = { 1L }, memory = memory)
        assertEquals("IOS", walker.order().first())
        assertIs<YouTubeStreamWalker.Result.Found>(walker.walk(video))
        assertEquals(listOf("IOS"), script.asked, "nothing asked before it")

        // A refusal while playing: the new first app is what a restart starts with.
        walker.demote("IOS")
        assertEquals("VISIONOS", kept)
        // An app this build doesn't know is ignored.
        kept = "SOMETHING_ELSE"
        assertEquals(listOf("VISIONOS", "TVHTML5", "ANDROID_VR", "IOS", "ANDROID"), YouTubeStreamWalker(script, memory = memory).order())
    }

    @Test
    fun `apps skipped for a device test aren't asked`() = runTest {
        script.players["IOS"] = { HttpResponse(200, ok("IOS", 251)) }
        script.serving += url("IOS", 251)
        StreamWalkDebug.skipped = setOf("VISIONOS", "ANDROID_VR")
        try {
            assertEquals("IOS", assertIs<YouTubeStreamWalker.Result.Found>(defaultWalker().walk(video)).stream.identity)
            assertEquals(listOf("IOS"), script.asked)
        } finally {
            StreamWalkDebug.skipped = emptySet()
        }
    }

    // --- In the resolver: what the player gets ------------------------------------------------------

    private fun song() = Track(
        id = TrackId.of(YouTubeMusicSource.SOURCE_ID, video),
        title = "Song",
        artists = emptyList(),
        durationMs = 200_000,
        source = SourceRef(YouTubeMusicSource.SOURCE_ID, video),
        routes = setOf(PlaybackRoute.DIRECT),
    )

    /** The walk scripted, NewPipe replaced by [failsafe]. */
    private inner class Resolver(private val failsafe: () -> FacetResolution = { FacetResolution.Miss(MissReason.NOT_STREAMABLE) }) :
        YouTubeMusicStreamResolver(YouTubeMusicSource.SOURCE_ID, YouTubeMusicDownloader({ "Test" }), walker = walker()) {
        var failsafeAsked = 0
        override fun failsafe(track: Track, videoId: String, quality: QualityRequest): FacetResolution {
            failsafeAsked++
            return failsafe()
        }
    }

    @Test
    fun `the player gets the proven stream, asked as its app`() = runBlocking {
        script.players["ANDROID_VR"] = { HttpResponse(200, ok("ANDROID_VR", 251)) }
        script.serving += url("ANDROID_VR", 251)
        val resolver = Resolver()
        val media = ((resolver.resolve(song(), QualityRequest.Maximum) as FacetResolution.Resolved).target as PlaybackTarget.DirectStream).media
        assertEquals(url("ANDROID_VR", 251), media.uri)
        assertEquals(mapOf("User-Agent" to PlayerIdentities.ANDROID_VR.userAgent), media.headers)
        assertEquals(Codec.OPUS, media.claimedQuality?.codec)
        assertEquals(0, resolver.failsafeAsked)
        // Refused while playing: that app goes last, and the song is asked for afresh.
        resolver.forget(song())
        assertEquals(listOf("VISIONOS", "IOS", "ANDROID_VR", "ANDROID"), resolver.walkOrder())
    }

    @Test
    fun `when every app's stream is refused, the failsafe's stream plays only if it serves audio`() = runBlocking {
        PlayerIdentities.ALL.forEach { app -> script.players[app.key] = { HttpResponse(200, ok(app.key, 251)) } }
        val newPipeUrl = url("VISIONOS", 140)
        val fromNewPipe = FacetResolution.Resolved(
            PlaybackTarget.DirectStream(song().id, app.podium.sources.api.PlayableMedia(uri = newPipeUrl, sourceId = YouTubeMusicSource.SOURCE_ID, cacheKey = "k")),
        )
        val refusedToo = Resolver { fromNewPipe }
        assertEquals(FacetResolution.Miss(MissReason.NOT_STREAMABLE), refusedToo.resolve(song(), QualityRequest.Maximum), "refused everywhere: can't play right now")
        assertEquals(1, refusedToo.failsafeAsked)

        script.serving += newPipeUrl
        val served = Resolver { fromNewPipe }
        assertEquals(fromNewPipe, served.resolve(song(), QualityRequest.Maximum))
    }

    @Test
    fun `bot checks everywhere rest the source briefly, gone songs are misses without the failsafe`() = runBlocking {
        PlayerIdentities.ALL.forEach { app -> script.players[app.key] = { HttpResponse(200, unavailable("LOGIN_REQUIRED", "Sign in to confirm you're not a bot")) } }
        val bot = assertIs<FacetResolution.Failed>(Resolver().resolve(song(), QualityRequest.Maximum))
        assertEquals(HealthOutcome.RATE_LIMIT, bot.outcome)
        assertEquals(YouTubeMusicStreamResolver.BOT_CHECK_REST_MS, bot.retryAfterMillis)

        script.players.clear()
        val gone = Resolver()
        assertEquals(FacetResolution.Miss(MissReason.NOT_FOUND), gone.resolve(song(), QualityRequest.Maximum))
        assertEquals(0, gone.failsafeAsked)
    }
}
