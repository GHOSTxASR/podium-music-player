package app.podium.core.designsystem.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.podium.core.designsystem.artwork.ArtworkImage
import app.podium.core.designsystem.theme.PodiumTheme
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.delay

/**
 * The "infinite paper" (D-29): every screen is a column on one horizontal sheet. A list shows the
 * previous column peeking in at the left, its own items as a straight column bounded on the right by
 * an arc that mirrors the Wheel's right side (hollow facing the list, the selection indicator riding
 * on it), and a glimpse of the next column — what the focused item leads to — beyond the arc.
 */
object Paper {
    /** Width of the left strip where the previous column peeks in. */
    const val PeekFraction = 0.09f

    /** Width of the region beyond the arc where the next column is previewed. */
    const val NextFraction = 0.17f

    /** Gap between the rows' right edge and the arc. */
    val ArcGap: Dp = 12.dp

    /** The arc's radius relative to the readable height: large, so the bow stays subtle. */
    const val RadiusFactor = 1.8f

    /** How far a row at [dy] from the readable centre bends left, for readable height [h]. */
    fun bend(dy: Float, h: Float): Float {
        val r = h * RadiusFactor
        return r - sqrt((r * r - dy * dy).coerceAtLeast(0f))
    }
}

/**
 * The previous column's peek, provided by the shell for each screen (the screen itself doesn't
 * know where it sits on the paper). Null at the root.
 */
val LocalPaperPeek = staticCompositionLocalOf<(@Composable (Modifier) -> Unit)?> { null }

/**
 * How a screen's peek and preview behave while the screen itself slides on or off the paper. The
 * shell provides a fade tied to the navigation transition: the leaving screen's glimpses vanish at
 * once (the real column is arriving — its preview must not fly across the display) and the arriving
 * screen's appear once it has settled.
 */
val LocalPaperDecor = staticCompositionLocalOf<@Composable () -> Modifier> { { Modifier } }

/** Horizontal padding rows use; paper lists narrow it because the peek strip already gives air. */
val LocalRowPadding = staticCompositionLocalOf { 20.dp }

/** What the next column looks like, previewed beyond the arc while an item is focused (D-29). */
sealed interface MenuPreview {
    data object None : MenuPreview

    /** Artwork that slowly cycles with a crossfade; [round] for artists. */
    data class Artwork(val uris: List<String>, val round: Boolean = false) : MenuPreview

    /** Covers that step sideways, one at a time, like a record rack being browsed. */
    data class Carousel(val uris: List<String>) : MenuPreview

    /** A colour (a finish, a theme's display). */
    data class Swatch(val color: Color) : MenuPreview

    /** The device's own geometry (settings). */
    data object Instrument : MenuPreview
}

/**
 * The next column, seen from a little way off: smaller, dimmer and slightly soft, and never a card
 * (no frame, no shadow). Cycles change only every few seconds and the display rests between.
 */
