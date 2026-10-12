package app.podium.feature.nowplaying.line

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb

/**
 * The Continuous line palette (LYRICS_ARCHITECTURE.md §12.6): a matte black sheet, warm ivory ink,
 * and a muted champagne gold used sparingly. Every ink is opaque — quieter lines are ivory mixed
 * toward the paper, not transparent — so overlapping segments never bead.
 */
internal object LineInk {
    val Paper = Color(0xFF060606)
    val Ivory = Color(0xFFF2EBDC)
    val Gold = Color(0xFFD6B677)

    /** Words already written, and the line between them. */
    val Settled = lerp(Paper, Ivory, 0.5f)
    val Hair = lerp(Paper, Ivory, 0.34f)
    val SettledHair = lerp(Paper, Ivory, 0.2f)
    val Flourish = lerp(Paper, Ivory, 0.76f)
    val SettledFlourish = lerp(Paper, Ivory, 0.36f)
    val Quiet = lerp(Paper, Ivory, 0.62f)

    /** Gold as it catches the ivory line (the shimmer), never pure. */
    val Glint = lerp(Ivory, Gold, 0.82f)
}

/**
 * Draws a [LineFrame] of a [LineComposition] onto a canvas: only sections near the pen and inside
 * the display, each as the revealed prefix of its prepared segments — a handful of `drawLines`
 * calls on arrays built before — plus the pen. Paints and the glow's gradient are made once; no
 * allocation per frame. The camera is a canvas transform; everything is clipped to the display.
 */
