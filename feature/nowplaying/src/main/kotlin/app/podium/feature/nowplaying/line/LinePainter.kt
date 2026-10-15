package app.podium.feature.nowplaying.line

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
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

    /** The light around the line (D-82): ivory warmed a little toward the gold, like a neon tube's glow. */
    val Halo = lerp(Ivory, Gold, 0.35f)
}

/**
 * Draws a [LineFrame] of a [LineComposition] onto a canvas: only sections near the pen and inside
 * the display, each as the revealed prefix of its prepared segments — a handful of `drawLines`
 * calls on arrays built before — plus the pen. The camera is a canvas transform; everything is
 * clipped to the display. Paints, paths and the glow's gradient are made once; no allocation per
 * frame.
 *
 * The line glows (D-82), softly, like a neon tube or a highlighter's ink: [GLOW_LAYERS] wider, dim
 * strokes under every line, the inner one narrower and brighter so the light falls off, drawn first
 * so they never cover a line. They are opaque mixes toward the paper, not
 * transparency or blur, so crossings and joins never bead and nothing is blurred per frame. With the
 * bass the drawing swells a little about the display's centre and the glow brightens
 * ([LineFrame.swell]).
 *
 * The glow is drawn as platform paths without anti-aliasing, from each route's simpler copy
 * ([InkPath.glowStrokes]), which the GPU draws by itself. Measured on the phone (D-82), the same
 * strokes anti-aliased were rasterised on the render thread as masks and uploaded, about ten a
 * frame. Drawn segment by segment they were thousands of small shapes a frame. Either way the
 * render thread overran its frame. The glow's strokes are dim and wide, so their edges don't show
 * aliasing. A written section's glow is one path made once ([prepare], off the main thread ahead of
 * the pen). For the section being written, finished glow strokes join a growing path, and only the
 * stroke under the pen (at most [InkPath.STROKE_SEGMENTS] segments) is rebuilt each frame.
 */
internal class LinePainter(private val density: Float) {

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    // Not anti-aliased: drawn by the GPU, never rasterised on the render thread (see above).
    private val glowPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        // The pen's glow: one small radial gradient, positioned by translating the canvas.
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
    private val paper = LineInk.Paper.toArgb()
    private val halo = LineInk.Halo.toArgb()

    /** Draw counters for tests and diagnostics (the last frame). */
    var lastLineCalls = 0
        private set
    var lastSegments = 0
        private set
    var lastGlowSegments = 0
        private set

    /** One route's glow as platform paths: each glow stroke, and all of them together. */
    private class GlowPaths(val strokes: Array<Path>, val all: Path, val segments: Int)

