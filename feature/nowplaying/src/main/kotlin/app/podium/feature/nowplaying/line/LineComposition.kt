package app.podium.feature.nowplaying.line

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/** What a section of the composition is. */
internal enum class SectionKind {
    /** Before the first phrase: the pen arrives (and draws, if the introduction is long). */
    LEAD_IN,

    /** A lyric phrase, written. */
    PHRASE,

    /** Between two phrases: the connector, and instrumental line art when the gap is long. */
    BETWEEN,

    /** After the last phrase, or a whole song without words: instrumental line art to the end. */
    CODA,
}

/** Where the pen is and which way it is going (em, y down; the direction a unit vector). */
internal class PenPoint(val x: Float, val y: Float, val dx: Float, val dy: Float)

/** A motif placed on the paper: its entry at ([x], [y]), drawn at [scale]. */
internal class MotifPlacement(val motif: Motif, val x: Float, val y: Float, val scale: Float)

/**
 * A section's drawing, rebuilt on demand from its plan (identically, every time): the route, the
 * pen-time at which each word begins (phrases), and the beads and glints of its motifs as
 * (pen-time, x, y) triples.
 */
internal class SectionGeometry(val path: InkPath, val wordTaus: FloatArray, val beads: FloatArray, val glints: FloatArray)

/**
 * One stretch of the composition, with the time it covers ([startMs] to [endMs]) and how its
 * pen-time is spent over that time ([keyTimes] → [keyFractions] of [length]). [fromX]/[fromY] and
 * [toX]/[toY] are where the camera looks as the section begins and ends (the same point for a
 * phrase); [camera] is the pen's route smoothed, which the camera leans toward in between.
 */
internal class Section(
    val index: Int,
    val kind: SectionKind,
    val startMs: Long,
    val endMs: Long,
    /** The phrase written ([SectionKind.PHRASE]) or led into (lead-in, between); -1 otherwise. */
    val phrase: Int,
    val keyTimes: LongArray,
    val keyFractions: FloatArray,
    val length: Float,
    val minX: Float,
    val minY: Float,
    val maxX: Float,
    val maxY: Float,
    val fromX: Float,
    val fromY: Float,
    val toX: Float,
    val toY: Float,
    val camera: FloatArray,
    val hasMotifs: Boolean,
    internal val recipe: () -> SectionGeometry,
)

/** The state of the drawing at one cursor position ([LineComposition.frameAt]); reused between frames. */
internal class LineFrame {
    var section = 0
    var tau = 0f
    var penX = 0f
    var penY = 0f
    var penDown = false
    var cameraX = 0f
    var cameraY = 0f

    fun copyFrom(other: LineFrame) {
        section = other.section
        tau = other.tau
        penX = other.penX
        penY = other.penY
        penDown = other.penDown
        cameraX = other.cameraX
        cameraY = other.cameraY
    }

    override fun equals(other: Any?): Boolean = other is LineFrame && section == other.section && tau == other.tau &&
        penX == other.penX && penY == other.penY && penDown == other.penDown && cameraX == other.cameraX && cameraY == other.cameraY

    override fun hashCode(): Int = (section * 31 + tau.hashCode()) * 31 + penX.hashCode()

    override fun toString() = "LineFrame(section=$section, tau=$tau, pen=($penX, $penY, down=$penDown), camera=($cameraX, $cameraY))"
}

/**
 * The Continuous line composition (LYRICS_ARCHITECTURE.md §12): the whole song as one drawing that
 * the display looks into. Phrases are set down the paper on a diagonal — each a little right of and
 * below the last — and the line runs from each phrase's last letter to the next phrase's first,
 * through instrumental motifs when the gap is long.
 *
 * - **Deterministic.** Built only from the [script], the track's [seed] and the display's shape:
 *   the same song at the same moment is always the same drawing, forwards or backwards.
 * - **A function of the cursor.** [cursorAt] maps a playback position to a cursor (section index
 *   plus the fraction of its pen-time), [frameAt] maps a cursor to the pen and the camera. Seeking,
 *   rewinding and resuming are just other cursors.
 * - **Cheap to keep.** The plan (placements, timing, camera anchors) is built once; a section's
 *   route is rebuilt from its plan when it is drawn and only a few are kept ([geometry]), so memory
 *   doesn't grow with the song.
 */
