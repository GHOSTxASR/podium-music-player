package app.podium

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import kotlin.math.roundToInt
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOut
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.ui.NavDisplay
import app.podium.core.common.DiskActivity
import app.podium.core.common.Outcome
import app.podium.core.designsystem.artwork.BackgroundExtension
import app.podium.core.designsystem.artwork.LocalArtworkLoader
import app.podium.core.designsystem.artwork.rememberArtwork
import app.podium.core.designsystem.component.GlassMenu
import app.podium.core.designsystem.component.KeyboardHost
import app.podium.core.designsystem.component.KeyboardStyle
import app.podium.core.designsystem.component.LocalKeyboardHost
import app.podium.core.designsystem.component.LocalKeyboardStyle
import app.podium.core.designsystem.component.LocalMiniature
import app.podium.core.designsystem.component.LocalMiniatureFocusKey
import app.podium.core.designsystem.component.LocalOverlayHost
import app.podium.core.designsystem.component.LocalPaperDecor
import app.podium.core.designsystem.component.LocalPaperPeek
import app.podium.core.designsystem.component.LocalPaperKey
import app.podium.core.designsystem.component.LocalColumnTransition
import app.podium.core.designsystem.component.softArrival
import app.podium.core.designsystem.component.softDeparture
import app.podium.core.designsystem.component.LocalPaperLenses
import app.podium.core.designsystem.component.PaperLenses
import app.podium.core.designsystem.component.LocalScreenInsets
import app.podium.core.designsystem.component.MiniPlayer
import app.podium.core.designsystem.component.OverlayHost
import app.podium.core.designsystem.component.PaperGeometry
import app.podium.core.designsystem.component.PodWheel
import app.podium.core.designsystem.component.ScreenInsets
import app.podium.core.designsystem.component.WheelKeyboard
import app.podium.core.designsystem.glass.GlassHost
import app.podium.core.designsystem.glass.scrollEdgeFade
import app.podium.core.designsystem.shell.BootScreen
import app.podium.core.designsystem.shell.DeviceBody
import app.podium.core.designsystem.shell.DeviceLayout
import app.podium.core.designsystem.shell.PowerButton
import app.podium.core.designsystem.shell.ScreenHeader
import app.podium.core.designsystem.shell.ScreenHeaderHeight
import app.podium.core.designsystem.shell.ShellPalette
import app.podium.core.designsystem.shell.StatusLeds
import app.podium.core.designsystem.shell.palette
import app.podium.core.designsystem.theme.Atmosphere
import app.podium.core.designsystem.theme.AtmosphereBackground
import app.podium.core.designsystem.theme.DisplayBackground
import app.podium.core.designsystem.theme.LocalDisplaySurface
import app.podium.core.designsystem.theme.PodiumMotion
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.designsystem.theme.Spacing
import app.podium.core.interaction.Clicker
import app.podium.core.interaction.Feedback
import app.podium.core.interaction.InputRouter
import app.podium.core.interaction.LocalFeedback
import app.podium.core.interaction.LocalInputRouter
import app.podium.core.interaction.PodiumHaptics
import app.podium.core.interaction.PodiumInput
import app.podium.core.interaction.WheelButton
import app.podium.core.model.AlbumId
import app.podium.core.model.ArtistId
import app.podium.core.model.PlaylistId
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
import app.podium.feature.nowplaying.LyricsScreen
import app.podium.feature.nowplaying.NowPlayingScreen
import app.podium.feature.nowplaying.UpNextScreen
import app.podium.feature.online.OnlineActions
import app.podium.feature.online.OnlinePlace
import app.podium.feature.online.OnlineScreen
import app.podium.feature.settings.AppearanceLevel
import app.podium.feature.settings.AppearanceScreen
import app.podium.feature.settings.BackgroundScreen
import app.podium.feature.settings.ColorTarget
import app.podium.feature.settings.CustomColorScreen
import app.podium.feature.settings.DeviceBodyScreen
import app.podium.feature.settings.FinishScreen
import app.podium.feature.settings.FontScreen
import app.podium.feature.settings.LevelScreen
import app.podium.feature.settings.LyricsFontScreen
import app.podium.space.LocalPodiumSpace
import app.podium.space.HelpContent
import app.podium.space.StickerDetail
import app.podium.space.StickerGallery
import app.podium.stickers.Sticker
import app.podium.stickers.StickerEditor
import app.podium.stickers.StickerEditorBar
import app.podium.stickers.StickerEditorState
import app.podium.stickers.StickerLayer
import app.podium.stickers.StickerMaker
import app.podium.guide.GuideTour
import app.podium.guide.GuideState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import app.podium.space.SpacePanel
import app.podium.space.SpacePhase
import app.podium.space.SpacePage
import app.podium.space.rememberPodiumSpaceState
import app.podium.space.PodiumSpace
import app.podium.feature.settings.MusicFoldersScreen
import app.podium.feature.settings.OnlineServiceScreen
import app.podium.feature.settings.SettingsScreen
import app.podium.feature.settings.ThemeScreen
import app.podium.feature.settings.VirtualDisplayScreen
import app.podium.player.api.PlayIntent
import app.podium.player.api.PlaybackController
import app.podium.player.api.PlaybackSnapshot
import app.podium.player.api.RecommendationRequest
import app.podium.sources.api.ArtistSummary
import app.podium.sources.api.CapabilityAction
import app.podium.sources.api.RemoteContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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

    /** Now Playing ▸ Lyrics: the whole display is the lyric (no header, no mini player). */
    data object Lyrics : Dest
    data object Settings : Dest

    /** Settings ▸ Appearance (D-41) and its two halves. */
    data object Appearance : Dest
    data object DeviceBody : Dest
    data object VirtualDisplay : Dest
    data object Theme : Dest
    data object Font : Dest
    data object LyricsFont : Dest
    data object Background : Dest
    data object Finish : Dest
    data object CustomColor : Dest

    /** The display's background colour, in the colour editor. */
    data object DisplayColor : Dest

    /** A 0–1 level edited with the Wheel (grain, glitter, background opacity). */
    data class Level(val level: AppearanceLevel) : Dest

    /** Settings ▸ the online music service (D-38): account, the app that plays, hand-off. */
    data object OnlineSources : Dest
    data class Album(val id: AlbumId) : Dest
    data class Artist(val id: ArtistId) : Dest

    /** A level of the music-folder tree in Settings ("" is the top). */
    data class MusicFolders(val path: String) : Dest

    /** A place in the ONLINE section (D-34). */
    data class Online(val place: OnlinePlace) : Dest
}

