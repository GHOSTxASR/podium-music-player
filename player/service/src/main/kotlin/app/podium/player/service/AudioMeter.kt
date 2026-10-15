@file:OptIn(UnstableApi::class)

package app.podium.player.service

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import app.podium.player.api.AudioPulse
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How the music sounds, for the lyrics to move with it (D-82, D-83): a pass-through step in the
 * player's own audio path (after the equaliser, so it hears what the listener hears) that measures
 * the audio and changes nothing.
 *
 * - The audio is mixed to mono and measured as RMS over 10 ms blocks three ways: whole, low-passed
 *   at [LOW_HZ] (kick drums, bass lines) and high-passed at [HIGH_HZ] (cymbals, breath, sparkle).
 * - Each block is stamped with the song position it belongs to: the sink tells every flush where the
 *   stream starts ([AudioProcessor.StreamMetadata.positionOffsetUs]), and the blocks count on from
 *   there. The player decodes ahead of what is heard, so the UI asks by the playback position
 *   ([levelsAt]) rather than for "now", and the reading lines up with the sound.
 * - A flush (a seek, a new stream) starts the record afresh; a reader never mixes two streams.
 * - Only while something [listen]s (the lyrics on screen); otherwise the audio passes straight through
 *   and nothing is measured.
 * - Written on the playback thread, read on the main thread, without locks: the blocks of a
 *   generation are published through the volatile counter. A torn read can only cost a frame's
 *   decoration.
 *
 * 16-bit and float audio; anything else passes by unmeasured.
 */
class AudioMeter : BaseAudioProcessor(), AudioPulse {

    // The record: block start (song position, µs) and levels, in a ring.
    private val starts = LongArray(CAPACITY)
    private val whole = FloatArray(CAPACITY)
    private val low = FloatArray(CAPACITY)
    private val high = FloatArray(CAPACITY)
    @Volatile private var written = 0L
    @Volatile private var generation = 0
    @Volatile private var blockUs = BLOCK_MS * 1000L
    private val listeners = AtomicInteger(0)

    // Playback-thread state.
    private var sampleRate = 0
    private var channels = 0
    private var encoding = C.ENCODING_INVALID
    private var blockFrames = 0
    private var offsetUs = 0L
    private var frames = 0L
    private var inBlock = 0
    private var sumWhole = 0.0
    private var sumLow = 0.0
    private var sumHigh = 0.0
    private val lowPass = Biquad()
    private val highPass = Biquad()

