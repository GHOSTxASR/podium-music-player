package app.podium

import app.podium.WebSignInChecks.Step
import app.podium.sources.api.SetupProblem
import app.podium.sources.api.SignInResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The sign-in screen's checking (D-73): when it checks, and that it never loops or hangs. */
@OptIn(ExperimentalCoroutinesApi::class)
class WebSignInChecksTest {

    private var session: String? = null
    private var reads = 0
    private var answer: () -> SignInResult? = { SignInResult.SignedIn }
    private val sent = mutableListOf<String>()

    private fun TestScope.checks() = WebSignInChecks(
        scope = backgroundScope,
        readSession = { reads++; session },
        signIn = { s -> sent += s; answer() },
    )

    @Test
    fun `a finished page is checked once, and then the screen is done`() = runTest {
        session = "SAPISID=x"
        val checks = checks()
        checks.onNavigated(onServiceSite = true)
        assertEquals(Step.CHECKING, checks.state.value.step)
        checks.onNavigated(onServiceSite = true) // the page goes on moving while it's checked
        checks.checkNow()
        runCurrent()
        assertEquals(listOf("SAPISID=x"), sent)
        assertEquals(Step.DONE, checks.state.value.step)
    }

    @Test
    fun `nothing is looked for until the page reaches the service's site`() = runTest {
        session = "SAPISID=x"
        val checks = checks()
        checks.onNavigated(onServiceSite = false)
        advanceTimeBy(120_000)
        assertEquals(0, reads)
        assertEquals(Step.ON_PAGE, checks.state.value.step)
        assertNull(checks.state.value.notice)
    }

    @Test
    fun `a failed check never starts another by itself, so it can't loop`() = runTest {
        session = "SAPISID=x"
        answer = { SignInResult.Refused(SetupProblem.UNREACHABLE) }
        val checks = checks()
        checks.onNavigated(onServiceSite = true)
        runCurrent()
        val failed = checks.state.value
        assertEquals(Step.FAILED, failed.step)
        assertEquals(WebSignInChecks.UNREACHABLE, failed.failure)
        assertTrue(failed.canRetry)
        // The page keeps loading under the message, and time passes: no new check.
        repeat(3) { checks.onNavigated(onServiceSite = true) }
        advanceTimeBy(120_000)
        assertEquals(1, sent.size)
        // Try again: one more check, by hand.
        checks.checkNow()
        runCurrent()
        assertEquals(2, sent.size)
        assertEquals(Step.FAILED, checks.state.value.step)
    }

    @Test
    fun `a sign-in refused outright offers only to start over`() = runTest {
        session = "SAPISID=x"
        answer = { SignInResult.Refused(SetupProblem.WRONG_CREDENTIALS) }
        val checks = checks()
        checks.onNavigated(onServiceSite = true)
        runCurrent()
        assertEquals(WebSignInChecks.NOT_ACCEPTED, checks.state.value.failure)
        assertFalse(checks.state.value.canRetry)
    }

    @Test
    fun `starting over lets the next sign-in be checked by itself again`() = runTest {
        session = "SAPISID=x"
        answer = { SignInResult.Refused(SetupProblem.UNREACHABLE) }
        val checks = checks()
        checks.onNavigated(onServiceSite = true)
        runCurrent()
        assertEquals(Step.FAILED, checks.state.value.step)

        checks.startOver()
        assertEquals(WebSignInChecks.State(), checks.state.value)
        answer = { SignInResult.SignedIn }
        checks.onNavigated(onServiceSite = true)
        runCurrent()
        assertEquals(2, sent.size)
        assertEquals(Step.DONE, checks.state.value.step)
    }

