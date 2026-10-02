package app.podium.player.api

import app.podium.core.common.PodiumError
import app.podium.core.model.PlaybackRoute
import app.podium.core.model.SourceId
import app.podium.core.model.TrackId
import app.podium.sources.api.ControlsOwner
import app.podium.sources.api.EmbedConstraints
import app.podium.sources.api.PlayableMedia
import app.podium.sources.api.PlaybackTarget
import app.podium.sources.api.QueueOwnership
import app.podium.sources.api.RemotePolicy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame

class PlaybackRouterTest {
    private val events = mutableListOf<String>()

    private inner class FakeEngine(override val route: PlaybackRoute, override val controls: ControlSet = ControlSet()) :
        PlaybackEngine {
        override suspend fun prepare(target: PlaybackTarget, startPositionMs: Long, playWhenReady: Boolean) {
            events += "$route.prepare"
        }
        override fun play() { events += "$route.play" }
        override fun pause() { events += "$route.pause" }
        override fun stop() { events += "$route.stop" }
        override fun seekTo(positionMs: Long) {}
        override fun positionMs() = 0L
    }

    private val id = TrackId.of(SourceId("s"), "1")
    private val direct = PlaybackTarget.DirectStream(id, PlayableMedia("file:///a", sourceId = SourceId("s"), cacheKey = "a"))
    private val remote = PlaybackTarget.RemoteProvider(
        id, "jukebox", "ref",
        RemotePolicy(QueueOwnership.PODIUM, mixesWithOtherSources = false, allowsTransitions = false, systemControlsOwner = ControlsOwner.PODIUM),
    )
    private val embedded = PlaybackTarget.Embedded(id, "embed", EmbedConstraints())

    @Test
    fun `each target kind maps to its own engine`() {
        val router = PlaybackRouter(listOf(FakeEngine(PlaybackRoute.DIRECT), FakeEngine(PlaybackRoute.REMOTE)))
        assertEquals(PlaybackRoute.DIRECT, router.engineFor(direct).route)
        assertEquals(PlaybackRoute.REMOTE, router.engineFor(remote).route)
        assertFalse(router.supports(embedded))
        assertFailsWith<UnsupportedRouteException> { router.engineFor(embedded) }
    }

    @Test
    fun `switching engines is a hard cut - the old engine stops before the new one prepares`() = runTest {
        val router = PlaybackRouter(listOf(FakeEngine(PlaybackRoute.DIRECT), FakeEngine(PlaybackRoute.REMOTE)))
        router.activate(direct)
        router.activate(remote)
        assertEquals(listOf("DIRECT.prepare", "DIRECT.stop", "REMOTE.prepare"), events)
    }

    @Test
    fun `staying on one engine does not stop it`() = runTest {
        val engine = FakeEngine(PlaybackRoute.DIRECT)
        val router = PlaybackRouter(listOf(engine))
        router.activate(direct)
        router.activate(direct)
        assertEquals(listOf("DIRECT.prepare", "DIRECT.prepare"), events)
        assertSame(engine, router.active)
    }

    @Test
    fun `controls come from the active engine`() = runTest {
        val limited = ControlSet(seek = false, shuffle = false, repeat = false)
        val router = PlaybackRouter(listOf(FakeEngine(PlaybackRoute.DIRECT), FakeEngine(PlaybackRoute.REMOTE, limited)))
        router.activate(remote)
        assertEquals(limited, router.controls)
    }
}

class PlaybackStatusMapperTest {
    private fun facts(
        phase: EnginePhase = EnginePhase.READY,
        playWhenReady: Boolean = true,
        isPlaying: Boolean = true,
        hasItems: Boolean = true,
        wasReady: Boolean = true,
        suppressed: Boolean = false,
        error: PodiumError? = null,
    ) = PlayerFacts(phase, playWhenReady, isPlaying, hasItems, wasReady, suppressed, error)

    @Test
    fun `status is a single coherent value`() {
        assertEquals(PlaybackStatus.Idle, PlaybackStatusMapper.map(facts(hasItems = false)))
        assertEquals(PlaybackStatus.Loading, PlaybackStatusMapper.map(facts(EnginePhase.BUFFERING, isPlaying = false, wasReady = false)))
        assertEquals(PlaybackStatus.Buffering, PlaybackStatusMapper.map(facts(EnginePhase.BUFFERING, isPlaying = false)))
        assertEquals(PlaybackStatus.Playing, PlaybackStatusMapper.map(facts()))
        assertEquals(PlaybackStatus.Paused(PauseReason.USER), PlaybackStatusMapper.map(facts(playWhenReady = false, isPlaying = false)))
        assertEquals(PlaybackStatus.Paused(PauseReason.FOCUS_LOSS), PlaybackStatusMapper.map(facts(isPlaying = false, suppressed = true)))
        assertEquals(PlaybackStatus.Ended, PlaybackStatusMapper.map(facts(EnginePhase.ENDED, isPlaying = false)))
        assertIs<PlaybackStatus.Error>(PlaybackStatusMapper.map(facts(error = PodiumError.InvalidMedia())))
    }
}
