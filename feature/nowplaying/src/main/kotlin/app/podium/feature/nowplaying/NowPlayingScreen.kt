package app.podium.feature.nowplaying

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.podium.core.common.PodiumError
import app.podium.core.designsystem.artwork.BackgroundExtension
import app.podium.core.designsystem.artwork.FittedArtwork
import app.podium.core.designsystem.artwork.rememberArtwork
import app.podium.core.designsystem.component.LocalOverlayHost
import app.podium.core.designsystem.component.LocalScreenInsets
import app.podium.core.designsystem.component.MenuAction
import app.podium.core.designsystem.component.MenuSpec
import app.podium.core.designsystem.component.MessageState
import app.podium.core.designsystem.component.ProgressBar
import app.podium.core.designsystem.component.formatDuration
import app.podium.core.designsystem.component.rememberProgressTick
import app.podium.core.designsystem.glass.GlassHost
import app.podium.core.designsystem.glass.GlassMaterial
import app.podium.core.designsystem.glass.glass
import app.podium.core.designsystem.symbol.PodiumSymbol
import app.podium.core.designsystem.symbol.Symbol
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.designsystem.theme.Spacing
import app.podium.core.designsystem.type.PodiumText
import app.podium.core.interaction.InputTargetEffect
import app.podium.core.interaction.PodiumInput
import app.podium.core.interaction.WheelAcceleration
import app.podium.core.interaction.WheelButton
import app.podium.core.interaction.WheelContext
import app.podium.core.interaction.rememberPodiumHaptics
import app.podium.core.model.QualityLabel
import app.podium.player.api.NowPlayingItem
import app.podium.player.api.PlayIntent
import app.podium.player.api.PlaybackController
import app.podium.player.api.PlaybackSnapshot
import app.podium.player.api.PlaybackStatus
import app.podium.player.api.RepeatMode
import app.podium.player.api.VolumeController
import app.podium.sources.api.ResolutionPath
import kotlinx.coroutines.delay
import kotlin.math.ceil

/**
 * What the Wheel does on Now Playing. Center cycles through the modes as on the original
 * (volume → scrubber → the action row); Menu returns to volume; each mode times out back to it.
 */
private enum class WheelMode { Volume, Scrub, Actions }

private enum class Action { Shuffle, Repeat, Favorite, Queue, More }

/**
 * Now Playing (design-system.md §6.7, D-28): the artwork is the content and leads; text, progress
 * and transport sit beneath it; glass is used only for the secondary action cluster. Everything
 * here renders inside the virtual screen.
 */
