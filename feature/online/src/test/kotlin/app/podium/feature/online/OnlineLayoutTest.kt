package app.podium.feature.online

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.podium.core.common.Outcome
import app.podium.core.common.PodiumError
import app.podium.core.designsystem.artwork.ArtworkLoader
import app.podium.core.designsystem.artwork.LocalArtworkLoader
import app.podium.core.designsystem.component.LocalOverlayHost
import app.podium.core.designsystem.component.LocalScreenInsets
import app.podium.core.designsystem.component.OverlayHost
import app.podium.core.designsystem.component.PodWheel
import app.podium.core.designsystem.component.ScreenInsets
import app.podium.core.designsystem.shell.DeviceAppearance
import app.podium.core.designsystem.shell.DeviceBody
import app.podium.core.designsystem.shell.DeviceLayout
import app.podium.core.designsystem.shell.DisplayTestTag
import app.podium.core.designsystem.shell.PowerButton
import app.podium.core.designsystem.shell.ScreenHeader
import app.podium.core.designsystem.shell.ScreenHeaderHeight
import app.podium.core.designsystem.shell.palette
import app.podium.core.designsystem.theme.DisplayTheme
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.interaction.InputRouter
import app.podium.core.interaction.LocalInputRouter
import app.podium.core.model.ArtistId
import app.podium.core.model.ArtworkRef
import app.podium.core.model.PlaylistId
import app.podium.core.model.SourceId
import app.podium.core.model.Track
import app.podium.core.model.TrackId
import app.podium.sources.api.ArtistDetail
import app.podium.sources.api.ArtistSummary
import app.podium.sources.api.PlaylistDetail
import app.podium.sources.api.PlaylistSummary
import app.podium.sources.api.SearchResults
import app.podium.sources.api.Shelf
import app.podium.sources.api.ShelfPage
import app.podium.sources.testing.track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertTrue

