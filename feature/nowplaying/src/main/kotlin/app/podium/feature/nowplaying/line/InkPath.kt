package app.podium.feature.nowplaying.line

import kotlin.math.hypot

/** What a piece of the pen's route draws. */
internal object Ink {
    /** The pen in the air: moves, draws nothing. */
    const val LIFT: Byte = 0

    /** Lettering. */
    const val LETTER: Byte = 1

    /** The hairline between two words. */
    const val HAIR: Byte = 2

    /** The line between phrases, and the instrumental motifs. */
    const val FLOURISH: Byte = 3

    /** How long the pen takes over a unit of each kind, relative to lettering: lifts are quick. */
    fun weight(kind: Byte): Float = when (kind) {
        LIFT -> 0.25f
        HAIR -> 0.8f
        else -> 1f
    }
}

/**
 * The pen's route through one section of the composition, prepared once: [count] points, the kind
 * of each segment between consecutive points, and the cumulative pen-time [tau] at each point
 * (length weighted by [Ink.weight], so lifts pass quickly). [lines] holds every segment as
 * (x0, y0, x1, y1) so a revealed prefix is one `drawLines` call per [runs] entry, with no
 * allocation and no path building while drawing. Coordinates are em, y down.
 *
 * The glow around the line (D-82) is drawn from a simpler copy of the inked route: each run
 * simplified to within [GLOW_TOLERANCE] (a pixel or two, inside a glow many times wider), as
 * vertices ([glowXs], [glowYs], with their pen-time in [glowTau]), cut into [glowStrokes] (first
 * vertex, last vertex, kind) of at most [STROKE_SEGMENTS] segments, consecutive strokes of a run
 * sharing their end vertex. The painter makes each into a platform path once.
 */
internal class InkPath(
    val xs: FloatArray,
    val ys: FloatArray,
    val count: Int,
    val kinds: ByteArray,
    val tau: FloatArray,
    val lines: FloatArray,
    /** Consecutive segments of one kind, as triples (first segment, end segment exclusive, kind). */
    val runs: IntArray,
    val minX: Float,
    val minY: Float,
    val maxX: Float,
    val maxY: Float,
    val glowXs: FloatArray = FloatArray(0),
    val glowYs: FloatArray = FloatArray(0),
    val glowTau: FloatArray = FloatArray(0),
    val glowStrokes: IntArray = IntArray(0),
) {
    val length: Float get() = if (count == 0) 0f else tau[count - 1]
    val segments: Int get() = (count - 1).coerceAtLeast(0)

    /** The segment containing pen-time [t]: the last i with tau[i] <= t, clamped to the segments. */
    fun segmentAt(t: Float): Int {
        if (count < 2 || t <= 0f) return 0
        if (t >= length) return count - 2
        var lo = 0
        var hi = count - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (tau[mid] <= t) lo = mid else hi = mid
        }
        return lo
    }

    /** How far along segment [segment] pen-time [t] is (0–1). */
    fun fractionIn(segment: Int, t: Float): Float {
        if (count < 2) return 0f
        val a = tau[segment]
        val b = tau[segment + 1]
        return if (b <= a) 1f else ((t - a) / (b - a)).coerceIn(0f, 1f)
    }

    fun xAt(t: Float): Float {
        if (count == 0) return 0f
        if (count == 1) return xs[0]
        val s = segmentAt(t)
        val f = fractionIn(s, t)
        return xs[s] + (xs[s + 1] - xs[s]) * f
    }

    fun yAt(t: Float): Float {
        if (count == 0) return 0f
        if (count == 1) return ys[0]
        val s = segmentAt(t)
        val f = fractionIn(s, t)
        return ys[s] + (ys[s + 1] - ys[s]) * f
    }

    /** Whether the pen is touching the paper at pen-time [t]. */
    fun inkAt(t: Float): Boolean = count >= 2 && kinds[segmentAt(t)] != Ink.LIFT

    companion object {
        val EMPTY = InkPath(FloatArray(0), FloatArray(0), 0, ByteArray(0), FloatArray(0), FloatArray(0), IntArray(0), 0f, 0f, 0f, 0f)

        /** How far the glow's route may stray from the line's, in em (about 2 px on a phone). */
        const val GLOW_TOLERANCE = 0.012f

        /** The most segments in one glow stroke: what the painter rebuilds, at most, for the stroke under the pen. */
        const val STROKE_SEGMENTS = 48
    }
}

/** Builds an [InkPath] from moves, lines, polylines and cubic curves. */
internal class InkPathBuilder {
    private var xs = FloatArray(256)
    private var ys = FloatArray(256)
    private var tau = FloatArray(256)
    private var kinds = ByteArray(256)
    private var n = 0
    private val scratch = FloatList(64)

    val isEmpty: Boolean get() = n == 0
    val x: Float get() = xs[n - 1]
    val y: Float get() = ys[n - 1]

    /** Pen-time so far (for timing keyframes). */
    val time: Float get() = if (n == 0) 0f else tau[n - 1]

    /** Starts the route at a point, or lifts the pen to it. */
    fun moveTo(x: Float, y: Float) {
        if (n == 0) add(x, y, Ink.LIFT) else lineTo(x, y, Ink.LIFT)
    }

    fun lineTo(x: Float, y: Float, kind: Byte) {
        if (n == 0) {
            add(x, y, kind)
            return
        }
        if (x == xs[n - 1] && y == ys[n - 1]) return
        add(x, y, kind)
    }

