package app.podium.core.lyrics

/**
 * When each word of a synced line appears (LYRICS_ARCHITECTURE.md §5.1): the words come in as they
 * are sung, not the whole line at once.
 *
 * - **Timed by the provider** (enhanced LRC word stamps, or words in LRCLIB's `lyricsfile`): those
 *   times, kept in order and inside the line.
 * - **Timed by the line only** (most lyrics): an estimate inside the line's real window — from its
 *   start to its end (or the next line's start) — shared out by syllables, with a short pause after
 *   punctuation, and never slower than [MAX_MS_PER_SYLLABLE] so a line followed by an instrumental
 *   break doesn't trickle its last words across the break. The first word always appears exactly
 *   when the line starts; only the order and pace inside the line are estimated.
 */
object WordTiming {

    /** Singing rarely spends longer on a syllable; beyond this the line just waits fully shown. */
    const val MAX_MS_PER_SYLLABLE = 380L

    /** Without an end time, the words are all in by this share of the gap to the next line. */
    private const val SHARE_OF_GAP = 0.85

    /** A line with nothing after it gets this much per syllable. */
    private const val LAST_LINE_MS_PER_SYLLABLE = 300L

    /** The words of [line] as the screen shows them (split on spaces). */
    fun words(line: LyricsLine): List<String> = line.text.trim().split(SPACE_RUN).filter { it.isNotEmpty() }

    /** Whether [line]'s word times are estimated rather than the provider's. */
    fun isEstimated(line: LyricsLine): Boolean = line.words.size != words(line).size

    /** When each word of [line] appears; [nextStartMs] is the next line's start, if any. */
    fun starts(line: LyricsLine, nextStartMs: Long?): List<Long> {
        val words = words(line)
        if (words.isEmpty()) return emptyList()
        if (!isEstimated(line)) {
            // The provider's times, kept in order and never before the line itself starts.
            var last = line.startMs
            return line.words.map { w -> maxOf(last, w.startMs).also { last = it } }
        }
        val weights = words.mapIndexed { i, word ->
            syllables(word) + if (i > 0 && words[i - 1].last() in PAUSE_AFTER) 0.5 else 0.0
        }
        val total = weights.sum()
        val window = when {
            line.endMs != null && line.endMs > line.startMs -> line.endMs - line.startMs
            nextStartMs != null && nextStartMs > line.startMs -> ((nextStartMs - line.startMs) * SHARE_OF_GAP).toLong()
            else -> (total * LAST_LINE_MS_PER_SYLLABLE).toLong()
        }
        val span = minOf(window.toDouble(), total * MAX_MS_PER_SYLLABLE)
        var before = 0.0
        return weights.map { w ->
            (line.startMs + span * (before / total)).toLong().also { before += w }
        }
    }

    /** How many of the words of [line] have appeared at [positionMs]. */
    fun shown(starts: List<Long>, positionMs: Long): Int = starts.count { it <= positionMs }

    /** When the next word of [line] appears after [positionMs], or null when all have. */
    fun nextWordMs(starts: List<Long>, positionMs: Long): Long? = starts.firstOrNull { it > positionMs }

    /**
     * A rough syllable count: vowel groups (a silent final "e" doesn't count), at least one. Good
     * enough to pace "I" against "everything"; scripts without these vowels count one per word.
     */
    fun syllables(word: String): Int {
        val letters = word.lowercase().filter { it.isLetter() }
        if (letters.isEmpty()) return 1
        var groups = 0
        var inVowel = false
        for (c in letters) {
            val vowel = c in VOWELS
            if (vowel && !inVowel) groups++
            inVowel = vowel
        }
        if (groups > 1 && letters.endsWith("e") && !letters.endsWith("le") && letters.length > 3) groups--
        return groups.coerceAtLeast(1)
    }

    private val SPACE_RUN = Regex("""\s+""")
    private const val VOWELS = "aeiouyàáâãäåæèéêëìíîïòóôõöøùúûüýÿœ"
    private val PAUSE_AFTER = setOf(',', ';', ':', '.', '!', '?', '—', '–')
}

