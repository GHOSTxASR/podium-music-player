@file:OptIn(UnstableApi::class)

package app.podium.player.service

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import app.podium.player.api.SoundEffect
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Settings ▸ Equaliser ▸ Effect (D-89): one way of placing the music, after the equaliser in the
 * player's own audio path, on every song, local or online.
 *
 * - **Car stereo** — the cabin: a deep low end, the dashboard's presence, softened highs, the
 *   windscreen's first reflections, a narrower image.
 * - **3D hall** — a large hall's reverb after a short pre-delay, the dry sound widened.
 * - **8D** — the music circling the head every [Orbit.PERIOD_S] seconds: level, arrival time and the
 *   head's shadow change from ear to ear, with a little room around it.
 *
 * None passes the audio through untouched, sample for sample. Changing effect crossfades over
 * [FADE_MS], so it never clicks. Stereo 16-bit and float audio; anything else passes by untouched.
 */
class SoundEffectProcessor : BaseAudioProcessor() {

    @Volatile private var wanted: SoundEffect = SoundEffect.NONE

    private var sampleRate = 0
    private var encoding = C.ENCODING_INVALID
    private var current: Stage = Stage.Identity
    private var previous: Stage? = null
    private var fadeLeft = 0
    private var fadeLength = 1
    private val wet = FloatArray(2)
    private val old = FloatArray(2)

    /** Called from any thread; the next buffer is shaped by it. */
    fun update(effect: SoundEffect) {
        wanted = effect
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val supported = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        if (!supported || inputAudioFormat.channelCount != 2) return AudioProcessor.AudioFormat.NOT_SET
        sampleRate = inputAudioFormat.sampleRate
        encoding = inputAudioFormat.encoding
        current = Stage.of(wanted, sampleRate)
        previous = null
        fadeLength = (sampleRate * FADE_MS / 1000).coerceAtLeast(1)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        val effect = wanted
        if (effect != current.kind) {
            // Fade from what plays now to the new effect.
            previous = current
            current = Stage.of(effect, sampleRate)
            fadeLeft = fadeLength
        }
        val out = replaceOutputBuffer(bytes)
        if (current === Stage.Identity && previous == null) {
            out.put(inputBuffer)
            out.flip()
            return
        }
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val pcm16 = encoding == C.ENCODING_PCM_16BIT
        val frameBytes = if (pcm16) 4 else 8
        while (input.remaining() >= frameBytes) {
            val l: Float
            val r: Float
            if (pcm16) {
                l = input.getShort() / 32768f
                r = input.getShort() / 32768f
            } else {
                l = input.getFloat()
                r = input.getFloat()
            }
            current.process(l, r, wet)
            var yl = wet[0]
            var yr = wet[1]
            val from = previous
            if (from != null) {
                from.process(l, r, old)
                val t = 1f - fadeLeft.toFloat() / fadeLength
                // Equal power: the loudness holds through the change.
                val gIn = sin(t * HALF_PI)
                val gOut = cos(t * HALF_PI)
                yl = yl * gIn + old[0] * gOut
                yr = yr * gIn + old[1] * gOut
                if (--fadeLeft <= 0) previous = null
            }
            if (current === Stage.Identity && from == null) {
                // Settled back to none mid-buffer: exactly the input from here on.
                yl = l
                yr = r
            } else {
                yl = softClip(yl)
                yr = softClip(yr)
            }
            if (pcm16) {
                out.putShort((yl * 32768f).coerceIn(-32768f, 32767f).toInt().toShort())
                out.putShort((yr * 32768f).coerceIn(-32768f, 32767f).toInt().toShort())
            } else {
                out.putFloat(yl)
                out.putFloat(yr)
            }
        }
        while (input.hasRemaining()) out.put(input.get())
        out.flip()
    }

    override fun onFlush() {
        current.clear()
        previous = null
        fadeLeft = 0
    }

    override fun onReset() {
        current = Stage.Identity
        previous = null
        sampleRate = 0
        encoding = C.ENCODING_INVALID
    }

    /** One effect, its memory per stream. */
    internal abstract class Stage(val kind: SoundEffect) {
        abstract fun process(l: Float, r: Float, out: FloatArray)
        open fun clear() {}

        object Identity : Stage(SoundEffect.NONE) {
            override fun process(l: Float, r: Float, out: FloatArray) {
                out[0] = l
                out[1] = r
            }
        }

