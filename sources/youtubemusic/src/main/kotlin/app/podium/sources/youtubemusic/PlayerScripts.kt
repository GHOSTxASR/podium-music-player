package app.podium.sources.youtubemusic

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.io.IOException

/**
 * Runs JavaScript for the challenge solver (D-98), in one context kept between calls. On the phone:
 * JavaScriptSandbox, else a hidden WebView (the app provides it); in tests, a fake; in the opt-in live
 * test, node.
 */
interface ScriptEngine {
    /** Evaluates [script] in the kept context and gives back the string its last expression is. */
    suspend fun evaluate(script: String): String

    /** Drops the context: the next [evaluate] starts in a fresh one. */
    fun reset()
}

/** Where a player script's prepared form and signature timestamp are kept between runs, per player id. */
interface PlayerScriptCache {
    fun read(playerId: String, name: String): String?

    fun write(playerId: String, name: String, text: String)
}

/**
 * [PlayerScriptCache] in a folder of the app's cache: one folder per player id, the newest few kept
 * (names starting with `_` are notes, never dropped).
 */
class DirectoryPlayerScriptCache(private val dir: File, private val keep: Int = 3) : PlayerScriptCache {
    override fun read(playerId: String, name: String): String? =
        runCatching { File(File(dir, safe(playerId)), safe(name)).takeIf { it.isFile }?.readText() }.getOrNull()

    override fun write(playerId: String, name: String, text: String) {
        runCatching {
            val folder = File(dir, safe(playerId)).apply { mkdirs() }
            val target = File(folder, safe(name))
            val part = File(folder, safe(name) + ".part")
            part.writeText(text)
            if (!part.renameTo(target)) {
                target.delete()
                part.renameTo(target)
            }
            folder.setLastModified(System.currentTimeMillis())
            dir.listFiles { f -> f.isDirectory && !f.name.startsWith("_") }
                ?.sortedByDescending { it.lastModified() }?.drop(keep)?.forEach { it.deleteRecursively() }
        }
    }

    private fun safe(s: String) = s.filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }.ifEmpty { "_" }
}

/** The solved challenges: each `n` and each enciphered signature, to its answer. */
internal data class Solutions(val n: Map<String, String>, val sig: Map<String, String>)

/**
 * yt-dlp's challenge solver (yt-dlp-ejs 0.8.0: `lib.min.js` with meriyah and astring, and
 * `core.min.js`; Unlicense, ISC, MIT — third_party/CODE.md), run on YouTube's own player script by a
 * [ScriptEngine] (D-98). The script is prepared once per player id and the prepared form kept in
 * [cache], so later runs (and later starts) skip the slow part. Challenges are solved in one batch.
 * An engine that fails (its context lost, say) is reset and asked once more from the start; a second
 * failure gives null, and the walk moves on.
 */