internal class LinePainter(private val density: Float) {

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        // The glow: one small radial gradient, positioned by translating the canvas.
        shader = RadialGradient(
            0f, 0f, GLOW_DP * density,
            intArrayOf(LineInk.Gold.copy(alpha = 0.46f).toArgb(), LineInk.Gold.copy(alpha = 0.14f).toArgb(), 0),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    private val ivory = LineInk.Ivory.toArgb()
    private val settled = LineInk.Settled.toArgb()
    private val hair = LineInk.Hair.toArgb()
    private val settledHair = LineInk.SettledHair.toArgb()
    private val flourish = LineInk.Flourish.toArgb()
    private val settledFlourish = LineInk.SettledFlourish.toArgb()
    private val glint = LineInk.Glint.toArgb()
    private val gold = LineInk.Gold.toArgb()

    /** Draw counters for tests and diagnostics (the last frame). */
    var lastLineCalls = 0
        private set
    var lastSegments = 0
        private set

    fun draw(canvas: Canvas, widthPx: Float, heightPx: Float, composition: LineComposition, frame: LineFrame, reduced: Boolean) {
        lastLineCalls = 0
        lastSegments = 0
        val scale = widthPx / composition.viewWidth
        val viewH = heightPx / scale
        val left = frame.cameraX - composition.viewWidth / 2f
        val top = frame.cameraY - viewH / 2f
        canvas.save()
        canvas.clipRect(0f, 0f, widthPx, heightPx)
        canvas.scale(scale, scale)
        canvas.translate(-left, -top)
        val px = 1f / scale // one pixel in em
        val sections = composition.sections
        val s = frame.section
        // The phrase being written (or last written): full ivory; earlier phrases settle.
        var currentPhrase = -1
        for (i in s downTo 0) if (sections[i].kind == SectionKind.PHRASE && (i < s || frame.tau > 0f)) {
            currentPhrase = i
            break
        }
        val right = left + composition.viewWidth
        val bottom = top + viewH
        val margin = 0.2f
        for (i in maxOf(0, s - LOOKBACK)..s) {
            val section = sections[i]
            if (section.maxX < left - margin || section.minX > right + margin || section.maxY < top - margin || section.minY > bottom + margin) continue
            val geometry = composition.geometry(i)
            val reveal = if (i < s) Float.MAX_VALUE else frame.tau
            drawPath(canvas, geometry.path, reveal, i == currentPhrase, i >= s - 1, px)
            if (i == s || i == s - 1) drawBeads(canvas, geometry, if (i < s) Float.MAX_VALUE else frame.tau, px)
        }
        val section = sections[s]
        val geometry = composition.geometry(s)
        if (!reduced) {
            drawGlints(canvas, geometry, frame.tau, px)
            // As the pen leaves a phrase, a brief gold light runs across its last word.
            if (section.kind != SectionKind.PHRASE && s > 0 && sections[s - 1].kind == SectionKind.PHRASE && frame.tau < SHIMMER_SPAN) {
                drawShimmer(canvas, composition.geometry(s - 1), frame.tau / SHIMMER_SPAN, px)
            }
        }
        if (frame.penDown || (frame.tau > 0f && frame.tau < section.length)) drawPen(canvas, geometry.path, frame, reduced, px)
        canvas.restore()
    }

    private fun drawPath(canvas: Canvas, path: InkPath, reveal: Float, current: Boolean, recent: Boolean, px: Float) {
        if (path.count < 2) return
        val full = reveal >= path.length
        val segment = if (full) path.segments else path.segmentAt(reveal)
        val fraction = if (full) 0f else path.fractionIn(segment, reveal)
        val runs = path.runs
        var r = 0
        while (r < runs.size) {
            val start = runs[r]
            val end = runs[r + 1]
            val kind = runs[r + 2].toByte()
            r += 3
            if (start >= segment && !(start == segment && fraction > 0f)) break
            if (kind == Ink.LIFT) continue
            style(kind, current, recent, px)
            val whole = minOf(end, segment) - start
            if (whole > 0) {
                canvas.drawLines(path.lines, start * 4, whole * 4, stroke)
                lastLineCalls++
                lastSegments += whole
            }
            if (segment in start until end && fraction > 0f) {
                val i = segment * 4
                val l = path.lines
                canvas.drawLine(l[i], l[i + 1], l[i] + (l[i + 2] - l[i]) * fraction, l[i + 1] + (l[i + 3] - l[i + 1]) * fraction, stroke)
                lastLineCalls++
            }
        }
    }

    private fun style(kind: Byte, current: Boolean, recent: Boolean, px: Float) {
        when (kind) {
            Ink.LETTER -> {
                stroke.color = if (current) ivory else settled
                stroke.strokeWidth = LETTER_DP * density * px
            }
            Ink.HAIR -> {
                stroke.color = if (current) hair else settledHair
                stroke.strokeWidth = HAIR_DP * density * px
            }
            else -> {
                stroke.color = if (recent) flourish else settledFlourish
                stroke.strokeWidth = FLOURISH_DP * density * px
            }
        }
    }

    /** Beat dots: lit as the pen passes; the latest one gold. */
    private fun drawBeads(canvas: Canvas, geometry: SectionGeometry, reveal: Float, px: Float) {
        val b = geometry.beads
        var latest = -1
        for (i in 0 until b.size / 3) if (b[i * 3] <= reveal) latest = i
        for (i in 0..latest) {
            fill.color = if (i == latest && reveal - b[i * 3] < BEAD_SPAN) gold else flourish
            canvas.drawCircle(b[i * 3 + 1], b[i * 3 + 2], BEAD_DP * density * px, fill)
        }
    }

    /** Where a motif crosses itself, a short gold glint as the pen closes the crossing. */
    private fun drawGlints(canvas: Canvas, geometry: SectionGeometry, reveal: Float, px: Float) {
        val g = geometry.glints
        for (i in 0 until g.size / 3) {
            val age = reveal - g[i * 3]
            if (age < 0f || age > GLINT_SPAN) continue
            val k = 1f - age / GLINT_SPAN
            val r = GLINT_DP * density * px * (0.4f + 0.6f * k)
            stroke.color = glint
            stroke.strokeWidth = HAIR_DP * density * px
            val x = g[i * 3 + 1]
            val y = g[i * 3 + 2]
            canvas.drawLine(x - r, y, x + r, y, stroke)
            canvas.drawLine(x, y - r, x, y + r, stroke)
            fill.color = gold
            canvas.drawCircle(x, y, BEAD_DP * density * px * k, fill)
            lastLineCalls += 2
        }
    }

    /** A short gold window travelling across the phrase's last word, [progress] 0–1. */
    private fun drawShimmer(canvas: Canvas, phrase: SectionGeometry, progress: Float, px: Float) {
        val path = phrase.path
        if (path.count < 2 || phrase.wordTaus.isEmpty()) return
        val from = phrase.wordTaus.last()
        val head = from + (path.length - from + SHIMMER_WINDOW) * progress
        drawWindow(canvas, path, head - SHIMMER_WINDOW, head, glint, LETTER_DP * density * px, Ink.LETTER)
    }

    private fun drawPen(canvas: Canvas, path: InkPath, frame: LineFrame, reduced: Boolean, px: Float) {
        // The last of the ink behind the pen still catches the light: a gold wet edge.
        if (!reduced && frame.penDown) drawWindow(canvas, path, frame.tau - WET_SPAN, frame.tau, glint, FLOURISH_DP * density * px, null)
        if (!reduced) {
            canvas.save()
            canvas.translate(frame.penX, frame.penY)
            canvas.scale(px, px)
            canvas.drawCircle(0f, 0f, GLOW_DP * density, glow)
            canvas.restore()
        }
        fill.color = if (frame.penDown) ivory else flourish
        canvas.drawCircle(frame.penX, frame.penY, PEN_DP * density * px, fill)
        if (!reduced) {
            fill.color = gold
            canvas.drawCircle(frame.penX, frame.penY, PEN_DP * 0.55f * density * px, fill)
        }
    }

    /** The inked segments of [path] between pen-times [from] and [to], in [color]. */
    private fun drawWindow(canvas: Canvas, path: InkPath, from: Float, to: Float, color: Int, width: Float, only: Byte?) {
        if (path.count < 2 || to <= 0f) return
        stroke.color = color
        stroke.strokeWidth = width
        val a = path.segmentAt(from.coerceAtLeast(0f))
        val b = path.segmentAt(to.coerceAtMost(path.length))
        val l = path.lines
        for (i in a..b) {
            val kind = path.kinds[i]
            if (kind == Ink.LIFT || (only != null && kind != only)) continue
            val f0 = if (i == a) path.fractionIn(i, from) else 0f
            val f1 = if (i == b) path.fractionIn(i, to) else 1f
            if (f1 <= f0) continue
            val k = i * 4
            val dx = l[k + 2] - l[k]
            val dy = l[k + 3] - l[k + 1]
            canvas.drawLine(l[k] + dx * f0, l[k + 1] + dy * f0, l[k] + dx * f1, l[k + 1] + dy * f1, stroke)
            lastLineCalls++
        }
    }

    companion object {
        /** Sections before the pen's that may still be in view. */
        const val LOOKBACK = 4

        const val LETTER_DP = 1.5f
        const val FLOURISH_DP = 1.15f
        const val HAIR_DP = 0.85f
        const val PEN_DP = 1.9f
        const val GLOW_DP = 9f
        const val BEAD_DP = 1.5f
        const val GLINT_DP = 4.5f

        /** Pen-time spans (em) of the gold accents. */
        const val WET_SPAN = 0.14f
        const val GLINT_SPAN = 0.9f
        const val BEAD_SPAN = 0.7f
        const val SHIMMER_SPAN = 1.1f
        const val SHIMMER_WINDOW = 0.35f
    }
}
