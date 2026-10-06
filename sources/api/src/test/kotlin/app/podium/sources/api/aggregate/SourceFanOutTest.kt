package app.podium.sources.api.aggregate

import app.podium.core.common.ManualClock
import app.podium.core.common.Outcome
import app.podium.core.common.PodiumError
import app.podium.sources.api.SearchQuery
import app.podium.sources.api.SourceHealth
import app.podium.sources.api.SourceHealthMonitor
import app.podium.sources.testing.FakeOnlineMusicSource
import app.podium.sources.testing.FakeOnlineMusicSource.Behavior
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SourceFanOutTest {
    private val clock = ManualClock(1_000_000)
    private val health = SourceHealthMonitor(clock)

    private fun TestScope.fanOut(timeout: Long = 8_000) =
        SourceFanOut(health, timeout, CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))

    private suspend fun FakeOnlineMusicSource.search() = catalog.search(SearchQuery("song"))

    @Test
    fun `asks every source at once and returns answers in priority order`() = runTest {
        val a = FakeOnlineMusicSource("a").apply { track("Song A"); searchBehavior = Behavior.Slow(300) }
        val b = FakeOnlineMusicSource("b").apply { track("Song B"); searchBehavior = Behavior.Slow(300) }
        val answers = fanOut().askAll(listOf(a, b)) { (it as FakeOnlineMusicSource).search() }
        assertEquals(listOf(a.sourceId, b.sourceId), answers.map { it.source })
        assertEquals(300, currentTime, "concurrent, not one after the other")
    }

    @Test
    fun `a slow source never holds up a fast one`() = runTest {
        val slow = FakeOnlineMusicSource("slow").apply { track("Song"); searchBehavior = Behavior.Slow(5_000) }
        val fast = FakeOnlineMusicSource("fast").apply { track("Song"); searchBehavior = Behavior.Slow(300) }
        val first = fanOut().ask(listOf(slow, fast)) { (it as FakeOnlineMusicSource).search() }.first()
        assertEquals(fast.sourceId, first.source)
        assertEquals(300, currentTime)
    }

    @Test
    fun `a source that hangs times out and the rest still answer`() = runTest {
        val hung = FakeOnlineMusicSource("hung").apply { searchBehavior = Behavior.Hang() }
        val ok = FakeOnlineMusicSource("ok").apply { track("Song") }
        val answers = fanOut(timeout = 2_000).askAll(listOf(hung, ok)) { (it as FakeOnlineMusicSource).search() }
        assertIs<SourceAnswer.TimedOut>(answers[0])
        assertIs<SourceAnswer.Answered<*>>(answers[1])
        assertEquals(2_000, currentTime)
        assertIs<SourceHealth.Degraded>(health.health(hung.sourceId), "a timeout is a failure")
    }

    @Test
    fun `a failure is reported as a failure and the others still answer`() = runTest {
        val broken = FakeOnlineMusicSource("broken").apply { searchBehavior = Behavior.Fail(PodiumError.Server(503)) }
        val ok = FakeOnlineMusicSource("ok").apply { track("Song") }
        val answers = fanOut().askAll(listOf(broken, ok)) { (it as FakeOnlineMusicSource).search() }
        assertEquals(PodiumError.Server(503), assertIs<SourceAnswer.Failed>(answers[0]).error)
        assertIs<SourceAnswer.Answered<*>>(answers[1])
    }

    @Test
    fun `a miss is healthy and never counts toward the breaker`() = runTest {
        val source = FakeOnlineMusicSource("a")
        repeat(5) { fanOut().askAll(listOf(source)) { Outcome.Failure(PodiumError.NotFound("nothing")) } }
        assertEquals(SourceHealth.Healthy, health.health(source.sourceId))
    }

    @Test
    fun `three failures open the breaker and the source is skipped while another works`() = runTest {
        val broken = FakeOnlineMusicSource("broken").apply { searchBehavior = Behavior.Fail(PodiumError.Network()) }
        val ok = FakeOnlineMusicSource("ok").apply { track("Song") }
        val f = fanOut()
        repeat(3) { f.askAll(listOf(broken, ok)) { (it as FakeOnlineMusicSource).search() } }
        assertIs<SourceHealth.Unreachable>(health.health(broken.sourceId))
        val before = broken.searchCalls
        val answers = f.askAll(listOf(broken, ok)) { (it as FakeOnlineMusicSource).search() }
        assertEquals(before, broken.searchCalls, "not asked while its breaker is open")
        assertEquals(listOf(ok.sourceId), answers.map { it.source })
    }

    @Test
    fun `when every breaker is open the sources are asked anyway`() = runTest {
        val only = FakeOnlineMusicSource("only").apply { searchBehavior = Behavior.Fail(PodiumError.Network()) }
        val f = fanOut()
        repeat(3) { f.askAll(listOf(only)) { (it as FakeOnlineMusicSource).search() } }
        only.searchBehavior = Behavior.Answer
        val answers = f.askAll(listOf(only)) { (it as FakeOnlineMusicSource).search() }
        assertIs<SourceAnswer.Answered<*>>(answers.single())
        assertEquals(SourceHealth.Healthy, health.health(only.sourceId))
    }

    @Test
    fun `a rate-limited source waits its turn`() = runTest {
        val busy = FakeOnlineMusicSource("busy").apply { searchBehavior = Behavior.Fail(PodiumError.RateLimited(30_000)) }
        val f = fanOut()
        f.askAll(listOf(busy)) { (it as FakeOnlineMusicSource).search() }
        assertIs<SourceHealth.RateLimited>(health.health(busy.sourceId))
        assertTrue(f.askAll(listOf(busy)) { (it as FakeOnlineMusicSource).search() }.isEmpty())
        assertIs<PodiumError.RateLimited>(f.unavailable(busy.sourceId))
    }

    @Test
    fun `being offline counts against no source`() = runTest {
        val source = FakeOnlineMusicSource("a").apply { searchBehavior = Behavior.Fail(PodiumError.Offline) }
        repeat(5) { fanOut().askAll(listOf(source)) { (it as FakeOnlineMusicSource).search() } }
        assertEquals(SourceHealth.Healthy, health.health(source.sourceId))
    }

    @Test
    fun `not authorised only means rejected credentials for a source that signs in`() {
        assertEquals(app.podium.sources.api.HealthOutcome.SERVER_ERROR, SourceFanOut.outcomeOf(PodiumError.AuthRequired(), signsIn = false))
        assertEquals(app.podium.sources.api.HealthOutcome.AUTH_FAILURE, SourceFanOut.outcomeOf(PodiumError.AuthRequired(), signsIn = true))
    }

    @Test
    fun `withdrawing the question cancels the outstanding calls`() = runTest {
        val slow = FakeOnlineMusicSource("slow").apply { track("Song"); searchBehavior = Behavior.Slow(5_000) }
        val fast = FakeOnlineMusicSource("fast").apply { track("Song") }
        val answers = fanOut().ask(listOf(slow, fast)) { (it as FakeOnlineMusicSource).search() }.first()
        assertEquals(fast.sourceId, answers.source)
        testScheduler.advanceUntilIdle()
        assertEquals(SourceHealth.Healthy, health.health(slow.sourceId), "a withdrawn question records nothing")
        assertTrue(currentTime < 5_000, "the slow call was cancelled, not waited for")
    }

    @Test
    fun `answers arrive as they come`() = runTest {
        val a = FakeOnlineMusicSource("a").apply { track("Song"); searchBehavior = Behavior.Slow(900) }
        val b = FakeOnlineMusicSource("b").apply { track("Song"); searchBehavior = Behavior.Slow(100) }
        val order = fanOut().ask(listOf(a, b)) { (it as FakeOnlineMusicSource).search() }.toList().map { it.source }
        assertEquals(listOf(b.sourceId, a.sourceId), order)
    }
}
