package app.podium.feature.nowplaying

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
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
import app.podium.core.model.AlbumId
import app.podium.core.model.AlbumRef
import app.podium.core.model.ArtistCredit
import app.podium.core.model.ArtistId
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
import kotlin.test.assertEquals

/**
 * Now Playing ▸ More for a song whose pages arrive late (D-80): opening the menu before the
 * lookup has them asks again, and the next opening lists them.
 */
@RunWith(AndroidJUnit4::class)
class NowPlayingGoToTest {

    @get:Rule
    val compose = createComposeRule()

    private val source = SourceId("online")
    private val id = TrackId.of(source, "v1")
    private val item = NowPlayingItem(
        uid = QueueUid("q1"), trackId = id, title = "Stay", artistDisplay = "The Kid LAROI, Justin Bieber",
        albumTitle = "F*ck Love 3", artworkUri = null, durationMs = 140_000, indexInQueue = 0, queueSize = 1,
        servedByDisplayName = null, resolutionPath = null,
    )
    private val bare = Track(
        id = id, source = SourceRef(source, "v1"), title = "Stay",
        artists = listOf(ArtistCredit("The Kid LAROI, Justin Bieber")), album = AlbumRef("F*ck Love 3"),
    )
    private val full = bare.copy(
        artists = listOf(ArtistCredit("The Kid LAROI", id = ArtistId.of(source, "UCkl")), ArtistCredit("Justin Bieber", id = ArtistId.of(source, "UCjb"))),
        album = AlbumRef("F*ck Love 3", AlbumId.of(source, "MPREb_fl3")),
    )

    @Test
    fun `the menu opened before the song's pages arrive asks again, and then lists them`() {
        var lookups = 0
        val router = InputRouter()
        val overlay = OverlayHost()
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
                            // The first lookup comes back bare (still out, or failed); the next has the pages.
                            trackOf = { lookups++; if (lookups == 1) bare else full },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        fun openMenu(): List<String> {
            compose.runOnIdle { router.dispatch(PodiumInput.LongPress(WheelButton.CENTER)) }
            compose.waitForIdle()
            return overlay.menu!!.actions.map { it.label }.also { compose.runOnIdle { overlay.dismiss() } }
        }
        assertEquals(emptyList(), openMenu().filter { it.startsWith("Go to") })
        assertEquals(listOf("Go to album", "Go to The Kid LAROI", "Go to Justin Bieber"), openMenu().filter { it.startsWith("Go to") })
        assertEquals(2, lookups)
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
