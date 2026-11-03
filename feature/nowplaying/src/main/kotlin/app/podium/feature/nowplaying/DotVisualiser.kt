package app.podium.feature.nowplaying

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.player.api.AudioPulse
import app.podium.player.api.PlaybackController
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The dot visualiser (D-104): a dark capsule of dots, seven rows deep. Two waves cross along it,
 * mirrored, their height the music's loudness, tapering into the capsule's ends; the dots they pass
 * light up, with a soft glow around the brightest. Silent, it is a single lit line.
 *
 * The vinyl's stage draws it below the record, and the nostalgic Now Playing (D-109) draws it where
 * the transport used to be. [presence] (0–1) opens it from a dot; the waves rise once it is open.
 */
internal fun DrawScope.drawDotVisualiser(
    center: Offset,
    width: Float,
    height: Float,
    presence: Float,
    amplitude: Float,
    t: Float,
) {
    if (presence <= 0.01f) return
    val u = 1.dp.toPx()
    val open = 1f - (1f - presence).pow(3)
    val w = width * (0.12f + 0.88f * open)
    val left = center.x - w / 2f
    val top = center.y - height / 2f
    drawRoundRect(Color.Black.copy(alpha = 0.55f * presence), Offset(left, top), Size(w, height), CornerRadius(height / 2f))
    drawRoundRect(Color.White.copy(alpha = 0.1f * presence), Offset(left, top), Size(w, height), CornerRadius(height / 2f), style = Stroke(1f * u))
    val rows = 7
    val pitch = (height - 10f * u) / rows
    val cols = ((w - height * 0.7f) / pitch).toInt().coerceAtLeast(2)
    val x0 = center.x - (cols - 1) * pitch / 2f
    val dot = pitch * 0.32f
    val tau = 2f * PI.toFloat()
    for (c in 0 until cols) {
        val x = c / (cols - 1f)
        val envelope = sin(PI.toFloat() * x).coerceAtLeast(0f).pow(0.8f)
        val y1 = 3f * amplitude * envelope * sin(tau * (1.6f * x - 0.55f * t))
        val y2 = -3f * amplitude * envelope * sin(tau * (2.1f * x + 0.42f * t) + 1.3f)
        for (r in 0 until rows) {
            val row = (r - rows / 2).toFloat()
            val lit = max(max(glow(row - y1), glow(row - y2)), 0.6f * glow(row) * (1f - 0.5f * amplitude))
            val at = Offset(x0 + c * pitch, center.y + row * pitch)
            if (lit > 0.45f) drawCircle(Color.White.copy(alpha = 0.16f * lit * presence), dot * 2.3f, at)
            drawCircle(Color.White.copy(alpha = (0.1f + 0.9f * lit) * presence), dot, at)
        }
    }
}

private fun glow(d: Float): Float = exp(-(d * d) / 0.3f)

/** Below this the capsule draws as one still line, and the visualiser stops asking for frames. */
private const val Settled = 0.004f

/** Nothing measured (another app's music, a position not yet decoded): the waves idle rather than flatten. */
internal const val VisualiserIdleLevel = 0.35f

/**
 * The loudness the visualiser draws, moved one frame on: a root curve, so quiet passages still move
 * and loud ones fill the capsule; quick to rise and slow to fall, like a meter's needle.
 */
internal fun visualiserLevel(current: Float, measured: Boolean, whole: Float): Float {
    val target = if (measured) (sqrt((whole - 0.008f).coerceAtLeast(0f)) * 1.9f).coerceIn(0f, 1f) else VisualiserIdleLevel
    return current + (target - current) * (if (target > current) 0.45f else 0.08f)
}

/**
 * The visualiser as a piece of Now Playing (D-109): the vinyl's capsule of dots, on its own, filling
 * the place the transport keys had in the nostalgic layout. It runs its own frame loop and measures
 * the music only while it shows; with reduced motion it is one still lit line.
 */
@Composable
internal fun DotVisualiser(
    controller: PlaybackController,
    pulse: AudioPulse,
    playing: Boolean,
    modifier: Modifier = Modifier,
    height: Dp = 40.dp,
) {
    val reduced = PodiumTheme.motion.reduced
    val level = remember { mutableFloatStateOf(0f) }
    val clock = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(pulse, reduced, playing) {
        if (reduced) {
            level.floatValue = 0f
            return@LaunchedEffect
        }
        val listening = pulse.listen()
        try {
            val levels = FloatArray(3)
            var last = withFrameNanos { it }
            while (true) {
                withFrameNanos { now ->
                    val dt = ((now - last) / 1e9f).coerceIn(0f, 0.1f)
                    last = now
                    clock.floatValue += dt
                    val position = controller.positionMs()
                    // Stopped, the waves fall away to the one lit line rather than idling on as the
                    // vinyl's do: here there is no record turning to say the music is only paused.
                    level.floatValue = if (!playing) {
                        level.floatValue * (1f - 0.08f)
                    } else {
                        visualiserLevel(level.floatValue, pulse.levelsAt(position, levels), levels[AudioPulse.WHOLE])
                    }
                }
                // Stopped and flat: one lit line, which no further frame would change. The effect is
                // keyed on [playing], so starting the music starts it moving again.
                if (!playing && level.floatValue < Settled) {
                    level.floatValue = 0f
                    break
                }
            }
        } finally {
            listening.close()
        }
    }
    Canvas(modifier) {
        drawDotVisualiser(
            center = Offset(size.width / 2f, size.height / 2f),
            width = size.width,
            height = height.toPx().coerceAtMost(size.height),
            presence = 1f,
            amplitude = level.floatValue,
            t = clock.floatValue,
        )
    }
}
