package app.podium.core.designsystem.shell

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.podium.core.designsystem.symbol.PodiumSymbol
import app.podium.core.designsystem.symbol.Symbol
import app.podium.core.designsystem.theme.DisplaySurface
import app.podium.core.designsystem.theme.LocalDisplaySurface
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.designsystem.theme.Spacing
import app.podium.core.designsystem.type.PodiumText
import kotlin.math.roundToInt

val ScreenCorner: Dp = 22.dp
private val IndustrialScreenCorner: Dp = 10.dp

/** Marks the display panel, so tests can check that nothing on screen escapes it. */
const val DisplayTestTag = "podium-display"
val ScreenHeaderHeight: Dp = 44.dp
private val BezelWidth = 3.dp

/**
 * The display set into the device (D-26): a black bezel window, the panel inside it with its own
 * canvas, and a glass cover — a recess shadow, a hairline panel edge and one faint reflection drawn
 * over the content. A hard boundary: everything inside is clipped to the panel, so no artwork,
 * glow, scroll, transition or glass can reach the body. The screen is a text container, so it is
 * never glass itself.
 */
@Composable
fun VirtualScreen(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val colors = PodiumTheme.colors
    val corner = if (colors.isIndustrial) IndustrialScreenCorner else ScreenCorner
    val outer = RoundedCornerShape(corner + BezelWidth)
    val inner = RoundedCornerShape(corner)
    Box(
        modifier
            .clip(outer)
            .background(Color(0xFF050608))
            .border(1.dp, Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.White.copy(alpha = 0.16f))), outer)
            .padding(BezelWidth),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .testTag(DisplayTestTag)
                .clip(inner)
                .background(colors.canvas)
                .displaySurface(LocalDisplaySurface.current, colors.canvas)
                .drawWithContent {
                    drawContent()
                    // Recess: the bezel's shadow falls a few dp onto the top of the display.
                    drawRect(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.32f),
                            1f to Color.Transparent,
                            endY = 10.dp.toPx(),
                        ),
                    )
                    // The display's own edge: a hairline where the panel meets the bezel.
                    drawRoundRect(
                        Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.5f), Color.White.copy(alpha = 0.07f))),
                        cornerRadius = CornerRadius(corner.toPx()),
                        style = Stroke(width = 1.dp.toPx()),
                    )
                    // Cover glass: one faint reflection, strongest at the top-left corner. Industrial
                    // displays are matte: no reflection at all.
                    if (!colors.isIndustrial) drawRect(
                        Brush.linearGradient(
                            0f to Color.White.copy(alpha = if (colors.isDark) 0.06f else 0.10f),
                            0.38f to Color.Transparent,
                            start = Offset.Zero,
                            end = Offset(size.width, size.height * 0.7f),
                        ),
                    )
                },
            content = content,
        )
    }
}

/**
 * The screen's header (iPod heritage): the current title, a back chevron for touch, and a small
 * playback indicator. No background — content fades out beneath it.
 */
@Composable
fun ScreenHeader(
    title: String,
    canGoBack: Boolean,
    onBack: () -> Unit,
    /** True = playing, false = paused, null = nothing loaded. */
    playing: Boolean?,
    modifier: Modifier = Modifier,
    /** How deep in the hierarchy this title is: deeper titles arrive from the right, like the content. */
    depth: Int = 0,
) {
    val colors = PodiumTheme.colors
    val type = PodiumTheme.type
    Box(modifier.fillMaxWidth().height(ScreenHeaderHeight)) {
        if (canGoBack) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = Spacing.xs)
                    .size(ScreenHeaderHeight)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onBack)
                    .semantics {
                        role = Role.Button
                        contentDescription = "Back"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Symbol(PodiumSymbol.ChevronLeft, colors.labelSecondary, size = 24.dp, weight = 600)
            }
        }
        // The title travels with the paper (D-29): forward slides it in from the right, back from the left.
        val motion = PodiumTheme.motion
        AnimatedContent(
            targetState = title to depth,
            transitionSpec = {
                if (motion.reduced) {
                    fadeIn(motion.fadeFast()) togetherWith fadeOut(motion.fadeFast())
                } else {
                    val dir = if (targetState.second >= initialState.second) 1 else -1
                    (slideInHorizontally(motion.navigateOffset()) { dir * it / 2 } + fadeIn(motion.fadeStandard())) togetherWith
                        (slideOutHorizontally(motion.navigateOffset()) { -dir * it / 2 } + fadeOut(motion.fadeFast()))
                }
            },
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 56.dp),
            label = "headerTitle",
        ) { (shown, _) ->
            PodiumText(shown, type.title, colors.labelPrimary, Modifier.semantics { heading() }, maxLines = 1)
        }
        if (playing != null) {
            Row(Modifier.align(Alignment.CenterEnd).padding(end = Spacing.l)) {
                Symbol(
                    if (playing) PodiumSymbol.Play else PodiumSymbol.Pause,
                    colors.labelSecondary,
                    size = 16.dp,
                    weight = 600,
                    filled = true,
                )
            }
        }
    }
}

/**
 * The listener's background picture (D-41), behind everything on the display: cropped to fill it,
 * centred, at the chosen opacity over its solid colour (the canvas already painted). Decoded once
 * by the app at about the display's size, so drawing is one bitmap blit.
 */
private fun Modifier.displaySurface(surface: DisplaySurface?, canvas: Color): Modifier {
    val image = surface?.image ?: return this
    if (surface.imageAlpha <= 0f) return this
    return drawWithCache {
        val scale = maxOf(size.width / image.width, size.height / image.height)
        val srcW = (size.width / scale).roundToInt().coerceIn(1, image.width)
        val srcH = (size.height / scale).roundToInt().coerceIn(1, image.height)
        val srcOffset = IntOffset((image.width - srcW) / 2, (image.height - srcH) / 2)
        val dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt())
        onDrawBehind {
            drawImage(image, srcOffset, IntSize(srcW, srcH), dstSize = dstSize, alpha = surface.imageAlpha, filterQuality = FilterQuality.Medium)
            // A veil of the display's own colour, so text reads on a busy picture.
            if (surface.veil > 0f) drawRect(canvas, alpha = surface.veil)
        }
    }
}

