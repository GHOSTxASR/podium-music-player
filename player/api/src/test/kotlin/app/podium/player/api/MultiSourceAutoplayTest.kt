package app.podium.player.api

import app.podium.core.common.ManualClock
import app.podium.core.model.QueueUid
import app.podium.core.model.SourceId
import app.podium.core.model.Track
import app.podium.sources.api.MusicEnvironment
import app.podium.sources.api.SourceHealthMonitor
import app.podium.sources.api.SourceRegistry
import app.podium.sources.api.aggregate.TrackGrouper
import app.podium.sources.api.aggregate.remember
import app.podium.sources.api.resolve.InMemoryEquivalenceStore
import app.podium.sources.testing.FakeMusicSource
import app.podium.sources.testing.FakeOnlineMusicSource
import app.podium.sources.testing.FakeOnlineMusicSource.Behavior
import app.podium.sources.testing.track
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Autoplay and radio over several online sources (D-35): same environment, each song once. */
class MultiSourceAutoplayTest {
    private val registry = SourceRegistry(SourceHealthMonitor(ManualClock()))
    private val equivalence = InMemoryEquivalenceStore()
    private val a = FakeOnlineMusicSource("a").also { registry.register(it) }
    private val b = FakeOnlineMusicSource("b").also { registry.register(it) }
    private val local = FakeMusicSource("local", listOf(track("Local Song", source = "local", key = "l"))).also { registry.register(it) }

    private val env: (SourceId) -> MusicEnvironment? = { registry.get(it)?.descriptor?.environment }
    private val engine = AutoplayEngine(sameEnvironment = { x, y -> env(x) == env(y) }, equivalents = equivalence::exactEquivalents)
    private val recommender = SourceRecommendationEngine(registry, equivalence::exactEquivalents)
    private val on = AutoplaySettings()

    private fun queue(vararg tracks: Track) = QueueState(
        items = tracks.mapIndexed { i, t -> QueueItem(QueueUid("q$i"), t, QueueOrigin.CONTEXT) },
        currentIndex = tracks.lastIndex,
    )

    @Test
    fun `a mixed-source queue keeps every item's own source`() {
        val alpha = a.track("Song Alpha")
        val gamma = b.track("Song Gamma")
        val beta = a.track("Song Beta")
        val state = queue(alpha, gamma, beta)
        assertEquals(listOf(a.sourceId, b.sourceId, a.sourceId), state.items.map { it.preferredSource })
        assertEquals(listOf(alpha, gamma, beta), engine.seeds(state), "seeds come from every online source")
    }

    @Test
    fun `suggestions may come from any online source, never from local music`() {
        val state = queue(a.track("Song Alpha"))
        val candidates = listOf(b.track("Song Gamma"), local.let { track("Local Song", source = "local", key = "l") }, a.track("Song Beta"))
        val picked = engine.pick(candidates, state, emptySet(), on)
        assertEquals(listOf("Song Gamma", "Song Beta"), picked.map { it.title })
    }

    @Test
    fun `two copies of one song from two sources are never both added`() {
        val state = queue(a.track("Opening", artist = "Someone"))
        val onA = a.track("Song Shared", artist = "Band")
        val onB = b.track("Song Shared", artist = "Band", durationSec = 201.0)
        val picked = engine.pick(listOf(onA, onB, b.track("Song Gamma")), state, emptySet(), on)
        assertEquals(1, picked.count { it.title == "Song Shared" })
        assertTrue(picked.any { it.title == "Song Gamma" })
    }

    @Test
    fun `a copy of a song already queued is left out, whatever source it's on`() {
        val onA = a.track("Song Shared", artist = "Band")
        val onB = b.track("Song Shared", artist = "Band", durationSec = 201.0)
        val picked = engine.pick(listOf(onB), queue(onA), emptySet(), on)
        assertTrue(picked.isEmpty())
    }

    @Test
    fun `a copy of a song just played is left out when its twin is known`() {
        val onA = a.track("Song Shared", artist = "Band")
        val onB = b.track("Song Shared", artist = "Band", durationSec = 201.0)
        TrackGrouper().group(listOf(TrackGrouper.Ranked(a.sourceId, listOf(onA)), TrackGrouper.Ranked(b.sourceId, listOf(onB)))).forEach(equivalence::remember)
        val picked = engine.pick(listOf(onB), queue(a.track("Now", artist = "Other")), recentlyPlayed = setOf(onA.id), settings = on)
        assertTrue(picked.isEmpty())
    }

    @Test
    fun `every online source holding a seed is asked, the current song's source first`() = runTest {
        a.track("A next")
        b.track("B next")
        val seedA = a.track("Seed A")
        val seedB = b.track("Seed B")
        val result = recommender.recommend(RecommendationRequest(listOf(seedA, seedB), setOf(seedA.id, seedB.id), 10))
        assertEquals(listOf(seedB.id), b.recommendSeeds.single())
        assertEquals(listOf(seedA.id), a.recommendSeeds.single())
        assertEquals(b.sourceId, result.first().source.sourceId, "the current (last) seed's source leads")
        assertTrue(result.any { it.source.sourceId == a.sourceId })
        assertTrue(result.all { it.source.sourceId != local.sourceId })
    }

    @Test
    fun `a source is asked about a seed it holds a twin of`() = runTest {
        val onA = a.track("Song Shared", artist = "Band")
        val onB = b.track("Song Shared", artist = "Band", durationSec = 201.0)
        b.track("B next")
        TrackGrouper().group(listOf(TrackGrouper.Ranked(a.sourceId, listOf(onA)), TrackGrouper.Ranked(b.sourceId, listOf(onB)))).forEach(equivalence::remember)
        recommender.recommend(RecommendationRequest(listOf(onA), setOf(onA.id), 10))
        assertEquals(listOf(onB.id), b.recommendSeeds.single(), "b relates to its own copy of the seed")
    }

    @Test
    fun `a source holding none of the seeds isn't asked`() = runTest {
        val seed = a.track("Only On A")
        recommender.recommend(RecommendationRequest(listOf(seed), setOf(seed.id), 10))
        assertTrue(b.recommendSeeds.isEmpty())
    }

    @Test
    fun `a failing source leaves the others' suggestions`() = runTest {
        a.recommendBehavior = Behavior.Fail(app.podium.core.common.PodiumError.Network())
        a.track("A next")
        b.track("B next")
        val result = recommender.recommend(RecommendationRequest(listOf(a.track("Seed A"), b.track("Seed B")), emptySet(), 10))
        assertTrue(result.isNotEmpty())
        assertTrue(result.all { it.source.sourceId == b.sourceId })
    }

    @Test
    fun `a disabled source suggests nothing`() = runTest {
        registry.setEnabled(b.sourceId, false)
        b.track("B next")
        val result = recommender.recommend(RecommendationRequest(listOf(a.track("Seed A"), b.track("Seed B")), emptySet(), 10))
        assertFalse(result.any { it.source.sourceId == b.sourceId })
    }

    @Test
    fun `local seeds never get online suggestions`() = runTest {
        b.track("B next")
        val result = recommender.recommend(RecommendationRequest(listOf(track("Local Song", source = "local", key = "l")), emptySet(), 10))
        assertTrue(result.isEmpty())
    }
}
