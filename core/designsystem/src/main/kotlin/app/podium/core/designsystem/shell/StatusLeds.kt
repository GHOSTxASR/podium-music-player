package app.podium.core.designsystem.shell

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import app.podium.core.designsystem.theme.PodiumTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.PI
import kotlin.math.cos

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
    // Breathing (charging) and blinking (nearly empty) are slow: about 20 steps a second look the
    // same as 120 and cost a sixth of the frames — every step redraws the whole window. Only while
    // one is wanted, and read only while drawing, so the Wheel and the display never recompose for it.
    val reduced = PodiumTheme.motion.reduced
    val mode = when {
        battery == null -> LedMode.OFF
        battery.charging -> if (reduced) LedMode.ON else LedMode.BREATHE
        battery.percent <= 10 -> if (reduced) LedMode.ON else LedMode.BLINK
        else -> LedMode.ON
    }
    var clock by remember { mutableLongStateOf(0L) }
    LaunchedEffect(mode) {
        if (mode != LedMode.BREATHE && mode != LedMode.BLINK) return@LaunchedEffect
        val start = SystemClock.uptimeMillis()
        while (true) {
            clock = SystemClock.uptimeMillis() - start
            delay(LED_STEP_MS)
        }
    }
    val batteryLight = { mode.light(clock) }
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
        Led(DiskAmber, { disk.value }, "Disk", legend)
    }
}

private enum class LedMode {
    OFF, ON, BREATHE, BLINK;

    /** How lit the diode is [millis] into its rhythm. */
    fun light(millis: Long): Float = when (this) {
        OFF -> 0f
        ON -> 1f
        // 0.45 → 1 → 0.45 over 2.8 s, eased at both ends.
        BREATHE -> 0.45f + 0.55f * (0.5f - 0.5f * cos(2.0 * PI * millis / 2_800.0).toFloat())
        BLINK -> if (millis % 900 < 495) 1f else 0.1f
    }
}

private const val LED_STEP_MS = 50L

@Composable
private fun Led(color: Color, lightOf: () -> Float, label: String, legend: TextStyle) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(18.dp)) {
            val light = lightOf()
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
