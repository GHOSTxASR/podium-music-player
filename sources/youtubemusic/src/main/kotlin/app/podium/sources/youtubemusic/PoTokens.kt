package app.podium.sources.youtubemusic

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.util.Base64

/**
 * A browser page at a site's origin, never shown (D-98): BotGuard runs only in a real page. On the
 * phone, a hidden WebView that never goes online itself; in tests, a fake.
 */
interface OriginPage {
    /**
     * Runs [script] as global code in the page and gives back, as a string, what it ends with (a
     * promise's value once it settles). A script that throws, or a promise that rejects, throws.
     */
    suspend fun run(script: String): String

    /** Drops the page: the next [run] starts in a fresh one. */
    fun reset()
}

/** Opens [OriginPage]s: a page at [origin], as the browser [userAgent] (the app provides it, D-98). */
fun interface OriginPages {
    fun open(origin: String, userAgent: String): OriginPage
}

/**
 * PoTokens minted by YouTube's BotGuard (D-98), as NewPipe and Metrolist mint them (both GPL-3.0,
 * like Podium; this is Podium's own code after their `PoTokenWebView` and `po_token.html`):
 *
 * 1. Podium asks `jnn/v1/Create` for a challenge (from Kotlin: the page never goes online).
 * 2. The challenge's interpreter and program run in the [page] (YouTube's origin), and BotGuard's
 *    answer comes back.
 * 3. `jnn/v1/GenerateIT` turns that answer into an integrity token, good for hours.
 * 4. Each PoToken is minted in the page from the integrity token and what it's bound to: the guest
 *    visitor id for streams (`pot=`), or a video id.
 *
 * Tokens are kept per binding until the integrity token runs out. [forget] starts over (a stream
 * refused with a token: BotGuard asked again). Failures give null; the walk goes on without.
 */
