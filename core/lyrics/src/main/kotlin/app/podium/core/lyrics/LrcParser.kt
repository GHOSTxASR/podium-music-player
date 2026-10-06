package app.podium.core.lyrics

/**
 * Reads LRC text (LYRICS_ARCHITECTURE.md §4): `[mm:ss.xx] words`, also `[mm:ss]`, `[mm:ss.xxx]`,
 * `[mm:ss:xx]` and `[h:mm:ss.xx]`; several stamps on one line (a repeated chorus); an `[offset:±ms]`
 * tag; metadata tags (`[ar:…]`) ignored; word-level stamps (enhanced LRC, `<mm:ss.xx>` before a word
 * or syllable) kept as the line's [LyricsLine.words] and removed from its text. Lines without a
 * readable stamp are skipped, never guessed. Malformed input gives an empty list.
 */
object LrcParser {

    private val STAMP = Regex("""\[(\d{1,3}):(\d{1,2})(?::(\d{1,2}))?(?:[.:](\d{1,3}))?]""")
    private val LEADING_STAMPS = Regex("""^\s*((?:\[\d{1,3}:\d{1,2}(?::\d{1,2})?(?:[.:]\d{1,3})?]\s*)+)(.*)$""")
    private val OFFSET = Regex("""^\s*\[offset:\s*([+-]?\d+)\s*]\s*$""", RegexOption.IGNORE_CASE)
    private val WORD_STAMP = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")
    private val SPACES = Regex("""\s+""")

    fun parse(text: String?): List<LyricsLine> {
        if (text.isNullOrBlank()) return emptyList()
        var offsetMs = 0L
        val lines = ArrayList<LyricsLine>()
        for (raw in text.lineSequence()) {
            val offset = OFFSET.matchEntire(raw)
            if (offset != null) {
                offsetMs = offset.groupValues[1].toLongOrNull() ?: 0L
                continue
            }
            val match = LEADING_STAMPS.matchEntire(raw) ?: continue
            val (text, timed) = words(match.groupValues[2])
            val starts = STAMP.findAll(match.groupValues[1]).mapNotNull { millis(it) }.toList()
            val first = starts.minOrNull() ?: continue
            for (start in starts) {
                // A repeated line (several stamps) repeats its words' timing from each start.
                val shift = start - first
                lines += LyricsLine(start, text, words = timed.map { it.copy(startMs = it.startMs + shift) })
            }
        }
        // In LRC a positive offset makes lyrics appear sooner.
        return lines
            .map { line ->
                line.copy(
                    startMs = (line.startMs - offsetMs).coerceAtLeast(0),
                    words = line.words.map { it.copy(startMs = (it.startMs - offsetMs).coerceAtLeast(0)) },
                )
            }
            .sortedBy { it.startMs }
            .let(::collapseLeadingGaps)
    }

    /**
     * A line's text without word stamps, and its words with their stamps — empty when the line has
     * none. A stamp may start a syllable inside a word ("<00:12.00>Hel<00:12.30>lo"); a word takes
     * the stamp of its first character.
     */
    private fun words(body: String): Pair<String, List<LyricsWord>> {
        val stamps = WORD_STAMP.findAll(body).toList()
        if (stamps.isEmpty()) return body.replace(SPACES, " ").trim() to emptyList()
        val text = StringBuilder()
        val timeAt = ArrayList<Long?>() // the stamp in force at each character of text
        var current: Long? = null
        var cursor = 0
        for (stamp in stamps) {
            appendFragment(body.substring(cursor, stamp.range.first), current, text, timeAt)
            current = wordMillis(stamp) ?: current
            cursor = stamp.range.last + 1
        }
        appendFragment(body.substring(cursor), current, text, timeAt)
        val clean = text.toString()
        val words = ArrayList<LyricsWord>()
        var i = 0
        while (i < clean.length) {
            if (clean[i].isWhitespace()) { i++; continue }
            val start = i
            while (i < clean.length && !clean[i].isWhitespace()) i++
            val time = timeAt[start] ?: continue
            words += LyricsWord(time, clean.substring(start, i))
        }
        val trimmed = clean.replace(SPACES, " ").trim()
        // Words without a stamp anywhere before them can't be timed: keep the provider's words only
        // when they cover the whole line.
        return trimmed to if (words.size == trimmed.split(' ').count { it.isNotEmpty() }) words else emptyList()
    }

