package app.podium.feature.nowplaying.line

import app.podium.core.lyrics.Lyrics
import app.podium.core.lyrics.LyricsLine
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How the Continuous line moves and glows (D-82): the pen's pace changes smoothly from word to word
 * yet reaches each word on time, positions between milliseconds draw between frames, and the glow's
 * simpler route stays on the line, is revealed with it and costs fewer segments.
 */
class LineMotionTest {

    private val lines = listOf(
        LyricsLine(10_000, "I wish that everything could hold"),
        LyricsLine(14_500, "and on"),
        LyricsLine(30_000, "When the night is quiet"),
    )

    private fun composition() = LineComposition(LineScript.synced(Lyrics.Synced(lines), 60_000), LineComposition.seedOf("test|1"), 1.5f, ::glyphOrBox)

    @Test
    fun `the pen reaches every word exactly on time, never runs back, and changes pace smoothly`() {
        val c = composition()
        val s = c.sectionOfPhrase(0)
        val section = c.sections[s]
        val keys = section.keyTimes
        assertTrue(keys.size > 3, "a phrase is paced by its words")
        // On each word's moment, exactly the word's share (as before).
        for (k in keys.indices) assertEquals(s + section.keyFractions[k].toDouble(), c.cursorAt(keys[k]).coerceAtMost(s + 1.0), 1e-6)
        // Speed just before and just after each inner key: continuous now (a straight-line pace jumped).
        var worst = 0.0
        var linearWorst = 0.0
        for (k in 1 until keys.size - 1) {
            val t = keys[k].toDouble()
            val before = (c.cursorAt(t) - c.cursorAt(t - 4.0)) / 4.0
            val after = (c.cursorAt(t + 4.0) - c.cursorAt(t)) / 4.0
            val scale = maxOf(before, after, 1e-9)
            worst = maxOf(worst, abs(after - before) / scale)
            val d0 = (section.keyFractions[k] - section.keyFractions[k - 1]) / (keys[k] - keys[k - 1]).toDouble()
            val d1 = (section.keyFractions[k + 1] - section.keyFractions[k]) / (keys[k + 1] - keys[k]).toDouble()
            linearWorst = maxOf(linearWorst, abs(d1 - d0) / maxOf(d0, d1))
        }
        assertTrue(linearWorst > 0.3, "the words' own paces differ (${"%.2f".format(linearWorst)})")
        assertTrue(worst < 0.1, "the pace changes smoothly at the words (worst jump ${"%.3f".format(worst)})")
        var last = -1.0
        var t = keys.first().toDouble()
        while (t <= keys.last()) {
            val cursor = c.cursorAt(t)
            assertTrue(cursor >= last, "never back at $t")
            last = cursor
            t += 0.37
        }
    }

    @Test
    fun `a position between milliseconds draws between them`() {
        val c = composition()
        val a = c.cursorAt(11_000L)
        val b = c.cursorAt(11_001L)
        val mid = c.cursorAt(11_000.5)
        assertTrue(mid > a && mid < b, "$a < $mid < $b")
    }

    @Test
    fun `the glow's strokes keep to one inked run each, in order`() {
        val c = composition()
        for (i in c.sections.indices) {
            val path = c.geometry(i).path
            val gs = path.glowStrokes
            var lastEnd = -1
            for (k in 0 until gs.size / 3) {
                val a = gs[k * 3]
                val b = gs[k * 3 + 1]
                assertTrue(a >= lastEnd, "in order")
                assertTrue(gs[k * 3 + 2].toByte() != Ink.LIFT, "never a lift")
                // A stroke's vertices are consecutive in pen-time, with no lift between them.
                for (v in a until b) {
                    val mid = (path.glowTau[v] + path.glowTau[v + 1]) / 2f
                    assertTrue(path.inkAt(mid), "section $i stroke $k crosses a lift at $mid")
                }
                lastEnd = b
            }
        }
    }

    @Test
    fun `the glow follows the line closely, in order, and costs fewer segments`() {
        val c = composition()
        var line = 0
        var glow = 0
        for (i in c.sections.indices) {
            val path = c.geometry(i).path
            line += path.segments
            val gs = path.glowStrokes
            for (k in 0 until gs.size / 3) {
                glow += gs[k * 3 + 1] - gs[k * 3]
                assertTrue(gs[k * 3 + 1] - gs[k * 3] in 1..InkPath.STROKE_SEGMENTS)
            }
            // Glow times only go forward, so it is revealed with the line.
            for (v in 1 until path.glowTau.size) assertTrue(path.glowTau[v - 1] <= path.glowTau[v])
            // Every inked point of the route is within the tolerance of the glow.
            for (p in 0 until path.count) {
                val inked = (p > 0 && path.kinds[p - 1] != Ink.LIFT) || (p < path.segments && path.kinds[p] != Ink.LIFT)
                if (!inked) continue
                var nearest = Float.MAX_VALUE
                for (k in 0 until gs.size / 3) for (v in gs[k * 3] until gs[k * 3 + 1]) {
                    nearest = minOf(nearest, distance(path.xs[p], path.ys[p], path.glowXs[v], path.glowYs[v], path.glowXs[v + 1], path.glowYs[v + 1]))
                }
                assertTrue(nearest <= InkPath.GLOW_TOLERANCE * 1.01f, "section $i point $p is $nearest em from the glow")
            }
        }
        assertTrue(glow * 2 < line, "the glow draws far fewer segments: $glow of $line")
    }

    private fun distance(x: Float, y: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val l2 = dx * dx + dy * dy
        val t = if (l2 <= 0f) 0f else (((x - ax) * dx + (y - ay) * dy) / l2).coerceIn(0f, 1f)
        return hypot(x - (ax + dx * t), y - (ay + dy * t))
    }

    @Test
    fun `monotone slopes never overshoot a key`() {
        val times = longArrayOf(0, 100, 110, 400, 1_000)
        val fractions = floatArrayOf(0f, 0.5f, 0.52f, 0.9f, 1f)
        val slopes = monotoneSlopes(times, fractions)
        assertEquals(times.size, slopes.size)
        assertTrue(slopes.all { it >= 0f })
        // A flat stretch keeps a zero pace at both ends.
        val flat = monotoneSlopes(longArrayOf(0, 100, 200), floatArrayOf(0f, 0.5f, 0.5f))
        assertEquals(0f, flat[1])
        assertEquals(0f, flat[2])
    }

    @Test
    fun `the swell takes no part in comparing frames`() {
        val a = LineFrame().apply { tau = 1f; swell = 0f }
        val b = LineFrame().apply { tau = 1f; swell = 0.8f }
        assertEquals(a, b)
        val copy = LineFrame().also { it.copyFrom(b) }
        assertEquals(0.8f, copy.swell)
    }
}
