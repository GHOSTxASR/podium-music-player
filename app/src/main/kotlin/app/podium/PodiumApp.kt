package app.podium

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.ui.NavDisplay
import app.podium.core.designsystem.artwork.BackgroundExtension
import app.podium.core.designsystem.artwork.LocalArtworkLoader
import app.podium.core.designsystem.artwork.rememberArtwork
import app.podium.core.designsystem.component.GlassMenu
import app.podium.core.designsystem.component.LocalOverlayHost
import app.podium.core.designsystem.component.LocalPaperDecor
import app.podium.core.designsystem.component.LocalPaperPeek
import app.podium.core.designsystem.component.LocalScreenInsets
import app.podium.core.designsystem.component.MiniPlayer
import app.podium.core.designsystem.component.OverlayHost
import app.podium.core.designsystem.component.PaperPeekArtwork
import app.podium.core.designsystem.component.PaperPeekLabels
import app.podium.core.designsystem.component.PodWheel
import app.podium.core.designsystem.component.ScreenInsets
import app.podium.core.designsystem.glass.GlassHost
import app.podium.core.designsystem.glass.scrollEdgeFade
import app.podium.core.designsystem.shell.BootScreen
import app.podium.core.designsystem.shell.DeviceBody
import app.podium.core.designsystem.shell.DeviceLayout
import app.podium.core.designsystem.shell.PowerButton
import app.podium.core.designsystem.shell.ScreenHeader
import app.podium.core.designsystem.shell.ScreenHeaderHeight
import app.podium.core.designsystem.shell.ShellPalette
import app.podium.core.designsystem.shell.palette
import app.podium.core.designsystem.theme.Atmosphere
import app.podium.core.designsystem.theme.AtmosphereBackground
import app.podium.core.designsystem.theme.PodiumMotion
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.designsystem.theme.Spacing
import app.podium.core.interaction.InputRouter
import app.podium.core.interaction.LocalInputRouter
import app.podium.core.interaction.PodiumHaptics
import app.podium.core.interaction.PodiumInput
import app.podium.core.interaction.WheelButton
import app.podium.core.interaction.rememberPodiumHaptics
import app.podium.core.model.AlbumId
import app.podium.core.model.ArtistId
import app.podium.core.model.Track
import app.podium.core.model.TrackId
import app.podium.feature.library.AlbumScreen
import app.podium.feature.library.AlbumsScreen
import app.podium.feature.library.ArtistScreen
import app.podium.feature.library.ArtistsScreen
import app.podium.feature.library.CoverFlowScreen
import app.podium.feature.library.FavoritesScreen
import app.podium.feature.library.HomeScreen
import app.podium.feature.library.LibraryIndex
import app.podium.feature.library.LibraryState
import app.podium.feature.library.MusicScreen
import app.podium.feature.library.SongsScreen
import app.podium.feature.nowplaying.NowPlayingScreen
import app.podium.feature.nowplaying.UpNextScreen
import app.podium.feature.settings.CustomColorScreen
import app.podium.feature.settings.FinishScreen
import app.podium.feature.settings.GrainScreen
import app.podium.feature.settings.SettingsScreen
import app.podium.feature.settings.ThemeScreen
import app.podium.player.api.PlayIntent
import app.podium.player.api.PlaybackController
import app.podium.player.api.PlaybackSnapshot
import app.podium.sources.api.CapabilityAction
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Places on the paper (D-29): the hierarchy inside the screen. Album and Artist carry their ids,
 * so they survive process death through [BackStackSaver] like everything else.
 */
sealed interface Dest {
    data object Home : Dest
    data object Music : Dest
    data object CoverFlow : Dest
    data object Albums : Dest
    data object Artists : Dest
    data object Songs : Dest
    data object Favorites : Dest
    data object NowPlaying : Dest
    data object UpNext : Dest
    data object Settings : Dest
    data object Theme : Dest
    data object Finish : Dest
    data object Grain : Dest
    data object CustomColor : Dest
    data class Album(val id: AlbumId) : Dest
    data class Artist(val id: ArtistId) : Dest
}

