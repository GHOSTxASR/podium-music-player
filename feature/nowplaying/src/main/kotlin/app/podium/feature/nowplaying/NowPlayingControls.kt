package app.podium.feature.nowplaying

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.podium.core.designsystem.component.ProgressBar
import app.podium.core.designsystem.component.SquiggleProgress
import app.podium.core.designsystem.component.formatDuration
import app.podium.core.designsystem.component.pressFeedback
import app.podium.core.designsystem.component.rememberProgressTick
import app.podium.core.designsystem.glass.GlassMaterial
import app.podium.core.designsystem.glass.glass
import app.podium.core.designsystem.symbol.PodiumSymbol
import app.podium.core.designsystem.symbol.Symbol
import app.podium.core.designsystem.theme.DisplayStyle
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.designsystem.theme.Spacing
import app.podium.core.designsystem.shell.EdgeCorner
import app.podium.core.designsystem.tonal.ContinuousShape
import app.podium.core.designsystem.tonal.LocalAlbumTone
import app.podium.core.designsystem.tonal.SurfaceLevel
import app.podium.core.designsystem.tonal.tonalSurface
import app.podium.core.designsystem.type.PodiumText
import app.podium.core.interaction.rememberPodiumHaptics
import app.podium.core.model.MediaKind
import app.podium.player.api.PlaybackStatus

/*
 * Now Playing's composition and controls (D-101, D-103), the same on every theme: the cover flat
 * and leading, its facts beside its lower edge; the song's title, artist and album reading down
 * from the cover's edge; the seek bar with the times beneath; the transport as one group of keys;
 * the secondary actions in one tray, a hairline between each, in the Wheel's order. Each theme paints
 * its own material on the same parts: Carbon's solid keys, Glass's clear glass catching the cover's
 * light, the instrument's outlines.
 */

/** The side margin: the display's edge margin (D-103). The cover, the text and the controls line up on it. */
private val Gutter = Spacing.edge

/**
 * Between Now Playing's groups (D-103): the cover, the song, the seek bar, the keys and the tray
 * read the same distance apart, measured between what's drawn (a line of text's ink, the
 * playhead, a key's edge), not between the boxes that hold them.
 */
private val GroupGap = Spacing.l

/** Stacked Now Playing: cover and status, then the song, the seek bar, the keys, the tray. */
@Composable
internal fun StackedPortrait(p: NowPlayingParts) {
    // The screen's insets already hold the edge margin above (under the title bar) and below.
    Column(Modifier.fillMaxSize().padding(horizontal = Gutter)) {
        // The cover as large as the column allows, sitting on the song's title; its facts beside its
        // lower edge, like a caption.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val travel = artTravel(rowWidth = maxWidth, startInset = Gutter)
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.Bottom) {
                BoxWithConstraints(Modifier.weight(0.72f).fillMaxHeight().zIndex(1f), contentAlignment = Alignment.BottomStart) {
                    // Breathing from its lower corner on the gutter, the cover stays lined up with the
                    // text and keeps its distance from the title.
                    ArtworkStage(p, maxWidth, maxHeight, travel, origin = TransformOrigin(0f, 1f))
                }
                Spacer(Modifier.width(Spacing.m))
                StatusColumn(p, Modifier.weight(0.28f).padding(bottom = Spacing.xxs))
            }
        }
        Spacer(Modifier.height(GroupGap - TitleInkInset))
        TrackText(p.item, Alignment.Start, TextAlign.Start)
        Spacer(Modifier.height(GroupGap - TextInkInset - PlayheadInset))
        NowPlayingProgress(p)
        Spacer(Modifier.height(GroupGap - TextInkInset))
        NowPlayingTransport(p, Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(GroupGap))
        NowPlayingActions(p)
    }
}

/**
 * The seek bar and the times under it; the volume bar in its place while the Wheel turns the volume;
 * "Loading" while the song first loads. One fixed height, so nothing below ever moves. Carbon and
 * Glass draw the wave (D-101); the instrument its flat rule.
 */
