package app.podium

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.designsystem.type.PodiumText
import app.podium.core.model.SourceId
import app.podium.sources.api.MusicSource
import app.podium.sources.api.SetupProblem
import app.podium.sources.api.SignInResult
import app.podium.sources.api.WebSignIn
import kotlinx.coroutines.launch

/**
 * Signing in on the service's own web page (YOUTUBE_MUSIC_ARCHITECTURE.md §6). Generic: the source
 * describes the page ([WebSignIn]); this screen never knows which service it is.
 *
 * - The page is the service's: Podium never sees or stores the password; two-step checks and
 *   passkeys work as they do in a browser.
 * - The web view is locked down: https only, only the hosts the source names, no file or content
 *   access, no JavaScript bridge into Podium, no pop-up windows, Safe Browsing on, no cache.
 * - The moment the page has signed in, the session it left is handed to the source (which seals it
 *   with the Keystore) and the web view's own cookies and storage are wiped, so no unencrypted copy
 *   stays behind in the app's files. They are wiped again when the screen closes, whatever happened.
 */
class WebSignInActivity : ComponentActivity() {

    private var webView: WebView? = null
    private var completing = false
    private var message by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as PodiumApplication).graph
        val source = intent.getStringExtra(EXTRA_SOURCE)?.let { graph.registry.get(SourceId(it)) }
        val flow = source?.auth?.webSignIn
        if (source == null || flow == null) {
            finish()
            return
        }
        // Start from nothing: no session of anyone else's can carry over.
        wipeWebState()
        setContent {
            PodiumTheme(darkTheme = true) {
                val colors = PodiumTheme.colors
                val type = PodiumTheme.type
                Column(Modifier.fillMaxSize().background(colors.canvas).safeDrawingPadding()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        PodiumText(flow.title, type.row, colors.labelPrimary, modifier = Modifier.weight(1f))
                        PodiumText("Cancel", type.row, colors.labelSecondary, modifier = Modifier.clickable { finish() }.padding(8.dp))
                    }
                    PodiumText(
                        "You sign in on the service's own page. Podium never sees your password.",
                        type.caption,
                        colors.labelSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
                    )
                    val note = message
                    if (note != null) {
                        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                            PodiumText(note, type.body, colors.labelPrimary)
                        }
                    } else {
                        AndroidView(factory = { context -> buildWebView(context, flow, source) }, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled") // The service's sign-in page needs JavaScript; nothing of Podium's is exposed to it.
    private fun buildWebView(context: Context, flow: WebSignIn, source: MusicSource): WebView = WebView(context).apply {
        val view = this
        webView = view
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
            safeBrowsingEnabled = true
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            // The service's sign-in moves between its own domains (all on the allow-list).
            setAcceptThirdPartyCookies(view, true)
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                !allowed(request.url, flow)

            override fun onPageFinished(view: WebView, url: String) {
                if (url.startsWith(flow.doneUrlPrefix)) checkSignedIn(flow, source)
            }

            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel() // never proceed past a certificate problem
            }
        }
        loadUrl(flow.startUrl)
    }

    private fun checkSignedIn(flow: WebSignIn, source: MusicSource) {
        if (completing) return
        val cookies = CookieManager.getInstance().getCookie(flow.cookieOrigin) ?: return
        val names = cookies.split(';').map { it.substringBefore('=').trim() }.toSet()
        if (names.none { it in flow.doneWhenCookies }) return
        completing = true
        message = "Signing in…"
        lifecycleScope.launch {
            val result = source.auth?.signIn(mapOf(WebSignIn.SESSION_KEY to cookies))
            // Whatever the answer, the web view keeps nothing.
            wipeWebState()
            when (result) {
                SignInResult.SignedIn -> finish()
                is SignInResult.Refused -> message = when (result.problem) {
                    SetupProblem.UNREACHABLE -> "Couldn't reach the service. Check your connection and try again from Settings."
                    else -> "That sign-in didn't work. Try again from Settings."
                }
                null -> finish()
            }
        }
    }

    private fun wipeWebState() {
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }
        WebStorage.getInstance().deleteAllData()
        webView?.apply {
            clearCache(true)
            clearHistory()
            clearFormData()
        }
        runCatching { WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword() }
    }

    override fun onDestroy() {
        wipeWebState()
        webView?.apply {
            stopLoading()
            destroy()
        }
        webView = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_SOURCE = "source"

        fun intent(context: Context, source: SourceId): Intent =
            Intent(context, WebSignInActivity::class.java).putExtra(EXTRA_SOURCE, source.value)

        /** https only, and only the hosts (or their subdomains) the source allows. */
        internal fun allowed(uri: Uri, flow: WebSignIn): Boolean {
            if (uri.scheme != "https") return false
            val host = uri.host?.lowercase() ?: return false
            return flow.allowedHostSuffixes.any { host == it || host.endsWith(".$it") }
        }
    }
}
