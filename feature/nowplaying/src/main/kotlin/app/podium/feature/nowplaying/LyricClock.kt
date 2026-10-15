package app.podium.feature.nowplaying

import kotlin.math.abs
import kotlin.math.exp

/**
 * The lyrics' sense of time, frame by frame (D-82): the playback position as the display shows it.
 *
 * The player's position is right, but not smooth enough to draw from at 120 Hz. It is read at a
 * slightly different moment after each refresh, counts whole milliseconds, and jumps a little
 * whenever the player reports in, every few seconds. A pen that follows it raw moves by uneven
 * steps, which is the stutter.
 *
 * This clock advances by the display's own frame times while the music plays and leans gently onto
 * the reported position. The correction is never more than [MAX_SLEW] of the time passing, so each
 * frame moves the drawing about the same distance and the music never runs backwards. It snaps to
 * the reported position on a seek, a pause, buffering, or anything else that time alone doesn't
 * explain, and says so ([jumped]).
 *
 * Pure (times are passed in), so it is tested without a screen.
 */
internal class LyricClock {

    /** The position to draw, in (fractional) milliseconds. */
    var positionMs: Double = 0.0
        private set

    /** True for the frame on which the clock took the reported position outright (a seek, the first frame). */
    var jumped: Boolean = false
        private set

    private var lastFrameNanos = NONE

    /** Starts afresh: the next update takes the reported position as it is. */
    fun reset() {
        lastFrameNanos = NONE
    }

    /**
     * The position for the frame at [frameNanos], given the player's [reportedMs] now. [advancing]:
     * the music is actually playing (not paused, not buffering).
     */
    fun update(frameNanos: Long, reportedMs: Long, advancing: Boolean): Double {
        val reported = reportedMs.toDouble()
        if (lastFrameNanos == NONE) {
            lastFrameNanos = frameNanos
            positionMs = reported
            jumped = true
            return positionMs
        }
        val dt = ((frameNanos - lastFrameNanos) / 1e6).coerceIn(0.0, MAX_FRAME_MS)
        lastFrameNanos = frameNanos
        if (!advancing) {
            // Still: exactly where the music is.
            jumped = abs(reported - positionMs) > SEEK_MS
            positionMs = reported
            return positionMs
        }
        val predicted = positionMs + dt
        val error = reported - predicted
        if (abs(error) > SEEK_MS) {
            jumped = true
            positionMs = reported
            return positionMs
        }
        jumped = false
        val limit = dt * MAX_SLEW
        positionMs = predicted + (error * (1 - exp(-dt / LEAN_MS))).coerceIn(-limit, limit)
        return positionMs
    }

    companion object {
        /** Further than this from where time alone puts the music is a seek, not drift. */
        const val SEEK_MS = 300.0

        /** How fast the clock leans onto the reported position (time constant). */
        const val LEAN_MS = 400.0

        /** The most the clock runs faster or slower than real time while leaning (8 %). */
        const val MAX_SLEW = 0.08

        /** A longer gap between frames (the screen was away) counts as this much; the seek check does the rest. */
        const val MAX_FRAME_MS = 1_000.0

        private const val NONE = Long.MIN_VALUE
    }
}
