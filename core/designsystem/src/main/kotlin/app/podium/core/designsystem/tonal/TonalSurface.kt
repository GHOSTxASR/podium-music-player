package app.podium.core.designsystem.tonal

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How high a solid surface sits on Carbon (D-101), from the action row's tray up to a key under the
 * finger. Each level is a step lighter, tinted toward the playing album ([AlbumTone]); [elevation]
 * is the shadow it casts by default.
 */
enum class SurfaceLevel(val elevation: Dp) {
    /** A tray that groups controls: the action row, the header's back key. */
    Low(1.dp),

    /** Floating over the content: the mini player, menus, notices. */
    Base(8.dp),

    /** A key: the transport, the focus band. */
    High(3.dp),

    /** A key under the finger; the Wheel's focused action. */
    Highest(2.dp),
    ;

    fun colorIn(tone: AlbumTone): Color = when (this) {
        Low -> tone.surfaceLow
        Base -> tone.surface
        High -> tone.surfaceHigh
        Highest -> tone.surfaceHighest
    }
}

/**
 * Carbon's corner radii (D-101): one family, concentric with the display's corner. A surface set
 * the edge margin in from the display's edge takes the display's radius less that margin
 * (`EdgeCorner`, D-103); keys and bands sit further in.
 */
object CarbonRadius {
    /** The display panel itself (continuous). */
    val display: Dp = 26.dp

    /** Menus and notices: large floating panels. */
    val sheet: Dp = 20.dp

    /** Keys and the trays that hold them. */
    val key: Dp = 16.dp

    /** The focus band; preview tiles. */
    val band: Dp = 12.dp

    /** Small labels. */
    val chip: Dp = 8.dp

    /** Artwork: rounder as it grows, never a pill (44 dp → 8, 64 dp → 11.5, Now Playing → 14). */
    fun artwork(size: Dp): Dp = (size * 0.18f).coerceIn(6.dp, 14.dp)
}

/**
 * A solid Carbon surface (D-101): opaque, never glass. Its colour is its [level] in the playing
 * album's tone (or [fill], for a surface of another role — the play key is the accent), lit from
 * above: a fine highlight along its top edge, a darker lower edge, and a soft shadow beneath it
 * ([elevation]). [pressed] (read while drawing, so a press recomposes nothing) sinks it: the shade
 * deepens and the shadow draws in, as a key pushed into its housing.
 */
@Composable
fun Modifier.tonalSurface(
    shape: Shape,
    level: SurfaceLevel = SurfaceLevel.Base,
    elevation: Dp = level.elevation,
    pressed: () -> Boolean = { false },
    fill: (AlbumTone.() -> Color)? = null,
): Modifier {
    val tone = LocalAlbumTone.current
    return this
        .graphicsLayer {
            val e = elevation.toPx()
            shadowElevation = if (pressed()) e * 0.35f else e
            this.shape = shape
            clip = false
            ambientShadowColor = Color.Black
            spotShadowColor = Color.Black
        }
        .drawWithCache {
            val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
            val rim = Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.12f),
                0.4f to Color.White.copy(alpha = 0.025f),
                1f to Color.Black.copy(alpha = 0.24f),
            )
            val rimWidth = 2.dp.toPx()
            onDrawBehind {
                drawPath(path, fill?.invoke(tone) ?: level.colorIn(tone))
                if (pressed()) drawPath(path, Color.Black.copy(alpha = 0.16f))
                // Only the inner half of the stroke shows: a 1 dp edge inside the shape.
                clipPath(path) { drawPath(path, rim, style = Stroke(rimWidth)) }
            }
        }
}
