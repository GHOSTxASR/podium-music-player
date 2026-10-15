package app.podium.player.service

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bass meter the lyrics move with (D-82), on synthetic audio through the processor itself: it
 * changes nothing, hears the low end and not the top, and files each reading under the song position
 * its audio belongs to — across seeks.
 */
@RunWith(AndroidJUnit4::class)
class BassMeterTest {

    private val rate = 44_100

    /** Stereo audio [seconds] long: [hz] sine at [amplitude] during [onMs] (or throughout). */
    private fun tone(seconds: Double, hz: Double, amplitude: Double, onMs: LongRange? = null, float: Boolean = false): ByteBuffer {
        val frames = (rate * seconds).toInt()
        val b = ByteBuffer.allocateDirect(frames * 2 * if (float) 4 else 2).order(ByteOrder.nativeOrder())
        for (i in 0 until frames) {
            val ms = i * 1000L / rate
            val v = if (onMs == null || ms in onMs) amplitude * sin(2 * PI * hz * i / rate) else 0.0
            repeat(2) { if (float) b.putFloat(v.toFloat()) else b.putShort((v * 32767).toInt().toShort()) }
        }
        b.flip()
        return b
    }

    private fun meter(offsetUs: Long = 0, encoding: Int = C.ENCODING_PCM_16BIT): BassMeter = BassMeter().also {
        it.configure(AudioProcessor.AudioFormat(rate, 2, encoding))
        it.flush(AudioProcessor.StreamMetadata(offsetUs))
    }

    /** Feeds [input] in chunks the size the sink sends, draining the output each time. */
    private fun feed(m: BassMeter, input: ByteBuffer): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val chunk = 4096
        while (input.hasRemaining()) {
            val n = minOf(chunk, input.remaining())
            val piece = input.slice().limit(n) as ByteBuffer
            input.position(input.position() + n)
            m.queueInput(piece.order(ByteOrder.nativeOrder()))
            val o = m.output
            val bytes = ByteArray(o.remaining())
            o.get(bytes)
            out.write(bytes)
        }
        return out.toByteArray()
    }

    @Test
    fun `the audio passes through untouched`() {
        val input = tone(0.5, 55.0, 0.5)
        val bytes = ByteArray(input.remaining()).also { input.duplicate().get(it) }
        assertContentEquals(bytes, feed(meter(), input))
    }

    @Test
    fun `a kick is heard at its place in the song, and a cymbal is not`() {
        // The stream starts 42 s into the song (as after a seek there).
        val m = meter(offsetUs = 42_000_000)
        feed(m, tone(2.0, 55.0, 0.5, onMs = 500L..650L))
        val during = m.bassAt(42_560)
        val before = m.bassAt(42_300)
        val after = m.bassAt(43_200)
        assertTrue(during > 0.2f, "the kick: $during")
        assertTrue(before in 0f..0.01f && after in 0f..0.01f, "silence around it: $before, $after")
        // A 4 kHz tone is filtered out.
        val high = meter()
        feed(high, tone(1.0, 4_000.0, 0.5))
        assertTrue(high.bassAt(500) in 0f..0.01f, "no bass in a cymbal: ${high.bassAt(500)}")
    }

    @Test
    fun `nothing is claimed where nothing was measured`() {
        val m = meter(offsetUs = 10_000_000)
        assertEquals(-1f, m.bassAt(10_000), "before any audio")
        feed(m, tone(1.0, 55.0, 0.5))
        assertTrue(m.bassAt(10_500) > 0f)
        assertEquals(-1f, m.bassAt(9_000), "before the stream")
        assertEquals(-1f, m.bassAt(12_000), "beyond what was decoded")
    }

    @Test
    fun `a flush starts afresh at the new place`() {
        val m = meter(offsetUs = 0)
        feed(m, tone(1.0, 55.0, 0.5))
        assertTrue(m.bassAt(500) > 0.2f)
        // A seek to 90 s: the old readings are gone; the new ones are filed from 90 s.
        m.flush(AudioProcessor.StreamMetadata(90_000_000))
        assertEquals(-1f, m.bassAt(500))
        feed(m, tone(0.5, 55.0, 0.5, onMs = 200L..400L))
        assertTrue(m.bassAt(90_300) > 0.2f)
        assertTrue(m.bassAt(90_100) in 0f..0.01f)
    }

    @Test
    fun `float audio is measured alike`() {
        val m = meter(encoding = C.ENCODING_PCM_FLOAT)
        feed(m, tone(1.0, 55.0, 0.5, float = true))
        val s16 = meter()
        feed(s16, tone(1.0, 55.0, 0.5))
        assertEquals(s16.bassAt(600), m.bassAt(600), 0.01f)
    }

    @Test
    fun `audio it can't read passes by unmeasured`() {
        val m = BassMeter()
        val format = m.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_24BIT))
        assertEquals(AudioProcessor.AudioFormat.NOT_SET, format)
        assertTrue(!m.isActive)
        assertEquals(-1f, m.bassAt(0))
    }
}