    // A few routes' glow paths, least recently used out first (the composition keeps eight routes).
    private val glows = object : LinkedHashMap<InkPath, GlowPaths>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<InkPath, GlowPaths>?): Boolean = size > MAX_ROUTES
    }

    // The section being written: its finished glow strokes so far, and the one under the pen.
    private var growingOf: InkPath? = null
    private var growingStrokes = 0
    private var growingSegments = 0
    private val growing = Path()
    private val underPen = Path()
    private var underPenOn = false

    /** Makes [path]'s glow paths ahead of drawing it; any thread. */
    fun prepare(path: InkPath) {
        if (synchronized(glows) { glows.containsKey(path) }) return
        val built = build(path)
        synchronized(glows) { if (!glows.containsKey(path)) glows[path] = built }
    }

    private fun glowOf(path: InkPath): GlowPaths =
        synchronized(glows) { glows[path] } ?: build(path).also { synchronized(glows) { glows[path] = it } }

    private fun build(p: InkPath): GlowPaths {
        val gs = p.glowStrokes
        var segments = 0
        val strokes = Array(gs.size / 3) { k ->
            segments += gs[k * 3 + 1] - gs[k * 3]
            Path().apply {
                moveTo(p.glowXs[gs[k * 3]], p.glowYs[gs[k * 3]])
                for (i in gs[k * 3] + 1..gs[k * 3 + 1]) lineTo(p.glowXs[i], p.glowYs[i])
            }
        }
        val all = Path()
        for (g in strokes) all.addPath(g)
        return GlowPaths(strokes, all, segments)
    }

    /**
     * Brings the glow of the section being written up to [reveal]: finished glow strokes join the
     * growing path (all of them again after a step back), and the one under the pen is rebuilt.
     */
    private fun advance(path: InkPath, reveal: Float) {
        val glow = glowOf(path)
        val gs = path.glowStrokes
        val gt = path.glowTau
        if (growingOf !== path || (growingStrokes > 0 && gt[gs[(growingStrokes - 1) * 3 + 1]] > reveal)) {
            growingOf = path
            growingStrokes = 0
            growingSegments = 0
            growing.rewind()
        }
        val n = gs.size / 3
        while (growingStrokes < n && gt[gs[growingStrokes * 3 + 1]] <= reveal) {
            growing.addPath(glow.strokes[growingStrokes])
            growingSegments += gs[growingStrokes * 3 + 1] - gs[growingStrokes * 3]
            growingStrokes++
        }
        underPenOn = false
        if (growingStrokes < n) {
            val a = gs[growingStrokes * 3]
            val b = gs[growingStrokes * 3 + 1]
            if (gt[a] < reveal) {
                underPen.rewind()
                underPen.moveTo(path.glowXs[a], path.glowYs[a])
                var i = a + 1
                while (i <= b && gt[i] <= reveal) {
                    underPen.lineTo(path.glowXs[i], path.glowYs[i])
                    i++
                }
                underPen.lineTo(path.xAt(reveal), path.yAt(reveal))
                underPenOn = true
            }
        }
    }

    fun draw(canvas: Canvas, widthPx: Float, heightPx: Float, composition: LineComposition, frame: LineFrame, reduced: Boolean) {
        lastLineCalls = 0
        lastSegments = 0
        lastGlowSegments = 0
        val scale = widthPx / composition.viewWidth
        val viewH = heightPx / scale
        val left = frame.cameraX - composition.viewWidth / 2f
        val top = frame.cameraY - viewH / 2f
        val swell = if (reduced) 0f else frame.swell.coerceIn(0f, 1f)
        canvas.save()
        canvas.clipRect(0f, 0f, widthPx, heightPx)
        if (swell > 0f) {
            val grow = 1f + SWELL_SCALE * swell
            canvas.scale(grow, grow, widthPx / 2f, heightPx / 2f)
        }
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
        val first = maxOf(0, s - LOOKBACK)
        // Which sections are in view: one bit per section, counting back from the pen's.
        var visible = 0
        for (i in first..s) {
            val section = sections[i]
            if (section.maxX < left - margin || section.minX > right + margin || section.maxY < top - margin || section.minY > bottom + margin) continue
            visible = visible or (1 shl (s - i))
        }
        // The section being written, up to the pen (whether or not it is in view: it is where the pen is).
        advance(composition.geometry(s).path, frame.tau)
        // The glow first, outer then inner, so no line is ever under another's glow.
        val boost = 1f + GLOW_SWELL * swell
        for (layer in (if (reduced) GLOW_LAYERS - 1 else 0) until GLOW_LAYERS) {
            for (i in first..s) {
                if (visible and (1 shl (s - i)) == 0) continue
                val phrase = sections[i].kind == SectionKind.PHRASE
                drawGlow(canvas, composition.geometry(i).path, i == s, phrase, if (phrase) i == currentPhrase else i >= s - 1, layer, boost, px)
            }
        }
        for (i in first..s) {
            if (visible and (1 shl (s - i)) == 0) continue
            val geometry = composition.geometry(i)
            val reveal = if (i < s) Float.MAX_VALUE else frame.tau
            drawPath(canvas, geometry.path, reveal, i == currentPhrase, i >= s - 1, px)
            if (i == s || i == s - 1) drawBeads(canvas, geometry, reveal, px)
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
        if (frame.penDown || (frame.tau > 0f && frame.tau < section.length)) drawPen(canvas, geometry.path, frame, reduced, swell, px)
        canvas.restore()
    }

    /**
     * One glow [layer] (0 the outermost) of [path], brightened by [boost]: a written section's whole
     * glow in one path; the section being written ([writing]), its finished glow strokes and the one
     * under the pen.
     */
    private fun drawGlow(canvas: Canvas, path: InkPath, writing: Boolean, phrase: Boolean, lit: Boolean, layer: Int, boost: Float, px: Float) {
        if (path.glowStrokes.isEmpty()) return
        glowPaint.strokeWidth = (if (phrase) LETTER_DP else FLOURISH_DP) * density * px * GLOW_WIDTHS[layer]
        glowPaint.color = mix(paper, halo, GLOW_LIGHT[layer] * boost * (if (lit) 1f else SETTLED_GLOW) * (if (phrase) 1f else FLOURISH_GLOW))
        if (!writing) {
            val glow = glowOf(path)
            canvas.drawPath(glow.all, glowPaint)
            lastLineCalls++
            lastGlowSegments += glow.segments
            return
        }
        if (!growing.isEmpty) {
            canvas.drawPath(growing, glowPaint)
            lastLineCalls++
            lastGlowSegments += growingSegments
        }
        if (underPenOn) {
            canvas.drawPath(underPen, glowPaint)
            lastLineCalls++
        }
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

    private fun drawPen(canvas: Canvas, path: InkPath, frame: LineFrame, reduced: Boolean, swell: Float, px: Float) {
        // The last of the ink behind the pen still catches the light: a gold wet edge.
        if (!reduced && frame.penDown) drawWindow(canvas, path, frame.tau - WET_SPAN, frame.tau, glint, FLOURISH_DP * density * px, null)
        if (!reduced) {
            canvas.save()
            canvas.translate(frame.penX, frame.penY)
            val bloom = 1f + PEN_SWELL * swell
            canvas.scale(px * bloom, px * bloom)
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

        /** Routes whose glow paths are kept: the composition's eight, and some room. */
        private const val MAX_ROUTES = 12

        // The line, a little bolder than a fine pen's (D-82).
        const val LETTER_DP = 2.3f
        const val FLOURISH_DP = 1.8f
        const val HAIR_DP = 1.3f
        const val PEN_DP = 2.7f
        const val GLOW_DP = 12f
        const val BEAD_DP = 2f
        const val GLINT_DP = 5f

        /**
         * The glow around the line: its strokes' widths (times the line's), outermost first, and their
         * light. Two layers, measured on the phone (D-82): as smooth as no glow, where four made
         * frames late.
         */
        const val GLOW_LAYERS = 2
        private val GLOW_WIDTHS = floatArrayOf(4.4f, 2.2f)
        private val GLOW_LIGHT = floatArrayOf(0.055f, 0.14f)

        /** Written phrases glow this much of the phrase being written; the instrumental line this much of the words. */
        const val SETTLED_GLOW = 0.55f
        const val FLOURISH_GLOW = 0.9f

        /** With a strong bass hit: how much the drawing swells, the glow brightens and the pen blooms. */
        const val SWELL_SCALE = 0.014f
        const val GLOW_SWELL = 0.6f
        const val PEN_SWELL = 0.25f

        /** Pen-time spans (em) of the gold accents. */
        const val WET_SPAN = 0.14f
        const val GLINT_SPAN = 0.9f
        const val BEAD_SPAN = 0.7f
        const val SHIMMER_SPAN = 1.1f
        const val SHIMMER_WINDOW = 0.35f
    }
}

/** [from] mixed toward [to] by [k] (0–1), opaque, without allocating. */
internal fun mix(from: Int, to: Int, k: Float): Int {
    val t = k.coerceIn(0f, 1f)
    val r = mixChannel(from shr 16, to shr 16, t)
    val g = mixChannel(from shr 8, to shr 8, t)
    val b = mixChannel(from, to, t)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

private fun mixChannel(from: Int, to: Int, t: Float): Int {
    val a = from and 0xFF
    val b = to and 0xFF
    return (a + (b - a) * t + 0.5f).toInt().coerceIn(0, 255)
}
