package app.podium.feature.nowplaying.line

import java.text.Normalizer
import kotlin.math.hypot

/** One entry of the hand's table ([LINE_HAND_GLYPHS]). */
internal class GlyphSource(val char: Char, val advance: Int, val arrivalDegrees: Int, val path: String)

/** A combining accent ([LINE_HAND_MARKS]): drawn above the letter, or hanging from its baseline. */
internal class MarkSource(val char: Char, val above: Boolean, val path: String)

internal fun glyph(char: Char, advance: Int, arrivalDegrees: Int, path: String) = GlyphSource(char, advance, arrivalDegrees, path)

internal fun mark(char: Char, above: Boolean, path: String) = MarkSource(char, above, path)

/** One pen stroke: a polyline of (x, y) pairs in em, y down. A [mark] is drawn after the glyph's exit. */
internal class Stroke(val points: FloatArray, val mark: Boolean) {
    val pointCount: Int get() = points.size / 2
}

/**
 * A character the pen can write, in em (1 em = the 100-unit grid; x-height 0.5), y down, its
 * baseline at y 0 and its box starting at x 0. [arrivalX]/[arrivalY] is the direction the pen
 * travels into the first point; [exitX]/[exitY] the direction it leaves the end of
 * [exitStroke]. [joins]: letters are joined by the pen; punctuation, numerals and traced outlines
 * are reached with a lift. [traced]: an outline taken from a system font, for a character the hand
 * doesn't write ([Glyph.traced]).
 */
internal class Glyph(
    val advance: Float,
    val strokes: List<Stroke>,
    val exitStroke: Int,
    val arrivalX: Float,
    val arrivalY: Float,
    val exitX: Float,
    val exitY: Float,
    val joins: Boolean,
    val traced: Boolean = false,
) {
    val entryX: Float get() = strokes[0].points[0]
    val entryY: Float get() = strokes[0].points[1]
    val exitPointX: Float get() = strokes[exitStroke].points.let { it[it.size - 2] }
    val exitPointY: Float get() = strokes[exitStroke].points.let { it[it.size - 1] }

    companion object {
        /**
         * A character the hand doesn't write, from a system font's outline (the screen supplies
         * [contours], each a closed polyline in em with the baseline at y 0). The pen traces the
         * outline, lifting between contours, so it is still drawn as line, never filled.
         */
        fun traced(contours: List<FloatArray>, advance: Float): Glyph? {
            val strokes = contours.filter { it.size >= 4 }.map { Stroke(it, mark = false) }
            if (strokes.isEmpty()) return null
            return Glyph(advance, strokes, strokes.lastIndex, 1f, 0f, 1f, 0f, joins = false, traced = true)
        }
    }
}

/**
 * The Continuous line hand (LYRICS_ARCHITECTURE.md §12): the glyphs of [LINE_HAND_GLYPHS], parsed
 * and flattened once, slanted a little like handwriting, and cached. Accented letters are composed
 * from their canonical decomposition (the base letter and its accents' strokes), so "é", "ñ" and
 * "ü" are written by the same pen; anything else returns null and the screen traces a system
 * font's outline instead ([Glyph.traced]).
 */
internal object LineHand {

    /** The grid's units in an em. */
    private const val UNIT = 100f

    /** The hand leans right like handwriting: x moves this far for each unit of height. */
    private const val SLANT = 0.16f

    /** Letters are drawn a little wider than the grid, so the script breathes. */
    private const val WIDTH = 1.08f

    const val X_HEIGHT = 0.5f
    const val ASCENT = 1.0f
    const val DESCENT = 0.45f

    /** Room between two letters' boxes, which the joining connector spans. */
    const val LETTER_GAP = 0.05f

    /** A word space. */
    const val SPACE = 0.26f

    private val sources: Map<Char, GlyphSource> by lazy { LINE_HAND_GLYPHS.associateBy { it.char } }
    private val marks: Map<Char, MarkSource> by lazy { LINE_HAND_MARKS.associateBy { it.char } }
    private val cache = HashMap<Int, Glyph?>()

    /** The characters written directly (before accents). */
    val characters: Set<Char> get() = sources.keys

