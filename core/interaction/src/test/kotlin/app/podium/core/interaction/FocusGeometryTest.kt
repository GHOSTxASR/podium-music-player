package app.podium.core.interaction

import app.podium.core.interaction.FocusGeometry.Row
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The focus geometry (D-40): where the focused row goes, how the ends fade, where the lens sits. */
class FocusGeometryTest {

    private val readable = 500
    private val h = 50

    private fun row(index: Int, scrolledBy: Int = 0) = Row(index * h - scrolledBy, h)

    private fun centre(index: Int, scrolledBy: Int, count: Int) = FocusGeometry.scrollDelta(
        FocusScroll.CENTRE, readable, row(index, scrolledBy), row(index - 1, scrolledBy).takeIf { index > 0 },
        row(index + 1, scrolledBy).takeIf { index < count - 1 }, index > 0, index < count - 1,
    )

    @Test
    fun `on the paper the focused row is asked to the middle of the readable region`() {
        // Row 12 of 50 at the top of an unscrolled list: its centre (625) goes to 250.
        assertEquals(375f, centre(12, 0, 50))
        // Already centred: nothing to do.
        assertEquals(0f, centre(12, 375, 50))
    }

    @Test
    fun `at the ends the list's own clamp holds the first row at the top and the last at the bottom`() {
        // Row 0 asks to scroll backwards (negative); an unscrolled list can't, so it stays flush.
        assert(centre(0, 0, 50) < 0f)
        // Row 49 of 50 (content 2500, max scroll 2000) asks beyond the end; the list stops at 2000,
        // which puts the row's bottom exactly on the region's bottom.
        val delta = centre(49, 1500, 50)
        val scrolled = (1500 + delta).coerceAtMost(2000f)
        assertEquals(readable.toFloat(), 49f * h + h - scrolled)
    }

    @Test
    fun `plain lists keep a neighbour in view and the ends flush`() {
        fun edge(focused: Row, previous: Row?, next: Row?, hasPrevious: Boolean, hasNext: Boolean) =
            FocusGeometry.scrollDelta(FocusScroll.EDGE, readable, focused, previous, next, hasPrevious, hasNext)
        // Comfortably inside: no scroll.
        assertEquals(0f, edge(Row(200, h), Row(150, h), Row(250, h), true, true))
        // The next row is half below the bottom: scroll so it shows.
        assertEquals(25f, edge(Row(425, h), Row(375, h), Row(475, h), true, true))
        // The next row isn't laid out yet: assume it's as tall as this one.
        assertEquals(50f, edge(Row(450, h), Row(400, h), null, true, true))
        // The first row partly under the title: scroll back until it is flush (was never fixed before).
        assertEquals(-20f, edge(Row(-20, h), null, Row(30, h), false, true))
        // The last row partly under the bottom: scroll until it is flush.
        assertEquals(30f, edge(Row(480, h), Row(430, h), null, true, false))
    }

    @Test
    fun `rows too tall to show with neighbours show alone`() {
        val tall = Row(100, 300)
        assertEquals(0f, FocusGeometry.scrollDelta(FocusScroll.EDGE, readable, tall, Row(-200, 300), Row(400, 300), true, true))
        assertEquals(0f, FocusGeometry.scrollDelta(FocusScroll.EDGE, 0, tall, null, null, false, false))
    }

    @Test
    fun `an end fades only as far as the list continues beyond it`() {
        // A list that starts at the top: nothing above, so no fade there.
        assertEquals(0f, FocusGeometry.endStrength(FocusGeometry.hiddenAbove(0, 0, h), h.toFloat()))
        // Scrolled by half a row: half strength. A row or more: full.
        assertEquals(0.5f, FocusGeometry.endStrength(FocusGeometry.hiddenAbove(0, -25, h), h.toFloat()))
        assertEquals(1f, FocusGeometry.endStrength(FocusGeometry.hiddenAbove(3, 10, h), h.toFloat()))
        // A short list (last row ends at 150 of 500): nothing below.
        assertEquals(0f, FocusGeometry.endStrength(FocusGeometry.hiddenBelow(2, 150, h, 3, readable), h.toFloat()))
        // More rows than are laid out: full fade at the bottom.
        assertEquals(1f, FocusGeometry.endStrength(FocusGeometry.hiddenBelow(11, 520, h, 50, readable), h.toFloat()))
        // The last row 10 px past the bottom.
        assertEquals(0.2f, FocusGeometry.endStrength(FocusGeometry.hiddenBelow(9, 510, h, 10, readable), h.toFloat()), 0.001f)
    }

    @Test
    fun `the lens slides between rows and is glued to them`() {
        val rows = mapOf(3 to Row(100, 50), 4 to Row(150, 60))
        // Half-way from row 3 to row 4: half-way in place and in height.
        assertEquals(125f to 55f, FocusGeometry.lens(3.5f, 4) { rows[it] })
        // On a row exactly.
        assertEquals(150f to 60f, FocusGeometry.lens(4f, 4) { rows[it] })
        // The list scrolled by 40: the lens moves with it.
        val scrolled = rows.mapValues { (_, r) -> r.copy(offset = r.offset - 40) }
        assertEquals(85f to 55f, FocusGeometry.lens(3.5f, 4) { scrolled[it] })
        // Sliding from a row that is no longer laid out: the lens shows on the focused row.
        assertEquals(150f to 60f, FocusGeometry.lens(1.5f, 4) { rows[it] })
        // Nothing to sit on: hidden.
        assertNull(FocusGeometry.lens(7f, 9) { rows[it] })
    }
}
