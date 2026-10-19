package app.podium.core.designsystem.shell

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.podium.core.designsystem.theme.PodiumTheme
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * The time, in the title bar (D-85): shown when Podium is full screen and the phone's own status bar
 * is hidden. The phone's 12- or 24-hour choice, without seconds; it changes on the minute.
 */
@Composable
fun TitleBarClock(color: Color, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val format = remember(context) { DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm") }
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(format) {
        while (true) {
            now = LocalTime.now()
            // Wake just after the next minute begins.
            delay((60 - now.second) * 1000L - now.nano / 1_000_000 + 50)
        }
    }
    val text = now.format(format)
    BasicText(
        text,
        modifier.semantics { contentDescription = "Time $text" },
        style = PodiumTheme.type.caption.copy(color = color),
        maxLines = 1,
    )
}

/**
 * The phone's battery, in the title bar (D-85): a small outline filled to the charge, with a bolt
 * while charging, in the display's quiet ink (album art stays the only colour, D-29).
 */
@Composable
fun TitleBarBattery(level: BatteryLevel, color: Color, modifier: Modifier = Modifier) {
    val paper = PodiumTheme.colors.canvas
    val bolt = remember { Path() }
    Canvas(
        modifier
            .size(width = 23.dp, height = 11.dp)
            .semantics { contentDescription = "Battery ${level.percent} percent" + if (level.charging) ", charging" else "" },
    ) {
        val stroke = 1.2.dp.toPx()
        val nub = 1.8.dp.toPx()
        val body = Size(size.width - nub - stroke, size.height - stroke)
        val corner = CornerRadius(2.5.dp.toPx())
        drawRoundRect(color, Offset(stroke / 2, stroke / 2), body, corner, style = Stroke(stroke))
        drawRoundRect(color, Offset(size.width - nub, size.height * 0.32f), Size(nub, size.height * 0.36f), CornerRadius(nub / 2))
        val inset = stroke + 1.dp.toPx()
        val full = Size(body.width - inset * 2 + stroke, body.height - inset * 2 + stroke)
        val fill = (level.percent.coerceIn(0, 100) / 100f).let { if (it > 0f) maxOf(it, 0.08f) else 0f }
        drawRoundRect(color, Offset(inset, inset), Size(full.width * fill, full.height), CornerRadius(1.dp.toPx()))
        if (level.charging) {
            // A small bolt, cut out of the fill.
            val cx = stroke / 2 + body.width / 2
            val cy = size.height / 2
            val h = size.height * 0.62f
            bolt.rewind()
            bolt.moveTo(cx + h * 0.10f, cy - h / 2)
            bolt.lineTo(cx - h * 0.32f, cy + h * 0.06f)
            bolt.lineTo(cx - h * 0.02f, cy + h * 0.06f)
            bolt.lineTo(cx - h * 0.10f, cy + h / 2)
            bolt.lineTo(cx + h * 0.32f, cy - h * 0.06f)
            bolt.lineTo(cx + h * 0.02f, cy - h * 0.06f)
            bolt.close()
            drawPath(bolt, paper)
        }
    }
}
