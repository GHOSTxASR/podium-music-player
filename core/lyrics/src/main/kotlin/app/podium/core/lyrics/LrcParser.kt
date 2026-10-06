package app.podium.core.lyrics

/**
 * Reads LRC text (LYRICS_ARCHITECTURE.md §4): `[mm:ss.xx] words`, also `[mm:ss]`, `[mm:ss.xxx]`,
 * `[mm:ss:xx]` and `[h:mm:ss.xx]`; several stamps on one line (a repeated chorus); an `[offset:±ms]`
 * tag; metadata tags (`[ar:…]`) ignored; word-level stamps (`<mm:ss.xx>`) removed from the words.
 * Lines without a readable stamp are skipped, never guessed. Malformed input gives an empty list.
 */
object LrcParser {

    private val STAMP = Regex("""\[(\d{1,3}):(\d{1,2})(?::(\d{1,2}))?(?:[.:](\d{1,3}))?]""")
    private val LEADING_STAMPS = Regex("""^\s*((?:\[\d{1,3}:\d{1,2}(?::\d{1,2})?(?:[.:]\d{1,3})?]\s*)+)(.*)$""")
    private val OFFSET = Regex("""^\s*\[offset:\s*([+-]?\d+)\s*]\s*$""", RegexOption.IGNORE_CASE)
    private val WORD_STAMP = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")
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
            val words = match.groupValues[2].replace(WORD_STAMP, "").replace(SPACES, " ").trim()
            STAMP.findAll(match.groupValues[1]).forEach { stamp -> millis(stamp)?.let { lines += LyricsLine(it, words) } }
        }
        // In LRC a positive offset makes lyrics appear sooner.
        return lines
            .map { it.copy(startMs = (it.startMs - offsetMs).coerceAtLeast(0)) }
            .sortedBy { it.startMs }
            .let(::collapseLeadingGaps)
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