private val fixedDests = listOf(
    Dest.Home, Dest.Music, Dest.CoverFlow, Dest.Albums, Dest.Artists, Dest.Songs, Dest.Favorites, Dest.NowPlaying,
    Dest.UpNext, Dest.Settings, Dest.Theme, Dest.Finish, Dest.Grain, Dest.CustomColor,
).associateBy { it.toString() }

private fun Dest.encode(): String = when (this) {
    is Dest.Album -> "Album:${id.value}"
    is Dest.Artist -> "Artist:${id.value}"
    else -> toString()
}

private fun decodeDest(text: String): Dest? = when {
    text.startsWith("Album:") -> Dest.Album(AlbumId(text.removePrefix("Album:")))
    text.startsWith("Artist:") -> Dest.Artist(ArtistId(text.removePrefix("Artist:")))
    else -> fixedDests[text]
}

private val BackStackSaver = listSaver<SnapshotStateList<Dest>, String>(
    save = { stack -> stack.map { it.encode() } },
    restore = { names -> mutableStateListOf(*names.mapNotNull(::decodeDest).ifEmpty { listOf(Dest.Home) }.toTypedArray()) },
)

/** The header title for a place on the paper. */
@Composable
private fun titleOf(dest: Dest, graph: AppGraph): String = when (dest) {
    Dest.Home -> "Podium"
    Dest.Music -> "Music"
    Dest.CoverFlow -> "Cover Flow"
    Dest.Albums -> "Albums"
    Dest.Artists -> "Artists"
    Dest.Songs -> "Songs"
    Dest.Favorites -> "Favorites"
    Dest.NowPlaying -> "Now Playing"
    Dest.UpNext -> "Up Next"
    Dest.Settings -> "Settings"
    Dest.Theme -> "Theme"
    Dest.Finish -> "Finish"
    Dest.Grain -> "Grain"
    Dest.CustomColor -> "Custom color"
    is Dest.Album, is Dest.Artist -> {
        val songs by graph.library.songs.collectAsStateWithLifecycle()
        val tracks = (songs as? LibraryState.Ready)?.tracks.orEmpty()
        remember(tracks, dest) {
            when (dest) {
                is Dest.Album -> LibraryIndex.albums(tracks).firstOrNull { it.id == dest.id }?.title ?: "Album"
                is Dest.Artist -> LibraryIndex.artists(tracks).firstOrNull { it.id == dest.id }?.name ?: "Artist"
                else -> ""
            }
        }
    }
}

/**
 * The device (D-26). The window is the device's body in the chosen finish; a recessed virtual
 * screen above the Wheel holds the whole interface; a small power button sits beside the Wheel.
 * All input reaches screens through the [InputRouter]; anything a screen doesn't consume falls
 * through to the global handlers here.
 */
