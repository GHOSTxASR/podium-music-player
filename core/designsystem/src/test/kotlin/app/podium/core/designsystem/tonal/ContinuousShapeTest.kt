package app.podium.core.designsystem.tonal

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kyant.shapes.RoundedCornerStyle
import com.kyant.shapes.RoundedRectangularShape
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Continuous corners on glass (D-103): Glass's lens refracts only through the glass library's own
 * rounded rectangles, and on a phone with real glass any other outline stopped Podium at its first
 * frame. A continuous shape hands the glass the library's continuous rectangle, with its own radii.
 */
class ContinuousShapeTest {

    private val density = Density(2f)

    @Test
    fun glassGetsAShapeItsLensCanRefractThrough() {
        val glass = ContinuousShape(16.dp).forGlass()
        assertIs<RoundedRectangularShape>(glass)
        assertEquals(RoundedCornerStyle.Continuous, glass.style)
    }

    @Test
    fun withTheSameCorners() {
        val glass = assertIs<RoundedRectangularShape>(ContinuousShape(16.dp).forGlass())
        assertEquals(RoundedRectangularShape.Corners(32f, 32f, 32f, 32f), glass.corners(Size(200f, 120f), LayoutDirection.Ltr, density))
    }

    @Test
    fun mirroredRightToLeft() {
        val glass = assertIs<RoundedRectangularShape>(ContinuousShape(topStart = 4.dp, topEnd = 8.dp, bottomEnd = 12.dp, bottomStart = 16.dp).forGlass())
        assertEquals(RoundedRectangularShape.Corners(8f, 16f, 24f, 32f), glass.corners(Size(200f, 200f), LayoutDirection.Ltr, density))
        assertEquals(RoundedRectangularShape.Corners(16f, 8f, 32f, 24f), glass.corners(Size(200f, 200f), LayoutDirection.Rtl, density))
    }
}