private val fixedDests = listOf(
    Dest.Home, Dest.Music, Dest.CoverFlow, Dest.Albums, Dest.Artists, Dest.Songs, Dest.Favorites, Dest.NowPlaying,
    Dest.UpNext, Dest.Lyrics, Dest.Settings, Dest.Appearance, Dest.DeviceBody, Dest.VirtualDisplay, Dest.Theme, Dest.Font,
    Dest.LyricsFont, Dest.Background, Dest.Finish, Dest.CustomColor, Dest.DisplayColor, Dest.OnlineSources,
).associateBy { it.toString() }

private fun Dest.encode(): String = when (this) {
    is Dest.Album -> "Album:${id.value}"
    is Dest.Artist -> "Artist:${id.value}"
    is Dest.MusicFolders -> "MusicFolders:$path"
    is Dest.Online -> "Online:" + OnlinePlace.encode(place)
    is Dest.Level -> "Level:${level.name}"
    else -> toString()
}

private fun decodeDest(text: String): Dest? = when {
    text.startsWith("Album:") -> Dest.Album(AlbumId(text.removePrefix("Album:")))
    text.startsWith("Artist:") -> Dest.Artist(ArtistId(text.removePrefix("Artist:")))
    text.startsWith("MusicFolders:") -> Dest.MusicFolders(text.removePrefix("MusicFolders:"))
    text.startsWith("Online:") -> OnlinePlace.decode(text.removePrefix("Online:"))?.let(Dest::Online)
    text.startsWith("Level:") -> AppearanceLevel.entries.firstOrNull { it.name == text.removePrefix("Level:") }?.let(Dest::Level)
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
    Dest.Lyrics -> "Lyrics"
    Dest.Settings -> "Settings"
    is Dest.MusicFolders -> if (dest.path.isEmpty()) "Music folders" else dest.path.trimEnd('/').substringAfterLast('/')
    is Dest.Online -> dest.place.title
    Dest.Appearance -> "Appearance"
    Dest.DeviceBody -> "Device body"
    Dest.VirtualDisplay -> "Virtual display"
    Dest.Theme -> "Theme"
    Dest.Font -> "Font"
    Dest.LyricsFont -> "Lyrics font"
    Dest.Background -> "Background"
    Dest.Finish -> "Finish"
    Dest.CustomColor -> "Custom color"
    Dest.DisplayColor -> "Background color"
    is Dest.Level -> dest.level.label
    Dest.OnlineSources -> graph.onlineService.state.collectAsStateWithLifecycle().value?.name ?: "Online music"
    is Dest.Album -> {
        val album by remember(dest) { graph.library.album(dest.id) }.collectAsStateWithLifecycle(initialValue = null)
        album?.album?.title ?: "Album"
    }
    is Dest.Artist -> {
        val artist by remember(dest) { graph.library.artist(dest.id) }.collectAsStateWithLifecycle(initialValue = null)
        artist?.artist?.name ?: "Artist"
    }
}

/**
 * The device (D-26). The window is the device's body in the chosen finish; a recessed virtual
 * screen above the Wheel holds the whole interface; a small power button sits beside the Wheel.
 * All input reaches screens through the [InputRouter]; anything a screen doesn't consume falls
 * through to the global handlers here.
 */