@Composable
fun PodiumApp(graph: AppGraph, onSourceAction: (CapabilityAction) -> Unit) {
    val appearance by graph.deviceSettings.appearance.collectAsStateWithLifecycle()
    val preview by graph.deviceSettings.preview.collectAsStateWithLifecycle()
    val effective = preview ?: appearance
    PodiumTheme(displayTheme = effective.display) {
        val controller = graph.playbackController
        val snapshot by controller.snapshot.collectAsStateWithLifecycle()
        val power by graph.power.collectAsStateWithLifecycle()
        val colors = PodiumTheme.colors
        val palette = effective.palette(colors.isDark)

        val backStack = rememberSaveable(saver = BackStackSaver) { mutableStateListOf<Dest>(Dest.Home) }
        val navigator = remember(backStack) { Navigator(backStack) }
        val router = remember { InputRouter() }
        val overlay = remember { OverlayHost() }
        val haptics = rememberPodiumHaptics()
        val scope = rememberCoroutineScope()

        SystemBarIcons(darkIcons = palette.isLight)
        GlobalInput(router, navigator, haptics, controller, graph) { direction ->
            scope.launch {
                // Hold ⏮/⏭ to scan: 5 s steps, 5 per second (interaction-model.md §3).
                while (isActive) {
                    controller.seekTo((controller.positionMs() + direction * 5_000L).coerceAtLeast(0))
                    delay(200)
                }
            }
        }

        val nowPlayingArt = rememberArtwork(snapshot.item?.artworkUri, 96.dp)
        val atmosphere = remember(nowPlayingArt, colors.isDark) {
            nowPlayingArt?.let { Atmosphere.fromArtwork(it.asAndroidBitmap(), colors.isDark) } ?: Atmosphere.neutral(colors)
        }

        CompositionLocalProvider(
            LocalInputRouter provides router,
            LocalOverlayHost provides overlay,
            LocalArtworkLoader provides graph.artworkLoader,
        ) {
            // One structure for every finish, so trying finishes on never rebuilds the screen.
            GlassHost(
                modifier = Modifier.fillMaxSize(),
                content = { Body(palette, atmosphere, nowPlayingArt) },
                functional = {
                    DeviceLayout(
                        modifier = Modifier.fillMaxSize(),
                        screen = {
                            AnimatedContent(
                                targetState = power,
                                transitionSpec = { powerTransition(initialState, targetState) },
                                label = "power",
                            ) { state ->
                                when (state) {
                                    Power.OFF -> Box(Modifier.fillMaxSize().background(Color.Black).semantics { contentDescription = "Display off" })
                                    Power.BOOTING -> BootScreen(onStart = graph::onBootStarted, onFinished = graph::onBootFinished)
                                    Power.ON -> ScreenOs(graph, snapshot, backStack, navigator, overlay, atmosphere, onSourceAction)
                                }
                            }
                        },
                        wheel = { diameter ->
                            PodWheel(
                                onInput = { router.dispatch(it) },
                                diameter = diameter,
                                isPlaying = snapshot.intent == PlayIntent.PLAY,
                                palette = palette,
                            )
                        },
                        powerButton = {
                            PowerButton(on = power != Power.OFF, palette = palette, onToggle = graph::togglePower)
                        },
                    )
                },
            )
        }
    }
}

/**
 * The display's power changes (D-26). Switching off dims the panel first, holds a beat, then goes
 * black — inside the screen only; the body and the Wheel stay as they are. Waking lights the black
 * panel straight into the boot screen.
 */
private fun AnimatedContentTransitionScope<Power>.powerTransition(from: Power, to: Power): ContentTransform = when {
    to == Power.OFF -> fadeIn(
        keyframes {
            durationMillis = 520
            0f at 0
            0.55f at 150
            0.55f at 290
            1f at 520
        },
    ) togetherWith fadeOut(tween(durationMillis = 1, delayMillis = 520))
    from == Power.OFF -> fadeIn(tween(180)) togetherWith fadeOut(tween(1, delayMillis = 180))
    else -> fadeIn(tween(320)) togetherWith fadeOut(tween(320))
}

/** The device body: the Glass finish shows the artwork's atmosphere for the Wheel to refract. */
@Composable
private fun BoxScope.Body(palette: ShellPalette, atmosphere: Atmosphere, art: ImageBitmap?) {
    if (palette.isGlass) {
        AtmosphereBackground(atmosphere, Modifier.fillMaxSize())
        BackgroundExtension(art, Modifier.fillMaxSize())
    } else {
        DeviceBody(palette, Modifier.fillMaxSize())
    }
}

