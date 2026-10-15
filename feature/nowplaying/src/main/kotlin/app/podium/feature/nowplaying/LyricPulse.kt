package app.podium.feature.nowplaying

import kotlin.math.exp

/**
 * How far the lyrics swell with the bass (D-82): 0 at rest, 1 at a strong hit.
 *
 * - It moves with the bass's hits, not its level: what pushes is how far the bass measured at the
 *   playback position ([AudioPulse]) rises above its own average of the last moments ([AVERAGE_MS]).
 *   A kick pushes; a held bass note pushes once as it starts, then lets the words settle.
 * - Each hit is judged against the song's own recent hits. A reference rises quickly with the
 *   strongest and falls slowly, so a quiet master moves as much as a loud one, and only the stronger
 *   hits push: below [THRESHOLD] of the reference nothing moves.
 * - The swell follows a critically damped spring, like a speaker cone, toward a push that itself
 *   eases in over [EASE_MS]. It goes out quickly with a kick ([OUT_HZ]), settles back more softly
 *   ([BACK_HZ]) and never jumps: its speed, and how fast that changes, stay continuous whatever the
 *   measurements do. The callers read the bass [LEAD_MS] ahead (the player has decoded it already)
 *   so the swell peaks with the kick rather than after it.
 * - Nothing measured (another app plays, a seek not yet decoded): the swell settles to rest.
 *
 * Pure (time is passed in), so it is tested without a screen.
 */
internal class PulseFollower {

    var value: Float = 0f
        private set

    private var velocity = 0f
    private var push = 0f
    private var average = 0f
    private var reference = 0f

    /** At rest, and the reference forgotten (a new song). */
    fun reset() {
        value = 0f
        velocity = 0f
        push = 0f
        average = 0f
        reference = 0f
    }

    /** Whether the swell has settled at rest. */
    val atRest: Boolean get() = value < REST && kotlin.math.abs(velocity) < REST && push < REST

    /** Moves the swell on by [dtMs] toward what [level] (a bass RMS, or negative for none) asks. */
    fun update(level: Float, dtMs: Float): Float {
        val dt = dtMs.coerceIn(0f, MAX_STEP_MS)
        var target = 0f
        if (level >= 0f) {
            average += (level - average) * (1f - exp(-dt / AVERAGE_MS))
            val hit = (level - average).coerceAtLeast(0f)
            reference = if (hit > reference) {
                reference + (hit - reference) * (1f - exp(-dt / RISE_MS))
            } else {
                reference * exp(-dt / FALL_MS)
            }
            val r = hit / maxOf(reference, FLOOR)
            val drive = ((r - THRESHOLD) / (1f - THRESHOLD)).coerceIn(0f, 1f)
            target = drive * drive
        }
        // The push eases in, and a critically damped spring follows it, in small steps (the same at any frame rate).
        var left = dt
        while (left > 0f) {
            val step = minOf(left, SUBSTEP_MS)
            push += (target - push) * (1f - exp(-step / EASE_MS))
            val h = step / 1000f
            val w = (if (push > value) OUT_HZ else BACK_HZ) * TWO_PI
            velocity += (w * w * (push - value) - 2f * w * velocity) * h
            value += velocity * h
            left -= SUBSTEP_MS
        }
        if (value < 0f) {
            value = 0f
            if (velocity < 0f) velocity = 0f
        }
        if (value > 1f) {
            value = 1f
            if (velocity > 0f) velocity = 0f
        }
        return value
    }

    companion object {
        /** Below this share of the song's recent strongest hit, nothing moves. */
        const val THRESHOLD = 0.35f

        /** A rise of the bass smaller than this (RMS, about -40 dBFS) never counts as a strong hit, however quiet the song. */
        const val FLOOR = 0.01f

        /** The bass's own recent average, which a hit has to rise above. */
        const val AVERAGE_MS = 220f

        /** The reference rises with a hit in about this long, and lets go over this long. */
        const val RISE_MS = 60f
        const val FALL_MS = 3_000f

        /** The spring's speed going out with a hit and settling back, and how the push eases in. */
        const val OUT_HZ = 6f
        const val BACK_HZ = 2.4f
        const val EASE_MS = 20f

        /** How far ahead of the playback position the callers read the bass, so the swell lands on the beat. */
        const val LEAD_MS = 50L

        const val SUBSTEP_MS = 4f
        const val MAX_STEP_MS = 100f
        private const val REST = 0.002f
        private const val TWO_PI = (2 * Math.PI).toFloat()
    }
}