@Composable
fun NowPlayingScreen(
    controller: PlaybackController,
    volume: VolumeController,
    favorites: FavoritesRepository,
    onUpNext: () -> Unit,
) {
    val snapshot by controller.snapshot.collectAsStateWithLifecycle()
    val queue by controller.queue.collectAsStateWithLifecycle()
    val volumeState by volume.state.collectAsStateWithLifecycle()
    val favoriteIds by favorites.favorites.collectAsStateWithLifecycle()
    val insets = LocalScreenInsets.current
    val haptics = rememberPodiumHaptics()
    val overlay = LocalOverlayHost.current
    val item = snapshot.item

    var mode by remember { mutableStateOf(WheelMode.Volume) }
    var lastWheelAt by remember { mutableLongStateOf(0L) }
    var showVolume by remember { mutableStateOf(false) }
    var detentRemainder by remember { mutableIntStateOf(0) }
    var scrubTarget by remember { mutableStateOf<Long?>(null) }
    var actionFocus by remember { mutableIntStateOf(0) }

    // The volume bar lingers 1.5 s after the last detent; scrubbing and the action row time out.
    LaunchedEffect(lastWheelAt, mode) {
        when (mode) {
            WheelMode.Scrub -> {
                delay(3_000)
                mode = WheelMode.Volume
                scrubTarget = null
            }
            WheelMode.Actions -> {
                delay(5_000)
                mode = WheelMode.Volume
            }
            WheelMode.Volume -> if (showVolume) {
                delay(1_500)
                showVolume = false
            }
        }
    }

    val isFavorite = item != null && item.trackId in favoriteIds
    val showMore: () -> Unit = {
        if (item != null) {
            overlay.show(
                MenuSpec(
                    item.title,
                    listOf(
                        MenuAction("Up Next", onSelect = onUpNext),
                        MenuAction(if (isFavorite) "Remove from favorites" else "Add to favorites") { favorites.toggle(item.trackId) },
                        MenuAction("Clear Up Next", enabled = queue.upNext.isNotEmpty()) { controller.clearUpcoming() },
                    ),
                ),
            )
        }
    }
    val perform: (Action) -> Unit = { action ->
        when (action) {
            Action.Shuffle -> controller.setShuffle(!snapshot.shuffleEnabled)
            Action.Repeat -> controller.setRepeat(snapshot.repeatMode.next())
            Action.Favorite -> item?.let { favorites.toggle(it.trackId) }
            Action.Queue -> onUpNext()
            Action.More -> showMore()
        }
    }

    InputTargetEffect(
        when (mode) {
            WheelMode.Volume -> WheelContext.VOLUME
            WheelMode.Scrub -> WheelContext.SCRUB
            WheelMode.Actions -> WheelContext.LIST_FOCUS
        },
    ) { input ->
        when (input) {
            is PodiumInput.Rotate -> {
                lastWheelAt = System.nanoTime()
                when (mode) {
                    WheelMode.Volume -> {
                        if (volumeState.isFixed) return@InputTargetEffect true
                        showVolume = true
                        // A full sweep of the volume range is about 1.25 rotations on any device (D-16):
                        // 25 detents of 18°.
                        val detentsPerStep = ceil(25.0 / volumeState.max.coerceAtLeast(1)).toInt().coerceAtLeast(1)
                        detentRemainder += input.detents
                        val steps = detentRemainder / detentsPerStep
                        detentRemainder -= steps * detentsPerStep
                        if (steps != 0) {
                            val before = volumeState.level
                            volume.step(steps)
                            if ((steps > 0 && before >= volumeState.max) || (steps < 0 && before <= 0)) haptics.boundary() else haptics.step()
                        }
                    }
                    WheelMode.Scrub -> {
                        // Slow turns are precise (≈ 0.5 % of the song per detent); fast spins accelerate.
                        val duration = item?.durationMs ?: return@InputTargetEffect true
                        val stepMs = (duration / 200).coerceIn(500, 3_000)
                        val from = scrubTarget ?: controller.positionMs()
                        val delta = input.detents * stepMs * WheelAcceleration.seekMultiplier(input.velocity)
                        val target = (from + delta).coerceIn(0, duration)
                        scrubTarget = target
                        controller.seekTo(target)
                        if (target == 0L || target == duration) haptics.boundary()
                    }
                    WheelMode.Actions -> {
                        val next = (actionFocus + input.detents).coerceIn(0, Action.entries.lastIndex)
                        if (next == actionFocus) haptics.boundary()
                        actionFocus = next
                    }
                }
                true
            }
            is PodiumInput.Press -> when (input.button) {
                WheelButton.CENTER -> {
                    lastWheelAt = System.nanoTime()
                    when (mode) {
                        WheelMode.Volume -> mode = WheelMode.Scrub
                        WheelMode.Scrub -> {
                            mode = WheelMode.Actions
                            scrubTarget = null
                        }
                        WheelMode.Actions -> {
                            haptics.confirm()
                            perform(Action.entries[actionFocus])
                        }
                    }
                    showVolume = false
                    true
                }
                WheelButton.MENU -> if (mode != WheelMode.Volume) {
                    mode = WheelMode.Volume
                    scrubTarget = null
                    true
                } else false
                else -> false
            }
            is PodiumInput.LongPress -> if (input.button == WheelButton.CENTER) {
                showMore()
                true
            } else false
            is PodiumInput.Release -> false
        }
    }

    if (item == null) {
        Box(Modifier.fillMaxSize().padding(top = insets.top, bottom = insets.bottom), contentAlignment = Alignment.Center) {
            MessageState(PodiumSymbol.Note, "Nothing playing", "Choose a song from Music to start.")
        }
        return
    }

    val parts = NowPlayingParts(
        snapshot = snapshot,
        item = item,
        playing = snapshot.intent == PlayIntent.PLAY,
        isFavorite = isFavorite,
        mode = mode,
        actionFocus = actionFocus,
        volumeFraction = volumeState.fraction,
        showVolume = showVolume && mode == WheelMode.Volume,
        scrubTarget = scrubTarget,
        controller = controller,
        perform = perform,
    )
    // The cluster's glass refracts the artwork's environment, so the environment is the captured layer.
    GlassHost(
        modifier = Modifier.fillMaxSize(),
        content = { BackgroundExtension(rememberArtwork(item.artworkUri, 96.dp), Modifier.fillMaxSize()) },
        functional = {
            BoxWithConstraints(Modifier.fillMaxSize().padding(top = insets.top, bottom = insets.bottom)) {
                when {
                    maxWidth > maxHeight * 1.15f -> LandscapeLayout(parts)
                    // Too short for a stacked cover to stay meaningful: the cover moves beside the title.
                    maxHeight - StackedControlsHeight < maxWidth * 0.45f -> CompactLayout(parts)
                    else -> PortraitLayout(parts)
                }
            }
        },
    )
}

