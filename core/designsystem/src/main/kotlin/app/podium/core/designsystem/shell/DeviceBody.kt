package app.podium.core.designsystem.shell

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import app.podium.core.designsystem.theme.PodiumTheme

/**
 * A solid finish's body: the base colour with a gentle top-to-bottom light falloff, one soft
 * diagonal sheen (anodised metal catching light), the finish's grain, and — when chosen — glitter
 * in the material (D-41). Changes animate, so previewing finishes in Settings morphs the whole
 * device.
 */
@Composable
fun DeviceBody(palette: ShellPalette, modifier: Modifier = Modifier) {
    val duration = if (PodiumTheme.motion.reduced) 0 else 420
    val top by animateColorAsState(palette.bodyTop, tween(duration), label = "bodyTop")
    val bottom by animateColorAsState(palette.bodyBottom, tween(duration), label = "bodyBottom")
    val grain by animateFloatAsState(palette.grain, tween(duration), label = "bodyGrain")
    val sheenAlpha = if (palette.matte) 0f else if (palette.isLight) 0.22f else 0.07f
    Box(
        modifier
            .drawWithCache {
                val falloff = Brush.verticalGradient(listOf(top, bottom))
                val sheen = Brush.linearGradient(
                    0f to Color.White.copy(alpha = sheenAlpha),
                    0.45f to Color.Transparent,
                    start = Offset.Zero,
                    end = Offset(size.width * 0.9f, size.height * 0.55f),
                )
                onDrawBehind {
                    drawRect(falloff)
                    drawRect(sheen)
                }
            }
            .grain { grain }
            .glitter(palette.glitter, palette.glitterLight, palette.glitterDark),
    )
}