/** Everything shown on the virtual screen while the device is on. */
@Composable
private fun ScreenOs(
    graph: AppGraph,
    snapshot: PlaybackSnapshot,
    backStack: SnapshotStateList<Dest>,
    navigator: Navigator,
    overlay: OverlayHost,
    atmosphere: Atmosphere,
    onSourceAction: (CapabilityAction) -> Unit,
) {
    val controller = graph.playbackController
    val motion = PodiumTheme.motion
    val colors = PodiumTheme.colors
    val top = backStack.last()
    val miniPlayerVisible = snapshot.isActive && top.showsMiniPlayer()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val miniBlock = if (miniPlayerVisible) Spacing.miniPlayer + Spacing.s * 2 else Spacing.s
        val insets = ScreenInsets(top = ScreenHeaderHeight, bottom = miniBlock)
        val density = LocalDensity.current
        val screenHeight = maxHeight

        GlassHost(
            modifier = Modifier.fillMaxSize(),
            content = {
                // Glass tints the display with the artwork's atmosphere; Carbon and Bone stay matte.
                if (!colors.isIndustrial) AtmosphereBackground(atmosphere, Modifier.fillMaxSize())
                CompositionLocalProvider(LocalScreenInsets provides insets) {
                    NavDisplay(
                        backStack = backStack,
                        modifier = Modifier.fillMaxSize().scrollEdgeFade(
                            topFadePx = with(density) { insets.top.toPx() },
                            bottomStartPx = with(density) { (screenHeight - insets.bottom.coerceAtLeast(24.dp)).toPx() },
                        ),
                        onBack = { navigator.pop() },
                        transitionSpec = { forward(motion) },
                        popTransitionSpec = { backward(motion) },
                        predictivePopTransitionSpec = { backward(motion) },
                        entryProvider = { key ->
                            // Glass lets Now Playing rise from the mini player; Carbon and Bone keep the whole
                            // hierarchy on one horizontal sheet of paper (D-29).
                            val rise = key == Dest.NowPlaying && !colors.isIndustrial
                            NavEntry(key, metadata = if (rise) riseFromMiniPlayer(motion) else emptyMap()) { screen ->
                                // Each screen sees the column before it on the paper peeking in at its left.
                                val position = backStack.indexOf(screen)
                                val previous = if (position > 0) backStack[position - 1] else null
                                val peek: (@Composable (Modifier) -> Unit)? = previous?.let { prev -> { m -> PreviousColumn(prev, screen, graph, m) } }
                                val scope = LocalNavAnimatedContentScope.current
                                val decor: @Composable () -> Modifier = {
                                    with(scope) {
                                        Modifier.animateEnterExit(
                                            enter = fadeIn(tween(220, delayMillis = if (motion.reduced) 0 else 260)),
                                            exit = fadeOut(tween(80)),
                                        )
                                    }
                                }
                                CompositionLocalProvider(LocalPaperPeek provides peek, LocalPaperDecor provides decor) {
                                    ScreenContent(screen, graph, navigator, onSourceAction)
                                }
                            }
                        },
                    )
                }
            },
            functional = {
                ScreenHeader(
                    title = titleOf(top, graph),
                    depth = backStack.size,
                    canGoBack = backStack.size > 1,
                    onBack = { navigator.pop() },
                    playing = if (snapshot.isActive) snapshot.intent == PlayIntent.PLAY else null,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
                AnimatedVisibility(
                    visible = miniPlayerVisible,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = fadeIn(motion.fadeStandard()) + slideInVertically(motion.navigateOffset()) { it / 2 },
                    exit = fadeOut(motion.fadeFast()) + slideOutVertically(motion.navigateOffset()) { it / 2 },
                ) {
                    val item = snapshot.item
                    if (item != null) {
                        MiniPlayer(
                            title = item.title,
                            subtitle = item.artistDisplay,
                            artworkUri = item.artworkUri,
                            isPlaying = snapshot.intent == PlayIntent.PLAY,
                            progress = {
                                val duration = item.durationMs ?: 0L
                                if (duration <= 0) 0f else (controller.positionMs().toFloat() / duration).coerceIn(0f, 1f)
                            },
                            onOpen = navigator::showNowPlaying,
                            onPlayPause = controller::togglePlayPause,
                            onNext = controller::next,
                            modifier = Modifier.padding(Spacing.s).fillMaxWidth(),
                        )
                    }
                }
                overlay.menu?.let { spec ->
                    GlassMenu(spec, onDismiss = overlay::dismiss, panelPadding = PaddingValues(top = ScreenHeaderHeight))
                    BackHandler { overlay.dismiss() }
                }
            },
        )
    }
}