@Composable
fun PreviewPane(preview: MenuPreview, size: Dp, modifier: Modifier = Modifier) {
    val colors = PodiumTheme.colors
    val motion = PodiumTheme.motion
    val distance = modifier
        .graphicsLayer {
            alpha = if (colors.isDark) 0.6f else 0.68f
            scaleX = 0.94f
            scaleY = 0.94f
        }
        .blur(1.5.dp)
    val corner = if (colors.isIndustrial) 2.dp else 6.dp
    when (preview) {
        MenuPreview.None -> Unit
        is MenuPreview.Swatch -> Box(distance.size(size).clip(RoundedCornerShape(corner)).background(preview.color))
        is MenuPreview.Artwork, is MenuPreview.Carousel -> {
            val uris = if (preview is MenuPreview.Artwork) preview.uris else (preview as MenuPreview.Carousel).uris
            if (uris.isEmpty()) return
            val round = preview is MenuPreview.Artwork && preview.round
            val sideways = preview is MenuPreview.Carousel
            var index by remember(uris) { mutableIntStateOf(0) }
            LaunchedEffect(uris, motion.reduced) {
                if (motion.reduced || uris.size < 2) return@LaunchedEffect
                while (true) {
                    delay(if (sideways) 3_200 else 4_200)
                    index = (index + 1) % uris.size
                }
            }
            AnimatedContent(
                targetState = index,
                transitionSpec = {
                    if (sideways) {
                        slideInHorizontally(tween(700, easing = FastOutSlowInEasing)) { it } togetherWith
                            slideOutHorizontally(tween(700, easing = FastOutSlowInEasing)) { -it }
                    } else {
                        (fadeIn(tween(1_100)) + slideInHorizontally(tween(1_100)) { it / 14 }) togetherWith fadeOut(tween(900))
                    }
                },
                modifier = distance,
                label = "previewCycle",
            ) { i ->
                Box(Modifier.size(size).clip(if (round) CircleShape else RoundedCornerShape(corner))) {
                    ArtworkImage(uris[i % uris.size], size, fallbackText = "", modifier = Modifier.fillMaxSize())
                }
            }
        }
        MenuPreview.Instrument -> {
            // The Wheel, drawn as an instrument diagram; a single point steps around it now and then.
            val angle = remember { Animatable(0f) }
            LaunchedEffect(motion.reduced) {
                if (motion.reduced) return@LaunchedEffect
                while (true) {
                    delay(3_600)
                    angle.animateTo(angle.value + 72f, tween(1_200, easing = FastOutSlowInEasing))
                }
            }
            Canvas(distance.size(size)) {
                val c = center
                val r = this.size.minDimension / 2f * 0.92f
                val stroke = Stroke(width = 1.5.dp.toPx())
                drawCircle(colors.labelSecondary, radius = r, center = c, style = stroke)
                drawCircle(colors.labelSecondary, radius = r * 0.38f, center = c, style = stroke)
                val a = Math.toRadians(angle.value.toDouble() - 90)
                val ringR = (r + r * 0.38f) / 2f
                drawCircle(colors.labelPrimary, radius = 4.dp.toPx(), center = Offset(c.x + ringR * cos(a).toFloat(), c.y + ringR * sin(a).toFloat()))
            }
        }
    }
}

/**
 * The previous column's labels peeking in at the left edge (D-29): right-aligned, so only their
 * ends show, centred on the item that led here ([chosen]), dim and slightly soft — the paper you
 * came from, still there.
 */
@Composable
fun PaperPeekLabels(labels: List<String>, chosen: Int, modifier: Modifier = Modifier) {
    val colors = PodiumTheme.colors
    val type = PodiumTheme.type
    val insets = LocalScreenInsets.current
    BoxWithConstraints(
        modifier
            .padding(top = insets.top, bottom = insets.bottom)
            .graphicsLayer { alpha = if (colors.isDark) 0.7f else 0.75f }
            .blur(0.8.dp),
    ) {
        // The item that led here lines up with the top of the new list, where the eye lands.
        val rowH = 52.dp
        val centre = 8.dp
        for (k in -5..5) {
            val label = labels.getOrNull(chosen + k) ?: continue
            Box(
                Modifier
                    .offset(y = centre + rowH * k)
                    .fillMaxWidth()
                    .height(rowH)
                    .wrapContentWidth(Alignment.End, unbounded = true)
                    .padding(end = 8.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                BasicText(
                    label,
                    style = type.row.copy(color = if (k == 0) colors.labelSecondary else colors.labelTertiary),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/** The previous column was a picture (Now Playing): its artwork's edge peeks in. */
@Composable
fun PaperPeekArtwork(uri: String?, modifier: Modifier = Modifier) {
    val colors = PodiumTheme.colors
    BoxWithConstraints(modifier.graphicsLayer { alpha = if (colors.isDark) 0.6f else 0.7f }.blur(1.dp)) {
        val size = maxHeight * 0.42f
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .wrapContentWidth(Alignment.End, unbounded = true)
                .size(size),
        ) {
            ArtworkImage(uri, size, fallbackText = "", modifier = Modifier.fillMaxSize())
        }
    }
}
