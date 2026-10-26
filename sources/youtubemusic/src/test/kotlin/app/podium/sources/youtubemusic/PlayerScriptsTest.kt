package app.podium.sources.youtubemusic

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The backup apps that need YouTube's player script (D-98): the current player and its signature
 * timestamp, challenges solved in one batch by the solver run on it, enciphered streams solved then
 * checked, the pre-roll ad's wait, and the walk moving on when any of it fails.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerScriptsTest {

    private val video = "vid00000001"
    private val playerId = "cbcfbf2f"
    private val scriptUrl = "https://www.youtube.com/s/player/$playerId/player_ias.vflset/en_US/base.js"

    /** The solver, scripted: it answers each challenge with "N_"/"S_" and the challenge, or fails. */
    private class FakeEngine : ScriptEngine {
        val scripts = mutableListOf<String>()
        var fail = false
        var resets = 0

        /** Fails this many evaluations before working (a context lost once). */
        var failFirst = 0

        override suspend fun evaluate(script: String): String {
            scripts += script
            if (fail) throw IOException("sandbox gone")
            if (failFirst > 0) {
                failFirst--
                throw IOException("page replaced")
            }
            if ("Object.assign(globalThis, lib)" in script) return "ok"
            if (script.startsWith("globalThis.__podiumPlayer=")) return "ok"
            val requests = Regex("""requests:(\[\{"type":"n".*?]}])""", RegexOption.DOT_MATCHES_ALL).find(script)!!.groupValues[1]
            val parsed = Json.parseToJsonElement(requests).jsonArray
            fun answers(i: Int, prefix: String) = parsed[i].jsonObject["challenges"]!!.jsonArray
                .joinToString(",") { c -> "\"${c.jsonPrimitive.content}\":\"$prefix${c.jsonPrimitive.content}\"" }
            val responses = """[{"type":"result","data":{${answers(0, "N_")}}},{"type":"result","data":{${answers(1, "S_")}}}]"""
            return if ("type:'player'" in script) {
                """{"type":"result","responses":$responses,"prepared":"PREPARED-$PLAYER_MARK"}"""
            } else {
                """{"type":"result","responses":$responses}"""
            }
        }

        override fun reset() {
            resets++
        }

        companion object {
            const val PLAYER_MARK = "prepared"
        }
    }

    /** YouTube, scripted: iframe_api and the player script, the TV app's answer, and which addresses serve. */
    private inner class Script : StreamHttp {
        val players = mutableMapOf<String, () -> HttpResponse>()
        val serving = mutableSetOf<String>()
        val pagesAsked = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        var servesFrom = 0L
        var clock: () -> Long = { 0L }

        override suspend fun post(url: String, headers: Map<String, String>, body: ByteArray): HttpResponse {
            val json = Json.parseToJsonElement(body.decodeToString()).jsonObject
            if (url == YouTubeStreamWalker.VISITOR_URL) return HttpResponse(200, """{"responseContext":{"visitorData":"v1"}}""".toByteArray())
            bodies += body.decodeToString()
            val app = json["context"]!!.jsonObject["client"]!!.jsonObject["clientName"]!!.jsonPrimitive.content
            return players[app]?.invoke() ?: HttpResponse(200, """{"playabilityStatus":{"status":"UNPLAYABLE","reason":"x"}}""".toByteArray())
        }

        override suspend fun get(url: String, headers: Map<String, String>, maxBytes: Int): HttpResponse {
            pagesAsked += url
            return when (url) {
                PlayerScripts.IFRAME_API -> HttpResponse(200, """var scriptUrl = 'https:\/\/www.youtube.com\/s\/player\/$playerId\/www-widgetapi.vflset\/www-widgetapi.js';""".toByteArray())
                scriptUrl -> HttpResponse(200, "var x={signatureTimestamp:20746};function y(){}".toByteArray())
                else -> HttpResponse(404, ByteArray(0))
            }
        }

        override suspend fun probe(url: String, headers: Map<String, String>, range: String): Int =
            if (url in serving && clock() >= servesFrom) 206 else 403
    }

    private val script = Script()

    private fun tvAnswer(ads: String = "") = HttpResponse(
        200,
        """{"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"$video","lengthSeconds":"200"},$ads
            "streamingData":{"adaptiveFormats":[
              {"itag":251,"mimeType":"audio/webm; codecs=\"opus\"","bitrate":135000,
               "signatureCipher":"s=SIG%3D251&sp=sig&url=https%3A%2F%2Frr1.googlevideo.com%2Fvideoplayback%3Fitag%3D251%26c%3DTVHTML5%26clen%3D3000000%26n%3DnAbC"},
              {"itag":250,"mimeType":"audio/webm; codecs=\"opus\"","bitrate":70000,
               "signatureCipher":"s=SIG250&url=https%3A%2F%2Frr1.googlevideo.com%2Fvideoplayback%3Fitag%3D250%26c%3DTVHTML5%26clen%3D1500000%26n%3DnAbC"}]}}""".toByteArray(),
    )

    /** The 251 address with its signature and n answered (URL-encoded as the solver's answers go in). */
    private val solved251 = "https://rr1.googlevideo.com/videoplayback?itag=251&c=TVHTML5&clen=3000000&n=N_nAbC&sig=S_SIG%3D251"

    private fun cacheDir(): File = Files.createTempDirectory("podium-player").toFile().apply { deleteOnExit() }

    private fun TestScope.walker(engine: ScriptEngine?, cache: PlayerScriptCache = DirectoryPlayerScriptCache(cacheDir())): YouTubeStreamWalker {
        val now = { currentTime }
        script.clock = now
        val scripts = engine?.let { PlayerScripts(script, EjsChallengeSolver(it, cache), cache, now) }
        return YouTubeStreamWalker(
            script, region = { "IN" }, now = now,
            identities = listOf(PlayerIdentities.TVHTML5, PlayerIdentities.ANDROID_VR),
            scripts = scripts,
        )
    }

    @Test
    fun `an enciphered stream is solved, then checked, then handed over`() = runTest {
        script.players["TVHTML5"] = { tvAnswer() }
        script.serving += solved251
        val engine = FakeEngine()
        val found = assertIs<YouTubeStreamWalker.Result.Found>(walker(engine).walk(video)).stream
        assertEquals("TVHTML5", found.identity)
        assertEquals(solved251, found.url)
        assertEquals(PlayerIdentities.TVHTML5.userAgent, found.userAgent, "the player asks as the TV app")
        // Asked with the player's signature timestamp; both formats' challenges in one batch.
        assertTrue("\"signatureTimestamp\":20746" in script.bodies.single())
        val batch = engine.scripts.single { "type:'player'" in it }
        assertTrue("\"nAbC\"" in batch && "\"SIG=251\"" in batch && "\"SIG250\"" in batch)
        assertEquals(listOf(PlayerScripts.IFRAME_API, scriptUrl), script.pagesAsked)
    }

    @Test
    fun `when the solver fails, the walk moves on to the next app`() = runTest {
        script.players["TVHTML5"] = { tvAnswer() }
        script.players["ANDROID_VR"] = {
            HttpResponse(200, """{"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"$video"},"streamingData":{"adaptiveFormats":[
                {"itag":251,"url":"https://rr2.googlevideo.com/videoplayback?itag=251&c=ANDROID_VR","mimeType":"audio/webm","bitrate":1}]}}""".toByteArray())
        }
        script.serving += "https://rr2.googlevideo.com/videoplayback?itag=251&c=ANDROID_VR"
        val engine = FakeEngine().apply { fail = true }
        val found = assertIs<YouTubeStreamWalker.Result.Found>(walker(engine).walk(video)).stream
        assertEquals("ANDROID_VR", found.identity)
        assertEquals(2, engine.resets, "asked once more in a fresh context, then the walk moves on")
    }

    @Test
    fun `a context lost once is set up again and the challenges still solved`() = runTest {
        script.players["TVHTML5"] = { tvAnswer() }
        script.serving += solved251
        val engine = FakeEngine().apply { failFirst = 1 }
        assertEquals("TVHTML5", assertIs<YouTubeStreamWalker.Result.Found>(walker(engine).walk(video)).stream.identity)
        assertEquals(1, engine.resets)
    }

    @Test
    fun `without a script engine the TV app is passed over`() = runTest {
        script.players["TVHTML5"] = { tvAnswer() }
        assertEquals(YouTubeStreamWalker.Result.Unplayable(app.podium.sources.api.MissReason.NOT_FOUND), walker(null).walk(video))
        assertTrue(script.bodies.none { "TVHTML5" in it }, "never asked")
    }

    @Test
    fun `a stream held back by a pre-roll ad is checked once it could be skipped`() = runTest {
        val ad = """"adPlacements":[{"adPlacementRenderer":{"config":{"adPlacementConfig":{"kind":"AD_PLACEMENT_KIND_START"}},
            "renderer":{"instreamVideoAdRenderer":{"skipOffsetMilliseconds":5000,"playerVars":"length_seconds=26&x=1"}}}}],"""
        script.players["TVHTML5"] = { tvAnswer(ad) }
        script.serving += solved251
        script.servesFrom = 3_000L // the address starts serving a little before the skip point
        val started = currentTime
        val found = assertIs<YouTubeStreamWalker.Result.Found>(walker(FakeEngine()).walk(video)).stream
        assertEquals(solved251, found.url)
        assertTrue(currentTime - started in 3_500L..5_000L, "checked early, at ${currentTime - started} ms")

        // Checked early and refused: once more just after the skip point, then it plays.
        script.servesFrom = currentTime + 5_100L
        val again = currentTime
        assertIs<YouTubeStreamWalker.Result.Found>(walker(FakeEngine()).walk(video))
        assertTrue(currentTime - again in 5_100L..5_600L, "waited for the skip point: ${currentTime - again} ms")
    }

    @Test
    fun `an ad too long to wait for is a refusal, not a wait`() = runTest {
        val ad = """"adPlacements":[{"adPlacementRenderer":{"config":{"adPlacementConfig":{"kind":"AD_PLACEMENT_KIND_START"}},
            "renderer":{"instreamVideoAdRenderer":{"playerVars":"length_seconds=30"}}}}],"""
        script.players["TVHTML5"] = { tvAnswer(ad) }
        script.serving += solved251
        val started = currentTime
        assertEquals(YouTubeStreamWalker.Result.Refused, walker(FakeEngine()).walk(video))
        assertTrue(currentTime - started < 1_000L)
    }

    @Test
    fun `the prepared player is kept, so a new start doesn't fetch the script again`() = runTest {
        script.players["TVHTML5"] = { tvAnswer() }
        script.serving += solved251
        val cache = DirectoryPlayerScriptCache(cacheDir())
        assertIs<YouTubeStreamWalker.Result.Found>(walker(FakeEngine(), cache).walk(video))
        assertEquals("PREPARED-prepared", cache.read(playerId, EjsChallengeSolver.PREPARED))
        assertEquals("20746", cache.read(playerId, "sts"))

        script.pagesAsked.clear()
        val fresh = FakeEngine()
        assertIs<YouTubeStreamWalker.Result.Found>(walker(fresh, cache).walk(video))
        assertEquals(listOf(PlayerScripts.IFRAME_API), script.pagesAsked, "the script itself isn't fetched again")
        assertTrue(fresh.scripts.any { it.startsWith("globalThis.__podiumPlayer=\"PREPARED-prepared\"") })
        assertTrue(fresh.scripts.none { "type:'player'" in it })
    }

    @Test
    fun `a backup that served goes first for ten minutes only, and isn't remembered`() = runTest {
        script.players["TVHTML5"] = { tvAnswer() }
        script.serving += solved251
        var kept: String? = null
        val memory = object : StreamWalkMemory {
            override fun lastServed() = kept
            override fun served(identity: String) { kept = identity }
        }
        val now = { currentTime }
        script.clock = now
        val cache = DirectoryPlayerScriptCache(cacheDir())
        val walker = YouTubeStreamWalker(
            script, region = { "IN" }, now = now,
            identities = listOf(PlayerIdentities.ANDROID_VR, PlayerIdentities.TVHTML5),
            memory = memory,
            scripts = PlayerScripts(script, EjsChallengeSolver(FakeEngine(), cache), cache, now),
        )
        assertEquals("TVHTML5", assertIs<YouTubeStreamWalker.Result.Found>(walker.walk(video)).stream.identity)
        assertEquals(listOf("TVHTML5", "ANDROID_VR"), walker.order(), "first while it's the one serving")
        assertEquals(null, kept, "a backup isn't what a restart starts with")

        kotlinx.coroutines.delay(YouTubeStreamWalker.BACKUP_FIRST_MS + 1)
        script.bodies.clear()
        walker.walk(video)
        assertTrue("ANDROID_VR" in script.bodies.first(), "the usual app is asked first again")
    }

    @Test
    fun `a cache keeps the newest few players and never its notes`() {
        val dir = cacheDir()
        val cache = DirectoryPlayerScriptCache(dir, keep = 2)
        cache.write("_latest", "id", "c")
        listOf("a", "b", "c").forEachIndexed { i, id ->
            cache.write(id, "sts", "$i")
            File(dir, id).setLastModified(1_000_000L * (i + 1))
        }
        cache.write("c", "sts", "2")
        assertEquals(setOf("_latest", "b", "c"), dir.listFiles()!!.map { it.name }.toSet())
        assertEquals("c", cache.read("_latest", "id"))
    }

    @Test
    fun `the signature timestamp is read from the player script`() {
        assertEquals(20746, PlayerScripts.signatureTimestamp("a,signatureTimestamp:20746,b"))
        assertEquals(20111, PlayerScripts.signatureTimestamp("x={sts:20111}"))
        assertEquals(null, PlayerScripts.signatureTimestamp("nothing here"))
    }

    // --- Live, opt-in: the real solver on YouTube's current player, run by node ----------------------

    /** Runs every script so far again in one node context and gives back the last one's value (tests only). */
    private class NodeEngine : ScriptEngine {
        private val so = mutableListOf<String>()

        override suspend fun evaluate(script: String): String {
            so += script
            val dir = Files.createTempDirectory("podium-node").toFile()
            val list = File(dir, "scripts.json").apply { writeText(kotlinx.serialization.json.JsonArray(so.map { kotlinx.serialization.json.JsonPrimitive(it) }).toString()) }
            val runner = File(dir, "run.js").apply {
                writeText(
                    """
                    const vm = require('vm'); const fs = require('fs');
                    const ctx = vm.createContext({}); let out;
                    for (const s of JSON.parse(fs.readFileSync(process.argv[2], 'utf8'))) out = vm.runInContext(s, ctx);
                    fs.writeFileSync(process.argv[3], String(out));
                    """.trimIndent(),
                )
            }
            val result = File(dir, "out.txt")
            val p = ProcessBuilder("node", runner.path, list.path, result.path).redirectErrorStream(true).start()
            val log = p.inputStream.readBytes().decodeToString()
            if (p.waitFor() != 0) throw IOException("node: ${log.take(300)}")
            return result.readText().also { dir.deleteRecursively() }
        }

        override fun reset() = so.clear()
    }

    @Test
    fun `live - the TV app's stream is solved and serves (PODIUM_LIVE_NETWORK=1, node)`() = kotlinx.coroutines.runBlocking {
        Assume.assumeTrue("needs the network", System.getenv("PODIUM_LIVE_NETWORK") == "1")
        Assume.assumeTrue("needs node", runCatching { ProcessBuilder("node", "--version").start().waitFor() == 0 }.getOrDefault(false))
        val http = OkHttpStreamHttp()
        val cache = DirectoryPlayerScriptCache(cacheDir())
        val walker = YouTubeStreamWalker(
            http, region = { "IN" }, identities = listOf(PlayerIdentities.TVHTML5), budgetMillis = 60_000,
            scripts = PlayerScripts(http, EjsChallengeSolver(NodeEngine(), cache), cache),
        )
        val found = assertIs<YouTubeStreamWalker.Result.Found>(walker.walk("NJAv_7lHUIU"), "Kesariya, as the TV app").stream
        assertEquals("TVHTML5", found.identity)
    }
}