/** The column before [current] on the paper, as a peek: its labels around the one that led here. */
@Composable
private fun PreviousColumn(previous: Dest, current: Dest, graph: AppGraph, modifier: Modifier) {
    val songs by graph.library.songs.collectAsStateWithLifecycle()
    val snapshot by graph.playbackController.snapshot.collectAsStateWithLifecycle()
    val favoriteIds by graph.favorites.favorites.collectAsStateWithLifecycle()
    val tracks = (songs as? LibraryState.Ready)?.tracks.orEmpty()
    if (previous == Dest.NowPlaying) {
        PaperPeekArtwork(snapshot.item?.artworkUri, modifier)
        return
    }
    val currentTitle = titleOf(current, graph)
    val chosen = if (current == Dest.NowPlaying) snapshot.item?.title.orEmpty() else currentTitle
    val labels = remember(previous, tracks, snapshot.isActive, favoriteIds) { peekLabels(previous, tracks, snapshot.isActive, favoriteIds) }
    PaperPeekLabels(labels, labels.indexOfFirst { it.equals(chosen, ignoreCase = true) }.coerceAtLeast(0), modifier)
}

private fun peekLabels(dest: Dest, tracks: List<Track>, nowPlaying: Boolean, favorites: Set<TrackId>): List<String> = when (dest) {
    Dest.Home -> listOfNotNull("Music", "Shuffle songs", if (nowPlaying) "Now Playing" else null, "Settings")
    Dest.Music -> listOf("Cover Flow", "Albums", "Artists", "Songs", "Favorites")
    Dest.Settings -> listOf("Theme", "Finish", "Custom color", "Grain", "Startup sound")
    Dest.Albums, Dest.CoverFlow -> LibraryIndex.albums(tracks).map { it.title }
    Dest.Artists -> LibraryIndex.artists(tracks).map { it.name }
    Dest.Songs -> tracks.map { it.title }
    Dest.Favorites -> tracks.filter { it.id in favorites }.map { it.title }
    is Dest.Album -> LibraryIndex.albums(tracks).firstOrNull { it.id == dest.id }?.tracks?.map { it.title }.orEmpty()
    is Dest.Artist -> listOf("All songs") + LibraryIndex.artists(tracks).firstOrNull { it.id == dest.id }?.albums?.map { it.title }.orEmpty()
    else -> emptyList()
}

private val MiniPlayerScreens = setOf(Dest.Home, Dest.Music, Dest.Albums, Dest.Artists, Dest.Songs, Dest.Favorites)

private fun Dest.showsMiniPlayer() = this in MiniPlayerScreens || this is Dest.Album || this is Dest.Artist

