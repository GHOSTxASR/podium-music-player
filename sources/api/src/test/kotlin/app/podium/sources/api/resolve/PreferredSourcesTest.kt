package app.podium.sources.api.resolve

import app.podium.core.common.ManualClock
import app.podium.core.common.PodiumError
import app.podium.core.model.Explicitness
import app.podium.core.model.SourceId
import app.podium.core.model.Track
import app.podium.sources.api.MusicEnvironment
import app.podium.sources.api.MusicSource
import app.podium.sources.api.Purpose
import app.podium.sources.api.ResolutionPath
import app.podium.sources.api.SourceHealth
import app.podium.sources.api.SourceHealthMonitor
import app.podium.sources.api.SourceRegistry
import app.podium.sources.api.matching.TrackMatcher
import app.podium.sources.testing.FakeOnlineMusicSource
import app.podium.sources.testing.FakeOnlineMusicSource.Behavior
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Custom sources (D-91, D-92): the listener's own stream sources are asked first, in their order, for
 * any EXACT copy; the song's own source is always the fallback.
 */
class PreferredSourcesTest {
    private val clock = ManualClock(1_000_000)
    private val health = SourceHealthMonitor(clock)
    private val registry = SourceRegistry(health)
    private val equivalence = InMemoryEquivalenceStore()

    /** The catalogue source (YouTube Music in the app). */
    private val own = FakeOnlineMusicSource("own").also { registry.register(it) }

    /** Two custom sources, in the listener's order. Not in the registry: they aren't catalogues. */
    private val first = FakeOnlineMusicSource("custom-1")
    private val second = FakeOnlineMusicSource("custom-2")
    private val providers = Providers(mutableListOf(first, second))

    private val resolver = StreamResolver(
        registry, health, TrackMatcher(), clock, equivalence = equivalence,
        preferred = providers, preferredBudgetMillis = 5_000,
    )

    private val song: Track = own.track("Night Drive", artist = "Band")

    private class Providers(val list: MutableList<FakeOnlineMusicSource>) : StreamProviders {
        override fun ordered(environment: MusicEnvironment): List<MusicSource> = list.filter { it.descriptor.environment == environment }
        override fun get(id: SourceId): MusicSource? = list.firstOrNull { it.descriptor.id == id }
    }

    @Test
    fun `an exact copy on a custom source plays first, and the song's own source isn't asked`() = runTest {
        val copy = first.track("Night Drive", artist = "Band", durationSec = 201.0)
        val outcome = assertIs<ResolveOutcome.Resolved>(resolver.resolve(ResolveRequest(song)))
        assertEquals(ResolutionPath.PREFERRED_SOURCE, outcome.selection.path)
        assertEquals(first.sourceId, outcome.selection.servedBy)
        assertEquals(copy, outcome.selection.servedTrack)
        assertEquals(0, own.resolveCalls, "the song's own source is the fallback, not asked first")
    }

    @Test
    fun `the listener's order decides between custom sources`() = runTest {
        first.track("Night Drive", artist = "Band")
        second.track("Night Drive", artist = "Band")
        providers.list.reverse()
        val outcome = assertIs<ResolveOutcome.Resolved>(resolver.resolve(ResolveRequest(song)))
        assertEquals(second.sourceId, outcome.selection.servedBy)
    }

    @Test
    fun `no exact copy anywhere, so the song's own source plays it`() = runTest {
        first.track("Night Drive (Live)", artist = "Band")
        second.track("Night Drive", artist = "Somebody Else")
        val outcome = assertIs<ResolveOutcome.Resolved>(resolver.resolve(ResolveRequest(song)))
        assertEquals(ResolutionPath.OWN_SOURCE, outcome.selection.path)
        assertEquals(own.sourceId, outcome.selection.servedBy)
        assertEquals(1, first.searchCalls)
        assertEquals(0, first.resolveCalls, "a live take is never streamed in place of the studio song")
    }

