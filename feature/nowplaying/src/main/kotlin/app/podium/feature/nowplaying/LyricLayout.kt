package app.podium.feature.nowplaying

/**
 * How one lyric line fills the display (LYRICS_ARCHITECTURE.md §7): as large as it can be, set in
 * short lines, each line justified edge to edge like an old monochrome player's screen — a single
 * word stands centred. The text is never clipped: a line too long for the largest size gets a
 * smaller one, down to [minFontPx], and below that the whole block shrinks to fit.
 *
 * Pure: [measure] gives a word's width at a font size, so the layout is testable without a screen.
 */
internal object LyricLayout {

    data class Word(val text: String, val x: Float, val width: Float)

    data class Line(val words: List<Word>, val y: Float)

    data class Result(val fontPx: Float, val lineHeightPx: Float, val lines: List<Line>) {
        val height: Float get() = lines.size * lineHeightPx
    }

    fun layout(
        text: String,
        widthPx: Float,
        heightPx: Float,
        maxFontPx: Float,
        minFontPx: Float,
        lineHeightRatio: Float = 1.18f,
        measure: (word: String, fontPx: Float) -> Float,
    ): Result {
        val words = text.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (words.isEmpty() || widthPx <= 0f || heightPx <= 0f) return Result(maxFontPx, maxFontPx * lineHeightRatio, emptyList())
        var size = maxFontPx
        while (true) {
            val attempt = wrap(words, widthPx, size, measure)
            val fits = attempt != null && attempt.size * size * lineHeightRatio <= heightPx
            if (fits) return place(attempt!!, widthPx, heightPx, size, lineHeightRatio)
            if (size <= minFontPx) break
            size = (size * STEP).coerceAtLeast(minFontPx)
        }
        // Even the smallest size doesn't fit: shrink further rather than cut anything off.
        var smaller = minFontPx
        repeat(MAX_SHRINK_STEPS) {
            smaller *= STEP
            val attempt = wrap(words, widthPx, smaller, measure)
            if (attempt != null && attempt.size * smaller * lineHeightRatio <= heightPx) {
                return place(attempt, widthPx, heightPx, smaller, lineHeightRatio)
            }
        }
        val last = wrap(words, widthPx, smaller, measure) ?: words.map { listOf(it to measure(it, smaller)) }
        return place(last, widthPx, heightPx, smaller, lineHeightRatio)
    }

    /** Greedy lines of (word, width); null when a single word is wider than the line. */
    private fun wrap(words: List<String>, widthPx: Float, fontPx: Float, measure: (String, Float) -> Float): List<List<Pair<String, Float>>>? {
        val space = fontPx * SPACE_RATIO
        val lines = ArrayList<List<Pair<String, Float>>>()
        var current = ArrayList<Pair<String, Float>>()
        var used = 0f
        for (word in words) {
            val w = measure(word, fontPx)
            if (w > widthPx) return null
            val needed = if (current.isEmpty()) w else used + space + w
            if (needed <= widthPx) {
                current += word to w
                used = needed
            } else {
                lines += current
                current = arrayListOf(word to w)
                used = w
            }
        }
        if (current.isNotEmpty()) lines += current
        return lines
    }

    private fun place(lines: List<List<Pair<String, Float>>>, widthPx: Float, heightPx: Float, fontPx: Float, ratio: Float): Result {
        val lineHeight = fontPx * ratio
        val top = ((heightPx - lines.size * lineHeight) / 2f).coerceAtLeast(0f)
        val placed = lines.mapIndexed { i, line ->
            val total = line.sumOf { it.second.toDouble() }.toFloat()
            val words = if (line.size == 1) {
                listOf(Word(line[0].first, ((widthPx - total) / 2f).coerceAtLeast(0f), line[0].second))
            } else {
                val gap = (widthPx - total) / (line.size - 1)
                var x = 0f
                line.map { (word, w) -> Word(word, x, w).also { x += w + gap } }
            }
            Line(words, top + i * lineHeight)
        }
        return Result(fontPx, lineHeight, placed)
    }

    private val WHITESPACE = Regex("""\s+""")
    private const val STEP = 0.92f
    private const val SPACE_RATIO = 0.3f
    private const val MAX_SHRINK_STEPS = 24
}
