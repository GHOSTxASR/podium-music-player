package app.podium

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.NavDisplay
import app.podium.core.designsystem.artwork.BackgroundExtension
import app.podium.core.designsystem.artwork.LocalArtworkLoader
import app.podium.core.designsystem.artwork.rememberArtwork
import app.podium.core.designsystem.component.GlassMenu
import app.podium.core.designsystem.component.LocalOverlayHost
import app.podium.core.designsystem.component.LocalScreenInsets
import app.podium.core.designsystem.component.MiniPlayer
import app.podium.core.designsystem.component.OverlayHost
import app.podium.core.designsystem.component.PodWheel
import app.podium.core.designsystem.component.ScreenInsets
import app.podium.core.designsystem.glass.GlassHost
import app.podium.core.designsystem.glass.scrollEdgeFade
import app.podium.core.designsystem.shell.BootScreen
import app.podium.core.designsystem.shell.DeviceBody
import app.podium.core.designsystem.shell.PowerButton
import app.podium.core.designsystem.shell.ScreenHeader
import app.podium.core.designsystem.shell.ScreenHeaderHeight
import app.podium.core.designsystem.shell.ShellPalette
import app.podium.core.designsystem.shell.VirtualScreen
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
import app.podium.core.interaction.WheelContext
import app.podium.core.interaction.rememberPodiumHaptics
import app.podium.feature.library.HomeScreen
import app.podium.feature.library.LibraryState
import app.podium.feature.library.MusicScreen
import app.podium.feature.library.SongsScreen
import app.podium.feature.nowplaying.NowPlayingScreen
import app.podium.feature.nowplaying.UpNextScreen
import app.podium.feature.settings.CustomColorScreen
import app.podium.feature.settings.FinishScreen
import app.podium.feature.settings.GrainScreen
import app.podium.feature.settings.SettingsScreen
import app.podium.player.api.PlayIntent
import app.podium.player.api.PlaybackController
import app.podium.player.api.PlaybackSnapshot
import app.podium.sources.api.CapabilityAction
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The hierarchy inside the screen (vertical-slice-plan.md §2, plus Settings for D-26). */
enum class Screen(val title: String) {
    Home("Podium"),
    Music("Music"),
    Songs("Songs"),
    NowPlaying("Now Playing"),
    UpNext("Up Next"),
    Settings("Settings"),
    Finish("Finish"),
    Grain("Grain"),
    CustomColor("Custom color"),
}

private val BackStackSaver = listSaver<SnapshotStateList<Screen>, String>(
    save = { stack -> stack.map { it.name } },
    restore = { names -> mutableStateListOf(*names.map(Screen::valueOf).toTypedArray()) },
)

/**
 * The device (D-26). The window is the device's body in the chosen finish; a recessed virtual
 * screen above the Wheel holds the whole interface; a small power button sits beside the Wheel.
 * All input reaches screens through the [InputRouter]; anything a screen doesn't consume falls
 * through to the global handlers here.
 */
@Composable
fun PodiumApp(graph: AppGraph, onSourceAction: (CapabilityAction) -> Unit) {
    PodiumTheme {
        val controller = graph.playbackController
        val snapshot by controller.snapshot.collectAsStateWithLifecycle()
        val power by graph.power.collectAsStateWithLifecycle()
        val appearance by graph.deviceSettings.appearance.collectAsStateWithLifecycle()
        val preview by graph.deviceSettings.preview.collectAsStateWithLifecycle()
        val colors = PodiumTheme.colors
        val palette = (preview ?: appearance).palette(colors.isDark)

        val backStack = rememberSaveable(saver = BackStackSaver) { mutableStateListOf(Screen.Home) }
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
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val diameter = shellWheelDiameter(maxWidth, maxHeight)
                // One structure for every finish, so trying finishes on never rebuilds the screen.
                GlassHost(
                    modifier = Modifier.fillMaxSize(),
                    content = { Body(palette, atmosphere, nowPlayingArt) },
                    functional = {
                        Column(
                            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).padding(top = Spacing.s),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            VirtualScreen(Modifier.weight(1f).fillMaxWidth().padding(horizontal = Spacing.m)) {
                                AnimatedContent(
                                    targetState = power,
                                    transitionSpec = { fadeIn(tween(280)) togetherWith fadeOut(tween(280)) },
                                    label = "power",
                                ) { state ->
                                    when (state) {
                                        Power.OFF -> Box(Modifier.fillMaxSize().background(Color.Black))
                                        Power.BOOTING -> BootScreen(onStart = graph::onBootStarted, onFinished = graph::onBootFinished)
                                        Power.ON -> ScreenOs(graph, snapshot, backStack, navigator, overlay, atmosphere, onSourceAction)
                                    }
                                }
                            }
                            Box(Modifier.fillMaxWidth().padding(vertical = WheelGap)) {
                                PodWheel(
                                    onInput = { router.dispatch(it) },
                                    diameter = diameter,
                                    accelerate = router.activeContext == WheelContext.LIST_FOCUS,
                                    isPlaying = snapshot.intent == PlayIntent.PLAY,
                                    palette = palette,
                                    modifier = Modifier.align(Alignment.Center),
                                )
                                PowerButton(
                                    on = power != Power.OFF,
                                    palette = palette,
                                    onToggle = graph::togglePower,
                                    modifier = Modifier.align(Alignment.TopEnd).padding(end = Spacing.s),
                                )
                            }
                        }
                    },
                )
            }
        }
    }
}

