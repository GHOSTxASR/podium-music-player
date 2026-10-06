package app.podium.feature.nowplaying

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LyricLayoutTest {
    /** A monospaced stand-in: every character is 0.6 em wide. */
    private val measure: (String, Float) -> Float = { word, px -> word.length * px * 0.6f }

    private fun check(r: LyricLayout.Result, width: Float, height: Float) {
        assertTrue(r.height <= height + 0.5f, "block ${r.height} taller than $height")
        r.lines.forEach { line ->
            line.words.forEach { w ->
                assertTrue(w.x >= -0.01f && w.x + w.width <= width + 0.5f, "${w.text} at ${w.x}+${w.width} outside $width")
            }
            assertTrue(line.y >= 0f && line.y + r.lineHeightPx <= height + 0.5f)
        }
    }

    @Test
    fun `a short line is set large, justified edge to edge`() {
        val r = LyricLayout.layout("called me on the telephone", 300f, 300f, 64f, 20f, measure = measure)
        check(r, 300f, 300f)
        val multi = r.lines.filter { it.words.size > 1 }
        assertTrue(multi.isNotEmpty())
        multi.forEach { line ->
            assertEquals(0f, line.words.first().x, 0.01f)
            val last = line.words.last()
            assertEquals(300f, last.x + last.width, 0.5f)
        }
        // As large as the longest word allows ("telephone" is the limit here).
        assertTrue(r.fontPx <= 64f && "telephone".length * r.fontPx * 0.6f <= 300f)
        assertTrue("telephone".length * (r.fontPx / 0.92f) * 0.6f > 300f || r.fontPx == 64f)
    }

    @Test
    fun `a single word on its line is centred`() {
        val r = LyricLayout.layout("telephone", 300f, 300f, 40f, 20f, measure = measure)
        val w = r.lines.single().words.single()
        assertEquals((300f - w.width) / 2f, w.x, 0.01f)
        // The block sits in the middle of the height.
        assertEquals((300f - r.lineHeightPx) / 2f, r.lines.single().y, 0.01f)
    }

    @Test
    fun `long lines get smaller type, never clipped`() {
        val long = "i wish that i could be like the cool kids because all the cool kids they seem to fit in and everything is fine"
        val r = LyricLayout.layout(long, 300f, 260f, 64f, 20f, measure = measure)
        check(r, 300f, 260f)
        assertTrue(r.fontPx < 64f)
        assertEquals(long.split(" ").size, r.lines.sumOf { it.words.size })
    }

    @Test
    fun `beyond the smallest size the block still shrinks to fit`() {
        val huge = (1..120).joinToString(" ") { "word$it" }
        val r = LyricLayout.layout(huge, 200f, 150f, 48f, 20f, measure = measure)
        check(r, 200f, 150f)
        assertTrue(r.fontPx < 20f)
    }

    @Test
    fun `one enormous word shrinks until it fits the width`() {
        val r = LyricLayout.layout("Supercalifragilisticexpialidocious", 200f, 300f, 64f, 20f, measure = measure)
        check(r, 200f, 300f)
    }

    @Test
    fun `nothing to show is an empty layout`() {
        assertTrue(LyricLayout.layout("   ", 300f, 300f, 64f, 20f, measure = measure).lines.isEmpty())
        assertTrue(LyricLayout.layout("words", 0f, 300f, 64f, 20f, measure = measure).lines.isEmpty())
    }
}
