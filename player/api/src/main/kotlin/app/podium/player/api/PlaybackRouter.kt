package app.podium.player.api

import app.podium.core.model.PlaybackRoute
import app.podium.sources.api.PlaybackTarget

/** Plays one kind of [PlaybackTarget] (PLAYBACK_TARGETS.md §5). */
interface PlaybackEngine {
    val route: PlaybackRoute
    val controls: ControlSet
    suspend fun prepare(target: PlaybackTarget, startPositionMs: Long, playWhenReady: Boolean)
    fun play()
    fun pause()
    fun stop()
    fun seekTo(positionMs: Long)
    fun positionMs(): Long
}

/** Thrown when a target needs an engine that isn't installed in this build. */
class UnsupportedRouteException(val route: PlaybackRoute) : IllegalStateException("No engine for $route")

/**
 * Chooses the engine for each resolved target and performs handoffs.
 *
 * Rules (PLAYBACK_TARGETS.md §5.1): switching between engines is always a hard cut — the outgoing
 * engine is stopped before the incoming one prepares, so audio from two owners never overlaps.
 * Within one engine, transitions (e.g. gapless) are that engine's business.
 */
class PlaybackRouter(engines: List<PlaybackEngine>) {
    private val engines: Map<PlaybackRoute, PlaybackEngine> = engines.associateBy { it.route }

    var active: PlaybackEngine? = null
        private set

    fun routeOf(target: PlaybackTarget): PlaybackRoute = when (target) {
        is PlaybackTarget.DirectStream -> PlaybackRoute.DIRECT
        is PlaybackTarget.RemoteProvider -> PlaybackRoute.REMOTE
        is PlaybackTarget.Embedded -> PlaybackRoute.EMBEDDED
    }

    fun engineFor(target: PlaybackTarget): PlaybackEngine =
        engines[routeOf(target)] ?: throw UnsupportedRouteException(routeOf(target))

    fun supports(target: PlaybackTarget): Boolean = engines.containsKey(routeOf(target))

    /** Activate [target]: hard-cut from a different engine, then prepare. */
    suspend fun activate(target: PlaybackTarget, startPositionMs: Long = 0L, playWhenReady: Boolean = true): PlaybackEngine {
        val next = engineFor(target)
        val previous = active
        if (previous != null && previous !== next) previous.stop()
        active = next
        next.prepare(target, startPositionMs, playWhenReady)
        return next
    }

    /**
     * Hand playback to the engine for [route] without preparing a target there (an engine that keeps
     * its own queue, like Podium's own player): the other engine is stopped first, as always.
     */
    fun handOver(route: PlaybackRoute): PlaybackEngine {
        val next = engines[route] ?: throw UnsupportedRouteException(route)
        val previous = active
        if (previous != null && previous !== next) previous.stop()
        active = next
        return next
    }

    val controls: ControlSet get() = active?.controls ?: ControlSet()
}
