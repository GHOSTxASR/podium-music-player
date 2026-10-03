package app.podium.core.designsystem.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/**
 * The two planes of every Podium window: [content] (atmosphere + screens, captured once) and
 * [functional] (glass: title bar, wheel, mini player, overlays), drawn above and sampling the capture.
 */
@Composable
fun GlassHost(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
    functional: @Composable BoxScope.() -> Unit,
) {
    // One tree for every tier: switching between Glass and the matte themes must never rebuild
    // the screens inside (it reset their state — e.g. the Theme picker's focus — on every preview).
    // On the Solid tier the capture modifier is simply left off, so nothing is recorded.
    val solid = LocalGlassTier.current == GlassTier.Solid
    val backdrop = rememberLayerBackdrop()
    Box(modifier) {
        Box(Modifier.matchParentSize().then(if (solid) Modifier else Modifier.layerBackdrop(backdrop)), content = content)
        CompositionLocalProvider(LocalGlassBackdrop provides if (solid) null else backdrop) { functional() }
    }
}

/**
 * Scroll-edge effect (design-system.md §5.5): content fades out under the title bar and partially
 * under the functional stack, so floating controls stay legible without opaque bars.
 */
fun Modifier.scrollEdgeFade(topFadePx: Float, bottomStartPx: Float, bottomAlpha: Float = 0.35f): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        val h = size.height
        if (h <= 0f) return@drawWithContent
        val top = (topFadePx / h).coerceIn(0f, 1f)
        val bottom = (bottomStartPx / h).coerceIn(top, 1f)
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                top * 0.55f to Color.Black.copy(alpha = 0.25f),
                top to Color.Black,
                bottom to Color.Black,
                1f to Color.Black.copy(alpha = bottomAlpha),
            ),
            blendMode = BlendMode.DstIn,
        )
    }
