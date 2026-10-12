package app.podium

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.designsystem.type.PodiumText
import app.podium.core.model.SourceId
import app.podium.sources.api.MusicSource
import app.podium.sources.api.WebSignIn

/**
 * Signing in on the service's own web page (YOUTUBE_MUSIC_ARCHITECTURE.md §6). Generic: the source
 * describes the page ([WebSignIn]); this screen never knows which service it is.
 *
 * - The page is the service's: Podium never sees or stores the password; two-step checks work as
 *   they do in a browser.
 * - The web view is locked down: https only, only the hosts the source names or Google's regional
 *   sign-in hosts, no file or content access, no JavaScript bridge into Podium, no pop-up windows,
 *   Safe Browsing on, no cache, kept out of screenshots. A page it stops says so (D-73).
 * - [WebSignInChecks] decides when the page has finished and hands its session to the source, which
 *   seals it with the Keystore. It lives in a [WebSignInModel], so a check under way survives the
 *   screen being recreated; turning the phone doesn't recreate it at all (the manifest).
 * - Web state is wiped before a sign-in starts, before Start over, and when the screen closes.
 */
class WebSignInActivity : ComponentActivity() {

    private val model: WebSignInModel by viewModels()
    private var webView: WebView? = null

    /** The web view waits for the wipe of the last sign-in's cookies to finish. */
    private var ready by mutableStateOf(false)

    /** A new web view for each Start over. */
    private var page by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The account's page: kept out of screenshots, screen recordings and the recents thumbnail.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        val graph = (application as PodiumApplication).graph
        val source = intent.getStringExtra(EXTRA_SOURCE)?.let { graph.registry.get(SourceId(it)) }
        val flow = source?.auth?.webSignIn
        if (source == null || flow == null) {
            finish()
            return
        }
        val checks = model.checks(flow, source)

        if (savedInstanceState == null) {
            // Start from nothing: no session of anyone else's can carry over.
            wipeWebState { ready = true }
        } else {
            // Recreated mid-sign-in (e.g. after the listener fetched a code from another app):
            // keep what the page has done so far.
            ready = true
        }