@Composable
private fun ScreenContent(
    screen: Dest,
    graph: AppGraph,
    navigator: Navigator,
    onSourceAction: (CapabilityAction) -> Unit,
) {
    val controller = graph.playbackController
    val settings = graph.deviceSettings
    val play: (List<Track>, Int, String) -> Unit = { tracks, index, label ->
        controller.playContext(tracks.map { it.id }, index, label)
        navigator.showNowPlaying()
    }
    val playNext: (Track) -> Unit = { controller.playNext(listOf(it.id)) }
    val addToQueue: (Track) -> Unit = { controller.addToQueue(listOf(it.id)) }
    when (screen) {
        Dest.Home -> {
            val snapshot by controller.snapshot.collectAsStateWithLifecycle()
            HomeScreen(
                repository = graph.library,
                nowPlayingArtwork = snapshot.item?.artworkUri,
                nowPlayingActive = snapshot.isActive,
                onMusic = { navigator.push(Dest.Music) },
                onShuffleSongs = {
                    val tracks = (graph.library.songs.value as? LibraryState.Ready)?.tracks.orEmpty()
                    if (tracks.isNotEmpty()) {
                        controller.playContext(tracks.map { it.id }, 0, "Songs", shuffle = true)
                        navigator.showNowPlaying()
                    }
                },
                onNowPlaying = navigator::showNowPlaying,
                onSettings = { navigator.push(Dest.Settings) },
            )
        }
        Dest.Music -> MusicScreen(
            repository = graph.library,
            favorites = graph.favorites,
            onCoverFlow = { navigator.push(Dest.CoverFlow) },
            onAlbums = { navigator.push(Dest.Albums) },
            onArtists = { navigator.push(Dest.Artists) },
            onSongs = { navigator.push(Dest.Songs) },
            onFavorites = { navigator.push(Dest.Favorites) },
            onSourceAction = onSourceAction,
        )
        Dest.CoverFlow -> CoverFlowScreen(graph.library, onOpen = { navigator.push(Dest.Album(it)) })
        Dest.Albums -> AlbumsScreen(graph.library, onOpen = { navigator.push(Dest.Album(it)) })
        Dest.Artists -> ArtistsScreen(graph.library, onOpen = { navigator.push(Dest.Artist(it)) })
        is Dest.Album -> AlbumScreen(graph.library, screen.id, onPlay = play, onPlayNext = playNext, onAddToQueue = addToQueue)
        is Dest.Artist -> ArtistScreen(graph.library, screen.id, onOpenAlbum = { navigator.push(Dest.Album(it)) }, onPlay = play)
        Dest.Favorites -> FavoritesScreen(graph.library, graph.favorites, onPlay = play, onPlayNext = playNext, onAddToQueue = addToQueue)
        Dest.Songs -> SongsScreen(
            repository = graph.library,
            onPlay = { tracks, index -> play(tracks, index, "Songs") },
            onPlayNext = playNext,
            onAddToQueue = addToQueue,
        )
        Dest.NowPlaying -> NowPlayingScreen(controller, graph.volume, graph.favorites, onUpNext = { navigator.push(Dest.UpNext) })
        Dest.UpNext -> UpNextScreen(controller)
        Dest.Settings -> SettingsScreen(
            repository = settings,
            onTheme = { navigator.push(Dest.Theme) },
            onFinish = { navigator.push(Dest.Finish) },
            onCustomColor = { navigator.push(Dest.CustomColor) },
            onGrain = { navigator.push(Dest.Grain) },
        )
        Dest.Theme -> ThemeScreen(settings)
        Dest.Finish -> FinishScreen(settings, onCustomColor = { navigator.push(Dest.CustomColor) })
        Dest.Grain -> GrainScreen(settings)
        Dest.CustomColor -> CustomColorScreen(settings)
    }
}

/** The back stack's only writer. */
private class Navigator(private val stack: SnapshotStateList<Dest>) {
    fun push(screen: Dest) {
        if (stack.last() != screen) stack.add(screen)
    }

    /** @return false at the root. */
    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun popToRoot(): Boolean {
        if (stack.size <= 1) return false
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
        return true
    }

    /** Back to [screen] if it's in the stack; otherwise one level. */
    fun popTo(screen: Dest) {
        if (screen !in stack) {
            pop()
            return
        }
        while (stack.last() != screen) stack.removeAt(stack.lastIndex)
    }

    /** Now Playing is a single instance: return to it if it's already in the stack. */
    fun showNowPlaying() {
        val existing = stack.indexOf(Dest.NowPlaying)
        if (existing >= 0) {
            while (stack.lastIndex > existing) stack.removeAt(stack.lastIndex)
        } else {
            stack.add(Dest.NowPlaying)
        }
    }
}

/**
 * Menu = back, long Menu = Home, transport buttons = playback, hold ⏮/⏭ to scan (ADR-011).
 * Switched off, any press wakes the device; while it boots, input is ignored.
 */
