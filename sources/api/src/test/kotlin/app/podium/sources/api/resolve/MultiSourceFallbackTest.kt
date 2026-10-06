package app.podium.sources.api.resolve

import app.podium.core.common.ManualClock
import app.podium.core.common.PodiumError
import app.podium.core.model.Track
import app.podium.sources.api.MissReason
import app.podium.sources.api.ResolutionPath
import app.podium.sources.api.SourceHealth
import app.podium.sources.api.SourceHealthMonitor
import app.podium.sources.api.SourceRegistry
import app.podium.sources.api.aggregate.TrackGrouper
import app.podium.sources.api.aggregate.remember
import app.podium.sources.api.matching.TrackMatcher
import app.podium.sources.testing.FakeOnlineMusicSource
import app.podium.sources.testing.FakeOnlineMusicSource.Behavior
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Playback fallback across online sources (D-17, D-35): a miss moves on, only EXACT plays. */
class MultiSourceFallbackTest {
    private val clock = ManualClock(1_000_000)
    private val health = SourceHealthMonitor(clock)
    private val registry = SourceRegistry(health)
    private val equivalence = InMemoryEquivalenceStore()
    private val resolver = StreamResolver(registry, health, TrackMatcher(), clock, equivalence = equivalence)

    private val a = FakeOnlineMusicSource("a").also { registry.register(it) }
    private val b = FakeOnlineMusicSource("b").also { registry.register(it) }
    private val onA: Track = a.track("Song Shared", artist = "Band")
    private val onB: Track = b.track("Song Shared", artist = "Band", durationSec = 201.0)

    private fun knowTwins() =
        TrackGrouper().group(listOf(TrackGrouper.Ranked(a.sourceId, listOf(onA)), TrackGrouper.Ranked(b.sourceId, listOf(onB)))).forEach(equivalence::remember)

    @Test
    fun `a miss on the preferred source plays the exact copy on the next one`() = runTest {
        a.unstreamable += onA.id
        knowTwins()
        val outcome = assertIs<ResolveOutcome.Resolved>(resolver.resolve(ResolveRequest(onA)))
        assertEquals(ResolutionPath.EXACT_FALLBACK, outcome.selection.path)
        assertEquals(b.sourceId, outcome.selection.servedBy)
        assertEquals(onB, outcome.selection.servedTrack)
        assertEquals(0, b.searchCalls, "a known twin is played without searching again")
    }

    @Test
    fun `without a known twin the next source is searched and the matcher decides`() = runTest {
        a.resolveBehavior = Behavior.Miss
        val outcome = assertIs<ResolveOutcome.Resolved>(resolver.resolve(ResolveRequest(onA)))
        assertEquals(b.sourceId, outcome.selection.servedBy)
        assertEquals(1, b.searchCalls)
    }

    @Test
    fun `a miss never counts against the source`() = runTest {
        a.resolveBehavior = Behavior.Miss
        knowTwins()
        repeat(5) { resolver.resolve(ResolveRequest(onA)) }
        assertEquals(SourceHealth.Healthy, health.health(a.sourceId))
    }

    @Test
    fun `a failure counts against the source, and the exact copy still plays`() = runTest {
        a.resolveBehavior = Behavior.Fail(PodiumError.Network())
        knowTwins()
        val outcome = assertIs<ResolveOutcome.Resolved>(resolver.resolve(ResolveRequest(onA)))
        assertEquals(b.sourceId, outcome.selection.servedBy)
        assertIs<SourceHealth.Degraded>(health.health(a.sourceId))
    }

    @Test
    fun `three failures open the breaker and the source isn't even asked`() = runTest {
        a.resolveBehavior = Behavior.Fail(PodiumError.Server(503))
        knowTwins()
        repeat(3) { resolver.resolve(ResolveRequest(onA)) }
        assertIs<SourceHealth.Unreachable>(health.health(a.sourceId))
        val calls = a.resolveCalls
        assertIs<ResolveOutcome.Resolved>(resolver.resolve(ResolveRequest(onA)))
        assertEquals(calls, a.resolveCalls)
    }

    @Test
    fun `a close but not exact copy is never played instead`() = runTest {
        a.resolveBehavior = Behavior.Miss
        val strong = b.track("Close Song", artist = "Band", key = "close-b", durationSec = 204.0)
        val target = a.track("Close Song", artist = "Band", key = "close-a")
        val outcome = assertIs<ResolveOutcome.Miss>(resolver.resolve(ResolveRequest(target)))
        assertEquals(MissReason.NOT_FOUND, outcome.reason)
        assertEquals(1, b.searchCalls, "the close copy was found and judged")
        assertEquals(0, b.resolveCalls, "…and not played: ${strong.durationMs} ms isn't the same take")
    }

    @Test
    fun `a copy of unknown length is never played instead`() = runTest {
        a.resolveBehavior = Behavior.Miss
        val target = a.track("Lengthless", artist = "Band", key = "len-a")
        b.track("Lengthless", artist = "Band", key = "len-b").let { b.put(it.copy(durationMs = null)) }
        assertIs<ResolveOutcome.Miss>(resolver.resolve(ResolveRequest(target)))
    }

    @Test
    fun `a different version is never played instead`() = runTest {
        a.resolveBehavior = Behavior.Miss
        val target = a.track("Anthem", artist = "Band", key = "anthem-a")
        b.track("Anthem (Live)", artist = "Band", key = "anthem-live-b")
        assertIs<ResolveOutcome.Miss>(resolver.resolve(ResolveRequest(target)))
    }

    @Test
    fun `a pairing the listener rejected is never used`() = runTest {
        a.unstreamable += onA.id
        knowTwins()
        equivalence.reject(onA, onB)
        b.searchBehavior = Behavior.Miss
        assertIs<ResolveOutcome.Miss>(resolver.resolve(ResolveRequest(onA)))
    }

    @Test
    fun `a disabled source is never a fallback`() = runTest {
        a.unstreamable += onA.id
        knowTwins()
        registry.setEnabled(b.sourceId, false)
        assertIs<ResolveOutcome.Miss>(resolver.resolve(ResolveRequest(onA)))
        assertEquals(0, b.resolveCalls)
    }

    @Test
    fun `fallback follows the listener's priority`() = runTest {
        val c = FakeOnlineMusicSource("c").also { registry.register(it) }
        val onC = c.track("Song Shared", artist = "Band")
        a.unstreamable += onA.id
        TrackGrouper().group(
            listOf(TrackGrouper.Ranked(a.sourceId, listOf(onA)), TrackGrouper.Ranked(b.sourceId, listOf(onB)), TrackGrouper.Ranked(c.sourceId, listOf(onC))),
        ).forEach(equivalence::remember)
        registry.setPriority(listOf(a.sourceId, c.sourceId, b.sourceId))
        val outcome = assertIs<ResolveOutcome.Resolved>(resolver.resolve(ResolveRequest(onA)))
        assertEquals(c.sourceId, outcome.selection.servedBy)
    }

    @Test
    fun `a pinned copy is never swapped for another source's`() = runTest {
        val first = assertIs<ResolveOutcome.Resolved>(resolver.resolve(ResolveRequest(onA)))
        knowTwins()
        a.unstreamable += onA.id
        val again = assertIs<ResolveOutcome.Resolved>(resolver.resolve(ResolveRequest(onA, pinned = first.selection)))
        assertEquals(a.sourceId, again.selection.servedBy)
    }
}