/**
 * The ONLINE screens (D-34) on the paper, in Carbon and Glass at two device sizes: nothing the
 * display draws may leave it. Screenshots go to build/screenshots/ for review.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnlineLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    @Test fun carbonMenu() = check("online-carbon-menu", PHONE, DisplayTheme.CARBON, "Online") { OnlineMenuScreen(Repo, {}) }

    @Test fun glassHome() = check("online-glass-home", PHONE, DisplayTheme.GLASS, "Home") { OnlineHomeScreen(Repo, {}) }

    @Test fun carbonShelfSmall() = check("online-carbon-shelf-small", SMALL, DisplayTheme.CARBON, "Trending this week") {
        OnlineShelfScreen(OnlinePlace.Shelf("week", "Trending this week"), Repo, Actions, {})
    }

    @Test fun boneArtist() = check("online-bone-artist", PHONE, DisplayTheme.BONE, "Northern Sines") {
        OnlineArtistScreen(OnlinePlace.Artist(ArtistId.of(Audius, "ns"), "Northern Sines"), Repo, Actions, {})
    }

    @Test fun carbonAlbum() = check("online-carbon-album", PHONE, DisplayTheme.CARBON, "Coastline") {
        OnlineCollectionScreen(OnlinePlace.Collection(PlaylistId.of(Audius, "p1"), "Coastline", true), Repo, Actions, {})
    }

    @Test fun glassSearchSmall() = check("online-glass-search-small", SMALL, DisplayTheme.GLASS, "Search") { OnlineSearchScreen(Repo, Actions, {}) }

    @Test fun carbonOffline() = check("online-carbon-offline", SMALL, DisplayTheme.CARBON, "Explore") { OnlineExploreScreen(OfflineRepo, {}) }

    private fun check(name: String, qualifiers: String, theme: DisplayTheme, title: String, screen: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers(qualifiers)
        compose.setContent {
            PodiumTheme(displayTheme = theme) {
                val palette = DeviceAppearance(display = theme).palette(darkTheme = true)
                CompositionLocalProvider(
                    LocalInputRouter provides InputRouter(),
                    LocalOverlayHost provides OverlayHost(),
                    LocalArtworkLoader provides Covers,
                ) {
                    Box(Modifier.fillMaxSize()) {
                        DeviceBody(palette, Modifier.fillMaxSize())
                        DeviceLayout(
                            modifier = Modifier.fillMaxSize(),
                            screen = {
                                CompositionLocalProvider(LocalScreenInsets provides ScreenInsets(top = ScreenHeaderHeight, bottom = 8.dp)) { screen() }
                                ScreenHeader(title, canGoBack = true, onBack = {}, playing = false)
                            },
                            wheel = { d -> PodWheel(onInput = {}, diameter = d, palette = palette) },
                            powerButton = { PowerButton(on = true, palette = palette, onToggle = {}) },
                        )
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()

        val display = compose.onNodeWithTag(DisplayTestTag, useUnmergedTree = true).fetchSemanticsNode()
        display.descendants().forEach { node ->
            if (node.boundsInRoot.isEmpty) return@forEach
            val label = node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
                ?: node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text ?: "node ${node.id}"
            assertTrue(display.boundsInRoot.contains(node.boundsInRoot), "$label at ${node.boundsInRoot} escapes the display ($name)")
        }
        val out = File("build/screenshots").apply { mkdirs() }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun SemanticsNode.descendants(): List<SemanticsNode> = children.flatMap { listOf(it) + it.descendants() }

    /** Within a pixel: rounding at the edges isn't escaping. */
    private fun androidx.compose.ui.geometry.Rect.contains(other: androidx.compose.ui.geometry.Rect) =
        other.left >= left - 1 && other.top >= top - 1 && other.right <= right + 1 && other.bottom <= bottom + 1

    private object Covers : ArtworkLoader {
        private val palettes = listOf(0xFF1D3557 to 0xFFE63946, 0xFF2D6A4F to 0xFFD8F3DC, 0xFF3A0CA3 to 0xFFF72585, 0xFFF4A261 to 0xFF264653)
        override suspend fun load(uri: String, sizePx: Int): ImageBitmap = cover(uri)
        override fun peek(uri: String, sizePx: Int): ImageBitmap = cover(uri)
        private fun cover(uri: String): ImageBitmap {
            val (a, b) = palettes[(uri.hashCode() and 0x7fffffff) % palettes.size]
            val bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).drawRect(0f, 0f, 300f, 300f, Paint().apply { shader = LinearGradient(0f, 0f, 300f, 300f, a.toInt(), b.toInt(), Shader.TileMode.CLAMP) })
            return bitmap.asImageBitmap()
        }
    }

    private open class FakeRepo : OnlineRepository {
        override val source: StateFlow<OnlineSource?> = MutableStateFlow(OnlineSource("Online test", canSearch = true, canBrowse = true, canRecommend = true))
        override suspend fun shelves(): Outcome<List<Shelf>> = Outcome.Success(
            listOf(Shelf("week", "Trending this week", tracks = SONGS), Shelf("underground", "Underground", tracks = SONGS.reversed()), Shelf("playlists", "Popular playlists", playlists = PLAYLISTS)),
        )
        override suspend fun shelf(id: String, offset: Int, limit: Int) = Outcome.Success(if (offset == 0) ShelfPage(tracks = SONGS) else ShelfPage())
        override suspend fun genres(): Outcome<List<String>> = Outcome.Success(listOf("Electronic", "Hip-Hop/Rap", "Lo-fi"))
        override suspend fun genre(name: String, offset: Int, limit: Int) = Outcome.Success(SONGS)
        override suspend fun search(text: String, offset: Int, limit: Int) = Outcome.Success(SearchResults(tracks = SONGS))
        override suspend fun artist(id: ArtistId): Outcome<ArtistDetail> = Outcome.Success(
            ArtistDetail(ArtistSummary(id, "Northern Sines", ArtworkRef(Audius, "artist"), trackCount = 42), albums = emptyList(), tracks = SONGS, playlists = PLAYLISTS),
        )
        override suspend fun relatedArtists(id: ArtistId) = Outcome.Success(listOf(ArtistSummary(ArtistId.of(Audius, "fr"), "Field Recordings Co.", ArtworkRef(Audius, "fr"), 12)))
        override suspend fun collection(id: PlaylistId): Outcome<PlaylistDetail> = Outcome.Success(PlaylistDetail(PLAYLISTS.first().copy(isAlbum = true, year = 2024), SONGS))
        override val likedIds: StateFlow<Set<TrackId>> = MutableStateFlow(setOf(SONGS.first().id))
        override val likedTracks: Flow<List<Track>> = flowOf(SONGS.take(3))
        override fun setLiked(track: Track, liked: Boolean) = Unit
        override val playlists: Flow<List<OnlinePlaylist>> = flowOf(listOf(OnlinePlaylist("p", "Road trip", 4)))
        override fun playlist(id: String): Flow<OnlinePlaylistContents?> = flowOf(null)
        override suspend fun createPlaylist(name: String, tracks: List<Track>) = "p"
        override fun renamePlaylist(id: String, name: String) = Unit
        override fun deletePlaylist(id: String) = Unit
        override fun addToPlaylist(id: String, tracks: List<Track>) = Unit
        override fun removeFromPlaylist(id: String, entryId: Long) = Unit
        override fun movePlaylistEntry(id: String, entryId: Long, toIndex: Int) = Unit
        override val recentlyPlayed: Flow<List<Track>> = flowOf(SONGS.drop(2))
        override fun clearHistory() = Unit
    }

    private object Repo : FakeRepo()

    private object OfflineRepo : FakeRepo() {
        override suspend fun genres(): Outcome<List<String>> = Outcome.Failure(PodiumError.Offline)
    }

    private object Actions : OnlineActions {
        override fun play(tracks: List<Track>, index: Int, label: String) = Unit
        override fun shuffle(tracks: List<Track>, label: String) = Unit
        override fun playNext(track: Track) = Unit
        override fun addToQueue(track: Track) = Unit
        override fun startRadio(track: Track) = Unit
        override fun startArtistRadio(artist: ArtistSummary) = Unit
        override fun startGenreRadio(genre: String) = Unit
        override fun share(track: Track) = Unit
        override val nowPlaying: StateFlow<Track?> = MutableStateFlow(null)
    }

    private companion object {
        const val PHONE = "w411dp-h891dp-port-420dpi"
        const val SMALL = "w360dp-h640dp-port-xhdpi"
        val Audius = SourceId("online")

        val SONGS = listOf("Night train", "Coastline, part 1", "Signal path (extended mix)", "Woodland", "Low hum", "Nuit", "Long form", "Tuning fork")
            .mapIndexed { i, t -> track(t, if (i % 2 == 0) "Northern Sines" else "Field Recordings Co.", source = "online", key = "t$i").copy(artwork = ArtworkRef(Audius, "c$i")) }

        val PLAYLISTS = listOf("Coastline", "Late night", "Focus").mapIndexed { i, t ->
            PlaylistSummary(PlaylistId.of(Audius, "p$i"), t, "Curator $i", ArtworkRef(Audius, "p$i"), trackCount = 12 + i)
        }
    }
}