internal class LineComposition(
    val script: LineScript,
    val seed: Long,
    /** The display's height over its width. */
    aspect: Float,
    private val glyphOf: (Int) -> Glyph?,
) {
    /** The display's size in em: the hand's x-height is a fourteenth or so of its width. */
    val viewWidth: Float = VIEW_WIDTH
    val viewHeight: Float = VIEW_WIDTH * aspect.coerceIn(0.4f, 3f)

    val sections: List<Section>

    /** For each phrase, the index of its section. */
    private val phraseSections: IntArray

    // A few sections' routes, least recently used out first; arrays, so a lookup allocates nothing.
    private val cacheKeys = IntArray(CACHE_SECTIONS) { -1 }
    private val cacheValues = arrayOfNulls<SectionGeometry>(CACHE_SECTIONS)
    private val cacheUsed = LongArray(CACHE_SECTIONS)
    private var cacheClock = 0L

    init {
        val built = ArrayList<Section>()
        val phraseIndex = IntArray(script.phrases.size)
        val songEnd = script.songEndMs ?: ((script.phrases.lastOrNull()?.endMs ?: 0L) + if (script.phrases.isEmpty()) OPEN_SONG_MS else LineScript.OPEN_OUTRO_MS)
        var prevExit: PenPoint? = null
        var prevLeft = 0f
        var prevBottom = 0f
        var prevAnchorX = 0f
        var prevAnchorY = 0f
        var hasPrev = false
        var lastEnd = 0L
        script.phrases.forEachIndexed { p, phrase ->
            // The line leading into this phrase: from the last phrase (or the pen's arrival).
            val start = prevExit ?: PenPoint(0f, 0f, 0.94f, 0.34f)
            val duration = (phrase.startMs - lastEnd).coerceAtLeast(0)
            val chain = chain(start, motifs(built.size, duration, hasPrev))
            var left = if (hasPrev) prevLeft + viewWidth * DIAG_X else start.x + LEAD_X
            var top = if (hasPrev) prevBottom + GAP_Y else start.y + LEAD_Y
            if (chain.placements.isNotEmpty()) {
                // After instrumental line art the phrase starts just ahead of it, down and to the
                // right, so the line runs out of the last motif straight into the first letter.
                left = chain.endX + CHAIN_LEAD_X
                top = chain.endY + CHAIN_LEAD_Y
            }
            val layout = LinePhraseSetter.layout(phrase.text, left, top, viewWidth * MAX_ROW, viewHeight * MAX_BLOCK, glyphOf)
            val anchorX = (layout.left + layout.right) / 2f
            val anchorY = (layout.top + layout.bottom) / 2f
            val entry = layout.first?.let { PenPoint(it.entryX, it.entryY, it.glyph.arrivalX, it.glyph.arrivalY) }
                ?: PenPoint(layout.left, layout.top + LineHand.ASCENT, 1f, 0f)
            val fromX = if (hasPrev) prevAnchorX else start.x + viewWidth * 0.36f
            val fromY = if (hasPrev) prevAnchorY else start.y + viewHeight * 0.3f
            // A return to the left runs through the clear space between the two phrases.
            val gapY = if (hasPrev) (prevBottom + layout.top) / 2f else layout.top - GAP_Y / 2f
            built += between(
                built.size, if (hasPrev) SectionKind.BETWEEN else SectionKind.LEAD_IN, lastEnd, phrase.startMs, p,
                start, chain.placements, entry, gapY, fromX, fromY, anchorX, anchorY,
            )
            phraseIndex[p] = built.size
            built += phraseSection(built.size, p, phrase, layout, anchorX, anchorY)
            val last = layout.last
            prevExit = if (last != null) PenPoint(last.exitX, last.exitY, last.glyph.exitX, last.glyph.exitY) else entry
            prevLeft = layout.left
            prevBottom = layout.bottom
            prevAnchorX = anchorX
            prevAnchorY = anchorY
            hasPrev = true
            lastEnd = phrase.endMs
        }
        // After the last phrase, or the whole of a song without words.
        val start = prevExit ?: PenPoint(0f, 0f, 0.94f, 0.34f)
        val codaEnd = maxOf(songEnd, lastEnd)
        val chain = chain(start, motifs(built.size, codaEnd - lastEnd, hasPrev))
        val fromX = if (hasPrev) prevAnchorX else start.x + viewWidth * 0.36f
        val fromY = if (hasPrev) prevAnchorY else start.y + viewHeight * 0.3f
        val toX = if (chain.placements.isEmpty()) fromX else chain.endX - viewWidth * 0.12f
        val toY = if (chain.placements.isEmpty()) fromY else chain.endY - viewHeight * 0.05f
        built += between(built.size, SectionKind.CODA, lastEnd, codaEnd, -1, start, chain.placements, null, 0f, fromX, fromY, toX, toY)
        sections = built
        phraseSections = phraseIndex
    }

    /** The section writing phrase [phrase]. */
    fun sectionOfPhrase(phrase: Int): Int = phraseSections[phrase]

    /** The cursor at [timeMs] of playback: section index plus the fraction of its pen-time drawn. */
    fun cursorAt(timeMs: Long): Double {
        if (sections.isEmpty() || timeMs <= sections[0].startMs) return 0.0
        var lo = 0
        var hi = sections.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (sections[mid].startMs <= timeMs) lo = mid else hi = mid - 1
        }
        val section = sections[lo]
        if (timeMs >= section.endMs) return (lo + 1).toDouble()
        return lo + fraction(section, timeMs).toDouble()
    }

    /** The cursor with lyric line [line] fully written (reading with the Wheel; plain lyrics). */
    fun cursorAfterLine(line: Int): Double {
        val phrase = script.phrases.indexOfLast { it.line <= line }
        return if (phrase < 0) 0.0 else sectionOfPhrase(phrase) + 1.0
    }

    /** The lyric line being written (or last written) at [cursor], or -1 before the first. */
    fun lineAt(cursor: Double): Int {
        val s = floor(cursor).toInt().coerceIn(0, sections.size - 1)
        for (i in s downTo 0) {
            val section = sections[i]
            if (section.kind == SectionKind.PHRASE && (i < s || cursor > i)) return script.phrases[section.phrase].line
        }
        return -1
    }

    private fun fraction(section: Section, timeMs: Long): Float {
        val times = section.keyTimes
        val fractions = section.keyFractions
        if (timeMs <= times[0]) return fractions[0]
        if (timeMs >= times[times.size - 1]) return fractions[fractions.size - 1]
        var i = 0
        while (i < times.size - 2 && times[i + 1] <= timeMs) i++
        val span = times[i + 1] - times[i]
        val f = if (span <= 0) 1f else (timeMs - times[i]).toFloat() / span
        return fractions[i] + (fractions[i + 1] - fractions[i]) * f
    }

    /** [section]'s drawing: rebuilt from its plan when needed, a few kept. */
    fun geometry(section: Int): SectionGeometry {
        synchronized(cacheKeys) {
            for (i in 0 until CACHE_SECTIONS) {
                if (cacheKeys[i] == section) {
                    cacheUsed[i] = ++cacheClock
                    return cacheValues[i]!!
                }
            }
        }
        val built = sections[section].recipe()
        synchronized(cacheKeys) {
            var slot = 0
            for (i in 0 until CACHE_SECTIONS) {
                if (cacheKeys[i] == section) return cacheValues[i]!! // built meanwhile on another thread
                if (cacheUsed[i] < cacheUsed[slot]) slot = i
            }
            cacheKeys[slot] = section
            cacheValues[slot] = built
            cacheUsed[slot] = ++cacheClock
        }
        return built
    }

    /** How many sections' drawings are held right now (bounded by [CACHE_SECTIONS]). */
    internal val cachedSections: Int get() = synchronized(cacheKeys) { cacheKeys.count { it >= 0 } }

    /**
     * Where the pen and the camera are at [cursor]. [reduced] (reduced motion): the camera holds
     * still and moves in a few cuts instead of following.
     */
    fun frameAt(cursor: Double, reduced: Boolean, out: LineFrame) {
        if (sections.isEmpty()) return
        var s = floor(cursor).toInt()
        var f = (cursor - s).toFloat()
        if (s >= sections.size) {
            s = sections.size - 1
            f = 1f
        }
        if (s < 0) {
            s = 0
            f = 0f
        }
        val section = sections[s]
        val path = geometry(s).path
        val tau = f * section.length
        out.section = s
        out.tau = tau
        out.penX = path.xAt(tau)
        out.penY = path.yAt(tau)
        out.penDown = path.inkAt(tau) && f > 0f && f < 1f
        camera(section, if (reduced) quantized(section, f) else f, out)
    }

    private fun quantized(section: Section, f: Float): Float {
        if (section.kind == SectionKind.PHRASE) return f
        val pages = ceil(hypot(section.toX - section.fromX, section.toY - section.fromY) / (viewWidth * 0.45f)).coerceAtLeast(1f)
        return (floor(f * pages + 0.5f) / pages)
    }

    private fun camera(section: Section, f: Float, out: LineFrame) {
        var cx: Float
        var cy: Float
        if (section.kind == SectionKind.PHRASE) {
            cx = section.fromX
            cy = section.fromY
        } else {
            val e = f * f * (3f - 2f * f)
            cx = section.fromX + (section.toX - section.fromX) * e
            cy = section.fromY + (section.toY - section.fromY) * e
            val samples = section.camera
            if (samples.size >= 4) {
                val n = samples.size / 2
                val at = f * (n - 1)
                val i = floor(at).toInt().coerceIn(0, n - 2)
                val t = at - i
                val sx = samples[i * 2] + (samples[i * 2 + 2] - samples[i * 2]) * t
                val sy = samples[i * 2 + 1] + (samples[i * 2 + 3] - samples[i * 2 + 1]) * t
                // Between anchors the camera follows the line; it eases onto it and off again at the ends.
                val w = if (section.hasMotifs) {
                    val inOut = minOf(f, 1f - f) / FOLLOW_EASE
                    if (inOut >= 1f) 1f else inOut * inOut * (3f - 2f * inOut)
                } else {
                    FOLLOW_CONNECTOR * sin(PI * f).toFloat()
                }
                cx += (sx - cx) * w
                cy += (sy - cy) * w
            }
        }
        // The pen never leaves the display.
        val mx = viewWidth * (0.5f - PEN_MARGIN)
        val my = viewHeight * (0.5f - PEN_MARGIN)
        cx = cx.coerceIn(out.penX - mx, out.penX + mx)
        cy = cy.coerceIn(out.penY - my, out.penY + my)
        out.cameraX = cx
        out.cameraY = cy
    }

    // ---- Planning ----

    private class Chain(val placements: List<MotifPlacement>, val endX: Float, val endY: Float, val maxY: Float)

    /**
     * Which motifs fill [durationMs] of instrumental: none for a short gap (the connector alone),
     * otherwise a seeded, stable sequence long enough for the pen's unhurried pace.
     */
    private fun motifs(sectionIndex: Int, durationMs: Long, afterPhrase: Boolean): List<Pair<Motif, Float>> {
        val budget = durationMs / 1000f * PEN_SPEED - if (afterPhrase) CONNECTOR_ALLOWANCE else LEAD_ALLOWANCE
        if (budget < MOTIF_MIN_BUDGET) return emptyList()
        val random = SplitMix(seed xor (sectionIndex.toLong() * -0x61c8864680b583ebL))
        val chosen = ArrayList<Pair<Motif, Float>>()
        var used = 0f
        var previous: Motif? = null
        while (used < budget && chosen.size < MAX_MOTIFS) {
            // Every so often a long, calm transition; otherwise any motif but the one just drawn.
            val motif = if (chosen.size % 6 == 5) LineMotifs.SWOOP else {
                var pick: Motif
                do pick = LineMotifs.WEAVE[random.nextInt(LineMotifs.WEAVE.size)] while (pick === previous)
                pick
            }
            val scale = 0.85f + random.nextFloat() * 0.3f
            chosen += motif to scale
            used += motif.length * scale + MOTIF_JOIN
            previous = motif
        }
        return chosen
    }

    /** Places motifs one after another, leaning down-right from [start]. */
    private fun chain(start: PenPoint, motifs: List<Pair<Motif, Float>>): Chain {
        if (motifs.isEmpty()) return Chain(emptyList(), start.x, start.y, start.y)
        val random = SplitMix(seed xor 0x5DEECE66DL xor (start.x * 1000).toLong())
        var x = start.x + CHAIN_ENTRY_X
        var y = start.y + CHAIN_ENTRY_Y
        var maxY = y
        val placed = ArrayList<MotifPlacement>(motifs.size)
        for ((motif, scale) in motifs) {
            placed += MotifPlacement(motif, x, y, scale)
            // The motif's lowest point, for the next phrase to sit below.
            for (i in 1 until motif.points.size step 2) maxY = maxOf(maxY, y + motif.points[i] * scale)
            x += motif.width * scale + MOTIF_GAP_X
            y += motif.exitY * scale + MOTIF_DROP_MIN + random.nextFloat() * (MOTIF_DROP_MAX - MOTIF_DROP_MIN)
        }
        return Chain(placed, x - MOTIF_GAP_X, y, maxY)
    }

    private fun phraseSection(index: Int, p: Int, phrase: LinePhrase, layout: PhraseLayout, anchorX: Float, anchorY: Float): Section {
        val recipe = { buildPhrase(layout) }
        val g = recipe()
        val length = g.path.length
        val words = phrase.wordStarts
        val times: LongArray
        val fractions: FloatArray
        if (length > 0f && g.wordTaus.size == words.size && words.isNotEmpty()) {
            times = LongArray(words.size + 1) { if (it < words.size) words[it] else phrase.endMs }
            fractions = FloatArray(words.size + 1) { if (it < words.size) g.wordTaus[it] / length else 1f }
        } else {
            times = longArrayOf(phrase.startMs, phrase.endMs)
            fractions = floatArrayOf(0f, 1f)
        }
        return Section(
            index, SectionKind.PHRASE, phrase.startMs, phrase.endMs, p, times, fractions, length,
            g.path.minX, g.path.minY, g.path.maxX, g.path.maxY, anchorX, anchorY, anchorX, anchorY, FloatArray(0), false, recipe,
        )
    }

    private fun between(
        index: Int,
        kind: SectionKind,
        startMs: Long,
        endMs: Long,
        phrase: Int,
        start: PenPoint,
        placements: List<MotifPlacement>,
        end: PenPoint?,
        gapY: Float,
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
    ): Section {
        val recipe = { buildBetween(start, placements, end, gapY) }
        val g = recipe()
        val length = g.path.length
        return Section(
            index, kind, startMs, maxOf(startMs, endMs), phrase, longArrayOf(startMs, maxOf(startMs, endMs)), floatArrayOf(0f, 1f), length,
            g.path.minX, g.path.minY, g.path.maxX, g.path.maxY, fromX, fromY, toX, toY, smoothed(g.path), placements.isNotEmpty(), recipe,
        )
    }

    /** The pen's route sampled along its pen-time and smoothed, for the camera to lean toward. */
    private fun smoothed(path: InkPath): FloatArray {
        val length = path.length
        if (path.count < 2 || length <= 0f) return FloatArray(0)
        val n = (ceil(length / CAMERA_STEP).toInt() + 1).coerceIn(2, 2000)
        val raw = FloatArray(n * 2)
        for (i in 0 until n) {
            val t = length * i / (n - 1)
            raw[i * 2] = path.xAt(t)
            raw[i * 2 + 1] = path.yAt(t)
        }
        val half = (CAMERA_WINDOW / CAMERA_STEP / 2f).toInt().coerceAtLeast(1)
        val out = FloatArray(n * 2)
        for (i in 0 until n) {
            var sx = 0f
            var sy = 0f
            var count = 0
            for (j in maxOf(0, i - half)..minOf(n - 1, i + half)) {
                sx += raw[j * 2]
                sy += raw[j * 2 + 1]
                count++
            }
            out[i * 2] = sx / count
            out[i * 2 + 1] = sy / count
        }
        return out
    }

    // ---- Building routes ----

    private fun buildPhrase(layout: PhraseLayout): SectionGeometry {
        val b = InkPathBuilder()
        val wordTaus = FloatArray(layout.words.size)
        var exit: PenPoint? = null
        var lastRow = -1
        layout.words.forEachIndexed { w, word ->
            val first = word.glyphs.firstOrNull()
            if (first == null) {
                wordTaus[w] = b.time
                return@forEachIndexed
            }
            val previous = exit
            if (previous == null) {
                b.moveTo(first.entryX, first.entryY)
            } else if (word.row == lastRow) {
                // The hairline between words: the line carries on, quieter.
                b.connect(previous, first.entryX, first.entryY, first.glyph.arrivalX, first.glyph.arrivalY, Ink.HAIR)
            } else {
                b.moveTo(first.entryX, first.entryY) // a new row: the pen lifts back
            }
            wordTaus[w] = b.time
            lastRow = word.row
            var prevGlyph: PlacedGlyph? = null
            for (placed in word.glyphs) {
                val before = prevGlyph
                if (before != null) {
                    if (before.glyph.joins && placed.glyph.joins) {
                        b.connect(PenPoint(before.exitX, before.exitY, before.glyph.exitX, before.glyph.exitY), placed.entryX, placed.entryY, placed.glyph.arrivalX, placed.glyph.arrivalY, Ink.LETTER)
                    } else {
                        b.moveTo(placed.entryX, placed.entryY)
                    }
                }
                val glyph = placed.glyph
                for (stroke in glyph.strokes) b.stroke(stroke.points, placed.x, placed.baseline, placed.scale, Ink.LETTER)
                // Back from the dot or the bar to where the letter leaves.
                b.moveTo(placed.exitX, placed.exitY)
                prevGlyph = placed
            }
            val last = word.glyphs.last()
            exit = PenPoint(last.exitX, last.exitY, last.glyph.exitX, last.glyph.exitY)
        }
        return SectionGeometry(b.build(), wordTaus, FloatArray(0), FloatArray(0))
    }

    private fun buildBetween(start: PenPoint, placements: List<MotifPlacement>, end: PenPoint?, gapY: Float): SectionGeometry {
        val b = InkPathBuilder()
        b.moveTo(start.x, start.y)
        var pen = start
        val beads = FloatList(8)
        val beadTaus = ArrayList<Float>()
        val glints = FloatList(8)
        val glintTaus = ArrayList<Float>()
        for (m in placements) {
            b.connect(pen, m.x, m.y, 1f, 0f, Ink.FLOURISH)
            val t0 = b.time
            val p = m.motif.points
            for (i in 2 until p.size step 2) b.lineTo(m.x + p[i] * m.scale, m.y + p[i + 1] * m.scale, Ink.FLOURISH)
            val span = b.time - t0
            for (f in m.motif.beads) {
                beadTaus += t0 + span * f
                beads.add(pointAlong(p, f, m), pointAlongY(p, f, m))
            }
            for (f in m.motif.glints) {
                glintTaus += t0 + span * f
                glints.add(pointAlong(p, f, m), pointAlongY(p, f, m))
            }
            val n = p.size
            val dx = p[n - 2] - p[n - 4]
            val dy = p[n - 1] - p[n - 3]
            val l = hypot(dx, dy).takeIf { it > 0f } ?: 1f
            pen = PenPoint(b.x, b.y, dx / l, dy / l)
        }
        if (end != null) {
            b.route(pen, end, gapY)
        } else {
            // The end of the song: the line settles into a last small curl.
            val x = pen.x
            val y = pen.y
            b.cubicTo(x + pen.dx * 0.3f, y + pen.dy * 0.3f, x + 0.55f, y + 0.05f, x + 0.5f, y - 0.12f, Ink.FLOURISH)
            b.cubicTo(x + 0.46f, y - 0.24f, x + 0.3f, y - 0.2f, x + 0.36f, y - 0.08f, Ink.FLOURISH)
        }
        return SectionGeometry(b.build(), FloatArray(0), triples(beadTaus, beads), triples(glintTaus, glints))
    }

    private fun triples(taus: List<Float>, points: FloatList): FloatArray {
        val p = points.raw
        return FloatArray(taus.size * 3).also { out ->
            for (i in taus.indices) {
                out[i * 3] = taus[i]
                out[i * 3 + 1] = p[i * 2]
                out[i * 3 + 2] = p[i * 2 + 1]
            }
        }
    }

    private fun pointAlong(p: FloatArray, f: Float, m: MotifPlacement): Float = m.x + along(p, f, 0) * m.scale

    private fun pointAlongY(p: FloatArray, f: Float, m: MotifPlacement): Float = m.y + along(p, f, 1) * m.scale

    /** The x (axis 0) or y (axis 1) of the point a fraction [f] along polyline [p]'s length. */
    private fun along(p: FloatArray, f: Float, axis: Int): Float {
        var total = 0f
        for (i in 2 until p.size step 2) total += hypot(p[i] - p[i - 2], p[i + 1] - p[i - 1])
        var target = total * f
        for (i in 2 until p.size step 2) {
            val l = hypot(p[i] - p[i - 2], p[i + 1] - p[i - 1])
            if (target <= l || i == p.size - 2) {
                val t = if (l > 0f) (target / l).coerceIn(0f, 1f) else 0f
                return p[i - 2 + axis] + (p[i + axis] - p[i - 2 + axis]) * t
            }
            target -= l
        }
        return p[axis]
    }

    companion object {
        const val VIEW_WIDTH = 6.6f

        /** Each phrase starts this share of the display's width right of the one before. */
        private const val DIAG_X = 0.24f

        /** Room between a phrase (or the instrumental line above it) and the next, in em. */
        private const val GAP_Y = 0.55f

        /** Where the first phrase sits from the pen's arrival point. */
        private const val LEAD_X = 0.8f
        private const val LEAD_Y = 0.45f

        /** A phrase's rows are at most this share of the display's width, its block this share of its height. */
        private const val MAX_ROW = 0.8f
        private const val MAX_BLOCK = 0.46f

        /** After instrumental line art, where the next phrase's box starts from the line's end (em). */
        private const val CHAIN_LEAD_X = 0.12f
        private const val CHAIN_LEAD_Y = -0.2f

        /** Instrumental pace: em of line a second. */
        const val PEN_SPEED = 0.9f

        /** What the connectors take of an instrumental gap before motifs are added, in em. */
        private const val CONNECTOR_ALLOWANCE = 2.6f
        private const val LEAD_ALLOWANCE = 1.4f
        private const val MOTIF_MIN_BUDGET = 0.8f
        private const val MOTIF_JOIN = 0.35f
        private const val MAX_MOTIFS = 400

        private const val CHAIN_ENTRY_X = 0.35f
        private const val CHAIN_ENTRY_Y = 0.3f
        private const val MOTIF_GAP_X = 0.22f
        private const val MOTIF_DROP_MIN = 0.12f
        private const val MOTIF_DROP_MAX = 0.42f

        /** Camera: how far it leans toward the pen between anchors, and how the pen's route is smoothed. */
        private const val FOLLOW_EASE = 0.14f
        private const val FOLLOW_CONNECTOR = 0.35f
        private const val CAMERA_STEP = 0.2f
        private const val CAMERA_WINDOW = 3f

        /** The pen stays at least this share of the display from its edges. */
        private const val PEN_MARGIN = 0.1f

        /** A song without words and without a known length draws this long. */
        private const val OPEN_SONG_MS = 240_000L

        /** How many sections' routes are kept. */
        const val CACHE_SECTIONS = 8

        /** A stable seed for a track: FNV-1a over its key, so it never changes between runs. */
        fun seedOf(key: String): Long {
            var h = -0x340d631b7bdddcdbL
            for (c in key) {
                h = h xor c.code.toLong()
                h *= 0x100000001b3L
            }
            return h
        }
    }
}

