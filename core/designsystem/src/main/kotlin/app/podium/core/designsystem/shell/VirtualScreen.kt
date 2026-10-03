package app.podium.core.designsystem.shell

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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.podium.core.designsystem.symbol.PodiumSymbol
import app.podium.core.designsystem.symbol.Symbol
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.designsystem.theme.Spacing
import app.podium.core.designsystem.type.PodiumText

val ScreenCorner: Dp = 22.dp

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
    val outer = RoundedCornerShape(ScreenCorner + BezelWidth)
    val inner = RoundedCornerShape(ScreenCorner)
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
                        cornerRadius = CornerRadius(ScreenCorner.toPx()),
                        style = Stroke(width = 1.dp.toPx()),
                    )
                    // Cover glass: one faint reflection, strongest at the top-left corner.
                    drawRect(
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
        PodiumText(
            title,
            type.title,
            colors.labelPrimary,
            Modifier.align(Alignment.Center).padding(horizontal = 56.dp).semantics { heading() },
            maxLines = 1,
        )
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