internal class EjsChallengeSolver(
    private val engine: ScriptEngine,
    private val cache: PlayerScriptCache,
    private val lib: () -> String = { resource("lib.min.js") },
    private val core: () -> String = { resource("core.min.js") },
) {
    private val lock = Mutex()
    private var ready = false
    private var loaded: String? = null

    suspend fun solve(playerId: String, script: suspend () -> String?, n: Set<String>, sig: Set<String>): Solutions? = lock.withLock {
        if (n.isEmpty() && sig.isEmpty()) return@withLock Solutions(emptyMap(), emptyMap())
        repeat(ATTEMPTS - 1) { attempt(playerId, script, n, sig, last = false)?.let { return@withLock it } }
        attempt(playerId, script, n, sig, last = true)
    }

    private suspend fun attempt(playerId: String, script: suspend () -> String?, n: Set<String>, sig: Set<String>, last: Boolean): Solutions? {
        return try {
            if (!ready) {
                engine.evaluate(lib() + "\n;Object.assign(globalThis, lib);\n" + core() + "\n;'ok'")
                ready = true
            }
            val requests = requests(n, sig)
            val answer = if (loaded == playerId) {
                engine.evaluate("JSON.stringify(jsc({type:'preprocessed',preprocessed_player:globalThis.__podiumPlayer,requests:$requests}))")
            } else {
                val prepared = cache.read(playerId, PREPARED)
                if (prepared != null) {
                    engine.evaluate("globalThis.__podiumPlayer=${quote(prepared)};'ok'")
                    loaded = playerId
                    engine.evaluate("JSON.stringify(jsc({type:'preprocessed',preprocessed_player:globalThis.__podiumPlayer,requests:$requests}))")
                } else {
                    val raw = script() ?: return null
                    val out = engine.evaluate(
                        "(function(){var r=jsc({type:'player',player:${quote(raw)},requests:$requests,output_preprocessed:true});" +
                            "globalThis.__podiumPlayer=r.preprocessed_player;" +
                            "return JSON.stringify({type:r.type,responses:r.responses,prepared:r.preprocessed_player});})()",
                    )
                    val prepared2 = (Json.parseToJsonElement(out) as? JsonObject)?.get("prepared")?.let { (it as? JsonPrimitive)?.contentOrNull }
                    if (prepared2 != null) {
                        cache.write(playerId, PREPARED, prepared2)
                        loaded = playerId
                    }
                    out
                }
            }
            read(answer)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The engine's own words (a script error, say), never an address: none goes into a script but the player's.
            StreamWalkDebug.note { "solver: ${e.javaClass.simpleName} ${e.message.orEmpty().take(120)}${if (last) "" else ", once more"}" }
            ready = false
            loaded = null
            runCatching { engine.reset() }
            null
        }
    }

    private fun requests(n: Set<String>, sig: Set<String>): String = buildJsonArray {
        addJsonObject {
            put("type", "n")
            putJsonArray("challenges") { n.forEach { add(it) } }
        }
        addJsonObject {
            put("type", "sig")
            putJsonArray("challenges") { sig.forEach { add(it) } }
        }
    }.toString()

    /** The solver's answer: `{type:"result", responses:[{type:"result", data:{challenge: answer}}, …]}`, n first. */
    private fun read(answer: String): Solutions? {
        val o = Json.parseToJsonElement(answer) as? JsonObject ?: return null
        if ((o["type"] as? JsonPrimitive)?.contentOrNull != "result") return null
        val responses = (o["responses"] as? JsonArray)?.map { it as? JsonObject } ?: return null
        fun data(i: Int): Map<String, String> {
            val r = responses.getOrNull(i) ?: return emptyMap()
            if ((r["type"] as? JsonPrimitive)?.contentOrNull != "result") return emptyMap()
            return (r["data"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }?.toMap().orEmpty()
        }
        return Solutions(n = data(0), sig = data(1))
    }

    internal companion object {
        const val PREPARED = "player.prepared.js"

        /** An engine that fails is asked this many times in all. */
        const val ATTEMPTS = 2

        /** [s] as a JavaScript string literal (JSON's, which JavaScript reads the same). */
        fun quote(s: String): String = JsonPrimitive(s).toString()

        fun resource(name: String): String =
            EjsChallengeSolver::class.java.classLoader.getResourceAsStream("app/podium/sources/youtubemusic/ejs/$name")
                ?.use { it.readBytes().decodeToString() }
                ?: throw IOException("solver missing")
    }
}

/**
 * YouTube's player script, for the apps whose streams come enciphered (D-98): which player is current
 * (from `iframe_api`, rechecked every few hours), its signature timestamp (from the script, kept per
 * player id), and the challenges solved on it ([EjsChallengeSolver]). The script itself is fetched
 * only when its prepared form isn't kept yet.
 */
internal class PlayerScripts(
    private val http: StreamHttp,
    private val solver: EjsChallengeSolver,
    private val cache: PlayerScriptCache,
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class Player(val id: String, val signatureTimestamp: Int)

    private val lock = Mutex()
    @Volatile private var current: Player? = null
    @Volatile private var checkedAt = 0L

    /** The script fetched for the timestamp, kept until it has been prepared once. */
    @Volatile private var fetched: Pair<String, String>? = null

    suspend fun current(): Player? = lock.withLock {
        current?.let { if (now() - checkedAt < PLAYER_TTL_MS) return@withLock it }
        val id = playerId() ?: current?.id ?: cache.read(LATEST, "id") ?: return@withLock null
        val sts = cache.read(id, "sts")?.toIntOrNull() ?: script(id)?.let(::signatureTimestamp)?.also { cache.write(id, "sts", it.toString()) }
            ?: return@withLock current
        cache.write(LATEST, "id", id)
        Player(id, sts).also {
            current = it
            checkedAt = now()
        }
    }

    suspend fun solve(player: Player, n: Set<String>, sig: Set<String>): Solutions? =
        solver.solve(player.id, { script(player.id) }, n, sig).also { if (it != null) fetched = null }

    private suspend fun playerId(): String? = try {
        val answer = http.get(IFRAME_API, emptyMap(), SMALL_BYTES)
        if (answer.code in 200..299) PLAYER_ID.find(answer.body.decodeToString())?.groupValues?.get(1) else null
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        null
    }

    private suspend fun script(id: String): String? {
        fetched?.let { (fid, text) -> if (fid == id) return text }
        return try {
            val answer = http.get("https://www.youtube.com/s/player/$id/player_ias.vflset/en_US/base.js", emptyMap(), SCRIPT_BYTES)
            if (answer.code in 200..299) answer.body.decodeToString().also { fetched = id to it } else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            null
        }
    }

    internal companion object {
        const val IFRAME_API = "https://www.youtube.com/iframe_api"
        const val PLAYER_TTL_MS = 6 * 60 * 60_000L
        const val SMALL_BYTES = 512 * 1024
        const val SCRIPT_BYTES = 12 * 1024 * 1024
        private const val LATEST = "_latest"

        private val PLAYER_ID = Regex("""player\\?/([0-9a-f]{8})\\?/""")
        private val STS = Regex("""(?:signatureTimestamp|sts)\s*[:=]\s*(\d{5})""")

        fun signatureTimestamp(script: String): Int? = STS.find(script)?.groupValues?.get(1)?.toIntOrNull()
    }
}