        companion object {
            fun of(effect: SoundEffect, sampleRate: Int): Stage = when (effect) {
                SoundEffect.NONE -> Identity
                SoundEffect.CAR -> Car(sampleRate)
                SoundEffect.HALL -> Hall(sampleRate)
                SoundEffect.ORBIT -> Orbit(sampleRate)
            }
        }
    }

    /** The car's cabin. */
    internal class Car(fs: Int) : Stage(SoundEffect.CAR) {
        private val bass = Biquad.lowShelf(85.0, 7.0, fs.toDouble())
        private val dash = Biquad.peaking(2_500.0, 2.0, 1.0, fs.toDouble())
        private val air = Biquad.highShelf(9_000.0, -4.0, fs.toDouble())
        private val chain = listOf(bass, dash, air)
        private val state = Array(2) { Array(chain.size) { DoubleArray(2) } }
        private val reflections = Reflections(fs)

        override fun process(l: Float, r: Float, out: FloatArray) {
            var a = l * PREAMP
            var b = r * PREAMP
            for (i in chain.indices) {
                a = chain[i].run(a, state[0][i])
                b = chain[i].run(b, state[1][i])
            }
            // A narrower image: the speakers are close to one side.
            val mid = (a + b) * 0.5f
            val side = (a - b) * 0.5f * 0.75f
            reflections.process(mid + side, mid - side, out)
        }

        override fun clear() {
            state.forEach { s -> s.forEach { it.fill(0.0) } }
            reflections.clear()
        }

        /** The windscreen's and the doors' first reflections, a few milliseconds late. */
        private class Reflections(fs: Int) {
            private val size = Integer.highestOneBit((fs * 0.012).toInt()) * 2
            private val left = FloatArray(size)
            private val right = FloatArray(size)
            private var at = 0
            private val taps = TAPS.map { (ms, gain, cross) -> Triple((fs * ms / 1000).toInt().coerceIn(1, size - 1), gain, cross) }

            fun process(l: Float, r: Float, out: FloatArray) {
                left[at] = l
                right[at] = r
                var yl = l
                var yr = r
                for ((delay, gain, cross) in taps) {
                    val i = (at - delay) and (size - 1)
                    yl += gain * if (cross) right[i] else left[i]
                    yr += gain * if (cross) left[i] else right[i]
                }
                at = (at + 1) and (size - 1)
                out[0] = yl
                out[1] = yr
            }

            fun clear() {
                left.fill(0f)
                right.fill(0f)
            }

            companion object {
                /** Milliseconds late, how loud, and whether it arrives from the other side. */
                val TAPS = listOf(Triple(2.3, 0.22f, false), Triple(3.9, 0.17f, true), Triple(5.6, 0.13f, false), Triple(7.4, 0.11f, true), Triple(9.8, 0.07f, false))
            }
        }

        private companion object {
            /** Room for the bass boost before the soft limit. */
            const val PREAMP = 0.55f
        }
    }

    /** A large hall around the listener. */
    internal class Hall(fs: Int) : Stage(SoundEffect.HALL) {
        private val reverb = Reverb(fs, roomSize = 0.88f, damping = 0.38f, width = 1f)
        private val preDelay = DelayLine((fs * 0.025).toInt())
        private val tail = FloatArray(2)

        override fun process(l: Float, r: Float, out: FloatArray) {
            val delayed = preDelay.push(l, r)
            reverb.process(delayed.first, delayed.second, tail)
            // The dry sound a little wider, the hall around it.
            val mid = (l + r) * 0.5f
            val side = (l - r) * 0.5f * 1.3f
            out[0] = (mid + side) * DRY + tail[0] * WET
            out[1] = (mid - side) * DRY + tail[1] * WET
        }

        override fun clear() {
            reverb.clear()
            preDelay.clear()
        }

        private companion object {
            const val DRY = 0.78f
            const val WET = 0.42f
        }
    }

    /** The music circling the head ("8D"). */
    internal class Orbit(private val fs: Int) : Stage(SoundEffect.ORBIT) {
        private var phase = 0.0
        private val step = 2 * PI / (PERIOD_S * fs)
        private val maxItd = MAX_ITD_S * fs
        private val leftEar = DelayLine(64)
        private val rightEar = DelayLine(64)
        private var shadowL = 0f
        private var shadowR = 0f
        private var coefL = 0f
        private var coefR = 0f
        private var count = 0
        private val room = Reverb(fs, roomSize = 0.62f, damping = 0.5f, width = 1f)
        private val ambience = FloatArray(2)