@Composable
internal fun NowPlayingProgress(p: NowPlayingParts) {
    val colors = PodiumTheme.colors
    val motion = PodiumTheme.motion
    val controller = p.controller
    val duration = p.item.durationMs ?: 0L
    val live = p.item.kind == MediaKind.LIVE
    val shown = when {
        p.showVolume -> ProgressShows.Volume
        p.snapshot.status == PlaybackStatus.Loading -> ProgressShows.Loading
        else -> ProgressShows.Song
    }
    AnimatedContent(
        targetState = shown,
        transitionSpec = { fadeIn(motion.fadeFast()) togetherWith fadeOut(motion.fadeFast()) },
        modifier = Modifier.fillMaxWidth().height(ProgressBlock),
        contentAlignment = Alignment.Center,
        label = "nowPlayingProgress",
    ) { state ->
        when (state) {
            ProgressShows.Volume -> Row(
                Modifier.fillMaxSize().semantics { contentDescription = "Volume" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Symbol(PodiumSymbol.VolumeDown, colors.labelSecondary, size = 18.dp, weight = 600)
                ProgressBar({ p.volumeFraction }, running = false, modifier = Modifier.weight(1f).padding(horizontal = Spacing.s))
                Symbol(PodiumSymbol.VolumeUp, colors.labelSecondary, size = 18.dp, weight = 600)
            }
            ProgressShows.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingBar() }
            ProgressShows.Song -> Column(Modifier.fillMaxSize()) {
                val position = {
                    when {
                        live -> 1f
                        duration > 0 -> (p.scrubTarget ?: controller.positionMs()).toFloat() / duration
                        else -> 0f
                    }
                }
                val seekable = p.snapshot.controls.seek && duration > 0 && !live
                if (colors.style == DisplayStyle.INDUSTRIAL) {
                    SeekRule(p, position, seekable, duration)
                } else {
                    SquiggleProgress(
                        progress = position,
                        playing = p.snapshot.status == PlaybackStatus.Playing,
                        seekable = seekable,
                        emphasized = p.mode == WheelMode.Scrub,
                        onSeek = { f -> controller.seekTo((f * duration).toLong().coerceIn(0, duration)) },
                    )
                }
                NowPlayingTimes(p)
            }
        }
    }
}

/** The instrument's flat rule, tapped or dragged to seek. */
@Composable
private fun SeekRule(p: NowPlayingParts, position: () -> Float, seekable: Boolean, duration: Long) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .then(
                if (!seekable) Modifier else Modifier.pointerSeek { f -> p.controller.seekTo((f * duration).toLong().coerceIn(0, duration)) },
            )
            .semantics { contentDescription = "Song position" },
        contentAlignment = Alignment.Center,
    ) {
        ProgressBar(position, running = p.snapshot.status == PlaybackStatus.Playing, emphasized = p.mode == WheelMode.Scrub)
    }
}

/** Tap or drag along it to seek to that fraction of the song. */
@Composable
private fun Modifier.pointerSeek(seek: (Float) -> Unit): Modifier {
    val current by rememberUpdatedState(seek)
    return pointerInput(Unit) { detectTapGestures { o -> current((o.x / size.width).coerceIn(0f, 1f)) } }
        .pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> current((change.position.x / size.width).coerceIn(0f, 1f)) } }
}

private enum class ProgressShows { Song, Volume, Loading }

/** The seek bar's block: the bar's 32 dp and the times' 16 dp line, nothing else. */
private val ProgressBlock = 48.dp

/** From the top of the title's line to its ink (the cap height's top). */
private val TitleInkInset = 6.dp

/** From the ink of a line of text to the bottom of its line (the baseline's descent). */
private val TextInkInset = 4.dp

/** From the top of the seek bar's 32 dp to its playhead's top. */
private val PlayheadInset = 7.dp