private class NowPlayingParts(
    val snapshot: PlaybackSnapshot,
    val item: NowPlayingItem,
    val playing: Boolean,
    val isFavorite: Boolean,
    val mode: WheelMode,
    val actionFocus: Int,
    val volumeFraction: Float,
    val showVolume: Boolean,
    val scrubTarget: Long?,
    val controller: PlaybackController,
    val perform: (Action) -> Unit,
)

@Composable
private fun PortraitLayout(p: NowPlayingParts) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = Spacing.xl).padding(top = Spacing.xxs, bottom = Spacing.s),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The screen decides the composition: the artwork takes whatever the controls leave.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            ArtworkStage(p, maxWidth, maxHeight)
        }
        Spacer(Modifier.height(Spacing.m))
        TrackText(p.item, Alignment.CenterHorizontally, TextAlign.Center)
        Spacer(Modifier.height(Spacing.s + Spacing.xxs))
        ProgressRow(p)
        StatusSlot(p)
        Spacer(Modifier.height(Spacing.xxs))
        TransportRow(p)
        Spacer(Modifier.height(Spacing.xs))
        ActionCluster(p)
    }
}

/** Short screens: the cover sits beside the title, like the original's Now Playing. */
@Composable
private fun CompactLayout(p: NowPlayingParts) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = Spacing.l).padding(bottom = Spacing.s),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(Modifier.fillMaxWidth().weight(1f, fill = false), verticalAlignment = Alignment.CenterVertically) {
            BoxWithConstraints(Modifier.weight(0.42f).aspectRatio(1f), contentAlignment = Alignment.Center) {
                ArtworkStage(p, maxWidth, maxHeight)
            }
            Spacer(Modifier.width(Spacing.m))
            Box(Modifier.weight(0.58f)) { TrackText(p.item, Alignment.Start, TextAlign.Start) }
        }
        Spacer(Modifier.height(Spacing.s))
        ProgressRow(p)
        StatusSlot(p)
        TransportRow(p)
        Spacer(Modifier.height(Spacing.xs))
        ActionCluster(p)
    }
}

/** What the stacked layout needs below the cover (text, progress, status, transport, actions). */
private val StackedControlsHeight = 250.dp

/** Landscape screens read like the original: artwork on the left, everything else beside it. */
@Composable
private fun LandscapeLayout(p: NowPlayingParts) {
    Row(
        Modifier.fillMaxSize().padding(horizontal = Spacing.l).padding(bottom = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BoxWithConstraints(Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true), contentAlignment = Alignment.Center) {
            ArtworkStage(p, maxWidth, maxHeight)
        }
        Spacer(Modifier.width(Spacing.l))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            TrackText(p.item, Alignment.Start, TextAlign.Start)
            Spacer(Modifier.height(Spacing.s))
            ProgressRow(p)
            StatusSlot(p)
            TransportRow(p)
            Spacer(Modifier.height(Spacing.xs))
            ActionCluster(p)
        }
    }
}

private data class ArtKey(val identity: String, val uri: String?, val fallback: String, val index: Int)

/**
 * The artwork, as large as the box allows in its own proportions. It "breathes" with playback
 * (slightly smaller while paused), and a new cover arrives from the direction you skipped in —
 * a short crossfade with a little scale and travel, not a page flip. Songs sharing a cover keep it.
 */
