package app.podium.core.designsystem.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

/** The phone's battery, as the battery LED shows it. */
data class BatteryLevel(val percent: Int, val charging: Boolean)

private val LedOff = Color(0xFF1E1E1C)
private val LedGreen = Color(0xFF4CE06A)
private val LedLime = Color(0xFFB8E04C)
private val LedAmber = Color(0xFFFFB020)
private val LedRed = Color(0xFFFF3B30)
private val DiskAmber = Color(0xFFFFA726)

/** Green when full, through lime and amber, to red when nearly empty. */
fun batteryColor(percent: Int): Color = when {
    percent > 60 -> LedGreen
    percent > 30 -> LedLime
    percent > 15 -> LedAmber
    else -> LedRed
}

/**
 * Two indicator lights on the body (D-33), like a handheld from the '90s: battery — coloured by
 * charge, breathing while charging, blinking when nearly empty — and disk, flickering whenever
 * Podium reads or writes ([activity]). Tiny legends sit under each, in the body's legend colour.
 */
@Composable
fun StatusLeds(battery: BatteryLevel?, activity: Flow<Unit>, palette: ShellPalette, modifier: Modifier = Modifier) {
    val disk = remember { Animatable(0f) }
    LaunchedEffect(activity) {
        // A drive light: two quick flickers per burst of work; continuous work keeps it flickering.
        activity.collectLatest {
            disk.snapTo(1f)
            disk.animateTo(0.15f, tween(60, easing = LinearEasing))
            disk.snapTo(1f)
            disk.animateTo(0f, tween(140, easing = LinearEasing))
        }
    }
    val pulse = rememberInfiniteTransition(label = "battery")
    val breathe by pulse.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "breathe",
    )
    val blink by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "blink",
    )
    val batteryLight = when {
        battery == null -> 0f
        battery.charging -> breathe
        battery.percent <= 10 -> if (blink < 0.55f) 1f else 0.1f
        else -> 1f
    }
    val legend = TextStyle(fontSize = 8.sp, color = palette.legend.copy(alpha = 0.75f))
    Row(
        modifier.semantics {
            contentDescription = buildString {
                battery?.let { append("Battery ${it.percent} percent"); if (it.charging) append(", charging") }
            }
        },
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Led(battery?.let { batteryColor(it.percent) } ?: LedOff, batteryLight, "Battery", legend)
        Led(DiskAmber, disk.value, "Disk", legend)
    }
}

@Composable
private fun Led(color: Color, light: Float, label: String, legend: TextStyle) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(18.dp)) {
            val core = size.minDimension * 0.2f
            // The diode sits in a small recessed bezel, so it reads as a part even when dark.
            drawCircle(Color.Black.copy(alpha = 0.55f), radius = core * 1.55f)
            drawCircle(Color.White.copy(alpha = 0.07f), radius = core * 1.55f, style = androidx.compose.ui.graphics.drawscope.Stroke(0.8.dp.toPx()))
            if (light > 0.02f) {
                drawCircle(
                    Brush.radialGradient(listOf(color.copy(alpha = 0.55f * light), Color.Transparent), center, size.minDimension / 2f),
                    radius = size.minDimension / 2f,
                )
            }
            drawCircle(lerp(LedOff, color, light.coerceIn(0f, 1f)), radius = core)
            // A pinpoint highlight: a lens over the diode.
            drawCircle(Color.White.copy(alpha = 0.18f + 0.3f * light), radius = core * 0.35f, center = center.copy(x = center.x - core * 0.3f, y = center.y - core * 0.3f))
        }
        Spacer(Modifier.height(1.dp))
        BasicText(label, style = legend)
    }
}