    private fun appendFragment(fragment: String, time: Long?, text: StringBuilder, timeAt: MutableList<Long?>) {
        for (c in fragment) {
            text.append(c)
            timeAt += time
        }
    }

    private fun wordMillis(stamp: MatchResult): Long? {
        val (m, s, f) = stamp.destructured
        val minutes = m.toLongOrNull() ?: return null
        val seconds = s.toLongOrNull()?.takeIf { it < 60 } ?: return null
        val fractionMs = when (f.length) {
            0 -> 0L
            1 -> f.toLong() * 100
            2 -> f.toLong() * 10
            else -> f.toLong()
        }
        return (minutes * 60 + seconds) * 1000 + fractionMs
    }

    /** Synced lyrics from LRC, or null when nothing in it has words. */
    fun synced(text: String?): Lyrics.Synced? = parse(text).takeIf { lines -> lines.any { it.text.isNotEmpty() } }?.let(Lyrics::Synced)

    /** Plain lyrics: the lines as written, without blank runs at the ends. */
    fun plain(text: String?): Lyrics.Plain? {
        val lines = text?.lines()?.map { it.trim() }?.dropWhile { it.isEmpty() }?.dropLastWhile { it.isEmpty() }.orEmpty()
        return lines.takeIf { l -> l.any { it.isNotEmpty() } }?.let(Lyrics::Plain)
    }

    private fun millis(stamp: MatchResult): Long? {
        val (a, b, third, dotted) = stamp.destructured
        // "[mm:ss:xx]" (an old way of writing hundredths) is far more common than an hour field:
        // a third colon group is hours only when a fraction follows it ("[h:mm:ss.xx]").
        val c = if (dotted.isEmpty()) "" else third
        val fraction = if (dotted.isEmpty()) third else dotted
        val first = a.toLongOrNull() ?: return null
        val second = b.toLongOrNull() ?: return null
        val (minutes, seconds) = if (c.isNotEmpty()) {
            // h:mm:ss
            val third = c.toLongOrNull() ?: return null
            if (second >= 60 || third >= 60) return null
            (first * 60 + second) to third
        } else {
            if (second >= 60) return null
            first to second
        }
        val fractionMs = when (fraction.length) {
            0 -> 0L
            1 -> fraction.toLong() * 100
            2 -> fraction.toLong() * 10
            else -> fraction.toLong()
        }
        return (minutes * 60 + seconds) * 1000 + fractionMs
    }

    /** Several empty lines in a row say no more than one. */
    private fun collapseLeadingGaps(lines: List<LyricsLine>): List<LyricsLine> {
        val out = ArrayList<LyricsLine>(lines.size)
        for (line in lines) {
            if (line.text.isEmpty() && out.lastOrNull()?.text?.isEmpty() == true) continue
            out += line
        }
        return out
    }
}

/** Which line is being sung, and when the next one starts (LYRICS_ARCHITECTURE.md §5). */
object LyricsTiming {
    /** The index of the line sung at [positionMs]; -1 before the first line starts. */
    fun activeIndex(lines: List<LyricsLine>, positionMs: Long): Int {
        var low = 0
        var high = lines.lastIndex
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].startMs <= positionMs) {
                found = mid
                low = mid + 1
            } else high = mid - 1
        }
        return found
    }

    /**
     * The line to show at [positionMs]: the one being sung, or — before the first line starts — the
     * first line, waiting.
     */
    fun displayIndex(lines: List<LyricsLine>, positionMs: Long): Int =
        if (lines.isEmpty()) -1 else activeIndex(lines, positionMs).coerceAtLeast(0)

    /** When the line after [positionMs]'s line starts, or null after the last. */
    fun nextChangeMs(lines: List<LyricsLine>, positionMs: Long): Long? =
        lines.getOrNull(activeIndex(lines, positionMs) + 1)?.startMs
}
