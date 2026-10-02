package app.podium.core.designsystem.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import app.podium.core.designsystem.theme.LocalPodiumColors
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.Shadow

/** Rendering fidelity (ADR-007). Solid is also the reduced-transparency and old-device path. */
enum class GlassTier { Full, Blur, Solid }

/** Semantic materials (design-system.md §5.1). Components choose meaning, never blur values. */
enum class GlassMaterial(
    val blurDp: Float,
    val refractionHeightDp: Float,
    val refractionAmountDp: Float,
    val darkTint: Float,
    val lightTint: Float,
) {
    /** The Wheel ring, menus, sheets. */
    Regular(blurDp = 20f, refractionHeightDp = 16f, refractionAmountDp = 24f, darkTint = 0.38f, lightTint = 0.42f),

    /** The Wheel's center, small buttons. */
    Control(blurDp = 12f, refractionHeightDp = 12f, refractionAmountDp = 18f, darkTint = 0.30f, lightTint = 0.50f),

    /** Mini player, HUDs. */
    Floating(blurDp = 24f, refractionHeightDp = 12f, refractionAmountDp = 16f, darkTint = 0.44f, lightTint = 0.48f),
}

val LocalGlassTier = staticCompositionLocalOf { GlassTier.Solid }

/**
 * The single shared capture of the content layer that every glass surface samples (ADR-007 §2).
 * Glass is only ever drawn outside the captured layer, so glass never samples glass.
 */
val LocalGlassBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/**
 * Applies a glass material. The only way Podium draws glass: no other module may blur, use
 * RenderEffect, or import the Backdrop library (CLAUDE.md, enforced in review and by lint later).
 */
@Composable
fun Modifier.glass(material: GlassMaterial, shape: Shape, pressed: Boolean = false): Modifier {
    val tier = LocalGlassTier.current
    val colors = LocalPodiumColors.current
    val backdrop = LocalGlassBackdrop.current
    val density = LocalDensity.current
    val baseTint = if (colors.isDark) material.darkTint else material.lightTint
    val shadowColor = colors.shadow.copy(alpha = if (colors.isDark) 0.28f else 0.12f)

    if (tier == GlassTier.Solid || backdrop == null) {
        val fill = if (colors.isDark) colors.canvasRaised else colors.canvasRaised.copy(alpha = 0.97f)
        return this
            .shadow(16.dp, shape, ambientColor = shadowColor, spotColor = shadowColor)
            .background(if (pressed) colors.separator else fill, shape)
            .border(0.5.dp, colors.separator, shape)
    }

    val tint = (baseTint + (if (tier == GlassTier.Blur) 0.04f else 0f) + (if (pressed) 0.06f else 0f)).coerceAtMost(0.9f)
    val rimAlpha = (if (colors.isDark) 0.42f else 0.8f) + (if (pressed) 0.15f else 0f)
    val blurPx = with(density) { material.blurDp.dp.toPx() }
    val heightPx = with(density) { material.refractionHeightDp.dp.toPx() }
    val amountPx = with(density) { (material.refractionAmountDp * if (pressed) 1.2f else 1f).dp.toPx() }
    val tintColor = colors.glassTint.copy(alpha = tint)

    return this.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(blurPx)
            if (tier == GlassTier.Full) lens(heightPx, amountPx)
        },
        highlight = { Highlight(width = 1.dp, style = HighlightStyle.Default(color = colors.glassRim.copy(alpha = rimAlpha))) },
        shadow = { Shadow(radius = 24.dp, offset = DpOffset(0.dp, 8.dp), color = shadowColor) },
        onDrawSurface = { drawRect(tintColor) },
    )
}
