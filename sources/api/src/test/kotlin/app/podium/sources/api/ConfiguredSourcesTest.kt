package app.podium.sources.api

import app.podium.core.common.ManualClock
import app.podium.core.model.SourceId
import app.podium.sources.testing.FakeOnlineMusicSource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfiguredSourcesTest {
    private val profiles = InMemorySourceProfileStore()
    private val credentials = InMemoryCredentialStore()
    private val preferences = InMemorySourcePreferencesStore()
    private var ids = 0

    /** A kind of source that accepts the password "right". */
    private val factory = object : SourceFactory {
        override val form = SetupForm("server", "Add a music server", listOf(SetupField("address", "Server address"), SetupField("password", "Password", kind = SetupField.Kind.SECRET)))

        override suspend fun connect(values: Map<String, String>): ConnectResult =
            if (values["password"] == "right") ConnectResult.Connected("Server (${values["address"]})", mapOf("address" to values["address"].orEmpty()), mapOf("password" to "right"))
            else ConnectResult.Refused(SetupProblem.WRONG_CREDENTIALS)

        override fun create(profile: SourceProfile, credentials: CredentialStore): MusicSource = object : MusicSource by FakeOnlineMusicSource(profile.id.value) {
            override val descriptor = SourceDescriptor(profile.id, profile.displayName, "Fake", Basis.USER_SERVER)
        }
    }

    /** A fresh process: new registry, the same stores. */
    private fun launch(): Triple<SourceRegistry, SourceSettings, ConfiguredSources> {
        val registry = SourceRegistry(SourceHealthMonitor(ManualClock()))
        registry.register(FakeOnlineMusicSource("audius"))
        val settings = SourceSettings(registry, preferences)
        val configured = ConfiguredSources(registry, settings, profiles, credentials, listOf(factory)) { SourceId("$it-${++ids}") }
        configured.restore()
        settings.apply()
        return Triple(registry, settings, configured)
    }

    @Test
    fun `adding a server keeps its profile and secret apart, and registers it as a source`() = runTest {
        val (registry, _, configured) = launch()
        assertIs<ConnectResult.Connected>(configured.add("server", mapOf("address" to "https://a", "password" to "right")))
        val profile = profiles.load().single()
        assertEquals(SourceId("server-1"), profile.id)
        assertFalse("password" in profile.settings, "no secret in the profile")
        assertEquals("right", credentials.read(profile.id)?.get("password"))
        assertEquals("Server (https://a)", registry.get(profile.id)?.descriptor?.displayName)
        assertEquals(MusicEnvironment.ONLINE, registry.get(profile.id)?.descriptor?.environment)
    }

    @Test
    fun `a refused setup keeps nothing`() = runTest {
        val (registry, _, configured) = launch()
        assertIs<ConnectResult.Refused>(configured.add("server", mapOf("address" to "https://a", "password" to "wrong")))
        assertTrue(profiles.load().isEmpty())
        assertEquals(listOf(SourceId("audius")), registry.snapshotIds())
    }

    @Test
    fun `configured servers come back after a restart, with their order and on-off state`() = runTest {
        val (_, settings, configured) = launch()
        configured.add("server", mapOf("address" to "https://a", "password" to "right"))
        configured.add("server", mapOf("address" to "https://b", "password" to "right"))
        settings.move(SourceId("server-2"), 0, listOf(SourceId("audius"), SourceId("server-1"), SourceId("server-2")))
        settings.setEnabled(SourceId("server-1"), false)
        val (registry, _, _) = launch()
        assertEquals(listOf("server-2", "audius", "server-1"), registry.snapshotIds().map { it.value })
        assertFalse(registry.isEnabled(SourceId("server-1")))
        assertEquals(2, registry.registered(MusicEnvironment.ONLINE).count { it.descriptor.basis == Basis.USER_SERVER })
    }

    @Test
    fun `removing a server forgets it entirely`() = runTest {
        val (registry, settings, configured) = launch()
        configured.add("server", mapOf("address" to "https://a", "password" to "right"))
        settings.setEnabled(SourceId("server-1"), false)
        configured.remove(SourceId("server-1"))
        assertNull(registry.get(SourceId("server-1")))
        assertNull(credentials.read(SourceId("server-1")))
        assertTrue(profiles.load().isEmpty())
        assertFalse(SourceId("server-1") in preferences.load().enabled)
        configured.remove(SourceId("audius"))
        assertTrue(registry.get(SourceId("audius")) != null, "a built-in source can't be removed")
    }

    @Test
    fun `an unknown kind can't be added and a stored one of an unknown kind is skipped`() = runTest {
        profiles.save(listOf(SourceProfile(SourceId("gone-1"), "gone", "Old thing")))
        val (registry, _, configured) = launch()
        assertNull(registry.get(SourceId("gone-1")))
        assertEquals(SetupProblem.NOT_SUPPORTED, assertIs<ConnectResult.Refused>(configured.add("gone", emptyMap())).problem)
        assertEquals(listOf("server"), configured.forms.map { it.kind })
    }

    @Test
    fun `a connect result never prints its secrets`() {
        val text = ConnectResult.Connected("Server", mapOf("address" to "https://music.example.com"), mapOf("password" to "hunter2")).toString()
        assertFalse("hunter2" in text, text)
        assertTrue("password" in text, "which secrets, never their values")
    }
}
