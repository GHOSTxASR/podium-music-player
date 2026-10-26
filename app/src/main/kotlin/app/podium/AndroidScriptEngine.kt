package app.podium

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import app.podium.sources.youtubemusic.ScriptEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import kotlin.coroutines.resume

/**
 * JavaScript for the online source's challenge solver (D-98), off the app's own process where the
 * phone allows: JavaScriptSandbox (V8 in WebView's sandboxed process) when it's offered with large
 * scripts, else a hidden WebView that never goes online. One context is kept between calls, and both
 * are closed after [IDLE_MS] unused (the solver loads its prepared player again from the cache).
 *
 * Nothing it runs comes from outside Podium but YouTube's player script, read by the solver as data.
 */
internal class AndroidScriptEngine(context: Context) : ScriptEngine {
    private val app = context.applicationContext
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var idle: Job? = null

    private var sandbox: JavaScriptSandbox? = null
    private var isolate: JavaScriptIsolate? = null
    private var page: HiddenPage? = null

    override suspend fun evaluate(script: String): String = lock.withLock {
        idle?.cancel()
        try {
            if (!ScriptEngineDebug.forceWebView && sandboxReady()) {
                isolate!!.evaluateJavaScriptAsync(script).await()
            } else {
                (page ?: HiddenPage(app).also { page = it }).evaluate(script)
            }
        } finally {
            idle = scope.launch {
                delay(IDLE_MS)
                lock.withLock { close("idle") }
            }
        }
    }

    override fun reset() {
        scope.launch { lock.withLock { close("reset") } }
    }

    /** The sandbox and its isolate, started when first needed; false when this phone doesn't offer them. */
    private suspend fun sandboxReady(): Boolean {
        if (isolate != null) return true
        if (!supported) return false
        return try {
            val s = sandbox ?: JavaScriptSandbox.createConnectedInstanceAsync(app).await().also { sandbox = it }
            // Scripts and answers here are megabytes: without this feature they'd fail over binder.
            if (!s.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_EVALUATE_WITHOUT_TRANSACTION_LIMIT)) {
                supported = false
                close("no large scripts")
                return false
            }
            isolate = s.createIsolate()
            Log.d(TAG, "script engine: JavaScriptSandbox")
            true
        } catch (e: Exception) {
            Log.w(TAG, "JavaScriptSandbox unavailable (${e.javaClass.simpleName}); using a hidden WebView")
            supported = false
            close("sandbox failed")
            false
        }
    }

    private suspend fun close(why: String) {
        if (isolate != null || page != null) Log.d(TAG, "script engine closed: $why")
        runCatching { isolate?.close() }
        runCatching { sandbox?.close() }
        isolate = null
        sandbox = null
        page?.let { p -> withContext(Dispatchers.Main) { p.destroy() } }
        page = null
    }

    /**
     * A WebView never shown and never online (network loads blocked), holding one page whose context
     * the scripts share. Created and driven on the main thread, as WebView requires; answers are
     * decoded off it.
     */
    private class HiddenPage(private val context: Context) {
        private var view: WebView? = null
        private val loaded = CompletableDeferred<Unit>()

        @SuppressLint("SetJavaScriptEnabled")
        private fun create(): WebView = WebView(context).apply {
            Log.d(TAG, "script engine: a hidden WebView")
            settings.javaScriptEnabled = true
            settings.blockNetworkLoads = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                // Nothing the scripts do may take the page elsewhere: YouTube's player script, run
                // here as data, navigates a page it finds itself in, which wiped the solver's context.
                override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean = true

                override fun onPageFinished(view: WebView, url: String?) {
                    Log.d(TAG, "page finished: ${if (url?.startsWith(PAGE) == true) "ours" else "another (${url?.substringBefore(':')})"}")
                    // The WebView's own blank page finishes first; scripts run there are lost when ours replaces it.
                    if (url?.startsWith(PAGE) == true) {
                        view.evaluateJavascript("globalThis.$MARK=1") { loaded.complete(Unit) }
                    }
                }
            }
            // Its own address, never fetched (the page is given here, and network loads are blocked).
            loadDataWithBaseURL(PAGE, "<!doctype html><html><body></body></html>", "text/html", "utf-8", null)
        }

        suspend fun evaluate(script: String): String {
            val view = withContext(Dispatchers.Main) { view ?: create().also { view = it } }
            loaded.await()
            // A script that throws hands back null: its error is caught and handed back marked instead.
            // A page whose context was replaced (no mark) fails at once, so the solver starts over.
            val wrapped = "try{\nif(!globalThis.$MARK)throw new Error('page replaced');\n$script\n}" +
                "catch(e){'$ERROR'+String(e&&e.name)+': '+String(e&&e.message).slice(0,120)}"
            val raw = withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont -> view.evaluateJavascript(wrapped) { cont.resume(it) } }
            }
            // The page hands back the value as JSON: a string comes quoted.
            val value = withContext(Dispatchers.Default) { runCatching { Json.parseToJsonElement(raw ?: "null") }.getOrNull() }
            if (value == null || value is JsonNull) throw IOException("no value")
            val text = (value as? JsonPrimitive)?.content ?: value.toString()
            if (text.startsWith(ERROR)) throw IOException(text.removePrefix(ERROR))
            return text
        }

        fun destroy() {
            view?.destroy()
            view = null
        }

        private companion object {
            const val ERROR = "podium-script-error:"
            const val PAGE = "https://scripts.podium.invalid/"
            const val MARK = "__podiumPage"
        }
    }

    companion object {
        private const val TAG = "PodiumScripts"

        /** Unused this long, the sandbox (or page) is closed. */
        const val IDLE_MS = 3 * 60_000L

        /** Whether this phone offers JavaScriptSandbox; found out once. */
        @Volatile
        private var supported: Boolean = runCatching { JavaScriptSandbox.isSupported() }.getOrDefault(false)
    }
}

/** Debug builds only: run the solver in the hidden WebView even where JavaScriptSandbox is offered (D-98). */
internal object ScriptEngineDebug {
    @Volatile
    var forceWebView: Boolean = false
}
