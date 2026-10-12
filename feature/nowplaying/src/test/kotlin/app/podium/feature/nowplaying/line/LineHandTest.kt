package app.podium.feature.nowplaying.line

import org.junit.Test
import kotlin.math.hypot
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The single-stroke hand, phrase setting and prepared routes (LYRICS_ARCHITECTURE.md §12.1–12.2). */
class LineHandTest {

    private val letters = ('a'..'z') + ('A'..'Z')
    private val numerals = ('0'..'9')

    @Test
    fun `every letter and numeral is written with real strokes inside its box`() {
        for (c in letters + numerals + LineHand.characters) {
            val g = assertNotNull(LineHand.glyph(c.code), "'$c' is written by the hand")
            assertTrue(g.strokes.isNotEmpty())
            assertFalse(g.traced)
            var length = 0f
            for (stroke in g.strokes) {
                assertTrue(stroke.pointCount >= 2, "'$c' strokes are lines, not dots")
                for (i in 0 until stroke.points.size step 2) {
                    val x = stroke.points[i]
                    val y = stroke.points[i + 1]
                    assertTrue(x.isFinite() && y.isFinite())
                    // Within the hand's frame: ascenders to 1 em above the baseline, descenders below.
                    assertTrue(y >= -1.1f && y <= 0.5f, "'$c' stays between ascender and descender ($y)")
                    assertTrue(x >= -0.2f && x <= g.advance + 0.35f, "'$c' stays near its box ($x of ${g.advance})")
                    if (i > 0) length += hypot(x - stroke.points[i - 2], y - stroke.points[i - 1])
                }
            }
            assertTrue(length > 0.08f, "'$c' has a drawn length ($length)")
            // The exit is the end of the last stroke that isn't a mark; marks come after it.
            assertFalse(g.strokes[g.exitStroke].mark)
            for (i in g.exitStroke + 1 until g.strokes.size) assertTrue(g.strokes[i].mark || i > g.exitStroke)
            assertEquals(1f, hypot(g.arrivalX, g.arrivalY), 1e-4f)
            assertEquals(1f, hypot(g.exitX, g.exitY), 1e-4f)
        }
    }

    @Test
    fun `lowercase letters are mostly one stroke, marks and lifts only where a hand lifts`() {
        // Letters drawn in one movement in this hand.
        for (c in "abcdefghklmnopqrsuvwyz") assertEquals(1, LineHand.glyph(c.code)!!.strokes.size, "'$c' is one stroke")
        // The dot of an i and j, the bar of a t, the second diagonal of an x: one extra stroke each.
        for (c in "ijtx") assertEquals(2, LineHand.glyph(c.code)!!.strokes.size, "'$c'")
        assertTrue(LineHand.glyph('i'.code)!!.strokes.last().mark, "the i's dot is drawn after the letter")
        // Letters join; punctuation and numerals are reached with a lift.
        assertTrue(LineHand.glyph('a'.code)!!.joins)
        assertFalse(LineHand.glyph('?'.code)!!.joins)
        assertFalse(LineHand.glyph('7'.code)!!.joins)
    }

    @Test
    fun `accented letters are the base letter with the accent's strokes`() {
        val e = LineHand.glyph('e'.code)!!
        val acute = LineHand.glyph('é'.code)!!
        assertEquals(e.strokes.size + 1, acute.strokes.size)
        assertTrue(acute.strokes.last().mark)
        // The accent sits above the letter.
        assertTrue(acute.strokes.last().points.let { p -> (1 until p.size step 2).all { p[it] < -0.5f } })
        // An accented i loses its dot to the accent.
        val i = LineHand.glyph('í'.code)!!
        assertEquals(2, i.strokes.size)
        assertNotNull(LineHand.glyph('ñ'.code))
        assertNotNull(LineHand.glyph('Ü'.code))
        assertNotNull(LineHand.glyph('ç'.code))
        // Not written by the hand: another script, a ligature, a symbol.
        assertNull(LineHand.glyph('Ж'.code))
        assertNull(LineHand.glyph('愛'.code))
        assertNull(LineHand.glyph('æ'.code))
        assertNull(LineHand.glyph(0x1F3B5)) // 🎵
    }

    @Test
    fun `glyphs are built once and reused`() {
        assertTrue(LineHand.glyph('q'.code) === LineHand.glyph('q'.code))
    }

    @Test
    fun `a phrase is set in reading order, rows within the width, the same every time`() {
        val text = "When the night is quiet and everything they say"
        val a = LinePhraseSetter.layout(text, 0f, 0f, 5f, 4f, ::glyphOrBox)
        val b = LinePhraseSetter.layout(text, 0f, 0f, 5f, 4f, ::glyphOrBox)
        assertEquals(LinePhraseSetter.words(text).size, a.words.size)
        // Same input, same placement.
        assertEquals(a.words.flatMap { w -> w.glyphs.map { it.x to it.baseline } }, b.words.flatMap { w -> w.glyphs.map { it.x to it.baseline } })
        // Glyphs in each word left to right, words in rows top to bottom.
        var lastRow = 0
        for (word in a.words) {
            assertTrue(word.row >= lastRow)
            lastRow = word.row
            assertEquals(word.glyphs.map { it.x }.sorted(), word.glyphs.map { it.x })
        }
        assertTrue(a.rows > 1, "a long phrase wraps")
        // Each row fits.
        for (word in a.words) for (g in word.glyphs) assertTrue(g.x + g.glyph.advance * g.scale <= 5f + 1e-3f)
        // Each word's glyphs are the word's characters, in order.
        val first = a.words.first().glyphs.map { it.glyph }
        assertEquals("When".map { LineHand.glyph(it.code) }, first)
    }