/**
 * LRCLIB's `lyricsfile` (LYRICS_ARCHITECTURE.md §4.1): a small YAML document with, per line, its text,
 * `start_ms`, `end_ms`, and — for word-synced lyrics — its words. Read for the end times (and words)
 * it adds to the LRC; a tolerant reader of just that shape, failing to an empty list.
 */
object LyricsFile {

    private data class Item(var text: String? = null, var start: Long? = null, var end: Long? = null, val words: MutableList<Item> = ArrayList())

    fun parse(text: String?): List<LyricsLine> {
        if (text.isNullOrBlank()) return emptyList()
        val items = ArrayList<Item>()
        var inLines = false
        var lineIndent = -1 // indentation of a line item's "- "
        var wordsKeyIndent = -1 // indentation of the current line's "words:" key, -1 outside a word list
        var current: Item? = null
        var word: Item? = null
        for (raw in text.lineSequence()) {
            if (raw.isBlank()) continue
            val indent = raw.length - raw.trimStart().length
            val body = raw.trim()
            if (!inLines) {
                if (body == "lines:") inLines = true
                continue
            }
            // Another top-level key ends the list.
            if (indent == 0 && !body.startsWith("-")) break
            val dash = body.startsWith("-")
            val entry = if (dash) body.removePrefix("-").trim() else body
            var target: Item?
            when {
                dash && (lineIndent < 0 || indent <= lineIndent) -> {
                    lineIndent = indent
                    current = Item().also { items += it }
                    word = null
                    wordsKeyIndent = -1
                    target = current
                }
                dash && wordsKeyIndent >= 0 && indent >= wordsKeyIndent -> {
                    word = Item().also { current?.words?.add(it) }
                    target = word
                }
                dash -> continue // some other nested list: not read
                wordsKeyIndent >= 0 && indent > wordsKeyIndent && word != null -> target = word
                else -> {
                    // A key of the line itself (also after its word list).
                    if (wordsKeyIndent >= 0 && indent <= wordsKeyIndent) {
                        wordsKeyIndent = -1
                        word = null
                    }
                    target = current
                }
            }
            val key = entry.substringBefore(':', "").trim()
            val value = entry.substringAfter(':', "").trim()
            when (key) {
                "text" -> target?.text = unquote(value)
                "start_ms" -> target?.start = value.toLongOrNull()
                "end_ms" -> target?.end = value.toLongOrNull()
                "words" -> if (target === current) {
                    wordsKeyIndent = indent
                    word = null
                }
            }
        }
        return items.mapNotNull { item ->
            val start = item.start ?: return@mapNotNull null
            LyricsLine(
                startMs = start,
                text = item.text.orEmpty(),
                endMs = item.end?.takeIf { it > start },
                words = item.words.mapNotNull { w -> w.start?.let { LyricsWord(it, w.text.orEmpty().trim()) } }.filter { it.text.isNotEmpty() },
            )
        }.sortedBy { it.startMs }
    }

    /**
     * Adds what [file] knows to [lrc]: each LRC line takes the end time (and words) of the file's
     * line starting at the same moment (within 50 ms) with the same text. Nothing else changes.
     */
    fun enrich(lrc: List<LyricsLine>, file: List<LyricsLine>): List<LyricsLine> {
        if (file.isEmpty()) return lrc
        return lrc.map { line ->
            val match = file.firstOrNull { kotlin.math.abs(it.startMs - line.startMs) <= 50 && it.text.trim() == line.text.trim() }
                ?: return@map line
            val words = if (line.words.isEmpty() && match.words.size == WordTiming.words(line).size) match.words else line.words
            line.copy(endMs = line.endMs ?: match.endMs, words = words)
        }
    }

    private fun unquote(value: String): String = when {
        value.length >= 2 && value.startsWith('\'') && value.endsWith('\'') -> value.substring(1, value.length - 1).replace("''", "'")
        value.length >= 2 && value.startsWith('"') && value.endsWith('"') -> value.substring(1, value.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
        else -> value
    }
}
