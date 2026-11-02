package app.podium.core.designsystem.shell

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.podium.core.designsystem.component.pressFeedback
import app.podium.core.designsystem.glass.GlassMaterial
import app.podium.core.designsystem.glass.glass
import app.podium.core.designsystem.symbol.PodiumSymbol
import app.podium.core.designsystem.symbol.Symbol
import app.podium.core.designsystem.theme.DisplayStyle
import app.podium.core.designsystem.theme.DisplaySurface
import app.podium.core.designsystem.theme.LocalDisplaySurface
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.designsystem.theme.Spacing
import app.podium.core.designsystem.tonal.ContinuousShape
import app.podium.core.designsystem.tonal.SurfaceLevel
import app.podium.core.designsystem.tonal.continuousRect
import app.podium.core.designsystem.tonal.tonalSurface
import app.podium.core.designsystem.type.PodiumText
import app.podium.core.interaction.rememberPodiumHaptics
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** The lit displays' corner (D-103): continuous, so a little larger than a circular one to read the same. */
val ScreenCorner: Dp = 26.dp

/** A surface set the edge margin in from the display's edges: concentric with its corner (D-103). */
val EdgeCorner: Dp = ScreenCorner - Spacing.edge
private val IndustrialScreenCorner: Dp = 10.dp

/** Marks the display panel, so tests can check that nothing on screen escapes it. */
const val DisplayTestTag = "podium-display"

/** Marks the title bar's status housings, so tests can check where they sit (D-103). */
const val StatusTestTag = "podium-status"
/** The title bar's height: its housings with the edge margin above and below (D-103). */
val ScreenHeaderHeight: Dp = Spacing.titleBar
private val BezelWidth = 3.dp

/**
 * The display set into the device (D-26): a black bezel window, the panel inside it with its own
 * canvas, and a glass cover — a recess shadow, a hairline panel edge and one faint reflection drawn
 * over the content. A hard boundary: everything inside is clipped to the panel, so no artwork,
 * glow, scroll, transition or glass can reach the body. The screen is a text container, so it is
 * never glass itself.
 */
@Composable
fun VirtualScreen(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val colors = PodiumTheme.colors
    val continuous = !colors.isIndustrial
    val corner = if (colors.isIndustrial) IndustrialScreenCorner else ScreenCorner
    // Apple-style continuous corners on the lit displays (D-103); the instrument keeps its own.
    val outer = if (continuous) ContinuousShape(corner + BezelWidth) else RoundedCornerShape(corner + BezelWidth)
    val inner = if (continuous) ContinuousShape(corner) else RoundedCornerShape(corner)
    Box(
        modifier
            // Carbon (D-101): the display is set into the steel — a recessed seam around the bezel,
            // its lower lip catching the light.
            .then(if (colors.isTonal) Modifier.drawBehind { drawDisplaySeam(corner + BezelWidth) } else Modifier)
            .clip(outer)
            .background(Color(0xFF050608))
            .border(1.dp, Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.White.copy(alpha = 0.16f))), outer)
            .padding(BezelWidth),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .testTag(DisplayTestTag)
                .clip(inner)
                .background(colors.canvas)
                .displaySurface(LocalDisplaySurface.current, colors.canvas)
                .drawWithContent {
                    drawContent()
                    // Recess: the bezel's shadow falls a few dp onto the top of the display.
                    drawRect(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.32f),
                            1f to Color.Transparent,
                            endY = 10.dp.toPx(),
                        ),
                    )
                    // The display's own edge: a hairline where the panel meets the bezel.
                    val edge = Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.5f), Color.White.copy(alpha = 0.07f)))
                    if (continuous) {
                        val r = corner.toPx()
                        drawPath(continuousRect(Rect(Offset.Zero, size), r, r, r, r), edge, style = Stroke(width = 1.dp.toPx()))
                    } else {
                        drawRoundRect(edge, cornerRadius = CornerRadius(corner.toPx()), style = Stroke(width = 1.dp.toPx()))
                    }
                    // Cover glass: one faint reflection, strongest at the top-left corner. Industrial
                    // displays and Carbon are matte: no reflection at all (whatever the display shows,
                    // the lyrics' black sheet included, stays exactly as drawn).
                    if (colors.style == DisplayStyle.GLASS) drawRect(
                        Brush.linearGradient(
                            0f to Color.White.copy(alpha = if (colors.isDark) 0.06f else 0.10f),
                            0.38f to Color.Transparent,
                            start = Offset.Zero,
                            end = Offset(size.width, size.height * 0.7f),
                        ),
                    )
                },
            content = content,
        )
    }
}

