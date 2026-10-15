package app.podium.feature.nowplaying

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.podium.core.designsystem.orb.OrbFrame
import app.podium.core.designsystem.orb.OrbRenderer
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.designsystem.type.PodiumText
import app.podium.player.api.AudioPulse
import app.podium.player.api.PlaybackController
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow

/**
 * The lyrics' stage with the orb (D-83, LYRICS_ARCHITECTURE.md §14), for the non-line themes: the
 * [page] (the lyric on its paper) in front, and, in long stretches without words ([timeline]), the
 * orb behind it on black.
 *
 * One camera moves between them. Pulling back, the page recedes into the orb's dark hollow, seen
 * through a round window whose edge is softly feathered into the dark, and the orb comes into view
 * around it; going in, the camera travels
 * through the hollow, the orb's ring passing out beyond the display, and the next line's page fills
 * the display as it starts. How far out the camera is comes from [OrbTimeline] at the playback
 * position, so seeking and pausing land in the right place. With reduced motion the two cross-fade
 * and the orb holds still.
 *
 * One frame loop drives everything here: the page's bass swell (D-82), the camera and the orb's
 * music ([OrbDrive]). It runs only while the music plays (or something is still settling) and stops
 * when the stage leaves the screen. The orb is drawn only while it shows.
 *
 * [caption]: shown over the orb when the song has no words to show ([OrbTimeline.always]).
 */
