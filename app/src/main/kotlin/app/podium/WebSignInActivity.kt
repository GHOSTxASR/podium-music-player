package app.podium

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.WindowManager
import android.webkit.CookieManager
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * Signing in on the service's own web page (YOUTUBE_MUSIC_ARCHITECTURE.md §6). Generic: the source
 * describes the page ([WebSignIn]); this screen never knows which service it is.
 *
 * - The page is the service's: Podium never sees or stores the password; two-step checks and
 *   passkeys work as they do in a browser.
 * - The web view is locked down: https only, only the hosts the source names or regional Google auth hosts,
 *   no file or content access, no JavaScript bridge into Podium, no pop-up windows, Safe Browsing on, no cache.
 * - Multi-trigger session detection: onPageStarted, onPageFinished, doUpdateVisitedHistory,
 *   lifecycle-aware cookie polling, and an explicit "Check sign-in" action ensure session cookies
 *   are captured promptly without stalling indefinitely on heavy web applications.
 * - Session cookies are validated via [MusicSource.auth.signIn] and stored in the Keystore; web state
 *   is cleanly wiped upon completion.
 */
class WebSignInActivity : ComponentActivity() {

    private var webView: WebView? = null
    private var completing by mutableStateOf(false)
    private var statusText by mutableStateOf<String?>(null)
    private var userFeedback by mutableStateOf<String?>(null)

    private val checkMutex = Mutex()
    private var pollJob: Job? = null

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

        if (savedInstanceState == null) {
            // Start fresh only on a genuinely new sign-in flow, not on recreation across 2FA backgrounding
            wipeWebState()
        } else {
            completing = savedInstanceState.getBoolean(KEY_COMPLETING, false)
        }

        setContent {
            PodiumTheme(darkTheme = true) {
                val colors = PodiumTheme.colors
                val type = PodiumTheme.type
                Column(Modifier.fillMaxSize().background(colors.canvas).safeDrawingPadding()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PodiumText(flow.title, type.row, colors.labelPrimary, modifier = Modifier.weight(1f))
                        if (!completing) {
                            PodiumText(
                                "Check sign-in",
                                type.row,
                                colors.labelPrimary,
                                modifier = Modifier
                                    .clickable { requestCheck(flow, source, isManual = true) }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                        PodiumText(
                            "Cancel",
                            type.row,
                            colors.labelSecondary,
                            modifier = Modifier
                                .clickable { finish() }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    PodiumText(
                        "You sign in on the service's own page. Podium never sees your password.",
                        type.caption,
                        colors.labelSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
                    )

                    val feedback = userFeedback
                    if (feedback != null) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PodiumText(feedback, type.caption, colors.labelSecondary, modifier = Modifier.weight(1f))
                            PodiumText(
                                "Dismiss",
                                type.caption,
                                colors.labelPrimary,
                                modifier = Modifier.clickable { userFeedback = null }.padding(4.dp)
                            )
                        }
                    }

                    val status = statusText
                    if (status != null) {
                        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                            PodiumText(status, type.body, colors.labelPrimary)
                        }
                    } else {
                        AndroidView(
                            factory = { context -> buildWebView(context, flow, source) },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_COMPLETING, completing)
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

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (isRelevantUrl(url, flow)) {
                    startPollingIfNeeded(flow, source)
                    if (url.startsWith(flow.doneUrlPrefix)) {
                        requestCheck(flow, source, isManual = false)
                    }
                }
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                if (isRelevantUrl(url, flow)) {
                    startPollingIfNeeded(flow, source)
                    if (url.startsWith(flow.doneUrlPrefix)) {
                        requestCheck(flow, source, isManual = false)
                    }
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (isRelevantUrl(url, flow)) {
                    requestCheck(flow, source, isManual = false)
                }
            }

            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel() // never proceed past a certificate problem
                userFeedback = "Security error encountered. Sign-in cancelled."
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    userFeedback = "Page could not be loaded. Check your connection."
                }
            }
        }
        loadUrl(flow.startUrl)
    }

