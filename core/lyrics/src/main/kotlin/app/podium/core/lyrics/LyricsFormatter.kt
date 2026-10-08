package app.podium.core.lyrics

import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Lyrics in a shape the lyrics screen can follow (LYRICS_ARCHITECTURE.md §5.1, D-50). Providers
 * write lyrics in many ways; the screen shows one line at a time, following the music. So, given
 * the song's length:
 *
 * - **Synced lyrics** keep the provider's times when they're usable. Times that can't be followed
 *   are repaired: lines sharing one stamp are spread to the next stamp; stamps running past the end
 *   of the song are scaled into it; a line too long for the display is split at its phrases, the
 *   pieces timed within the line. When the times say nothing (every line on the same few stamps,
 *   or all bunched in a sliver of the song), the lyrics are paced like plain ones.
 * - **Plain lyrics** are paced across the song: section labels ("[Chorus]") dropped, a lead-in and
 *   an outro left free, each line given time by its length and each verse break a breath. That's
 *   an estimate, and marked so ([Lyrics.Synced.estimated]); the Wheel still reads ahead.
 * - Without the song's length nothing is invented: plain lyrics stay plain.
 *
 * Pure and deterministic; the provider's own answer (as cached) is never changed, only what's shown.
 */
object LyricsFormatter {

    fun forPlayback(lyrics: Lyrics, durationMs: Long?): Lyrics {
        val duration = durationMs?.takeIf { it >= MIN_SONG_MS }
        return when (lyrics) {
            Lyrics.Instrumental -> lyrics
            is Lyrics.Plain -> duration?.let { pace(lyrics.lines, it) } ?: lyrics
            is Lyrics.Synced -> repair(lyrics, duration)
        }
    }

    // --- Synced ------------------------------------------------------------------------------------

    private fun repair(lyrics: Lyrics.Synced, duration: Long?): Lyrics {
        val sung = lyrics.lines.filter { it.text.isNotBlank() }
        if (sung.isEmpty()) return lyrics
        if (timesSayNothing(sung, duration)) {
            return duration?.let { pace(lyrics.lines.map { it.text }, it) } ?: Lyrics.Plain(sung.map { it.text })
        }
        // A section label sung at a time is a pause, not words: it becomes a gap, keeping the timing.
        var lines = lyrics.lines.map { if (it.words.isEmpty() && isLabel(it.text)) it.copy(text = "") else it }
        var repaired = false
        if (duration != null) {
            val last = lines.maxOf { it.startMs }
            if (last > duration + OVERRUN_TOLERANCE_MS) {
                // Timed for a longer version (or in the wrong unit): fitted into this one.
                val factor = (duration - OUTRO_MIN_MS).toDouble() / last
                lines = lines.map { it.scaled(factor) }
                repaired = true
            }
        }
        val spread = spreadSharedStarts(lines, duration)
        if (spread != lines) repaired = true
        val split = spread.flatMapIndexed { i, line -> splitLong(line, spread.drop(i + 1).firstOrNull { it.startMs > line.startMs }?.startMs) }
        return Lyrics.Synced(split, estimated = lyrics.estimated || repaired)
    }

    /** Whether the stamps can't place the lines: too few distinct ones, or all in a sliver of the song. */
    private fun timesSayNothing(sung: List<LyricsLine>, duration: Long?): Boolean {
        if (sung.size < MIN_LINES_TO_JUDGE) return false
        val distinct = sung.map { it.startMs }.distinct().size
        if (distinct * 3 <= sung.size) return true
        val span = sung.maxOf { it.startMs } - sung.minOf { it.startMs }
        return duration != null && span < duration * MIN_SPAN_FRACTION
    }

    /** Runs of lines on one stamp spread evenly (by length) up to the next stamp. */
    private fun spreadSharedStarts(lines: List<LyricsLine>, duration: Long?): List<LyricsLine> {
        val out = ArrayList<LyricsLine>(lines.size)
        var i = 0
        while (i < lines.size) {
            var j = i
            while (j + 1 < lines.size && lines[j + 1].startMs == lines[i].startMs && lines[j + 1].words.isEmpty()) j++
            if (j == i || lines[i].words.isNotEmpty()) {
                out += lines[i]
                i++
                continue
            }
            val start = lines[i].startMs
            val end = lines.getOrNull(j + 1)?.startMs ?: (start + (j - i + 1) * DEFAULT_LINE_MS).let { e -> duration?.let { minOf(e, it) } ?: e }
            val run = lines.subList(i, j + 1)
            val weights = run.map { weightOf(it.text) }
            var t = start.toDouble()
            val step = (end - start).coerceAtLeast(0).toDouble() / weights.sum()
            run.forEachIndexed { k, line ->
                out += line.copy(startMs = t.roundToLong(), endMs = null)
                t += weights[k] * step
            }
            i = j + 1
        }
        return out
    }

