package app.podium.feature.nowplaying.line

import kotlin.math.hypot

/**
 * One piece of instrumental line art (LYRICS_ARCHITECTURE.md §12.4): a single stroke in em (y down)
 * that enters at (0, 0) heading right and leaves at ([width], [exitY]) heading right, so motifs
 * chain into one line. [beads] are places along it (fractions of its length) where a small dot is
 * lit as the pen passes; [glints] are where the line crosses itself (found once, when the motif is
 * built), each catching a brief gold light as the pen closes the crossing.
 */
internal class Motif(
    val name: String,
    val points: FloatArray,
    val beads: FloatArray,
    val glints: FloatArray,
) {
    val width: Float get() = points[points.size - 2]
    val exitY: Float get() = points[points.size - 1]
    val length: Float = run {
        var sum = 0f
        for (i in 2 until points.size step 2) sum += hypot(points[i] - points[i - 2], points[i + 1] - points[i - 1])
        sum
    }
}

/**
 * The library of instrumental motifs, hand-drawn in the hand's grid (100 units to the em, y up)
 * and flattened once. Nothing here is random: the composition picks from them with a seeded,
 * stable choice ([LineComposition]), so the same song always draws the same artwork.
 */
internal object LineMotifs {

    val WAVE = motif("wave", "0,0 C 25,30 55,30 80,0 C 105,-30 135,-30 160,0 C 175,18 195,20 210,12")
    val LOOP = motif("loop", "0,0 C 40,0 72,26 64,56 C 58,80 26,76 32,48 C 38,22 76,4 120,6")
    val SPIRAL = motif(
        "spiral",
        "0,0 C 40,-6 86,18 80,56 C 74,94 22,92 18,58 C 15,32 42,22 56,36 C 66,46 60,62 46,60 C 36,58 36,46 46,44 C 66,40 96,14 140,6",
    )
    val EIGHT = motif("figure eight", "0,0 C 20,26 56,30 76,0 C 96,-30 134,-28 134,0 C 134,28 96,30 76,0 C 60,-24 40,-30 30,-14 C 24,-4 60,4 170,4")
    val BEATS = motif("beat dots", "0,0 C 45,22 115,22 160,0", beads = floatArrayOf(0.14f, 0.38f, 0.62f, 0.86f))
    val NOTE = motif(
        "note",
        "0,0 C 14,0 20,-2 24,-8 C 28,-16 46,-16 48,-8 C 50,2 34,8 28,2 C 24,-4 30,-12 40,-12 C 46,-12 50,-8 49,-2 " +
            "C 48,4 38,6 34,2 C 33,0 40,-2 48,-2 L 50,72 C 56,62 74,56 72,36 C 71,26 66,22 62,18 C 76,10 96,2 120,2",
    )
    val NOTES = motif(
        "two notes",
        "0,0 C 12,0 20,-6 26,-10 C 32,-14 44,-12 44,-4 C 44,6 30,8 26,2 C 22,-4 32,-10 40,-8 L 44,-2 L 46,66 " +
            "C 62,70 80,74 96,78 L 94,8 C 92,-2 80,-2 78,6 C 76,14 90,16 94,8 C 100,0 112,-2 140,0",
    )
    val KNOT = motif("crossing", "0,0 C 40,0 70,50 100,50 C 124,50 128,20 108,14 C 86,8 70,40 92,58 C 110,72 140,30 170,10")
    val SWOOP = motif("long transition", "0,0 C 60,0 90,-50 150,-50 C 210,-50 230,-10 300,-10")

    /** What an instrumental passage is made of, in the order the seeded choice draws from. */
    val WEAVE: List<Motif> = listOf(WAVE, LOOP, SPIRAL, EIGHT, BEATS, NOTE, NOTES, KNOT)

    private fun motif(name: String, path: String, beads: FloatArray = FloatArray(0)): Motif {
        val grid = parsePath(path).first().points
        val points = FloatArray(grid.size)
        for (i in grid.indices step 2) {
            points[i] = grid[i] / 100f
            points[i + 1] = -grid[i + 1] / 100f
        }
        return Motif(name, points, beads, crossings(points))
    }

    /**
     * Where the stroke crosses itself, as fractions of its length (at the second pass, when the
     * crossing is closed). Segments that touch at a shared point don't count.
     */
    internal fun crossings(p: FloatArray): FloatArray {
        val n = p.size / 2
        val lengths = FloatArray(n)
        for (i in 1 until n) lengths[i] = lengths[i - 1] + hypot(p[i * 2] - p[i * 2 - 2], p[i * 2 + 1] - p[i * 2 - 1])
        val total = lengths[n - 1].takeIf { it > 0f } ?: return FloatArray(0)
        val found = ArrayList<Float>()
        for (j in 1 until n) {
            for (i in 1 until j - 1) {
                val t = intersect(p, i - 1, j - 1) ?: continue
                val at = (lengths[j - 1] + (lengths[j] - lengths[j - 1]) * t) / total
                // One glint per crossing, even if the polyline crosses near a vertex twice.
                if (found.none { kotlin.math.abs(it - at) < 0.04f }) found += at
            }
        }
        return found.sorted().toFloatArray()
    }

    /** Where segment b (from point b to b+1) crosses segment a, as a fraction along b; null if not. */
    private fun intersect(p: FloatArray, a: Int, b: Int): Float? {
        val x1 = p[a * 2]
        val y1 = p[a * 2 + 1]
        val x2 = p[a * 2 + 2]
        val y2 = p[a * 2 + 3]
        val x3 = p[b * 2]
        val y3 = p[b * 2 + 1]
        val x4 = p[b * 2 + 2]
        val y4 = p[b * 2 + 3]
        val d = (x2 - x1) * (y4 - y3) - (y2 - y1) * (x4 - x3)
        if (d == 0f) return null
        val ta = ((x3 - x1) * (y4 - y3) - (y3 - y1) * (x4 - x3)) / d
        val tb = ((x3 - x1) * (y2 - y1) - (y3 - y1) * (x2 - x1)) / d
        return if (ta > 0.001f && ta < 0.999f && tb > 0.001f && tb < 0.999f) tb else null
    }
}
