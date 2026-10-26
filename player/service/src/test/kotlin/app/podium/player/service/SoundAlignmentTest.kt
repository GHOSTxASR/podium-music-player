package app.podium.player.service

import app.podium.player.api.AudioPulse
import org.junit.Test
import kotlin.math.exp
import kotlin.random.Random
import kotlin.test.assertEquals

/**
 * Lining a carried copy up with the song by their sound (D-96): loudness envelopes like the meters
 * record, of music with hits at irregular times, the copy's sound placed a little apart.
 */
class SoundAlignmentTest {

    /** Music: a quiet bed and hits (beats, notes) at irregular times, each dying away. */
    private class Music(seed: Int, val level: Float = 1f, val noise: Float = 0f) {
        private val random = Random(seed)
        private val hits = generateSequence(0L) { it + 90 + random.nextLong(320) }.takeWhile { it < 60_000 }.map { it to 0.2f + random.nextFloat() }.toList()
        private val wobble = Random(seed + 1)

        fun at(ms: Long): Float {
            var v = 0.03f
            for ((t, amp) in hits) {
                if (t > ms) break
                if (ms - t < 600) v += amp * exp(-(ms - t) / 90.0).toFloat()
            }
            return v * level + if (noise > 0f) wobble.nextFloat() * noise else 0f
        }
    }

    /** A meter's record: [sound] at song position p, measured from [fromMs] to [toMs]. */
    private class Pulse(private val fromMs: Long, private val toMs: Long, private val sound: (Long) -> Float) : AudioPulse {
        override fun listen(): AutoCloseable = AutoCloseable {}
        override fun bassAt(positionMs: Long): Float = -1f
        override fun levelsAt(positionMs: Long, out: FloatArray): Boolean {
            if (positionMs < fromMs || positionMs > toMs) return false
            val v = sound(positionMs / 10 * 10)
            out[AudioPulse.WHOLE] = v
            out[AudioPulse.LOW] = v
            out[AudioPulse.HIGH] = v
            return true
        }
    }

    private val music = Music(seed = 7)

    private fun measure(offsetMs: Long, copy: Music = music, copyFrom: Long = 0, at: Long = 20_000): SoundAlignment.Result =
        SoundAlignment(
            song = Pulse(0, 60_000) { music.at(it) },
            copy = Pulse(copyFrom, 60_000) { copy.at(it - offsetMs) },
        ).measure(at)

    @Test
    fun `the copy's sound is found a little later`() {
        assertEquals(SoundAlignment.Result.Offset(120), measure(120))
    }

    @Test
    fun `or a little earlier`() {
        assertEquals(SoundAlignment.Result.Offset(-70), measure(-70))
    }

    @Test
    fun `or right where the song is`() {
        assertEquals(SoundAlignment.Result.Offset(0), measure(0))
    }

    @Test
    fun `a copy mastered quieter, with a little noise, is still placed`() {
        assertEquals(SoundAlignment.Result.Offset(240), measure(240, copy = Music(seed = 7, level = 0.4f, noise = 0.004f)))
    }

    @Test
    fun `silence or a steady sound has nothing to go by`() {
        val steady = SoundAlignment(Pulse(0, 60_000) { 0.2f }, Pulse(0, 60_000) { 0.2f }).measure(20_000)
        assertEquals(SoundAlignment.Result.Unsure, steady)
    }

    @Test
    fun `other music isn't taken for the song`() {
        assertEquals(SoundAlignment.Result.Unsure, measure(0, copy = Music(seed = 99)))
    }

    @Test
    fun `nothing is said before both have been measured long enough`() {
        assertEquals(SoundAlignment.Result.NotYet, measure(0, copyFrom = 20_000 - SoundAlignment.NEEDS_MS / 2))
        assertEquals(SoundAlignment.Result.Offset(0), measure(0, copyFrom = 20_000 - SoundAlignment.NEEDS_MS))
    }
}
