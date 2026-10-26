package app.podium.player.service

import app.podium.player.api.AudioPulse
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Lines a carried copy up with the song by their sound (D-96). Two copies of one recording can start
 * their audio a little apart (another encoder's padding, a few ms of silence trimmed), so the same
 * position isn't always the same sound, and a crossfade between them would be heard twice over.
 *
 * Both players' meters ([AudioMeter]) record the loudness of every 10 ms they decode, stamped with its
 * place in the song. A two-second stretch of the song is compared with the copy at offsets of up to
 * [MAX_OFFSET_MS] either way, by their onsets (where the sound jumps: beats, notes, consonants), and
 * the offset where they agree best is where the copy's sound is.
 *
 * Only a clear answer counts: the best offset must agree well ([MIN_MATCH]) and clearly better than
 * any other ([MIN_LEAD]). A quiet or steady passage has nothing to go by: [Result.Unsure].
 */
internal class SoundAlignment(private val song: AudioPulse, private val copy: AudioPulse) {

    sealed interface Result {
        /** Not enough of both measured yet. */
        data object NotYet : Result

        data object Unsure : Result

        /** At song position p the copy has the same sound at p + [ms]. */
        data class Offset(val ms: Long) : Result
    }

    /** Both meters measure while this is open. */
    fun listen(): AutoCloseable {
        val a = song.listen()
        val b = copy.listen()
        return AutoCloseable {
            a.close()
            b.close()
        }
    }

    /** Compares the stretch of the song that ended a little before [songPositionMs] with the copy. */
    fun measure(songPositionMs: Long): Result {
        val start = songPositionMs - MARGIN_MS - MAX_OFFSET_MS - WINDOW_MS
        val a = envelope(song, start, WINDOW_STEPS) ?: return Result.NotYet
        val b = envelope(copy, start - MAX_OFFSET_MS, WINDOW_STEPS + 2 * LAGS) ?: return Result.NotYet
        return offset(a, b)
    }

    private fun envelope(pulse: AudioPulse, fromMs: Long, steps: Int): FloatArray? {
        val levels = FloatArray(3)
        val out = FloatArray(steps)
        for (i in 0 until steps) {
            if (!pulse.levelsAt(fromMs + i * STEP_MS, levels)) return null
            out[i] = levels[AudioPulse.WHOLE]
        }
        return out
    }

    companion object {
        const val STEP_MS = 10L
        const val WINDOW_MS = 2_000L
        const val MAX_OFFSET_MS = 500L
        private const val MARGIN_MS = 100L
        private const val MIN_MATCH = 0.6
        private const val MIN_LEAD = 0.1

        /** Offsets this close to the best are the same peak, not a rival. */
        private const val NEIGHBOURHOOD = 3
        private const val WINDOW_STEPS = (WINDOW_MS / STEP_MS).toInt()
        private const val LAGS = (MAX_OFFSET_MS / STEP_MS).toInt()

        /** How long the copy must have played, measured, before [measure] can answer. */
        const val NEEDS_MS = WINDOW_MS + 2 * MAX_OFFSET_MS + MARGIN_MS

        /**
         * Where [copy] — the same length as [song] plus an equal margin at each end — agrees best with
         * [song], as an offset from the middle, in steps of [STEP_MS].
         */
        internal fun offset(song: FloatArray, copy: FloatArray): Result {
            val lags = (copy.size - song.size) / 2
            if (lags < 0) return Result.Unsure
            val a = onsets(song)
            val b = onsets(copy)
            val scores = DoubleArray(2 * lags + 1) { shift -> correlation(a, b, shift) }
            val best = scores.indices.maxBy { scores[it] }
            val rival = scores.indices.filter { abs(it - best) > NEIGHBOURHOOD }.maxOfOrNull { scores[it] } ?: -1.0
            if (scores[best] < MIN_MATCH || scores[best] - rival < MIN_LEAD) return Result.Unsure
            return Result.Offset((best - lags) * STEP_MS)
        }

        /** Loudness on a log scale, and where it rises: beats and notes, whatever the overall level. */
        private fun onsets(levels: FloatArray): DoubleArray {
            val out = DoubleArray(levels.size)
            var previous = ln(levels.firstOrNull()?.toDouble()?.plus(FLOOR) ?: FLOOR)
            for (i in levels.indices) {
                val now = ln(levels[i] + FLOOR)
                out[i] = max(0.0, now - previous)
                previous = now
            }
            return out
        }

        private const val FLOOR = 1e-4

        /** Pearson's correlation of [a] with [b] from [shift] on. */
        private fun correlation(a: DoubleArray, b: DoubleArray, shift: Int): Double {
            val n = a.size
            var sa = 0.0
            var sb = 0.0
            for (i in 0 until n) {
                sa += a[i]
                sb += b[i + shift]
            }
            val ma = sa / n
            val mb = sb / n
            var num = 0.0
            var da = 0.0
            var db = 0.0
            for (i in 0 until n) {
                val x = a[i] - ma
                val y = b[i + shift] - mb
                num += x * y
                da += x * x
                db += y * y
            }
            return if (da <= 0.0 || db <= 0.0) 0.0 else num / sqrt(da * db)
        }
    }
}
