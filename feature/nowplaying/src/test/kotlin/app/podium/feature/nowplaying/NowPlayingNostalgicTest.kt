package app.podium.feature.nowplaying

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.podium.core.designsystem.component.LocalOverlayHost
import app.podium.core.designsystem.component.LocalScreenInsets
import app.podium.core.designsystem.component.OverlayHost
import app.podium.core.designsystem.component.ScreenInsets
import app.podium.core.designsystem.glass.GlassTier
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.interaction.InputRouter
import app.podium.core.interaction.LocalInputRouter
import app.podium.core.interaction.PodiumInput
import app.podium.core.interaction.WheelButton
import app.podium.core.model.AlbumRef
import app.podium.core.model.ArtistCredit
import app.podium.core.model.QueueUid
import app.podium.core.model.SourceId
import app.podium.core.model.SourceRef
import app.podium.core.model.Track
import app.podium.core.model.TrackId
import app.podium.player.api.FavoritesRepository
import app.podium.player.api.NowPlayingItem
import app.podium.player.api.PauseReason
import app.podium.player.api.PlayIntent
import app.podium.player.api.PlaybackController
import app.podium.player.api.PlaybackSnapshot
import app.podium.player.api.PlaybackStatus
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Now Playing's nostalgic screen and its neighbours (D-109): no playback keys on the display, the
 * vinyl's visualiser in their place, everything relaxing down when the tray slides away, and Add to
 * playlist offered for an online song as well as one of the library's.
 */
@RunWith(AndroidJUnit4::class)
class NowPlayingNostalgicTest {

    @get:Rule
    val compose = createComposeRule()

    private val source = SourceId("online")
    private val id = TrackId.of(source, "v1")
    private val item = NowPlayingItem(
        uid = QueueUid("q1"), trackId = id, title = "Stay", artistDisplay = "The Kid LAROI",
        albumTitle = "F*ck Love 3", artworkUri = null, durationMs = 140_000, indexInQueue = 0, queueSize = 1,
        servedByDisplayName = null, resolutionPath = null,
    )
    private val track = Track(
        id = id, source = SourceRef(source, "v1"), title = "Stay",
        artists = listOf(ArtistCredit("The Kid LAROI")), album = AlbumRef("F*ck Love 3"),
    )

    private val router = InputRouter()
    private val overlay = OverlayHost()