/** Elapsed at the left, time left at the right, under the bar's ends. Its own scope: it ticks, nothing else does. */
@Composable
private fun NowPlayingTimes(p: NowPlayingParts) {
    val colors = PodiumTheme.colors
    val type = PodiumTheme.type
    val duration = p.item.durationMs ?: 0L
    val tick = rememberProgressTick(p.snapshot.status == PlaybackStatus.Playing, hz = 4)
    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
        @Suppress("UNUSED_EXPRESSION") tick
        val position = p.scrubTarget ?: p.controller.positionMs()
        val style = type.caption.copy(color = colors.labelSecondary)
        BasicText(formatDuration(position) ?: "0:00", style = style)
        Spacer(Modifier.weight(1f))
        BasicText(
            // A live station has no end: it says so where the time left would be (D-90).
            if (p.item.kind == MediaKind.LIVE) "Live" else "−" + (formatDuration((duration - position).coerceAtLeast(0)) ?: "0:00"),
            style = style.copy(textAlign = TextAlign.End),
        )
    }
}

/**
 * The transport: previous and next as two keys with the orb between them (D-107), their outer corners
 * round and their inner corners curving away from it. The orb is the play key, and it carries the
 * state in its shape instead of a glyph: round while the music plays, a settled pebble when it is
 * stopped. On Glass the keys are clear glass, lit by the cover; on Carbon they are solid, and the orb
 * is ivory. [compact] for short screens.
 */
@Composable
internal fun NowPlayingTransport(p: NowPlayingParts, modifier: Modifier = Modifier, compact: Boolean = false) {
    val controller = p.controller
    val controls = p.snapshot.controls
    val height = if (compact) 48.dp else 56.dp
    val orb = if (compact) OrbCompact else OrbDiameter
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        // The group's own proportions, narrowed to fit a slim column (landscape) — never clipped.
        val scale = ((maxWidth - KeyGap * 2) / (SideKeyWidth * 2 + orb)).coerceAtMost(1f)
        Row(horizontalArrangement = Arrangement.spacedBy(KeyGap), verticalAlignment = Alignment.CenterVertically) {
            TransportKey(PodiumSymbol.Previous, "Previous track", controls.previous, leading = true, SideKeyWidth * scale, height, p.touched, controller::previous)
            OrbKey(p, orb * scale)
            TransportKey(PodiumSymbol.Next, "Next track", controls.next, leading = false, SideKeyWidth * scale, height, p.touched, controller::next)
        }
    }
}

/** A key's housing in the display's material. The orb beside them brings its own (D-107). */
@Composable
private fun Modifier.keyHousing(shape: Shape, pressed: () -> Boolean): Modifier {
    val colors = PodiumTheme.colors
    return when (colors.style) {
        DisplayStyle.TONAL -> tonalSurface(shape, SurfaceLevel.High, pressed = pressed)
        DisplayStyle.GLASS -> glass(GlassMaterial.Control, shape, pressed = pressed())
        DisplayStyle.INDUSTRIAL -> border(1.dp, colors.separator, shape)
            .background(if (pressed()) colors.labelPrimary.copy(alpha = 0.08f) else Color.Transparent, shape)
    }
}

@Composable
private fun keyShape(start: Dp, end: Dp): Shape =
    if (PodiumTheme.colors.style == DisplayStyle.INDUSTRIAL) RoundedCornerShape(3.dp) else ContinuousShape(start, end, end, start)

@Composable
private fun TransportKey(
    symbol: PodiumSymbol,
    label: String,
    enabled: Boolean,
    leading: Boolean,
    width: Dp,
    height: Dp,
    touched: () -> Unit,
    onClick: () -> Unit,
) {
    val colors = PodiumTheme.colors
    val haptics = rememberPodiumHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .size(width, height)
            .pressFeedback(0.96f)
            .keyHousing(if (leading) keyShape(KeyOuter, KeyInner) else keyShape(KeyInner, KeyOuter), pressed = { pressed })
            .clickable(interactionSource = interaction, indication = null) {
                touched()
                if (enabled) haptics.skip(forward = !leading) else haptics.reject()
                onClick()
            }
            .semantics {
                role = Role.Button
                contentDescription = label
            },
        contentAlignment = Alignment.Center,
    ) {
        Symbol(symbol, if (enabled) colors.labelPrimary else colors.labelTertiary, size = 26.dp, weight = 700, filled = true)
    }
}