@Composable
fun PodiumApp(graph: AppGraph, onSourceAction: (CapabilityAction) -> Unit, onTurnOff: () -> Unit = {}) {
    val appearance by graph.deviceSettings.appearance.collectAsStateWithLifecycle()
    val preview by graph.deviceSettings.preview.collectAsStateWithLifecycle()
    val effective = preview ?: appearance
    val displayImage by graph.displayImages.state.collectAsStateWithLifecycle()
    LaunchedEffect(effective.screen.imageUri) { graph.displayImages.show(effective.screen.imageUri) }
    PodiumTheme(
        displayTheme = effective.display,
        display = effective.screen,
        displayImage = displayImage.image?.takeIf { displayImage.uri == effective.screen.imageUri },
    ) {
        val controller = graph.playbackController
        val snapshot by controller.snapshot.collectAsStateWithLifecycle()
        val power by graph.power.collectAsStateWithLifecycle()
        // Where the music is heard (D-52). A route switched in the system's output picker sends no
        // event, so it's looked at again every few seconds while Podium is in front.
        val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(lifecycle) {
            lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                while (true) {
                    graph.audioOutput.refresh()
                    kotlinx.coroutines.delay(OUTPUT_REFRESH_MS)
                }
            }
        }
        val colors = PodiumTheme.colors
        val palette = effective.palette(colors.isDark)

        val backStack = rememberSaveable(saver = BackStackSaver) { mutableStateListOf<Dest>(Dest.Home) }
        val navigator = remember(backStack) { Navigator(backStack) }
        val router = remember { InputRouter() }
        val overlay = remember { OverlayHost() }
        // Typing (D-45): fields open the Podium keyboard where the Wheel is, or the phone's.
        val keyboard = remember { KeyboardHost() }
        val podiumKeyboard by graph.deviceSettings.podiumKeyboard.collectAsStateWithLifecycle()
        val hapticsOn by graph.deviceSettings.haptics.collectAsStateWithLifecycle()
        val clicksOn by graph.deviceSettings.clicks.collectAsStateWithLifecycle()
        val feedback = remember(hapticsOn, clicksOn) { Feedback(haptics = hapticsOn, clicker = if (clicksOn) graph.sounds else Clicker.Silent) }
        val view = LocalView.current
        val haptics = remember(view, feedback) { PodiumHaptics(view, feedback) }
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

        // The physical Podium (D-54, D-56): pinch it into its space; the settings page beside it.
        val space = rememberPodiumSpaceState()
        var spacePage by remember { mutableStateOf(SpacePage.MAIN) }
        var openSticker by remember { mutableStateOf<String?>(null) }
        var makingFrom by remember { mutableStateOf<android.net.Uri?>(null) }
        var turningOff by remember { mutableStateOf(false) }
        val stickerEditor = remember { StickerEditorState() }
        val guide = remember { GuideState(graph.guide) }
        LaunchedEffect(space.phase) {
            if (space.phase == SpacePhase.NORMAL) {
                spacePage = SpacePage.MAIN
                openSticker = null
            }
        }
        LaunchedEffect(space) {
            graph.debugSpace.collect { open ->
                if (open == true && !space.isOut) space.enter() else if (open == false && space.isOut) space.leave()
                graph.debugSpace.value = null
            }
        }
        LaunchedEffect(space) {
            graph.debugStickerSource.collect { uri ->
                if (uri != null) {
                    if (!space.isOut) space.enter()
                    makingFrom = uri
                    graph.debugStickerSource.value = null
                }
            }
        }
        LaunchedEffect(space) {
            graph.debugArrange.collect { arrange ->
                if (!arrange) return@collect
                if (!space.isOut) space.enter()
                snapshotFlow { space.phase }.first { it == SpacePhase.PHYSICAL }
                space.startEditing()
                graph.debugArrange.value = false
            }
        }
        LaunchedEffect(guide) {
            graph.debugGuide.collect { start ->
                if (start) {
                    guide.offer()
                    graph.debugGuide.value = false
                }
            }
        }
        // The tour (D-57): offered once Podium has switched on for the first time.
        LaunchedEffect(power) {
            if (power == Power.ON) {
                delay(GUIDE_DELAY_MS)
                guide.offer()
            }
        }
        val pickPicture = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) makingFrom = uri
        }
        fun stickOn(sticker: Sticker) {
            val placed = graph.stickers.place(sticker.id)
            stickerEditor.selected = placed.id
            spacePage = SpacePage.MAIN
            space.startEditing()
        }

        val nowPlayingArt = rememberArtwork(snapshot.item?.artworkUri, 96.dp)
        val atmosphere = remember(nowPlayingArt, colors.isDark) {
            nowPlayingArt?.let { Atmosphere.fromArtwork(it.asAndroidBitmap(), colors.isDark) } ?: Atmosphere.neutral(colors)
        }

        CompositionLocalProvider(
            LocalInputRouter provides router,
            LocalOverlayHost provides overlay,
            LocalArtworkLoader provides graph.artworkLoader,
            LocalFeedback provides feedback,
            LocalKeyboardHost provides keyboard,
            LocalKeyboardStyle provides if (podiumKeyboard) KeyboardStyle.PODIUM else KeyboardStyle.PHONE,
            LocalPodiumSpace provides space,
        ) {
         Box(Modifier.fillMaxSize()) {
          PodiumSpace(
            state = space,
            edgeColor = if (palette.isGlass) GlassEdge else palette.bodyBottom,
            spaceTint = androidx.compose.ui.graphics.lerp(if (palette.isGlass) GlassEdge else palette.bodyBottom, Color.Black, 0.94f),
            stickers = { StickerLayer(graph.stickers, editor = stickerEditor) },
            stickerEditor = { StickerEditor(graph.stickers, stickerEditor) },
            panel = {
                BackHandler {
                    when {
                        space.phase == SpacePhase.STICKER_EDITING -> {
                            stickerEditor.selected = null
                            space.stopEditing()
                        }
                        spacePage != SpacePage.MAIN -> spacePage = SpacePage.MAIN
                        else -> space.leave()
                    }
                }
                SpacePanel(
                    state = space,
                    page = spacePage,
                    onPage = { spacePage = it },
                    stickers = {
                        StickerGallery(
                            graph.stickers,
                            onAdd = { pickPicture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            onOpen = {
                                openSticker = it.id
                                spacePage = SpacePage.STICKER
                            },
                            onArrange = space::startEditing,
                        )
                    },
                    sticker = {
                        StickerDetail(
                            graph.stickers,
                            openSticker,
                            onStick = ::stickOn,
                            onArrange = space::startEditing,
                            onDeleted = {
                                spacePage = SpacePage.MAIN
                                openSticker = null
                            },
                        )
                    },
                    help = {
                        HelpContent(onReplayGuide = guide::begin)
                    },
                    onTurnOff = {
                        // The music stops at once; the goodbye, then Podium closes.
                        controller.pause()
                        turningOff = true
                    },
                )
                val arranging by remember(space) { derivedStateOf { space.phase == SpacePhase.STICKER_EDITING || space.edit.value > 0.01f } }
                if (arranging) StickerEditorBar(space, stickerEditor, graph.stickers)
                makingFrom?.let { uri ->
                    StickerMaker(
                        source = uri,
                        cutter = graph.cutter,
                        store = graph.stickers,
                        onSaved = { sticker ->
                            makingFrom = null
                            stickOn(sticker)
                        },
                        onCancel = { makingFrom = null },
                    )
                }
            },
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
                                    Power.BOOTING -> {
                                        val checks by graph.bootChecks.collectAsStateWithLifecycle()
                                        BootScreen(checks = checks, onChime = graph::onBootChime, onFinished = graph::onBootFinished)
                                    }
                                    Power.ON -> ScreenOs(graph, snapshot, backStack, navigator, overlay, atmosphere, onSourceAction)
                                }
                            }
                        },
                        wheel = { diameter ->
                            WheelKeyboard(keyboard, diameter, palette) {
                                PodWheel(
                                    onInput = { router.dispatch(it) },
                                    diameter = diameter,
                                    isPlaying = snapshot.intent == PlayIntent.PLAY,
                                    palette = palette,
                                )
                            }
                        },
                        keyboardOpen = keyboard.isOpen,
                        powerButton = {
                            PowerButton(on = power != Power.OFF, palette = palette, onToggle = graph::togglePower)
                        },
                        indicators = {
                            val battery by graph.battery.collectAsStateWithLifecycle()
                            StatusLeds(battery, DiskActivity.pulses, palette)
                        },
                    )
                },
            )
          }
          GuideTour(guide, palette)
          if (turningOff) Farewell(onFinished = onTurnOff)
         }
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
    val output by graph.audioOutput.state.collectAsStateWithLifecycle()
    val top = backStack.last()
    val miniPlayerVisible = snapshot.isActive && top.showsMiniPlayer()
    // Lyrics take the whole display: no header, no fading edges.
    val fullScreen = top == Dest.Lyrics

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val miniBlock = if (miniPlayerVisible) Spacing.miniPlayer + Spacing.s * 2 else Spacing.s
        val insets = ScreenInsets(top = ScreenHeaderHeight, bottom = miniBlock)
        val density = LocalDensity.current
        val screenHeight = maxHeight
        // The same geometry the lists use, so a screen moving away lands exactly in its box.
        val paper = PaperGeometry(maxWidth, maxHeight, insets.top + 8.dp, insets.bottom + 8.dp)
        // Where each column's lit row sits, and which column was in front before this move (D-61).
        val lenses = remember { PaperLenses() }
        // The column in front now, and the one in front before it. The move may be set up before or
        // after this records the change, so the column leaving is whichever of the two isn't in front.
        val fronts = remember { arrayOfNulls<Dest>(2) }
        SideEffect {
            val now = backStack.last()
            if (fronts[1] != now) {
                fronts[0] = fronts[1]
                fronts[1] = now
            }
        }
        val leavingLens: () -> Float? = {
            val now = backStack.last()
            lenses.centreOf(if (fronts[1] == now) fronts[0] else fronts[1])
        }
        val comingLens: () -> Float? = { lenses.centreOf(backStack.last()) }

        GlassHost(
            modifier = Modifier.fillMaxSize(),
            content = {
                // Glass tints the display with the artwork's atmosphere; Carbon and Bone stay matte, and
                // a background the listener chose (D-41) shows as it is.
                if (!colors.isIndustrial && LocalDisplaySurface.current == null) AtmosphereBackground(atmosphere, Modifier.fillMaxSize())
                CompositionLocalProvider(LocalScreenInsets provides insets, LocalPaperLenses provides lenses) {
                    NavDisplay(
                        backStack = backStack,
                        modifier = Modifier.fillMaxSize().scrollEdgeFade(
                            topFadePx = if (fullScreen) 0f else with(density) { insets.top.toPx() },
                            bottomStartPx = with(density) { (if (fullScreen) screenHeight else screenHeight - insets.bottom.coerceAtLeast(24.dp)).toPx() },
                        ),
                        onBack = { navigator.pop() },
                        // Forward: the column in front leaves for the left box, the new one comes from the right.
                        // Back: the column in front leaves for the right box, the one behind comes from the left.
                        transitionSpec = { paperForward(paper, density, motion.reduced, leaving = leavingLens()) },
                        popTransitionSpec = { paperBack(paper, density, motion.reduced, leaving = leavingLens(), coming = comingLens()) },
                        predictivePopTransitionSpec = { paperBack(paper, density, motion.reduced, leaving = leavingLens(), coming = comingLens()) },
                        entryProvider = { key ->
                            // Glass lets Now Playing rise from the mini player; Carbon and Bone keep the whole
                            // hierarchy on one horizontal sheet of paper (D-29).
                            val rise = key == Dest.NowPlaying && !colors.isIndustrial
                            NavEntry(key, metadata = if (rise) riseFromMiniPlayer(motion) else emptyMap()) { screen ->
                                // Each screen sees the column before it on the paper peeking in at its left.
                                val position = backStack.indexOf(screen)
                                val previous = if (position > 0) backStack[position - 1] else null
                                val peek: (@Composable (Modifier) -> Unit)? = previous?.let { prev ->
                                    { m -> PreviousColumn(prev, screen, graph, navigator, m) }
                                }
                                val scope = LocalNavAnimatedContentScope.current
                                val decor: @Composable () -> Modifier = {
                                    // The glimpses come in as the move settles, soft then sharp, overlapping
                                    // the column that sinks into their box (D-62).
                                    with(scope) {
                                        Modifier
                                            .animateEnterExit(
                                                enter = fadeIn(tween(GlimpseArrivalMillis, delayMillis = if (motion.reduced) 0 else GlimpseDelayMillis, easing = PodiumMotion.Standard)),
                                                exit = fadeOut(tween(80)),
                                            )
                                            .softArrival(scope, delayMillis = GlimpseDelayMillis, durationMillis = GlimpseArrivalMillis + 80)
                                    }
                                }
                                CompositionLocalProvider(
                                    LocalPaperPeek provides peek,
                                    LocalPaperDecor provides decor,
                                    LocalPaperKey provides screen,
                                    LocalColumnTransition provides scope,
                                ) {
                                    // Leaving for a box, a column melts into the glimpse that takes its place (D-64).
                                    Box(Modifier.fillMaxSize().softDeparture(scope, delayMillis = DepartureDelayMillis, durationMillis = PaperTransitionMillis - DepartureDelayMillis)) {
                                        ScreenContent(screen, graph, navigator, onSourceAction)
                                    }
                                }
                            }
                        },
                    )
                }
            },
            functional = {
                if (!fullScreen) ScreenHeader(
                    title = titleOf(top, graph),
                    depth = backStack.size,
                    canGoBack = backStack.size > 1,
                    onBack = { navigator.pop() },
                    playing = if (snapshot.isActive) snapshot.intent == PlayIntent.PLAY else null,
                    output = output.indicator(),
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
                // Back folds the keyboard away before it goes anywhere (D-45). Registered when it
                // opens, so it comes after the navigation's own handler and wins.
                val keyboard = LocalKeyboardHost.current
                if (keyboard != null && keyboard.isOpen) BackHandler { keyboard.dismiss() }
                overlay.menu?.let { spec ->
                    GlassMenu(spec, onDismiss = overlay::dismiss, panelPadding = PaddingValues(top = ScreenHeaderHeight))
                    BackHandler { overlay.dismiss() }
                }
            },
        )
    }
}