/** The groove the display sits in, just outside the bezel, and the lit lip below it. */
private fun DrawScope.drawDisplaySeam(corner: Dp) {
    val r = corner.toPx()
    fun outline(grow: Float, radius: Float) =
        continuousRect(Rect(-grow, -grow, size.width + grow, size.height + grow), radius, radius, radius, radius)
    val gap = 1.dp.toPx()
    drawPath(outline(gap, r + gap), Color.Black.copy(alpha = 0.6f), style = Stroke(width = 2.dp.toPx()))
    val lip = 2.5.dp.toPx()
    drawPath(
        outline(lip, r + lip),
        Brush.verticalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.10f)), startY = 0f, endY = size.height + lip),
        style = Stroke(width = 1.dp.toPx()),
    )
}

/**
 * The screen's header (iPod heritage, D-103): on the left the back key in its housing and the title
 * beside it; on the right the status, every thing in its own housing ([StatusPills]): the time
 * (full screen, D-85) in one, and where the music is heard (D-52), the play state and the battery
 * (full screen) sharing another, a hairline between them. No background — content fades out beneath it.
 */
@Composable
fun ScreenHeader(
    title: String,
    canGoBack: Boolean,
    onBack: () -> Unit,
    /** True = playing, false = paused, null = nothing loaded. */
    playing: Boolean?,
    modifier: Modifier = Modifier,
    /** How deep in the hierarchy this title is: deeper titles arrive from the right, like the content. */
    depth: Int = 0,
    /** Where the music is heard, or muted (D-52); null shows nothing. */
    output: OutputIndicator? = null,
    /** Full screen (D-85): the time, in its own housing, in place of the phone's hidden status bar. */
    clock: Boolean = false,
    /** Full screen (D-85): the phone's battery, beside the play state; null shows nothing. */
    battery: BatteryLevel? = null,
) {
    val haptics = rememberPodiumHaptics()
    val colors = PodiumTheme.colors
    val type = PodiumTheme.type
    val motion = PodiumTheme.motion
    val ink = colors.labelPrimary
    val status = buildList {
        if (clock) add(StatusItem("time", housing = 0) { TitleBarClock(ink, Modifier.padding(horizontal = 2.dp)) })
        if (output != null) {
            add(
                StatusItem("output", housing = 1) {
                    AnimatedContent(output, transitionSpec = { statusSwap(motion.reduced) }, label = "headerOutput") { OutputIcon(it, ink, size = 15.dp) }
                },
            )
        }
        if (playing != null) {
            add(
                StatusItem("play", housing = 1) {
                    AnimatedContent(playing, transitionSpec = { statusSwap(motion.reduced) }, label = "headerPlay") { now ->
                        // Paused, the light blinks, as a deck's pause light does (D-107): Now Playing's
                        // orb has no play or pause glyph, so the title bar says it. Steady with reduced
                        // motion. Frames run only during the fades.
                        val light = remember { Animatable(1f) }
                        LaunchedEffect(now, motion.reduced) {
                            light.snapTo(1f)
                            if (!now && !motion.reduced) {
                                while (true) {
                                    delay(PauseLitMillis)
                                    light.animateTo(PauseDim, tween(PauseFadeMillis))
                                    delay(PauseDarkMillis)
                                    light.animateTo(1f, tween(PauseFadeMillis))
                                }
                            }
                        }
                        Symbol(
                            if (now) PodiumSymbol.Play else PodiumSymbol.Pause,
                            ink,
                            Modifier
                                .graphicsLayer { alpha = light.value }
                                .semantics { contentDescription = if (now) "Playing" else "Paused" },
                            size = 14.dp,
                            weight = 700,
                            filled = true,
                        )
                    }
                },
            )
        }
        if (battery != null) add(StatusItem("battery", housing = 1) { TitleBarBattery(battery, ink) })
    }
    Row(
        // Every housing sits the edge margin from the display's top and sides (D-103).
        modifier.fillMaxWidth().height(ScreenHeaderHeight).padding(start = Spacing.edge - BackKeyOverhang, end = Spacing.edge),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (canGoBack) {
            Box(
                Modifier
                    .size(BackKeyTouch)
                    .pressFeedback(0.86f)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        haptics.navigate(forward = false)
                        onBack()
                    }
                    .semantics {
                        role = Role.Button
                        contentDescription = "Back"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(Spacing.housing).backKeyHousing(), contentAlignment = Alignment.Center) {
                    Symbol(PodiumSymbol.ChevronLeft, ink, size = 21.dp, weight = 700)
                }
            }
            // The title keeps the edge margin from the back key, as the key keeps it from the edge.
            Spacer(Modifier.width(Spacing.edge - BackKeyOverhang))
        } else {
            Spacer(Modifier.width(BackKeyOverhang))
        }
        // The title travels with the paper (D-29): forward slides it in from the right, back from the left.
        AnimatedContent(
            targetState = title to depth,
            transitionSpec = {
                if (motion.reduced) {
                    fadeIn(motion.fadeFast()) togetherWith fadeOut(motion.fadeFast())
                } else {
                    // SPATIAL (D-65): the title travels on its column's own curve, so the two never drift apart.
                    val dir = if (targetState.second >= initialState.second) 1 else -1
                    (slideInHorizontally(motion.spatial()) { dir * it / 2 } + fadeIn(motion.fadeStandard())) togetherWith
                        (slideOutHorizontally(motion.spatial()) { -dir * it / 2 } + fadeOut(motion.fadeFast()))
                }
            },
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart,
            label = "headerTitle",
        ) { (shown, _) ->
            PodiumText(shown, type.title, colors.labelPrimary, Modifier.semantics { heading() }, maxLines = 1)
        }
        Spacer(Modifier.width(Spacing.edge))
        StatusPills(status, Modifier.testTag(StatusTestTag))
    }
}