        override fun process(l: Float, r: Float, out: FloatArray) {
            // Where the music is: pan −1 (left) … 1 (right); front when cos ≥ 0.
            val angle = phase
            phase += step
            if (phase >= 2 * PI) phase -= 2 * PI
            val pan = sin(angle).toFloat()
            val behind = (-cos(angle)).coerceAtLeast(0.0).toFloat()
            if (count++ % COEF_EVERY == 0) {
                // The far ear hears the head's shadow (darker), more so behind.
                coefL = onePole(18_000f - 14_000f * pan.coerceAtLeast(0f) - 5_000f * behind)
                coefR = onePole(18_000f - 14_000f * (-pan).coerceAtLeast(0f) - 5_000f * behind)
            }
            val mid = (l + r) * 0.5f
            val side = (l - r) * 0.5f
            // Equal-power level, and the far ear hears it a little later.
            val theta = (pan + 1f) * (PI.toFloat() / 4f)
            val gl = cos(theta) * GAIN
            val gr = sin(theta) * GAIN
            val delayL = (pan.coerceAtLeast(0f) * maxItd).toFloat()
            val delayR = ((-pan).coerceAtLeast(0f) * maxItd).toFloat()
            val el = leftEar.pushRead(mid, delayL)
            val er = rightEar.pushRead(mid, delayR)
            shadowL = (1 - coefL) * el + coefL * shadowL
            shadowR = (1 - coefR) * er + coefR * shadowR
            val yl = shadowL * gl + side * KEEP_SIDE
            val yr = shadowR * gr - side * KEEP_SIDE
            room.process(yl, yr, ambience)
            out[0] = yl + ambience[0] * ROOM
            out[1] = yr + ambience[1] * ROOM
        }

        private fun onePole(cutoffHz: Float): Float = exp(-2.0 * PI * cutoffHz.coerceAtLeast(500f) / fs).toFloat()

        override fun clear() {
            leftEar.clear()
            rightEar.clear()
            shadowL = 0f
            shadowR = 0f
            room.clear()
        }

        companion object {
            /** Seconds for one turn around the head. */
            const val PERIOD_S = 8.0

            /** The most one ear hears ahead of the other (a head's width). */
            private const val MAX_ITD_S = 0.00045
            private const val COEF_EVERY = 32
            private const val GAIN = 1.25f
            private const val KEEP_SIDE = 0.3f
            private const val ROOM = 0.16f
        }
    }

    /** A short stereo delay, read at a fractional position. */
    internal class DelayLine(length: Int) {
        private val size = Integer.highestOneBit(length.coerceAtLeast(2)) * 2
        private val left = FloatArray(size)
        private val right = FloatArray(size)
        private var at = 0
        private val delay = length

        /** Push a pair, get the pair from [delay] samples ago. */
        fun push(l: Float, r: Float): Pair<Float, Float> {
            left[at] = l
            right[at] = r
            val i = (at - delay) and (size - 1)
            at = (at + 1) and (size - 1)
            return left[i] to right[i]
        }

        /** Mono use: push [x], read it [samples] ago (fractional, linear). */
        fun pushRead(x: Float, samples: Float): Float {
            left[at] = x
            val whole = samples.toInt()
            val frac = samples - whole
            val a = left[(at - whole) and (size - 1)]
            val b = left[(at - whole - 1) and (size - 1)]
            at = (at + 1) and (size - 1)
            return a + (b - a) * frac
        }

        fun clear() {
            left.fill(0f)
            right.fill(0f)
        }
    }

    /**
     * A stereo Schroeder–Moorer reverb with Freeverb's public-domain tuning (Jezar at Dreampoint):
     * eight damped combs and four allpasses per side, the right side's delays a little longer.
     */
    internal class Reverb(fs: Int, roomSize: Float, damping: Float, private val width: Float) {
        private val scale = fs / 44_100.0
        private fun len(n: Int) = (n * scale).toInt().coerceAtLeast(1)
        private val combsL = COMBS.map { Comb(len(it)) }
        private val combsR = COMBS.map { Comb(len(it + SPREAD)) }
        private val allL = ALLPASSES.map { Allpass(len(it)) }
        private val allR = ALLPASSES.map { Allpass(len(it + SPREAD)) }
        private val feedback = roomSize * 0.28f + 0.7f
        private val damp = damping * 0.4f