@Composable
private fun ArtworkStage(p: NowPlayingParts, maxWidth: Dp, maxHeight: Dp) {
    val motion = PodiumTheme.motion
    val breathe by animateFloatAsState(
        if (p.playing || motion.reduced) 1f else 0.93f,
        spring(dampingRatio = 0.8f, stiffness = 240f),
        label = "breathe",
    )
    val key = ArtKey(p.item.artworkUri ?: p.item.uid.value, p.item.artworkUri, p.item.albumTitle ?: p.item.title, p.item.indexInQueue)
    AnimatedContent(
        targetState = key,
        contentKey = { it.identity },
        transitionSpec = {
            if (motion.reduced) {
                fadeIn(tween(160)) togetherWith fadeOut(tween(160))
            } else {
                val dir = if (targetState.index >= initialState.index) 1 else -1
                (fadeIn(tween(300, delayMillis = 60)) +
                    scaleIn(spring(dampingRatio = 0.86f, stiffness = 320f), initialScale = 0.93f) +
                    slideInHorizontally(spring(dampingRatio = 0.9f, stiffness = 320f)) { dir * it / 9 }) togetherWith
                    (fadeOut(tween(180)) +
                        scaleOut(tween(240), targetScale = 0.95f) +
                        slideOutHorizontally(tween(240)) { -dir * it / 9 })
            }.using(SizeTransform(clip = false))
        },
        modifier = Modifier.graphicsLayer {
            scaleX = breathe
            scaleY = breathe
        },
        contentAlignment = Alignment.Center,
        label = "artwork",
    ) { art ->
        FittedArtwork(
            uri = art.uri,
            fallbackText = art.fallback,
            maxWidth = maxWidth,
            maxHeight = maxHeight,
            modifier = Modifier.semantics { contentDescription = "Artwork" },
        )
    }
}

/** Title (strongest), artist, album. Long titles scroll slowly; the rest ellipsize. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackText(item: NowPlayingItem, alignment: Alignment.Horizontal, textAlign: TextAlign) {
    val colors = PodiumTheme.colors
    val type = PodiumTheme.type
    val motion = PodiumTheme.motion
    AnimatedContent(
        targetState = item,
        contentKey = { it.uid },
        transitionSpec = { fadeIn(tween(if (motion.reduced) 120 else 220, delayMillis = 40)) togetherWith fadeOut(tween(120)) },
        label = "trackText",
    ) { shown ->
        Column(Modifier.fillMaxWidth(), horizontalAlignment = alignment) {
            PodiumText(
                shown.title,
                type.nowPlayingTitle,
                colors.labelPrimary,
                Modifier
                    .then(if (motion.reduced) Modifier else Modifier.basicMarquee(initialDelayMillis = 1_800, repeatDelayMillis = 2_400))
                    .semantics { heading() },
                textAlign = textAlign,
            )
            PodiumText(shown.artistDisplay, type.nowPlayingSubtitle.copy(fontSize = type.body.fontSize), colors.labelSecondary, textAlign = textAlign)
            shown.albumTitle?.takeIf { it.isNotBlank() }?.let {
                PodiumText(it, type.footnote, colors.labelTertiary, textAlign = textAlign)
            }
        }
    }
}

/** Elapsed — bar — remaining on one line; the bar becomes the volume bar while volume changes. */
@Composable
private fun ProgressRow(p: NowPlayingParts) {
    val colors = PodiumTheme.colors
    val type = PodiumTheme.type
    val motion = PodiumTheme.motion
    val controller = p.controller
    val duration = p.item.durationMs ?: 0L
    val running = p.snapshot.status == PlaybackStatus.Playing
    val tick = rememberProgressTick(running, hz = 4)
    Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
        AnimatedContent(
            targetState = p.showVolume,
            transitionSpec = { fadeIn(motion.fadeFast()) togetherWith fadeOut(motion.fadeFast()) },
            modifier = Modifier.fillMaxWidth(),
            label = "progressOrVolume",
        ) { volumeVisible ->
            if (volumeVisible) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics { contentDescription = "Volume" }) {
                    Symbol(PodiumSymbol.VolumeDown, colors.labelSecondary, size = 18.dp)
                    ProgressBar({ p.volumeFraction }, running = false, modifier = Modifier.weight(1f).padding(horizontal = Spacing.s))
                    Symbol(PodiumSymbol.VolumeUp, colors.labelSecondary, size = 18.dp)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    @Suppress("UNUSED_EXPRESSION") tick
                    val position = p.scrubTarget ?: controller.positionMs()
                    BasicText(
                        formatDuration(position) ?: "0:00",
                        style = type.caption.copy(color = colors.labelSecondary, textAlign = TextAlign.Start),
                        modifier = Modifier.width(40.dp),
                    )
                    // Touch: tap or drag along the bar to seek.
                    Box(
                        Modifier
                            .weight(1f)
                            .height(24.dp)
                            .pointerInput(duration) {
                                if (duration <= 0) return@pointerInput
                                detectTapGestures { o -> controller.seekTo((o.x / size.width * duration).toLong().coerceIn(0, duration)) }
                            }
                            .pointerInput(duration) {
                                if (duration <= 0) return@pointerInput
                                detectHorizontalDragGestures { change, _ ->
                                    controller.seekTo((change.position.x / size.width * duration).toLong().coerceIn(0, duration))
                                }
                            }
                            .semantics { contentDescription = "Song position" },
                        contentAlignment = Alignment.Center,
                    ) {
                        ProgressBar(
                            value = { if (duration > 0) (p.scrubTarget ?: controller.positionMs()).toFloat() / duration else 0f },
                            running = running,
                            emphasized = p.mode == WheelMode.Scrub,
                        )
                    }
                    BasicText(
                        "−" + (formatDuration((duration - position).coerceAtLeast(0)) ?: "0:00"),
                        style = type.caption.copy(color = colors.labelSecondary, textAlign = TextAlign.End),
                        modifier = Modifier.width(44.dp),
                    )
                }
            }
        }
    }
}