/** A status icon changing in place: the old one shrinks away as the new one swells in. */
private fun <S> AnimatedContentTransitionScope<S>.statusSwap(reduced: Boolean): ContentTransform =
    if (reduced) {
        fadeIn(tween(100)) togetherWith fadeOut(tween(100))
    } else {
        (fadeIn(tween(140)) + scaleIn(spring(dampingRatio = 0.6f, stiffness = 700f), initialScale = 0.4f)) togetherWith
            (fadeOut(tween(90)) + scaleOut(tween(120), targetScale = 0.4f))
    }

/** The back key's housing, in the display's material: Carbon's solid key, Glass's glass, the instrument's hairline ring. */
@Composable
private fun Modifier.backKeyHousing(): Modifier {
    val colors = PodiumTheme.colors
    return when (colors.style) {
        DisplayStyle.TONAL -> tonalSurface(CircleShape, SurfaceLevel.Low)
        DisplayStyle.GLASS -> glass(GlassMaterial.Control, CircleShape)
        DisplayStyle.INDUSTRIAL -> border(1.dp, colors.separator, CircleShape).background(colors.labelPrimary.copy(alpha = 0.04f), CircleShape)
    }
}

/** The back key's touch target, a little larger than its housing. */
private val BackKeyTouch = 40.dp

/** How far the back key's touch target reaches past its housing on each side. */
private val BackKeyOverhang = (BackKeyTouch - Spacing.housing) / 2

/**
 * The listener's background picture (D-41), behind everything on the display: cropped to fill it,
 * centred, at the chosen opacity over its solid colour (the canvas already painted). Decoded once
 * by the app at about the display's size, so drawing is one bitmap blit.
 */
private fun Modifier.displaySurface(surface: DisplaySurface?, canvas: Color): Modifier {
    val image = surface?.image ?: return this
    if (surface.imageAlpha <= 0f) return this
    return drawWithCache {
        val scale = maxOf(size.width / image.width, size.height / image.height)
        val srcW = (size.width / scale).roundToInt().coerceIn(1, image.width)
        val srcH = (size.height / scale).roundToInt().coerceIn(1, image.height)
        val srcOffset = IntOffset((image.width - srcW) / 2, (image.height - srcH) / 2)
        val dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt())
        onDrawBehind {
            drawImage(image, srcOffset, IntSize(srcW, srcH), dstSize = dstSize, alpha = surface.imageAlpha, filterQuality = FilterQuality.Medium)
            // A veil of the display's own colour, so text reads on a busy picture.
            if (surface.veil > 0f) drawRect(canvas, alpha = surface.veil)
        }
    }
}

/** The paused light's beat: lit, a fade down, dark, a fade back (D-107). */
private const val PauseLitMillis = 800L
private const val PauseDarkMillis = 380L
private const val PauseFadeMillis = 160
private const val PauseDim = 0.12f