    private fun isRelevantUrl(url: String, flow: WebSignIn): Boolean =
        url.startsWith(flow.doneUrlPrefix) || url.contains("youtube.com")

    private fun startPollingIfNeeded(flow: WebSignIn, source: MusicSource) {
        if (pollJob != null || completing || isFinishing || isDestroyed) return
        pollJob = lifecycleScope.launch {
            var elapsed = 0L
            val interval = 800L
            val maxDuration = 60_000L
            while (isActive && !completing && elapsed < maxDuration) {
                delay(interval)
                elapsed += interval
                requestCheck(flow, source, isManual = false)
            }
            if (!completing && elapsed >= maxDuration) {
                if (userFeedback == null && statusText == null) {
                    userFeedback = "Sign-in taking longer than expected. Tap 'Check sign-in' if you've completed verification."
                }
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun extractCandidateCookies(flow: WebSignIn): String? {
        val cm = CookieManager.getInstance()
        val direct = cm.getCookie(flow.cookieOrigin)
        if (hasAuthCookie(direct, flow.doneWhenCookies)) return direct

        val rootOrigin = "https://youtube.com"
        if (rootOrigin != flow.cookieOrigin) {
            val root = cm.getCookie(rootOrigin)
            if (hasAuthCookie(root, flow.doneWhenCookies)) return root
        }
        return direct
    }

    private fun hasAuthCookie(cookieHeader: String?, targetCookies: Set<String>): Boolean {
        if (cookieHeader.isNullOrBlank()) return false
        val names = cookieHeader.split(';').map { it.substringBefore('=').trim() }.toSet()
        return names.any { it in targetCookies }
    }

    private fun requestCheck(flow: WebSignIn, source: MusicSource, isManual: Boolean = false) {
        if (completing || isFinishing || isDestroyed) return
        lifecycleScope.launch {
            if (!checkMutex.tryLock()) return@launch
            try {
                if (completing || isFinishing || isDestroyed) return@launch
                val cookies = extractCandidateCookies(flow)
                if (!hasAuthCookie(cookies, flow.doneWhenCookies)) {
                    if (isManual) {
                        userFeedback = "Verification not detected yet. Please complete sign-in on the page."
                    }
                    return@launch
                }

                completing = true
                stopPolling()
                statusText = "Verifying account…"
                userFeedback = null

                val result = source.auth?.signIn(mapOf(WebSignIn.SESSION_KEY to cookies!!))
                if (isFinishing || isDestroyed) return@launch

                when (result) {
                    SignInResult.SignedIn -> {
                        statusText = "Signed in"
                        wipeWebState()
                        finish()
                    }
                    is SignInResult.Refused -> {
                        completing = false
                        statusText = null
                        userFeedback = when (result.problem) {
                            SetupProblem.UNREACHABLE -> "Couldn't reach the service. Check your connection and try again."
                            else -> "That sign-in didn't work. Try again from Settings."
                        }
                    }
                    null -> {
                        wipeWebState()
                        finish()
                    }
                }
            } finally {
                checkMutex.unlock()
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
        stopPolling()
        if (isFinishing) {
            wipeWebState()
        }
        webView?.apply {
            stopLoading()
            destroy()
        }
        webView = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_SOURCE = "source"
        private const val KEY_COMPLETING = "completing"

        fun intent(context: Context, source: SourceId): Intent =
            Intent(context, WebSignInActivity::class.java).putExtra(EXTRA_SOURCE, source.value)

        /**
         * Validates regional Google ccTLDs (e.g. accounts.google.co.in, google.de, accounts.google.co.uk).
         * Requires https, strict domain boundary matching, and rejects lookalikes like evil-google.com.
         */
        private val REGIONAL_GOOGLE_HOST = Regex(
            """^([a-z0-9-]+\.)*google\.(co\.[a-z]{2}|com\.[a-z]{2}|[a-z]{2})$"""
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
    }
}