        setContent {
            PodiumTheme(darkTheme = true) {
                val colors = PodiumTheme.colors
                val type = PodiumTheme.type
                val state by checks.state.collectAsStateWithLifecycle()

                LaunchedEffect(state.step) {
                    if (state.step == WebSignInChecks.Step.DONE) {
                        wipeWebState()
                        finish()
                    }
                }

                Column(Modifier.fillMaxSize().background(colors.canvas).safeDrawingPadding()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PodiumText(flow.title, type.row, colors.labelPrimary, modifier = Modifier.weight(1f))
                        if (state.step == WebSignInChecks.Step.ON_PAGE) {
                            PodiumText(
                                "Check sign-in",
                                type.row,
                                colors.labelPrimary,
                                modifier = Modifier.clickable { checks.checkNow() }.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                        PodiumText(
                            "Cancel",
                            type.row,
                            colors.labelSecondary,
                            modifier = Modifier.clickable { finish() }.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                    PodiumText(
                        "You sign in on the service's own page. Podium never sees your password.",
                        type.caption,
                        colors.labelSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
                    )

                    val notice = state.notice
                    if (notice != null && state.step == WebSignInChecks.Step.ON_PAGE) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PodiumText(notice.text, type.caption, colors.labelSecondary, modifier = Modifier.weight(1f))
                            if (notice.startOver) {
                                PodiumText(
                                    "Start over",
                                    type.caption,
                                    colors.labelPrimary,
                                    modifier = Modifier.clickable { startOver(checks) }.padding(4.dp),
                                )
                            }
                            PodiumText(
                                "Dismiss",
                                type.caption,
                                colors.labelPrimary,
                                modifier = Modifier.clickable { checks.dismissNotice() }.padding(4.dp),
                            )
                        }
                    }

                    Box(Modifier.fillMaxSize()) {
                        // One web view per sign-in attempt: a check covers it, never replaces it,
                        // so a failure can't reload the page and start the check over by itself.
                        if (ready) {
                            key(page) {
                                AndroidView(
                                    factory = { context -> buildWebView(context, flow, checks) },
                                    onRelease = { view -> release(view) },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        when (state.step) {
                            WebSignInChecks.Step.CHECKING, WebSignInChecks.Step.DONE -> Box(
                                Modifier.fillMaxSize().background(colors.canvas).padding(24.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                PodiumText("Checking your account…", type.body, colors.labelPrimary)
                            }
                            WebSignInChecks.Step.FAILED -> Column(
                                Modifier.fillMaxSize().background(colors.canvas).padding(24.dp),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                PodiumText(state.failure.orEmpty(), type.body, colors.labelPrimary)
                                Spacer(Modifier.height(16.dp))
                                Row {
                                    if (state.canRetry) {
                                        PodiumText(
                                            "Try again",
                                            type.row,
                                            colors.labelPrimary,
                                            modifier = Modifier.clickable { checks.checkNow() }.padding(12.dp),
                                        )
                                    }
                                    PodiumText(
                                        "Start over",
                                        type.row,
                                        colors.labelPrimary,
                                        modifier = Modifier.clickable { startOver(checks) }.padding(12.dp),
                                    )
                                }
                            }
                            WebSignInChecks.Step.ON_PAGE -> Unit
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled") // The service's sign-in page needs JavaScript; nothing of Podium's is exposed to it.
    private fun buildWebView(context: Context, flow: WebSignIn, checks: WebSignInChecks): WebView = WebView(context).apply {
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
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (allowed(request.url, flow)) return false
                if (request.isForMainFrame) {
                    // Only where it tried to go, never the path or query (they can carry tokens).
                    if (BuildConfig.DEBUG) Log.i(TAG, "Stopped the sign-in page at ${request.url.scheme}://${request.url.host}")
                    checks.onBlocked()
                }
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                checks.onNavigated(onServiceSite(url, flow))
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                checks.onNavigated(onServiceSite(url, flow))
            }

            override fun onPageFinished(view: WebView, url: String) {
                checks.onNavigated(onServiceSite(url, flow))
            }

            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel() // never proceed past a certificate problem
                checks.onCertificateError()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) checks.onPageError()
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // The page is gone, not the app: its web view goes (it can't be used again), the
                // listener is told, and Start over builds a new one.
                ready = false
                checks.onPageLost()
                return true
            }
        }
        loadUrl(flow.startUrl)
    }

    /** A new sign-in from the beginning: the old page goes, its web state is wiped, a new page loads. */
    private fun startOver(checks: WebSignInChecks) {
        webView?.apply {
            webViewClient = WebViewClient() // nothing more from the old page
            stopLoading()
        }
        ready = false
        checks.startOver()
        wipeWebState {
            page++
            ready = true
        }
    }

    /** Destroys a web view once, whichever comes first: leaving the screen's layout or the screen closing. */
    private fun release(view: WebView) {
        if (webView === view) webView = null
        if (view.tag === RELEASED) return
        view.tag = RELEASED
        view.destroy()
    }

    /** Every cookie, storage and form the web view holds; [then] runs once the cookies are gone. */
    private fun wipeWebState(then: (() -> Unit)? = null) {
        CookieManager.getInstance().apply {
            removeAllCookies { then?.invoke() }
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
        if (isFinishing) wipeWebState()
        webView?.let(::release)
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_SOURCE = "source"
        private const val TAG = "PodiumSignIn"
        private val RELEASED = Any()

        fun intent(context: Context, source: SourceId): Intent =
            Intent(context, WebSignInActivity::class.java).putExtra(EXTRA_SOURCE, source.value)

        /**
         * Validates regional Google ccTLDs (e.g. accounts.google.co.in, google.de, accounts.google.co.uk).
         * Requires https, strict domain boundary matching, and rejects lookalikes like evil-google.com.
         */
        private val REGIONAL_GOOGLE_HOST = Regex(
            """^([a-z0-9-]+\.)*google\.(co\.[a-z]{2}|com\.[a-z]{2}|[a-z]{2})$""",
        )

        internal fun isRegionalGoogleHost(host: String): Boolean =
            REGIONAL_GOOGLE_HOST.matches(host)

        /** https only, and only the hosts (or their subdomains) the source allows or verified regional Google hosts. */
        internal fun allowed(uri: Uri, flow: WebSignIn): Boolean {
            if (uri.scheme != "https") return false
            val host = uri.host?.lowercase() ?: return false
            if (flow.allowedHostSuffixes.any { host == it || host.endsWith(".$it") }) return true
            return isRegionalGoogleHost(host)
        }

        /**
         * Whether [url] is on the service's own site — the site its session cookies belong to
         * ([WebSignIn.cookieOrigin] without its first label: music.example.com → example.com). Only
         * there can the page have left a session, so only there is it looked for.
         */
        internal fun onServiceSite(url: String, flow: WebSignIn): Boolean {
            val uri = Uri.parse(url)
            if (uri.scheme != "https") return false
            val host = uri.host?.lowercase() ?: return false
            val site = Uri.parse(flow.cookieOrigin).host?.lowercase()?.let(::siteOf) ?: return false
            return host == site || host.endsWith(".$site")
        }

        internal fun siteOf(host: String): String = host.split('.').takeLast(2).joinToString(".")

        /** The page's session for the service's origin, once it has one of the cookies the source names. */
        internal fun sessionCookies(flow: WebSignIn): String? =
            CookieManager.getInstance().getCookie(flow.cookieOrigin)?.takeIf { hasSessionCookie(it, flow.doneWhenCookies) }

        internal fun hasSessionCookie(cookieHeader: String, names: Set<String>): Boolean =
            cookieHeader.split(';').any { it.substringBefore('=').trim() in names }
    }
}

/** Holds the sign-in's checks across a recreated screen; a check under way ends only when the screen is closed. */
internal class WebSignInModel : ViewModel() {
    private var checks: WebSignInChecks? = null

    fun checks(flow: WebSignIn, source: MusicSource): WebSignInChecks = checks ?: WebSignInChecks(
        scope = viewModelScope,
        readSession = { WebSignInActivity.sessionCookies(flow) },
        signIn = { session -> source.auth?.signIn(mapOf(WebSignIn.SESSION_KEY to session)) },
    ).also { checks = it }
}
