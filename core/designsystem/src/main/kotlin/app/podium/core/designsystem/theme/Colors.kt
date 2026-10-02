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
)

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