@Composable
private fun GlobalInput(
    router: InputRouter,
    navigator: Navigator,
    haptics: PodiumHaptics,
    controller: PlaybackController,
    graph: AppGraph,
    startScan: (direction: Int) -> Job,
) {
    DisposableEffect(router, navigator) {
        var scan: Job? = null
        router.global = handler@{ input ->
            when (graph.power.value) {
                Power.OFF -> {
                    if (input is PodiumInput.Press) graph.wake()
                    return@handler true
                }
                Power.BOOTING -> return@handler true
                Power.ON -> Unit
            }
            when (input) {
                is PodiumInput.Press -> when (input.button) {
                    WheelButton.MENU -> {
                        if (!navigator.pop()) haptics.boundary()
                        true
                    }
                    WheelButton.PLAY_PAUSE -> { controller.togglePlayPause(); true }
                    WheelButton.NEXT -> { controller.next(); true }
                    WheelButton.PREVIOUS -> { controller.previous(); true }
                    WheelButton.CENTER -> false
                }
                is PodiumInput.LongPress -> when (input.button) {
                    // Hold ⏯ to switch off, as on the original.
                    WheelButton.PLAY_PAUSE -> {
                        graph.togglePower()
                        true
                    }
                    WheelButton.MENU -> {
                        if (!navigator.popToRoot()) haptics.boundary()
                        true
                    }
                    WheelButton.NEXT, WheelButton.PREVIOUS -> {
                        scan?.cancel()
                        scan = startScan(if (input.button == WheelButton.NEXT) 1 else -1)
                        true
                    }
                    else -> false
                }
                is PodiumInput.Release -> {
                    scan?.cancel()
                    scan = null
                    true
                }
                is PodiumInput.Rotate -> false
            }
        }
        onDispose {
            scan?.cancel()
            router.global = { false }
        }
    }
}

/** Status- and navigation-bar icons follow the body: dark icons on Silver and Glacier blue. */
@Composable
private fun SystemBarIcons(darkIcons: Boolean) {
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = darkIcons
            isAppearanceLightNavigationBars = darkIcons
        }
    }
}

private fun AnimatedContentTransitionScope<Scene<Dest>>.forward(motion: PodiumMotion): ContentTransform =
    if (motion.reduced) {
        fadeIn(motion.fadeStandard()) togetherWith fadeOut(motion.fadeFast())
    } else {
        // The iPod strip: the next level slides in from the right as the current one leaves left.
        slideInHorizontally(motion.navigateOffset()) { it } togetherWith slideOutHorizontally(motion.navigateOffset()) { -it }
    }

private fun AnimatedContentTransitionScope<Scene<Dest>>.backward(motion: PodiumMotion): ContentTransform =
    if (motion.reduced) {
        fadeIn(motion.fadeStandard()) togetherWith fadeOut(motion.fadeFast())
    } else {
        slideInHorizontally(motion.navigateOffset()) { -it } togetherWith slideOutHorizontally(motion.navigateOffset()) { it }
    }

/**
 * Now Playing rises from where the mini player sits and sinks back into it, so library → mini
 * player → Now Playing reads as one gesture. Its siblings keep the horizontal strip.
 */
private fun riseFromMiniPlayer(motion: PodiumMotion): Map<String, Any> {
    val sink: AnimatedContentTransitionScope<Scene<*>>.() -> ContentTransform = {
        if (motion.reduced) {
            fadeIn(motion.fadeStandard()) togetherWith fadeOut(motion.fadeFast())
        } else {
            (fadeIn(tween(240)) + scaleIn(tween(320), initialScale = 0.97f)) togetherWith
                (slideOutVertically(motion.navigateOffset()) { it } + fadeOut(tween(220, delayMillis = 80)))
        }.apply { targetContentZIndex = -1f }
    }
    return NavDisplay.transitionSpec {
        if (motion.reduced) {
            fadeIn(motion.fadeStandard()) togetherWith fadeOut(motion.fadeFast())
        } else {
            (slideInVertically(motion.navigateOffset()) { it } + fadeIn(tween(200))) togetherWith
                (fadeOut(tween(260)) + scaleOut(tween(320), targetScale = 0.97f))
        }
    } + NavDisplay.popTransitionSpec(sink) + NavDisplay.predictivePopTransitionSpec { sink() }
}