internal class BotGuardMinter(
    private val http: StreamHttp,
    private val page: OriginPage,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private var expiresAt = 0L
    private var startedAt = 0L
    private val tokens = HashMap<String, String>()

    /**
     * A PoToken bound to [identifier] (a visitor id or a video id), or null when BotGuard can't make
     * one. A page that lost BotGuard (closed while idle, say) is started again once.
     */
    suspend fun token(identifier: String): String? = lock.withLock {
        tokens[identifier]?.let { if (now() < expiresAt) return@withLock it }
        repeat(2) { attempt ->
            try {
                if (now() >= expiresAt) start()
                return@withLock mint(identifier).also { tokens[identifier] = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                StreamWalkDebug.note { "potoken: ${e.javaClass.simpleName} ${e.message.orEmpty().take(120)}${if (attempt == 0) ", from the start" else ""}" }
                expiresAt = 0L
                tokens.clear()
                runCatching { page.reset() }
            }
        }
        null
    }

    /**
     * A stream was refused with a token: BotGuard is asked again from the start next time, unless it
     * was only just asked (then it's the app, not the token). Whether it will be.
     */
    suspend fun forget(): Boolean = lock.withLock {
        if (expiresAt != 0L && now() - startedAt < REMINT_AFTER_MS) return@withLock false
        expiresAt = 0L
        tokens.clear()
        runCatching { page.reset() }
        true
    }

    private suspend fun start() {
        val started = now()
        startedAt = started
        page.run(HELPERS)
        val challenge = ask(CREATE_URL, "[\"$REQUEST_KEY\"]")
        val data = challengeData(challenge)
        val botguard = page.run(
            "runBotGuard($data).then(function (r) { webPoSignalOutput = r.webPoSignalOutput; return r.botguardResponse; })",
        )
        val answer = ask(GENERATE_URL, Json.encodeToString(JsonArray.serializer(), JsonArray(listOf(JsonPrimitive(REQUEST_KEY), JsonPrimitive(botguard)))))
        val it = Json.parseToJsonElement(answer) as? JsonArray ?: throw IOException("no integrity token")
        val token = (it.getOrNull(0) as? JsonPrimitive)?.contentOrNull ?: throw IOException("no integrity token")
        val ttl = (it.getOrNull(1) as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: DEFAULT_TTL_S
        page.run("integrityToken = ${uint8(decode(token))}; 'ok'")
        // A margin, as NewPipe keeps: a token minted near the end would outlive its integrity token.
        expiresAt = now() + (ttl - MARGIN_S).coerceAtLeast(MIN_TTL_S) * 1000
        tokens.clear()
        StreamWalkDebug.note { "potoken: BotGuard ready in ${now() - started} ms, good for ${ttl / 60} min" }
    }

    private suspend fun mint(identifier: String): String {
        val bytes = page.run(
            "(function () { var t = obtainPoToken(webPoSignalOutput, integrityToken, ${uint8(identifier.toByteArray())});" +
                " var s = ''; for (var i = 0; i < t.length; i++) { if (i) s += ','; s += t[i]; } return s; })()",
        )
        val raw = bytes.split(',').map { it.trim().toInt().toByte() }.toByteArray()
        return Base64.getEncoder().encodeToString(raw).replace('+', '-').replace('/', '_')
    }

    /** One BotGuard service request, from Kotlin as YouTube's web page sends it. */
    private suspend fun ask(url: String, body: String): String {
        val response = http.post(
            url,
            mapOf(
                "Content-Type" to "application/json+protobuf",
                "Accept" to "application/json",
                "User-Agent" to USER_AGENT,
                // YouTube's public web key, as its own pages send it with these requests (not a secret).
                "x-goog-api-key" to PUBLIC_WEB_KEY,
                "x-user-agent" to "grpc-web-javascript/0.1",
            ),
            body.encodeToByteArray(),
        )
        if (response.code !in 200..299) throw IOException("HTTP ${response.code}")
        return response.body.decodeToString()
    }

    internal companion object {
        const val ORIGIN = "https://www.youtube.com"
        const val CREATE_URL = "https://www.youtube.com/api/jnn/v1/Create"
        const val GENERATE_URL = "https://www.youtube.com/api/jnn/v1/GenerateIT"
        const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
        const val PUBLIC_WEB_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"

        /** The browser the page is, and the BotGuard requests come from. */
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"

        /** A refused token makes BotGuard start over only when it last did longer ago than this. */
        const val REMINT_AFTER_MS = 5 * 60_000L

        const val DEFAULT_TTL_S = 12 * 60 * 60L
        const val MARGIN_S = 10 * 60L
        const val MIN_TTL_S = 60L

        /**
         * The challenge as the page's `runBotGuard` takes it. `Create` answers either with the data
         * itself, or scrambled (base64, each byte less 97) in its second element.
         */
        fun challengeData(raw: String): String {
            val outer = Json.parseToJsonElement(raw) as? JsonArray ?: throw IOException("no challenge")
            val data = if (outer.size > 1 && (outer[1] as? JsonPrimitive)?.isString == true) {
                val scrambled = decode(outer[1].let { (it as JsonPrimitive).content })
                Json.parseToJsonElement(scrambled.map { (it + 97).toByte() }.toByteArray().decodeToString()) as? JsonArray
            } else {
                outer.getOrNull(0) as? JsonArray
            } ?: throw IOException("no challenge")
            fun str(i: Int) = (data.getOrNull(i) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
            fun firstString(i: Int) = (data.getOrNull(i) as? JsonArray)?.firstOrNull { (it as? JsonPrimitive)?.isString == true }?.let { (it as JsonPrimitive).content }
            return buildJsonObject {
                put("messageId", str(0))
                putJsonObject("interpreterJavascript") {
                    put("privateDoNotAccessOrElseSafeScriptWrappedValue", firstString(1))
                    put("privateDoNotAccessOrElseTrustedResourceUrlWrappedValue", firstString(2))
                }
                put("interpreterHash", str(3))
                put("program", str(4) ?: throw IOException("no program"))
                put("globalName", str(5) ?: throw IOException("no global name"))
                put("clientExperimentsStateBlob", str(7))
            }.toString()
        }

        /** YouTube's base64 (url-safe, `.` for padding) to bytes. */
        fun decode(s: String): ByteArray = Base64.getDecoder().decode(s.replace('-', '+').replace('_', '/').replace('.', '='))

        fun uint8(bytes: ByteArray): String = bytes.joinToString(",", "new Uint8Array([", "])") { (it.toInt() and 0xff).toString() }

        /**
         * The page's helpers, after NewPipe's `po_token.html` (GPL-3.0): load the BotGuard VM the
         * challenge brings, take its snapshot, and mint a token from the integrity token.
         */
        val HELPERS = """
            function loadBotGuard(challengeData) {
              this.vm = this[challengeData.globalName];
              this.program = challengeData.program;
              this.vmFunctions = {};
              this.syncSnapshotFunction = null;
              if (!this.vm) throw new Error('BotGuard VM not found');
              if (!this.vm.a) throw new Error('BotGuard program not loadable');
              var vmFunctionsCallback = function (asyncSnapshotFunction, shutdownFunction, passEventFunction, checkCameraFunction) {
                this.vmFunctions = { asyncSnapshotFunction: asyncSnapshotFunction, shutdownFunction: shutdownFunction,
                  passEventFunction: passEventFunction, checkCameraFunction: checkCameraFunction };
              };
              this.syncSnapshotFunction = this.vm.a(this.program, vmFunctionsCallback, true, undefined, function () {}, [[], []])[0];
              return new Promise(function (resolve, reject) {
                var i = 0;
                var id = setInterval(function () {
                  if (!!this.vmFunctions.asyncSnapshotFunction) { clearInterval(id); resolve(this); }
                  if (i >= 10000) { clearInterval(id); reject(new Error('BotGuard snapshot not ready after 10 s')); }
                  i += 1;
                }, 1);
              });
            }
            function snapshot(args) {
              return new Promise(function (resolve, reject) {
                if (!this.vmFunctions.asyncSnapshotFunction) return reject(new Error('BotGuard snapshot function missing'));
                this.vmFunctions.asyncSnapshotFunction(function (response) { resolve(response); },
                  [args.contentBinding, args.signedTimestamp, args.webPoSignalOutput, args.skipPrivacyBuffer]);
              });
            }
            function runBotGuard(challengeData) {
              var interpreter = challengeData.interpreterJavascript.privateDoNotAccessOrElseSafeScriptWrappedValue;
              if (!interpreter) throw new Error('BotGuard interpreter missing');
              new Function(interpreter)();
              var webPoSignalOutput = [];
              return loadBotGuard({ globalName: challengeData.globalName, program: challengeData.program })
                .then(function () { return snapshot({ webPoSignalOutput: webPoSignalOutput }); })
                .then(function (botguardResponse) { return { webPoSignalOutput: webPoSignalOutput, botguardResponse: botguardResponse }; });
            }
            function obtainPoToken(webPoSignalOutput, integrityToken, identifier) {
              var getMinter = webPoSignalOutput[0];
              if (!getMinter) throw new Error('PMD:Undefined');
              var mintCallback = getMinter(integrityToken);
              if (!(mintCallback instanceof Function)) throw new Error('APF:Failed');
              var result = mintCallback(identifier);
              if (!result) throw new Error('YNJ:Undefined');
              if (!(result instanceof Uint8Array)) throw new Error('ODM:Invalid');
              return result;
            }
            'ok'
        """.trimIndent()
    }
}
