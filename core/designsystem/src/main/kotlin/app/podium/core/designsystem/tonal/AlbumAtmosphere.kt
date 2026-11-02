package app.podium.core.designsystem.tonal

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.podium.core.designsystem.shell.grain
import app.podium.core.designsystem.theme.PodiumMotion
import app.podium.core.designsystem.theme.PodiumTheme
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The light behind the display (D-101, D-103): the playing album's [AtmosphereField], enlarged past
 * the display's edges so no edge of the cover shows, over the display's base. With no cover it is a
 * quiet neutral light from the top, so the display is never flat. Blur is in the field itself;
 * nothing here is blurred while drawing, and the content in front stays solid and crisp.
 *
 * The field is laid twice, enlarged and turned against each other. While [moving] (Now Playing,
 * while the music plays) the two drift across the display and turn against each other — a turn in
 * 47 s and 35 s — so the cover's colours travel all around it; when it stops they ease to a halt
 * rather than freeze.
 *
 * A new cover's colours blend in over 1.8 s (a short fade with reduced motion), the old ones under
 * them until then. A cover arriving mid-blend blends in over the mixture as it stands, so however
 * fast the songs change, the colours never jump. [level] (0–1, read while drawing) is how strongly the
 * album shows on the screen in front. A scrim keeps the top (under the header) and the corners
 * quieter, and a fine grain dithers the dark gradients, which an OLED panel would otherwise band.
 * Reduced motion: still.
 */
@Composable
fun AlbumAtmosphere(field: ImageBitmap?, level: () -> Float, modifier: Modifier = Modifier, moving: () -> Boolean = { false }) {
    val reduced = PodiumTheme.motion.reduced
    val colors = PodiumTheme.colors
    val canvas = colors.canvas
    val dark = colors.isDark
    // Every cover still showing, oldest first, each at its own strength.
    val layers = remember { mutableStateListOf(FieldLayer(field, 1f)) }
    LaunchedEffect(field) {
        if (layers.lastOrNull()?.field === field) return@LaunchedEffect
        val layer = FieldLayer(field, 0f)
        layers += layer
        layer.alpha.animateTo(1f, tween(if (reduced) 200 else 1_800, easing = BlendEasing))
        // Fully in, it covers everything under it.
        layers.removeAll { it !== layer }
    }
    // The flow: a clock that runs while moving, its speed easing in and out.
    val phase = remember { mutableFloatStateOf(0f) }
    val speed = remember { Animatable(0f) }
    val isMoving by rememberUpdatedState(moving)
    LaunchedEffect(reduced) {
        if (reduced) return@LaunchedEffect
        snapshotFlow { isMoving() }.collectLatest { go ->
            coroutineScope {
                launch { speed.animateTo(if (go) 1f else 0f, tween(if (go) 1_400 else 2_200, easing = PodiumMotion.Standard)) }
                // Frames only while it flows: at rest the atmosphere costs nothing.
                if (go || speed.value > 0.001f) {
                    var last = withFrameNanos { it }
                    while (go || speed.value > 0.001f) {
                        withFrameNanos { now ->
                            phase.floatValue += (now - last) / 1e9f * speed.value
                            last = now
                        }
                    }
                }
            }
        }
    }
    Box(
        modifier
            // Its own cached layer, like the body (D-65): while the display moves in front of it, the
            // atmosphere is composited as one picture, not redrawn pass by pass on every frame.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
                val glow = Brush.radialGradient(
                    0f to lerp(canvas, if (dark) Color.White else Color.Black, if (dark) 0.06f else 0.03f),
                    1f to canvas,
                    center = Offset(size.width / 2f, -size.height * 0.08f),
                    radius = size.height * 0.95f,
                )
                onDrawBehind {
                    drawRect(canvas)
                    drawRect(glow)
                }
            },
    ) {
        // The covers, crossfading; the level fades each draw rather than the layer, so no offscreen
        // buffer is drawn on every frame the display moves (the crossfade is near enough without one).
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = level().coerceIn(0f, 1f)
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }
                .drawBehind {
                    val t = phase.floatValue
                    // Each cover over the ones before it; a "no cover" fading in fades out what lies under it.
                    for (i in layers.indices) {
                        val cover = layers[i].field ?: continue
                        var a = layers[i].alpha.value
                        for (j in i + 1 until layers.size) if (layers[j].field == null) a *= 1f - layers[j].alpha.value
                        drawFlow(cover, a, t)
                    }
                },
        )
        Box(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    val shade = if (dark) Color.Black else Color.White
                    val header = Brush.verticalGradient(
                        0f to shade.copy(alpha = if (dark) 0.34f else 0.4f),
                        1f to Color.Transparent,
                        endY = 72.dp.toPx(),
                    )
                    val corners = Brush.radialGradient(
                        0.55f to Color.Transparent,
                        1f to shade.copy(alpha = if (dark) 0.38f else 0.3f),
                        center = Offset(size.width / 2f, size.height / 2f),
                        radius = max(size.width, size.height) * 0.72f,
                    )
                    onDrawBehind {
                        drawRect(header)
                        drawRect(corners)
                    }
                }
                .grain { DitherGrain },
        )
    }
}