/**
 * Two fixed lines, so nothing below ever jumps: position in the queue and the measured quality,
 * then any note (an error, scrubbing, where the audio comes from, a source's quality claim).
 */
@Composable
private fun StatusSlot(p: NowPlayingParts) {
    val colors = PodiumTheme.colors
    val type = PodiumTheme.type
    val status = p.snapshot.status
    val quality = QualityLabel.forNowPlaying(p.snapshot.quality)
    val note = when {
        status is PlaybackStatus.Error -> errorCopy(status.error)
        p.mode == WheelMode.Scrub -> "Scrubbing. Turn faster to cover more."
        p.item.resolutionPath != null && p.item.resolutionPath != ResolutionPath.OWN_SOURCE ->
            p.item.servedByDisplayName?.let { "Playing from $it" }
        p.snapshot.quality.codecMismatch -> p.snapshot.quality.sourceClaimed?.let(QualityLabel::format)?.let { "Source reported $it" }
        else -> null
    }
    Column(Modifier.fillMaxWidth().height(34.dp), verticalArrangement = Arrangement.Center) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            PodiumText("${p.item.indexInQueue + 1} of ${p.item.queueSize}", type.caption, colors.labelTertiary)
            quality?.let { PodiumText(it, type.caption, colors.labelTertiary) }
        }
        PodiumText(
            note ?: "",
            type.caption,
            if (status is PlaybackStatus.Error) colors.critical else colors.labelSecondary,
            Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
            textAlign = TextAlign.Center,
        )
    }
}

/** The primary controls: visually dominant, no container. */
@Composable
private fun TransportRow(p: NowPlayingParts) {
    val controller = p.controller
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
        ControlButton(PodiumSymbol.Previous, "Previous track", 30.dp, 52.dp, onClick = controller::previous)
        ControlButton(
            if (p.playing) PodiumSymbol.Pause else PodiumSymbol.Play,
            if (p.playing) "Pause" else "Play",
            42.dp,
            56.dp,
            onClick = controller::togglePlayPause,
        )
        ControlButton(PodiumSymbol.Next, "Next track", 30.dp, 52.dp, onClick = controller::next)
    }
}

/**
 * Secondary actions: one glass capsule (a control cluster over media, the one place glass earns its
 * keep here), quieter glyphs, and the focus lens beneath the focused action in the Wheel's action mode.
 */
@Composable
private fun ActionCluster(p: NowPlayingParts) {
    BoxWithConstraints {
        // Five slots of up to 46 dp, narrower when the screen is: the cluster never overflows.
        ActionClusterContent(p, slot = ((maxWidth - Spacing.s) / Action.entries.size).coerceIn(36.dp, 46.dp))
    }
}