private val KeyGap = 10.dp
private val KeyOuter = 26.dp

/** The corner facing the orb: it curves away from the round thing between the keys (D-107). */
private val KeyInner = 20.dp
private val SideKeyWidth = 72.dp

/** The orb stands taller than the keys either side of it: it is the one thing in the group that is alive. */
private val OrbDiameter = 72.dp
private val OrbCompact = 60.dp

/**
 * The secondary actions in one tray, in the Wheel's order (shuffle, repeat, favourite, lyrics,
 * vinyl, Up Next, more), a hairline between each. An action that's on sits on a small backing in the
 * accent; in the Wheel's action mode a key slides beneath the focused action.
 */
@Composable
internal fun NowPlayingActions(p: NowPlayingParts, compact: Boolean = false) {
    val colors = PodiumTheme.colors
    val tone = LocalAlbumTone.current
    val motion = PodiumTheme.motion
    val height = if (compact) 44.dp else 48.dp
    val lensVisible = p.mode == WheelMode.Actions && !p.trayHidden
    // Hidden (D-108), it slides down off the display and fades. Its place is kept, so the cover, the
    // song and the orb stay exactly where they were and only the tray goes. Read while drawing: the
    // slide costs no recomposition, and once it is out its own layer takes it out of reach of a touch.
    val away = animateFloatAsState(
        if (p.trayHidden) 1f else 0f,
        if (motion.reduced) tween(0) else spring(dampingRatio = 0.92f, stiffness = 420f),
        label = "trayAway",
    )
    val style = colors.style
    // At the display's foot, the edge margin in: concentric with its corners (D-103).
    val tray = if (style == DisplayStyle.INDUSTRIAL) RoundedCornerShape(2.dp) else ContinuousShape(EdgeCorner)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(height)
            .graphicsLayer {
                val gone = away.value
                translationY = gone * (size.height + TrayExit.toPx())
                alpha = 1f - gone
            }
            .then(
                when (style) {
                    DisplayStyle.TONAL -> Modifier.tonalSurface(tray, SurfaceLevel.Low)
                    DisplayStyle.GLASS -> Modifier.glass(GlassMaterial.Control, tray)
                    DisplayStyle.INDUSTRIAL -> Modifier.border(1.dp, colors.separator, tray)
                },
            ),
    ) {
        val pad = TrayPadding
        val slot = (maxWidth - pad * 2) / Action.entries.size
        val slotPx = with(LocalDensity.current) { slot.toPx() }
        // The focused action's key, as a slot position, gliding on the lens's own spring.
        val keyAt = remember { Animatable(p.actionFocus.toFloat()) }
        LaunchedEffect(p.actionFocus) {
            if (motion.reduced) keyAt.snapTo(p.actionFocus.toFloat())
            else keyAt.animateTo(p.actionFocus.toFloat(), spring(dampingRatio = 0.86f, stiffness = 1_400f))
        }
        val padPx = with(LocalDensity.current) { pad.toPx() }
        Box(
            Modifier.fillMaxSize().drawBehind {
                // Hairlines between the actions.
                if (style != DisplayStyle.INDUSTRIAL) {
                    val line = if (style == DisplayStyle.GLASS) Color.White.copy(alpha = if (colors.isDark) 0.18f else 0.45f) else colors.labelPrimary.copy(alpha = 0.12f)
                    val inset = size.height * 0.3f
                    for (i in 1 until Action.entries.size) {
                        val x = padPx + slotPx * i
                        drawLine(line, Offset(x, inset), Offset(x, size.height - inset), 1.dp.toPx())
                    }
                } else {
                    for (i in 1 until Action.entries.size) {
                        val x = padPx + slotPx * i
                        drawLine(colors.separator, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                    }
                }
                if (!lensVisible) return@drawBehind
                // The key fills its slot and keeps the tray's padding from the tray's edges on every
                // side, its corners concentric with the tray's (D-103).
                val l = padPx + slotPx * keyAt.value
                val r = l + slotPx
                val inset = padPx
                val h = size.height - inset * 2
                val cr = CornerRadius((EdgeCorner - TrayPadding).toPx())
                when (style) {
                    DisplayStyle.TONAL -> {
                        drawRoundRect(Color.Black.copy(alpha = 0.3f), Offset(l, inset + 1.5.dp.toPx()), Size(r - l, h), cr)
                        drawRoundRect(tone.surfaceHighest, Offset(l, inset), Size(r - l, h), cr)
                    }
                    DisplayStyle.GLASS -> {
                        // A pane of stained glass.
                        drawRoundRect(colors.highlight.copy(alpha = if (colors.isDark) 0.42f else 0.24f), Offset(l, inset), Size(r - l, h), cr)
                        drawRoundRect(
                            Brush.verticalGradient(listOf(Color.White.copy(alpha = if (colors.isDark) 0.35f else 0.7f), Color.Transparent), startY = inset, endY = inset + h),
                            Offset(l, inset),
                            Size(r - l, h),
                            cr,
                            style = Stroke(1.dp.toPx()),
                        )
                    }
                    DisplayStyle.INDUSTRIAL -> drawRect(colors.labelPrimary, Offset((l + r) / 2f - 7.dp.toPx(), size.height - 6.dp.toPx()), Size(14.dp.toPx(), 2.dp.toPx()))
                }
            },
        )
        Row(Modifier.fillMaxSize().padding(horizontal = pad), verticalAlignment = Alignment.CenterVertically) {
            Action.entries.forEachIndexed { i, action ->
                val (symbol, label, on) = actionLook(p, action)
                val focused = lensVisible && i == p.actionFocus
                val unsupported = (action == Action.Shuffle && !p.snapshot.controls.shuffle) || (action == Action.Repeat && !p.snapshot.controls.repeat)
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    if (on == true && style == DisplayStyle.TONAL) {
                        Box(Modifier.size(40.dp, 30.dp).drawBehind { drawRoundRect(tone.accentContainer, cornerRadius = CornerRadius(10.dp.toPx())) })
                    }
                    ControlButton(
                        symbol,
                        label,
                        20.dp,
                        slot,
                        tint = when {
                            unsupported -> colors.labelTertiary
                            on == true && style == DisplayStyle.TONAL -> tone.accent
                            on == true && action == Action.Favorite && style == DisplayStyle.GLASS -> colors.like
                            on == true -> if (style == DisplayStyle.GLASS) colors.highlightText else colors.labelPrimary
                            focused -> colors.labelPrimary
                            else -> colors.labelSecondary
                        },
                        filled = action == Action.Favorite && on == true,
                        toggled = on,
                        height = height,
                        onClick = { p.perform(action) },
                    )
                }
            }
        }
    }
}

