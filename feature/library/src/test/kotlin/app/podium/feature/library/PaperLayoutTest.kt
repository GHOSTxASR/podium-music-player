package app.podium.feature.library

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
import androidx.compose.ui.geometry.Rect
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
import app.podium.core.designsystem.artwork.ArtworkLoader
import app.podium.core.designsystem.artwork.LocalArtworkLoader
import app.podium.core.designsystem.component.LocalOverlayHost
import app.podium.core.designsystem.component.LocalPaperPeek
import app.podium.core.designsystem.component.LocalScreenInsets
import app.podium.core.designsystem.component.OverlayHost
import app.podium.core.designsystem.component.LocalMiniature
import app.podium.core.designsystem.component.LocalMiniatureFocusKey
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
import app.podium.core.model.AlbumId
import app.podium.core.model.AlbumRef
import app.podium.core.model.ArtworkRef
import app.podium.core.model.SourceId
import app.podium.core.model.TrackId
import app.podium.player.api.FavoritesRepository
import app.podium.sources.testing.track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertTrue

/**
 * The paper composition (D-29, D-30) in every theme at several device sizes: every node the display
 * draws must stay inside it. Screenshots go to build/screenshots/ for review.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PaperLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    @Test fun carbonHome() = check("carbon-home", PHONE, DisplayTheme.CARBON, "Podium") { Home() }

    @Test fun boneHome() = check("bone-home", PHONE, DisplayTheme.BONE, "Podium") { Home() }

    @Test fun carbonMusicSmall() = check("carbon-music-small", SMALL, DisplayTheme.CARBON, "Music", previous = { Home() }, focusKey = "Music") {
        Music()
    }

    @Test fun glassMusic() = check("glass-music", PHONE, DisplayTheme.GLASS, "Music", previous = { Home() }, focusKey = "Music") {
        Music()
    }

    @Test fun boneAlbums() = check("bone-albums", PHONE, DisplayTheme.BONE, "Albums", previous = { Music() }, focusKey = "Albums") {
        AlbumsScreen(Library) {}
    }

    @Test fun carbonAlbum() = check("carbon-album", PHONE, DisplayTheme.CARBON, "Coastline", previous = { AlbumsScreen(Library) {} }, focusKey = ALBUMS.first().first.value) {
        AlbumScreen(Library, ALBUMS.first().first, onPlay = { _, _, _ -> }, onPlayNext = {}, onAddToQueue = {})
    }

    @Test fun glassSongs() = check("glass-songs", PHONE, DisplayTheme.GLASS, "Songs", previous = { Music() }, focusKey = "Songs") {
        SongsScreen(Library, onPlay = { _, _ -> }, onPlayNext = {}, onAddToQueue = {})
    }

    @Test fun carbonCoverFlow() = check("carbon-coverflow", PHONE, DisplayTheme.CARBON, "Cover Flow") { CoverFlowScreen(Library) {} }

    @Test fun boneCoverFlowSmall() = check("bone-coverflow-small", SMALL, DisplayTheme.BONE, "Cover Flow") { CoverFlowScreen(Library) {} }

    @Composable
    private fun Home() = HomeScreen(Library, "test://a0", nowPlayingActive = true, {}, {}, {}, {})

    @Composable
    private fun Music() = MusicScreen(Library, NoFavorites, {}, {}, {}, {}, {}, {})

    private fun check(
        name: String,
        qualifiers: String,
        theme: DisplayTheme,
        title: String,
        previous: (@Composable () -> Unit)? = null,
        focusKey: String? = null,
        screen: @Composable () -> Unit,
    ) {
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
                                CompositionLocalProvider(
                                    LocalScreenInsets provides ScreenInsets(top = ScreenHeaderHeight, bottom = 8.dp),
                                    // The previous column, live, as the shell provides it.
                                    LocalPaperPeek provides previous?.let { prev ->
                                        { m ->
                                            Box(m) {
                                                CompositionLocalProvider(
                                                    LocalMiniature provides true,
                                                    LocalMiniatureFocusKey provides focusKey,
                                                    LocalInputRouter provides InputRouter(),
                                                    LocalPaperPeek provides null,
                                                ) { prev() }
                                            }
                                        }
                                    },
                                ) { screen() }
                                ScreenHeader(title, canGoBack = previous != null, onBack = {}, playing = false)
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
            // A node clipped away entirely reports an empty rect: it isn't drawn anywhere.
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

    private fun Rect.contains(other: Rect) =
        other.left >= left - 0.5f && other.top >= top - 0.5f && other.right <= right + 0.5f && other.bottom <= bottom + 0.5f

    private object Library : LibraryRepository {
        override val songs: StateFlow<LibraryState> = MutableStateFlow(LibraryState.Ready(TRACKS))
        override val pendingActions: StateFlow<List<SourceAction>> = MutableStateFlow(emptyList())
    }

    private object NoFavorites : FavoritesRepository {
        override val favorites: StateFlow<Set<TrackId>> = MutableStateFlow(emptySet())
        override fun toggle(id: TrackId) = Unit
    }

    /** Generated covers only — no third-party artwork in the repository. */
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

    private companion object {
        const val PHONE = "w411dp-h891dp-port-420dpi"
        const val SMALL = "w360dp-h640dp-port-xhdpi"

        val ALBUMS = listOf("Coastline", "Woodland", "Nuit", "Signal Path", "Long Form").mapIndexed { i, t -> AlbumId.of(SourceId("test"), "a$i") to t }

        val TRACKS = ALBUMS.flatMapIndexed { a, (id, album) ->
            (1..4).map { n ->
                track("$album, part $n", artist = if (a % 2 == 0) "Field Recordings Co." else "Northern Sines", key = "a$a-$n")
                    .copy(album = AlbumRef(album, id), artwork = ArtworkRef(SourceId("test"), "a$a"), trackNumber = n)
            }
        }
    }
}
