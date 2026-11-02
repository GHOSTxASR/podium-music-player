package app.podium.feature.nowplaying

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.podium.core.designsystem.artwork.ArtworkLoader
import app.podium.core.designsystem.artwork.LocalArtworkLoader
import app.podium.core.designsystem.component.LocalImmersiveHost
import app.podium.core.designsystem.component.LocalOverlayHost
import app.podium.core.designsystem.component.LocalScreenInsets
import app.podium.core.designsystem.component.OverlayHost
import app.podium.core.designsystem.component.ScreenInsets
import app.podium.core.designsystem.component.ImmersiveHost
import app.podium.core.designsystem.theme.DisplayTheme
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.designsystem.theme.Spacing
import app.podium.core.interaction.InputRouter
import app.podium.core.interaction.LocalInputRouter
import app.podium.core.interaction.PodiumInput
import app.podium.core.interaction.WheelButton
import app.podium.core.model.QueueUid
import app.podium.core.model.TrackId
import app.podium.player.api.FavoritesRepository
import app.podium.player.api.NowPlayingItem
import app.podium.player.api.PauseReason
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Now Playing's tray sliding away (D-108): it goes once nothing has been touched, and then the
 * Wheel's next move only brings it back — it does not select, pause, or go anywhere — after which the
 * Wheel works as it always does. Off by default, when none of it happens.
 */
@RunWith(AndroidJUnit4::class)
class NowPlayingTrayTest {

    @get:Rule
    val compose = createComposeRule()

    private val router = InputRouter()
    private val controller = FakeController()

    /** Whatever the screen does not handle is the global handler's, as the shell wires it (ADR-011). */
    private val globals = mutableListOf<PodiumInput>()

    private fun show(autoHide: Boolean) {
        FakeVolume.steps.clear()
        RuntimeEnvironment.setQualifiers("w411dp-h891dp-port-420dpi")
        router.global = { input ->
            globals += input
            true
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PodiumTheme(darkTheme = true, displayTheme = DisplayTheme.CARBON) {
                CompositionLocalProvider(
                    LocalInputRouter provides router,
                    LocalOverlayHost provides OverlayHost(),
                    LocalArtworkLoader provides NoArtwork,
                    LocalImmersiveHost provides ImmersiveHost(),
                    LocalScreenInsets provides ScreenInsets(top = Spacing.xxl, bottom = Spacing.edge),
                ) {
                    Box(Modifier.fillMaxSize()) {
                        NowPlayingScreen(controller, FakeVolume, FakeFavorites, onUpNext = {}, autoHideTray = autoHide)
                    }
                }
            }
        }
        settle()
    }

    /** Long enough for the tray's own spring to have arrived, and far short of its idle time. */
    private fun settle() {
        compose.mainClock.advanceTimeBy(900)
        compose.waitForIdle()
    }

    /** Long enough that the tray has certainly gone, and its slide has finished. */
    private fun waitOut() {
        compose.mainClock.advanceTimeBy(9_000)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
    }

    private fun press(button: WheelButton): Boolean {
        val consumed = router.dispatch(PodiumInput.Press(button))
        settle()
        return consumed
    }

    /** Where the tray's first action sits, measured from the top of everything. */
    private fun trayTop(): Float = compose.onNodeWithContentDescription("Shuffle").fetchSemanticsNode().boundsInRoot.top

    @Test
    fun `left alone, the tray slides away and the first thing the Wheel does is bring it back`() {
        show(autoHide = true)
        val home = trayTop()
        waitOut()
        assertTrue(trayTop() > home + 40f, "the tray has gone down out of the way: $home → ${trayTop()}")

        // Whatever the Wheel does next only brings it back. Nothing is selected and nothing is played.
        assertTrue(press(WheelButton.CENTER), "the Wheel's wake is the screen's, not the globals'")
        assertEquals(emptyList(), controller.calls, "nothing was played or changed")
        assertEquals(emptyList<PodiumInput>(), globals, "and nothing fell through to the globals")
        assertTrue(trayTop() < home + 1f, "and the tray is back where it was: ${trayTop()}")
    }

    @Test
    fun `only once the tray is up does the Wheel turn anything`() {
        show(autoHide = true)
        waitOut()
        // Hidden, a turn of the Wheel brings the tray back and moves nothing else.
        router.dispatch(PodiumInput.Rotate(detents = 3))
        settle()
        assertEquals(emptyList(), FakeVolume.steps, "the volume did not move while the tray was away")
        // Up again, the same turn does what it always does.
        router.dispatch(PodiumInput.Rotate(detents = 3))
        settle()
        assertTrue(FakeVolume.steps.isNotEmpty(), "the Wheel turns the volume once the tray is up")
    }

