package app.podium.core.designsystem.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.podium.core.designsystem.artwork.ArtworkImage
import app.podium.core.designsystem.glass.GlassMaterial
import app.podium.core.designsystem.glass.glass
import app.podium.core.designsystem.symbol.PodiumSymbol
import app.podium.core.designsystem.symbol.Symbol
import app.podium.core.designsystem.theme.PodiumMotion
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.designsystem.theme.Spacing
import app.podium.core.designsystem.shell.EdgeCorner
import app.podium.core.designsystem.type.PodiumText
import app.podium.core.designsystem.tonal.ContinuousShape
import app.podium.core.designsystem.tonal.LocalAlbumTone
import app.podium.core.interaction.rememberPodiumHaptics
import kotlinx.coroutines.delay

/** A round glass button for the functional layer (title bar). */
@Composable
fun GlassIconButton(symbol: PodiumSymbol, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(40.dp)
            .glass(GlassMaterial.Control, CircleShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .semantics {
                role = Role.Button
                this.contentDescription = contentDescription
            },
        contentAlignment = Alignment.Center,
    ) {
        Symbol(symbol, PodiumTheme.colors.labelPrimary, size = 22.dp, weight = 600)
    }
}

/**
 * The title bar (design-system.md §6.4): floating glass controls and a centered title over the
 * scroll-edge effect — not a slab. The title cross-fades on navigation.
 */
@Composable
fun TitleBar(title: String, canGoBack: Boolean, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val motion = PodiumTheme.motion
    Box(modifier.fillMaxWidth().height(Spacing.titleBar).padding(horizontal = Spacing.l)) {
        AnimatedVisibility(
            visible = canGoBack,
            modifier = Modifier.align(Alignment.CenterStart),
            enter = fadeIn(motion.fadeFast()) + scaleIn(PodiumMotion.pressOut(), initialScale = 0.8f),
            exit = fadeOut(motion.fadeFast()) + scaleOut(PodiumMotion.pressIn(), targetScale = 0.8f),
        ) {
            GlassIconButton(PodiumSymbol.ChevronLeft, "Back", onBack)
        }
        AnimatedContent(
            targetState = title,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 56.dp),
            transitionSpec = { fadeIn(motion.fadeStandard()) togetherWith fadeOut(motion.fadeFast()) },
            label = "title",
        ) { t ->
            PodiumText(t, PodiumTheme.type.title, PodiumTheme.colors.labelPrimary)
        }
    }
}

/**
 * Re-reads [progress] about [hz] times a second while [running], invalidating only [content]'s
 * draw — playback position never flows through UI state (performance.md §3).
 */
@Composable
fun rememberProgressTick(running: Boolean, hz: Int = 15): Long {
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(running, hz) {
        while (running) {
            delay(1000L / hz)
            tick++
        }
    }
    return tick
}

/** A thin progress line (mini player); on Carbon it fills with the album's accent (D-101). */
@Composable
fun ProgressHairline(progress: () -> Float, running: Boolean, modifier: Modifier = Modifier) {
    val colors = PodiumTheme.colors
    val tone = LocalAlbumTone.current
    val tick = rememberProgressTick(running, hz = 10)
    Canvas(modifier.fillMaxWidth().height(2.dp)) {
        @Suppress("UNUSED_EXPRESSION") tick
        val r = CornerRadius(size.height / 2)
        drawRoundRect(colors.labelPrimary.copy(alpha = if (colors.isTonal) 0.14f else 0.18f), cornerRadius = r)
        val fill = if (colors.isTonal) tone.accent else colors.labelPrimary.copy(alpha = 0.8f)
        drawRoundRect(fill, size = Size(size.width * progress().coerceIn(0f, 1f), size.height), cornerRadius = r)
    }
}

/**
 * The mini player (design-system.md §6.5): a glass capsule just above the Wheel. Hidden on
 * Now Playing and Up Next, and when nothing is loaded.
 */
@Composable
fun MiniPlayer(
    title: String,
    subtitle: String,
    artworkUri: String?,
    isPlaying: Boolean,
    progress: () -> Float,
    onOpen: () -> Unit,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier,
    onNext: (() -> Unit)? = null,
) {
    val colors = PodiumTheme.colors
    val type = PodiumTheme.type
    val haptics = rememberPodiumHaptics()
    Box(
        modifier
            .height(Spacing.miniPlayer)
            // A large surface goes down only a little; its own keys go further (TACTILE, D-65).
            .pressFeedback(0.985f)
            .glass(GlassMaterial.Floating, if (colors.isIndustrial) RoundedCornerShape(3.dp) else ContinuousShape(EdgeCorner))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                haptics.navigate(forward = true)
                onOpen()
            }
            .semantics { contentDescription = "Now playing: $title, $subtitle. Opens Now Playing" },
    ) {
        // The artwork sits 8 dp from the top, the bottom and the start, and the last key's glyph
        // 8 dp from the end (D-103).
        Row(
            Modifier.fillMaxSize().padding(start = MiniPlayerPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtworkImage(artworkUri, 40.dp, fallbackText = title, corner = EdgeCorner - MiniPlayerPadding)
            Spacer(Modifier.width(Spacing.m))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                PodiumText(title, type.rowFocused.copy(fontSize = type.body.fontSize, lineHeight = type.body.lineHeight), colors.labelPrimary)
                PodiumText(subtitle, type.footnote, colors.labelSecondary)
            }
            Box(
                Modifier
                    .size(MiniPlayerKeyWidth, Spacing.miniPlayer)
                    .pressFeedback(0.86f)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        haptics.playPause(playing = !isPlaying)
                        onPlayPause()
                    }
                    .semantics {
                        role = Role.Button
                        contentDescription = if (isPlaying) "Pause" else "Play"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Symbol(if (isPlaying) PodiumSymbol.Pause else PodiumSymbol.Play, colors.labelPrimary, size = 26.dp, weight = 600, filled = true)
            }
            if (onNext != null) {
                Box(
                    Modifier
                        .size(MiniPlayerKeyWidth, Spacing.miniPlayer)
                        .pressFeedback(0.86f)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            haptics.skip(forward = true)
                            onNext()
                        }
                        .semantics {
                            role = Role.Button
                            contentDescription = "Next track"
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Symbol(PodiumSymbol.Next, colors.labelSecondary, size = 24.dp, weight = 600, filled = true)
                }
            }
        }
        ProgressHairline(progress, isPlaying, Modifier.align(Alignment.BottomCenter).padding(horizontal = Spacing.xl).padding(bottom = 5.dp))
    }
}

