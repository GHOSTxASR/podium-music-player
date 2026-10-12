@file:OptIn(UnstableApi::class)

package app.podium.player.service

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import app.podium.player.api.EqualizerSettings
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Settings ▸ Equaliser (D-76) in the player's own audio path: one peaking filter per band, an
 * octave wide, in the decoded audio before it reaches Android's mixer. It's the same on every
 * phone (no reliance on the phone's own audio effects) and on every song, local or online.
 *
 * - Off, or flat, it passes the audio through untouched, sample for sample.
 * - Boosting a band lowers everything first by the curve's highest point, so a boost never clips.
 * - Changes apply at once, while a song plays.
 *
 * 16-bit and float audio; anything else passes by untouched (it isn't asked for).
 */
class EqualizerProcessor : BaseAudioProcessor() {

    @Volatile private var settings: EqualizerSettings = EqualizerSettings()
    @Volatile private var filters: Filters? = null
    private var sampleRate = 0
    private var channels = 0
    private var encoding = C.ENCODING_INVALID

    /** Called from any thread; the next buffer is shaped by it. */
    fun update(settings: EqualizerSettings) {
        if (settings == this.settings) return
        this.settings = settings
        filters = if (sampleRate > 0) Filters.of(settings, sampleRate, channels, previous = filters) else null
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            return AudioProcessor.AudioFormat.NOT_SET // inactive: passes by untouched
        }
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        encoding = inputAudioFormat.encoding
        filters = Filters.of(settings, sampleRate, channels, previous = null)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        val out = replaceOutputBuffer(bytes)
        val shape = filters?.takeIf { settings.isAudible }
        if (shape == null) {
            out.put(inputBuffer)
        } else {
            val input = inputBuffer.order(ByteOrder.nativeOrder())
            if (encoding == C.ENCODING_PCM_16BIT) {
                while (input.remaining() >= 2 * channels) {
                    for (ch in 0 until channels) {
                        val y = shape.process(ch, input.getShort() / 32768f)
                        out.putShort((y * 32767f).coerceIn(-32768f, 32767f).toInt().toShort())
                    }
                }
            } else {
                while (input.remaining() >= 4 * channels) {
                    for (ch in 0 until channels) out.putFloat(shape.process(ch, input.getFloat()).coerceIn(-1f, 1f))
                }
            }
            // A partial frame (never sent by the sink, but never lost either).
            while (input.hasRemaining()) out.put(input.get())
        }
        out.flip()
    }

    override fun onFlush() {
        filters?.clearState()
    }

    override fun onReset() {
        filters = null
        sampleRate = 0
        channels = 0
        encoding = C.ENCODING_INVALID
    }

    /** The bands' filters for one sample rate, with each channel's memory, and the headroom. */
    internal class Filters private constructor(
        private val b0: FloatArray,
        private val b1: FloatArray,
        private val b2: FloatArray,
        private val a1: FloatArray,
        private val a2: FloatArray,
        /** Linear gain applied first, so the curve's peak lands at 0 dB. */
        val preamp: Float,
        channels: Int,
        previous: Filters?,
    ) {
        private val bands: Int = b0.size
        // Transposed direct form II memory: two values per band per channel.
        private val z1: FloatArray = previous?.z1?.takeIf { it.size == bands * channels } ?: FloatArray(bands * channels)
        private val z2: FloatArray = previous?.z2?.takeIf { it.size == bands * channels } ?: FloatArray(bands * channels)

        fun process(channel: Int, sample: Float): Float {
            var x = sample * preamp
            val base = channel * bands
            for (b in 0 until bands) {
                val i = base + b
                val y = b0[b] * x + z1[i]
                z1[i] = b1[b] * x - a1[b] * y + z2[i]
                z2[i] = b2[b] * x - a2[b] * y
                x = y
            }
            return x
        }

        fun clearState() {
            z1.fill(0f)
            z2.fill(0f)
        }

        companion object {
            /** An octave: Q = √2 for a peaking filter. */
            private val Q = sqrt(2.0)

            fun of(settings: EqualizerSettings, sampleRate: Int, channels: Int, previous: Filters?): Filters {
                val active = EqualizerSettings.BANDS_HZ.indices.filter {
                    settings.gainsDb[it] != 0f && EqualizerSettings.BANDS_HZ[it] < sampleRate * 0.45
                }
                val coefficients = active.map { peaking(EqualizerSettings.BANDS_HZ[it].toDouble(), settings.gainsDb[it].toDouble(), sampleRate.toDouble()) }
                val peakDb = peakResponseDb(coefficients, sampleRate.toDouble())
                return Filters(
                    b0 = FloatArray(active.size) { coefficients[it][0].toFloat() },
                    b1 = FloatArray(active.size) { coefficients[it][1].toFloat() },
                    b2 = FloatArray(active.size) { coefficients[it][2].toFloat() },
                    a1 = FloatArray(active.size) { coefficients[it][3].toFloat() },
                    a2 = FloatArray(active.size) { coefficients[it][4].toFloat() },
                    preamp = 10.0.pow(-peakDb.coerceAtLeast(0.0) / 20).toFloat(),
                    channels = channels,
                    previous = previous,
                )
            }

            /** RBJ cookbook peaking EQ, normalised: b0, b1, b2, a1, a2. */
            internal fun peaking(f0: Double, gainDb: Double, fs: Double): DoubleArray {
                val a = 10.0.pow(gainDb / 40)
                val w0 = 2 * PI * f0 / fs
                val alpha = sin(w0) / (2 * Q)
                val a0 = 1 + alpha / a
                return doubleArrayOf(
                    (1 + alpha * a) / a0,
                    (-2 * cos(w0)) / a0,
                    (1 - alpha * a) / a0,
                    (-2 * cos(w0)) / a0,
                    (1 - alpha / a) / a0,
                )
            }

            /** The cascade's highest gain, in dB, over the audible range (log-spaced points). */
            internal fun peakResponseDb(coefficients: List<DoubleArray>, fs: Double): Double {
                if (coefficients.isEmpty()) return 0.0
                var peak = Double.NEGATIVE_INFINITY
                val top = minOf(20_000.0, fs * 0.49)
                for (i in 0..96) {
                    val f = 20.0 * (top / 20.0).pow(i / 96.0)
                    val db = coefficients.sumOf { responseDb(it, f, fs) }
                    if (db > peak) peak = db
                }
                return peak
            }

            /** One biquad's gain at [f], in dB. */
            internal fun responseDb(c: DoubleArray, f: Double, fs: Double): Double {
                val w = 2 * PI * f / fs
                val cw = cos(w)
                val sw = sin(w)
                val c2w = cos(2 * w)
                val s2w = sin(2 * w)
                val numRe = c[0] + c[1] * cw + c[2] * c2w
                val numIm = -(c[1] * sw + c[2] * s2w)
                val denRe = 1 + c[3] * cw + c[4] * c2w
                val denIm = -(c[3] * sw + c[4] * s2w)
                val num = numRe * numRe + numIm * numIm
                val den = denRe * denRe + denIm * denIm
                return 10 * log10(num / den)
            }
        }
    }
}
