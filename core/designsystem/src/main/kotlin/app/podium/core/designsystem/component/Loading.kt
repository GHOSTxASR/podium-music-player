package app.podium.core.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.designsystem.theme.Spacing
import app.podium.core.designsystem.type.PodiumText
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Podium's loading indicator (D-43): twelve spokes around a hub, the lit spoke stepping round with
 * a fading tail — the pinwheel of the old pocket players, in the display's own ink. It steps (about
 * 12 times a second) rather than spinning smoothly, so it redraws only when a spoke changes; with
 * reduced motion it stands still.
 */
@Composable
fun Pinwheel(modifier: Modifier = Modifier, size: Dp = 28.dp, color: Color = PodiumTheme.colors.labelPrimary) {
    val reduced = PodiumTheme.motion.reduced
    var step by remember { mutableIntStateOf(0) }
    if (!reduced) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(STEP_MS)
                // Suspends while nothing is drawn (screen off, app hidden).
                withFrameMillis { step = (step + 1) % SPOKES }
            }
        }
    }
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2f
        val inner = r * 0.42f
        val stroke = r * 0.17f
        for (i in 0 until SPOKES) {
            // The lit spoke, then a tail fading behind it.
            val age = ((step - i) % SPOKES + SPOKES) % SPOKES
            val alpha = if (reduced) 0.55f else (1f - age / SPOKES.toFloat()).coerceAtLeast(0.14f)
            val a = (2 * PI * i / SPOKES - PI / 2).toFloat()
            val dx = cos(a)
            val dy = sin(a)
            drawLine(
                color.copy(alpha = color.alpha * alpha),
                start = Offset(center.x + dx * inner, center.y + dy * inner),
                end = Offset(center.x + dx * (r - stroke / 2), center.y + dy * (r - stroke / 2)),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * The screen while content loads: the pinwheel and a word, centred in the readable region. It waits
 * [LOADING_DELAY_MS] before showing, so a fast answer never flashes a loading screen.
 */
@Composable
fun LoadingScreen(label: String = "Loading", modifier: Modifier = Modifier) {
    val insets = LocalScreenInsets.current
    Box(
        modifier
            .fillMaxSize()
            .padding(top = insets.top, bottom = insets.bottom)
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
            },
        contentAlignment = Alignment.Center,
    ) {
        Delayed {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Pinwheel(size = 30.dp)
                Spacer(Modifier.height(Spacing.m))
                PodiumText(label, PodiumTheme.type.footnote, PodiumTheme.colors.labelSecondary)
            }
        }
    }
}

/** The same, as one row of a list (a search on its way): the pinwheel beside its word. */
@Composable
fun LoadingRow(label: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.menuRow)
            .padding(horizontal = LocalRowPadding.current)
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Delayed {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pinwheel(size = 18.dp, color = PodiumTheme.colors.labelSecondary)
                Spacer(Modifier.width(Spacing.m))
                PodiumText(label, PodiumTheme.type.footnote, PodiumTheme.colors.labelSecondary)
            }
        }
    }
}

@Composable
private fun Delayed(content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(LOADING_DELAY_MS)
        visible = true
    }
    val fade = if (PodiumTheme.motion.reduced) 0 else 180
    AnimatedVisibility(visible, enter = fadeIn(tween(fade))) { content() }
}

/** A load faster than this shows no loading screen at all. */
const val LOADING_DELAY_MS = 150L

private const val SPOKES = 12
private const val STEP_MS = 75L
