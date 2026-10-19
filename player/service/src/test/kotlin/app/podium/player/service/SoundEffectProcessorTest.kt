package app.podium.player.service

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import app.podium.player.api.SoundEffect
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The sound effects (D-89), measured through the processor itself. */
class SoundEffectProcessorTest {

    private val rate = 44_100
    private val format = AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT)

    /** Stereo 16-bit: [left] and [right] (seconds → -1..1), [seconds] long. */
    private fun signal(seconds: Double, left: (Double) -> Double, right: (Double) -> Double = left): ByteBuffer {
        val frames = (rate * seconds).toInt()
        val b = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until frames) {
            val t = i.toDouble() / rate
            b.putShort((left(t) * 32767).toInt().toShort())
            b.putShort((right(t) * 32767).toInt().toShort())
        }
        b.flip()
        return b
    }

    private fun sine(hz: Double, amplitude: Double = 0.25): (Double) -> Double = { t -> amplitude * sin(2 * PI * hz * t) }

    private fun processor(effect: SoundEffect, f: AudioProcessor.AudioFormat = format) = SoundEffectProcessor().apply {
        update(effect)
        configure(f)
        flush()
    }

    private fun SoundEffectProcessor.feed(input: ByteBuffer): ShortArray {
        queueInput(input)
        val out = output.order(ByteOrder.nativeOrder())
        return ShortArray(out.remaining() / 2) { out.getShort() }
    }

    private fun run(effect: SoundEffect, input: ByteBuffer) = processor(effect).feed(input)

    private fun shorts(b: ByteBuffer) = b.duplicate().order(ByteOrder.nativeOrder()).let { d -> ShortArray(d.remaining() / 2) { d.getShort() } }

    /** RMS in dB of one channel (0 left, 1 right) over frames [from, to). */
    private fun levelDb(s: ShortArray, channel: Int, from: Int = 0, to: Int = s.size / 2): Double {
        var sum = 0.0
        for (i in from until to) {
            val v = s[i * 2 + channel] / 32768.0
            sum += v * v
        }
        return 10 * log10(sum / (to - from) + 1e-12)
    }

    @Test
    fun `none passes the audio through untouched`() {
        val input = signal(0.3, sine(440.0), sine(660.0))
        assertContentEquals(shorts(input), run(SoundEffect.NONE, input))
    }

    @Test
    fun `car stereo lifts the bass against the mids`() {
        val bass = run(SoundEffect.CAR, signal(0.6, sine(60.0)))
        val mid = run(SoundEffect.CAR, signal(0.6, sine(1_000.0)))
        val skip = rate / 5
        val lift = levelDb(bass, 0, skip) - levelDb(mid, 0, skip)
        assertTrue(lift > 4.0, "60 Hz is louder than 1 kHz by $lift dB")
    }

    @Test
    fun `the hall rings on after the music stops`() {
        val burst = signal(0.8, { t -> if (t < 0.15) 0.5 * sin(2 * PI * 330 * t) else 0.0 }, { t -> if (t < 0.15) 0.5 * sin(2 * PI * 495 * t) else 0.0 })
        val hall = run(SoundEffect.HALL, burst)
        val after = (rate * 0.3).toInt()
        val until = (rate * 0.6).toInt()
        assertTrue(levelDb(hall, 0, after, until) > -50, "a tail: ${levelDb(hall, 0, after, until)} dB")
        val dry = run(SoundEffect.NONE, signal(0.8, { t -> if (t < 0.15) 0.5 * sin(2 * PI * 330 * t) else 0.0 }))
        assertTrue(levelDb(dry, 0, after, until) < -100, "nothing without it")
    }

    @Test
    fun `8D carries the music round from ear to ear`() {
        val period = SoundEffectProcessor.Orbit.PERIOD_S
        val out = run(SoundEffect.ORBIT, signal(period, sine(1_000.0)))
        val window = rate / 4
        fun balanceAt(seconds: Double): Double {
            val from = (seconds * rate).toInt() - window / 2
            return levelDb(out, 1, from, from + window) - levelDb(out, 0, from, from + window)
        }
        // A quarter of the way round it is on the right; three quarters, on the left.
        assertTrue(balanceAt(period / 4) > 6, "right at a quarter turn: ${balanceAt(period / 4)} dB")
        assertTrue(balanceAt(period * 3 / 4) < -6, "left at three quarters: ${balanceAt(period * 3 / 4)} dB")
        assertTrue(abs(balanceAt(period / 2)) < 3, "in the middle between: ${balanceAt(period / 2)} dB")
    }

    @Test
    fun `no effect takes full-scale audio past full scale`() {
        val float = AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_FLOAT)
        for (effect in SoundEffect.entries) {
            val fx = processor(effect, float)
            val frames = rate / 2
            val b = ByteBuffer.allocateDirect(frames * 8).order(ByteOrder.nativeOrder())
            for (i in 0 until frames) {
                val v = if ((i / 50) % 2 == 0) 1f else -1f // a full-scale square wave, low and loud
                b.putFloat(v)
                b.putFloat(-v)
            }
            b.flip()
            fx.queueInput(b)
            val out = fx.output.order(ByteOrder.nativeOrder())
            while (out.hasRemaining()) {
                val y = out.getFloat()
                assertTrue(abs(y) <= 1f, "$effect stays within full scale: $y")
            }
        }
    }

    @Test
    fun `changing effect mid-song doesn't click`() {
        val fx = processor(SoundEffect.NONE)
        val tone = sine(440.0)
        val first = fx.feed(signal(0.2, tone))
        fx.update(SoundEffect.HALL)
        val second = fx.feed(signal(0.2, { t -> tone(t + 0.2) }))
        fx.update(SoundEffect.ORBIT)
        val third = fx.feed(signal(0.2, { t -> tone(t + 0.4) }))
        fx.update(SoundEffect.NONE)
        val fourth = fx.feed(signal(0.2, { t -> tone(t + 0.6) }))
        val all = first + second + third + fourth
        var jump = 0
        for (i in 2 until all.size) jump = maxOf(jump, abs(all[i] - all[i - 2]))
        // A 440 Hz tone at a quarter of full scale moves at most ~515 a sample; a click moves thousands.
        assertTrue(jump < 1_500, "largest step $jump")
        // And once settled back to none, the audio is exactly the input again.
        val tail = fourth.copyOfRange(fourth.size - 2_000, fourth.size)
        val expected = shorts(signal(0.2, { t -> tone(t + 0.6) })).let { it.copyOfRange(it.size - 2_000, it.size) }
        assertContentEquals(expected, tail)
    }

    @Test
    fun `mono passes by untouched`() {
        val fx = SoundEffectProcessor()
        fx.update(SoundEffect.ORBIT)
        fx.configure(AudioProcessor.AudioFormat(rate, 1, C.ENCODING_PCM_16BIT))
        assertFalse(fx.isActive)
    }

    @Test
    fun `the soft limit leaves ordinary levels alone`() {
        assertTrue(SoundEffectProcessor.softClip(0.5f) == 0.5f)
        assertTrue(SoundEffectProcessor.softClip(-0.79f) == -0.79f)
        assertTrue(SoundEffectProcessor.softClip(3f) <= 1f && SoundEffectProcessor.softClip(3f) > 0.95f)
    }
}
