package app.podium.feature.nowplaying

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.zIndex
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.ui.graphics.TransformOrigin
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
import app.podium.core.model.TrackId
import app.podium.player.api.FavoritesRepository
import app.podium.player.api.NowPlayingItem
import app.podium.player.api.PlayIntent
import app.podium.player.api.PlaybackController
import app.podium.player.api.PlaybackOwner
import app.podium.player.api.RemoteProblem
import app.podium.player.api.RemoteStatus
import app.podium.player.api.PlaybackSnapshot
import app.podium.player.api.PlaybackStatus
import androidx.compose.ui.platform.LocalDensity
import app.podium.core.designsystem.component.Pinwheel
import app.podium.player.api.Recovery
import app.podium.player.api.RepeatMode
import app.podium.player.api.VolumeController
import kotlin.math.ceil
import kotlinx.coroutines.delay

/**
 * What the Wheel does on Now Playing. Center cycles through the modes as on the original
 * (volume → scrubber → the action row); Menu returns to volume; each mode times out back to it.
 */
private enum class WheelMode { Volume, Scrub, Actions }

private enum class Action { Shuffle, Repeat, Favorite, Lyrics, Queue, More }

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
    /** A quiet word on where the song belongs ("Online"), or null for the local library (D-34). */
    environmentOf: (TrackId) -> String? = { null },
    /** Now Playing ▸ Lyrics (LYRICS_ARCHITECTURE.md §7). */
    onLyrics: () -> Unit = {},
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
                delay(4_000)
                mode = WheelMode.Volume
                scrubTarget = null
            }
            WheelMode.Actions -> {
                delay(6_000)
                mode = WheelMode.Volume
            }
            WheelMode.Volume -> if (showVolume) {
                delay(1_500)
                showVolume = false
            }
        }
    }

    val isFavorite = item != null && item.trackId in favoriteIds
    val remoteOwner = snapshot.owner as? PlaybackOwner.Remote
    val controls = snapshot.controls
    val showMore: () -> Unit = {
        if (item != null) {
            overlay.show(
                MenuSpec(
                    item.title,
                    buildList {
                        add(MenuAction("Lyrics", onSelect = onLyrics))
                        add(MenuAction("Up Next", onSelect = onUpNext))
                        add(MenuAction(if (isFavorite) "Remove from favorites" else "Add to favorites") { favorites.toggle(item.trackId) })
                        if (remoteOwner != null) {
                            add(MenuAction("Open ${remoteOwner.displayName}") { controller.openRemoteApp() })
                            if (snapshot.canResumeLocal) add(MenuAction("Back to my music") { controller.resumeLocal() })
                        } else {
                            add(MenuAction("Clear Up Next", enabled = queue.upNext.isNotEmpty()) { controller.clearUpcoming() })
                        }
                    },
                ),
            )
        }
    }
    val perform: (Action) -> Unit = { action ->
        when (action) {
            // Only what the playing owner supports does anything (§8.4); the rest says no.
            Action.Shuffle -> if (controls.shuffle) controller.setShuffle(!snapshot.shuffleEnabled) else haptics.reject()
            Action.Repeat -> if (controls.repeat) controller.setRepeat(snapshot.repeatMode.next()) else haptics.reject()
            Action.Favorite -> item?.let { favorites.toggle(it.trackId) }
            Action.Lyrics -> onLyrics()
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
                        WheelMode.Volume -> mode = if (controls.seek && item?.durationMs != null) WheelMode.Scrub else WheelMode.Actions
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
        environment = environmentOf(item.trackId),
        queueHidden = queue.hidden,
    )
    // The cluster's glass refracts the artwork's environment, so the environment is the captured layer.
    GlassHost(
        modifier = Modifier.fillMaxSize(),
        content = { BackgroundExtension(rememberArtwork(item.artworkUri, 96.dp), Modifier.fillMaxSize()) },
        functional = {
            BoxWithConstraints(Modifier.fillMaxSize().padding(top = insets.top, bottom = insets.bottom)) {
                CompositionLocalProvider(LocalWindowWidth provides maxWidth) {
                when {
                    maxWidth > maxHeight * 1.15f -> LandscapeLayout(parts)
                    // Too short for a stacked cover to stay meaningful: the cover moves beside the title.
                    maxHeight - StackedControlsHeight < maxWidth * 0.45f -> CompactLayout(parts)
                    else -> PortraitLayout(parts)
                }
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
    val environment: String? = null,
    /** The playing owner doesn't show its queue: no "1 of 1" pretending to know. */
    val queueHidden: Boolean = false,
)

@Composable
private fun PortraitLayout(p: NowPlayingParts) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = Spacing.xl).padding(top = Spacing.xxs, bottom = Spacing.s),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The screen decides the composition: the cover and its facts take whatever the controls
        // leave — the cover at the left, the song's place, quality and any note beside it, in every
        // theme. A new cover travels in from the right edge of the display; the old one leaves left.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val travel = artTravel(rowWidth = maxWidth, startInset = Spacing.xl)
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                BoxWithConstraints(Modifier.weight(0.62f).fillMaxHeight().zIndex(1f), contentAlignment = Alignment.CenterStart) {
                    ArtworkStage(p, maxWidth, maxHeight, travel)
                }
                Spacer(Modifier.width(Spacing.m))
                StatusColumn(p, Modifier.weight(0.38f))
            }
        }
        Spacer(Modifier.height(Spacing.m))
        TrackText(p.item, Alignment.CenterHorizontally, TextAlign.Center)
        Spacer(Modifier.height(Spacing.s + Spacing.xxs))
        ProgressRow(p)
        Spacer(Modifier.height(Spacing.s))
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
            val rowWidth = LocalWindowWidth.current
            BoxWithConstraints(Modifier.weight(0.42f).aspectRatio(1f).zIndex(1f), contentAlignment = Alignment.Center) {
                ArtworkStage(p, maxWidth, maxHeight, artTravel(rowWidth, Spacing.l))
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
        val rowWidth = LocalWindowWidth.current
        BoxWithConstraints(Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true).zIndex(1f), contentAlignment = Alignment.Center) {
            ArtworkStage(p, maxWidth, maxHeight, artTravel(rowWidth, Spacing.l))
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
 * How far a cover travels when the song changes, in pixels: in from beyond the display's right
 * edge ([enterFromPx], measured from the cover's place) and out past its left edge ([exitToPx],
 * the cover's own width and the margin before it). Previous runs the other way.
 */
@Immutable
private data class ArtTravel(val enterFromPx: Int, val exitToPx: (fullWidth: Int) -> Int)

/** The width Now Playing lays out in: a cover leaving or arriving crosses all of it. */
private val LocalWindowWidth = staticCompositionLocalOf { 0.dp }

@Composable
private fun artTravel(rowWidth: Dp, startInset: Dp): ArtTravel {
    val density = LocalDensity.current
    return remember(rowWidth, startInset, density) {
        with(density) {
            val edge = startInset.roundToPx() + ArtTravelOvershoot.roundToPx()
            ArtTravel(enterFromPx = rowWidth.roundToPx() + edge, exitToPx = { fullWidth -> fullWidth + edge })
        }
    }
}

/** A little past the display's edge, so a cover is wholly gone before it's out of the frame. */
private val ArtTravelOvershoot = 12.dp

private const val ART_TRAVEL_MS = 460

/** The cover's lean (D-58): a turn about its vertical axis, seen from not far off. */
private const val ART_TILT_DEG = 26f
private const val ART_CAMERA_DISTANCE = 6f

/**
 * The artwork, as large as the box allows in its own proportions. It "breathes" with playback
 * (slightly smaller while paused), and a new cover arrives from the direction you skipped in —
 * a short crossfade with a little scale and travel, not a page flip. Songs sharing a cover keep it.
 */
@Composable
private fun ArtworkStage(p: NowPlayingParts, maxWidth: Dp, maxHeight: Dp, travel: ArtTravel? = null) {
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
            val dir = if (targetState.index >= initialState.index) 1 else -1
            if (motion.reduced) {
                fadeIn(tween(160)) togetherWith fadeOut(tween(160))
            } else if (travel != null) {
                // Next: the cover slides off the display to the left and the new one comes in from
                // the right, settling at the left. Previous: the mirror.
                val move = tween<IntOffset>(ART_TRAVEL_MS, easing = FastOutSlowInEasing)
                slideInHorizontally(move) { full -> if (dir > 0) travel.enterFromPx else -travel.exitToPx(full) } togetherWith
                    slideOutHorizontally(move) { full -> if (dir > 0) -travel.exitToPx(full) else travel.enterFromPx }
            } else {
                (fadeIn(tween(300, delayMillis = 60)) +
                    scaleIn(spring(dampingRatio = 0.86f, stiffness = 320f), initialScale = 0.93f) +
                    slideInHorizontally(spring(dampingRatio = 0.9f, stiffness = 320f)) { dir * it / 9 }) togetherWith
                    (fadeOut(tween(180)) +
                        scaleOut(tween(240), targetScale = 0.95f) +
                        slideOutHorizontally(tween(240)) { -dir * it / 9 })
            }.using(SizeTransform(clip = false))
        },
        modifier = Modifier
            .graphicsLayer {
                // Turned a little to the right, like a record leaning on a shelf: the near edge at
                // the left, the far edge receding. Hinged on the left so the cover keeps its place.
                rotationY = ART_TILT_DEG
                cameraDistance = ART_CAMERA_DISTANCE * density
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
            .graphicsLayer {
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
            } else if (p.snapshot.status == PlaybackStatus.Loading) {
                // Until the song first plays (an online song's stream is being found), the bar says so.
                LoadingBar()
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
                            .pointerInput(duration, p.snapshot.controls.seek) {
                                if (duration <= 0 || !p.snapshot.controls.seek) return@pointerInput
                                detectTapGestures { o -> controller.seekTo((o.x / size.width * duration).toLong().coerceIn(0, duration)) }
                            }
                            .pointerInput(duration, p.snapshot.controls.seek) {
                                if (duration <= 0 || !p.snapshot.controls.seek) return@pointerInput
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
 * Beside the cover: the song's place in the queue, the measured quality, where it belongs
 * ("Online"), and any note — an error, a retry, scrubbing, a source's quality claim — on as many
 * lines as it needs (up to five), so nothing is ever cut off whatever the font.
 */
@Composable
private fun StatusColumn(p: NowPlayingParts, modifier: Modifier = Modifier) {
    val colors = PodiumTheme.colors
    val type = PodiumTheme.type
    val (note, problem) = statusNote(p, includeEnvironment = false)
    Column(modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        if (!p.queueHidden) PodiumText("${p.item.indexInQueue + 1} of ${p.item.queueSize}", type.caption, colors.labelSecondary)
        QualityLabel.forNowPlaying(p.snapshot.quality)?.let { PodiumText(it, type.caption, colors.labelTertiary, maxLines = 2) }
        p.environment?.let { PodiumText(it, type.caption, colors.labelTertiary) }
        if (note != null) {
            PodiumText(
                note,
                type.caption,
                if (problem) colors.critical else colors.labelSecondary,
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                maxLines = 5,
            )
        }
    }
}

/**
 * The note Now Playing shows, and whether it's a problem: what another app is doing, what the
 * player is doing about a problem, the problem, scrubbing, a source's quality claim — or, where
 * there's room for one line only, where the song belongs.
 */
private fun statusNote(p: NowPlayingParts, includeEnvironment: Boolean): Pair<String?, Boolean> {
    val status = p.snapshot.status
    val remote = p.snapshot.remote
    val owner = (p.snapshot.owner as? PlaybackOwner.Remote)?.displayName
    val recovery = p.snapshot.recovery
    val note = when {
        remote != null && owner != null -> remoteNote(remote, owner)
        // While the player deals with a problem it says what it's doing, not only what went wrong.
        recovery == Recovery.REFRESHING_STREAM -> "The stream dropped. Getting it again…"
        status is PlaybackStatus.Error && recovery == Recovery.SKIPPING -> skippingCopy(status.error)
        status is PlaybackStatus.Error -> errorCopy(status.error)
        p.mode == WheelMode.Scrub -> "Scrubbing. Turn faster to cover more."
        // Which source serves the song is Podium's business, not the listener's (D-36).
        p.snapshot.quality.codecMismatch -> p.snapshot.quality.sourceClaimed?.let(QualityLabel::format)?.let { "Source reported $it" }
        includeEnvironment -> p.environment
        else -> null
    }
    val problem = (status is PlaybackStatus.Error && recovery == null) || remote?.problem != null
    return note to problem
}

/**
 * The progress row while a song loads: a small pinwheel and the word, centred where the bar goes.
 * It waits a moment before showing, so a local song that opens at once never flashes it.
 */
@Composable
private fun LoadingBar() {
    val colors = PodiumTheme.colors
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(LOADING_BAR_DELAY_MS)
        shown = true
    }
    Row(
        Modifier.fillMaxWidth().semantics { contentDescription = "Loading the song" },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (shown) {
            Pinwheel(size = 14.dp, color = colors.labelSecondary)
            Spacer(Modifier.width(Spacing.s))
            PodiumText("Loading", PodiumTheme.type.caption, colors.labelSecondary)
        }
    }
}

/** A song that opens within this shows no loading state at all. */
private const val LOADING_BAR_DELAY_MS = 250L

/**
 * Two fixed lines, so nothing below ever jumps: position in the queue and the measured quality,
 * then any note (an error, scrubbing, where the audio comes from, a source's quality claim). The
 * lines are as tall as the display font's captions: faces set larger never lose their lower line.
 */
@Composable
private fun StatusSlot(p: NowPlayingParts) {
    val colors = PodiumTheme.colors
    val type = PodiumTheme.type
    val quality = QualityLabel.forNowPlaying(p.snapshot.quality)
    val (note, problem) = statusNote(p, includeEnvironment = true)
    val lineHeight = with(LocalDensity.current) { type.caption.lineHeight.toDp() }
    Column(Modifier.fillMaxWidth().height(maxOf(34.dp, lineHeight * 2 + 4.dp)), verticalArrangement = Arrangement.Center) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            PodiumText(if (p.queueHidden) "" else "${p.item.indexInQueue + 1} of ${p.item.queueSize}", type.caption, colors.labelTertiary)
            quality?.let { PodiumText(it, type.caption, colors.labelTertiary) }
        }
        PodiumText(
            note ?: "",
            type.caption,
            if (problem) colors.critical else colors.labelSecondary,
            Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The primary controls: visually dominant. Glass shows bare glyphs; Carbon and Bone show them as
 * outlined rectangular keys, like an instrument's transport (D-29).
 */
@Composable
private fun TransportRow(p: NowPlayingParts) {
    val controller = p.controller
    val controls = p.snapshot.controls
    val dim = PodiumTheme.colors.labelTertiary
    if (PodiumTheme.colors.isIndustrial) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Key(PodiumSymbol.Previous, "Previous track", 26.dp, 64.dp, enabled = controls.previous, onClick = controller::previous)
            Key(if (p.playing) PodiumSymbol.Pause else PodiumSymbol.Play, if (p.playing) "Pause" else "Play", 30.dp, 84.dp, onClick = controller::togglePlayPause)
            Key(PodiumSymbol.Next, "Next track", 26.dp, 64.dp, enabled = controls.next, onClick = controller::next)
        }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
        ControlButton(PodiumSymbol.Previous, "Previous track", 30.dp, 52.dp, tint = if (controls.previous) PodiumTheme.colors.labelPrimary else dim, onClick = controller::previous)
        ControlButton(
            if (p.playing) PodiumSymbol.Pause else PodiumSymbol.Play,
            if (p.playing) "Pause" else "Play",
            42.dp,
            56.dp,
            onClick = controller::togglePlayPause,
        )
        ControlButton(PodiumSymbol.Next, "Next track", 30.dp, 52.dp, tint = if (controls.next) PodiumTheme.colors.labelPrimary else dim, onClick = controller::next)
    }
}

/**
 * Secondary actions: one glass capsule (a control cluster over media, the one place glass earns its
 * keep here), quieter glyphs, and the focus lens beneath the focused action in the Wheel's action mode.
 */
@Composable
private fun ActionCluster(p: NowPlayingParts) {
    BoxWithConstraints {
        // Six slots of up to 46 dp, narrower when the screen is: the cluster never overflows.
        ActionClusterContent(p, slot = ((maxWidth - Spacing.s) / Action.entries.size).coerceIn(36.dp, 46.dp))
    }
}

@Composable
private fun ActionClusterContent(p: NowPlayingParts, slot: Dp) {
    if (PodiumTheme.colors.isIndustrial) {
        ActionStrip(p, slot)
        return
    }
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
        if (lensVisible && colors.isIndustrial) {
            // Industrial: a small lit indicator under the focused action, no pill (D-29).
            Box(
                Modifier
                    .offset(x = lensX)
                    .size(slot, 40.dp)
                    .drawBehind {
                        val c = Offset(size.width / 2f, size.height - 4.dp.toPx())
                        if (colors.isDark) drawCircle(colors.highlight.copy(alpha = 0.22f), radius = 6.dp.toPx(), center = c)
                        drawCircle(colors.highlight, radius = 2.5.dp.toPx(), center = c)
                    },
            )
        } else if (lensVisible) {
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
                    Action.Lyrics -> Triple(PodiumSymbol.Lyrics, "Lyrics", null)
                    Action.Queue -> Triple(PodiumSymbol.Queue, "Up Next", null)
                    Action.More -> Triple(PodiumSymbol.More, "More", null)
                }
                val focusedHere = lensVisible && Action.entries.indexOf(action) == p.actionFocus
                val unsupported = (action == Action.Shuffle && !p.snapshot.controls.shuffle) || (action == Action.Repeat && !p.snapshot.controls.repeat)
                val tint = when {
                    unsupported -> colors.labelTertiary
                    action == Action.Favorite && on == true -> colors.like
                    on == true -> colors.highlightText
                    focusedHere && colors.isIndustrial -> colors.labelPrimary
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

/**
 * The industrial action row (D-29): one hairline-outlined strip divided into cells. The Wheel's
 * action mode lights a short bar under the focused cell — an indicator, not a highlight.
 */
@Composable
private fun ActionStrip(p: NowPlayingParts, slot: Dp) {
    val colors = PodiumTheme.colors
    val shape = RoundedCornerShape(2.dp)
    Row(
        Modifier.height(40.dp).border(1.dp, colors.separator, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Action.entries.forEachIndexed { i, action ->
            if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(colors.separator))
            val (symbol, label, on) = actionLook(p, action)
            val focused = p.mode == WheelMode.Actions && i == p.actionFocus
            Box(Modifier.size(slot, 40.dp), contentAlignment = Alignment.Center) {
                ControlButton(
                    symbol,
                    label,
                    20.dp,
                    slot,
                    tint = when {
                        (action == Action.Shuffle && !p.snapshot.controls.shuffle) || (action == Action.Repeat && !p.snapshot.controls.repeat) -> colors.labelTertiary
                        on == true || focused -> colors.labelPrimary
                        else -> colors.labelSecondary
                    },
                    filled = action == Action.Favorite && on == true,
                    toggled = on,
                    height = 40.dp,
                    onClick = { p.perform(action) },
                )
                if (focused) {
                    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp).size(14.dp, 2.dp).background(colors.labelPrimary))
                }
            }
        }
    }
}

/** Glyph, label and on/off state for an action. */
private fun actionLook(p: NowPlayingParts, action: Action): Triple<PodiumSymbol, String, Boolean?> = when (action) {
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
    Action.Lyrics -> Triple(PodiumSymbol.Lyrics, "Lyrics", null)
    Action.Queue -> Triple(PodiumSymbol.Queue, "Up Next", null)
    Action.More -> Triple(PodiumSymbol.More, "More", null)
}

/** An outlined rectangular key (industrial transport). Pressing darkens it briefly. */
@Composable
private fun Key(symbol: PodiumSymbol, label: String, iconSize: Dp, width: Dp, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = PodiumTheme.colors
    val haptics = rememberPodiumHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(2.dp)
    Box(
        Modifier
            .size(width, 48.dp)
            .border(1.dp, colors.separator, shape)
            .background(if (pressed) colors.labelPrimary.copy(alpha = 0.08f) else Color.Transparent, shape)
            .clickable(interactionSource = interaction, indication = null) {
                haptics.press()
                onClick()
            }
            .semantics {
                role = Role.Button
                contentDescription = label
            },
        contentAlignment = Alignment.Center,
    ) {
        Symbol(symbol, if (enabled) colors.labelPrimary else colors.labelTertiary, size = iconSize, weight = 600, filled = true)
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

/** What another app's playback means right now, and what to do about it (§8.4). */
internal fun remoteNote(remote: RemoteStatus, owner: String): String = when {
    remote.starting -> "Starting in $owner"
    remote.problem == RemoteProblem.NEEDS_ACCESS -> "Opened in $owner. Allow media controls in Settings to control it here."
    remote.problem == RemoteProblem.NO_APP -> "Install $owner to play this song."
    remote.problem == RemoteProblem.DID_NOT_START -> "$owner didn't start it. Hold Center, then Open $owner."
    remote.problem == RemoteProblem.ENDED -> "$owner stopped. Choose a song to play again."
    else -> "Playing in $owner"
}

/** Error copy (design-system.md §11): what happened, what's next; never raw exception text. */
/** An error, and that the next song is on its way. */
internal fun skippingCopy(error: PodiumError): String = when (error) {
    is PodiumError.Network, PodiumError.Offline -> "Can't reach the music source. Skipping to the next song."
    is PodiumError.NotFound -> "This song isn't available right now. Skipping to the next one."
    else -> "This song can't play right now. Skipping to the next one."
}

internal fun errorCopy(error: PodiumError): String = when (error) {
    is PodiumError.NotPlayable -> "This song can't play right now. Choose another one."
    is PodiumError.AuthExpired -> "Your sign-in expired. Sign in again from Settings."
    is PodiumError.InvalidMedia, is PodiumError.UnsupportedFormat -> "This song can't be played. Skipping to the next one."
    is PodiumError.NotFound -> "This song isn't available right now. Skipping to the next one."
    is PodiumError.Network, PodiumError.Offline -> "Can't reach the music source. Check your connection."
    is PodiumError.AuthRequired -> "Sign in to this source again to keep listening."
    is PodiumError.PermissionRequired -> "Allow music access from Home to play this song."
    else -> "Something went wrong playing this song."
}
