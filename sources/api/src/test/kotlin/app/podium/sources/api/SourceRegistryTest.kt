package app.podium.sources.api

import app.podium.core.common.ManualClock
import app.podium.core.model.SourceId
import app.podium.sources.testing.FakeMusicSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceRegistryTest {
    private val health = SourceHealthMonitor(ManualClock())
    private val registry = SourceRegistry(health)

    @Test
    fun `register, retrieve and unregister`() {
        val a = FakeMusicSource("a")
        registry.register(a)
        assertEquals(a, registry.get(SourceId("a")))
        registry.unregister(SourceId("a"))
        assertNull(registry.get(SourceId("a")))
    }

    @Test
    fun `duplicate registration is rejected`() {
        registry.register(FakeMusicSource("a"))
        assertFailsWith<IllegalArgumentException> { registry.register(FakeMusicSource("a")) }
    }

    @Test
    fun `user priority orders sources, unlisted ones follow in registration order`() {
        listOf("a", "b", "c").forEach { registry.register(FakeMusicSource(it)) }
        assertEquals(listOf("a", "b", "c"), registry.ordered().map { it.descriptor.id.value })
        registry.setPriority(listOf(SourceId("c"), SourceId("a")))
        assertEquals(listOf("c", "a", "b"), registry.ordered().map { it.descriptor.id.value })
    }

    @Test
    fun `disabled sources are excluded from ordering and report Disabled health`() {
        registry.register(FakeMusicSource("a"))
        registry.register(FakeMusicSource("b"), enabled = false)
        assertEquals(listOf("a"), registry.ordered().map { it.descriptor.id.value })
        assertEquals(SourceHealth.Disabled, registry.health(SourceId("b")))
    }

    @Test
    fun `capability queries use effective capability state`() {
        val a = FakeMusicSource("a")
        val b = FakeMusicSource("b")
        registry.register(a)
        registry.register(b)
        b.setCapability(Capability.SEARCH, CapabilityState(CapabilityStatus.REQUIRES_SIGN_IN, "Sign in"))
        assertEquals(listOf(a), registry.withCapability(Capability.SEARCH))
    }

    @Test
    fun `connected sources combine capabilities, health and auth`() = runTest {
        registry.register(FakeMusicSource("a"))
        registry.register(FakeMusicSource("b"))
        repeat(3) { health.record(SourceId("b"), HealthOutcome.NETWORK_FAILURE) }
        val connected = registry.connectedSources.first()
        assertEquals(listOf("a", "b"), connected.map { it.sourceId.value })
        assertTrue(connected[0].capabilities.isUsable(Capability.DIRECT_STREAM))
        assertTrue(connected[1].health is SourceHealth.Unreachable)
        assertEquals(AuthState.NotRequired, connected[0].authenticationState)
    }
}
