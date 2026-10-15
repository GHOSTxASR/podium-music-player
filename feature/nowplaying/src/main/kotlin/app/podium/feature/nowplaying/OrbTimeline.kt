package app.podium.feature.nowplaying

import app.podium.core.lyrics.Lyrics
import app.podium.core.lyrics.WordTiming

/**
 * When the orb shows during a song (D-83, LYRICS_ARCHITECTURE.md §14): only in long stretches without
 * words, judged from the lyrics' own times and the playback position — never from what a frame happens
 * to show, so a seek, a rewind or a pause lands in the right state by itself.
 *
 * A stretch without words runs from the moment a line's words end to the next line's start (or from
 * the song's start to its first line, or from its last line to its end). It brings the orb only when it
 * lasts at least [THRESHOLD_MS] and has room for the whole move: the last line lingers ([LINGER_MS],
 * longer when its end is only estimated), the camera pulls back ([ENTER_MS]), the orb holds, and the
 * camera goes back in through the orb's hollow ([EXIT_MS]), arriving exactly when the next line
 * starts. Shorter gaps keep the words on screen.
 *
 * Pure, so it is tested without a screen.
 */
internal class OrbTimeline private constructor(
    // Per stretch: where it starts and ends, when the camera starts pulling back and when it starts
    // going back in (Long.MAX_VALUE after the last line: the orb stays to the end).
    private val starts: LongArray,
    private val ends: LongArray,
    private val enters: LongArray,
    private val exits: LongArray,
    private val qualifies: BooleanArray,
    /** No words at all (or none found): the orb throughout. */
    val always: Boolean,
) {

    enum class Phase {
        /** Words are being sung (or the line is on screen between them). */
        LYRICS,

        /** A gap too short for the orb: the words stay. */
        BRIEF_GAP,

        /** A long gap, still lingering on the last line before the camera moves. */
        SUSTAINED,

        /** The camera pulling back from the words to the orb. */
        ENTERING,

        /** The orb alone. */
        ORB,

        /** The camera going in through the orb's hollow to the next line. */
        EXITING,
    }

    /** The state at one position; reused between frames. */
    class State {
        var phase = Phase.LYRICS

        /** How far through [Phase.ENTERING] or [Phase.EXITING] (0–1); 0 otherwise. */
        var progress = 0f

        /** How much the camera is out at the orb: 0 at the words, 1 at the orb, eased. */
        var amount = 0f

        /** True from the start of the move in until the next line starts: the screen shows that line. */
        var showsNext = false
    }

    /** Whether any stretch of this song brings the orb. */
    val any: Boolean get() = always || qualifies.any { it }

    fun at(positionMs: Double, out: State) {
        out.progress = 0f
        out.showsNext = false
        if (always) {
            out.phase = Phase.ORB
            out.amount = 1f
            return
        }
        val g = gapAt(positionMs)
        if (g < 0) {
            out.phase = Phase.LYRICS
            out.amount = 0f
            return
        }
        if (!qualifies[g]) {
            out.phase = Phase.BRIEF_GAP
            out.amount = 0f
            return
        }
        val enter = enters[g]
        val held = enter + ENTER_MS
        val exit = exits[g]
        when {
            positionMs < enter -> {
                out.phase = Phase.SUSTAINED
                out.amount = 0f
            }
            positionMs < held -> {
                out.phase = Phase.ENTERING
                out.progress = ((positionMs - enter) / ENTER_MS).toFloat().coerceIn(0f, 1f)
                out.amount = ease(out.progress)
            }
            positionMs < exit -> {
                out.phase = Phase.ORB
                out.amount = 1f
            }
            else -> {
                out.phase = Phase.EXITING
                out.progress = ((positionMs - exit) / EXIT_MS).toFloat().coerceIn(0f, 1f)
                out.amount = 1f - ease(out.progress)
                out.showsNext = true
            }
        }
        if (out.phase == Phase.ORB && exit != Long.MAX_VALUE) out.showsNext = true
    }

    /** The next moment after [positionMs] where the state changes (for a screen that wakes by events), or null. */
    fun nextChangeMs(positionMs: Long): Long? {
        var best: Long? = null
        fun consider(t: Long) {
            if (t > positionMs && t != Long.MAX_VALUE && (best == null || t < best!!)) best = t
        }
        for (g in starts.indices) {
            if (!qualifies[g]) continue
            consider(enters[g])
            consider(enters[g] + ENTER_MS)
            consider(exits[g])
        }
        return best
    }

    /** The stretch without words containing [positionMs], or -1. */
    private fun gapAt(positionMs: Double): Int {
        var lo = 0
        var hi = starts.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] <= positionMs) {
                found = mid
                lo = mid + 1
            } else hi = mid - 1
        }
        return if (found >= 0 && positionMs < ends[found]) found else -1
    }

    companion object {
        /** A stretch without words this long (or longer) can bring the orb. */
        const val THRESHOLD_MS = 6_000L

        /** How long the last line stays before the camera moves, when its end is known (the provider's end or word times). */
        const val LINGER_MS = 1_400L

        /** … and when its end is Podium's estimate (a held last note may still be sounding). */
        const val LINGER_ESTIMATED_MS = 2_800L
        const val ENTER_MS = 1_800L
        const val EXIT_MS = 1_500L

        /** The orb holds at least this long, or the gap keeps the words. */
        const val MIN_HOLD_MS = 800L

        /** Never: the orb doesn't come (plain lyrics, lyrics timed by Podium, reading with the Wheel). */
        val NEVER = OrbTimeline(LongArray(0), LongArray(0), LongArray(0), LongArray(0), BooleanArray(0), always = false)

        /** No words at all, or none found: the orb throughout. */
        val ALWAYS = OrbTimeline(LongArray(0), LongArray(0), LongArray(0), LongArray(0), BooleanArray(0), always = true)

        /**
         * The stretches of synced [lyrics]. Lyrics whose times are Podium's own estimate (paced plain
         * lyrics, D-50) never bring the orb: their gaps are guesses.
         */
        fun of(lyrics: Lyrics.Synced, durationMs: Long?): OrbTimeline {
            if (lyrics.estimated) return NEVER
            val lines = lyrics.lines
            val sung = lines.indices.filter { WordTiming.words(lines[it]).isNotEmpty() }
            if (sung.isEmpty()) return ALWAYS
            val starts = ArrayList<Long>()
            val ends = ArrayList<Long>()
            val enters = ArrayList<Long>()
            val exits = ArrayList<Long>()
            val qualifies = ArrayList<Boolean>()
            // [last]: after the song's last line, where the orb stays to the end instead of leading anywhere.
            fun add(start: Long, end: Long, linger: Long, last: Boolean) {
                if (end <= start) return
                val enter = start + linger
                val exit = if (last) Long.MAX_VALUE else end - EXIT_MS
                val room = (if (last) end else exit) - (enter + ENTER_MS)
                starts += start
                ends += end
                enters += enter
                exits += exit
                qualifies += end - start >= THRESHOLD_MS && room >= MIN_HOLD_MS
            }
            // Before the first line: the introduction (the quiet "…" lingers like a line would).
            add(0L, lines[sung.first()].startMs, LINGER_MS, last = false)
            for (k in sung.indices) {
                val line = lines[sung[k]]
                val next = sung.getOrNull(k + 1)?.let { lines[it].startMs }
                val wordsEnd = WordTiming.end(line, next)
                val known = line.endMs != null || !WordTiming.isEstimated(line)
                val end = next ?: durationMs?.takeIf { it > wordsEnd } ?: Long.MAX_VALUE
                add(wordsEnd, end, if (known) LINGER_MS else LINGER_ESTIMATED_MS, last = next == null)
            }
            return OrbTimeline(starts.toLongArray(), ends.toLongArray(), enters.toLongArray(), exits.toLongArray(), qualifies.toBooleanArray(), always = false)
        }

        /** Smoothstep: the camera starts and ends its move gently. */
        fun ease(t: Float): Float {
            val x = t.coerceIn(0f, 1f)
            return x * x * (3f - 2f * x)
        }
    }
}
