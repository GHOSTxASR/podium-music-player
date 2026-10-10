package app.podium

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.podium.sources.api.WebSignIn
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class WebSignInActivityTest {

    private val origin = "https://music.youtube.com"

    private val authCookieNames = setOf("SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID")

    private val flow = WebSignIn(
        title = "Sign in to YouTube Music",
        startUrl = "https://accounts.google.com/ServiceLogin?service=youtube&passive=true&continue=https%3A%2F%2Fmusic.youtube.com%2F",
        cookieOrigin = origin,
        doneWhenCookies = authCookieNames,
        doneUrlPrefix = origin,
        allowedHostSuffixes = listOf(
            "google.com",
            "youtube.com",
            "gstatic.com",
            "googleusercontent.com",
            "ggpht.com",
            "ytimg.com",
            "googleapis.com",
        ),
    )

    // --- Host Validation Tests (Phase 2.A & 3) --------------------------------------------------------

    @Test
    fun `allowed accepts standard Google and YouTube Music authentication hosts`() {
        assertTrue(WebSignInActivity.allowed(Uri.parse("https://accounts.google.com/ServiceLogin"), flow))
        assertTrue(WebSignInActivity.allowed(Uri.parse("https://accounts.youtube.com/accounts/SetSID"), flow))
        assertTrue(WebSignInActivity.allowed(Uri.parse("https://music.youtube.com/"), flow))
        assertTrue(WebSignInActivity.allowed(Uri.parse("https://myaccount.google.com/signinoptions/two-step-verification"), flow))
        assertTrue(WebSignInActivity.allowed(Uri.parse("https://ssl.gstatic.com/accounts/static/css/"), flow))
        assertTrue(WebSignInActivity.allowed(Uri.parse("https://lh3.googleusercontent.com/avatar"), flow))
    }

    @Test
    fun `allowed accepts legitimate regional Google authentication hosts`() {
        // India (realme GT 6T regional context)
        assertTrue(WebSignInActivity.allowed(Uri.parse("https://accounts.google.co.in/ServiceLogin"), flow))
        assertTrue(WebSignInActivity.allowed(Uri.parse("https://google.co.in/"), flow))
        assertTrue(WebSignInActivity.allowed(Uri.parse("https://myaccount.google.co.in/"), flow))

        // United Kingdom, Germany, Japan, Australia
        assertTrue(WebSignInActivity.allowed(Uri.parse("https://accounts.google.co.uk/ServiceLogin"), flow))
        assertTrue(WebSignInActivity.allowed(Uri.parse("https://accounts.google.de/ServiceLogin"), flow))
        assertTrue(WebSignInActivity.allowed(Uri.parse("https://accounts.google.co.jp/ServiceLogin"), flow))
        assertTrue(WebSignInActivity.allowed(Uri.parse("https://accounts.google.com.au/ServiceLogin"), flow))
    }

    @Test
    fun `isRegionalGoogleHost correctly identifies valid regional Google domains`() {
        assertTrue(WebSignInActivity.isRegionalGoogleHost("accounts.google.co.in"))
        assertTrue(WebSignInActivity.isRegionalGoogleHost("google.co.in"))
        assertTrue(WebSignInActivity.isRegionalGoogleHost("accounts.google.de"))
        assertTrue(WebSignInActivity.isRegionalGoogleHost("accounts.google.co.uk"))
        assertTrue(WebSignInActivity.isRegionalGoogleHost("accounts.google.com.au"))
        assertTrue(WebSignInActivity.isRegionalGoogleHost("myaccount.google.co.jp"))
    }

    @Test
    fun `allowed strictly rejects insecure HTTP schemes even on valid hosts`() {
        assertFalse(WebSignInActivity.allowed(Uri.parse("http://accounts.google.com/"), flow))
        assertFalse(WebSignInActivity.allowed(Uri.parse("http://accounts.google.co.in/"), flow))
        assertFalse(WebSignInActivity.allowed(Uri.parse("http://music.youtube.com/"), flow))
    }

    @Test
    fun `allowed strictly rejects malicious lookalike and attacker domains`() {
        // Hyphenated lookalikes
        assertFalse(WebSignInActivity.allowed(Uri.parse("https://evil-google.com/"), flow))
        assertFalse(WebSignInActivity.allowed(Uri.parse("https://notgoogle.com/"), flow))
        assertFalse(WebSignInActivity.allowed(Uri.parse("https://fake-accounts-google.co.in/"), flow))
        assertFalse(WebSignInActivity.allowed(Uri.parse("https://attackergoogle.co.in/"), flow))

        // Subdomain trickery where attacker controls the domain
        assertFalse(WebSignInActivity.allowed(Uri.parse("https://google.com.attacker.example/"), flow))
        assertFalse(WebSignInActivity.allowed(Uri.parse("https://accounts.google.co.in.attacker.example/"), flow))
        assertFalse(WebSignInActivity.allowed(Uri.parse("https://google.attacker.com/"), flow))
        assertFalse(WebSignInActivity.allowed(Uri.parse("https://youtube.com.phishing.io/"), flow))
    }

    // --- Cookie Recognition & Completion Configuration Tests (Phase 2.B) -----------------------------

    @Test
    fun `doneWhenCookies includes all three supported API secret cookie variants`() {
        assertEquals(
            setOf("SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID"),
            flow.doneWhenCookies,
        )
    }

    @Test
    fun `doneWhenCookies matching succeeds for each supported cookie name individually`() {
        val sapisidCookies = "SID=abc; SAPISID=secret-123; HSID=def"
        val sapisidNames = sapisidCookies.split(';').map { it.substringBefore('=').trim() }.toSet()
        assertTrue(sapisidNames.any { it in flow.doneWhenCookies })

        val secure3Cookies = "SID=abc; __Secure-3PAPISID=secret-456; HSID=def"
        val secure3Names = secure3Cookies.split(';').map { it.substringBefore('=').trim() }.toSet()
        assertTrue(secure3Names.any { it in flow.doneWhenCookies })

        val secure1Cookies = "SID=abc; __Secure-1PAPISID=secret-789; HSID=def"
        val secure1Names = secure1Cookies.split(';').map { it.substringBefore('=').trim() }.toSet()
        assertTrue(secure1Names.any { it in flow.doneWhenCookies })
    }

    @Test
    fun `doneWhenCookies matching returns false when auth cookies are not yet present`() {
        val googleOnlyCookies = "SID=abc; HSID=def; SSID=ghi"
        val names = googleOnlyCookies.split(';').map { it.substringBefore('=').trim() }.toSet()
        assertFalse(names.any { it in flow.doneWhenCookies })

        val irrelevantCookies = "NID=123; 1P_JAR=456; PREF=789"
        val irrelevantNames = irrelevantCookies.split(';').map { it.substringBefore('=').trim() }.toSet()
        assertFalse(irrelevantNames.any { it in flow.doneWhenCookies })
    }

    // --- URL Prefix Matching Tests (Phase 2.C) -------------------------------------------------------

    @Test
    fun `doneUrlPrefix check matches music youtube com with and without path or parameters`() {
        assertTrue("https://music.youtube.com/".startsWith(flow.doneUrlPrefix))
        assertTrue("https://music.youtube.com/?cbrd=1".startsWith(flow.doneUrlPrefix))
        assertTrue("https://music.youtube.com/browse/FEmusic_home".startsWith(flow.doneUrlPrefix))

        // Intermediate redirects do NOT match doneUrlPrefix
        assertFalse("https://accounts.youtube.com/accounts/SetSID".startsWith(flow.doneUrlPrefix))
        assertFalse("https://accounts.google.com/CheckCookie".startsWith(flow.doneUrlPrefix))
    }
}