/** A cover in the blend and how strongly it shows. */
@Stable
private class FieldLayer(val field: ImageBitmap?, alpha: Float) {
    val alpha: Animatable<Float, AnimationVector1D> = Animatable(alpha)
}

/**
 * The field laid twice, enlarged and turned against each other, drifting with [t] (seconds of
 * flow) across the display: one full layer and a larger one over it at half strength, each turning
 * its own way, so the cover's colours travel all around (D-103).
 */
private fun DrawScope.drawFlow(field: ImageBitmap, alpha: Float, t: Float) {
    if (alpha <= 0f) return
    val tau = 2f * PI.toFloat()
    val w = size.width
    val h = size.height
    translate(w * DriftA.x * sin(t * tau / 15f), h * DriftA.y * cos(t * tau / 19f)) {
        rotate(t * 360f / 47f) { drawField(field, alpha, DriftA.x, DriftA.y, 1f) }
    }
    translate(w * DriftB.x * cos(t * tau / 21f), h * DriftB.y * sin(t * tau / 13f)) {
        rotate(180f - t * 360f / 35f) { drawField(field, alpha * 0.55f, DriftB.x, DriftB.y, 1.25f) }
    }
}

/**
 * The field enlarged so that, turned any way and drifted as far as [driftX] × [driftY] of the
 * display, it still covers every corner (the cover's own edges never show), then [zoom] beyond.
 */
private fun DrawScope.drawField(field: ImageBitmap, alpha: Float, driftX: Float, driftY: Float, zoom: Float) {
    if (alpha <= 0f) return
    val reachX = size.width * (0.5f + driftX)
    val reachY = size.height * (0.5f + driftY)
    val cover = 2f * kotlin.math.sqrt(reachX * reachX + reachY * reachY) * FieldMargin
    val scale = max(cover / field.width, cover / field.height) * zoom
    val w = (field.width * scale).roundToInt()
    val h = (field.height * scale).roundToInt()
    drawImage(
        field,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(field.width, field.height),
        dstOffset = IntOffset(((size.width - w) / 2f).roundToInt(), ((size.height - h) / 2f).roundToInt()),
        dstSize = IntSize(w, h),
        alpha = alpha,
        filterQuality = FilterQuality.Low,
    )
}

/** A little beyond what the drift needs, so not even the blurred border shows. */
private const val FieldMargin = 1.05f

/** How far each layer drifts, as a share of the display's width and height. */
private val DriftA = Offset(0.16f, 0.13f)
private val DriftB = Offset(0.22f, 0.18f)

/** A gentle blend for a new cover's colours: as slow to leave the old as to settle on the new. */
private val BlendEasing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

/** Grain strength over the atmosphere: enough to dither 8-bit steps, too fine to see as texture. */
private const val DitherGrain = 0.07f
