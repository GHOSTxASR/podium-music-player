package app.podium.feature.nowplaying.line

import app.podium.core.lyrics.Lyrics
import app.podium.core.lyrics.LyricsLine
import app.podium.core.lyrics.WordTiming

/**
 * One lyric line the pen writes: [text] from [startMs], each word reached at [wordStarts] (one per
 * whitespace-separated word, as [WordTiming.words] splits them), the last word finished at
 * [endMs]. [estimated]: the words' pace inside the line is Podium's estimate ([WordTiming]), not the
 * provider's word times — only the line's start is the provider's.
 */
internal class LinePhrase(
    /** Index of the line in the lyrics (for the Wheel and the details). */
    val line: Int,
    val text: String,
    val startMs: Long,
    val wordStarts: LongArray,
    val endMs: Long,
    val estimated: Boolean,
)

/**
 * What the pen writes and when (LYRICS_ARCHITECTURE.md §12.3): the phrases in order and, when the
 * song's length is known, where it ends ([songEndMs]). Between and around the phrases the pen draws
 * instrumental line art. Timing comes only from the lyrics: provider word times when a line has
 * them, otherwise the line's own start and end with the words paced inside it. Nothing is invented
 * beyond what [WordTiming] already estimates (and labels).
 */
internal class LineScript(val phrases: List<LinePhrase>, val songEndMs: Long?, val timed: Boolean) {

    /** The phrase that writes lyric line [line], if it has words. */
    fun phraseOf(line: Int): Int = phrases.indexOfFirst { it.line == line }

    companion object {
        /** A connector between phrases gets at least this long, taken from the end of the phrase before. */
        const val MIN_CONNECTOR_MS = 240L

        /** Without the song's length, the line keeps drawing this long after the last phrase. */
        const val OPEN_OUTRO_MS = 12_000L

        /** Plain lyrics (no times at all) are laid out on a nominal clock; they are never auto-advanced. */
        const val PLAIN_STEP_MS = 6_000L

        fun synced(lyrics: Lyrics.Synced, durationMs: Long?): LineScript {
            val lines = lyrics.lines
            val phrases = ArrayList<LinePhrase>()
            for (i in lines.indices) {
                val line = lines[i]
                if (WordTiming.words(line).isEmpty()) continue // a gap: drawn as instrumental line
                val next = lines.getOrNull(i + 1)?.startMs?.takeIf { it > line.startMs }
                phrases += phrase(i, line, next)
            }
            // Leave every connector a moment: a phrase sung right up to the next one finishes a
            // little sooner, never later, so the next phrase still starts exactly on time.
            for (k in 0 until phrases.size - 1) {
                val a = phrases[k]
                val b = phrases[k + 1]
                if (b.startMs - a.endMs < MIN_CONNECTOR_MS) {
                    val last = a.wordStarts.last()
                    val end = maxOf(last + (a.endMs - last) / 2, b.startMs - MIN_CONNECTOR_MS).coerceAtMost(b.startMs)
                    phrases[k] = LinePhrase(a.line, a.text, a.startMs, a.wordStarts, maxOf(end, last), a.estimated)
                }
            }
            return LineScript(phrases, durationMs?.takeIf { it > 0 }, timed = true)
        }

        /** Plain lyrics: one phrase per line on a nominal clock, so each line can be shown written. */
        fun plain(lines: List<String>): LineScript {
            val phrases = lines.mapIndexedNotNull { i, text ->
                val line = LyricsLine(i * PLAIN_STEP_MS + PLAIN_STEP_MS, text)
                if (WordTiming.words(line).isEmpty()) null else phrase(i, line, line.startMs + PLAIN_STEP_MS / 2)
            }
            return LineScript(phrases, null, timed = false)
        }

        /** A song without words: one instrumental composition across it. */
        fun instrumental(durationMs: Long?): LineScript = LineScript(emptyList(), durationMs?.takeIf { it > 0 }, timed = true)

        private fun phrase(index: Int, line: LyricsLine, next: Long?): LinePhrase {
            val starts = WordTiming.starts(line, next)
            val end = WordTiming.end(line, next)
            return LinePhrase(index, line.text.trim(), line.startMs, starts.toLongArray(), end, WordTiming.isEstimated(line))
        }
    }
}