/**
 * The column before [current], live (D-29): the previous screen itself, scaled into the box at
 * middle-left with the item that led here focused. It takes no input and has no side effects
 * (its own wheel targets go to a throwaway router); tapping it goes back.
 */
@Composable
private fun PreviousColumn(previous: Dest, current: Dest, graph: AppGraph, navigator: Navigator, modifier: Modifier) {
    val snapshot by graph.playbackController.snapshot.collectAsStateWithLifecycle()
    val focusKey = when (current) {
        is Dest.Album -> current.id.value
        is Dest.Artist -> current.id.value
        Dest.NowPlaying -> snapshot.item?.trackId?.value
        // Settings rows are keyed by their enum names.
        Dest.Appearance -> "Appearance"
        Dest.DeviceBody -> "DeviceBody"
        Dest.VirtualDisplay -> "VirtualDisplay"
        Dest.Theme -> "Theme"
        Dest.Font -> "Font"
        Dest.LyricsFont -> "LyricsFont"
        Dest.Background -> "Background"
        Dest.Finish -> "Finish"
        Dest.CustomColor -> if (previous == Dest.Finish) "CUSTOM" else "CustomColor"
        Dest.DisplayColor -> "BackgroundColor"
        is Dest.Level -> current.level.name
        else -> titleOf(current, graph)
    }
    val backLabel = "Back to ${titleOf(previous, graph)}"
    Box(modifier) {
        Box(Modifier.fillMaxSize()) {
            CompositionLocalProvider(
                LocalMiniature provides true,
                LocalMiniatureFocusKey provides focusKey,
                LocalInputRouter provides remember { InputRouter() },
                LocalOverlayHost provides remember { OverlayHost() },
                LocalPaperPeek provides null,
                LocalColumnTransition provides null,
            ) {
                ScreenContent(previous, graph, navigator, onSourceAction = {})
            }
        }
        // The miniature is a picture of where you were: touching it takes you back there.
        Box(
            Modifier
                .matchParentSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { navigator.pop() }
                .semantics { contentDescription = backLabel },
        )
    }
}