        fun process(l: Float, r: Float, out: FloatArray) {
            val input = (l + r) * FIXED_GAIN
            var a = 0f
            var b = 0f
            for (c in combsL) a += c.run(input, feedback, damp)
            for (c in combsR) b += c.run(input, feedback, damp)
            for (p in allL) a = p.run(a)
            for (p in allR) b = p.run(b)
            val w1 = width / 2 + 0.5f
            val w2 = (1 - width) / 2
            out[0] = a * w1 + b * w2
            out[1] = b * w1 + a * w2
        }

        fun clear() {
            (combsL + combsR).forEach { it.clear() }
            (allL + allR).forEach { it.clear() }
        }

        private class Comb(size: Int) {
            private val buffer = FloatArray(size)
            private var at = 0
            private var store = 0f

            fun run(x: Float, feedback: Float, damp: Float): Float {
                val y = buffer[at]
                store = y * (1 - damp) + store * damp
                buffer[at] = x + store * feedback
                if (++at == buffer.size) at = 0
                return y
            }

            fun clear() {
                buffer.fill(0f)
                store = 0f
            }
        }

        private class Allpass(size: Int) {
            private val buffer = FloatArray(size)
            private var at = 0

            fun run(x: Float): Float {
                val held = buffer[at]
                buffer[at] = x + held * 0.5f
                if (++at == buffer.size) at = 0
                return held - x
            }

            fun clear() = buffer.fill(0f)
        }

        private companion object {
            const val FIXED_GAIN = 0.015f
            const val SPREAD = 23
            val COMBS = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
            val ALLPASSES = intArrayOf(556, 441, 341, 225)
        }
    }

    /** RBJ cookbook biquads, transposed direct form II (two values of memory). */
    internal class Biquad private constructor(private val b0: Double, private val b1: Double, private val b2: Double, private val a1: Double, private val a2: Double) {
        fun run(x: Float, z: DoubleArray): Float {
            val y = b0 * x + z[0]
            z[0] = b1 * x - a1 * y + z[1]
            z[1] = b2 * x - a2 * y
            return y.toFloat()
        }

        companion object {
            private fun make(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) = Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)

            fun peaking(f0: Double, gainDb: Double, q: Double, fs: Double): Biquad {
                val a = 10.0.pow(gainDb / 40)
                val w = 2 * PI * f0 / fs
                val alpha = sin(w) / (2 * q)
                return make(1 + alpha * a, -2 * cos(w), 1 - alpha * a, 1 + alpha / a, -2 * cos(w), 1 - alpha / a)
            }

            fun lowShelf(f0: Double, gainDb: Double, fs: Double): Biquad {
                val a = 10.0.pow(gainDb / 40)
                val w = 2 * PI * f0 / fs
                val cw = cos(w)
                val alpha = sin(w) / 2 * sqrt(2.0) // shelf slope 1
                val s = 2 * sqrt(a) * alpha
                return make(
                    a * ((a + 1) - (a - 1) * cw + s), 2 * a * ((a - 1) - (a + 1) * cw), a * ((a + 1) - (a - 1) * cw - s),
                    (a + 1) + (a - 1) * cw + s, -2 * ((a - 1) + (a + 1) * cw), (a + 1) + (a - 1) * cw - s,
                )
            }

            fun highShelf(f0: Double, gainDb: Double, fs: Double): Biquad {
                val a = 10.0.pow(gainDb / 40)
                val w = 2 * PI * f0 / fs
                val cw = cos(w)
                val alpha = sin(w) / 2 * sqrt(2.0)
                val s = 2 * sqrt(a) * alpha
                return make(
                    a * ((a + 1) + (a - 1) * cw + s), -2 * a * ((a - 1) + (a + 1) * cw), a * ((a + 1) + (a - 1) * cw - s),
                    (a + 1) - (a - 1) * cw + s, 2 * ((a - 1) - (a + 1) * cw), (a + 1) - (a - 1) * cw - s,
                )
            }
        }
    }

    internal companion object {
        /** How long a change of effect crossfades. */
        const val FADE_MS = 60

        private const val HALF_PI = (PI / 2).toFloat()

        /** Untouched below 0.8; above, rounded off so it never passes full scale. */
        fun softClip(x: Float): Float {
            val m = abs(x)
            if (m <= KNEE) return x
            val y = KNEE + (1 - KNEE) * tanh((m - KNEE) / (1 - KNEE))
            return if (x < 0) -y else y
        }

        private const val KNEE = 0.8f
    }
}
