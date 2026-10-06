package app.podium.sources.api

import app.podium.core.common.ManualClock
import app.podium.core.model.SourceId
import app.podium.sources.testing.FakeMusicSource
import app.podium.sources.testing.FakeOnlineMusicSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceSettingsTest {
    private val store = InMemorySourcePreferencesStore()

    /** A fresh process: new registry, same sources registered, same stored preferences. */
    private fun launch(): Pair<SourceRegistry, SourceSettings> {
        val registry = SourceRegistry(SourceHealthMonitor(ManualClock()))
        registry.register(FakeMusicSource("local"))
        registry.register(FakeOnlineMusicSource("audius"))
        registry.register(FakeOnlineMusicSource("other"), enabled = false)
        val settings = SourceSettings(registry, store).also { it.apply() }
        return registry to settings
    }

    private val audius = SourceId("audius")
    private val other = SourceId("other")
    private val local = SourceId("local")

    @Test
    fun `sources start with the defaults they were registered with`() {
        val (registry, _) = launch()
        assertTrue(registry.isEnabled(audius))
        assertFalse(registry.isEnabled(other))
    }

    @Test
    fun `turning a source on or off survives a restart`() {
        val (_, settings) = launch()
        settings.setEnabled(other, true)
        settings.setEnabled(audius, false)
        val (registry, _) = launch()
        assertTrue(registry.isEnabled(other))
        assertFalse(registry.isEnabled(audius))
    }

    @Test
    fun `priority survives a restart`() {
        val (registry, settings) = launch()
        settings.setEnabled(other, true)
        settings.move(other, 0, registry.registered(MusicEnvironment.ONLINE).map { it.descriptor.id })
        val (restarted, _) = launch()
        assertEquals(listOf(other, audius), restarted.ordered(MusicEnvironment.ONLINE).map { it.descriptor.id })
    }

    @Test
    fun `moving among online sources leaves local sources in place`() {
        val (registry, settings) = launch()
        val online = registry.registered(MusicEnvironment.ONLINE).map { it.descriptor.id }
        settings.move(other, 0, online)
        assertEquals(listOf(local, other, audius), registry.snapshotIds())
    }

    @Test
    fun `a stored choice about a source this build doesn't have is ignored`() {
        store.save(SourcePreferences(enabled = mapOf(SourceId("gone") to true), priority = listOf(SourceId("gone"), other)))
        val (registry, _) = launch()
        assertEquals(listOf(other, local, audius), registry.snapshotIds())
        assertFalse(registry.isEnabled(other), "priority doesn't switch a source on")
    }

    @Test
    fun `turning a source back on clears its old breaker`() {
        val health = SourceHealthMonitor(ManualClock())
        val registry = SourceRegistry(health)
        registry.register(FakeOnlineMusicSource("audius"))
        repeat(3) { health.record(audius, HealthOutcome.NETWORK_FAILURE) }
        registry.setEnabled(audius, false)
        registry.setEnabled(audius, true)
        assertEquals(SourceHealth.Healthy, registry.health(audius))
    }

    @Test
    fun `environments are ordered separately`() {
        val (registry, _) = launch()
        assertEquals(listOf(audius), registry.ordered(MusicEnvironment.ONLINE).map { it.descriptor.id })
        assertEquals(listOf(local), registry.ordered(MusicEnvironment.LOCAL).map { it.descriptor.id })
        assertEquals(listOf(audius, other), registry.registered(MusicEnvironment.ONLINE).map { it.descriptor.id }, "registered includes disabled")
    }
}
