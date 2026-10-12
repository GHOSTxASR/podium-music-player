package app.podium.feature.nowplaying.line

import kotlin.math.abs

/**
 * Turns the target cursor (from the playback position, or the line read with the Wheel) into the
 * cursor drawn (LYRICS_ARCHITECTURE.md §12.5). While the music plays it is the target, exactly. When
 * the target jumps — a seek, a rewind, reading back or ahead — the drawn cursor travels to it along
 * the composition over a moment, so the pen visibly retraces its own route (and the camera its own
 * diagonal) instead of the artwork being swapped. It always lands exactly on the target, so the
 * settled drawing is a pure function of the playback position.
 *
 * Pure (time is passed in), so it is tested without a screen.
 */
internal class LinePlayhead {
    /** The cursor drawn. */
    var shown: Double = 0.0
        private set

    /** Whether a retrace is under way. */
    var travelling: Boolean = false
        private set

    private var from = 0.0
    private var startMs = 0L
    private var durationMs = 0L
    private var lastTarget = Double.NaN

    /** Starts afresh at [target] (a new song, a new composition): nothing travels from before. */
    fun reset(target: Double) {
        shown = target
        lastTarget = target
        travelling = false
    }

    /**
     * Moves toward [target] at [nowMs]. [jumped]: the caller saw a discontinuity (the position moved
     * unlike the clock, or the Wheel chose another line). A target that moves by more than
     * [JUMP_SECTIONS] at once is treated as a jump too. [reduced] (reduced motion): no travel.
     */
    fun update(target: Double, nowMs: Long, jumped: Boolean, reduced: Boolean): Double {
        if (lastTarget.isNaN()) reset(target)
        val jump = jumped || abs(target - lastTarget) > JUMP_SECTIONS
        lastTarget = target
        if (reduced) {
            travelling = false
            shown = target
            return shown
        }
        if (jump && abs(target - shown) > SETTLED) {
            from = shown
            startMs = nowMs
            durationMs = (MIN_TRAVEL_MS + abs(target - shown) * PER_SECTION_MS).toLong().coerceAtMost(MAX_TRAVEL_MS)
            travelling = true
        }
        if (travelling) {
            val t = ((nowMs - startMs).toFloat() / durationMs).coerceIn(0f, 1f)
            if (t >= 1f) {
                travelling = false
                shown = target
            } else {
                shown = from + (target - from) * easeOut(t)
            }
        } else {
            shown = target
        }
        return shown
    }

    companion object {
        /** A move of the target larger than this many sections in one step is a jump. */
        const val JUMP_SECTIONS = 0.35
        private const val SETTLED = 1e-4
        private const val MIN_TRAVEL_MS = 280.0
        private const val PER_SECTION_MS = 60.0
        private const val MAX_TRAVEL_MS = 900L
    }
}
