package app.podium.feature.nowplaying

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.podium.core.designsystem.artwork.ArtworkLoader
import app.podium.core.designsystem.artwork.LocalArtworkLoader
import app.podium.core.designsystem.component.ImmersiveHost
import app.podium.core.designsystem.component.LocalImmersiveHost
import app.podium.core.designsystem.component.LocalOverlayHost
import app.podium.core.designsystem.component.LocalScreenInsets
import app.podium.core.designsystem.component.OverlayHost
import app.podium.core.designsystem.component.PodWheel
import app.podium.core.designsystem.component.ScreenInsets
import app.podium.core.designsystem.glass.GlassTier
import app.podium.core.designsystem.shell.DeviceAppearance
import app.podium.core.designsystem.shell.DeviceBody
import app.podium.core.designsystem.shell.DeviceLayout
import app.podium.core.designsystem.shell.FinishPreset
import app.podium.core.designsystem.shell.PowerButton
import app.podium.core.designsystem.shell.ScreenHeader
import app.podium.core.designsystem.shell.ScreenHeaderHeight
import app.podium.core.designsystem.shell.palette
import app.podium.core.designsystem.theme.DisplayTheme
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.designsystem.theme.Spacing
import app.podium.core.designsystem.tonal.defocus
import app.podium.core.interaction.InputRouter
import app.podium.core.interaction.LocalInputRouter
import app.podium.core.interaction.PodiumInput
import app.podium.core.interaction.WheelButton
import app.podium.core.model.QueueUid
import app.podium.core.model.TrackId
import app.podium.player.api.AudioPulse
import app.podium.player.api.FavoritesRepository
import app.podium.player.api.NowPlayingItem
import app.podium.player.api.PlayIntent
import app.podium.player.api.PlaybackController
import app.podium.player.api.PlaybackSnapshot
import app.podium.player.api.PlaybackStatus
import app.podium.player.api.QueueEntry
import app.podium.player.api.QueueOrigin
import app.podium.player.api.QueueView
import app.podium.player.api.RepeatMode
import app.podium.player.api.VolumeController
import app.podium.player.api.VolumeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Now Playing's vinyl (D-104), drawn with Robolectric's native graphics on the device, its clock
 * stepped by hand: the way in, a song changing, the way back. Frames go to build/screenshots/.
 * And the Wheel: only Menu, ⏮, ⏭ and Center do anything while it shows.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VinylStageTest {

    @get:Rule
    val compose = createComposeRule()

    private val host = ImmersiveHost()
    private val router = InputRouter()
    private val controller = FakeController()

    private fun show(theme: DisplayTheme) {
        RuntimeEnvironment.setQualifiers("w411dp-h891dp-port-420dpi")
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PodiumTheme(darkTheme = true, glassTier = GlassTier.Solid, displayTheme = theme) {
                val palette = DeviceAppearance(FinishPreset.STEEL_GRAY, display = theme).palette(darkTheme = true)
                CompositionLocalProvider(
                    LocalInputRouter provides router,
                    LocalOverlayHost provides OverlayHost(),
                    LocalArtworkLoader provides FakeArtwork,
                    LocalImmersiveHost provides host,
                ) {
                    Box(Modifier.fillMaxSize()) {
                        DeviceBody(palette, Modifier.fillMaxSize())
                        DeviceLayout(
                            modifier = Modifier.fillMaxSize(),
                            screen = {
                                // As the shell does: the display behind goes out of focus, the moment over all of it.
                                Box(Modifier.fillMaxSize().defocus { host.depth * 2f }) {
                                    CompositionLocalProvider(LocalScreenInsets provides ScreenInsets(top = ScreenHeaderHeight, bottom = Spacing.edge)) {
                                        NowPlayingScreen(controller, FakeVolume, FakeFavorites, onUpNext = {}, pulse = FakePulse)
                                    }
                                    ScreenHeader("Now Playing", canGoBack = true, onBack = {}, playing = controller.playing)
                                }
                                host.content?.invoke()
                            },
                            wheel = { d -> PodWheel(onInput = {}, diameter = d, palette = palette) },
                            powerButton = { PowerButton(on = true, palette = palette, onToggle = {}) },
                        )
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
    }

    private fun frame(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(File("build/screenshots").apply { mkdirs() }, "vinyl-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Steps the clock to [ms] after [from], letting the records render off the main thread on the way. */
    private fun at(from: Long, ms: Long) {
        compose.mainClock.advanceTimeBy(ms - from)
        Thread.sleep(60)
        compose.mainClock.advanceTimeBy(16)
    }

    @Test
    fun theWayInASongChangeAndTheWayBack() {
        show(DisplayTheme.GLASS)
        frame("0-now-playing")
        compose.onNodeWithContentDescription("Vinyl").performClick()
        var t = 0L
        for (ms in listOf(250L, 600L, 900L, 1_250L, 1_650L, 2_050L, 2_600L)) {
            at(t, ms)
            t = ms + 16
            frame("in-$ms")
        }
        // The needle is down: the paused song plays.
        assertTrue(controller.played, "the record plays when the needle lands")

        controller.skip()
        t = 0
        for (ms in listOf(200L, 450L, 700L, 1_100L)) {
            at(t, ms)
            t = ms + 16
            frame("next-$ms")
        }

        router.dispatch(PodiumInput.Press(WheelButton.MENU))
        t = 0
        for (ms in listOf(250L, 700L, 1_100L, 1_500L, 1_800L, 2_300L)) {
            at(t, ms)
            t = ms + 16
            frame("out-$ms")
        }
        assertEquals(null, host.content, "back in Now Playing, the stage is gone")
        frame("out-done")
    }

    @Test
    fun onlyMenuSkipsAndCenterDoAnything() {
        show(DisplayTheme.CARBON)
        compose.onNodeWithContentDescription("Vinyl").performClick()
        compose.mainClock.advanceTimeBy(3_000)
        Thread.sleep(60)
        compose.mainClock.advanceTimeBy(100)
        val before = controller.calls.toList()
        // ⏯, holds, and turning without seeking: nothing, and nothing reaches Now Playing or the shell.
        for (input in listOf(
            PodiumInput.Press(WheelButton.PLAY_PAUSE),
            PodiumInput.LongPress(WheelButton.PLAY_PAUSE),
            PodiumInput.LongPress(WheelButton.MENU),
            PodiumInput.LongPress(WheelButton.CENTER),
            PodiumInput.Rotate(3, 0f),
        )) {
            assertTrue(router.dispatch(input), "$input is the vinyl's")
        }
        assertEquals(before, controller.calls, "nothing happened")
        // Center, then turning, seeks; ⏭ changes the song.
        router.dispatch(PodiumInput.Press(WheelButton.CENTER))
        router.dispatch(PodiumInput.Rotate(2, 0f))
        assertTrue(controller.calls.any { it.startsWith("seekTo") }, "Center then the Wheel seeks: ${controller.calls}")
        router.dispatch(PodiumInput.Press(WheelButton.NEXT))
        assertTrue("next" in controller.calls, "⏭ changes the song")
        frame("carbon-playing")
    }

    private class FakeController : PlaybackController {
        val calls = mutableListOf<String>()
        var played = false
        private val songs = listOf(
            song("q1", "Harbour Lights", "Field Recordings Co.", "test://square", 2),
            song("q2", "Woodland Song", "Northern Sines", "test://green", 3),
        )
        private var index = 0
        private val state = MutableStateFlow(PlaybackSnapshot(status = PlaybackStatus.Paused(app.podium.player.api.PauseReason.USER), intent = PlayIntent.PAUSE, item = songs[0]))
        val playing: Boolean get() = state.value.intent == PlayIntent.PLAY
        override val snapshot: StateFlow<PlaybackSnapshot> = state
        override val queue: StateFlow<QueueView> = MutableStateFlow(QueueView(entries = songs.map { QueueEntry(it.uid, it.trackId, it.title, it.artistDisplay, it.artworkUri, it.durationMs, QueueOrigin.CONTEXT, it.uid == QueueUid("q1")) }, currentIndex = 0))
        override fun positionMs() = 63_000L
        override fun play() {
            played = true
            calls += "play"
            state.value = state.value.copy(status = PlaybackStatus.Playing, intent = PlayIntent.PLAY)
        }
        override fun pause() { calls += "pause" }
        override fun togglePlayPause() { calls += "toggle" }
        override fun next() { calls += "next" }
        override fun previous() { calls += "previous" }
        override fun seekTo(positionMs: Long) { calls += "seekTo $positionMs" }
        override fun setRepeat(mode: RepeatMode) { calls += "repeat" }
        override fun setShuffle(enabled: Boolean) { calls += "shuffle" }
        override fun playContext(tracks: List<TrackId>, startIndex: Int, contextLabel: String?, shuffle: Boolean, radio: Boolean) = Unit
        override fun playNext(tracks: List<TrackId>) = Unit
        override fun addToQueue(tracks: List<TrackId>) = Unit
        override fun move(uid: QueueUid, toIndex: Int) = Unit
        override fun remove(uids: Set<QueueUid>) = Unit
        override fun skipTo(uid: QueueUid) = Unit
        override fun clearUpcoming() = Unit

        /** The next song plays, as the player would report it. */
        fun skip() {
            index = (index + 1) % songs.size
            state.value = state.value.copy(item = songs[index])
        }

        private fun song(uid: String, title: String, artist: String, art: String, at: Int) = NowPlayingItem(
            uid = QueueUid(uid),
            trackId = TrackId("test|$uid"),
            title = title,
            artistDisplay = artist,
            albumTitle = null,
            artworkUri = art,
            durationMs = 214_000,
            indexInQueue = at,
            queueSize = 11,
            servedByDisplayName = "On this device",
            resolutionPath = null,
        )
    }

    private object FakeVolume : VolumeController {
        override val state: StateFlow<VolumeState> = MutableStateFlow(VolumeState(level = 6, max = 15, isFixed = false))
        override fun step(delta: Int) = Unit
    }

    private object FakeFavorites : FavoritesRepository {
        override val favorites: StateFlow<Set<TrackId>> = MutableStateFlow(emptySet())
        override fun toggle(id: TrackId) = Unit
    }

    /** A steady, moderately loud song. */
    private object FakePulse : AudioPulse {
        override fun bassAt(positionMs: Long): Float = 0.2f
        override fun levelsAt(positionMs: Long, out: FloatArray): Boolean {
            out[AudioPulse.WHOLE] = 0.22f
            out[AudioPulse.LOW] = 0.2f
            out[AudioPulse.HIGH] = 0.05f
            return true
        }
    }

    /** Generated covers only — no third-party artwork in the repository. */
    private object FakeArtwork : ArtworkLoader {
        override suspend fun load(uri: String, sizePx: Int): ImageBitmap? = cover(uri)
        override fun peek(uri: String, sizePx: Int): ImageBitmap? = cover(uri)

        private fun cover(uri: String): ImageBitmap? {
            val (from, to) = when (uri.substringAfter("test://")) {
                "square" -> 0xFF1D3557.toInt() to 0xFFE63946.toInt()
                "green" -> 0xFF2D6A4F.toInt() to 0xFFD8F3DC.toInt()
                else -> return null
            }
            val bitmap = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).drawRect(0f, 0f, 600f, 600f, Paint().apply {
                shader = LinearGradient(0f, 0f, 600f, 600f, from, to, Shader.TileMode.CLAMP)
            })
            Canvas(bitmap).drawCircle(420f, 200f, 90f, Paint().apply { color = 0xFFF1FAEE.toInt() })
            return bitmap.asImageBitmap()
        }
    }
}