    /** A line too long for one screen, split at its phrases; the pieces share the line's time. */
    private fun splitLong(line: LyricsLine, nextStartMs: Long?): List<LyricsLine> {
        val words = WordTiming.words(line)
        if (words.size <= MAX_WORDS_PER_LINE) return listOf(line)
        val pieces = phrases(words)
        if (pieces.size < 2) return listOf(line)
        val end = line.endMs ?: nextStartMs ?: (line.startMs + words.size * WORD_MS)
        val timed = !WordTiming.isEstimated(line)
        var index = 0
        var t = line.startMs.toDouble()
        val step = (end - line.startMs).coerceAtLeast(0).toDouble() / words.size
        return pieces.map { piece ->
            val start = if (timed) line.words[index].startMs else t.roundToLong()
            val pieceWords = if (timed) line.words.subList(index, index + piece.size) else emptyList()
            index += piece.size
            t += piece.size * step
            LyricsLine(start, piece.joinToString(" "), words = pieceWords)
        }
    }

    /** Words grouped at punctuation into pieces of at most [MAX_WORDS_PER_LINE], evenly otherwise. */
    private fun phrases(words: List<String>): List<List<String>> {
        val out = ArrayList<List<String>>()
        var current = ArrayList<String>()
        for (w in words) {
            current += w
            val breaks = w.last() in PHRASE_ENDS && current.size >= MIN_WORDS_PER_PIECE
            if (breaks || current.size == MAX_WORDS_PER_LINE) {
                out += current
                current = ArrayList()
            }
        }
        if (current.isNotEmpty()) {
            // A short tail joins the piece before it when that still fits.
            if (current.size < MIN_WORDS_PER_PIECE && out.isNotEmpty() && out.last().size + current.size <= MAX_WORDS_PER_LINE) {
                out[out.lastIndex] = out.last() + current
            } else out += current
        }
        return out
    }

    // --- Plain -------------------------------------------------------------------------------------

    /** Plain lines paced across a song of [duration]; null when there's nothing to sing. */
    private fun pace(raw: List<String>, duration: Long): Lyrics.Synced? {
        val lines = raw.map { it.trim() }.filterNot(::isLabel)
            .fold(ArrayList<String>()) { acc, l -> if (l.isEmpty() && (acc.isEmpty() || acc.last().isEmpty())) acc else acc.apply { add(l) } }
            .dropLastWhile { it.isEmpty() }
            .flatMap { l -> if (l.isEmpty()) listOf(l) else phrases(l.split(' ').filter { it.isNotEmpty() }).map { it.joinToString(" ") } }
        if (lines.none { it.isNotEmpty() }) return null
        val intro = (duration * INTRO_FRACTION).roundToLong().coerceIn(INTRO_MIN_MS, INTRO_MAX_MS)
        val outro = (duration * OUTRO_FRACTION).roundToLong().coerceIn(OUTRO_MIN_MS, OUTRO_MAX_MS)
        val singing = max(duration - intro - outro, duration / 2)
        val weights = lines.map { if (it.isEmpty()) BREATH_WEIGHT else weightOf(it) }
        val step = singing.toDouble() / weights.sum()
        var t = intro.toDouble()
        val timed = lines.mapIndexed { i, text ->
            LyricsLine(t.roundToLong(), text).also { t += weights[i] * step }
        }
        return Lyrics.Synced(timed, estimated = true)
    }

    /** How long a line takes, relatively: a moment to start, then by its letters. */
    private fun weightOf(text: String): Double = 1.0 + text.count { it.isLetterOrDigit() } / LETTERS_PER_UNIT

    /** "[Chorus]", "(Verse 2)", "Chorus:", "[Bridge: Name]" — labels, not lyrics. */
    internal fun isLabel(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (t.startsWith("[") && t.endsWith("]")) return true
        val words = t.trimEnd(':').trim('(', ')').trim().lowercase()
        return (t.endsWith(":") || (t.startsWith("(") && t.endsWith(")"))) && LABEL.containsMatchIn(words) && words.split(' ').size <= 4
    }

    private fun LyricsLine.scaled(factor: Double) = copy(
        startMs = (startMs * factor).roundToLong(),
        endMs = endMs?.let { (it * factor).roundToLong() },
        words = words.map { it.copy(startMs = (it.startMs * factor).roundToLong()) },
    )

    private val LABEL = Regex("""^(intro|outro|verse|chorus|pre-chorus|prechorus|post-chorus|hook|bridge|refrain|interlude|break|instrumental)\b""")
    private val PHRASE_ENDS = setOf(',', ';', '.', '!', '?', ':')

    private const val MIN_SONG_MS = 20_000L
    private const val MIN_LINES_TO_JUDGE = 6
    private const val MIN_SPAN_FRACTION = 0.15
    private const val OVERRUN_TOLERANCE_MS = 5_000L
    private const val DEFAULT_LINE_MS = 3_000L
    private const val WORD_MS = 350L
    private const val MAX_WORDS_PER_LINE = 14
    private const val MIN_WORDS_PER_PIECE = 4
    private const val LETTERS_PER_UNIT = 9.0
    private const val BREATH_WEIGHT = 1.6
    private const val INTRO_FRACTION = 0.07
    private const val INTRO_MIN_MS = 4_000L
    private const val INTRO_MAX_MS = 18_000L
    private const val OUTRO_FRACTION = 0.06
    private const val OUTRO_MIN_MS = 4_000L
    private const val OUTRO_MAX_MS = 20_000L
}
