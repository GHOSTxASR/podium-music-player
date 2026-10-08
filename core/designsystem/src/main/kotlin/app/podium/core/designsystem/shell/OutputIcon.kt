package app.podium.core.designsystem.shell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.podium.core.designsystem.symbol.PodiumSymbol
import app.podium.core.designsystem.symbol.Symbol

/** What the status bar says about the music's output (D-52). Muted outranks where it goes. */
enum class OutputIndicator(val description: String) {
    MUTED("Muted"),
    BLUETOOTH("Playing through Bluetooth"),
    HEADPHONES("Playing through headphones"),
    PHONE_SPEAKER("Playing through the phone speaker"),
}

/**
 * The output glyph beside the play state in the status bar. Headphones come from Podium's symbol
 * font; the Bluetooth rune, the phone and the muted speaker (not in the bundled subset) are drawn
 * here with the same stroke and rounded ends, so the four read as one family.
 */
@Composable
fun OutputIcon(indicator: OutputIndicator, color: Color, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    val described = modifier.semantics { contentDescription = indicator.description }
    if (indicator == OutputIndicator.HEADPHONES) {
        Symbol(PodiumSymbol.Headphones, color, described, size = size, weight = 600)
        return
    }
    Canvas(described.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(w * x, h * y)
        when (indicator) {
            OutputIndicator.BLUETOOTH -> {
                val rune = Path().apply {
                    moveTo(w * 0.28f, h * 0.31f)
                    lineTo(w * 0.72f, h * 0.69f)
                    lineTo(w * 0.5f, h * 0.9f)
                    lineTo(w * 0.5f, h * 0.1f)
                    lineTo(w * 0.72f, h * 0.31f)
                    lineTo(w * 0.28f, h * 0.69f)
                }
                drawPath(rune, color, style = stroke)
            }
            OutputIndicator.PHONE_SPEAKER -> {
                drawRoundRect(color, p(0.3f, 0.08f), Size(w * 0.4f, h * 0.84f), CornerRadius(w * 0.1f), style = stroke)
                drawLine(color, p(0.44f, 0.8f), p(0.56f, 0.8f), stroke.width, StrokeCap.Round)
            }
            OutputIndicator.MUTED -> {
                val cone = Path().apply {
                    moveTo(w * 0.12f, h * 0.38f)
                    lineTo(w * 0.28f, h * 0.38f)
                    lineTo(w * 0.48f, h * 0.2f)
                    lineTo(w * 0.48f, h * 0.8f)
                    lineTo(w * 0.28f, h * 0.62f)
                    lineTo(w * 0.12f, h * 0.62f)
                    close()
                }
                drawPath(cone, color, style = stroke)
                drawLine(color, p(0.62f, 0.38f), p(0.86f, 0.62f), stroke.width, StrokeCap.Round)
                drawLine(color, p(0.86f, 0.38f), p(0.62f, 0.62f), stroke.width, StrokeCap.Round)
            }
            OutputIndicator.HEADPHONES -> Unit
        }
    }
}
