package app.podium.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import app.podium.core.designsystem.type.DisplayFont
import app.podium.core.designsystem.type.LyricsFont

/** What sits behind the virtual display's content (PODIUM_CUSTOMIZATION.md §4). */
enum class DisplayBackground(val label: String) {
    /** The theme's own display: Glass's tinted canvas, or Custom's colour. */
    NONE("None"),
    SOLID("Solid"),
    IMAGE("Image"),
}

/** How far secondary text steps back from primary text. High brings it forward. */
enum class TextContrast(val label: String) {
    STANDARD("Standard"),
    HIGH("High"),
}

/**
 * The listener's customization of the virtual display (PODIUM_CUSTOMIZATION.md §2): its font, its
 * background, how strongly a background image shows, and text contrast. The theme ([DisplayTheme])
 * stays the visual system; this only dresses it.
 *
 * Readability can't be broken by a choice here: on a solid colour or an image, the ink (light or
 * dark) is chosen for the strongest contrast with what's actually behind it, and an image is
 * always laid over its solid colour at no more than [imageOpacity].
 */
@Immutable
data class VirtualDisplay(
    val font: DisplayFont = DisplayFont.CLASSIC,
    /** The lyrics screen's face (D-50): the display's own, or one of its own. */
    val lyricsFont: LyricsFont = LyricsFont.SAME_AS_DISPLAY,
    val background: DisplayBackground = DisplayBackground.NONE,
    /** The solid colour (opaque ARGB): the background itself, or the base under an image. */
    val solidArgb: Int = DEFAULT_SOLID,
    /** A content URI the listener chose with the system photo picker; read access is persisted. */
    val imageUri: String? = null,
    /** 0–1: how strongly the image shows over the solid colour. */
    val imageOpacity: Float = DEFAULT_IMAGE_OPACITY,
    val contrast: TextContrast = TextContrast.STANDARD,
) {
    /**
     * The background a theme actually shows. Carbon and Bone keep their own display (D-29: album art
     * is the only colour there); Custom is always at least its solid colour.
     */
    fun backgroundFor(theme: DisplayTheme): DisplayBackground = when (theme) {
        DisplayTheme.CARBON, DisplayTheme.BONE -> DisplayBackground.NONE
        DisplayTheme.CUSTOM -> if (background == DisplayBackground.NONE) DisplayBackground.SOLID else background
        DisplayTheme.GLASS -> background
    }

    companion object {
        /** A deep slate: a calm first choice that reads with light ink. */
        const val DEFAULT_SOLID = 0xFF22313A.toInt()
        const val DEFAULT_IMAGE_OPACITY = 0.5f
    }
}

/** A decoded background image and its average luminance (0–1), provided by the app. */
@Immutable
class DisplayImage(val bitmap: ImageBitmap, val luminance: Float)

/**
 * What the display draws behind its content, resolved for the current theme: the solid colour, a
 * picture over it at [imageAlpha], and over the picture a veil of the display's own colour at
 * [veil] so text stays readable on a busy picture.
 */
@Immutable
data class DisplaySurface(val solid: Color, val image: ImageBitmap?, val imageAlpha: Float, val veil: Float = 0f)

/** The resolved surface, or null when the theme's own canvas is shown. Read by `VirtualScreen`. */
val LocalDisplaySurface = staticCompositionLocalOf<DisplaySurface?> { null }

/** Colours and surface for one theme and customization (PODIUM_CUSTOMIZATION.md §5). */
object DisplayColors {

    /** How much of an image's opacity High contrast keeps: a busy picture steps back further. */
    const val HIGH_CONTRAST_IMAGE_FACTOR = 0.6f

    /** The veil of the display's colour over a picture: always some, more with High contrast. */
    const val STANDARD_VEIL = 0.22f
    const val HIGH_CONTRAST_VEIL = 0.4f

    fun surface(theme: DisplayTheme, display: VirtualDisplay, image: DisplayImage?): DisplaySurface? {
        val solid = Color(display.solidArgb)
        return when (display.backgroundFor(theme)) {
            DisplayBackground.NONE -> null
            DisplayBackground.SOLID -> DisplaySurface(solid, null, 0f)
            // An image that couldn't be read falls back to the solid colour.
            DisplayBackground.IMAGE -> if (image == null) {
                DisplaySurface(solid, null, 0f)
            } else {
                val high = display.contrast == TextContrast.HIGH
                val factor = if (high) HIGH_CONTRAST_IMAGE_FACTOR else 1f
                DisplaySurface(solid, image.bitmap, (display.imageOpacity * factor).coerceIn(0f, 1f), if (high) HIGH_CONTRAST_VEIL else STANDARD_VEIL)
            }
        }
    }