/** The connector from [from] to ([x], [y]) arriving travelling ([ax], [ay]): one smooth cubic. */
internal fun InkPathBuilder.connect(from: PenPoint, x: Float, y: Float, ax: Float, ay: Float, kind: Byte) {
    val d = hypot(x - from.x, y - from.y)
    if (d < 1e-4f) return
    val k = (d * 0.45f).coerceIn(0.02f, 0.6f)
    cubicTo(from.x + from.dx * k, from.y + from.dy * k, x - ax * k, y - ay * k, x, y, kind)
}

/**
 * The line from where the pen is to the next phrase's first letter (D-81). Forward (the letter is to
 * the right): one S-curve. Back to the left (a new phrase below the last): the line carries on the way
 * the letter left, drops into the clear space between the two phrases at [gapY], runs back along it
 * and comes down into the first letter — one stroke, like a pen moving to the next line without
 * lifting, never across the words.
 */
internal fun InkPathBuilder.route(from: PenPoint, to: PenPoint, gapY: Float) {
    if (to.x > from.x + 0.4f) {
        val d = hypot(to.x - from.x, to.y - from.y)
        val k = (d * 0.45f).coerceIn(0.1f, 1.8f)
        cubicTo(from.x + from.dx * k, from.y + from.dy * k, to.x - to.dx * k, to.y - to.dy * k, to.x, to.y, Ink.FLOURISH)
        return
    }
    val gap = maxOf(gapY, from.y + 0.25f)
    val xs = floatArrayOf(from.x, from.x + 0.42f, from.x - 0.05f, to.x - 0.45f, to.x)
    val ys = floatArrayOf(from.y, (from.y + gap) / 2f + 0.05f, gap, gap + 0.04f, to.y)
    // Arrive heading between the letter's own arrival and the way down from the gap.
    val dx = to.x - xs[3]
    val dy = to.y - ys[3]
    val l = hypot(dx, dy).takeIf { it > 0f } ?: 1f
    var ax = to.dx + dx / l
    var ay = to.dy + dy / l
    val al = hypot(ax, ay).takeIf { it > 0f } ?: 1f
    ax /= al
    ay /= al
    through(xs, ys, from.dx, from.dy, ax, ay, Ink.FLOURISH)
}