@Composable
internal fun OrbStage(
    controller: PlaybackController,
    pulse: AudioPulse,
    timeline: OrbTimeline,
    advancing: Boolean,
    following: Boolean,
    paper: Color,
    caption: String?,
    page: @Composable (swell: () -> Float) -> Unit,
) {
    val reduced = PodiumTheme.motion.reduced
    val renderer = remember { OrbRenderer() }
    val frame = remember { OrbFrame() }
    val drive = remember { OrbDrive() }
    val clock = remember { LyricClock() }
    val state = remember { OrbTimeline.State() }
    val levels = remember { FloatArray(3) }
    val probe = remember { LyricMotionProbe() }
    val amount = remember { mutableFloatStateOf(if (timeline.always) 1f else 0f) }
    val swell = remember { mutableFloatStateOf(0f) }
    val tick = remember { mutableLongStateOf(0L) }
    val isFollowing by rememberUpdatedState(following)
    // The first frame takes the camera's place outright (opening the screen mid-orb shows the orb);
    // after that it moves no faster than OrbTimeline.MAX_RATE_PER_MS.
    val placed = remember { booleanArrayOf(false) }
    // A song without words: the orb fades in once, then stays.
    val appear = remember(timeline) { Animatable(if (timeline.always && !reduced) 0f else 1f) }
    LaunchedEffect(timeline) { if (timeline.always) appear.animateTo(1f, tween(APPEAR_MS)) }
    val moving = pulse !== AudioPulse.None || timeline.any

    LaunchedEffect(timeline, advancing, reduced, pulse) {
        if (!moving) {
            amount.floatValue = 0f
            swell.floatValue = 0f
            return@LaunchedEffect
        }
        clock.reset()
        var last = -1L
        while (isActive) {
            val nanos = withFrameNanos { it }
            val position = clock.update(nanos, controller.positionMs(), advancing)
            timeline.at(position, state)
            val target = if (timeline.always || isFollowing) state.amount else 0f
            val dt = if (last < 0) 0f else (nanos - last) / 1e6f
            last = nanos
            amount.floatValue = if (placed[0]) OrbTimeline.follow(amount.floatValue, target, dt) else target
            placed[0] = true
            probe.target(target, position, amount.floatValue)
            val measured = advancing && pulse.levelsAt(position.toLong() + PulseFollower.LEAD_MS, levels)
            if (reduced) {
                swell.floatValue = 0f
            } else {
                drive.update(if (measured) levels else null, dt, advancing, frame)
                swell.floatValue = frame.low
            }
            if (amount.floatValue > 0f) tick.longValue++
            // Frames only while something moves: the orb shows, the swell is moving, the music is
            // being measured, or the camera is about to move. Otherwise wait for the next change
            // (looking for a seek a few times a second): paused and settled, or between orbs.
            val settled = reduced || drive.atRest
            val next = timeline.nextChangeMs(position.toLong())
            val untilNext = if (next == null) Long.MAX_VALUE else next - position.toLong()
            val needsFrames = !settled || amount.floatValue != target || (advancing && (amount.floatValue > 0f || (measured && !reduced) || untilNext < SOON_MS))
            if (!needsFrames) {
                delay(minOf(STILL_POLL_MS, (untilNext - SOON_MS).coerceAtLeast(MIN_WAIT_MS)))
                last = -1L
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        // The orb's world: black, and the orb, behind the page.
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_EXPRESSION")
            tick.longValue
            val t = amount.floatValue * appear.value
            if (t <= 0f) return@Canvas
            drawRect(OrbRenderer.Black, alpha = if (timeline.always) 1f else if (reduced) t else rampIn(t))
            val z = if (reduced) 1f else zoomAt(t)
            frame.alpha = if (reduced) t else smooth(0.05f, 0.6f, t) * (if (timeline.always) appear.value else 1f)
            if (reduced) {
                frame.energy = 0f
                frame.low = 0f
                frame.high = 0f
                frame.glint = 0f
            }
            renderer.draw(this, center, min(size.width, size.height) * ORB_SIZE * z, frame)
        }
        if (!timeline.always) {
            // The page: the lyric on its paper, receding into the hollow and coming out of it.
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val t = amount.floatValue
                        val s = if (reduced || t <= 0f) 1f else zoomAt(t) / ZOOM_IN
                        scaleX = s
                        scaleY = s
                        alpha = if (reduced) 1f - t else 1f - smooth(0.45f, 0.9f, t)
                        // Masked while it moves (below), so drawn on its own: the mask must not touch the orb.
                        compositingStrategy = if (!reduced && t > 0f) CompositingStrategy.Offscreen else CompositingStrategy.Auto
                    }
                    .drawWithContent {
                        drawContent()
                        val t = amount.floatValue
                        if (reduced || t <= 0f) return@drawWithContent
                        // Seen through the hollow: a round window, its edge softly feathered into the dark,
                        // closing in from beyond the corners to a circle well inside the page's width.
                        val corner = hypot(size.width / 2f, size.height / 2f) / (1f - WINDOW_FEATHER) * 1.02f
                        val window = min(size.width, size.height) / 2f * WINDOW_SIZE
                        val r = corner + (window - corner) * smooth(0f, 0.4f, t)
                        drawRect(
                            Brush.radialGradient(
                                0f to Color.Black,
                                (1f - WINDOW_FEATHER) to Color.Black,
                                1f to Color.Transparent,
                                center = center,
                                radius = r,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                    .background(paper),
            ) {
                page { swell.floatValue }
            }
        } else if (caption != null) {
            Box(Modifier.fillMaxSize().semantics { contentDescription = caption })
            PodiumText(
                caption,
                PodiumTheme.type.footnote,
                OrbRenderer.Ivory.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(top = 14.dp),
            )
        }
    }
}

/** The camera's zoom when it is [t] of the way out to the orb: [ZOOM_IN] at the words, 1 at the orb, geometric between. */
private fun zoomAt(t: Float): Float = ZOOM_IN.pow(1f - t.coerceIn(0f, 1f))

/** The black around the page comes in early in the pull-back, before any paper shows round it. */
private fun rampIn(t: Float): Float = smooth(0f, 0.12f, t)

private fun smooth(from: Float, to: Float, t: Float): Float {
    val x = ((t - from) / (to - from)).coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

/** How much nearer the camera is at the words than at the orb. */
private const val ZOOM_IN = 4.2f

/** The orb's outer radius at rest, as a share of the display's shorter side. */
private const val ORB_SIZE = 0.42f

/** The window's radius at the orb, as a share of half the page's shorter side: a circle, not the page's edges. */
private const val WINDOW_SIZE = 0.92f

/** How much of the window's radius its soft edge takes. */
private const val WINDOW_FEATHER = 0.32f
private const val APPEAR_MS = 700
private const val STILL_POLL_MS = 250L
private const val MIN_WAIT_MS = 16L

/** Start drawing every frame this long before the camera moves. */
private const val SOON_MS = 400L
