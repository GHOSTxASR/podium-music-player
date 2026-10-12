package app.podium.feature.nowplaying.line

/** A glyph placed on the paper: its box's left edge at [x], its baseline at [baseline], at [scale]. */
internal class PlacedGlyph(val glyph: Glyph, val x: Float, val baseline: Float, val scale: Float) {
    val entryX: Float get() = x + glyph.entryX * scale
    val entryY: Float get() = baseline + glyph.entryY * scale
    val exitX: Float get() = x + glyph.exitPointX * scale
    val exitY: Float get() = baseline + glyph.exitPointY * scale
}

/** A word of a phrase: its glyphs (possibly none, when nothing in it can be drawn) and its row. */
internal class PlacedWord(val glyphs: List<PlacedGlyph>, val row: Int)

/**
 * A phrase set in the hand (LYRICS_ARCHITECTURE.md §12.2): words in rows no wider than the space
 * allowed, each row starting a little further right than the one above (the composition leans
 * down-right), at the largest [scale] that fits. Bounds include ascenders and descenders.
 */
internal class PhraseLayout(
    val words: List<PlacedWord>,
    val scale: Float,
    val rows: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val first: PlacedGlyph? get() = words.firstNotNullOfOrNull { it.glyphs.firstOrNull() }
    val last: PlacedGlyph? get() = words.lastOrNull { it.glyphs.isNotEmpty() }?.glyphs?.last()
}

/** Sets phrases in the hand. Pure: [glyphOf] supplies glyphs, so it is testable without a screen. */
internal object LinePhraseSetter {

    /** Distance between rows, in em at scale 1 (ascenders and descenders never touch). */
    const val ROW_PITCH = 1.42f

    /** Each row starts this much further right than the one above, at scale 1. */
    const val ROW_INDENT = 0.22f

    /** The smallest scale a long phrase is set at; below this it may overflow and the camera follows. */
    const val MIN_SCALE = 0.42f

    private const val STEP = 0.9f
    private val SPACES = Regex("""\s+""")

    /** The words of [text] as the timing splits them ([app.podium.core.lyrics.WordTiming.words]). */
    fun words(text: String): List<String> = text.trim().split(SPACES).filter { it.isNotEmpty() }

    fun layout(text: String, left: Float, top: Float, maxWidth: Float, maxHeight: Float, glyphOf: (Int) -> Glyph?): PhraseLayout {
        val words = words(text).map { word ->
            val glyphs = ArrayList<Glyph>()
            var i = 0
            while (i < word.length) {
                val cp = word.codePointAt(i)
                glyphOf(cp)?.let(glyphs::add)
                i += Character.charCount(cp)
            }
            glyphs
        }
        var scale = 1f
        var rows: List<List<Int>>
        while (true) {
            rows = wrap(words, maxWidth, scale)
            val height = blockHeight(rows.size, scale)
            val widest = rows.maxOfOrNull { row -> rowWidth(words, row, scale) } ?: 0f
            if ((height <= maxHeight && widest <= maxWidth) || scale <= MIN_SCALE) break
            scale = (scale * STEP).coerceAtLeast(MIN_SCALE)
        }
        val placed = ArrayList<PlacedWord>(words.size)
        var right = left
        rows.forEachIndexed { r, row ->
            var x = left + r * ROW_INDENT * scale
            val baseline = top + LineHand.ASCENT * scale + r * ROW_PITCH * scale
            row.forEachIndexed { k, w ->
                if (k > 0) x += LineHand.SPACE * scale
                val glyphs = words[w].mapIndexed { g, glyph ->
                    if (g > 0) x += LineHand.LETTER_GAP * scale
                    PlacedGlyph(glyph, x, baseline, scale).also { x += glyph.advance * scale }
                }
                placed += PlacedWord(glyphs, r)
            }
            right = maxOf(right, x)
        }
        return PhraseLayout(placed, scale, rows.size, left, top, right, top + blockHeight(rows.size, scale))
    }

    private fun blockHeight(rows: Int, scale: Float): Float =
        if (rows == 0) 0f else (LineHand.ASCENT + (rows - 1) * ROW_PITCH + LineHand.DESCENT) * scale

    private fun wordWidth(glyphs: List<Glyph>, scale: Float): Float =
        (glyphs.sumOf { it.advance.toDouble() }.toFloat() + LineHand.LETTER_GAP * (glyphs.size - 1).coerceAtLeast(0)) * scale

    private fun rowWidth(words: List<List<Glyph>>, row: List<Int>, scale: Float): Float =
        row.sumOf { wordWidth(words[it], scale).toDouble() }.toFloat() + LineHand.SPACE * scale * (row.size - 1).coerceAtLeast(0)

    /** Greedy rows of word indices; each row narrower by its indent. A word too wide sits alone. */
    private fun wrap(words: List<List<Glyph>>, maxWidth: Float, scale: Float): List<List<Int>> {
        val rows = ArrayList<List<Int>>()
        var current = ArrayList<Int>()
        var used = 0f
        for (i in words.indices) {
            val w = wordWidth(words[i], scale)
            val limit = maxWidth - rows.size * ROW_INDENT * scale
            val needed = if (current.isEmpty()) w else used + LineHand.SPACE * scale + w
            if (current.isEmpty() || needed <= limit) {
                current += i
                used = needed
            } else {
                rows += current
                current = arrayListOf(i)
                used = w
            }
        }
        if (current.isNotEmpty()) rows += current
        return rows
    }
}
