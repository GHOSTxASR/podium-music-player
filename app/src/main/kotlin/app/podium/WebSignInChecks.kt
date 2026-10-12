package app.podium

import app.podium.sources.api.SetupProblem
import app.podium.sources.api.SignInResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * When the sign-in page has finished, and handing its session to the source (D-73). Kept apart
 * from the web view so it can be tested, and so it outlives a recreated screen ([WebSignInModel]).
 *
 * - Once the page reaches the service's own site, the session is looked for at every navigation
 *   and every [pollEveryMs] for [pollForMs]; navigating there again starts a new window.
 * - A session found is checked once. While it's checked nothing else starts.
 * - A check that fails stops the automatic checks: the reason shows with Try again and Start
 *   over, and only the listener starts the next one. (A failed check never loops into another.)
 * - A check still running after [slowAfterMs] says that a slow connection can take a while. It
 *   isn't cut short: each request gives up by itself if the connection stalls.
 *
 * Main thread only.
 */
internal class WebSignInChecks(
    private val scope: CoroutineScope,
    /** The session the page has left, once it has one; null before then. */
    private val readSession: () -> String?,
    /** The source's check; null when there's no source to sign in to. */
    private val signIn: suspend (String) -> SignInResult?,
    private val pollEveryMs: Long = 800,
    private val pollForMs: Long = 60_000,
    private val slowAfterMs: Long = 15_000,
) {
    enum class Step { ON_PAGE, CHECKING, FAILED, DONE }

    /** A note over the page. [startOver] offers to load the page again from the start. */
    data class Notice(val text: String, val startOver: Boolean = false)

    data class State(
        val step: Step = Step.ON_PAGE,
        val notice: Notice? = null,
        /** Why the last check failed (step [Step.FAILED]). */
        val failure: String? = null,
        /** Whether checking the same session again could help. */
        val canRetry: Boolean = false,
        /** The check is taking a while (step [Step.CHECKING]). */
        val slow: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Checks start by themselves until one fails; then only the listener starts one. */
    private var automatic = true
    private var polling: Job? = null

    /** The page moved (started, finished, or changed its address); [onServiceSite] says whether it's on the service's site. */
    fun onNavigated(onServiceSite: Boolean) {
        if (!onServiceSite || !automatic || _state.value.step != Step.ON_PAGE) return
        check(manual = false)
        if (_state.value.step == Step.ON_PAGE) startPolling()
    }

    /** "Check sign-in", or "Try again" after a failure. */
    fun checkNow() = check(manual = true)

    /** The page is being loaded again from the start (its web state already wiped). */
    fun startOver() {
        stopPolling()
        automatic = true
        _state.value = State()
    }

    fun dismissNotice() {
        _state.value = _state.value.copy(notice = null)
    }

    /** The page tried to go somewhere the sign-in doesn't allow, and was stopped. */
    fun onBlocked() = note(Notice(BLOCKED, startOver = true))

    fun onPageError() = note(Notice(PAGE_ERROR, startOver = true))

    fun onCertificateError() = note(Notice(CERTIFICATE, startOver = true))

    /** The page's renderer is gone: only loading it again helps. */
    fun onPageLost() {
        if (_state.value.step == Step.DONE) return
        stopPolling()
        automatic = false
        _state.value = State(step = Step.FAILED, failure = PAGE_LOST, canRetry = false)
    }

    private fun note(notice: Notice) {
        if (_state.value.step == Step.ON_PAGE) _state.value = _state.value.copy(notice = notice)
    }

    private fun check(manual: Boolean) {
        val current = _state.value
        when (current.step) {
            Step.CHECKING, Step.DONE -> return
            Step.FAILED -> if (!manual) return
            Step.ON_PAGE -> if (!manual && !automatic) return
        }
        val session = readSession()
        if (session == null) {
            if (manual) {
                _state.value = if (current.step == Step.FAILED) {
                    current.copy(failure = NOT_FINISHED_START_OVER, canRetry = false)
                } else {
                    current.copy(notice = Notice(NOT_FINISHED))
                }
            }
            return
        }
        stopPolling()
        _state.value = State(step = Step.CHECKING)
        scope.launch {
            val reassure = launch {
                delay(slowAfterMs)
                if (_state.value.step == Step.CHECKING) _state.value = _state.value.copy(slow = true)
            }
            val result = try {
                signIn(session)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                SignInResult.Refused(SetupProblem.UNKNOWN)
            } finally {
                reassure.cancel()
            }
            _state.value = when (result) {
                SignInResult.SignedIn, null -> State(step = Step.DONE)
                is SignInResult.Refused -> {
                    automatic = false
                    // Refused outright, the same session won't do better: only starting over helps.
                    State(step = Step.FAILED, failure = failureText(result.problem), canRetry = result.problem != SetupProblem.WRONG_CREDENTIALS)
                }
            }
        }
    }

    private fun startPolling() {
        if (polling?.isActive == true) return
        polling = scope.launch {
            var waited = 0L
            while (waited < pollForMs) {
                delay(pollEveryMs)
                waited += pollEveryMs
                if (!automatic || _state.value.step != Step.ON_PAGE) return@launch
                check(manual = false)
                if (_state.value.step != Step.ON_PAGE) return@launch
            }
            if (_state.value.notice == null) note(Notice(STILL_WAITING))
        }
    }

    private fun stopPolling() {
        polling?.cancel()
        polling = null
    }

    internal companion object {
        const val NOT_FINISHED = "Sign-in isn't finished yet. Finish the steps on the page, then tap Check sign-in."
        const val NOT_FINISHED_START_OVER = "Sign-in isn't finished yet. Tap Start over to sign in again."
        const val STILL_WAITING = "Still waiting for the page to finish signing in. When you're done there, tap Check sign-in."
        const val BLOCKED = "The page tried to open something Podium doesn't allow, so it stopped there. Tap Start over to try again."
        const val PAGE_ERROR = "The page couldn't load. Check your connection, then tap Start over."
        const val CERTIFICATE = "The page's security certificate couldn't be checked, so it was stopped. Check the phone's date and time, then tap Start over."
        const val PAGE_LOST = "The sign-in page stopped working. Tap Start over to load it again."
        const val UNREACHABLE = "Couldn't reach the service. Check your connection, then try again."
        const val NOT_ACCEPTED = "The service didn't accept this sign-in. Tap Start over to sign in again."
        const val NOT_FINISHED_CHECK = "Couldn't finish signing in. Try again, or start over."
        const val SLOW = "This can take a while on a slow connection. Keep waiting, or tap Cancel to stop."

        fun failureText(problem: SetupProblem): String = when (problem) {
            SetupProblem.UNREACHABLE -> UNREACHABLE
            SetupProblem.WRONG_CREDENTIALS -> NOT_ACCEPTED
            else -> NOT_FINISHED_CHECK
        }
    }
}
