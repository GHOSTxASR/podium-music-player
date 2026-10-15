package app.podium.feature.nowplaying

import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The lyrics' bass swell (D-82): out with each hit and softly back, never a jump, still for silence,
 * a held note or no measurement, and the same at any frame rate.
 */
class PulseFollowerTest {

    /** A kick every [everyMs] (a fast rise, then its low end dying away over ~250 ms) over a quiet bass line, as 10 ms RMS blocks. */
    private fun kicks(timeMs: Double, everyMs: Double = 500.0, peak: Float = 0.3f, bed: Float = 0.04f): Float {
        val since = timeMs % everyMs
        return bed + peak * exp(-since / 90.0).toFloat()
    }

    private fun run(frameMs: Float, seconds: Double, level: (Double) -> Float): List<Float> {
        val f = PulseFollower()
        val out = ArrayList<Float>()
        var t = 0.0
        while (t < seconds * 1000) {
            // The meter's blocks are 10 ms: the level holds for each.
            out += f.update(level(t - t % 10.0), frameMs)
            t += frameMs
        }
        return out
    }

    @Test
    fun `each kick swells the words out and they settle back before the next`() {
        val frame = 1000f / 90
        val values = run(frame, 6.0) { kicks(it) }
        // Look at the last few beats (the reference has learnt the song by then).
        val beats = (3_000 / 500 until 6_000 / 500).map { b ->
            val from = (b * 500 / frame).toInt()
            values.subList(from, minOf(values.size, from + (500 / frame).toInt()))
        }
        for (beat in beats) {
            val peak = beat.max()
            val peakAt = beat.indexOf(peak) * frame
            assertTrue(peak in 0.35f..1f, "a kick pushes (peak $peak)")
            assertTrue(peakAt < 160, "soon after the kick (${peakAt} ms)")
            assertTrue(beat.last() < peak * 0.3f, "settled back before the next kick (${beat.last()} of $peak)")
        }
    }

    @Test
    fun `it never jumps - its speed changes smoothly`() {
        val frame = 1000f / 120
        val values = run(frame, 4.0) { kicks(it, everyMs = 430.0, peak = 0.5f) }
        var worstStep = 0f
        var worstChange = 0f
        for (i in 2 until values.size) {
            val step = values[i] - values[i - 1]
            val before = values[i - 1] - values[i - 2]
            worstStep = maxOf(worstStep, abs(step))
            worstChange = maxOf(worstChange, abs(step - before))
        }
        assertTrue(worstStep < 0.1f, "moves at most a tenth of its range in a frame ($worstStep)")
        assertTrue(worstChange < 0.03f, "its speed never changes abruptly ($worstChange)")
    }

    @Test
    fun `silence, a held bass note and no measurement leave the words still`() {
        assertTrue(run(11f, 3.0) { 0f }.all { it == 0f }, "silence")
        assertTrue(run(11f, 3.0) { -1f }.all { it == 0f }, "nothing measured")
        // A held note pushes once as it starts, then settles.
        val held = run(11f, 3.0) { 0.25f }
        assertTrue(held.takeLast(100).all { it < 0.02f }, "a held note settles: ${held.takeLast(100).max()}")
        // The music stopping mid-swell: back to rest, smoothly.
        val f = PulseFollower()
        repeat(40) { f.update(kicks(it * 11.0, peak = 0.6f), 11f) }
        repeat(120) { f.update(-1f, 11f) }
        assertTrue(f.atRest, "at rest: ${f.value}")
    }

    @Test
    fun `quiet and loud masters move alike`() {
        val loud = run(11f, 5.0) { kicks(it, peak = 0.4f, bed = 0.05f) }.takeLast(200).max()
        val quiet = run(11f, 5.0) { kicks(it, peak = 0.08f, bed = 0.01f) }.takeLast(200).max()
        assertTrue(abs(loud - quiet) < 0.1f, "loud $loud, quiet $quiet")
    }

    @Test
    fun `the same at 60, 90 and 120 frames a second`() {
        val peaks = listOf(60f, 90f, 120f).map { hz -> run(1000f / hz, 5.0) { kicks(it) }.takeLast((1500 * hz / 1000).toInt()).max() }
        for (p in peaks) assertEquals(peaks[1], p, 0.08f, "peaks $peaks")
    }
}
