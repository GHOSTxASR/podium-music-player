package app.podium.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Colour tokens (design-system.md §2.1). Values come from the OKLCH definitions; contrast against
 * the worst-case tinted atmosphere was verified (primary ≥ 15:1, secondary ≥ 6.3:1).
 */
@Immutable
data class PodiumColors(
    val isDark: Boolean,
    val canvas: Color,
    val canvasRaised: Color,
    val labelPrimary: Color,
    val labelSecondary: Color,
    val labelTertiary: Color,
    val separator: Color,
    val highlight: Color,
    val highlightText: Color,
    val onHighlight: Color,
    val like: Color,
    val critical: Color,
    val warning: Color,
    val positive: Color,
    /** Base tint layered onto glass surfaces (material-specific opacity applied by the glass system). */
    val glassTint: Color,
    val glassRim: Color,
    val shadow: Color,
    /** Glass, the matte industrial instrument (Bone, Custom: D-29), or Carbon's tonal display (D-101). */
    val style: DisplayStyle = DisplayStyle.GLASS,
) {
    val isIndustrial: Boolean get() = style == DisplayStyle.INDUSTRIAL

    /** Carbon (D-101): solid, rounded surfaces over the playing album's atmosphere. */
    val isTonal: Boolean get() = style == DisplayStyle.TONAL
}

enum class DisplayStyle {
    GLASS,
    INDUSTRIAL,

    /**
     * Carbon (D-101): no glass anywhere on the display, like the industrial look, but its controls are
     * solid rounded surfaces with real depth, tinted by the playing album, over a defocused field of
     * its colours. Text stays neutral.
     */
    TONAL,
}

val DarkColors = PodiumColors(
    isDark = true,
    canvas = Color(0xFF0C0E13),
    canvasRaised = Color(0xFF171A1F),
    labelPrimary = Color(0xFFF5F7F9),
    labelSecondary = Color(0xFFAEB3BA),
    labelTertiary = Color(0xFF7C8088),
    separator = Color(0xFF2B2E33),
    highlight = Color(0xFF367FE0),
    highlightText = Color(0xFF539AF2),
    onHighlight = Color(0xFFFFFFFF),
    like = Color(0xFFF46A82),
    critical = Color(0xFFF7665B),
    warning = Color(0xFFF5AE39),
    positive = Color(0xFF61CB7C),
    glassTint = Color(0xFF000000),
    glassRim = Color(0xFFFFFFFF),
    shadow = Color(0xFF000000),
)

val LightColors = PodiumColors(
    isDark = false,
    canvas = Color(0xFFF5F7F9),
    canvasRaised = Color(0xFFFFFFFF),
    labelPrimary = Color(0xFF161A1F),
    labelSecondary = Color(0xFF52575F),
    labelTertiary = Color(0xFF787C83),
    separator = Color(0xFFD8DBDF),
    highlight = Color(0xFF1153BA),
    highlightText = Color(0xFF1C65C8),
    onHighlight = Color(0xFFFFFFFF),
    like = Color(0xFFCE2854),
    critical = Color(0xFFCC2827),
    warning = Color(0xFFB17000),
    positive = Color(0xFF218A45),
    glassTint = Color(0xFFFFFFFF),
    glassRim = Color(0xFFFFFFFF),
    shadow = Color(0xFF000000),
)

/**
 * Carbon's display (D-101): deep graphite lit by the playing album. Text stays warm white and neutral
 * greys; the album's colour reaches the screen only through the tonal surfaces and accent
 * (`tonal.AlbumTone`) and the atmosphere behind the content. [highlight] is the accent Carbon falls
 * back to with no cover to take one from: platinum.
 */
val CarbonTonalColors = PodiumColors(
    isDark = true,
    canvas = Color(0xFF0B0B0D),
    canvasRaised = Color(0xFF1A1A1D),
    labelPrimary = Color(0xFFF3F1EC),
    labelSecondary = Color(0xFFB4B1A9),
    labelTertiary = Color(0xFF7B7973),
    separator = Color(0xFF2A2A2E),
    highlight = Color(0xFFE6E3DC),
    highlightText = Color(0xFFE6E3DC),
    onHighlight = Color(0xFF121214),
    like = Color(0xFFE6E3DC),
    critical = Color(0xFFF08470),
    warning = Color(0xFFE5B158),
    positive = Color(0xFF93CE9C),
    glassTint = Color(0xFF000000),
    glassRim = Color(0xFFFFFFFF),
    shadow = Color(0xFF000000),
    style = DisplayStyle.TONAL,
)

/**
 * The matte black instrument ink (D-29): off-white type, muted greys, a white indicator. Carbon's
 * display before D-101; still the black paper of Classic lyrics, the space's panels, and Custom's
 * template on a dark colour.
 */
val CarbonColors = PodiumColors(
    isDark = true,
    canvas = Color(0xFF0A0A0A),
    canvasRaised = Color(0xFF161615),
    labelPrimary = Color(0xFFF2F0EB),
    labelSecondary = Color(0xFF9B988F),
    labelTertiary = Color(0xFF65635D),
    separator = Color(0xFF262523),
    highlight = Color(0xFFF2F0EB),
    highlightText = Color(0xFFF2F0EB),
    onHighlight = Color(0xFF0A0A0A),
    like = Color(0xFFF2F0EB),
    critical = Color(0xFFE07A66),
    warning = Color(0xFFD8A647),
    positive = Color(0xFF9CC79C),
    glassTint = Color(0xFF000000),
    glassRim = Color(0xFFFFFFFF),
    shadow = Color(0xFF000000),
    style = DisplayStyle.INDUSTRIAL,
)

/**
 * The warm off-white instrument: the matte display with near-black type and indicator. It was Bone
 * (D-29) until D-108 withdrew that theme; it is still what Custom builds a light display from and what
 * Classic lyrics use for their paper.
 */
val PaperColors = PodiumColors(
    isDark = false,
    canvas = Color(0xFFECE8DF),
    canvasRaised = Color(0xFFE2DDD2),
    labelPrimary = Color(0xFF141311),
    labelSecondary = Color(0xFF68645C),
    labelTertiary = Color(0xFF9B968C),
    separator = Color(0xFFD2CDC2),
    highlight = Color(0xFF141311),
    highlightText = Color(0xFF141311),
    onHighlight = Color(0xFFECE8DF),
    like = Color(0xFF141311),
    critical = Color(0xFFAF3A2A),
    warning = Color(0xFF875900),
    positive = Color(0xFF2E6A39),
    glassTint = Color(0xFFFFFFFF),
    glassRim = Color(0xFFFFFFFF),
    shadow = Color(0xFF000000),
    style = DisplayStyle.INDUSTRIAL,
)

