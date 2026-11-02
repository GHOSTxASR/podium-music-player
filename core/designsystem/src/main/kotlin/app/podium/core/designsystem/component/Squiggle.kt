package app.podium.core.designsystem.component

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.designsystem.tonal.LocalAlbumTone
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Carbon's seek bar (D-101): the part already played is a line that undulates gently, in the
 * album's accent, travelling toward the playhead while the music plays and lying flat when it
 * pauses; the part still to come is a quiet straight line. The playhead is an upright bar with a
 * dark halo, so it reads on any album's light.
 *
 * Everything moving is read while drawing — [progress] on every frame the wave moves, so the line
 * and the time are never out of step — and nothing recomposes as it plays. The wave flattens into
 * the start and into the playhead, so it never meets either at an angle.
 *
 * Touch: put a finger down anywhere on it and the playhead goes there; drag and the song follows
 * ([onSeek] on every move, as the flat bar did); lift and it stays where it was left. Until the
 * player reports the new place, the bar shows where the finger left it rather than jumping back.
 * [emphasized] is the Wheel's scrub mode: a bolder line and a taller playhead. Reduced motion: the
 * wave holds still.
 */
@Composable
fun SquiggleProgress(
    progress: () -> Float,
    playing: Boolean,
    modifier: Modifier = Modifier,
    seekable: Boolean = true,
    emphasized: Boolean = false,
    onSeek: (Float) -> Unit = {},
    height: Dp = 32.dp,
) {
    val colors = PodiumTheme.colors
    val tone = LocalAlbumTone.current
    val reduced = PodiumTheme.motion.reduced
    val seek by rememberUpdatedState(onSeek)
    val scope = rememberCoroutineScope()

    val phase = remember { mutableFloatStateOf(0f) }
    val amplitude = remember { Animatable(if (playing) 1f else 0f) }
    val bold = remember { Animatable(if (emphasized) 1f else 0f) }
    // While a finger holds the playhead, and for a moment after it lets go.
    var touching by remember { mutableStateOf(false) }
    val touchFraction = remember { mutableFloatStateOf(0f) }
    val heldUntil = remember { mutableLongStateOf(0L) }

    // Playing: the line rises into its wave; paused: it settles flat, without a bounce.
    LaunchedEffect(playing) { amplitude.animateTo(if (playing) 1f else 0f, spring(dampingRatio = 1f, stiffness = if (playing) 90f else 260f)) }
    LaunchedEffect(emphasized, touching) { bold.animateTo(if (emphasized || touching) 1f else 0f, spring(dampingRatio = 0.9f, stiffness = 900f)) }
    // The wave travels while the music plays (one wavelength every ~2 s): every frame, but only then.
    val tick = remember { mutableIntStateOf(0) }
    LaunchedEffect(playing, reduced) {
        if (!playing) return@LaunchedEffect
        if (reduced) {
            // The wave holds still; the playhead still moves on.
            while (true) {
                delay(REDUCED_TICK_MS)
                tick.intValue++
            }
        }
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                phase.floatValue = ((phase.floatValue + (now - last) / 1e9f * PHASE_PER_SECOND) % TWO_PI)
                last = now
            }
        }
    }

    val path = remember { Path() }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .semantics {
                contentDescription = "Song position"
                progressBarRangeInfo = ProgressBarRangeInfo(progress().coerceIn(0f, 1f), 0f..1f)
                if (seekable) setProgress { target ->
                    seek(target.coerceIn(0f, 1f))
                    true
                }
            }
            .pointerInput(seekable) {
                if (!seekable) return@pointerInput
                val inset = Inset.toPx()
                fun fractionAt(x: Float) = ((x - inset) / (size.width - inset * 2)).coerceIn(0f, 1f)
                awaitEachGesture {
                    val down = awaitFirstDown()
                    touching = true
                    touchFraction.floatValue = fractionAt(down.position.x)
                    down.consume()
                    var moved = false
                    var lastSent = -1f
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val f = fractionAt(change.position.x)
                        if (f != touchFraction.floatValue) {
                            moved = true
                            touchFraction.floatValue = f
                            seek(f)
                            lastSent = f
                        }
                        change.consume()
                    }
                    // A tap seeks once, on lift; a drag has been seeking all along and lands here.
                    if (!moved || touchFraction.floatValue != lastSent) seek(touchFraction.floatValue)
                    heldUntil.longValue = SystemClock.uptimeMillis() + HOLD_AFTER_SEEK_MS
                    touching = false
                    scope.launch(start = CoroutineStart.UNDISPATCHED) { bold.animateTo(if (emphasized) 1f else 0f, spring(dampingRatio = 0.9f, stiffness = 700f)) }
                }
            },
    ) {
        val playerFraction = progress().coerceIn(0f, 1f)
        val held = SystemClock.uptimeMillis() < heldUntil.longValue && abs(playerFraction - touchFraction.floatValue) > 0.004f
        val fraction = if (touching || held) touchFraction.floatValue else playerFraction
        @Suppress("UNUSED_EXPRESSION") tick.intValue

        val b = bold.value
        val inset = Inset.toPx()
        val start = inset
        val end = size.width - inset
        val cy = size.height / 2f
        val headX = start + (end - start) * fraction
        val stroke = (3.dp + 1.dp * b).toPx()
        val wave = WaveLength.toPx()
        val amp = (2.5.dp + 1.dp * b).toPx() * amplitude.value
        val head = Size((5.dp + 1.dp * b).toPx(), (18.dp + 6.dp * b).toPx())

        // Still to come: a quiet straight line from just past the playhead.
        val restStart = headX + head.width / 2 + 3.dp.toPx()
        if (restStart < end) {
            drawLine(colors.labelPrimary.copy(alpha = 0.2f), Offset(restStart, cy), Offset(end, cy), stroke, StrokeCap.Round)
        }
        // Played: the wave, flattening into the start and into the playhead.
        if (headX > start) {
            path.rewind()
            path.moveTo(start, cy)
            val step = 1.5.dp.toPx()
            val inTaper = wave * 0.5f
            val outTaper = wave
            val shift = phase.floatValue
            var x = start
            while (x < headX) {
                x = minOf(x + step, headX)
                val taper = minOf(1f, (x - start) / inTaper, (headX - x) / outTaper).coerceAtLeast(0f)
                val y = cy + amp * taper * sin(TWO_PI * (x - start) / wave - shift)
                path.lineTo(x, y)
            }
            drawPath(path, tone.accent, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        drawPlayhead(Offset(headX, cy), head, colors.labelPrimary)
    }
}

/** Carbon's playhead (D-101): an upright bar with a dark halo, so it reads on any album's light. */
internal fun DrawScope.drawPlayhead(center: Offset, size: Size, color: Color) {
    val halo = 1.5.dp.toPx()
    drawRoundRect(
        Color.Black.copy(alpha = 0.4f),
        Offset(center.x - size.width / 2 - halo, center.y - size.height / 2 - halo),
        Size(size.width + halo * 2, size.height + halo * 2),
        CornerRadius(size.width / 2 + halo),
    )
    drawRoundRect(color, Offset(center.x - size.width / 2, center.y - size.height / 2), size, CornerRadius(size.width / 2))
}

/** Room at either end for the playhead. */
private val Inset = 4.dp

/** One undulation of the played line. */
private val WaveLength = 22.dp

private const val TWO_PI = (2 * PI).toFloat()

/** How fast the wave travels: about one wavelength every two seconds. */
private const val PHASE_PER_SECOND = TWO_PI / 2.1f

/** With reduced motion, how often the still wave's playhead moves on. */
private const val REDUCED_TICK_MS = 66L

/** After a seek, how long the bar trusts the finger over a player that hasn't caught up. */
private const val HOLD_AFTER_SEEK_MS = 700L