private val WheelGap = 18.dp

/** As large a screen as possible: the Wheel takes what it needs and no more. */
private fun shellWheelDiameter(width: Dp, height: Dp): Dp =
    (width * 0.62f).coerceIn(216.dp, 300.dp).coerceAtMost(height * 0.34f)

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
    backStack: SnapshotStateList<Screen>,
    navigator: Navigator,
    overlay: OverlayHost,
    atmosphere: Atmosphere,
    onSourceAction: (CapabilityAction) -> Unit,
) {
    val controller = graph.playbackController
    val motion = PodiumTheme.motion
    val top = backStack.last()
    val miniPlayerVisible = snapshot.isActive && top in MiniPlayerScreens

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val miniBlock = if (miniPlayerVisible) Spacing.miniPlayer + Spacing.s * 2 else Spacing.s
        val insets = ScreenInsets(top = ScreenHeaderHeight, bottom = miniBlock)
        val density = LocalDensity.current
        val screenHeight = maxHeight

        GlassHost(
            modifier = Modifier.fillMaxSize(),
            content = {
                AtmosphereBackground(atmosphere, Modifier.fillMaxSize())
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
                            NavEntry(key) { screen -> ScreenContent(screen, graph, navigator, onSourceAction) }
                        },
                    )
                }
            },
            functional = {
                ScreenHeader(
                    title = top.title,
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

private val MiniPlayerScreens = setOf(Screen.Home, Screen.Music, Screen.Songs)

@Composable
private fun ScreenContent(
    screen: Screen,
    graph: AppGraph,
    navigator: Navigator,
    onSourceAction: (CapabilityAction) -> Unit,
) {
    val controller = graph.playbackController
    val settings = graph.deviceSettings
    when (screen) {
        Screen.Home -> {
            val snapshot by controller.snapshot.collectAsStateWithLifecycle()
            HomeScreen(
                nowPlayingActive = snapshot.isActive,
                onMusic = { navigator.push(Screen.Music) },
                onShuffleSongs = {
                    val tracks = (graph.library.songs.value as? LibraryState.Ready)?.tracks.orEmpty()
                    if (tracks.isNotEmpty()) {
                        controller.playContext(tracks.map { it.id }, 0, "Songs", shuffle = true)
                        navigator.showNowPlaying()
                    }
                },
                onNowPlaying = navigator::showNowPlaying,
                onSettings = { navigator.push(Screen.Settings) },
            )
        }
        Screen.Music -> MusicScreen(
            repository = graph.library,
            onSongs = { navigator.push(Screen.Songs) },
            onSourceAction = onSourceAction,
        )
        Screen.Songs -> SongsScreen(
            repository = graph.library,
            onPlay = { tracks, index ->
                controller.playContext(tracks.map { it.id }, index, "Songs")
                navigator.showNowPlaying()
            },
            onPlayNext = { controller.playNext(listOf(it.id)) },
            onAddToQueue = { controller.addToQueue(listOf(it.id)) },
        )
        Screen.NowPlaying -> NowPlayingScreen(controller, graph.volume, onUpNext = { navigator.push(Screen.UpNext) })
        Screen.UpNext -> UpNextScreen(controller)
        Screen.Settings -> SettingsScreen(
            repository = settings,
            onFinish = { navigator.push(Screen.Finish) },
            onCustomColor = { navigator.push(Screen.CustomColor) },
            onGrain = { navigator.push(Screen.Grain) },
        )
        Screen.Finish -> FinishScreen(settings, onDone = { navigator.pop() }, onCustomColor = { navigator.push(Screen.CustomColor) })
        Screen.Grain -> GrainScreen(settings, onDone = { navigator.pop() })
        Screen.CustomColor -> CustomColorScreen(settings, onDone = { navigator.popTo(Screen.Settings) })
    }
}

/** The back stack's only writer. */
private class Navigator(private val stack: SnapshotStateList<Screen>) {
    fun push(screen: Screen) {
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
    fun popTo(screen: Screen) {
        if (screen !in stack) {
            pop()
            return
        }
        while (stack.last() != screen) stack.removeAt(stack.lastIndex)
    }

    /** Now Playing is a single instance: return to it if it's already in the stack. */
    fun showNowPlaying() {
        val existing = stack.indexOf(Screen.NowPlaying)
        if (existing >= 0) {
            while (stack.lastIndex > existing) stack.removeAt(stack.lastIndex)
        } else {
            stack.add(Screen.NowPlaying)
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

private fun AnimatedContentTransitionScope<Scene<Screen>>.forward(motion: PodiumMotion): ContentTransform =
    if (motion.reduced) {
        fadeIn(motion.fadeStandard()) togetherWith fadeOut(motion.fadeFast())
    } else {
        // The iPod strip: the next level slides in from the right as the current one leaves left.
        slideInHorizontally(motion.navigateOffset()) { it } togetherWith slideOutHorizontally(motion.navigateOffset()) { -it }
    }

private fun AnimatedContentTransitionScope<Scene<Screen>>.backward(motion: PodiumMotion): ContentTransform =
    if (motion.reduced) {
        fadeIn(motion.fadeStandard()) togetherWith fadeOut(motion.fadeFast())
    } else {
        slideInHorizontally(motion.navigateOffset()) { -it } togetherWith slideOutHorizontally(motion.navigateOffset()) { it }
    }