    private fun show(
        nostalgic: Boolean = false,
        autoHideTray: Boolean = false,
        environment: String? = null,
        onAddToPlaylist: ((TrackId) -> Unit)? = null,
        onAddOnlineToPlaylist: ((Track) -> Unit)? = null,
    ) {
        RuntimeEnvironment.setQualifiers("w411dp-h891dp-port-420dpi")
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PodiumTheme(darkTheme = true, glassTier = GlassTier.Solid) {
                CompositionLocalProvider(
                    LocalInputRouter provides router,
                    LocalOverlayHost provides overlay,
                    LocalScreenInsets provides ScreenInsets(top = 24.dp, bottom = 8.dp),
                ) {
                    Box(Modifier.fillMaxSize()) {
                        NowPlayingScreen(
                            OneSong(item), FixedVolume, NoFavorites, onUpNext = {},
                            environmentOf = { environment },
                            onAddToPlaylist = onAddToPlaylist,
                            onAddOnlineToPlaylist = onAddOnlineToPlaylist,
                            trackOf = { track },
                            autoHideTray = autoHideTray,
                            nostalgic = nostalgic,
                        )
                    }
                }
            }
        }
        settle()
    }

    /**
     * Long enough for the tray's spring to have arrived, and far short of its idle time. Advanced in
     * steps: with the clock held and no loop of the screen's own running, a spring needs a frame
     * pumped after the state that started it before time carries it.
     */
    private fun settle() {
        repeat(4) {
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(250)
            compose.waitForIdle()
        }
    }

    private fun waitOut() {
        compose.mainClock.advanceTimeBy(9_000)
        compose.waitForIdle()
        settle()
    }

    /** Where the song's title sits: it rides the block that relaxes with the tray. */
    private fun titleTop(): Float = compose.onNodeWithText("Stay").fetchSemanticsNode().boundsInRoot.top

    /** Where the tray itself sits: it slides away on the same spring the content relaxes on. */
    private fun trayTop(): Float = compose.onNodeWithContentDescription("Shuffle").fetchSemanticsNode().boundsInRoot.top

    private fun menu(): List<String> {
        compose.runOnIdle { router.dispatch(PodiumInput.LongPress(WheelButton.CENTER)) }
        compose.waitForIdle()
        return overlay.menu!!.actions.map { it.label }.also { compose.runOnIdle { overlay.dismiss() } }
    }

    @Test
    fun `the nostalgic screen has no playback keys, and the visualiser in their place`() {
        show(nostalgic = true)
        compose.onNodeWithTag(VisualiserTestTag).assertIsDisplayed()
        // The orb and the two transport keys are the playback controls: none of them is here.
        for (key in listOf("Play", "Pause", "Previous track", "Next track")) {
            compose.onNodeWithContentDescription(key).assertDoesNotExist()
        }
        // Everything else the screen is for is still there.
        compose.onNodeWithContentDescription("Artwork").assertExists()
        compose.onNodeWithText("Stay").assertExists()
        compose.onNodeWithContentDescription("Shuffle").assertExists()
    }

    @Test
    fun `the new screen keeps its keys and has no visualiser`() {
        show(nostalgic = false)
        compose.onNodeWithContentDescription("Play").assertExists()
        compose.onNodeWithContentDescription("Previous track").assertExists()
        compose.onNodeWithContentDescription("Next track").assertExists()
        compose.onNodeWithTag(VisualiserTestTag).assertDoesNotExist()
    }

    @Test
    fun `when the tray slides away the new screen relaxes down, and draws back up with it`() = relaxes(nostalgic = false)

    @Test
    fun `when the tray slides away the nostalgic screen relaxes down too`() = relaxes(nostalgic = true)

    private fun relaxes(nostalgic: Boolean) {
        show(nostalgic = nostalgic, autoHideTray = true)
        val home = titleTop()
        val trayHome = trayTop()
        waitOut()
        val relaxed = titleTop()
        assertTrue(relaxed > home + 20f, "the screen relaxes down into the tray's place ($home -> $relaxed)")
        // The Wheel brings the tray back (D-108) and everything draws up with it.
        compose.runOnIdle { router.dispatch(PodiumInput.Press(WheelButton.CENTER)) }
        settle()
        assertTrue(
            titleTop() < home + 1f,
            "and back where it was (title home $home, relaxed $relaxed, now ${titleTop()}; tray home $trayHome, now ${trayTop()})",
        )
    }

    @Test
    fun `an online song goes into one of ONLINE's playlists`() {
        var added: Track? = null
        var localAsked: TrackId? = null
        show(
            environment = "Online",
            onAddToPlaylist = { localAsked = it },
            onAddOnlineToPlaylist = { added = it },
        )
        val actions = menu()
        assertTrue("Add to playlist" in actions, "offered for an online song: $actions")
        // And it is ONLINE's door, not the library's.
        compose.runOnIdle { router.dispatch(PodiumInput.LongPress(WheelButton.CENTER)) }
        compose.waitForIdle()
        compose.runOnIdle { overlay.menu!!.actions.first { it.label == "Add to playlist" }.onSelect() }
        compose.waitForIdle()
        assertEquals(track, added, "the song went to ONLINE's playlists")
        assertNull(localAsked, "and never to the library's")
    }

    @Test
    fun `a song of the library still goes into the library's playlists`() {
        var added: Track? = null
        var localAsked: TrackId? = null
        show(
            environment = null,
            onAddToPlaylist = { localAsked = it },
            onAddOnlineToPlaylist = { added = it },
        )
        compose.runOnIdle { router.dispatch(PodiumInput.LongPress(WheelButton.CENTER)) }
        compose.waitForIdle()
        compose.runOnIdle { overlay.menu!!.actions.first { it.label == "Add to playlist" }.onSelect() }
        compose.waitForIdle()
        assertEquals(id, localAsked, "the song went to the library's playlists")
        assertNull(added, "and never to ONLINE's")
    }

    @Test
    fun `without a way to add, the menu says nothing about playlists`() {
        show(environment = "Online", onAddToPlaylist = null, onAddOnlineToPlaylist = null)
        assertTrue("Add to playlist" !in menu(), "nothing offered when there is nowhere to put it")
    }

    private class OneSong(item: NowPlayingItem) : PlaybackController {
        override val snapshot: StateFlow<PlaybackSnapshot> =
            MutableStateFlow(PlaybackSnapshot(status = PlaybackStatus.Paused(PauseReason.USER), intent = PlayIntent.PAUSE, item = item))
        override val queue: StateFlow<QueueView> = MutableStateFlow(QueueView())
        override fun positionMs() = 0L
        override fun play() = Unit
        override fun pause() = Unit
        override fun togglePlayPause() = Unit
        override fun next() = Unit
        override fun previous() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun setRepeat(mode: RepeatMode) = Unit
        override fun setShuffle(enabled: Boolean) = Unit
        override fun playContext(tracks: List<TrackId>, startIndex: Int, contextLabel: String?, shuffle: Boolean, radio: Boolean) = Unit
        override fun playNext(tracks: List<TrackId>) = Unit
        override fun addToQueue(tracks: List<TrackId>) = Unit
        override fun move(uid: QueueUid, toIndex: Int) = Unit
        override fun remove(uids: Set<QueueUid>) = Unit
        override fun skipTo(uid: QueueUid) = Unit
        override fun clearUpcoming() = Unit
    }

    private object FixedVolume : VolumeController {
        override val state: StateFlow<VolumeState> = MutableStateFlow(VolumeState(level = 6, max = 15, isFixed = false))
        override fun step(delta: Int) = Unit
    }

    private object NoFavorites : FavoritesRepository {
        override val favorites: StateFlow<Set<TrackId>> = MutableStateFlow(emptySet())
        override fun toggle(id: TrackId) = Unit
    }
}
