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
 * every stream proven before the player gets it, refusals and bot checks never handed over.
 */
class YouTubeStreamWalkerTest {

    private val video = "vid00000001"

    /** YouTube, scripted: the player's answer per app, and which stream addresses serve bytes. */
    private class Script : StreamHttp {
        val players = mutableMapOf<String, () -> HttpResponse>()
        val serving = mutableSetOf<String>()
        val asked = mutableListOf<String>()
        val probed = mutableListOf<Pair<String, String?>>()
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

        override suspend fun probe(url: String, headers: Map<String, String>): Int {
            probed += url to headers["User-Agent"]
            return if (url in serving) 206 else 403
        }
    }

    private val script = Script()

    private fun walker() = YouTubeStreamWalker(script, region = { "IN" }, now = { 1_000_000L })

    private fun ok(app: String, vararg itags: Int, videoId: String = video): ByteArray {
        val formats = itags.joinToString(",") { itag ->
            val mime = if (itag in listOf(249, 250, 251)) "audio/webm; codecs=\\\"opus\\\"" else "audio/mp4; codecs=\\\"mp4a.40.2\\\""
            val bitrate = mapOf(251 to 135000, 250 to 70000, 249 to 50000, 140 to 130000, 139 to 48000)[itag] ?: 1000
            """{"itag":$itag,"url":"${url(app, itag)}","mimeType":"$mime","bitrate":$bitrate,"averageBitrate":$bitrate,"audioSampleRate":"48000","audioChannels":2}"""
        }
        return """{"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"$videoId","lengthSeconds":"213"},
            "streamingData":{"expiresInSeconds":"21540","adaptiveFormats":[{"itag":136,"url":"https://v.googlevideo.com/video","mimeType":"video/mp4"},$formats]}}""".toByteArray()
    }

    private fun url(app: String, itag: Int) = "https://rr1.googlevideo.com/videoplayback?expire=1021600&itag=$itag&c=$app"

    private companion object {
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
        assertEquals(listOf<Pair<String, String?>>(url("ANDROID_VR", 251) to PlayerIdentities.ANDROID_VR.userAgent), script.probed)
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
        assertEquals(listOf(url("ANDROID_VR", 251), url("ANDROID_VR", 140), url("VISIONOS", 251)), script.probed.map { it.first })
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
    fun `one app's "not available" isn't the last word`() = runTest {
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
    fun `an app whose stream was refused while playing goes last`() {
        val walker = walker()
        walker.demote("ANDROID_VR")
        assertEquals(listOf("VISIONOS", "IOS", "ANDROID", "ANDROID_VR"), walker.order())
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
        assertTrue(resolver.walkOrder().last() == "ANDROID_VR")
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
