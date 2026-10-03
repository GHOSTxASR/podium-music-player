package app.podium.core.designsystem.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.podium.core.designsystem.theme.Spacing

/**
 * The device's physical arrangement (D-26): the screen above the Wheel in portrait, beside it in
 * landscape, with the power button next to the Wheel. The screen keeps a device-like proportion
 * (portrait 0.60–0.82 width:height, landscape 1.20–1.70) and is otherwise as large as the Wheel
 * allows; whatever is left over is body. Everything the display shows goes in [screen], which is
 * clipped to the display — nothing on the screen can reach the body.
 */
@Composable
fun DeviceLayout(
    modifier: Modifier = Modifier,
    screen: @Composable BoxScope.() -> Unit,
    wheel: @Composable (diameter: Dp) -> Unit,
    powerButton: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier.windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))) {
        if (maxWidth <= maxHeight) {
            val diameter = (maxWidth * 0.58f).coerceIn(216.dp, 300.dp).coerceAtMost(maxHeight * 0.31f)
            Column(Modifier.fillMaxSize().padding(top = Spacing.s), horizontalAlignment = Alignment.CenterHorizontally) {
                BoxWithConstraints(
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = Spacing.m),
                    contentAlignment = Alignment.Center,
                ) {
                    val (w, h) = fitProportion(maxWidth, maxHeight, PORTRAIT_MIN, PORTRAIT_MAX)
                    VirtualScreen(Modifier.size(w, h), content = screen)
                }
                Box(Modifier.fillMaxWidth().padding(vertical = WheelGap)) {
                    Box(Modifier.align(Alignment.Center)) { wheel(diameter) }
                    Box(Modifier.align(Alignment.TopEnd).padding(end = Spacing.s)) { powerButton() }
                }
            }
        } else {
            val diameter = (maxHeight * 0.62f).coerceIn(180.dp, 300.dp).coerceAtMost(maxWidth * 0.36f)
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                BoxWithConstraints(
                    Modifier.weight(1f).fillMaxHeight().padding(Spacing.m),
                    contentAlignment = Alignment.Center,
                ) {
                    val (w, h) = fitProportion(maxWidth, maxHeight, LANDSCAPE_MIN, LANDSCAPE_MAX)
                    VirtualScreen(Modifier.size(w, h), content = screen)
                }
                Box(Modifier.fillMaxHeight().padding(horizontal = WheelGap)) {
                    Box(Modifier.align(Alignment.Center)) { wheel(diameter) }
                    Box(Modifier.align(Alignment.TopEnd)) { powerButton() }
                }
            }
        }
    }
}

private val WheelGap = 18.dp
private const val PORTRAIT_MIN = 0.60f
private const val PORTRAIT_MAX = 0.82f
private const val LANDSCAPE_MIN = 1.20f
private const val LANDSCAPE_MAX = 1.70f

/** The largest width × height inside [maxW] × [maxH] whose width:height lies in [min]..[max]. */
internal fun fitProportion(maxW: Dp, maxH: Dp, min: Float, max: Float): Pair<Dp, Dp> {
    val ratio = maxW / maxH
    return when {
        ratio > max -> (maxH * max) to maxH
        ratio < min -> maxW to (maxW / min)
        else -> maxW to maxH
    }
}