    @Test
    fun `a long phrase is set smaller rather than overflowing, a short one at full size`() {
        val short = LinePhraseSetter.layout("Hold on", 0f, 0f, 5f, 4f, ::glyphOrBox)
        assertEquals(1f, short.scale)
        assertEquals(1, short.rows)
        val long = LinePhraseSetter.layout("and everything they say is a little bit too long to fit on one small screen without wrapping again and again", 0f, 0f, 5f, 4f, ::glyphOrBox)
        assertTrue(long.scale < 1f)
        assertTrue(long.bottom - long.top <= 4f + 1e-3f)
    }

    @Test
    fun `the route through a phrase is prepared once, ink only where the pen touches`() {
        val c = LineComposition(
            LineScript.synced(app.podium.core.lyrics.Lyrics.Synced(listOf(app.podium.core.lyrics.LyricsLine(1_000, "quiet night"))), 10_000),
            1L, 1.5f, ::glyphOrBox,
        )
        val s = c.sectionOfPhrase(0)
        val path = c.geometry(s).path
        // Pen-time only grows.
        for (i in 1 until path.count) assertTrue(path.tau[i] >= path.tau[i - 1])
        // Runs cover every segment exactly once, in order.
        var next = 0
        for (r in 0 until path.runs.size step 3) {
            assertEquals(next, path.runs[r])
            next = path.runs[r + 1]
        }
        assertEquals(path.segments, next)
        // Letters, the hairline between the words, and lifts for the i's dot and the t's bar.
        val kinds = path.kinds.toSet()
        assertTrue(Ink.LETTER in kinds && Ink.HAIR in kinds && Ink.LIFT in kinds)
        // segmentAt finds the segment containing a pen-time.
        for (k in 0..50) {
            val t = path.length * k / 50f
            val seg = path.segmentAt(t)
            assertTrue(path.tau[seg] <= t + 1e-4f && (seg == path.segments - 1 || path.tau[seg + 1] >= t - 1e-4f))
        }
        // Drawing data matches the points.
        for (i in 0 until path.segments) {
            assertEquals(path.xs[i], path.lines[i * 4])
            assertEquals(path.ys[i + 1], path.lines[i * 4 + 3])
        }
    }

    @Test
    fun `the pen writes each letter in its own stroke order`() {
        // Writing "it": the pen follows the i's stroke, lifts to its dot, then comes back to join the t.
        val c = LineComposition(
            LineScript.synced(app.podium.core.lyrics.Lyrics.Synced(listOf(app.podium.core.lyrics.LyricsLine(1_000, "it"))), 10_000),
            1L, 1.5f, ::glyphOrBox,
        )
        val path = c.geometry(c.sectionOfPhrase(0)).path
        val i = LineHand.glyph('i'.code)!!
        val body = i.strokes[0].points
        val dot = i.strokes[1].points
        // The route begins with the i's own stroke, point for point (placed, at full size).
        val ox = path.xs[0] - body[0]
        val oy = path.ys[0] - body[1]
        for (k in 0 until body.size / 2) {
            assertEquals(body[k * 2] + ox, path.xs[k], 1e-4f)
            assertEquals(body[k * 2 + 1] + oy, path.ys[k], 1e-4f)
            if (k > 0) assertEquals(Ink.LETTER, path.kinds[k - 1])
        }
        // Then the pen lifts to the dot, draws it, and lifts back to where the i leaves.
        val n = body.size / 2
        assertEquals(Ink.LIFT, path.kinds[n - 1])
        assertEquals(dot[0] + ox, path.xs[n], 1e-4f)
        assertEquals(dot[1] + oy, path.ys[n], 1e-4f)
        assertEquals(Ink.LETTER, path.kinds[n])
        assertEquals(Ink.LIFT, path.kinds[n + dot.size / 2 - 1])
        assertEquals(body[body.size - 2] + ox, path.xs[n + dot.size / 2], 1e-4f)
        // The last ink is the t's crossbar, the stroke the letter leaves by.
        assertEquals(Ink.LETTER, path.kinds.last())
    }

    @Test
    fun `motif crossings are found once, and the same every time`() {
        assertTrue(LineMotifs.LOOP.glints.isNotEmpty(), "a loop crosses itself")
        assertTrue(LineMotifs.EIGHT.glints.isNotEmpty())
        assertTrue(LineMotifs.WAVE.glints.isEmpty(), "a wave never crosses itself")
        assertTrue(LineMotifs.LOOP.glints.contentEquals(LineMotifs.crossings(LineMotifs.LOOP.points)))
        for (m in LineMotifs.WEAVE + LineMotifs.SWOOP) {
            // Every motif enters at the origin heading right and leaves to the right.
            assertTrue(m.points[0] == 0f && m.points[1] == 0f, "${m.name} starts at the origin")
            assertTrue(m.width > 0.5f, "${m.name} moves the line on")
            assertTrue(m.length > m.width)
        }
        assertEquals(4, LineMotifs.BEATS.beads.size)
    }
}
