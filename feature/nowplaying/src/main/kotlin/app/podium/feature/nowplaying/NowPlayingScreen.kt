package app.podium.feature.nowplaying

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.zIndex
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.podium.core.common.PodiumError
import app.podium.core.designsystem.artwork.FittedArtwork
import app.podium.core.designsystem.artwork.LocalArtworkLoader
import app.podium.core.designsystem.artwork.artworkBucketPx
import app.podium.core.designsystem.artwork.rememberArtworkState
import app.podium.core.designsystem.theme.DisplayStyle
import app.podium.core.designsystem.tonal.AlbumAtmosphere
import app.podium.core.designsystem.tonal.rememberCoverAnalysis
import app.podium.core.designsystem.component.LocalColumnTransition
import app.podium.core.designsystem.component.LocalOverlayHost
import app.podium.core.designsystem.component.LocalImmersiveHost
import app.podium.core.designsystem.component.LocalScreenInsets
import app.podium.core.designsystem.component.MenuAction
import app.podium.core.designsystem.component.MenuSpec
import app.podium.core.designsystem.component.MessageState
import app.podium.core.designsystem.component.pressFeedback
import app.podium.core.designsystem.glass.GlassHost
import app.podium.core.designsystem.glass.glass
import app.podium.core.designsystem.symbol.PodiumSymbol
import app.podium.core.designsystem.symbol.Symbol
import app.podium.core.designsystem.theme.PodiumMotion
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.designsystem.theme.Spacing
import app.podium.core.designsystem.type.PodiumText
import app.podium.core.interaction.InputTargetEffect
import app.podium.core.interaction.PodiumInput
import app.podium.core.interaction.WheelAcceleration
import app.podium.core.interaction.WheelButton
import app.podium.core.interaction.WheelContext
import app.podium.core.interaction.rememberPodiumHaptics
import app.podium.core.model.ArtistCredit
import app.podium.core.model.MediaKind
import app.podium.core.model.QualityLabel
import app.podium.core.model.QueueUid
import app.podium.core.model.Track
import app.podium.core.model.TrackId
import app.podium.player.api.FavoritesRepository
import app.podium.player.api.NowPlayingItem
import app.podium.player.api.PlayIntent
import app.podium.player.api.AudioPulse
import app.podium.player.api.PlaybackController
import app.podium.player.api.PlaybackOwner
import app.podium.player.api.RemoteProblem
import app.podium.player.api.RemoteStatus
import app.podium.player.api.PlaybackSnapshot
import app.podium.player.api.PlaybackStatus
import androidx.compose.ui.platform.LocalDensity
import app.podium.core.designsystem.component.Pinwheel
import app.podium.player.api.Recovery
import app.podium.player.api.SleepTimer
import app.podium.player.api.SleepTimerState
import app.podium.player.api.SleepTimerText
import app.podium.player.api.RepeatMode
import app.podium.player.api.VolumeController
import kotlin.math.ceil
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * What the Wheel does on Now Playing. Center cycles through the modes as on the original
 * (volume → scrubber → the action row); Menu returns to volume; each mode times out back to it.
 */
internal enum class WheelMode { Volume, Scrub, Actions }