    override fun listen(): AutoCloseable {
        listeners.incrementAndGet()
        var open = true
        return AutoCloseable {
            if (open) {
                open = false
                listeners.decrementAndGet()
            }
        }
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        forget()
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            return AudioProcessor.AudioFormat.NOT_SET // inactive: passes by unmeasured
        }
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        encoding = inputAudioFormat.encoding
        blockFrames = (sampleRate * BLOCK_MS / 1000).coerceAtLeast(1)
        blockUs = blockFrames * 1_000_000L / sampleRate
        lowPass.lowPass(LOW_HZ.toDouble(), sampleRate.toDouble())
        highPass.highPass(HIGH_HZ.toDouble(), sampleRate.toDouble())
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        if (listeners.get() > 0) {
            measure(inputBuffer.duplicate().order(ByteOrder.nativeOrder()))
        } else {
            // Nobody listening: just keep the song position counting, so listening can start mid-stream.
            frames += bytes / frameBytes().coerceAtLeast(1)
            inBlock = 0
            sumWhole = 0.0
            sumLow = 0.0
            sumHigh = 0.0
        }
        val out = replaceOutputBuffer(bytes)
        out.put(inputBuffer)
        out.flip()
    }

    private fun frameBytes(): Int = channels * if (encoding == C.ENCODING_PCM_16BIT) 2 else 4

    private fun measure(input: ByteBuffer) {
        if (channels <= 0) return
        val sixteen = encoding == C.ENCODING_PCM_16BIT
        val frameBytes = frameBytes()
        while (input.remaining() >= frameBytes) {
            var mono = 0f
            for (ch in 0 until channels) mono += if (sixteen) input.getShort() / 32768f else input.getFloat()
            mono /= channels
            val l = lowPass.process(mono)
            val h = highPass.process(mono)
            sumWhole += (mono * mono).toDouble()
            sumLow += (l * l).toDouble()
            sumHigh += (h * h).toDouble()
            frames++
            if (++inBlock == blockFrames) {
                publish(
                    offsetUs + (frames - blockFrames) * 1_000_000L / sampleRate,
                    sqrt(sumWhole / blockFrames).toFloat(),
                    sqrt(sumLow / blockFrames).toFloat(),
                    sqrt(sumHigh / blockFrames).toFloat(),
                )
                inBlock = 0
                sumWhole = 0.0
                sumLow = 0.0
                sumHigh = 0.0
            }
        }
    }

    private fun publish(startUs: Long, wholeLevel: Float, lowLevel: Float, highLevel: Float) {
        val n = written
        val i = (n % CAPACITY).toInt()
        starts[i] = startUs
        whole[i] = wholeLevel
        low[i] = lowLevel
        high[i] = highLevel
        written = n + 1 // publishes the block
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        forget()
        offsetUs = streamMetadata.positionOffsetUs
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "flush: stream starts at ${offsetUs / 1000} ms")
    }

    override fun onReset() {
        forget()
        sampleRate = 0
        channels = 0
        encoding = C.ENCODING_INVALID
    }

    private fun forget() {
        generation++
        written = 0
        offsetUs = 0
        frames = 0
        inBlock = 0
        sumWhole = 0.0
        sumLow = 0.0
        sumHigh = 0.0
        lowPass.clear()
        highPass.clear()
    }

    /** The ring slot of the block covering [positionMs], or -1. */
    private fun slotAt(positionMs: Long): Int {
        val n = written
        if (n == 0L) return -1
        val us = positionMs * 1000
        // The ring's blocks are in time order; keep clear of the slot being overwritten.
        var lo = maxOf(0L, n - CAPACITY + 1)
        var hi = n - 1
        if (us < starts[(lo % CAPACITY).toInt()] || us >= starts[(hi % CAPACITY).toInt()] + blockUs) return -1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[(mid % CAPACITY).toInt()] <= us) lo = mid else hi = mid - 1
        }
        return (lo % CAPACITY).toInt()
    }

    override fun bassAt(positionMs: Long): Float {
        val gen = generation
        val i = slotAt(positionMs)
        if (i < 0) return -1f
        val level = low[i]
        return if (generation == gen) level else -1f
    }

    override fun levelsAt(positionMs: Long, out: FloatArray): Boolean {
        val gen = generation
        val i = slotAt(positionMs)
        if (i < 0) return false
        val w = whole[i]
        val l = low[i]
        val h = high[i]
        if (generation != gen) return false
        out[AudioPulse.WHOLE] = w
        out[AudioPulse.LOW] = l
        out[AudioPulse.HIGH] = h
        return true
    }

    /** A second-order filter (RBJ cookbook, Butterworth Q = 1/√2), transposed direct form II. */
    private class Biquad {
        private var b0 = 0f
        private var b1 = 0f
        private var b2 = 0f
        private var a1 = 0f
        private var a2 = 0f
        private var z1 = 0f
        private var z2 = 0f

        fun process(x: Float): Float {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }

        fun clear() {
            z1 = 0f
            z2 = 0f
        }

        fun lowPass(hz: Double, rate: Double) {
            val (c, alpha) = prewarp(hz, rate)
            set((1 - c) / 2, 1 - c, (1 - c) / 2, c, alpha)
        }

        fun highPass(hz: Double, rate: Double) {
            val (c, alpha) = prewarp(hz, rate)
            set((1 + c) / 2, -(1 + c), (1 + c) / 2, c, alpha)
        }

        private fun prewarp(hz: Double, rate: Double): Pair<Double, Double> {
            val w = 2 * PI * hz.coerceAtMost(rate * 0.45) / rate
            return cos(w) to sin(w) / (2 * Q)
        }

        private fun set(nb0: Double, nb1: Double, nb2: Double, c: Double, alpha: Double) {
            val a0 = 1 + alpha
            b0 = (nb0 / a0).toFloat()
            b1 = (nb1 / a0).toFloat()
            b2 = (nb2 / a0).toFloat()
            a1 = (-2 * c / a0).toFloat()
            a2 = ((1 - alpha) / a0).toFloat()
        }
    }

    companion object {
        /** Kick drums and bass lines; voices and cymbals are well above. */
        const val LOW_HZ = 120

        /** Cymbals, breath and sparkle; voices' bodies are below. */
        const val HIGH_HZ = 2_500
        const val BLOCK_MS = 10

        /** Five seconds of blocks: far more than the player ever decodes ahead. */
        const val CAPACITY = 512

        private const val Q = 0.7071067811865476
        private const val TAG = "PodiumAudioMeter"
    }
}