    /** The glyph for [codePoint], or null when the hand doesn't write it. */
    fun glyph(codePoint: Int): Glyph? = synchronized(cache) {
        if (cache.containsKey(codePoint)) cache[codePoint] else build(codePoint).also { cache[codePoint] = it }
    }

    private fun build(codePoint: Int): Glyph? {
        if (Character.isBmpCodePoint(codePoint)) sources[codePoint.toChar()]?.let { return compose(it, emptyList()) }
        // An accented letter: its base letter and the accents' strokes.
        val decomposed = Normalizer.normalize(String(Character.toChars(codePoint)), Normalizer.Form.NFD)
        if (decomposed.length < 2) return null
        val base = sources[decomposed[0]] ?: return null
        val accents = decomposed.drop(1).map { marks[it] ?: return null }
        return compose(base, accents)
    }

    private fun compose(source: GlyphSource, accents: List<MarkSource>): Glyph {
        val parsed = parsePath(source.path)
        // An accent above an i or j replaces its dot.
        val dotless = accents.any { it.above } && (source.char == 'i' || source.char == 'j')
        val strokes = if (dotless) parsed.filterNot { it.mark } else parsed
        val all = ArrayList(strokes)
        if (accents.isNotEmpty()) {
            val body = strokes.filterNot { it.mark }
            var minX = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var top = if (source.char.isUpperCase()) 90f else 50f
            for (s in body) for (i in 0 until s.points.size step 2) {
                minX = minOf(minX, s.points[i])
                maxX = maxOf(maxX, s.points[i])
                if (s.points[i + 1] > top && !source.char.isLowerCase()) top = s.points[i + 1]
            }
            val centre = (minX + maxX) / 2f
            var above = top + 4f
            for (accent in accents) {
                val dy = if (accent.above) above else 0f
                for (s in parsePath(accent.path)) {
                    val p = s.points
                    for (i in p.indices step 2) {
                        p[i] += centre
                        p[i + 1] += dy
                    }
                    all += GridStroke(p, mark = true, s.lastControlX + centre, s.lastControlY + dy)
                }
                if (accent.above) above += 12f
            }
        }
        val exit = all.indexOfLast { !it.mark }
        val exitStroke = all[exit]
        val ex = exitStroke.points[exitStroke.points.size - 2] - exitStroke.lastControlX
        val ey = exitStroke.points[exitStroke.points.size - 1] - exitStroke.lastControlY
        val angle = Math.toRadians(source.arrivalDegrees.toDouble())
        val (ax, ay) = direction(kotlin.math.cos(angle).toFloat(), kotlin.math.sin(angle).toFloat())
        val (tx, ty) = if (ex == 0f && ey == 0f) 1f to 0f else direction(ex, ey)
        return Glyph(
            advance = source.advance * WIDTH / UNIT,
            strokes = all.map { Stroke(transform(it.points), it.mark) },
            exitStroke = exit,
            arrivalX = ax,
            arrivalY = ay,
            exitX = tx,
            exitY = ty,
            joins = source.char.isLetter(),
        )
    }

    /** A grid-space (y up) direction as an em-space (y down) unit vector, slanted like the letters. */
    private fun direction(x: Float, y: Float): Pair<Float, Float> {
        val tx = x * WIDTH + y * SLANT
        val ty = -y
        val length = hypot(tx, ty).takeIf { it > 0f } ?: return 1f to 0f
        return tx / length to ty / length
    }

    private fun transform(grid: FloatArray): FloatArray = FloatArray(grid.size).also { out ->
        for (i in grid.indices step 2) {
            val x = grid[i]
            val y = grid[i + 1]
            out[i] = (x * WIDTH + y * SLANT) / UNIT
            out[i + 1] = -y / UNIT
        }
    }
}

/** A stroke in grid units (y up) with the last control point, for its exit direction. */
internal class GridStroke(val points: FloatArray, val mark: Boolean, val lastControlX: Float, val lastControlY: Float)

private val PATH_TOKEN = Regex("""[LQC]|-?\d+(?:\.\d+)?""")

