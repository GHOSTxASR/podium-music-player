package app.podium.core.designsystem.theme

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The album-art environment (design-system.md §2.3): a restrained ambient tint derived from the
 * artwork, rendered as a gentle vertical light falloff — never a blob, never more than one hue.
 */
@Immutable
data class Atmosphere(val top: Color, val bottom: Color) {
    companion object {
        fun neutral(colors: PodiumColors): Atmosphere = fromOklch(
            l = if (colors.isDark) 0.165 else 0.975,
            c = if (colors.isDark) 0.010 else 0.004,
            h = 262.0,
            isDark = colors.isDark,
        )

        /** Derive from artwork pixels. Neutral artwork stays neutral; vivid artwork tints softly. */
        fun fromArtwork(bitmap: Bitmap, isDark: Boolean): Atmosphere {
            val small = Bitmap.createScaledBitmap(bitmap, 32, 32, true)
            var sumA = 0.0
            var sumB = 0.0
            var weight = 0.0
            var sumL = 0.0
            var count = 0
            for (y in 0 until small.height) for (x in 0 until small.width) {
                val (l, a, b) = srgbToOklab(small.getPixel(x, y))
                val chroma = hypot(a, b)
                sumL += l
                count++
                if (chroma >= 0.03) {
                    sumA += a * chroma
                    sumB += b * chroma
                    weight += chroma
                }
            }
            if (small !== bitmap) small.recycle()
            val meanL = if (count > 0) sumL / count else 0.5
            if (weight == 0.0) {
                return fromOklch(if (isDark) 0.18 else 0.965, if (isDark) 0.008 else 0.004, 262.0, isDark)
            }
            val a = sumA / weight
            val b = sumB / weight
            val chroma = hypot(a, b)
            val hue = Math.toDegrees(atan2(b, a)).let { if (it < 0) it + 360 else it }
            return if (isDark) {
                val l = 0.185 + 0.02 * meanL // darker artwork → darker room
                fromOklch(l, min(chroma * 0.35, 0.040), hue, true)
            } else {
                val l = 0.955 + 0.015 * meanL
                fromOklch(l, min(chroma * 0.20, 0.020), hue, false)
            }
        }

        private fun fromOklch(l: Double, c: Double, h: Double, isDark: Boolean): Atmosphere {
            val topL = l + if (isDark) 0.025 else 0.012
            val bottomL = l - 0.010
            return Atmosphere(oklchToColor(topL, c, h), oklchToColor(bottomL, c, h))
        }
    }
}

@Composable
fun AtmosphereBackground(atmosphere: Atmosphere, modifier: Modifier = Modifier) {
    val motion = PodiumTheme.motion
    val top by animateColorAsState(atmosphere.top, motion.atmosphere(), label = "atmosphereTop")
    val bottom by animateColorAsState(atmosphere.bottom, motion.atmosphere(), label = "atmosphereBottom")
    Box(modifier.background(Brush.verticalGradient(listOf(top, bottom))))
}

// --- OKLab / OKLCH (Björn Ottosson's published formulas) -----------------------------------------

private fun srgbToLinear(c: Double) = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
private fun linearToSrgb(c: Double) = if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1 / 2.4) - 0.055

internal fun srgbToOklab(argb: Int): Triple<Double, Double, Double> {
    val r = srgbToLinear(((argb shr 16) and 0xFF) / 255.0)
    val g = srgbToLinear(((argb shr 8) and 0xFF) / 255.0)
    val b = srgbToLinear((argb and 0xFF) / 255.0)
    val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
    val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
    val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
    return Triple(
        0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
        1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
        0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
    )
}

internal fun oklchToColor(l: Double, c: Double, hDeg: Double): Color {
    val h = Math.toRadians(hDeg)
    val a = c * cos(h)
    val b = c * sin(h)
    val l_ = (l + 0.3963377774 * a + 0.2158037573 * b).pow(3)
    val m_ = (l - 0.1055613458 * a - 0.0638541728 * b).pow(3)
    val s_ = (l - 0.0894841775 * a - 1.2914855480 * b).pow(3)
    fun ch(v: Double) = linearToSrgb(v).let { max(0.0, min(1.0, it)) }.toFloat()
    return Color(
        red = ch(4.0767416621 * l_ - 3.3077115913 * m_ + 0.2309699292 * s_),
        green = ch(-1.2684380046 * l_ + 2.6097574011 * m_ - 0.3413193965 * s_),
        blue = ch(-0.0041960863 * l_ - 0.7034186147 * m_ + 1.7076147010 * s_),
    )
}
