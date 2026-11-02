package app.podium.core.designsystem.tonal

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kyant.shapes.RoundedCornerStyle
import com.kyant.shapes.UnevenRoundedRectangle
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Continuous corners, as Apple draws them (D-103): the curve eases out of each straight side and
 * back into the next, instead of a circular arc meeting the side at a crease — a squircle. A corner
 * with [smoothing] 0.6 (Apple's own) spends 1.6 × its radius turning; one too small for that keeps
 * as much smoothing as fits, and a corner as round as the shape is short becomes a plain capsule end.
 *
 * Corners are given as start/end so the shape mirrors with the layout direction.
 */
@Immutable
class ContinuousShape(
    private val topStart: Dp,
    private val topEnd: Dp = topStart,
    private val bottomEnd: Dp = topStart,
    private val bottomStart: Dp = topStart,
    private val smoothing: Float = AppleSmoothing,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val ltr = layoutDirection == LayoutDirection.Ltr
        fun px(d: Dp) = with(density) { d.toPx() }
        val tl = px(if (ltr) topStart else topEnd)
        val tr = px(if (ltr) topEnd else topStart)
        val br = px(if (ltr) bottomEnd else bottomStart)
        val bl = px(if (ltr) bottomStart else bottomEnd)
        return Outline.Generic(continuousRect(Rect(0f, 0f, size.width, size.height), tl, tr, br, bl, smoothing))
    }

    /**
     * The same corners as the glass library's own continuous rectangle (D-103). Glass's lens refracts
     * only through the library's rounded rectangles; handed any other outline, it stops the app.
     */
    internal fun forGlass(): Shape = UnevenRoundedRectangle(topStart, topEnd, bottomEnd, bottomStart, RoundedCornerStyle.Continuous)

    override fun equals(other: Any?): Boolean = other is ContinuousShape && other.topStart == topStart && other.topEnd == topEnd &&
        other.bottomEnd == bottomEnd && other.bottomStart == bottomStart && other.smoothing == smoothing

    override fun hashCode(): Int = ((((topStart.hashCode() * 31 + topEnd.hashCode()) * 31 + bottomEnd.hashCode()) * 31 + bottomStart.hashCode()) * 31) + smoothing.hashCode()

    companion object {
        /** iOS's corner smoothing. */
        const val AppleSmoothing = 0.6f

        /** A capsule: every corner as round as the shape is short. */
        val Capsule = ContinuousShape(1000.dp)
    }
}

/** One corner's construction: two cubic easements around a shortened circular arc. */
private class Corner(val r: Float, val p: Float, val a: Float, val b: Float, val c: Float, val d: Float, val arcDegrees: Float, val alpha: Float)

private fun corner(radius: Float, smoothing: Float, budget: Float): Corner {
    val r = radius.coerceIn(0f, budget)
    if (r <= 0f) return Corner(0f, 0f, 0f, 0f, 0f, 0f, 90f, 0f)
    // As much smoothing as the side leaves room for.
    val s = smoothing.coerceAtMost((budget / r - 1f).coerceAtLeast(0f)).coerceAtLeast(0f)
    val p = min((1f + s) * r, budget)
    val arcDegrees = 90f * (1f - s)
    val arcChord = sin(rad(arcDegrees / 2f)) * r * sqrt(2f)
    val alpha = (90f - arcDegrees) / 2f
    val p3ToP4 = r * tan(rad(alpha / 2f))
    val beta = 45f * s
    val c = p3ToP4 * cos(rad(beta))
    val d = c * tan(rad(beta))
    val b = ((p - arcChord - c - d) / 3f).coerceAtLeast(0f)
    return Corner(r, p, 2f * b, b, c, d, arcDegrees, alpha)
}

/**
 * The outline of [rect] with continuous corners of radii [tl], [tr], [br], [bl] (px), drawn
 * clockwise from the top edge.
 */
fun continuousRect(rect: Rect, tl: Float, tr: Float, br: Float, bl: Float, smoothing: Float = ContinuousShape.AppleSmoothing): Path {
    val w = rect.width
    val h = rect.height
    val budget = min(w, h) / 2f
    val x0 = rect.left
    val y0 = rect.top
    val x1 = rect.right
    val y1 = rect.bottom
    val cTL = corner(tl, smoothing, budget)
    val cTR = corner(tr, smoothing, budget)
    val cBR = corner(br, smoothing, budget)
    val cBL = corner(bl, smoothing, budget)
    return Path().apply {
        moveTo(x0 + cTL.p, y0)
        // Top right.
        lineTo(x1 - cTR.p, y0)
        if (cTR.r > 0f) with(cTR) {
            cubicTo(x1 - p + a, y0, x1 - p + a + b, y0, x1 - p + a + b + c, y0 + d)
            arcTo(Rect(x1 - 2 * r, y0, x1, y0 + 2 * r), -90f + alpha, arcDegrees, false)
            cubicTo(x1, y0 + p - a - b, x1, y0 + p - a, x1, y0 + p)
        }
        // Bottom right.
        lineTo(x1, y1 - cBR.p)
        if (cBR.r > 0f) with(cBR) {
            cubicTo(x1, y1 - p + a, x1, y1 - p + a + b, x1 - d, y1 - p + a + b + c)
            arcTo(Rect(x1 - 2 * r, y1 - 2 * r, x1, y1), alpha, arcDegrees, false)
            cubicTo(x1 - p + a + b, y1, x1 - p + a, y1, x1 - p, y1)
        }
        // Bottom left.
        lineTo(x0 + cBL.p, y1)
        if (cBL.r > 0f) with(cBL) {
            cubicTo(x0 + p - a, y1, x0 + p - a - b, y1, x0 + p - a - b - c, y1 - d)
            arcTo(Rect(x0, y1 - 2 * r, x0 + 2 * r, y1), 90f + alpha, arcDegrees, false)
            cubicTo(x0, y1 - p + a + b, x0, y1 - p + a, x0, y1 - p)
        }
        // Top left.
        lineTo(x0, y0 + cTL.p)
        if (cTL.r > 0f) with(cTL) {
            cubicTo(x0, y0 + p - a, x0, y0 + p - a - b, x0 + d, y0 + p - a - b - c)
            arcTo(Rect(x0, y0, x0 + 2 * r, y0 + 2 * r), 180f + alpha, arcDegrees, false)
            cubicTo(x0 + p - a - b, y0, x0 + p - a, y0, x0 + p, y0)
        }
        close()
    }
}

private fun rad(deg: Float) = Math.toRadians(deg.toDouble()).toFloat()