private val TrayPadding = 4.dp

/** How far past the display's foot the hidden tray goes, so no edge of it is left showing (D-108). */
private val TrayExit = 24.dp


/** The measured quality (audio-architecture.md §5), as a small label in the display's material beside the cover. */
@Composable
internal fun QualityChip(text: String) {
    val colors = PodiumTheme.colors
    val tone = LocalAlbumTone.current
    val style = colors.style
    Box(
        Modifier
            .drawBehind {
                val r = CornerRadius(if (style == DisplayStyle.INDUSTRIAL) 2.dp.toPx() else size.height / 2f)
                when (style) {
                    DisplayStyle.TONAL -> drawRoundRect(tone.surfaceLow, cornerRadius = r)
                    DisplayStyle.GLASS -> drawRoundRect(Color.White.copy(alpha = if (colors.isDark) 0.1f else 0.5f), cornerRadius = r)
                    DisplayStyle.INDUSTRIAL -> Unit
                }
                drawRoundRect(
                    if (style == DisplayStyle.GLASS) Color.White.copy(alpha = if (colors.isDark) 0.22f else 0.7f) else colors.separator,
                    cornerRadius = r,
                    style = Stroke(1.dp.toPx()),
                )
            }
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        PodiumText(text, PodiumTheme.type.caption, colors.labelPrimary, maxLines = 2)
    }
}
