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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
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
 * The "infinite paper" (D-29, D-30): every screen is a column on one sheet. A list curves along an
 * arc that mirrors the Wheel's right side (the selection indicator riding on it), with the previous
 * column at middle-left and a glimpse of the next column — what the focused item leads to — at
 * middle-right. [PaperGeometry] holds the measurements.
 */
object Paper {
    /** Gap between the rows' right edge and the arc. */
    val ArcGap: Dp = 12.dp
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
    val distance = modifier.distant()
    val corner = previewCorner()
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
 * The paper's geometry for a screen of [width] × [height] whose readable region is inset by
 * [padTop] and [padBottom] (D-29, after the user's sketch): a ")" arc whose apex sits at mid-height
 * near the right; rows that ride the arc; the previous column's live miniature in the free space
 * the curve leaves at middle-left; the next column at middle-right beyond the arc. Shared by the
 * list layout and the navigation transitions, so a screen sliding away lands exactly in its box.
 */
class PaperGeometry(val width: Dp, val height: Dp, val padTop: Dp, val padBottom: Dp) {
    val readable: Dp = (height - padTop - padBottom).coerceAtLeast(1.dp)
    val centreY: Dp = padTop + readable / 2
    val apexX: Dp = width * 0.80f
    private val radius: Dp = readable * 1.05f
    val columnWidth: Dp = width * 0.56f
    /** The column's left edge where the arc is furthest right (mid-height); rows elsewhere sit further left. */
    val columnLeft: Dp = apexX - Paper.ArcGap - columnWidth
    val boxHeight: Dp = readable * 0.34f
    val boxTop: Dp = centreY - boxHeight / 2
    /** Scale at which a whole screen fits a box's height: the next column growing out of its box. */
    val miniScale: Float = boxHeight / height
    val rightBoxX: Dp = apexX + 12.dp

    /** The previous column's box mirrors the next one: the same square, running off the left edge. */
    val leftBoxRight: Dp = columnLeft - 10.dp
    val leftBoxX: Dp = leftBoxRight - boxHeight

    /** The previous column is shown zoomed in on the middle of its list, where the item that led here sits. */
    val peekScale: Float = (boxHeight / readable * 1.6f).coerceAtMost(0.6f)

    /** Where the previous screen's top-left sits, at [peekScale]: its labels start at the box's visible edge, its middle at the box's. */
    val peekOriginX: Dp = maxOf(leftBoxX, 0.dp) + 4.dp - columnLeft * peekScale
    val peekOriginY: Dp = centreY - centreY * peekScale

    /** How far left of the apex the arc (and a row) sits at [dy] from mid-height. */
    fun bend(dy: Dp): Dp {
        val r = radius.value
        val d = dy.value.coerceIn(-r, r)
        return (r - sqrt(r * r - d * d)).dp
    }

    fun arcX(y: Dp): Dp = apexX - bend(y - centreY)

    /** 0 at mid-height, 1 at either end of the readable region: drives the ends' fade and blur. */
    fun depth(y: Dp): Float = (kotlin.math.abs((y - centreY).value) / (readable.value / 2f)).coerceIn(0f, 1f)

    /** 1 inside the readable region, falling to 0 within [fade] beyond it — rows never drift under the title or the mini player. */
    fun within(y: Dp, fade: Dp = 24.dp): Float {
        val out = maxOf(padTop - y, y - (height - padBottom), 0.dp)
        return (1f - out / fade).coerceIn(0f, 1f)
    }
}

/** True while composing a live miniature of another column: no input, no glimpses, no side effects. */
val LocalMiniature = staticCompositionLocalOf { false }

/** In a miniature, the item (by list key) that led to the current column, shown focused. */
val LocalMiniatureFocusKey = staticCompositionLocalOf<String?> { null }

/** Both glimpses sit a step away from the column in focus: dimmer, a touch smaller, softly out of focus. */
@Composable
internal fun Modifier.distant(): Modifier {
    val dark = PodiumTheme.colors.isDark
    return graphicsLayer {
        alpha = if (dark) 0.6f else 0.68f
        scaleX = 0.94f
        scaleY = 0.94f
    }.blur(1.5.dp)
}

@Composable
internal fun previewCorner(): Dp = if (PodiumTheme.colors.isIndustrial) 2.dp else 6.dp