private val MiniPlayerScreens = setOf(Dest.Home, Dest.Music, Dest.Albums, Dest.Artists, Dest.Songs, Dest.Favorites)

private fun Dest.showsMiniPlayer() = this in MiniPlayerScreens || this is Dest.Album || this is Dest.Artist ||
    (this is Dest.Online && place !is OnlinePlace.Search && place !is OnlinePlace.NamePlaylist)

@Composable
private fun ScreenContent(
    screen: Dest,
    graph: AppGraph,
    navigator: Navigator,
    onSourceAction: (CapabilityAction) -> Unit,
) {
    val podiumSpace = app.podium.space.LocalPodiumSpace.current
    val controller = graph.playbackController
    val settings = graph.deviceSettings
    val onlineStatus by graph.online.status.collectAsStateWithLifecycle()
    val onlineRecent by graph.online.recentlyPlayed.collectAsStateWithLifecycle(initialValue = emptyList())
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
                onOnline = if (onlineStatus != null) ({ navigator.push(Dest.Online(OnlinePlace.Menu)) }) else null,
                onlineArtwork = onlineRecent.mapNotNull { it.artwork?.uri }.distinct().take(10),
                onSourceAction = onSourceAction,
            )
        }
        Dest.Music -> MusicScreen(
            repository = graph.library,
            favorites = graph.localFavorites,
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
        Dest.Favorites -> FavoritesScreen(graph.library, graph.localFavorites, onPlay = play, onPlayNext = playNext, onAddToQueue = addToQueue)
        Dest.Songs -> SongsScreen(
            repository = graph.library,
            onPlay = { tracks, index -> play(tracks, index, "Songs") },
            onPlayNext = playNext,
            onAddToQueue = addToQueue,
        )
        Dest.NowPlaying -> NowPlayingScreen(
            controller,
            graph.volume,
            graph.favorites,
            onUpNext = { navigator.push(Dest.UpNext) },
            environmentOf = { id -> if (graph.environments.isOnline(id)) "Online" else null },
            onLyrics = { navigator.push(Dest.Lyrics) },
        )
        Dest.Lyrics -> LyricsScreen(controller, graph.lyricsGateway)
        is Dest.Online -> OnlineScreen(
            place = screen.place,
            repository = graph.online,
            actions = rememberOnlineActions(graph, navigator),
            navigate = { navigator.push(Dest.Online(it)) },
            back = { navigator.pop() },
        )
        Dest.UpNext -> UpNextScreen(controller)
        Dest.Settings -> SettingsScreen(
            onPodiumSpace = { podiumSpace?.enter() },
            repository = settings,
            folders = graph.musicFolders,
            onlineService = graph.onlineService,
            onAppearance = { navigator.push(Dest.Appearance) },
            onMusicFolders = { navigator.push(Dest.MusicFolders("")) },
            onOnlineService = { navigator.push(Dest.OnlineSources) },
        )
        Dest.OnlineSources -> OnlineServiceScreen(graph.onlineService)
        is Dest.MusicFolders -> MusicFoldersScreen(graph.musicFolders, screen.path, onOpen = { navigator.push(Dest.MusicFolders(it)) })
        Dest.Appearance -> AppearanceScreen(
            settings,
            onDeviceBody = { navigator.push(Dest.DeviceBody) },
            onVirtualDisplay = { navigator.push(Dest.VirtualDisplay) },
        )
        Dest.DeviceBody -> DeviceBodyScreen(
            settings,
            onFinish = { navigator.push(Dest.Finish) },
            onCustomColor = { navigator.push(Dest.CustomColor) },
            onLevel = { navigator.push(Dest.Level(it)) },
        )
        Dest.VirtualDisplay -> {
            val image by graph.displayImages.state.collectAsStateWithLifecycle()
            VirtualDisplayScreen(
                settings,
                imageStatus = image.status,
                onTheme = { navigator.push(Dest.Theme) },
                onFont = { navigator.push(Dest.Font) },
                onLyricsFont = { navigator.push(Dest.LyricsFont) },
                onBackground = { navigator.push(Dest.Background) },
                onBackgroundColor = { navigator.push(Dest.DisplayColor) },
                onLevel = { navigator.push(Dest.Level(it)) },
            )
        }
        Dest.Theme -> ThemeScreen(settings)
        Dest.Font -> FontScreen(settings)
        Dest.LyricsFont -> LyricsFontScreen(settings)
        Dest.Background -> {
            val image by graph.displayImages.state.collectAsStateWithLifecycle()
            // The system photo picker: no storage permission; Podium keeps only the URI.
            val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                if (uri != null) {
                    val current = settings.appearance.value
                    graph.displayImages.adopt(uri, previous = current.screen.imageUri)
                    settings.setAppearance(current.copy(screen = current.screen.copy(background = DisplayBackground.IMAGE, imageUri = uri.toString())))
                    settings.setPreview(null)
                }
            }
            BackgroundScreen(settings, image.status, onChooseImage = {
                pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            })
        }
        Dest.Finish -> FinishScreen(settings, onCustomColor = { navigator.push(Dest.CustomColor) })
        Dest.CustomColor -> CustomColorScreen(settings)
        Dest.DisplayColor -> CustomColorScreen(settings, ColorTarget.DisplayBackground)
        is Dest.Level -> LevelScreen(settings, screen.level)
    }
}

