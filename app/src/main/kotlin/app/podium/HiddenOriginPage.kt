package app.podium

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import app.podium.sources.youtubemusic.OriginPage
import app.podium.sources.youtubemusic.OriginPages
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Opens [HiddenOriginPage]s (D-98). */
internal class HiddenOriginPages(private val context: Context) : OriginPages {
    override fun open(origin: String, userAgent: String): OriginPage = HiddenOriginPage(context, origin, userAgent)
}

/**
 * A browser page at a site's origin that is never shown and never goes online itself (D-98): the
 * online source runs BotGuard in it. The page is given as data at [origin] (so its scripts see that
 * origin), network loads are blocked, file and content access are off, and every navigation it starts
 * is refused. Scripts run as global code; what they end with (a promise's value once it settles)
 * comes back through a bridge. Closed after [IDLE_MS] unused.
 */
internal class HiddenOriginPage(
    context: Context,
    private val origin: String,
    private val userAgent: String,
) : OriginPage {
    private val app = context.applicationContext
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var idle: Job? = null

    private var view: WebView? = null
    private var loaded = CompletableDeferred<Unit>()
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<String>>()
    private val ids = AtomicInteger()

    override suspend fun run(script: String): String = lock.withLock {
        idle?.cancel()
        try {
            val page = withContext(Dispatchers.Main) { view ?: create().also { view = it } }
            withTimeout(LOAD_TIMEOUT_MS) { loaded.await() }
            val id = ids.incrementAndGet()
            val answer = CompletableDeferred<String>().also { pending[id] = it }
            // The page's value as text: a promise is waited for; a failure comes back as one.
            val code = JsonPrimitive(script).toString()
            val wrapped = "(function(){try{if(!globalThis.$MARK)throw new Error('page replaced');" +
                "Promise.resolve((0,eval)($code)).then(function(v){$BRIDGE.done($id,String(v))}," +
                "function(e){$BRIDGE.fail($id,String(e&&e.message||e))})}" +
                "catch(e){$BRIDGE.fail($id,String(e&&e.message||e))}})()"
            withContext(Dispatchers.Main) { page.evaluateJavascript(wrapped, null) }
            try {
                withTimeout(RUN_TIMEOUT_MS) { answer.await() }
            } finally {
                pending.remove(id)
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

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun create(): WebView = WebView(app).apply {
        Log.d(TAG, "a hidden page")
        loaded = CompletableDeferred()
        settings.javaScriptEnabled = true
        settings.blockNetworkLoads = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.userAgentString = userAgent
        addJavascriptInterface(Bridge(), BRIDGE)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

            override fun onPageFinished(view: WebView, url: String?) {
                // The WebView's own blank page finishes first; only ours counts.
                if (url?.startsWith(origin) == true) view.evaluateJavascript("globalThis.$MARK=1") { loaded.complete(Unit) }
            }
        }
        loadDataWithBaseURL("$origin/", "<!doctype html><html><head></head><body></body></html>", "text/html", "utf-8", null)
    }

    private suspend fun close(why: String) {
        val v = view ?: return
        view = null
        Log.d(TAG, "hidden page closed: $why")
        pending.values.forEach { it.completeExceptionally(IOException("page closed")) }
        pending.clear()
        withContext(Dispatchers.Main) {
            v.loadUrl("about:blank")
            v.destroy()
        }
    }

    /** What the page's scripts call back with; only the page Podium loaded has it. */
    private inner class Bridge {
        @JavascriptInterface
        fun done(id: Int, value: String) {
            pending[id]?.complete(value)
        }

        @JavascriptInterface
        fun fail(id: Int, message: String) {
            pending[id]?.completeExceptionally(IOException(message.take(200)))
        }
    }

    private companion object {
        const val TAG = "PodiumPage"
        const val BRIDGE = "PodiumPage"
        const val MARK = "__podiumPage"
        const val LOAD_TIMEOUT_MS = 10_000L
        const val RUN_TIMEOUT_MS = 20_000L
        const val IDLE_MS = 10 * 60_000L
    }
}
