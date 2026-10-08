package app.podium.feature.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.designsystem.theme.Spacing
import app.podium.core.designsystem.type.PodiumText
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A colour as the picker turns it (D-51): hue all the way round (0–360°), saturation from grey to
 * full colour and brightness from black to full (both 0–1). Every colour is reachable, and turning
 * the hue of a greyish colour still shows the whole palette on the hue bar.
 */
internal data class Hsv(val hue: Float, val saturation: Float, val brightness: Float) {
    val argb: Int get() = hsvToArgb(hue, saturation, brightness)

    fun value(channel: ColorChannel): Float = when (channel) {
        ColorChannel.HUE -> hue / 360f
        ColorChannel.SATURATION -> saturation
        ColorChannel.BRIGHTNESS -> brightness
    }

    /** [channel] set to [fraction] (0–1) of its range; hue wraps, the others stop at their ends. */
    fun with(channel: ColorChannel, fraction: Float): Hsv = when (channel) {
        ColorChannel.HUE -> copy(hue = ((fraction * 360f) % 360f + 360f) % 360f)
        ColorChannel.SATURATION -> copy(saturation = fraction.coerceIn(0f, 1f))
        ColorChannel.BRIGHTNESS -> copy(brightness = fraction.coerceIn(0f, 1f))
    }

    /** [detents] steps of the Wheel on [channel]. */
    fun turned(channel: ColorChannel, detents: Int): Hsv = with(channel, value(channel) + detents * channel.step)

    companion object {
        fun of(argb: Int): Hsv {
            val r = (argb shr 16 and 0xFF) / 255f
            val g = (argb shr 8 and 0xFF) / 255f
            val b = (argb and 0xFF) / 255f
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val d = max - min
            val hue = when {
                d == 0f -> 0f
                max == r -> 60f * (((g - b) / d) % 6f)
                max == g -> 60f * ((b - r) / d + 2f)
                else -> 60f * ((r - g) / d + 4f)
            }.let { (it + 360f) % 360f }
            return Hsv(hue, if (max == 0f) 0f else d / max, max)
        }
    }
}

/** The three bars of the picker; the Wheel turns the chosen one. */
internal enum class ColorChannel(val label: String, val step: Float) {
    HUE("Hue", 1f / 90f),
    SATURATION("Saturation", 0.02f),
    BRIGHTNESS("Brightness", 0.02f),
}

internal fun hsvToArgb(hue: Float, saturation: Float, brightness: Float): Int {
    val h = ((hue % 360f) + 360f) % 360f / 60f
    val c = brightness * saturation
    val x = c * (1 - abs(h % 2f - 1))
    val m = brightness - c
    val (r, g, b) = when (h.toInt()) {
        0 -> Triple(c, x, 0f)
        1 -> Triple(x, c, 0f)
        2 -> Triple(0f, c, x)
        3 -> Triple(0f, x, c)
        4 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    fun channel(v: Float) = ((v + m) * 255f).roundToInt().coerceIn(0, 255)
    return (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
}

/** What a bar shows behind its marker: the whole palette for hue, the reach of the others. */
private fun barColors(channel: ColorChannel, hsv: Hsv): List<Color> = when (channel) {
    ColorChannel.HUE -> (0..12).map { Color(hsvToArgb(it * 30f, 1f, 1f)) }
    ColorChannel.SATURATION -> listOf(Color(hsvToArgb(hsv.hue, 0f, hsv.brightness)), Color(hsvToArgb(hsv.hue, 1f, hsv.brightness)))
    ColorChannel.BRIGHTNESS -> listOf(Color.Black, Color(hsvToArgb(hsv.hue, hsv.saturation, 1f)))
}

/** How a bar's value reads aloud and beside it. */
internal fun ColorChannel.describe(hsv: Hsv): String = when (this) {
    ColorChannel.HUE -> "${hsv.hue.roundToInt()}°"
    ColorChannel.SATURATION -> "${(hsv.saturation * 100).roundToInt()}%"
    ColorChannel.BRIGHTNESS -> "${(hsv.brightness * 100).roundToInt()}%"
}

/**
 * One bar: its name and value above, the gradient with a marker at the value. The chosen bar's
 * name is lit and a dot stands beside it. Touch sets the value directly (and chooses the bar).
 */
@Composable
internal fun ColorBar(channel: ColorChannel, hsv: Hsv, chosen: Boolean, onChoose: () -> Unit, onSet: (Float) -> Unit) {
    val colors = PodiumTheme.colors
    val type = PodiumTheme.type
    val set by rememberUpdatedState(onSet)
    val choose by rememberUpdatedState(onChoose)
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(10.dp), contentAlignment = Alignment.CenterStart) {
                if (chosen) Canvas(Modifier.width(6.dp).height(6.dp)) { drawCircle(colors.highlightText) }
            }
            PodiumText(channel.label, if (chosen) type.rowFocused else type.rowSecondary, if (chosen) colors.labelPrimary else colors.labelSecondary, Modifier.weight(1f))
            PodiumText(channel.describe(hsv), type.caption, colors.labelSecondary)
        }
        Spacer(Modifier.height(Spacing.xs))
        val gradient = barColors(channel, hsv)
        val value = hsv.value(channel)
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(22.dp)
                .semantics {
                    contentDescription = channel.label
                    stateDescription = channel.describe(hsv)
                    progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f)
                    setProgress { target ->
                        set(target)
                        true
                    }
                }
                .pointerInput(channel) {
                    detectTapGestures { offset ->
                        choose()
                        set((offset.x / size.width).coerceIn(0f, 1f))
                    }
                }
                .pointerInput(channel) {
                    detectHorizontalDragGestures(onDragStart = { choose() }) { change, _ ->
                        change.consume()
                        set((change.position.x / size.width).coerceIn(0f, 1f))
                    }
                },
        ) {
            val radius = CornerRadius(6.dp.toPx())
            drawRoundRect(Brush.horizontalGradient(gradient), cornerRadius = radius)
            drawRoundRect(colors.separator, cornerRadius = radius, style = Stroke(1.dp.toPx()))
            // The marker: a ring in the bar's own colour at the value, outlined in both inks so it
            // reads on any part of the palette.
            val x = (value * size.width).coerceIn(4.dp.toPx(), size.width - 4.dp.toPx())
            val ring = Size(8.dp.toPx(), size.height + 6.dp.toPx())
            val topLeft = Offset(x - ring.width / 2, -3.dp.toPx())
            drawRoundRect(Color.Black.copy(alpha = 0.55f), topLeft, ring, CornerRadius(3.dp.toPx()), style = Stroke(3.dp.toPx()))
            drawRoundRect(Color.White, topLeft, ring, CornerRadius(3.dp.toPx()), style = Stroke(1.5.dp.toPx()))
        }
    }
}
