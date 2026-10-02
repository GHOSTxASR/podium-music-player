package app.podium.core.designsystem.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.podium.core.designsystem.theme.PodiumMotion
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.designsystem.type.PodiumText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val MarkRing = Color(0xFFE8EEF4)
private val MarkCenter = Color(0xFF367FE0)

/**
 * Power-on (D-26): the screen lights black, Podium's mark draws itself — the ring sweeps closed,
 * the center lands — the wordmark fades in, then [onFinished]. About 1.7 s; 0.9 s, static, with
 * reduced motion. The chime is played by the caller, in step with [onStart].
 */
@Composable
fun BootScreen(onStart: () -> Unit, onFinished: () -> Unit, modifier: Modifier = Modifier) {
    val reduced = PodiumTheme.motion.reduced
    val sweep = remember { Animatable(if (reduced) 1f else 0f) }
    val dot = remember { Animatable(if (reduced) 1f else 0f) }
    val word = remember { Animatable(if (reduced) 1f else 0f) }
    val start by rememberUpdatedState(onStart)
    val finished by rememberUpdatedState(onFinished)
    LaunchedEffect(Unit) {
        start()
        if (!reduced) {
            launch { sweep.animateTo(1f, tween(640, easing = PodiumMotion.EmphasizedDecelerate)) }
            launch {
                delay(380)
                dot.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 500f))
            }
            launch {
                delay(620)
                word.animateTo(1f, tween(420))
            }
            delay(1700)
        } else {
            delay(900)
        }
        finished()
    }
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .semantics { contentDescription = "Starting Podium" },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(84.dp)) {
                val stroke = 9.dp.toPx()
                val r = size.minDimension / 2f - stroke / 2f
                drawArc(
                    color = MarkRing,
                    startAngle = -90f,
                    sweepAngle = 360f * sweep.value,
                    useCenter = false,
                    topLeft = Offset(center.x - r, center.y - r),
                    size = Size(r * 2, r * 2),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                drawCircle(MarkCenter, radius = size.minDimension * 0.15f * dot.value)
            }
            Spacer(Modifier.height(18.dp))
            PodiumText(
                "Podium",
                PodiumTheme.type.title,
                MarkRing,
                Modifier.graphicsLayer { alpha = word.value },
            )
        }
    }
}