internal enum class Action { Shuffle, Repeat, Favorite, Lyrics, Vinyl, Queue, More }

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
    /** Add to playlist for a song of the library (D-106); null leaves it out. */
    onAddToPlaylist: ((TrackId) -> Unit)? = null,
    /** Now Playing ▸ Lyrics (LYRICS_ARCHITECTURE.md §7). */
    onLyrics: () -> Unit = {},
    /** The playing song in full, for its album and artists (D-74); null when it can't be found. */
    trackOf: suspend (TrackId) -> Track? = { null },
    /** Opens the song's album, in the song's own environment (D-34). */
    onOpenAlbum: (Track) -> Unit = {},
    /** Opens one of the song's artists, in the song's own environment. */
    onOpenArtist: (ArtistCredit) -> Unit = {},
    /** The sleep timer (D-75); null hides it. */
    sleepTimer: SleepTimer? = null,
    /** How the music sounds, for the vinyl's visualiser (D-104). */
    pulse: AudioPulse = AudioPulse.None,
    /** Settings ▸ Now Playing's tray (D-108): the tray slides away when nothing has been touched. */
    autoHideTray: Boolean = false,
) {
    val snapshot by controller.snapshot.collectAsStateWithLifecycle()
    val queue by controller.queue.collectAsStateWithLifecycle()
    val volumeState by volume.state.collectAsStateWithLifecycle()
    val favoriteIds by favorites.favorites.collectAsStateWithLifecycle()
    val insets = LocalScreenInsets.current
    val haptics = rememberPodiumHaptics()
    val overlay = LocalOverlayHost.current
    val immersive = LocalImmersiveHost.current
    val vinyl = remember { VinylState() }
    val item = snapshot.item
    // The music's sound is measured for this screen only while it shows (D-83): the orb dances to it.
    LifecycleResumeEffect(pulse) {
        val listening = pulse.listen()
        onPauseOrDispose { listening.close() }
    }

    var mode by remember { mutableStateOf(WheelMode.Volume) }
    var lastWheelAt by remember { mutableLongStateOf(0L) }
    var showVolume by remember { mutableStateOf(false) }
    var detentRemainder by remember { mutableIntStateOf(0) }
    var scrubTarget by remember { mutableStateOf<Long?>(null) }
    var actionFocus by remember { mutableIntStateOf(0) }

    /**
     * The tray has slid away (D-108). It goes once nothing has been touched for [TrayIdleMillis], and
     * only while the Wheel is resting on the volume — never out from under someone using the actions,
     * and never behind the vinyl. Any touch, anywhere, puts the clock back.
     */
    var trayHidden by remember { mutableStateOf(false) }
    var lastTouchAt by remember { mutableLongStateOf(0L) }
    val touched: () -> Unit = { lastTouchAt = System.nanoTime() }
    LaunchedEffect(autoHideTray, lastTouchAt, lastWheelAt, mode, trayHidden, vinyl.open) {
        if (!autoHideTray || trayHidden || mode != WheelMode.Volume || vinyl.open) return@LaunchedEffect
        delay(TrayIdleMillis)
        trayHidden = true
    }
    // Turning the setting off brings it straight back, so nobody is left hunting for it.
    LaunchedEffect(autoHideTray) { if (!autoHideTray) trayHidden = false }

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

    // The song in full, for Go to album and Go to artist; kept only while it's the one playing.
    // Opening the menu without them asks again (the first lookup may still be out, or have failed).
    var lookAgain by remember { mutableIntStateOf(0) }
    val looked by produceState<Track?>(null, item?.trackId, lookAgain) {
        val id = item?.trackId ?: return@produceState
        runCatching { trackOf(id) }.getOrNull()?.let { value = it }
    }
    val playingTrack = looked?.takeIf { it.id == item?.trackId }

    // While the sleep timer is set, a line says when the music stops (counting down each second).
    val sleepState by (sleepTimer?.state ?: remember { MutableStateFlow<SleepTimerState>(SleepTimerState.Off) }).collectAsStateWithLifecycle()
    val sleepNote by produceState<String?>(null, sleepState) {
        while (true) {
            value = SleepTimerText.note(sleepState, sleepTimer?.remainingMs())
            if (sleepState !is SleepTimerState.Counting) break
            delay(1_000)
        }
    }

    val isFavorite = item != null && item.trackId in favoriteIds
    val remoteOwner = snapshot.owner as? PlaybackOwner.Remote
    val controls = snapshot.controls
    val showMore: () -> Unit = {
        if (item != null) {
            val goTo = goToActions(playingTrack, onOpenAlbum, onOpenArtist)
            if (goTo.isEmpty()) lookAgain++
            overlay.show(
                MenuSpec(
                    item.title,
                    buildList {
                        add(MenuAction("Lyrics", onSelect = onLyrics))
                        add(MenuAction("Up Next", onSelect = onUpNext))
                        add(MenuAction(if (isFavorite) "Remove from favorites" else "Add to favorites") { favorites.toggle(item.trackId) })
                        if (onAddToPlaylist != null && remoteOwner == null && environmentOf(item.trackId) == null) {
                            add(MenuAction("Add to playlist") { onAddToPlaylist(item.trackId) })
                        }
                        goTo.forEach(::add)
                        if (sleepTimer != null) add(MenuAction("Sleep timer") { overlay.show(sleepTimerMenu(sleepTimer)) })
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
    // The vinyl (D-104): it takes the whole display, over the title bar, until Menu brings it back.
    val openVinyl: () -> Unit = {
        if (item != null && !vinyl.open) {
            vinyl.open = true
            immersive.show(onBack = { vinyl.backPresses++ }) {
                VinylStage(controller, pulse, vinyl, immersive) {
                    immersive.close()
                    vinyl.open = false
                }
            }
        }
    }
    // Leaving Now Playing takes the vinyl with it.
    DisposableEffect(Unit) {
        onDispose {
            if (vinyl.open) immersive.close()
            vinyl.open = false
            vinyl.holdsCover = false
        }
    }
    val perform: (Action) -> Unit = { action ->
        touched()
        // An action tapped while the tray is still sliding away only brings it back (D-108).
        if (trayHidden) {
            trayHidden = false
        } else when (action) {
            // Only what the playing owner supports does anything (§8.4); the rest says no.
            // Each says what it did (D-105): on or off, into a screen.
            Action.Shuffle -> if (controls.shuffle) {
                haptics.toggle(on = !snapshot.shuffleEnabled)
                controller.setShuffle(!snapshot.shuffleEnabled)
            } else {
                haptics.reject()
            }
            Action.Repeat -> if (controls.repeat) {
                val next = snapshot.repeatMode.next()
                haptics.toggle(on = next != RepeatMode.OFF)
                controller.setRepeat(next)
            } else {
                haptics.reject()
            }
            Action.Favorite -> item?.let {
                haptics.favorite(on = !isFavorite)
                favorites.toggle(it.trackId)
            }
            Action.Lyrics -> {
                haptics.navigate(forward = true)
                onLyrics()
            }
            Action.Vinyl -> {
                haptics.navigate(forward = true)
                openVinyl()
            }
            Action.Queue -> {
                haptics.navigate(forward = true)
                onUpNext()
            }
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
        // The tray has slid away (D-108): whatever the Wheel does next only brings it back — no
        // selecting, no pausing, no going back — and from the frame after that it works as it always
        // does. A release is swallowed too, so the button that woke it cannot act on the way up.
        if (trayHidden) {
            if (input !is PodiumInput.Release) {
                trayHidden = false
                lastWheelAt = System.nanoTime()
                touched()
                showVolume = false
                haptics.toggle(on = true)
            }
            return@InputTargetEffect true
        }
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
                            if ((steps > 0 && before >= volumeState.max) || (steps < 0 && before <= 0)) {
                                haptics.boundary()
                            } else {
                                // Firmer as the music gets louder.
                                haptics.step(level = (before + steps).coerceIn(0, volumeState.max).toFloat() / volumeState.max.coerceAtLeast(1))
                            }
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
                            // The action's own feel (on, off, into a screen); a confirm if it has none.
                            val mark = haptics.mark()
                            perform(Action.entries[actionFocus])
                            if (!haptics.askedSince(mark)) haptics.confirm()
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
        sleepNote = sleepNote,
        queueOrder = queue.entries.map { it.uid },
        vinyl = vinyl,
        pulse = pulse,
        trayHidden = trayHidden,
        touched = touched,
        neighbourArtwork = listOfNotNull(
            queue.entries.getOrNull(queue.currentIndex + 1)?.artworkUri,
            queue.entries.getOrNull(queue.currentIndex - 1)?.artworkUri,
        ).distinct(),
    )
    // The controls' glass refracts the cover's light, so that light is the captured layer. On Glass
    // the cover's colours flow behind Now Playing while the music plays (D-103); Carbon's flow is the
    // display's own atmosphere, behind every screen.
    GlassHost(
        modifier = Modifier.fillMaxSize(),
        content = {
            if (PodiumTheme.colors.style == DisplayStyle.GLASS) {
                val art = rememberArtworkState(item.artworkUri, 96.dp)
                val cover = rememberCoverAnalysis(art.bitmap, art.settled)
                AlbumAtmosphere(
                    cover?.glassField(PodiumTheme.colors.isDark),
                    level = { 1f },
                    Modifier.fillMaxSize(),
                    moving = { snapshot.intent == PlayIntent.PLAY },
                )
            }
        },
        functional = {
            BoxWithConstraints(Modifier.fillMaxSize().padding(top = insets.top, bottom = insets.bottom)) {
                CompositionLocalProvider(LocalWindowWidth provides maxWidth) {
                // Portrait only (D-108), so the display is always taller than it is wide; a short one
                // still moves the cover beside the title, where a stacked cover would stop meaning much.
                when {
                    maxHeight - StackedControlsHeight < maxWidth * 0.45f -> CompactLayout(parts)
                    else -> PortraitLayout(parts)
                }
                }
            }
        },
    )
}

internal class NowPlayingParts(
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
    /** When the sleep timer stops the music, while it's set (D-75). */
    val sleepNote: String? = null,
    /** The queue's entries in order, to tell Previous from a new song (D-78). */
    val queueOrder: List<QueueUid> = emptyList(),
    /** The covers of the songs either side of this one, loaded ahead so they slide in showing (D-78). */
    val neighbourArtwork: List<String> = emptyList(),
    /** What the vinyl shares with the cover: where it is, and whether the vinyl holds it (D-104). */
    val vinyl: VinylState = VinylState(),
    /** How the music sounds, for the orb to dance to (D-107) and the vinyl's visualiser (D-104). */
    val pulse: AudioPulse = AudioPulse.None,
    /** The tray has slid away and the Wheel is waiting to be woken (D-108). */
    val trayHidden: Boolean = false,
    /** Something on the display was touched: the tray's clock starts again (D-108). */
    val touched: () -> Unit = {},
)

/** Tall screens: the same stacked composition in every theme (D-103). */
@Composable
private fun PortraitLayout(p: NowPlayingParts) = StackedPortrait(p)

/** Short screens: the cover sits beside the title, like the original's Now Playing. */
@Composable
private fun CompactLayout(p: NowPlayingParts) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = Spacing.edge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(Modifier.fillMaxWidth().weight(1f, fill = false), verticalAlignment = Alignment.CenterVertically) {
            val rowWidth = LocalWindowWidth.current
            BoxWithConstraints(Modifier.weight(0.42f).aspectRatio(1f).zIndex(1f), contentAlignment = Alignment.Center) {
                ArtworkStage(p, maxWidth, maxHeight, artTravel(rowWidth, Spacing.edge))
            }
            Spacer(Modifier.width(Spacing.m))
            Box(Modifier.weight(0.58f)) { TrackText(p.item, Alignment.Start, TextAlign.Start) }
        }
        Spacer(Modifier.height(Spacing.s))
        NowPlayingProgress(p)
        StatusSlot(p)
        NowPlayingTransport(p, compact = true)
        Spacer(Modifier.height(Spacing.s))
        NowPlayingActions(p, compact = true)
    }
}

/**
 * How long Now Playing goes untouched before the tray slides away (D-108). Longer than the Wheel's own
 * timeouts (the action row's is 6 s), so the tray never leaves while a mode is still timing out.
 */
private const val TrayIdleMillis = 7_000L

/** What the stacked layout needs below the cover (text, progress, status, transport, actions). */
private val StackedControlsHeight = 250.dp

/** The cover on show: one per song in the queue, so every new song slides, even with the same cover (D-78). */
private data class ArtKey(val uid: QueueUid, val uri: String?, val fallback: String, val index: Int)

/**
 * Which way a new song's cover moves (D-78): back (in from the left) only for Previous, when the
 * new song is the one just before [oldUid] in the same queue. Anything else is a new song: a skip,
 * the song ending, a song chosen from a list or a search. Those come in from the right.
 */
internal fun songChangeIsBack(oldUid: QueueUid, newIndex: Int, queueOrder: List<QueueUid>): Boolean =
    newIndex >= 0 && queueOrder.getOrNull(newIndex + 1) == oldUid

/**
 * How far a cover travels when the song changes, in pixels: in from beyond the display's right
 * edge ([enterFromPx], measured from the cover's place) and out past its left edge ([exitToPx],
 * the cover's own width and the margin before it). Previous runs the other way.
 */
@Immutable
internal data class ArtTravel(val enterFromPx: Int, val exitToPx: (fullWidth: Int) -> Int)

/** The width Now Playing lays out in: a cover leaving or arriving crosses all of it. */
private val LocalWindowWidth = staticCompositionLocalOf { 0.dp }

@Composable
internal fun artTravel(rowWidth: Dp, startInset: Dp): ArtTravel {
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

/** A skip moves the cover on the SPATIAL curve (D-65): it leaves on the press. */
private const val ART_TRAVEL_MS = 360

/** Entering Now Playing, the cover arrives a little smaller and settles (D-65). */
private const val ART_ARRIVAL_SCALE = 0.9f


/**
 * The artwork, as large as the box allows in its own proportions. It "breathes" with playback
 * (slightly smaller while paused). Every new song moves the cover on (D-78): the one playing slides
 * off the display to the left as the next one comes in from the right, leaning as it always does;
 * Previous runs the other way. The covers either side are loaded ahead, so the one coming in is
 * already showing.
 */
@Composable
internal fun ArtworkStage(
    p: NowPlayingParts,
    maxWidth: Dp,
    maxHeight: Dp,
    travel: ArtTravel? = null,
    /** Where the cover breathes from: its corner on the gutter, where it lines up with the text (D-103). */
    origin: TransformOrigin = TransformOrigin.Center,
) {
    val motion = PodiumTheme.motion
    val breathe by animateFloatAsState(
        if (p.playing || motion.reduced) 1f else 0.93f,
        spring(dampingRatio = 0.8f, stiffness = 240f),
        label = "breathe",
    )
    val key = ArtKey(p.item.uid, p.item.artworkUri, p.item.albumTitle ?: p.item.title, p.item.indexInQueue)
    // The neighbours' covers, at the size this stage shows them (the loader keeps them in memory).
    val loader = LocalArtworkLoader.current
    val bucket = artworkBucketPx(with(LocalDensity.current) { maxOf(maxWidth, maxHeight).roundToPx() })
    LaunchedEffect(p.neighbourArtwork, bucket) {
        p.neighbourArtwork.forEach { uri -> runCatching { loader.load(uri, bucket) } }
    }
    val queueOrder = p.queueOrder
    // DRAMATIC (D-65): entering Now Playing, the cover is the anchor — it settles into its place on
    // its own spring as the screen arrives, so the move reads "I chose this and went in".
    // Read only while drawing; nothing waits for it.
    val arrival = LocalColumnTransition.current
    val unsettled = if (arrival != null && !motion.reduced) {
        arrival.transition.animateFloat(
            transitionSpec = { if (targetState == EnterExitState.Visible) PodiumMotion.dramatic() else tween(160, easing = PodiumMotion.Standard) },
            label = "artArrival",
        ) { state -> if (state == EnterExitState.Visible) 0f else 1f }
    } else null
    AnimatedContent(
        targetState = key,
        contentKey = { it.uid },
        transitionSpec = {
            val dir = if (songChangeIsBack(initialState.uid, targetState.index, queueOrder)) -1 else 1
            if (motion.reduced) {
                fadeIn(tween(160)) togetherWith fadeOut(tween(160))
            } else if (travel != null) {
                // Next: the cover slides off the display to the left and the new one comes in from
                // the right, settling at the left. Previous: the mirror.
                val move = tween<IntOffset>(ART_TRAVEL_MS, easing = PodiumMotion.Spatial)
                slideInHorizontally(move) { full -> if (dir > 0) travel.enterFromPx else -travel.exitToPx(full) } togetherWith
                    slideOutHorizontally(move) { full -> if (dir > 0) -travel.exitToPx(full) else travel.enterFromPx }
            } else {
                (fadeIn(tween(200, easing = PodiumMotion.Standard)) +
                    scaleIn(spring(dampingRatio = 0.86f, stiffness = 320f), initialScale = 0.93f) +
                    slideInHorizontally(spring(dampingRatio = 0.9f, stiffness = 320f)) { dir * it / 9 }) togetherWith
                    (fadeOut(tween(180)) +
                        scaleOut(tween(240), targetScale = 0.95f) +
                        slideOutHorizontally(tween(240)) { -dir * it / 9 })
            }.using(SizeTransform(clip = false))
        },
        modifier = Modifier
            // Where the vinyl's sleeve lifts from and comes back to (D-104); hidden while it holds the cover.
            .onGloballyPositioned { p.vinyl.coverBounds = it.boundsInRoot() }
            .graphicsLayer {
                val arriving = 1f - (1f - ART_ARRIVAL_SCALE) * (unsettled?.value ?: 0f)
                transformOrigin = origin
                scaleX = breathe * arriving
                scaleY = breathe * arriving
                alpha = if (p.vinyl.holdsCover) 0f else 1f
            },
        contentAlignment = Alignment.Center,
        label = "artwork",
    ) { art ->
        FittedArtwork(
            uri = art.uri,
            fallbackText = art.fallback,
            maxWidth = maxWidth,
            maxHeight = maxHeight,
            modifier = Modifier
                .semantics { contentDescription = "Artwork" },
        )
    }
}

/** Title (strongest), artist, album. Long titles scroll slowly; the rest ellipsize. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TrackText(item: NowPlayingItem, alignment: Alignment.Horizontal, textAlign: TextAlign) {
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
            // Artist and album read as one quieter pair under the title (D-103).
            PodiumText(shown.artistDisplay, type.nowPlayingSubtitle, colors.labelSecondary, textAlign = textAlign)
            shown.albumTitle?.takeIf { it.isNotBlank() }?.let {
                PodiumText(it, type.nowPlayingSubtitle.copy(fontWeight = FontWeight(500)), colors.labelSecondary, textAlign = textAlign)
            }
            videoNote(shown.kind)?.let {
                PodiumText(it, type.footnote, colors.labelTertiary, textAlign = textAlign)
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
internal fun StatusColumn(p: NowPlayingParts, modifier: Modifier = Modifier) {
    val colors = PodiumTheme.colors
    val type = PodiumTheme.type
    val (note, problem) = statusNote(p, includeEnvironment = false)
    val quiet = colors.labelSecondary
    Column(modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        if (!p.queueHidden) PodiumText("${p.item.indexInQueue + 1} of ${p.item.queueSize}", type.caption, colors.labelSecondary)
        QualityLabel.forNowPlaying(p.snapshot.quality)?.let { QualityChip(it) }
        p.environment?.let { PodiumText(it, type.caption, quiet) }
        p.sleepNote?.let { PodiumText(it, type.caption, colors.labelSecondary) }
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
internal fun LoadingBar() {
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
    val quiet = if (colors.isTonal) colors.labelSecondary else colors.labelTertiary
    Column(Modifier.fillMaxWidth().height(maxOf(34.dp, lineHeight * 2 + 4.dp)), verticalArrangement = Arrangement.Center) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            PodiumText(if (p.queueHidden) "" else "${p.item.indexInQueue + 1} of ${p.item.queueSize}", type.caption, quiet)
            quality?.let { PodiumText(it, type.caption, quiet) }
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

/** Glyph, label and on/off state for an action. */
internal fun actionLook(p: NowPlayingParts, action: Action): Triple<PodiumSymbol, String, Boolean?> = when (action) {
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
    Action.Vinyl -> Triple(PodiumSymbol.Album, "Vinyl", null)
    Action.Queue -> Triple(PodiumSymbol.Queue, "Up Next", null)
    Action.More -> Triple(PodiumSymbol.More, "More", null)
}

/** A glyph button for the screen: generous touch target, press feedback, TalkBack state. */
@Composable
internal fun ControlButton(
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
            .pressFeedback(0.88f)
            .clickable(interactionSource = interaction, indication = null) {
                // The action's own feel when it has one (on, off, into a screen); else a press.
                val mark = haptics.mark()
                onClick()
                if (!haptics.askedSince(mark)) haptics.press()
            }
            .semantics {
                role = Role.Button
                contentDescription = label
                if (toggled != null) stateDescription = if (toggled) "On" else "Off"
            },
        contentAlignment = Alignment.Center,
    ) {
        Symbol(symbol, tint, size = iconSize, weight = 700, filled = filled)
    }
}

private fun RepeatMode.next() = when (this) {
    RepeatMode.OFF -> RepeatMode.ALL
    RepeatMode.ALL -> RepeatMode.ONE
    RepeatMode.ONE -> RepeatMode.OFF
}

/** What another app's playback means right now, and what to do about it (§8.4). */
/**
 * The sleep timer's choices (D-75, audio-architecture.md §9): a countdown, or the end of the song or
 * album. Set, the title says what's set and Turn off comes first.
 */
fun sleepTimerMenu(timer: SleepTimer): MenuSpec {
    val state = timer.state.value
    val title = if (state == SleepTimerState.Off) "Sleep timer" else {
        "Sleep timer, " + SleepTimerText.summary(state, timer.remainingMs()).replaceFirstChar { it.lowercase() }
    }
    return MenuSpec(
        title,
        buildList {
            if (state != SleepTimerState.Off) add(MenuAction("Turn off") { timer.cancel() })
            SleepTimer.CHOICES.forEach { minutes -> add(MenuAction(SleepTimerText.choice(minutes)) { timer.start(minutes) }) }
            add(MenuAction("End of song") { timer.stopAtEndOfSong() })
            add(MenuAction("End of album") { timer.stopAtEndOfAlbum() })
        },
    )
}

/**
 * What Now Playing says when the sound comes from a video rather than a song's audio release
 * (D-74), or from a live station (D-90).
 */
internal fun videoNote(kind: MediaKind): String? = when (kind) {
    MediaKind.MUSIC_VIDEO -> "Audio from the music video"
    MediaKind.VIDEO -> "Audio from a video"
    MediaKind.LIVE -> "Live radio"
    MediaKind.SONG, MediaKind.EPISODE -> null
}

/**
 * Go to album, and Go to each of the song's artists by name, in the menu itself (D-74, D-79): only
 * the pages the song has.
 */
internal fun goToActions(
    track: Track?,
    onOpenAlbum: (Track) -> Unit,
    onOpenArtist: (ArtistCredit) -> Unit,
): List<MenuAction> {
    if (track == null) return emptyList()
    val artists = track.artists.filter { it.id != null }.distinctBy { it.id }
    return buildList {
        if (track.album?.id != null) add(MenuAction("Go to album") { onOpenAlbum(track) })
        artists.forEach { a -> add(MenuAction("Go to ${a.name}") { onOpenArtist(a) }) }
    }
}

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
    is PodiumError.RateLimited, is PodiumError.Server -> "The music source isn't answering right now. Skipping to the next song."
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
    is PodiumError.RateLimited -> "The music source is busy. Try again in a few minutes."
    is PodiumError.Server -> "The music source isn't answering right now. Try again in a moment."
    else -> "Something went wrong playing this song."
}