/** Inside the mini player, from its edges to its artwork (top, bottom and start) and to its last key's glyph. */
private val MiniPlayerPadding = Spacing.s

/** A mini player key: the full height, its 24 dp glyph centred 8 dp from the key's sides. */
private val MiniPlayerKeyWidth = 40.dp

/**
 * Scrubber / volume bar (design-system.md §6.8). Value is read in the draw phase. On Carbon (D-101)
 * a rounded track that fills with the album's accent, with Carbon's upright playhead when emphasized.
 */
@Composable
fun ProgressBar(value: () -> Float, running: Boolean, modifier: Modifier = Modifier, emphasized: Boolean = false, thickness: Dp = 4.dp) {
    val colors = PodiumTheme.colors
    val tone = LocalAlbumTone.current
    val tick = rememberProgressTick(running, hz = 30)
    val h = if (emphasized) thickness * 2 else thickness
    Canvas(modifier.fillMaxWidth().height(if (colors.isTonal && emphasized) 22.dp else 14.dp)) {
        @Suppress("UNUSED_EXPRESSION") tick
        val barH = h.toPx()
        val y = (size.height - barH) / 2
        val fraction = value().coerceIn(0f, 1f)
        if (colors.isTonal) {
            val track = (if (emphasized) 6.dp else thickness).toPx()
            val ty = (size.height - track) / 2
            val head = if (emphasized) Size(5.dp.toPx(), 20.dp.toPx()) else Size(0f, 0f)
            val r = CornerRadius(track / 2)
            drawRoundRect(colors.labelPrimary.copy(alpha = 0.16f), Offset(0f, ty), Size(size.width, track), r)
            val x = (size.width - head.width) * fraction
            drawRoundRect(tone.accent, Offset(0f, ty), Size(x + head.width / 2, track), r)
            if (emphasized) drawPlayhead(Offset(x + head.width / 2, size.height / 2), head, colors.labelPrimary)
            return@Canvas
        }
        if (colors.isIndustrial) {
            // A flat rule with a square playhead: an instrument's meter, not a pill.
            val rule = (barH / 2).coerceAtLeast(1.5.dp.toPx())
            val ry = (size.height - rule) / 2
            drawRect(colors.labelPrimary.copy(alpha = 0.2f), Offset(0f, ry), Size(size.width, rule))
            drawRect(colors.labelPrimary, Offset(0f, ry), Size(size.width * fraction, rule))
            val head = (if (emphasized) 9 else 6).dp.toPx()
            drawRect(colors.labelPrimary, Offset((size.width * fraction - head / 2).coerceIn(0f, size.width - head), (size.height - head) / 2), Size(head, head))
            return@Canvas
        }
        val r = CornerRadius(barH / 2)
        drawRoundRect(colors.labelPrimary.copy(alpha = 0.18f), Offset(0f, y), Size(size.width, barH), r)
        drawRoundRect(colors.labelPrimary, Offset(0f, y), Size(size.width * fraction, barH), r)
        if (emphasized) drawCircle(colors.labelPrimary, radius = 6.dp.toPx(), center = Offset(size.width * fraction, size.height / 2))
    }
}

/** Empty / error states (design-system.md §6.11, §11): glyph, title, one sentence, one action. */
@Composable
fun MessageState(symbol: PodiumSymbol, title: String, message: String, modifier: Modifier = Modifier) {
    val colors = PodiumTheme.colors
    Column(modifier.fillMaxWidth().padding(horizontal = Spacing.xxxl), horizontalAlignment = Alignment.CenterHorizontally) {
        Symbol(symbol, colors.labelTertiary, size = 48.dp, weight = 400)
        Spacer(Modifier.height(Spacing.m))
        BasicText(title, style = PodiumTheme.type.title.copy(color = colors.labelPrimary))
        Spacer(Modifier.height(Spacing.xs))
        BasicText(message, style = PodiumTheme.type.body.copy(color = colors.labelSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center))
    }
}