@Composable
private fun ActionClusterContent(p: NowPlayingParts, slot: Dp) {
    val colors = PodiumTheme.colors
    val motion = PodiumTheme.motion
    val lensVisible = p.mode == WheelMode.Actions
    val lensX by animateDpAsState(
        slot * p.actionFocus,
        if (motion.reduced) tween(0) else spring(dampingRatio = 0.86f, stiffness = 1_400f),
        label = "actionLens",
    )
    Box(
        Modifier
            .height(40.dp)
            .glass(GlassMaterial.Control, RoundedCornerShape(20.dp))
            .padding(horizontal = Spacing.xs),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (lensVisible) {
            Box(
                Modifier
                    .offset(x = lensX)
                    .size(slot, 32.dp)
                    .drawBehind {
                        val r = CornerRadius(16.dp.toPx())
                        drawRoundRect(colors.highlight.copy(alpha = if (colors.isDark) 0.42f else 0.24f), cornerRadius = r)
                        drawRoundRect(
                            Color.White.copy(alpha = if (colors.isDark) 0.35f else 0.7f),
                            topLeft = Offset(0.5f, 0.5f),
                            size = Size(size.width - 1f, size.height - 1f),
                            cornerRadius = r,
                            style = Stroke(1.dp.toPx()),
                        )
                    },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Action.entries.forEach { action ->
                val (symbol, label, on) = when (action) {
                    Action.Shuffle -> Triple(if (p.snapshot.shuffleEnabled) PodiumSymbol.ShuffleOn else PodiumSymbol.Shuffle, "Shuffle", p.snapshot.shuffleEnabled)
                    Action.Repeat -> Triple(
                        if (p.snapshot.repeatMode == RepeatMode.ONE) PodiumSymbol.RepeatOne else PodiumSymbol.Repeat,
                        when (p.snapshot.repeatMode) {
                            RepeatMode.OFF -> "Repeat"
                            RepeatMode.ALL -> "Repeat all"
                            RepeatMode.ONE -> "Repeat one"
                        },
                        p.snapshot.repeatMode != RepeatMode.OFF,
                    )
                    Action.Favorite -> Triple(PodiumSymbol.Favorite, "Favorite", p.isFavorite)
                    Action.Queue -> Triple(PodiumSymbol.Queue, "Up Next", null)
                    Action.More -> Triple(PodiumSymbol.More, "More", null)
                }
                val tint = when {
                    action == Action.Favorite && on == true -> colors.like
                    on == true -> colors.highlightText
                    else -> colors.labelSecondary
                }
                ControlButton(
                    symbol,
                    label,
                    20.dp,
                    slot,
                    tint = tint,
                    filled = action == Action.Favorite && on == true,
                    toggled = on,
                    height = 40.dp,
                    onClick = { p.perform(action) },
                )
            }
        }
    }
}

/** A glyph button for the screen: generous touch target, press feedback, TalkBack state. */
@Composable
private fun ControlButton(
    symbol: PodiumSymbol,
    label: String,
    iconSize: Dp,
    width: Dp,
    onClick: () -> Unit,
    tint: Color = PodiumTheme.colors.labelPrimary,
    filled: Boolean = true,
    toggled: Boolean? = null,
    height: Dp = width,
) {
    val haptics = rememberPodiumHaptics()
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(width, height)
            .clickable(interactionSource = interaction, indication = null) {
                haptics.press()
                onClick()
            }
            .semantics {
                role = Role.Button
                contentDescription = label
                if (toggled != null) stateDescription = if (toggled) "On" else "Off"
            },
        contentAlignment = Alignment.Center,
    ) {
        Symbol(symbol, tint, size = iconSize, weight = 600, filled = filled)
    }
}

private fun RepeatMode.next() = when (this) {
    RepeatMode.OFF -> RepeatMode.ALL
    RepeatMode.ALL -> RepeatMode.ONE
    RepeatMode.ONE -> RepeatMode.OFF
}

/** Error copy (design-system.md §11): what happened, what's next; never raw exception text. */
internal fun errorCopy(error: PodiumError): String = when (error) {
    is PodiumError.InvalidMedia, is PodiumError.UnsupportedFormat -> "This song can't be played. Skipping to the next one."
    is PodiumError.NotFound -> "This song isn't available right now. Skipping to the next one."
    is PodiumError.Network, PodiumError.Offline -> "Can't reach the music source. Check your connection."
    is PodiumError.AuthRequired -> "Sign in to this source again to keep listening."
    is PodiumError.PermissionRequired -> "Allow access to music on this phone to play this song."
    else -> "Something went wrong playing this song."
}