    /** A stroke given as (x, y) pairs, placed at ([dx], [dy]) and scaled by [scale]: lifts to its start. */
    fun stroke(points: FloatArray, dx: Float, dy: Float, scale: Float, kind: Byte) {
        moveTo(dx + points[0] * scale, dy + points[1] * scale)
        for (i in 2 until points.size step 2) lineTo(dx + points[i] * scale, dy + points[i + 1] * scale, kind)
    }

    fun cubicTo(c1x: Float, c1y: Float, c2x: Float, c2y: Float, x: Float, y: Float, kind: Byte, tolerance: Float = 0.004f) {
        scratch.clear()
        cubic(scratch, this.x, this.y, c1x, c1y, c2x, c2y, x, y, tolerance)
        val p = scratch.raw
        for (i in 0 until scratch.size step 2) lineTo(p[i], p[i + 1], kind)
    }

    private fun add(x: Float, y: Float, kind: Byte) {
        if (n == xs.size) {
            val size = xs.size * 2
            xs = xs.copyOf(size)
            ys = ys.copyOf(size)
            tau = tau.copyOf(size)
            kinds = kinds.copyOf(size)
        }
        xs[n] = x
        ys[n] = y
        if (n == 0) {
            tau[0] = 0f
        } else {
            kinds[n - 1] = kind
            tau[n] = tau[n - 1] + hypot(x - xs[n - 1], y - ys[n - 1]) * Ink.weight(kind)
        }
        n++
    }

    fun build(): InkPath {
        if (n == 0) return InkPath.EMPTY
        val segments = (n - 1).coerceAtLeast(0)
        val lines = FloatArray(segments * 4)
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in 0 until n) {
            minX = minOf(minX, xs[i])
            minY = minOf(minY, ys[i])
            maxX = maxOf(maxX, xs[i])
            maxY = maxOf(maxY, ys[i])
        }
        for (i in 0 until segments) {
            lines[i * 4] = xs[i]
            lines[i * 4 + 1] = ys[i]
            lines[i * 4 + 2] = xs[i + 1]
            lines[i * 4 + 3] = ys[i + 1]
        }
        val runs = ArrayList<Int>()
        var start = 0
        for (i in 1..segments) {
            if (i == segments || kinds[i] != kinds[start]) {
                runs += start
                runs += i
                runs += kinds[start].toInt()
                start = i
            }
        }
        val glow = glow(segments)
        return InkPath(
            xs.copyOf(n), ys.copyOf(n), n, kinds.copyOf(segments), tau.copyOf(n), lines, runs.toIntArray(), minX, minY, maxX, maxY,
            glow.xs, glow.ys, glow.tau, glow.strokes,
        )
    }

    private class Glow(val xs: FloatArray, val ys: FloatArray, val tau: FloatArray, val strokes: IntArray)

    /**
     * The inked runs simplified (Douglas–Peucker, [InkPath.GLOW_TOLERANCE]) for the glow: each kept
     * vertex keeps its pen-time, so the glow is revealed with the line.
     */
    private fun glow(segments: Int): Glow {
        val points = FloatList(256)
        val taus = FloatList(128)
        val strokes = ArrayList<Int>()
        val keep = BooleanArray(n)
        val stack = IntArray(n * 2 + 2)
        var start = 0
        while (start < segments) {
            var end = start
            while (end < segments && kinds[end] == kinds[start]) end++
            if (kinds[start] != Ink.LIFT) {
                // Points start..end (segments start until end).
                simplify(start, end, keep, stack)
                var first = taus.size
                for (i in start..end) {
                    if (!keep[i]) continue
                    points.add(xs[i], ys[i])
                    taus.add(tau[i])
                    if (taus.size - 1 - first == InkPath.STROKE_SEGMENTS) {
                        strokes += first
                        strokes += taus.size - 1
                        strokes += kinds[start].toInt()
                        first = taus.size - 1
                    }
                }
                if (taus.size - 1 > first) {
                    strokes += first
                    strokes += taus.size - 1
                    strokes += kinds[start].toInt()
                }
            }
            start = end
        }
        val p = points.toArray()
        return Glow(FloatArray(p.size / 2) { p[it * 2] }, FloatArray(p.size / 2) { p[it * 2 + 1] }, taus.toArray(), strokes.toIntArray())
    }

    private fun simplify(from: Int, to: Int, keep: BooleanArray, stack: IntArray) {
        for (i in from..to) keep[i] = false
        keep[from] = true
        keep[to] = true
        var top = 0
        stack[top++] = from
        stack[top++] = to
        val tolerance = InkPath.GLOW_TOLERANCE
        while (top > 0) {
            val b = stack[--top]
            val a = stack[--top]
            if (b - a < 2) continue
            val ax = xs[a]
            val ay = ys[a]
            val dx = xs[b] - ax
            val dy = ys[b] - ay
            val length = hypot(dx, dy)
            var worst = -1f
            var at = -1
            for (i in a + 1 until b) {
                val d = if (length < 1e-6f) hypot(xs[i] - ax, ys[i] - ay) else kotlin.math.abs((xs[i] - ax) * dy - (ys[i] - ay) * dx) / length
                if (d > worst) {
                    worst = d
                    at = i
                }
            }
            if (worst > tolerance) {
                keep[at] = true
                stack[top++] = a
                stack[top++] = at
                stack[top++] = at
                stack[top++] = b
            }
        }
    }
}
