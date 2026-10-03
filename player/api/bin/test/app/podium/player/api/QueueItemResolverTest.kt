package app.podium.player.api

import app.podium.core.common.ManualClock
import app.podium.sources.api.FacetResolution
import app.podium.sources.api.HealthOutcome
import app.podium.sources.api.MissReason
import app.podium.sources.api.ResolutionPath
import app.podium.sources.api.SourceHealthMonitor
import app.podium.sources.api.SourceRegistry
import app.podium.sources.api.matching.TrackMatcher
import app.podium.sources.api.resolve.ResolveOutcome
import app.podium.sources.api.resolve.StreamResolver
import app.podium.sources.testing.FakeMusicSource
import app.podium.sources.testing.track
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class QueueItemResolverTest {
    private val clock = ManualClock(5_000_000)
    private val health = SourceHealthMonitor(clock)
    private val registry = SourceRegistry(health)
    private val resolver = StreamResolver(registry, health, TrackMatcher(), clock)
    private val queue = QueueManager()
    private val items = QueueItemResolver(queue, resolver)

    private val song = track("Song", "Artist", source = "primary", album = "Album")

    @Test
    fun `a queue item is resolved once and pinned`() = runTest {
        val source = FakeMusicSource("primary", listOf(song)).also { registry.register(it) }
        queue.setContext(listOf(song), 0, null)
        val uid = queue.state.value.current!!.uid

        assertIs<ResolveOutcome.Resolved>(items.resolve(uid))
        val pinned = assertNotNull(queue.item(uid)?.pinnedMedia)
        repeat(3) { assertIs<ResolveOutcome.Resolved>(items.resolve(uid)) }

        assertEquals(1, source.resolveCalls, "a valid pinned target must not be resolved again")
        assertEquals(pinned, queue.item(uid)?.pinnedMedia)
    }

    @Test
    fun `the pinned selection records a fallback and keeps it for the whole play`() = runTest {
        val primary = FakeMusicSource("primary", listOf(song)).also { registry.register(it) }
        primary.outcomes[song.id] = FacetResolution.Miss(MissReason.NOT_FOUND)
        val copy = track("Song", "Artist", source = "mirror", album = "Album")
        val mirror = FakeMusicSource("mirror", listOf(copy)).also { registry.register(it) }
        queue.setContext(listOf(song), 0, null)
        val uid = queue.state.value.current!!.uid

        items.resolve(uid)
        val item = queue.item(uid)!!
        assertEquals(ResolutionPath.EXACT_FALLBACK, item.selection?.path)
        assertEquals(mirror.sourceId, item.selection?.servedBy)
        assertEquals(song.source.sourceId, item.preferredSource, "the preferred source is remembered")

        primary.outcomes.clear() // the primary is back — but this play stays where it started
        items.resolve(uid)
        assertEquals(mirror.sourceId, queue.item(uid)?.selection?.servedBy)
    }

    @Test
    fun `a mid-track refresh stays on the pinned source`() = runTest {
        val primary = FakeMusicSource("primary", listOf(song)).also { registry.register(it) }
        FakeMusicSource("mirror", listOf(track("Song", "Artist", source = "mirror", album = "Album"))).also { registry.register(it) }
        queue.setContext(listOf(song), 0, null)
        val uid = queue.state.value.current!!.uid
        items.resolve(uid)

        primary.outcomes[song.id] = FacetResolution.Failed(HealthOutcome.SERVER_ERROR)
        assertIs<ResolveOutcome.Failed>(items.refreshPinned(uid))
        assertEquals(primary.sourceId, queue.item(uid)?.selection?.servedBy)
    }
}
