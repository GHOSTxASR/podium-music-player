package app.podium.feature.nowplaying

import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The lyrics' frame clock (D-82): smooth from frame to frame where the player's position is not, and
 * still exactly where the music is after a seek, a pause or a stall.
 */
class LyricClockTest {

    private val frameNanos = 11_111_111L // 90 Hz, as the phone ran the lyrics

    /**
     * The player's position as the phone reported it: read a little after each refresh (0–3 ms late),
     * in whole milliseconds, corrected by up to [correction] ms every three seconds.
     */
    private fun reported(trueMs: Double, random: Random, correction: Double, frame: Int): Long {
        val late = random.nextDouble(0.0, 3.0)
        val drift = if ((frame * frameNanos / 1_000_000) % 3_000 < 1_500) 0.0 else correction
        return (trueMs + late + drift).toLong()
    }

    @Test
    fun `each frame moves the drawing by the frame's own time, whatever the reported position does`() {
        val clock = LyricClock()
        val random = Random(7)
        var truth = 20_000.0
        var last = Double.NaN
        var worstStep = 0.0
        var worstLag = 0.0
        var rawWorst = 0.0
        var lastRaw = -1L
        for (frame in 0 until 2_000) {
            val raw = reported(truth, random, correction = 40.0, frame)
            val shown = clock.update(frame * frameNanos, raw, advancing = true)
            if (frame > 0) {
                val step = shown - last
                assertTrue(step > 0, "never backwards (frame $frame: $step)")
                if (frame > 200) worstStep = maxOf(worstStep, abs(step - frameNanos / 1e6))
                rawWorst = maxOf(rawWorst, abs((raw - lastRaw) - frameNanos / 1e6))
            }
            if (frame > 200) worstLag = maxOf(worstLag, abs(shown - truth))
            last = shown
            lastRaw = raw
            truth += frameNanos / 1e6
        }
        assertTrue(rawWorst > 30.0, "the raw position takes uneven steps (worst $rawWorst ms)")
        // At most the clock's slew: 8 % of a frame.
        assertTrue(worstStep <= frameNanos / 1e6 * LyricClock.MAX_SLEW + 1e-6, "steps stay even (worst ${"%.3f".format(worstStep)} ms off a frame)")
        assertTrue(worstLag < 60.0, "stays with the music (worst ${"%.1f".format(worstLag)} ms)")
    }

    @Test
    fun `a seek is taken at once, and said`() {
        val clock = LyricClock()
        clock.update(0, 10_000, advancing = true)
        clock.update(frameNanos, 10_011, advancing = true)
        assertFalse(clock.jumped)
        val shown = clock.update(2 * frameNanos, 75_000, advancing = true)
        assertEquals(75_000.0, shown)
        assertTrue(clock.jumped)
        clock.update(3 * frameNanos, 75_011, advancing = true)
        assertFalse(clock.jumped, "only the frame of the seek")
        // Back again, the same way.
        assertEquals(5_000.0, clock.update(4 * frameNanos, 5_000, advancing = true))
        assertTrue(clock.jumped)
    }

    @Test
    fun `paused or buffering, it shows exactly where the music is, and resumes from there without a jump`() {
        val clock = LyricClock()
        var t = 0L
        var position = 30_000.0
        repeat(50) {
            clock.update(t, position.toLong(), advancing = true)
            t += frameNanos
            position += frameNanos / 1e6
        }
        val paused = position.toLong()
        repeat(90) {
            assertEquals(paused.toDouble(), clock.update(t, paused, advancing = false), "held still while paused")
            t += frameNanos
        }
        // Playing again: the first frame moves on by one frame's time from where it was, no more.
        val first = clock.update(t, paused + 11, advancing = true)
        assertTrue(first - paused in 0.0..(frameNanos / 1e6 * (1 + LyricClock.MAX_SLEW) + 0.5), "resumes smoothly: ${first - paused}")
        assertFalse(clock.jumped)
    }

    @Test
    fun `the same reports give the same positions`() {
        fun run(): List<Double> {
            val clock = LyricClock()
            val random = Random(3)
            var truth = 0.0
            return List(500) { frame ->
                clock.update(frame * frameNanos, reported(truth, random, 25.0, frame), advancing = true).also { truth += frameNanos / 1e6 }
            }
        }
        assertEquals(run(), run())
    }

    @Test
    fun `after a reset the next report is taken as it is`() {
        val clock = LyricClock()
        clock.update(0, 1_000, advancing = true)
        clock.update(frameNanos, 1_011, advancing = true)
        clock.reset()
        assertEquals(1_200.0, clock.update(2 * frameNanos, 1_200, advancing = true))
        assertTrue(clock.jumped)
    }
}
