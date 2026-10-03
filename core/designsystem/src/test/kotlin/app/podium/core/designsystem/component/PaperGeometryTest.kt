package app.podium.core.designsystem.component

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaperGeometryTest {

    // A phone's display: 411 × 560 dp with the title above and the mini player below.
    private val g = PaperGeometry(411.dp, 560.dp, 64.dp, 92.dp)

    @Test
    fun `the arc bulges right at mid-height and bends left toward the ends`() {
        assertEquals(g.apexX, g.arcX(g.centreY))
        assertTrue(g.arcX(g.padTop) < g.apexX)
        assertEquals(g.arcX(g.padTop).value, g.arcX(g.centreY * 2 - g.padTop).value, 0.01f)
    }

    @Test
    fun `the two glimpses are the same square at the same height, mirrored off either edge`() {
        assertTrue(g.leftBoxX < 0.dp, "the previous column's box runs off the left edge")
        assertTrue(g.rightBoxX + g.boxHeight > g.width, "the next column's box runs off the right edge")
        val leftVisible = g.leftBoxRight
        val rightVisible = g.width - g.rightBoxX
        assertTrue((leftVisible - rightVisible).value in -24f..24f, "both show about the same slice: $leftVisible vs $rightVisible")
    }

    @Test
    fun `neither glimpse reaches the rows`() {
        // At the boxes' top and bottom the rows have bent left; they must still clear the left box.
        val rowLeftAtBoxEdge = g.columnLeft - g.bend(g.boxHeight / 2)
        assertTrue(g.leftBoxRight < rowLeftAtBoxEdge)
        assertTrue(g.rightBoxX > g.apexX)
    }

    @Test
    fun `rows fade out before they drift under the title or the mini player`() {
        assertEquals(1f, g.within(g.centreY))
        assertEquals(1f, g.within(g.padTop))
        assertEquals(0f, g.within(g.padTop - 30.dp))
        assertEquals(0f, g.within(g.height - g.padBottom + 30.dp))
    }

    @Test
    fun `the previous screen's middle and its labels land inside its box`() {
        // Its mid-height maps to the box's mid-height…
        val mid = g.peekOriginY + g.centreY * g.peekScale
        assertEquals((g.boxTop + g.boxHeight / 2).value, mid.value, 0.01f)
        // …and its column starts just inside the box's visible edge.
        val labels = g.peekOriginX + g.columnLeft * g.peekScale
        assertTrue(labels > 0.dp && labels < g.leftBoxRight)
    }
}
