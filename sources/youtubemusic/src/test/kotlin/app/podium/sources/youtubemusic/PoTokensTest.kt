package app.podium.sources.youtubemusic

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.nio.file.Files
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PoTokens from BotGuard (D-98): minted in a page at YouTube's origin, kept per binding, put on the
 * streams of the apps that need them, bound the other way and then minted afresh when refused.
 */
class PoTokensTest {
    private val video = "vid00000001"
    private var clock = 1_000_000L

    /** BotGuard's page, scripted: each start mints a new generation of tokens. */
    private class FakePage : OriginPage {
        val runs = mutableListOf<String>()
        var generation = 1
        var resets = 0
        var failMint = false

        override suspend fun run(script: String): String {
            runs += script
            return when {
                script.startsWith("function loadBotGuard") -> "ok"
                script.startsWith("runBotGuard(") -> "bg-answer"
                script.startsWith("integrityToken = ") -> "ok"
                "obtainPoToken" in script -> if (failMint) throw java.io.IOException("PMD:Undefined") else if (generation == 1) "1,2,3" else "4,5,6"
                else -> error("unexpected script")
            }
        }

        override fun reset() {
            resets++
            generation++
        }
    }

    /** YouTube, scripted: BotGuard's two services, the web player, and streams that serve with one token only. */
    private inner class Script : StreamHttp {
        val botguard = mutableListOf<Pair<String, Map<String, String>>>()
        val asked = mutableListOf<String>()
        var serveWith = "AQID" // base64url of 1,2,3
        var scrambled = false

        override suspend fun post(url: String, headers: Map<String, String>, body: ByteArray): HttpResponse {
            when (url) {
                BotGuardMinter.CREATE_URL -> {
                    botguard += url to headers
                    val data = """["msg",["var vmHere = 1;"],["https://example.invalid/vm.js"],"hash","PROGRAM","globalVm",null,"blob"]"""
                    return if (scrambled) {
                        val bytes = data.toByteArray().map { (it - 97).toByte() }.toByteArray()
                        HttpResponse(200, """[null,"${Base64.getEncoder().encodeToString(bytes)}"]""".toByteArray())
                    } else {
                        HttpResponse(200, "[$data]".toByteArray())
                    }
                }
                BotGuardMinter.GENERATE_URL -> {
                    botguard += url to headers
                    return HttpResponse(200, """["AAEC",43200,21600]""".toByteArray())
                }
                YouTubeStreamWalker.VISITOR_URL -> return HttpResponse(200, """{"responseContext":{"visitorData":"guest-visitor"}}""".toByteArray())
            }
            val app = Json.parseToJsonElement(body.decodeToString()).jsonObject["context"]!!.jsonObject["client"]!!.jsonObject["clientName"]!!.jsonPrimitive.content
            asked += app
            return HttpResponse(
                200,
                """{"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"$video"},"streamingData":{"adaptiveFormats":[
                  {"itag":251,"mimeType":"audio/webm; codecs=\"opus\"","bitrate":135000,
                   "signatureCipher":"s=S&url=https%3A%2F%2Frr1.googlevideo.com%2Fvideoplayback%3Fc%3D$app%26n%3Dn1"}]}}""".toByteArray(),
            )
        }

        override suspend fun get(url: String, headers: Map<String, String>, maxBytes: Int): HttpResponse = when (url) {
            PlayerScripts.IFRAME_API -> HttpResponse(200, """player\/cbcfbf2f\/w""".toByteArray())
            else -> HttpResponse(200, "x={signatureTimestamp:20746}".toByteArray())
        }

        override suspend fun probe(url: String, headers: Map<String, String>, range: String): Int =
            if ("pot=$serveWith" in url) 206 else 403
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
    private val page = FakePage()

    /** The web player as asked when a device test names it (it's a trial app by default). */
    private val webRemix = PlayerIdentities.WEB_REMIX.copy(trial = false)
    private val minter = BotGuardMinter(script, page) { clock }

    private fun walker(identities: List<PlayerIdentity>, poTokens: BotGuardMinter? = minter, session: StreamSession? = null): YouTubeStreamWalker {
        val cache = DirectoryPlayerScriptCache(Files.createTempDirectory("podium-pot").toFile())
        return YouTubeStreamWalker(
            script, region = { "IN" }, now = { clock }, identities = identities,
            scripts = PlayerScripts(script, EjsChallengeSolver(Solver(), cache), cache) { clock },
            session = { session }, poTokens = poTokens,
        )
    }

    @Test
    fun `a token is minted in the page from BotGuard's integrity token, and kept per binding`() = runTest {
        assertEquals("AQID", minter.token("guest-visitor"))
        // Asked as YouTube's web page asks, from Podium (the page never goes online).
        assertEquals(listOf(BotGuardMinter.CREATE_URL, BotGuardMinter.GENERATE_URL), script.botguard.map { it.first })
        script.botguard.forEach { (_, h) ->
            assertEquals("application/json+protobuf", h["Content-Type"])
            assertEquals(BotGuardMinter.PUBLIC_WEB_KEY, h["x-goog-api-key"])
        }
        // The challenge reached the page whole; the integrity token went in as bytes; the binding as bytes.
        assertTrue(page.runs.any { it.startsWith("runBotGuard(") && "\"program\":\"PROGRAM\"" in it && "\"globalName\":\"globalVm\"" in it && "var vmHere = 1;" in it })
        assertTrue(page.runs.any { it == "integrityToken = new Uint8Array([0,1,2]); 'ok'" })
        assertTrue(page.runs.last().contains("new Uint8Array([" + "guest-visitor".toByteArray().joinToString(",") + "])"))

        val runs = page.runs.size
        assertEquals("AQID", minter.token("guest-visitor"), "kept")
        assertEquals(runs, page.runs.size)
        minter.token(video)
        assertEquals(runs + 1, page.runs.size, "another binding: minted, BotGuard not asked again")
        assertEquals(2, script.botguard.size)
    }

    @Test
    fun `a scrambled challenge is read too`() = runTest {
        script.scrambled = true
        assertEquals("AQID", minter.token("guest-visitor"))
        assertTrue(page.runs.any { it.startsWith("runBotGuard(") && "\"program\":\"PROGRAM\"" in it })
    }

    @Test
    fun `a page that lost BotGuard is started again once, and twice lost gives no token`() = runTest {
        assertEquals("AQID", minter.token("a"))
        page.failMint = true
        assertNull(minter.token("b"))
        assertEquals(2, page.resets, "started over once, then given up")
        page.failMint = false
        assertEquals("BAUG", minter.token("c"), "a new generation")
    }

    @Test
    fun `the web player's stream gets a token bound to the guest visitor it was asked as`() = runTest {
        val found = assertIs<YouTubeStreamWalker.Result.Found>(walker(listOf(webRemix)).walk(video)).stream
        assertEquals("WEB_REMIX", found.identity)
        assertTrue("pot=AQID" in found.url)
        assertEquals(BotGuardMinter.USER_AGENT, found.userAgent)
        assertTrue(page.runs.last().contains("new Uint8Array([" + "guest-visitor".toByteArray().joinToString(",") + "])"))
    }

    @Test
    fun `refused with its token, the stream is tried bound to the video, then with BotGuard asked afresh`() = runTest {
        val w = walker(listOf(webRemix))
        assertIs<YouTubeStreamWalker.Result.Found>(w.walk(video))
        // The tokens stop serving; BotGuard was asked only just now, so it isn't asked again yet.
        script.serveWith = "BAUG"
        assertEquals(YouTubeStreamWalker.Result.Refused, w.walk(video))
        assertEquals(0, page.resets)
        // Minutes later, refused again: bound to the video, then BotGuard afresh, whose token serves.
        clock += BotGuardMinter.REMINT_AFTER_MS + 1
        val found = assertIs<YouTubeStreamWalker.Result.Found>(w.walk(video)).stream
        assertTrue("pot=BAUG" in found.url)
        assertEquals(1, page.resets)
        assertEquals(4, script.botguard.size, "Create and GenerateIT, twice")
    }

    @Test
    fun `without BotGuard the signed-out web player is passed over, the signed-in one still asked`() = runTest {
        val session = StreamSession("SAPISID=x", { "SAPISIDHASH 1_h" }, null, "Browser/1.0")
        val w = walker(listOf(webRemix, PlayerIdentities.WEB_REMIX_SIGNED_IN), poTokens = null, session = session)
        assertEquals(YouTubeStreamWalker.Result.Refused, w.walk(video), "no Premium here: refused without a token")
        assertEquals(listOf("WEB_REMIX"), script.asked, "only the signed-in one (its client name) was asked")
        assertFalse(page.runs.isNotEmpty())
    }

    @Test
    fun `signed in, a token (given by a device test) is bound to the video`() = runTest {
        script.serveWith = "AQID"
        val session = StreamSession("SAPISID=x", { "SAPISIDHASH 1_h" }, null, "Browser/1.0")
        StreamWalkDebug.potFor = setOf("WEB_REMIX_SIGNED_IN")
        try {
            val found = assertIs<YouTubeStreamWalker.Result.Found>(walker(listOf(PlayerIdentities.WEB_REMIX_SIGNED_IN), session = session).walk(video)).stream
            assertTrue("pot=AQID" in found.url)
            assertTrue(page.runs.last().contains("new Uint8Array([" + video.toByteArray().joinToString(",") + "])"))
        } finally {
            StreamWalkDebug.potFor = emptySet()
        }
    }

    @Test
    fun `the trial web player is asked only when a device test names it`() = runTest {
        assertEquals(YouTubeStreamWalker.Result.Unreachable, walker(listOf(PlayerIdentities.WEB_REMIX)).walk(video))
        assertTrue(script.asked.isEmpty(), "not asked")
        StreamWalkDebug.potFor = setOf("WEB_REMIX")
        try {
            assertIs<YouTubeStreamWalker.Result.Found>(walker(listOf(PlayerIdentities.WEB_REMIX)).walk(video))
        } finally {
            StreamWalkDebug.potFor = emptySet()
        }
    }

    @Test
    fun `a debug switch gives other apps a token too`() = runTest {
        StreamWalkDebug.potFor = setOf("TVHTML5")
        try {
            val found = assertIs<YouTubeStreamWalker.Result.Found>(walker(listOf(PlayerIdentities.TVHTML5)).walk(video)).stream
            assertTrue("pot=AQID" in found.url)
        } finally {
            StreamWalkDebug.potFor = emptySet()
        }
    }
}