    @Test
    fun `a custom source that fails or misses falls through to the next, then to the song's own source`() = runTest {
        first.track("Night Drive", artist = "Band")
        first.resolveBehavior = Behavior.Fail(PodiumError.Server(503))
        second.track("Night Drive", artist = "Band")
        second.resolveBehavior = Behavior.Miss
        val outcome = assertIs<ResolveOutcome.Resolved>(resolver.resolve(ResolveRequest(song)))
        assertEquals(ResolutionPath.OWN_SOURCE, outcome.selection.path)
        assertIs<SourceHealth.Degraded>(health.health(first.sourceId))
        assertEquals(SourceHealth.Healthy, health.health(second.sourceId), "a miss isn't a failure")
    }

    @Test
    fun `slow custom sources never hold the song back beyond the budget`() = runTest {
        first.track("Night Drive", artist = "Band")
        first.searchBehavior = Behavior.Hang(60_000)
        val outcome = assertIs<ResolveOutcome.Resolved>(resolver.resolve(ResolveRequest(song)))
        assertEquals(ResolutionPath.OWN_SOURCE, outcome.selection.path)
        assertTrue(testScheduler.currentTime <= 5_000 + 100, "budget kept: ${testScheduler.currentTime} ms")
        assertTrue(outcome.attempts.any { it.detail == "custom sources took too long" })
    }

    @Test
    fun `an explicit song plays from a copy that doesn't say which edition it is (D-92)`() = runTest {
        val explicitSong = own.track("Loud Song", artist = "Band").copy(explicitness = Explicitness.EXPLICIT).also(own::put)
        first.track("Loud Song", artist = "Band")
        val outcome = assertIs<ResolveOutcome.Resolved>(resolver.resolve(ResolveRequest(explicitSong)))
        assertEquals(first.sourceId, outcome.selection.servedBy, "if the song is there, it plays from there")
    }

    @Test
    fun `a clean copy never plays for an explicit song`() = runTest {
        val explicitSong = own.track("Loud Song", artist = "Band").copy(explicitness = Explicitness.EXPLICIT).also(own::put)
        first.put(first.track("Loud Song", artist = "Band").copy(explicitness = Explicitness.CLEAN))
        val outcome = assertIs<ResolveOutcome.Resolved>(resolver.resolve(ResolveRequest(explicitSong)))
        assertEquals(own.sourceId, outcome.selection.servedBy, "a clean copy is a different recording")
        assertEquals(0, first.resolveCalls)
    }

    @Test
    fun `a song its own source doesn't mark plays from a copy marked explicit too`() = runTest {
        first.put(first.track("Night Drive", artist = "Band").copy(explicitness = Explicitness.EXPLICIT))
        val outcome = assertIs<ResolveOutcome.Resolved>(resolver.resolve(ResolveRequest(song)))
        assertEquals(first.sourceId, outcome.selection.servedBy)
    }

    @Test
    fun `asked again in the background, only the custom sources answer (D-92)`() = runTest {
        first.track("Night Drive", artist = "Band")
        first.searchBehavior = Behavior.Hang(60_000)
        val slow = assertIs<ResolveOutcome.Resolved>(resolver.resolve(ResolveRequest(song)))
        assertEquals(ResolutionPath.OWN_SOURCE, slow.selection.path)
        assertTrue(StreamResolver.mayStillAnswer(slow.attempts), "too slow isn't \"not there\"")
        // The network recovers: the background ask finds the copy, and the own source isn't asked.
        first.searchBehavior = Behavior.Answer
        val ownCalls = own.resolveCalls
        val later = assertIs<ResolveOutcome.Resolved>(resolver.preferredCopy(song))
        assertEquals(ResolutionPath.PREFERRED_SOURCE, later.selection.path)
        assertEquals(first.sourceId, later.selection.servedBy)
        assertEquals(ownCalls, own.resolveCalls)
    }