/** Parses a path in the table's small language (see [LINE_HAND_GLYPHS]) into flattened grid-unit strokes. */
internal fun parsePath(path: String, tolerance: Float = PATH_TOLERANCE): List<GridStroke> = path.split('|').map { part ->
    val text = part.trim()
    val mark = text.startsWith('*')
    val tokens = PATH_TOKEN.findAll(text.removePrefix("*")).map { it.value }.toList()
    var i = 0
    fun number() = tokens[i++].toFloat()
    val out = FloatList()
    var x = number()
    var y = number()
    out.add(x, y)
    var cx = x
    var cy = y
    while (i < tokens.size) {
        when (tokens[i++]) {
            "L" -> {
                cx = x
                cy = y
                x = number()
                y = number()
                out.add(x, y)
            }
            "Q" -> {
                val qx = number()
                val qy = number()
                val nx = number()
                val ny = number()
                val c1x = x + 2f / 3f * (qx - x)
                val c1y = y + 2f / 3f * (qy - y)
                val c2x = nx + 2f / 3f * (qx - nx)
                val c2y = ny + 2f / 3f * (qy - ny)
                cubic(out, x, y, c1x, c1y, c2x, c2y, nx, ny, tolerance)
                cx = c2x
                cy = c2y
                x = nx
                y = ny
            }
            "C" -> {
                val c1x = number()
                val c1y = number()
                val c2x = number()
                val c2y = number()
                val nx = number()
                val ny = number()
                cubic(out, x, y, c1x, c1y, c2x, c2y, nx, ny, tolerance)
                cx = c2x
                cy = c2y
                x = nx
                y = ny
            }
            else -> error("bad path: $path")
        }
    }
    GridStroke(out.toArray(), mark, cx, cy)
}

/** Curves are flattened to within this many grid units (well under a pixel on the display). */
internal const val PATH_TOLERANCE = 0.3f

/** A growable list of float pairs without boxing. */
internal class FloatList(capacity: Int = 64) {
    private var data = FloatArray(capacity)
    var size = 0
        private set

    fun add(x: Float, y: Float) {
        if (size + 2 > data.size) data = data.copyOf(maxOf(data.size * 2, size + 2))
        data[size++] = x
        data[size++] = y
    }

    fun add(v: Float) {
        if (size + 1 > data.size) data = data.copyOf(maxOf(data.size * 2, size + 1))
        data[size++] = v
    }

    fun clear() {
        size = 0
    }

    /** The backing array; valid up to [size]. */
    val raw: FloatArray get() = data

    fun toArray(): FloatArray = data.copyOf(size)
}

/**
 * Appends the cubic Bézier from (x0, y0) to (x3, y3) to [out] (not its first point), subdivided
 * until each piece is flat to within [tolerance].
 */
internal fun cubic(
    out: FloatList,
    x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float,
    tolerance: Float,
    depth: Int = 0,
) {
    val ux = 3f * x1 - 2f * x0 - x3
    val uy = 3f * y1 - 2f * y0 - y3
    val vx = 3f * x2 - 2f * x3 - x0
    val vy = 3f * y2 - 2f * y3 - y0
    val flat = maxOf(ux * ux, vx * vx) + maxOf(uy * uy, vy * vy) <= 16f * tolerance * tolerance
    if (flat || depth >= 10) {
        out.add(x3, y3)
        return
    }
    val x01 = (x0 + x1) / 2f
    val y01 = (y0 + y1) / 2f
    val x12 = (x1 + x2) / 2f
    val y12 = (y1 + y2) / 2f
    val x23 = (x2 + x3) / 2f
    val y23 = (y2 + y3) / 2f
    val xa = (x01 + x12) / 2f
    val ya = (y01 + y12) / 2f
    val xb = (x12 + x23) / 2f
    val yb = (y12 + y23) / 2f
    val xm = (xa + xb) / 2f
    val ym = (ya + yb) / 2f
    cubic(out, x0, y0, x01, y01, xa, ya, xm, ym, tolerance, depth + 1)
    cubic(out, xm, ym, xb, yb, x23, y23, x3, y3, tolerance, depth + 1)
}