    @Test
    fun `a hidden tray swallows play and pause and going back, and lets nothing through`() {
        show(autoHide = true)
        waitOut()
        assertTrue(press(WheelButton.PLAY_PAUSE), "the wake takes the press")
        assertEquals(emptyList(), controller.calls, "the music did not stop or start")
        assertEquals(emptyList<PodiumInput>(), globals, "the global play/pause never saw it")

        waitOut()
        assertTrue(press(WheelButton.MENU), "and Menu wakes it instead of going back")
        assertEquals(emptyList<PodiumInput>(), globals, "the global back never saw it")
    }

    @Test
    fun `turned off, the tray never goes`() {
        show(autoHide = false)
        val home = trayTop()
        waitOut()
        assertEquals(home, trayTop(), "it stays exactly where it was")
        // And the Wheel is live from the first press: Center steps into scrubbing, nothing is swallowed.
        assertTrue(press(WheelButton.CENTER))
        assertEquals(emptyList<PodiumInput>(), globals)
    }

    @Test
    fun `touching the screen puts the tray's clock back`() {
        show(autoHide = true)
        val home = trayTop()
        // Most of the way to going, then a tap on the orb: it starts counting again.
        compose.mainClock.advanceTimeBy(6_000)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Play").performClick()
        settle()
        compose.mainClock.advanceTimeBy(4_000)
        compose.waitForIdle()
        assertTrue(trayTop() < home + 1f, "still up, four seconds after the tap: ${trayTop()}")
    }

    private class FakeController : PlaybackController {
        val calls = mutableListOf<String>()
        private val item = NowPlayingItem(
            uid = QueueUid("q1"),
            trackId = TrackId("test|q1"),
            title = "Harbour Lights",
            artistDisplay = "Field Recordings Co.",
            albumTitle = null,
            artworkUri = null,
            durationMs = 214_000,
            indexInQueue = 0,
            queueSize = 1,
            servedByDisplayName = "On this device",
            resolutionPath = null,
        )
        private val state = MutableStateFlow(
            PlaybackSnapshot(status = PlaybackStatus.Paused(PauseReason.USER), intent = PlayIntent.PAUSE, item = item),
        )
        override val snapshot: StateFlow<PlaybackSnapshot> = state
        override val queue: StateFlow<QueueView> = MutableStateFlow(
            QueueView(
                entries = listOf(QueueEntry(item.uid, item.trackId, item.title, item.artistDisplay, null, item.durationMs, QueueOrigin.CONTEXT, true)),
                currentIndex = 0,
            ),
        )
        override fun positionMs() = 63_000L
        override fun play() { calls += "play" }
        override fun pause() { calls += "pause" }
        override fun togglePlayPause() { calls += "toggle" }
        override fun next() { calls += "next" }
        override fun previous() { calls += "previous" }
        override fun seekTo(positionMs: Long) { calls += "seekTo" }
        override fun setRepeat(mode: RepeatMode) { calls += "repeat" }
        override fun setShuffle(enabled: Boolean) { calls += "shuffle" }
        override fun playContext(tracks: List<TrackId>, startIndex: Int, contextLabel: String?, shuffle: Boolean, radio: Boolean) = Unit
        override fun playNext(tracks: List<TrackId>) = Unit
        override fun addToQueue(tracks: List<TrackId>) = Unit
        override fun move(uid: QueueUid, toIndex: Int) = Unit
        override fun remove(uids: Set<QueueUid>) = Unit
        override fun skipTo(uid: QueueUid) = Unit
        override fun clearUpcoming() = Unit
    }

    private object FakeVolume : VolumeController {
        val steps = mutableListOf<Int>()
        override val state: StateFlow<VolumeState> = MutableStateFlow(VolumeState(level = 6, max = 15, isFixed = false))
        override fun step(delta: Int) {
            steps += delta
        }
    }

    private object FakeFavorites : FavoritesRepository {
        override val favorites: StateFlow<Set<TrackId>> = MutableStateFlow(emptySet())
        override fun toggle(id: TrackId) = Unit
    }

    private object NoArtwork : ArtworkLoader {
        override suspend fun load(uri: String, sizePx: Int): ImageBitmap? = null
        override fun peek(uri: String, sizePx: Int): ImageBitmap? = null
    }
}
