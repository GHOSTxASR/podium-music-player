package app.podium

import app.podium.core.common.ManualClock
import app.podium.core.common.Outcome
import app.podium.core.common.PodiumError
import app.podium.core.model.SourceId
import app.podium.feature.settings.AddSourceResult
import app.podium.feature.settings.CustomSourceOption
import app.podium.feature.settings.CustomSourceStatus
import app.podium.sources.addon.AddonManifest
import app.podium.sources.addon.AddonMusicSource
import app.podium.sources.addon.AddonSetting
import app.podium.sources.api.InMemoryCredentialStore
import app.podium.sources.api.MusicEnvironment
import app.podium.sources.api.SourceHealth
import app.podium.sources.api.SourceHealthMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CustomSourcesTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = ManualClock(1_000_000)
    private val health = SourceHealthMonitor(clock)
    private val credentials = InMemoryCredentialStore()

    private fun manifest(id: String = "com.example.lossless", name: String = "Lossless test", resources: Set<String> = setOf("search", "stream")) = AddonManifest(
        id = id,
        name = name,
        resources = resources,
        settings = listOf(
            AddonSetting(
                "quality", "Quality", AddonSetting.Type.SELECT, "lossless",
                listOf(AddonSetting.Option("Lossless", "lossless"), AddonSetting.Option("High", "high")),
            ),
        ),
    )

    private val answers = mutableMapOf<String, Outcome<AddonManifest>>()
    private val asked = mutableListOf<String>()

    /** The store's own scope: background work in a test's backgroundScope never runs to idle. */
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    @After fun stop() = scope.cancel()

    private fun TestScope.store() = CustomSources(
        credentials, health, scope, clock::nowMillis, io = dispatcher,
        fetchManifest = { base -> asked += base; answers[base] ?: Outcome.Failure(PodiumError.NotFound("manifest")) },
    )

    @Test
    fun `adds a source from its manifest address`() = runTest(dispatcher) {
        answers["https://addon.example.com/u/token123"] = Outcome.Success(manifest())
        val store = store()
        val added = assertIs<AddSourceResult.Added>(store.add("https://addon.example.com/u/token123/manifest.json"))
        assertEquals("Lossless test", added.name)
        advanceUntilIdle()
        val state = store.sources.value.single()
        assertEquals("addon.example.com", state.host, "the host alone: the rest of the address can carry a token")
        assertTrue(state.enabled)
        assertEquals(CustomSourceStatus.Ready, state.status)
        val quality = state.options.single()
        assertEquals(CustomSourceOption.Kind.CHOICE, quality.kind)
        assertEquals("lossless", quality.value)
        assertEquals("Lossless", quality.valueLabel)
        assertEquals(1, store.ordered(MusicEnvironment.ONLINE).size)
        assertEquals(0, store.ordered(MusicEnvironment.LOCAL).size, "custom sources serve online songs only")
    }

    @Test
    fun `addresses Podium won't use are explained`() = runTest(dispatcher) {
        val store = store()
        assertEquals(
            "Podium uses unencrypted addresses only on your own network. Use the https address.",
            assertIs<AddSourceResult.Problem>(store.add("http://addon.example.com/manifest.json")).message,
        )
        assertIs<AddSourceResult.Problem>(store.add("  "))
        assertEquals(AddSourceResult.NeedsLocalConsent, store.add("http://192.168.1.20:7000/manifest.json"))
        assertTrue(asked.isEmpty(), "nothing was asked before the address was good")
        answers["http://192.168.1.20:7000"] = Outcome.Success(manifest())
        assertIs<AddSourceResult.Added>(store.add("http://192.168.1.20:7000/manifest.json", allowLocalUnencrypted = true))
    }

    @Test
    fun `sources that can't serve, or don't answer, aren't added`() = runTest(dispatcher) {
        val store = store()
        assertEquals(
            "No source answered at that address. Check it and try again.",
            assertIs<AddSourceResult.Problem>(store.add("https://nothing.example.com")).message,
        )
        answers["https://down.example.com"] = Outcome.Failure(PodiumError.Network("IOException"))
        assertTrue(assertIs<AddSourceResult.Problem>(store.add("https://down.example.com")).message.startsWith("Couldn't reach"))
        answers["https://meta.example.com"] = Outcome.Success(manifest(resources = setOf("search")))
        assertEquals(
            "Lossless test doesn't offer streams, so it can't play songs.",
            assertIs<AddSourceResult.Problem>(store.add("https://meta.example.com")).message,
        )
        assertTrue(store.sources.value.isEmpty())
    }

    @Test
    fun `the same source can't be added twice`() = runTest(dispatcher) {
        answers["https://addon.example.com"] = Outcome.Success(manifest())
        val store = store()
        assertIs<AddSourceResult.Added>(store.add("https://addon.example.com"))
        assertEquals("That source is already in your list.", assertIs<AddSourceResult.Problem>(store.add("addon.example.com/manifest.json")).message)
    }

    @Test
    fun `order, on and off, and settings`() = runTest(dispatcher) {
        answers["https://a.example.com"] = Outcome.Success(manifest(id = "a", name = "A"))
        answers["https://b.example.com"] = Outcome.Success(manifest(id = "b", name = "B"))
        val store = store()
        val a = (store.add("https://a.example.com") as AddSourceResult.Added).id
        val b = (store.add("https://b.example.com") as AddSourceResult.Added).id
        assertEquals(listOf("A", "B"), store.ordered(MusicEnvironment.ONLINE).map { it.descriptor.displayName })
        store.move(b, -1)
        assertEquals(listOf("B", "A"), store.ordered(MusicEnvironment.ONLINE).map { it.descriptor.displayName })
        store.setEnabled(b, false)
        assertEquals(listOf("A"), store.ordered(MusicEnvironment.ONLINE).map { it.descriptor.displayName })
        assertNull(store.get(SourceId(b)), "a source that's off serves nothing, pinned streams included")
        assertEquals(setOf(SourceId(a)), store.serving.first())
        store.setOption(a, "quality", "high")
        assertEquals("high", (store.get(SourceId(a)) as AddonMusicSource).config.settings["quality"])
        store.setOption(a, "quality", "ultra")
        assertEquals("high", (store.get(SourceId(a)) as AddonMusicSource).config.settings["quality"], "only values the source declares")
    }

    @Test
    fun `kept sealed on the phone, in order, across restarts`() = runTest(dispatcher) {
        answers["https://a.example.com/u/secret"] = Outcome.Success(manifest(id = "a", name = "A"))
        answers["https://b.example.com"] = Outcome.Success(manifest(id = "b", name = "B"))
        val first = store()
        val a = (first.add("https://a.example.com/u/secret") as AddSourceResult.Added).id
        val b = (first.add("https://b.example.com") as AddSourceResult.Added).id
        first.move(b, -1)
        first.setEnabled(a, false)
        first.setOption(b, "quality", "high")
        advanceUntilIdle()
        val again = store()
        advanceUntilIdle()
        val list = again.sources.value
        assertEquals(listOf("B", "A"), list.map { it.name })
        assertEquals(listOf(true, false), list.map { it.enabled })
        assertEquals("high", list.first().options.single().value)
        assertEquals("https://a.example.com/u/secret", (again.sourcesFor(a)).config.base)
    }

    private fun CustomSources.sourcesFor(id: String): AddonMusicSource {
        setEnabled(id, true)
        return get(SourceId(id)) as AddonMusicSource
    }

    @Test
    fun `removing forgets the source and its address`() = runTest(dispatcher) {
        answers["https://a.example.com/u/secret"] = Outcome.Success(manifest())
        val store = store()
        val id = (store.add("https://a.example.com/u/secret") as AddSourceResult.Added).id
        advanceUntilIdle()
        assertTrue(credentials.read(SourceId(id)) != null)
        store.remove(id)
        advanceUntilIdle()
        assertTrue(store.sources.value.isEmpty())
        assertNull(credentials.read(SourceId(id)))
        assertTrue(store().sources.value.isEmpty())
    }

    @Test
    fun `check asks again and says what it found`() = runTest(dispatcher) {
        answers["https://a.example.com"] = Outcome.Success(manifest())
        val store = store()
        val id = (store.add("https://a.example.com") as AddSourceResult.Added).id
        answers["https://a.example.com"] = Outcome.Failure(PodiumError.Server(502))
        store.check(id)
        advanceUntilIdle()
        assertEquals(
            CustomSourceStatus.Problem("The source answered with an error (HTTP 502). Try again later."),
            store.sources.value.single().status,
        )
        assertIs<SourceHealth.Degraded>(health.health(SourceId(id)))
        answers["https://a.example.com"] = Outcome.Success(manifest(name = "Lossless test 2"))
        store.check(id)
        advanceUntilIdle()
        assertEquals(CustomSourceStatus.Ready, store.sources.value.single().status)
        assertEquals("Lossless test 2", store.sources.value.single().name, "a renamed source keeps its new name")
        answers["https://a.example.com"] = Outcome.Success(manifest(id = "someone.else"))
        store.check(id)
        advanceUntilIdle()
        assertIs<CustomSourceStatus.Problem>(store.sources.value.single().status)
    }
}