    @Test
    fun `a song no custom source has isn't asked for again`() = runTest {
        first.track("Night Drive (Live)", artist = "Band")
        val outcome = assertIs<ResolveOutcome.Resolved>(resolver.resolve(ResolveRequest(song)))
        assertTrue(!StreamResolver.mayStillAnswer(outcome.attempts), "every custom source answered: it hasn't got it")
        assertIs<ResolveOutcome.Miss>(resolver.preferredCopy(song))
        first.resolveBehavior = Behavior.Fail(PodiumError.Server(503))
        first.track("Night Drive", artist = "Band")
        val failing = resolver.preferredCopy(song)
        assertIs<ResolveOutcome.Failed>(failing, "a failing source may have it later")
    }

    @Test
    fun `never asked when the queue item may not leave its source, or for downloads`() = runTest {
        first.track("Night Drive", artist = "Band")
        val none = assertIs<ResolveOutcome.Resolved>(resolver.resolve(ResolveRequest(song, fallbackPolicy = FallbackPolicy.NONE)))
        assertEquals(own.sourceId, none.selection.servedBy)
        val download = assertIs<ResolveOutcome.Resolved>(resolver.resolve(ResolveRequest(song, purpose = Purpose.DOWNLOAD)))
        assertEquals(own.sourceId, download.selection.servedBy)
        assertEquals(0, first.searchCalls)
    }

    @Test
    fun `custom sources serve their own environment only`() = runTest {
        val local = FakeLocal("local").also { registry.register(it) }
        val localSong = local.track("Night Drive", artist = "Band")
        first.track("Night Drive", artist = "Band")
        val outcome = assertIs<ResolveOutcome.Resolved>(resolver.resolve(ResolveRequest(localSong)))
        assertEquals(local.sourceId, outcome.selection.servedBy, "a song you own plays as yours, never from an online copy")
        assertEquals(0, first.searchCalls)
    }

    @Test
    fun `a chosen copy is pinned, and a refused stream is asked again from the same custom source`() = runTest {
        first.track("Night Drive", artist = "Band")
        val chosen = assertIs<ResolveOutcome.Resolved>(resolver.resolve(ResolveRequest(song))).selection
        val again = assertIs<ResolveOutcome.Resolved>(resolver.resolve(ResolveRequest(song, pinned = chosen)))
        assertTrue(again.reused)
        assertEquals(1, first.searchCalls, "pinned: not searched again")
        val refreshed = assertIs<ResolveOutcome.Resolved>(resolver.resolve(ResolveRequest(song, pinned = chosen, refreshPinnedSourceOnly = true)))
        assertEquals(first.sourceId, refreshed.selection.servedBy, "mid-track, the same copy from the same source (D-18)")
        assertEquals(0, own.resolveCalls)
    }

    @Test
    fun `a copy already known to be exact is played without searching`() = runTest {
        val copy = first.track("Night Drive", artist = "Band")
        equivalence.put(song, copy, TrackMatcher().match(song, copy))
        val outcome = assertIs<ResolveOutcome.Resolved>(resolver.resolve(ResolveRequest(song)))
        assertEquals(first.sourceId, outcome.selection.servedBy)
        assertEquals(0, first.searchCalls)
    }

    @Test
    fun `without custom sources nothing changes`() = runTest {
        val plain = StreamResolver(registry, health, TrackMatcher(), clock)
        val outcome = assertIs<ResolveOutcome.Resolved>(plain.resolve(ResolveRequest(song)))
        assertEquals(ResolutionPath.OWN_SOURCE, outcome.selection.path)
        assertTrue(outcome.attempts.none { it.step == ResolutionPath.PREFERRED_SOURCE })
    }

    /** A LOCAL source: the fake online source re-labelled as music the listener owns. */
    private class FakeLocal private constructor(private val inner: FakeOnlineMusicSource) : MusicSource by inner {
        constructor(id: String) : this(FakeOnlineMusicSource(id))
        val sourceId get() = inner.sourceId
        override val descriptor = inner.descriptor.copy(environment = MusicEnvironment.LOCAL)
        fun track(title: String, artist: String) = inner.track(title, artist = artist)
    }
}