    /** The luminance the ink has to read against: the solid colour, mixed with the image and veil as drawn. */
    fun effectiveLuminance(surface: DisplaySurface, image: DisplayImage?): Float {
        val base = surface.solid.luminance()
        if (surface.image == null || image == null) return base
        val picture = base + (image.luminance - base) * surface.imageAlpha
        return picture + (base - picture) * surface.veil
    }

    fun colors(theme: DisplayTheme, darkSystem: Boolean, display: VirtualDisplay, image: DisplayImage?): PodiumColors {
        val surface = surface(theme, display, image)
        val base = when (theme) {
            DisplayTheme.CARBON -> CarbonColors
            DisplayTheme.BONE -> BoneColors
            DisplayTheme.GLASS -> if (surface == null) {
                if (darkSystem) DarkColors else LightColors
            } else {
                // Glass on your own background: light or dark glass, whichever reads better on it.
                val glass = if (prefersDarkInk(effectiveLuminance(surface, image))) LightColors else DarkColors
                glass.copy(canvas = if (surface.image == null) readablePaper(surface.solid, glass.labelPrimary) else surface.solid)
            }
            DisplayTheme.CUSTOM -> custom(surface ?: DisplaySurface(Color(display.solidArgb), null, 0f), image)
        }
        return if (display.contrast == TextContrast.HIGH) base.highContrast() else base
    }

    /**
     * Custom: the matte instrument (Carbon's and Bone's system — lit selection, no glass) on the
     * listener's own display colour, with near-black or near-white ink, whichever reads better.
     */
    private fun custom(surface: DisplaySurface, image: DisplayImage?): PodiumColors {
        val darkInk = prefersDarkInk(effectiveLuminance(surface, image))
        val template = if (darkInk) BoneColors else CarbonColors
        val ink = if (darkInk) DARK_INK else LIGHT_INK
        val paper = if (surface.image == null) readablePaper(surface.solid, ink) else surface.solid
        return template.copy(
            isDark = !darkInk,
            canvas = paper,
            canvasRaised = lerp(paper, ink, 0.07f),
            labelPrimary = ink,
            // Over a picture the quieter inks stay nearer the primary: the picture is busier than paper.
            labelSecondary = quieter(ink, paper, if (surface.image == null) 0.36f else 0.16f, SECONDARY_MIN_CONTRAST),
            labelTertiary = quieter(ink, paper, if (surface.image == null) 0.55f else 0.3f, TERTIARY_MIN_CONTRAST),
            separator = lerp(paper, ink, 0.14f),
            highlight = ink,
            highlightText = ink,
            onHighlight = paper,
            like = ink,
        )
    }

    /**
     * The listener's colour, or — when even the better ink can't reach 4.5:1 on it (a few mid-tones,
     * such as a saturated mid blue) — the same colour nudged just far enough away from the ink.
     * The setting keeps the colour as chosen; only the display adjusts.
     */
    fun readablePaper(paper: Color, ink: Color, minContrast: Float = PRIMARY_MIN_CONTRAST): Color {
        val inkL = ink.luminance()
        val away = if (inkL > 0.5f) Color.Black else Color.White
        var t = 0f
        while (t <= 1f) {
            val c = lerp(paper, away, t)
            if (contrast(c.luminance(), inkL) >= minContrast) return c
            t += 0.02f
        }
        return away
    }

    /**
     * The ink stepped [mix] of the way toward the paper — or less, as far as it still clears
     * [minContrast] against it (a mid-tone paper leaves less room between ink and paper).
     */
    private fun quieter(ink: Color, paper: Color, mix: Float, minContrast: Float): Color {
        val paperL = paper.luminance()
        var t = mix
        while (t > 0f) {
            val c = lerp(ink, paper, t)
            if (contrast(c.luminance(), paperL) >= minContrast) return c
            t -= 0.02f
        }
        return ink
    }

    private const val PRIMARY_MIN_CONTRAST = 4.6f
    private const val SECONDARY_MIN_CONTRAST = 4.5f
    private const val TERTIARY_MIN_CONTRAST = 3f

    /** True when near-black ink has more contrast than near-white ink against [luminance]. */
    fun prefersDarkInk(luminance: Float): Boolean =
        contrast(luminance, DARK_INK.luminance()) >= contrast(luminance, LIGHT_INK.luminance())

    /** WCAG contrast ratio of two relative luminances. */
    fun contrast(a: Float, b: Float): Float {
        val hi = maxOf(a, b)
        val lo = minOf(a, b)
        return (hi + 0.05f) / (lo + 0.05f)
    }

    private fun PodiumColors.highContrast(): PodiumColors = copy(
        labelSecondary = lerp(labelSecondary, labelPrimary, 0.55f),
        labelTertiary = lerp(labelTertiary, labelPrimary, 0.45f),
        separator = lerp(separator, labelPrimary, 0.2f),
    )

    val DARK_INK = Color(0xFF121212)
    val LIGHT_INK = Color(0xFFF4F3EF)
}