/**
 * A smooth curve through the points ([xs], [ys]) leaving the first along ([sx], [sy]) and arriving at
 * the last along ([ex], [ey]): cubic pieces with Catmull-Rom directions at the inner points, each
 * control a fixed share of its chord (so nothing overshoots).
 */
internal fun InkPathBuilder.through(xs: FloatArray, ys: FloatArray, sx: Float, sy: Float, ex: Float, ey: Float, kind: Byte) {
    val n = xs.size
    val tx = FloatArray(n)
    val ty = FloatArray(n)
    tx[0] = sx
    ty[0] = sy
    tx[n - 1] = ex
    ty[n - 1] = ey
    for (i in 1 until n - 1) {
        val dx = xs[i + 1] - xs[i - 1]
        val dy = ys[i + 1] - ys[i - 1]
        val l = hypot(dx, dy).takeIf { it > 0f } ?: 1f
        tx[i] = dx / l
        ty[i] = dy / l
    }
    for (i in 0 until n - 1) {
        val chord = hypot(xs[i + 1] - xs[i], ys[i + 1] - ys[i])
        val k = chord * 0.38f
        cubicTo(xs[i] + tx[i] * k, ys[i] + ty[i] * k, xs[i + 1] - tx[i + 1] * k, ys[i + 1] - ty[i + 1] * k, xs[i + 1], ys[i + 1], kind)
    }
}

/** SplitMix64: a tiny, stable pseudo-random sequence (the same seed always gives the same choices). */
internal class SplitMix(private var state: Long) {
    fun nextLong(): Long {
        state += -0x61c8864680b583ebL
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    fun nextInt(bound: Int): Int = ((nextLong() ushr 33) % bound).toInt()

    fun nextFloat(): Float = (nextLong() ushr 40).toFloat() / (1 shl 24).toFloat()
}

/** Ease-out for a retrace (fast away, soft landing). */
internal fun easeOut(t: Float): Float = 1f - (1f - t) * (1f - t) * (1f - t)
