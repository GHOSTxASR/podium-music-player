package app.podium.sources.youtubemusic

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The apps that accept the listener's session (D-98): passed over when signed out; signed in, asked
 * with the session on YouTube's own host only, as the chosen channel, and never with it anywhere else.
 */
class SignedInWalkTest {
    private val video = "vid00000001"
    private val browser = "Mozilla/5.0 (Linux; Android 17) AppleWebKit/537.36 Mobile Safari/537.36"

    private class Asked(val url: String, val headers: Map<String, String>, val body: String) {
        val app get() = Json.parseToJsonElement(body).jsonObject["context"]!!.jsonObject["client"]!!.jsonObject["clientName"]!!.jsonPrimitive.content
    }

    /** YouTube, scripted: every app answers with one enciphered Opus stream, which serves once solved. */
    private class Script : StreamHttp {
        val asked = mutableListOf<Asked>()
        val probeHeaders = mutableListOf<Map<String, String>>()

        override suspend fun post(url: String, headers: Map<String, String>, body: ByteArray): HttpResponse {
            if (url == YouTubeStreamWalker.VISITOR_URL) return HttpResponse(200, """{"responseContext":{"visitorData":"guest"}}""".toByteArray())
            val a = Asked(url, headers, body.decodeToString()).also { asked += it }
            val c = a.app
            return HttpResponse(
                200,
                """{"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"vid00000001"},"streamingData":{"adaptiveFormats":[
                  {"itag":251,"mimeType":"audio/webm; codecs=\"opus\"","bitrate":135000,
                   "signatureCipher":"s=SIG&url=https%3A%2F%2Frr1.googlevideo.com%2Fvideoplayback%3Fc%3D$c%26n%3Dabc"}]}}""".toByteArray(),
            )
        }

        override suspend fun get(url: String, headers: Map<String, String>, maxBytes: Int): HttpResponse = when (url) {
            PlayerScripts.IFRAME_API -> HttpResponse(200, """player\/cbcfbf2f\/www-widgetapi""".toByteArray())
            else -> HttpResponse(200, "x={signatureTimestamp:20746}".toByteArray())
        }

        override suspend fun probe(url: String, headers: Map<String, String>, range: String): Int {
            probeHeaders += headers
            return if ("signature=ok" in url && "n=ok" in url) 206 else 403
        }
    }

    private class Solver : ScriptEngine {
        override suspend fun evaluate(script: String): String {
            if ("Object.assign(globalThis, lib)" in script || script.startsWith("globalThis.__podiumPlayer=")) return "ok"
            val requests = Regex("""requests:(\[\{"type":"n".*?]}])""", RegexOption.DOT_MATCHES_ALL).find(script)!!.groupValues[1]
            val parsed = Json.parseToJsonElement(requests).jsonArray
            fun answers(i: Int) = parsed[i].jsonObject["challenges"]!!.jsonArray.joinToString(",") { "\"${it.jsonPrimitive.content}\":\"ok\"" }
            return """{"type":"result","responses":[{"type":"result","data":{${answers(0)}}},{"type":"result","data":{${answers(1)}}}],"prepared":"P"}"""
        }

        override fun reset() = Unit
    }

    private val script = Script()

    private fun walker(identities: List<PlayerIdentity>, session: StreamSession?): YouTubeStreamWalker {
        val cache = DirectoryPlayerScriptCache(Files.createTempDirectory("podium-signed-in").toFile())
        return YouTubeStreamWalker(
            script, region = { "IN" }, now = { 1_000L }, identities = identities,
            scripts = PlayerScripts(script, EjsChallengeSolver(Solver(), cache), cache) { 1_000L },
            session = { session },
        )
    }

    private val session = StreamSession(
        cookie = "SAPISID=secret; __Secure-3PAPISID=secret",
        authorization = { origin -> "SAPISIDHASH 1_hash-for-$origin" },
        pageId = "page123",
        userAgent = browser,
    )

    @Test
    fun `signed out, the apps that need a session are passed over`() = runTest {
        val w = walker(listOf(PlayerIdentities.TVHTML5_SIGNED_IN, PlayerIdentities.WEB_REMIX_SIGNED_IN, PlayerIdentities.TVHTML5), null)
        assertEquals("TVHTML5", assertIs<YouTubeStreamWalker.Result.Found>(w.walk(video)).stream.identity)
        assertEquals(1, script.asked.size, "only the signed-out TV app was asked")
        assertNull(script.asked.single().headers["Cookie"])
    }

    @Test
    fun `signed in, the TV app is asked with the session on youtube's own host, as the chosen channel`() = runTest {
        val w = walker(listOf(PlayerIdentities.TVHTML5_SIGNED_IN, PlayerIdentities.TVHTML5), session)
        val found = assertIs<YouTubeStreamWalker.Result.Found>(w.walk(video)).stream
        assertEquals("TVHTML5_SIGNED_IN", found.identity)
        val asked = script.asked.single()
        assertTrue(asked.url.startsWith("https://www.youtube.com/youtubei/v1/player"))
        assertEquals(session.cookie, asked.headers["Cookie"])
        assertEquals("SAPISIDHASH 1_hash-for-https://www.youtube.com", asked.headers["Authorization"])
        assertEquals("https://www.youtube.com", asked.headers["X-Origin"])
        assertNull(asked.headers["X-Goog-Visitor-Id"], "the session's own cookies name the visitor")
        assertTrue("\"onBehalfOfUser\":\"page123\"" in asked.body)
        assertEquals(PlayerIdentities.TVHTML5.userAgent, found.userAgent, "the player asks as the TV app")
        // The stream's checks (and so the player) carry nothing of the session.
        assertTrue(script.probeHeaders.isNotEmpty())
        assertTrue(script.probeHeaders.all { it.keys == setOf("User-Agent") })
        assertEquals("TVHTML5_SIGNED_IN", w.identityOf(found.url), "a refusal while playing names this app")
    }

    @Test
    fun `signed in, YouTube Music's web player is asked on its own host from the listener's browser`() = runTest {
        val w = walker(listOf(PlayerIdentities.WEB_REMIX_SIGNED_IN), session)
        val found = assertIs<YouTubeStreamWalker.Result.Found>(w.walk(video)).stream
        val asked = script.asked.single()
        assertEquals("WEB_REMIX", asked.app)
        assertTrue(asked.url.startsWith("https://music.youtube.com/youtubei/v1/player"))
        assertEquals("SAPISIDHASH 1_hash-for-https://music.youtube.com", asked.headers["Authorization"])
        assertEquals(browser, asked.headers["User-Agent"])
        assertEquals(browser, found.userAgent)
        assertFalse(script.probeHeaders.any { "Cookie" in it || "Authorization" in it })
    }

    @Test
    fun `the session never shows up in a log line`() {
        assertEquals("StreamSession", session.toString())
    }
}
