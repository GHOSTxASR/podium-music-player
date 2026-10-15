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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The lyrics' sense of the bass (D-82): a pass-through step in the player's own audio path (after
 * the equaliser, so it hears what the listener hears) that measures the low end and changes nothing.
 *
 * - The audio is mixed to mono, low-passed at [CUTOFF_HZ] (kick drums, bass lines) and measured as
 *   RMS over 10 ms blocks.
 * - Each block is stamped with the song position it belongs to: the sink tells every flush where the
 *   stream starts ([AudioProcessor.StreamMetadata.positionOffsetUs]), and the blocks count on from
 *   there. The player decodes ahead of what is heard, so the UI asks by the playback position
 *   ([bassAt]) rather than for "now", and the reading lines up with the sound.
 * - A flush (a seek, a new stream) starts the record afresh; a reader never mixes two streams.
 * - Written on the playback thread, read on the main thread, without locks: the blocks of a
 *   generation are published through the volatile counter. A torn read can only cost a frame's
 *   decoration.
 *
 * 16-bit and float audio; anything else passes by unmeasured.
 */
class BassMeter : BaseAudioProcessor(), AudioPulse {

    // The record: block start (song position, µs) and level, in a ring.
    private val starts = LongArray(CAPACITY)
    private val levels = FloatArray(CAPACITY)
    @Volatile private var written = 0L
    @Volatile private var generation = 0
    @Volatile private var blockUs = BLOCK_MS * 1000L

    // Playback-thread state.
    private var sampleRate = 0
    private var channels = 0
    private var encoding = C.ENCODING_INVALID
    private var blockFrames = 0
    private var offsetUs = 0L
    private var frames = 0L
    private var inBlock = 0
    private var sum = 0.0
    private var b0 = 0f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f
    private var z1 = 0f
    private var z2 = 0f

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
        lowPass(CUTOFF_HZ.toDouble(), sampleRate.toDouble())
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        measure(inputBuffer.duplicate().order(ByteOrder.nativeOrder()))
        val out = replaceOutputBuffer(bytes)
        out.put(inputBuffer)
        out.flip()
    }

    private fun measure(input: ByteBuffer) {
        if (channels <= 0) return
        val sixteen = encoding == C.ENCODING_PCM_16BIT
        val frameBytes = channels * if (sixteen) 2 else 4
        while (input.remaining() >= frameBytes) {
            var mono = 0f
            for (ch in 0 until channels) mono += if (sixteen) input.getShort() / 32768f else input.getFloat()
            mono /= channels
            // Transposed direct form II.
            val y = b0 * mono + z1
            z1 = b1 * mono - a1 * y + z2
            z2 = b2 * mono - a2 * y
            sum += (y * y).toDouble()
            frames++
            if (++inBlock == blockFrames) {
                publish(offsetUs + (frames - blockFrames) * 1_000_000L / sampleRate, sqrt(sum / blockFrames).toFloat())
                inBlock = 0
                sum = 0.0
            }
        }
    }

    private fun publish(startUs: Long, level: Float) {
        val n = written
        val i = (n % CAPACITY).toInt()
        starts[i] = startUs
        levels[i] = level
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
        sum = 0.0
        z1 = 0f
        z2 = 0f
    }

    override fun bassAt(positionMs: Long): Float {
        val gen = generation
        val n = written
        if (n == 0L) return -1f
        val us = positionMs * 1000
        val span = blockUs
        // The ring's blocks are in time order; keep clear of the slot being overwritten.
        var lo = maxOf(0L, n - CAPACITY + 1)
        var hi = n - 1
        if (us < starts[(lo % CAPACITY).toInt()] || us >= starts[(hi % CAPACITY).toInt()] + span) return -1f
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[(mid % CAPACITY).toInt()] <= us) lo = mid else hi = mid - 1
        }
        val level = levels[(lo % CAPACITY).toInt()]
        return if (generation == gen) level else -1f
    }

    /** RBJ cookbook second-order low-pass, Butterworth (Q = 1/√2), normalised. */
    private fun lowPass(hz: Double, rate: Double) {
        val w = 2 * PI * hz.coerceAtMost(rate * 0.45) / rate
        val alpha = sin(w) / (2 * Q)
        val c = cos(w)
        val a0 = 1 + alpha
        b0 = ((1 - c) / 2 / a0).toFloat()
        b1 = ((1 - c) / a0).toFloat()
        b2 = b0
        a1 = (-2 * c / a0).toFloat()
        a2 = ((1 - alpha) / a0).toFloat()
    }

    companion object {
        /** Kick drums and bass lines; voices and cymbals are well above. */
        const val CUTOFF_HZ = 120
        const val BLOCK_MS = 10

        /** Five seconds of blocks: far more than the player ever decodes ahead. */
        const val CAPACITY = 512

        private const val Q = 0.7071067811865476
        private const val TAG = "PodiumBass"
    }
}