    @Test
    fun `a session that appears a moment after the page arrives is found by polling`() = runTest {
        val checks = checks()
        checks.onNavigated(onServiceSite = true) // on the site, cookies not there yet
        advanceTimeBy(2_000)
        assertTrue(sent.isEmpty())
        session = "__Secure-3PAPISID=y"
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf("__Secure-3PAPISID=y"), sent)
        assertEquals(Step.DONE, checks.state.value.step)
    }

    @Test
    fun `polling ends with a hint, and starts again when the page moves on`() = runTest {
        val checks = checks()
        checks.onNavigated(onServiceSite = true)
        advanceTimeBy(61_000)
        assertEquals(WebSignInChecks.STILL_WAITING, checks.state.value.notice?.text)
        val afterWindow = reads
        advanceTimeBy(10_000)
        assertEquals(afterWindow, reads, "the window has ended")

        checks.onNavigated(onServiceSite = true) // e.g. a two-step check finished elsewhere
        advanceTimeBy(5_000)
        assertTrue(reads > afterWindow + 2, "a new window started")
        session = "SAPISID=z"
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(Step.DONE, checks.state.value.step)
    }

    @Test
    fun `checking by hand before the page has finished says what to do`() = runTest {
        val checks = checks()
        checks.checkNow()
        assertEquals(WebSignInChecks.NOT_FINISHED, checks.state.value.notice?.text)
        assertEquals(Step.ON_PAGE, checks.state.value.step)
        assertTrue(sent.isEmpty())
        checks.dismissNotice()
        assertNull(checks.state.value.notice)
    }

    @Test
    fun `a check that throws ends as a failure the listener can act on, not a hang`() = runTest {
        session = "SAPISID=x"
        answer = { throw IllegalStateException("keystore") }
        val checks = checks()
        checks.onNavigated(onServiceSite = true)
        runCurrent()
        assertEquals(Step.FAILED, checks.state.value.step)
        assertEquals(WebSignInChecks.NOT_FINISHED_CHECK, checks.state.value.failure)
    }

    @Test
    fun `a stopped or failed page says so and offers to start over`() = runTest {
        val checks = checks()
        checks.onBlocked()
        assertEquals(WebSignInChecks.Notice(WebSignInChecks.BLOCKED, startOver = true), checks.state.value.notice)
        checks.onPageError()
        assertEquals(WebSignInChecks.PAGE_ERROR, checks.state.value.notice?.text)
        checks.onCertificateError()
        assertTrue(checks.state.value.notice!!.startOver)
    }

    @Test
    fun `a lost page fails with only start over, and isn't checked behind the message`() = runTest {
        session = "SAPISID=x"
        val checks = checks()
        checks.onPageLost()
        assertEquals(Step.FAILED, checks.state.value.step)
        assertEquals(WebSignInChecks.PAGE_LOST, checks.state.value.failure)
        assertFalse(checks.state.value.canRetry)
        checks.onNavigated(onServiceSite = true)
        advanceTimeBy(10_000)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a slow check says it can take a while, and keeps waiting`() = runTest {
        session = "SAPISID=x"
        val reply = CompletableDeferred<SignInResult>()
        val checks = WebSignInChecks(backgroundScope, { session }, { s -> sent += s; reply.await() })
        checks.onNavigated(onServiceSite = true)
        advanceTimeBy(14_000)
        assertEquals(Step.CHECKING, checks.state.value.step)
        assertFalse(checks.state.value.slow)
        advanceTimeBy(2_000)
        assertTrue(checks.state.value.slow, "a slow connection is told it can take a while")
        advanceTimeBy(60_000)
        assertEquals(Step.CHECKING, checks.state.value.step, "and the check isn't cut short")
        reply.complete(SignInResult.SignedIn)
        runCurrent()
        assertEquals(Step.DONE, checks.state.value.step)
        assertEquals(1, sent.size)
    }

    @Test
    fun `a quick check never shows the slow connection note`() = runTest {
        session = "SAPISID=x"
        answer = { SignInResult.Refused(SetupProblem.UNREACHABLE) }
        val checks = checks()
        checks.onNavigated(onServiceSite = true)
        runCurrent()
        advanceTimeBy(30_000)
        assertEquals(Step.FAILED, checks.state.value.step)
        assertFalse(checks.state.value.slow)
    }

    @Test
    fun `no source to sign in to closes the screen rather than waiting`() = runTest {
        session = "SAPISID=x"
        answer = { null }
        val checks = checks()
        checks.onNavigated(onServiceSite = true)
        runCurrent()
        assertEquals(Step.DONE, checks.state.value.step)
    }
}
