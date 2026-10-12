package app.podium.player.service

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import app.podium.player.api.EqualizerPreset
import app.podium.player.api.EqualizerSettings
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/** The equaliser's sound (D-76): measured on sine waves through the processor itself. */
class EqualizerProcessorTest {

    private val format = AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT)

    private fun sine(hz: Double, amplitude: Double = 0.25, seconds: Double = 0.5): ByteBuffer {
        val frames = (format.sampleRate * seconds).toInt()
        val b = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until frames) {
            val v = (amplitude * sin(2 * PI * hz * i / format.sampleRate) * 32767).toInt().toShort()
            b.putShort(v)
            b.putShort(v)
        }
        b.flip()
        return b
    }

    private fun run(settings: EqualizerSettings, input: ByteBuffer): ShortArray {
        val eq = EqualizerProcessor()
        eq.update(settings)
        eq.configure(format)
        eq.flush()
        eq.queueInput(input)
        val out = eq.output.order(ByteOrder.nativeOrder())
        return ShortArray(out.remaining() / 2) { out.getShort() }
    }

    /** RMS in dB of the left channel, past the filters' settling time. */
    private fun levelDb(samples: ShortArray): Double {
        val left = samples.filterIndexed { i, _ -> i % 2 == 0 }.drop(4_000)
        return 20 * log10(sqrt(left.sumOf { (it / 32768.0) * (it / 32768.0) } / left.size))
    }

    private fun bytesOf(b: ByteBuffer): ByteArray = ByteArray(b.remaining()).also { b.duplicate().get(it) }

    @Test
    fun `off, or flat, the audio passes through untouched`() {
        val input = sine(1_000.0)
        val expected = ShortArray(input.remaining() / 2) { input.duplicate().order(ByteOrder.nativeOrder()).getShort(it * 2) }
        assertContentEquals(expected, run(EqualizerSettings(enabled = false).withPreset(EqualizerPreset.ROCK), sine(1_000.0)))
        assertContentEquals(expected, run(EqualizerSettings(enabled = true), sine(1_000.0)))
    }

    @Test
    fun `bass boost raises the bass against the treble by about its curve`() {
        val bass = EqualizerSettings(enabled = true).withPreset(EqualizerPreset.BASS)
        val flat = EqualizerSettings(enabled = false)
        val lowGain = levelDb(run(bass, sine(62.0))) - levelDb(run(flat, sine(62.0)))
        val highGain = levelDb(run(bass, sine(4_000.0))) - levelDb(run(flat, sine(4_000.0)))
        val lift = lowGain - highGain
        assertTrue(lift in 4.0..8.0, "62 Hz lifted over 4 kHz by $lift dB")
    }

    @Test
    fun `a boost never clips, the curve's peak is taken off first`() {
        val loud = EqualizerSettings(enabled = true).withGain(5, 12f)
        val out = run(loud, sine(1_000.0, amplitude = 0.95))
        val peak = out.maxOf { kotlin.math.abs(it.toInt()) }
        assertTrue(peak < 32_767, "peak $peak")
    }
}
