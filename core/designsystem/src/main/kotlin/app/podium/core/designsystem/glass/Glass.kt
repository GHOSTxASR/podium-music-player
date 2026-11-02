package app.podium.core.designsystem.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import app.podium.core.designsystem.theme.LocalPodiumColors
import app.podium.core.designsystem.tonal.ContinuousShape
import app.podium.core.designsystem.tonal.LocalAlbumTone
import app.podium.core.designsystem.tonal.SurfaceLevel
import app.podium.core.designsystem.tonal.tonalSurface
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.RoundedRectangularShape

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

    /**
     * Now Playing's orb (D-107). A thicker frost than a key's, so the creature reads as a body and
     * not a pane: the cover behind it is light and colour, never a picture seen through it.
     */
    Orb(blurDp = 30f, refractionHeightDp = 14f, refractionAmountDp = 18f, darkTint = 0.54f, lightTint = 0.66f),
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
    // The cover's light (D-103): the album's colour falls on the glass from above, a faint glow along
    // its upper edge and a touch of it in the rim. Read while drawing: a new album only redraws.
    val tone = LocalAlbumTone.current
    val glow = if (colors.isDark) 0.16f else 0.10f

    if (tier == GlassTier.Solid || backdrop == null) {
        // Carbon (D-101): never glass; the material's place becomes a solid tonal surface.
        if (colors.isTonal) {
            return when (material) {
                GlassMaterial.Control -> this.tonalSurface(shape, SurfaceLevel.High, pressed = { pressed })
                // Menus and sheets float highest of all.
                GlassMaterial.Regular -> this.tonalSurface(shape, SurfaceLevel.Base, elevation = 16.dp, pressed = { pressed })
                GlassMaterial.Floating -> this.tonalSurface(shape, SurfaceLevel.Base, pressed = { pressed })
                // Carbon's orb is ivory, which `LiveOrb` fills itself; this is only the fallback.
                GlassMaterial.Orb -> this.tonalSurface(shape, SurfaceLevel.High, elevation = 6.dp, pressed = { pressed })
            }
        }
        val fill = if (colors.isDark) colors.canvasRaised else colors.canvasRaised.copy(alpha = 0.97f)
        // Industrial themes are flat: no drop shadow, just the surface and its hairline. Glass without
        // its glass still catches the cover's light, as the glass does.
        if (colors.isIndustrial) {
            return this
                .background(if (pressed) colors.separator else fill, shape)
                .border(0.5.dp, colors.separator, shape)
        }
        return this
            .shadow(16.dp, shape, ambientColor = shadowColor, spotColor = shadowColor)
            .background(if (pressed) colors.separator else fill, shape)
            .drawWithCache {
                val outline = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
                onDrawBehind {
                    clipPath(outline) { drawRect(Brush.verticalGradient(listOf(tone.accent.copy(alpha = glow), Color.Transparent), endY = size.height * 0.8f)) }
                }
            }
            .border(0.5.dp, lerp(colors.separator, tone.accent, 0.3f), shape)
    }

    val tint = (baseTint + (if (tier == GlassTier.Blur) 0.04f else 0f) + (if (pressed) 0.06f else 0f)).coerceAtMost(0.9f)
    val rimAlpha = (if (colors.isDark) 0.42f else 0.8f) + (if (pressed) 0.15f else 0f)
    val blurPx = with(density) { material.blurDp.dp.toPx() }
    val heightPx = with(density) { material.refractionHeightDp.dp.toPx() }
    val amountPx = with(density) { (material.refractionAmountDp * if (pressed) 1.2f else 1f).dp.toPx() }
    val tintColor = colors.glassTint.copy(alpha = tint)
    // Continuous corners in the glass library's own shape, the only kind its lens can measure. Held
    // in state and read while the glass draws: the lens and rim follow a shape that changes (the play
    // key squaring up as the music starts) instead of keeping the outline they began with.
    val glassShape = remember { mutableStateOf(if (shape is ContinuousShape) shape.forGlass() else shape) }
    SideEffect { glassShape.value = if (shape is ContinuousShape) shape.forGlass() else shape }

    return this.drawBackdrop(
        backdrop = backdrop,
        shape = { glassShape.value },
        effects = {
            vibrancy()
            blur(blurPx)
            // The lens refracts only through rounded rectangles it can measure (D-103): anything else
            // keeps its blur and tint rather than failing to draw.
            val outline = glassShape.value
            if (tier == GlassTier.Full && (outline is CornerBasedShape || outline is RoundedRectangularShape)) lens(heightPx, amountPx)
        },
        highlight = { Highlight(width = 1.dp, style = HighlightStyle.Default(color = lerp(colors.glassRim, tone.accent, 0.3f).copy(alpha = rimAlpha))) },
        shadow = { Shadow(radius = 24.dp, offset = DpOffset(0.dp, 8.dp), color = shadowColor) },
        onDrawSurface = {
            drawRect(tintColor)
            drawRect(Brush.verticalGradient(listOf(tone.accent.copy(alpha = glow), Color.Transparent), endY = size.height * 0.8f))
        },
    )
}
