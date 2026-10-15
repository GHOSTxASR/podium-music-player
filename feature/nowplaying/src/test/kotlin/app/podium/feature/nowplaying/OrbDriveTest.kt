package app.podium.feature.nowplaying

import app.podium.core.designsystem.orb.OrbFrame
import app.podium.player.api.AudioPulse
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How the music moves the orb (D-83): calm values that follow the song, never jump, settle when the
 * music stops, and stop the orb's own time while paused.
 */
class OrbDriveTest {

    /** A song: a bass kick every 500 ms over a steady body, with bright hats on the off-beat. */
    private fun levels(ms: Double, out: FloatArray): FloatArray {
        val kick = exp(-(ms % 500.0) / 90.0).toFloat()
        val hat = exp(-((ms + 250.0) % 500.0) / 40.0).toFloat()
        out[AudioPulse.WHOLE] = 0.12f + 0.2f * kick + 0.04f * hat
        out[AudioPulse.LOW] = 0.04f + 0.3f * kick
        out[AudioPulse.HIGH] = 0.004f + 0.03f * hat
        return out
    }

    private fun run(seconds: Double, frameMs: Float = 1000f / 90, advancing: Boolean = true, silent: Boolean = false, drive: OrbDrive = OrbDrive()): List<OrbFrame> {
        val buf = FloatArray(3)
        val frames = ArrayList<OrbFrame>()
        var t = 0.0
        while (t < seconds * 1000) {
            val f = OrbFrame()
            drive.update(if (silent) null else levels(t - t % 10.0, buf), frameMs, advancing, f)
            frames += f
            t += frameMs
        }
        return frames
    }

    @Test
    fun `the music moves every value a little, within bounds`() {
        val frames = run(6.0).drop(300)
        for (f in frames) {
            for (v in listOf(f.energy, f.low, f.high, f.glint)) assertTrue(v in 0f..1f, "in 0-1: $v")
        }
        assertTrue(frames.maxOf { it.energy } > 0.5f, "energy follows the song")
        assertTrue(frames.maxOf { it.low } > 0.3f, "the kicks swell it")
        assertTrue(frames.maxOf { it.high } > 0.3f, "the hats ripple it")
        assertTrue(frames.maxOf { it.glint } > 0.2f, "the hits catch the light")
    }

    @Test
    fun `no value ever jumps`() {
        val frames = run(5.0, frameMs = 1000f / 120)
        for (pick in listOf<(OrbFrame) -> Float>({ it.energy }, { it.low }, { it.high }, { it.glint })) {
            var worst = 0f
            for (i in 1 until frames.size) worst = maxOf(worst, abs(pick(frames[i]) - pick(frames[i - 1])))
            assertTrue(worst < 0.2f, "largest step in a frame: $worst")
        }
    }

    @Test
    fun `paused, the orb's time stops and everything settles`() {
        val drive = OrbDrive()
        val playing = run(3.0, drive = drive)
        val paused = run(3.0, advancing = false, drive = drive)
        assertEquals(playing.last().time, paused.last().time, "time stood still")
        assertEquals(playing.last().rotation, paused.last().rotation)
        val end = paused.last()
        assertTrue(end.energy < 0.01f && end.low < 0.01f && end.high < 0.01f && end.glint < 0.01f, "settled: ${end.energy} ${end.low} ${end.high} ${end.glint}")
        assertTrue(drive.atRest)
    }

    @Test
    fun `nothing measured settles to rest while the orb still drifts slowly`() {
        val frames = run(4.0, silent = true)
        val end = frames.last()
        assertEquals(0f, end.energy)
        assertTrue(end.time > 1f, "it lives at its resting pace: ${end.time}")
    }
}
