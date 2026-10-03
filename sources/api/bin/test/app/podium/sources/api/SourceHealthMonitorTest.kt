package app.podium.sources.api

import app.podium.core.common.ManualClock
import app.podium.core.model.SourceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SourceHealthMonitorTest {
    private val clock = ManualClock(1_000_000)
    private val monitor = SourceHealthMonitor(clock)
    private val id = SourceId("server")

    @Test
    fun `misses never damage health`() {
        repeat(20) { monitor.record(id, HealthOutcome.MISS) }
        assertEquals(SourceHealth.Healthy, monitor.health(id))
    }

    @Test
    fun `a miss proves reachability and clears a failure streak`() {
        monitor.record(id, HealthOutcome.NETWORK_FAILURE)
        monitor.record(id, HealthOutcome.NETWORK_FAILURE)
        monitor.record(id, HealthOutcome.MISS)
        monitor.record(id, HealthOutcome.NETWORK_FAILURE)
        assertIs<SourceHealth.Degraded>(monitor.health(id))
        assertTrue(monitor.canAttempt(id))
    }

    @Test
    fun `repeated infrastructure failures open the circuit, then probe, then close`() {
        repeat(3) { monitor.record(id, HealthOutcome.SERVER_ERROR) }
        val open = assertIs<SourceHealth.Unreachable>(monitor.health(id))
        assertFalse(monitor.canAttempt(id))
        assertEquals(clock.nowMillis() + 30_000, open.retryAtMillis)

        clock.advanceBy(30_000)
        assertIs<SourceHealth.Probing>(monitor.health(id))
        assertTrue(monitor.canAttempt(id))

        monitor.record(id, HealthOutcome.SUCCESS)
        assertEquals(SourceHealth.Healthy, monitor.health(id))
    }

    @Test
    fun `a failed probe re-opens the circuit for longer`() {
        repeat(3) { monitor.record(id, HealthOutcome.NETWORK_FAILURE) }
        clock.advanceBy(30_000)
        monitor.record(id, HealthOutcome.NETWORK_FAILURE)
        val reopened = assertIs<SourceHealth.Unreachable>(monitor.health(id))
        assertEquals(2, reopened.openCount)
        assertEquals(clock.nowMillis() + 120_000, reopened.retryAtMillis)
    }

    @Test
    fun `invalid media is per item and does not trip the breaker`() {
        repeat(10) { monitor.record(id, HealthOutcome.INVALID_MEDIA) }
        assertTrue(monitor.canAttempt(id))
    }

    @Test
    fun `auth failure blocks until reset, rate limit until it expires`() {
        monitor.record(id, HealthOutcome.AUTH_FAILURE)
        assertIs<SourceHealth.AuthRejected>(monitor.health(id))
        assertFalse(monitor.canAttempt(id))
        monitor.reset(id)
        assertTrue(monitor.canAttempt(id))

        monitor.record(id, HealthOutcome.RATE_LIMIT, retryAfterMillis = 5_000)
        assertFalse(monitor.canAttempt(id))
        clock.advanceBy(5_000)
        assertTrue(monitor.canAttempt(id))
    }

    @Test
    fun `policy disabled blocks attempts`() {
        monitor.record(id, HealthOutcome.POLICY_DISABLED)
        assertEquals(SourceHealth.Disabled, monitor.health(id))
    }
}
