package app.podium.core.designsystem.shell

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.podium.core.designsystem.theme.DisplayTheme
import app.podium.core.designsystem.theme.oklchToColor
import app.podium.core.designsystem.theme.srgbToOklab
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * The device's finish (D-26): the body around the screen and the Wheel. [GLASS] is the original
 * Liquid Glass look; the others are solid, anodised-style colours with optional grain.
 */
enum class FinishPreset(val label: String, internal val baseArgb: Int?) {
    GLASS("Glass", null),
    STEEL_GRAY("Steel gray", 0xFF5A5F66.toInt()),
    BURGUNDY("Burgundy", 0xFF5C1D2B.toInt()),
    GLACIER_BLUE("Glacier blue", 0xFFB4CFDF.toInt()),
    SILVER("Silver", 0xFFD6D8DB.toInt()),
    CUSTOM("Custom color", null),
}

@Immutable
data class DeviceAppearance(
    val preset: FinishPreset = FinishPreset.GLASS,
    /** Used when [preset] is [FinishPreset.CUSTOM]. Opaque ARGB. */
    val customArgb: Int = DEFAULT_CUSTOM,
    /** Grain strength, 0–1. Solid finishes only. */
    val grain: Float = DEFAULT_GRAIN,
    /** The display's visual system. Carbon and Bone put the display in matte black hardware (D-29). */
    val display: DisplayTheme = DisplayTheme.GLASS,
) {
    val isGlass: Boolean get() = !display.isIndustrial && preset == FinishPreset.GLASS

    /** The solid base colour, or null for Glass. Industrial themes always use matte black hardware. */
    val baseArgb: Int? get() = when {
        display.isIndustrial -> MATTE_BLACK
        preset == FinishPreset.CUSTOM -> customArgb
        else -> preset.baseArgb
    }

    companion object {
        const val DEFAULT_CUSTOM = 0xFF2F6F5E.toInt()
        const val DEFAULT_GRAIN = 0.25f
        const val MATTE_BLACK = 0xFF171717.toInt()
    }
}

/**
 * Colours for one finish, derived in OKLCH so every base colour — including any custom hex — gets
 * a body with a gentle top-to-bottom light falloff and a Wheel that reads as a separate piece.
 */
@Immutable
data class ShellPalette(
    val isGlass: Boolean,
    /** Light bodies take dark legends and dark status-bar icons. */
    val isLight: Boolean,
    val bodyTop: Color,
    val bodyBottom: Color,
    val ring: Color,
    val ringRim: Color,
    val center: Color,
    val legend: Color,
    val pressShade: Color,
    val grain: Float,
    /** Matte industrial hardware: flat surfaces, no sheen, no drop shadows (D-29). */
    val matte: Boolean = false,
)

fun DeviceAppearance.palette(darkTheme: Boolean): ShellPalette {
    val base = baseArgb ?: return ShellPalette(
        isGlass = true,
        isLight = !darkTheme,
        bodyTop = Color.Unspecified,
        bodyBottom = Color.Unspecified,
        ring = Color.Unspecified,
        ringRim = Color.Unspecified,
        center = Color.Unspecified,
        legend = Color.Unspecified,
        pressShade = Color.Black.copy(alpha = if (darkTheme) 0.16f else 0.08f),
        grain = 0f,
    )
    if (display.isIndustrial) {
        // Matte black instrument hardware: a barely lighter wheel, a hairline rim, off-white legends.
        return ShellPalette(
            isGlass = false,
            isLight = false,
            bodyTop = Color(0xFF1A1A1A),
            bodyBottom = Color(0xFF141414),
            ring = Color(0xFF1F1F1E),
            ringRim = Color.White.copy(alpha = 0.07f),
            center = Color(0xFF181818),
            legend = Color(0xFFCFCBC2),
            pressShade = Color.White.copy(alpha = 0.05f),
            grain = grain.coerceIn(0f, 1f),
            matte = true,
        )
    }
    val (l, a, b) = srgbToOklab(base)
    val c = hypot(a, b)
    val h = Math.toDegrees(atan2(b, a))
    val light = l > 0.62
    fun lc(dl: Double, cScale: Double = 1.0) = oklchToColor((l + dl).coerceIn(0.05, 0.98), c * cScale, h)
    return ShellPalette(
        isGlass = false,
        isLight = light,
        bodyTop = lc(+0.035),
        bodyBottom = lc(-0.055),
        // Light bodies get a paler wheel (the classic white-wheel look); dark ones a deeper wheel.
        ring = if (light) lc(+0.07, 0.55) else lc(-0.05, 0.9),
        ringRim = if (light) lc(-0.14).copy(alpha = 0.45f) else Color.White.copy(alpha = 0.10f),
        center = lc(0.0),
        legend = if (light) oklchToColor(0.45, c * 0.5, h) else oklchToColor(0.86, c * 0.3, h),
        pressShade = Color.Black.copy(alpha = if (light) 0.08f else 0.18f),
        grain = grain.coerceIn(0f, 1f),
    )
}

/** "#RRGGBB" for an opaque colour. */
fun formatHex(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

/** Parses "#RRGGBB" or "RRGGBB" (case-insensitive); null if it isn't exactly six hex digits. */
fun parseHex(text: String): Int? {
    val digits = text.trim().removePrefix("#")
    if (digits.length != 6 || !digits.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
    return (0xFF000000.toInt()) or digits.toInt(16)
}

/**
 * Walks a colour's hue in OKLCH. Lightness and chroma come from the starting colour and only the
 * angle accumulates, so a full turn returns to the start instead of drifting (each step's gamut
 * clipping is never fed back into the next).
 */
@Immutable
data class HueWalk(private val l: Double, private val c: Double, private val h: Double) {
    fun turned(degrees: Double): HueWalk = copy(h = h + degrees)

    val argb: Int get() = oklchToColor(l, c, h).toArgb()

    companion object {
        fun of(argb: Int): HueWalk {
            val (l, a, b) = srgbToOklab(argb)
            return HueWalk(l, hypot(a, b), Math.toDegrees(atan2(b, a)))
        }
    }
}