/** The back stack's only writer. */
private class Navigator(private val stack: SnapshotStateList<Dest>) {
    fun push(screen: Dest) {
        if (stack.last() != screen) {
            stack.add(screen)
            DiskActivity.pulse() // opening a folder on the paper
        }
    }

    /** @return false at the root. */
    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        DiskActivity.pulse()
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

/** One move along the paper (SPATIAL, D-65). */
private const val PaperTransitionMillis = PodiumMotion.SpatialMillis

/**
 * The glimpses arrive as the column leaving reaches its box (92 % of the way, at 47 % of the time on
 * [PodiumMotion.Spatial]), overlapping it, and sharpen as the move settles.
 */
private const val GlimpseDelayMillis = PaperTransitionMillis * 47 / 100
private const val GlimpseArrivalMillis = 240

/** The column leaving softens from 88 % of its way (40 % of the time), as its glimpse arrives. */
private const val DepartureDelayMillis = PaperTransitionMillis * 40 / 100

/**
 * Forward along the paper (D-29, after the user's sketch): the next column grows out of its
 * preview box at middle-right and rises along the curve into focus, while the current screen
 * sinks down and to the left into the previous-column box — the Wheel turning one step. Every part
 * of the move follows one curve, [PodiumMotion.Spatial] (D-65, after D-60): it leaves on the frame
 * of the press, keeps going and settles with a long, soft finish — never pausing part-way.
 */
private fun AnimatedContentTransitionScope<Scene<Dest>>.paperForward(
    g: PaperGeometry,
    density: Density,
    reduced: Boolean,
    leaving: Float?,
): ContentTransform {
    if (reduced) return fadeIn(tween(160)) togetherWith fadeOut(tween(120))
    val (right, left, lift) = paperPoints(g, density)
    return paperMove(
        enterFrom = right,
        enterScale = g.tileScale,
        exitTo = left + litToLeftBox(g, density, leaving),
        exitScale = g.glimpseScale,
        lift = lift,
    )
}

/** Back along the paper: the previous column rises out of its box into focus; the current one sinks into the next box. */
private fun AnimatedContentTransitionScope<Scene<Dest>>.paperBack(
    g: PaperGeometry,
    density: Density,
    reduced: Boolean,
    leaving: Float?,
    coming: Float?,
): ContentTransform {
    if (reduced) return fadeIn(tween(160)) togetherWith fadeOut(tween(120))
    val (right, left, lift) = paperPoints(g, density)
    return paperMove(
        enterFrom = left + litToLeftBox(g, density, coming),
        enterScale = g.glimpseScale,
        exitTo = right,
        exitScale = g.tileScale,
        lift = lift,
    )
}

/**
 * How far to move a column sitting in the left box (at [PaperGeometry.peekScale]) so its lit row,
 * at [litY] px in the column, lands on the box's middle — where the glimpse shows that row (D-61).
 */
private fun litToLeftBox(g: PaperGeometry, density: Density, litY: Float?): IntOffset = with(density) {
    val lit = litY ?: return IntOffset.Zero
    IntOffset(0, ((g.centreY.toPx() - lit) * g.glimpseScale).roundToInt())
}


private fun paperMove(enterFrom: IntOffset, enterScale: Float, exitTo: IntOffset, exitScale: Float, lift: Int): ContentTransform {
    val d = PaperTransitionMillis
    val spatial = PodiumMotion.Spatial
    // The column coming in rises out of its box along an arc; the one leaving dips into the other.
    // The leaving column fades only once it is in its box (95 % of the way), under its glimpse.
    val enter = slideIn(arc(enterFrom, IntOffset.Zero, IntOffset(enterFrom.x / 2, enterFrom.y - lift), d)) { enterFrom } +
        scaleIn(tween(d, easing = spatial), initialScale = enterScale) +
        fadeIn(tween(d * 45 / 100, easing = spatial), initialAlpha = 0.55f)
    val exit = slideOut(arc(IntOffset.Zero, exitTo, IntOffset(exitTo.x / 2, exitTo.y + lift), d)) { exitTo } +
        scaleOut(tween(d, easing = spatial), targetScale = exitScale) +
        fadeOut(tween(d * 43 / 100, delayMillis = d * 57 / 100, easing = PodiumMotion.Standard))
    return enter togetherWith exit
}

/**
 * A move from [from] to [to] along the arc through [through] (its midpoint), timed by one curve
 * end to end ([PodiumMotion.Spatial]): the path is a quadratic curve, sampled finely at eased
 * times, so the speed never dips part-way and the first frame already moves.
 */
private fun arc(from: IntOffset, to: IntOffset, through: IntOffset, durationMillis: Int) = keyframes<IntOffset> {
    this.durationMillis = durationMillis
    // The control point that makes the curve pass through [through] halfway.
    val cx = 2f * through.x - (from.x + to.x) / 2f
    val cy = 2f * through.y - (from.y + to.y) / 2f
    for (i in 0..ArcSamples) {
        val time = i / ArcSamples.toFloat()
        val t = PodiumMotion.Spatial.transform(time)
        val u = 1f - t
        val x = u * u * from.x + 2f * u * t * cx + t * t * to.x
        val y = u * u * from.y + 2f * u * t * cy + t * t * to.y
        IntOffset(x.roundToInt(), y.roundToInt()) at (durationMillis * i / ArcSamples) using LinearEasing
    }
}

private const val ArcSamples = 30

/**
 * Centre offsets of a screen sitting in the next box (whole, filling the tile's frame at
 * [PaperGeometry.tileScale] — D-66) and in the previous box (exactly as the glimpse shows it, at
 * [PaperGeometry.glimpseScale] — D-63), and how far the path arcs.
 */
private fun paperPoints(g: PaperGeometry, density: Density): Triple<IntOffset, IntOffset, Int> = with(density) {
    val right = IntOffset(
        (g.tileLeft + g.width * g.tileScale / 2 - g.width / 2).roundToPx(),
        (g.centreY - g.height / 2).roundToPx(),
    )
    val left = IntOffset(
        (g.glimpseOriginX + g.width * g.glimpseScale / 2 - g.width / 2).roundToPx(),
        (g.glimpseOriginY + g.height * g.glimpseScale / 2 - g.height / 2).roundToPx(),
    )
    Triple(right, left, (g.height * 0.12f).roundToPx())
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

/**
 * What ONLINE asks of the player and the system (D-34). Songs go into the catalog first, so the
 * service can play them by id; radios start as RADIO items from the source's own recommendations.
 */
@Composable
private fun rememberOnlineActions(graph: AppGraph, navigator: Navigator): OnlineActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(graph, navigator, context) {
        val controller = graph.playbackController
        object : OnlineActions {
            private fun startRadio(seed: List<Track>, label: String) {
                if (seed.isEmpty()) return
                graph.catalog.remember(seed)
                controller.playContext(seed.map { it.id }, 0, label, radio = true)
                navigator.showNowPlaying()
            }

            override fun play(tracks: List<Track>, index: Int, label: String) {
                if (tracks.isEmpty()) return
                graph.catalog.remember(tracks)
                controller.playContext(tracks.map { it.id }, index.coerceIn(0, tracks.lastIndex), label)
                navigator.showNowPlaying()
            }

            override fun shuffle(tracks: List<Track>, label: String) {
                if (tracks.isEmpty()) return
                graph.catalog.remember(tracks)
                controller.playContext(tracks.map { it.id }, tracks.indices.random(), label, shuffle = true)
                navigator.showNowPlaying()
            }

            override fun playNext(track: Track) {
                graph.catalog.remember(listOf(track))
                controller.playNext(listOf(track.id))
            }

            override fun addToQueue(track: Track) {
                graph.catalog.remember(listOf(track))
                controller.addToQueue(listOf(track.id))
            }

            override fun startRadio(track: Track) {
                graph.catalog.remember(listOf(track))
                if (graph.playsRemotely(track)) {
                    // The app that plays it runs its own radio from this song (YOUTUBE_MUSIC_ARCHITECTURE §8.5).
                    controller.playCollection(listOf(track.id), 0, "${track.title} radio", RemoteContext.Radio(track))
                    navigator.showNowPlaying()
                    return
                }
                scope.launch {
                    val more = graph.recommendations.recommend(RecommendationRequest(listOf(track), setOf(track.id), RADIO_SIZE))
                    startRadio(listOf(track) + more.filter { it.id != track.id }, "${track.title} radio")
                }
            }

            override fun startArtistRadio(artist: ArtistSummary) {
                scope.launch {
                    val top = (graph.online.artist(artist.id) as? Outcome.Success)?.value?.tracks?.firstOrNull()
                    if (top != null && graph.playsRemotely(top)) {
                        graph.catalog.remember(listOf(top))
                        controller.playCollection(listOf(top.id), 0, "${artist.name} radio", RemoteContext.ArtistRadio(artist.id))
                        navigator.showNowPlaying()
                        return@launch
                    }
                    val tracks = graph.recommendations.artistRadio(artist.id, artist.id.sourceId, RADIO_SIZE, emptySet())
                    startRadio(tracks, "${artist.name} radio")
                }
            }

            override fun playCollection(tracks: List<Track>, index: Int, label: String, collection: PlaylistId, shuffle: Boolean) {
                if (tracks.isEmpty()) return
                graph.catalog.remember(tracks)
                val start = if (shuffle) tracks.indices.random() else index.coerceIn(0, tracks.lastIndex)
                controller.playCollection(tracks.map { it.id }, start, label, RemoteContext.Collection(collection), shuffle)
                navigator.showNowPlaying()
            }

            override fun signIn() = graph.onlineService.signIn()

            override fun startGenreRadio(genre: String) {
                scope.launch {
                    val tracks = (graph.online.genre(genre, 0, RADIO_SIZE) as? Outcome.Success)?.value.orEmpty().shuffled()
                    startRadio(tracks, "$genre radio")
                }
            }

            override fun share(track: Track) {
                val link = track.source.providerUri ?: return
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "${track.title} by ${track.artistDisplay}\n$link")
                context.startActivity(Intent.createChooser(send, "Share ${track.title}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }

            override val nowPlaying: StateFlow<Track?> = controller.snapshot
                .map { snap -> snap.item?.trackId?.takeIf { graph.environments.isOnline(it) }?.let { graph.catalog.cached(it) } }
                .stateIn(scope, SharingStarted.Eagerly, null)
        }
    }
}

private const val RADIO_SIZE = 30

private const val OUTPUT_REFRESH_MS = 4_000L

/** The guide waits a moment after switching on, for the display to settle (and any first question). */
private const val GUIDE_DELAY_MS = 900L

/** The slab's edge for the Glass finish (its body is the artwork's atmosphere, not a colour). */
private val GlassEdge = Color(0xFF1C1E22)

/** The status bar's output glyph: muted first, then where the music goes (none for other outputs). */
private fun app.podium.player.service.AudioOutputState.indicator(): app.podium.core.designsystem.shell.OutputIndicator? = when {
    muted -> app.podium.core.designsystem.shell.OutputIndicator.MUTED
    route == app.podium.player.service.AudioRoute.BLUETOOTH -> app.podium.core.designsystem.shell.OutputIndicator.BLUETOOTH
    route == app.podium.player.service.AudioRoute.WIRED -> app.podium.core.designsystem.shell.OutputIndicator.HEADPHONES
    route == app.podium.player.service.AudioRoute.SPEAKER -> app.podium.core.designsystem.shell.OutputIndicator.PHONE_SPEAKER
    else -> null
}

