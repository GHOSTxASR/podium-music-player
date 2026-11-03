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
import app.podium.core.designsystem.component.LocalScreenInsets
import app.podium.core.designsystem.component.OverlayHost
import app.podium.core.designsystem.component.PodWheel
import app.podium.core.designsystem.component.ScreenInsets
import app.podium.core.designsystem.glass.GlassTier
import app.podium.core.designsystem.shell.DeviceAppearance
import app.podium.core.designsystem.shell.DeviceBody
import app.podium.core.designsystem.shell.DeviceLayout
import app.podium.core.designsystem.shell.DisplayTestTag
import app.podium.core.designsystem.shell.FinishPreset
import app.podium.core.designsystem.shell.PowerButton
import app.podium.core.designsystem.shell.ScreenHeader
import app.podium.core.designsystem.shell.ScreenHeaderHeight
import app.podium.core.designsystem.shell.StatusTestTag
import app.podium.core.designsystem.shell.palette
import app.podium.core.designsystem.theme.DisplayTheme
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.designsystem.theme.Spacing
import app.podium.core.designsystem.artwork.rememberArtworkState
import app.podium.core.designsystem.tonal.AlbumAtmosphere
import app.podium.core.designsystem.tonal.LocalAlbumTone
import app.podium.core.designsystem.tonal.rememberAlbumTone
import app.podium.core.designsystem.tonal.rememberCoverAnalysis
import app.podium.core.interaction.InputRouter
import app.podium.core.interaction.LocalInputRouter
import app.podium.core.model.QueueUid
import app.podium.core.model.SourceId
import app.podium.core.model.TrackId
import app.podium.sources.api.ResolutionPath
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
import app.podium.core.designsystem.type.DisplayFont
import app.podium.core.designsystem.theme.VirtualDisplay
import app.podium.core.common.PodiumError
import app.podium.player.api.Recovery
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Now Playing inside the device at several screen sizes and with awkward artwork, rendered with
 * Robolectric's native graphics. Each case checks the hard rule — nothing on the display escapes
 * the display — and saves a screenshot to build/screenshots/ for review.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NowPlayingLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    private enum class Art { SQUARE, PORTRAIT, LANDSCAPE, BRIGHT, MISSING }

    @Test fun phoneSquare() = check("phone-square", PHONE, Art.SQUARE)

    @Test fun phonePortraitArt() = check("phone-portrait-art", PHONE, Art.PORTRAIT)

    @Test fun phoneLandscapeArt() = check("phone-landscape-art", PHONE, Art.LANDSCAPE)

    @Test fun phoneBrightArtLongTitle() = check("phone-bright-long-title", PHONE, Art.BRIGHT, longTitle = true)

    @Test fun smallPhoneMissingArt() = check("small-missing-art", SMALL, Art.MISSING)

    @Test fun compactPhone() = check("compact-360x640", COMPACT, Art.SQUARE)

    @Test fun largeDevice() = check("large-square", LARGE, Art.SQUARE)

    /** The nostalgic screen (D-109): no playback keys, the visualiser in their place. */
    @Test fun nostalgicPhone() = check("nostalgic-square", PHONE, Art.SQUARE, nostalgic = true)

    @Test fun nostalgicCarbon() = check("nostalgic-carbon", PHONE, Art.SQUARE, theme = DisplayTheme.CARBON, nostalgic = true)

    /** Another source's copy is playing: Podium knows which (D-36), the listener isn't told. */
    @Test fun fallbackCopyIsNotNamed() = check("phone-fallback", PHONE, Art.SQUARE, fallback = true)

    /** Faces set larger than Classic keep both status lines whole: the error and "Online" aren't cut. */
    @Test fun handwrittenKeepsTheStatusLines() = largeFace(DisplayFont.HANDWRITTEN)

    @Test fun scriptKeepsTheStatusLines() = largeFace(DisplayFont.SCRIPT)

    @Test fun gothicKeepsTheStatusLines() = largeFace(DisplayFont.GOTHIC)

    @Test fun timesKeepsTheStatusLines() = largeFace(DisplayFont.TIMES)

    private fun largeFace(font: DisplayFont) {
        check(
            "phone-error-${font.name.lowercase()}", PHONE, Art.SQUARE,
            status = PlaybackStatus.Error(PodiumError.NotPlayable("stream")), environment = "Online", font = font,
        )
        assertWhole("can't play")
    }

    /** While a refused stream is replaced the note says so (not an error); then the song goes on. */
    @Test fun aRefreshingStreamSaysSo() {
        check("phone-refreshing", PHONE, Art.SQUARE, status = PlaybackStatus.Buffering, recovery = Recovery.REFRESHING_STREAM, environment = "Online")
        compose.onNodeWithText("The stream dropped. Getting it again…").assertExists()
    }

    // Carbon (D-101): the same checks on the tonal display, with the album's atmosphere and tone.
    @Test fun carbonPhoneSquare() = check("carbon-phone-square", PHONE, Art.SQUARE, theme = DisplayTheme.CARBON)

    @Test fun carbonPhonePlaying() = check("carbon-phone-playing", PHONE, Art.LANDSCAPE, theme = DisplayTheme.CARBON, status = PlaybackStatus.Playing)

    @Test fun carbonBrightArtLongTitle() = check("carbon-bright-long-title", PHONE, Art.BRIGHT, longTitle = true, theme = DisplayTheme.CARBON)

    @Test fun carbonPortraitArt() = check("carbon-portrait-art", PHONE, Art.PORTRAIT, theme = DisplayTheme.CARBON, environment = "Online")

    @Test fun carbonMissingArt() = check("carbon-small-missing-art", SMALL, Art.MISSING, theme = DisplayTheme.CARBON)

    @Test fun carbonCompact() = check("carbon-compact-360x640", COMPACT, Art.SQUARE, theme = DisplayTheme.CARBON)

    @Test fun carbonLarge() = check("carbon-large-square", LARGE, Art.SQUARE, theme = DisplayTheme.CARBON)

    @Test fun carbonErrorInALargeFace() {
        check(
            "carbon-phone-error-handwritten", PHONE, Art.SQUARE, theme = DisplayTheme.CARBON,
            status = PlaybackStatus.Error(PodiumError.NotPlayable("stream")), environment = "Online", font = DisplayFont.HANDWRITTEN,
        )
        assertWhole("can't play")
    }

    /** Until an online song first plays, the bar says it's loading. */
    @Test fun aLoadingSongSaysSoOnTheBar() {
        check("phone-loading", PHONE, Art.SQUARE, status = PlaybackStatus.Loading, environment = "Online")
        compose.onNodeWithText("Loading").assertExists()
        compose.onNodeWithContentDescription("Song position").assertDoesNotExist()
    }

    /** No line of the text containing [fragment] is cut off (its layout fits its box). */
    private fun assertWhole(fragment: String) {
        val nodes = compose.onAllNodes(hasText(fragment, substring = true, ignoreCase = true), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue(nodes.isNotEmpty(), "\"$fragment\" is shown")
        for (node in nodes) {
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            assertTrue(layouts.none { it.hasVisualOverflow }, "\"$fragment\" is cut off: " + layouts.joinToString { "${it.size} lines=${it.lineCount} overflowW=${it.didOverflowWidth} overflowH=${it.didOverflowHeight}" })
        }
    }

    private fun check(
        name: String,
        qualifiers: String,
        art: Art,
        longTitle: Boolean = false,
        fallback: Boolean = false,
        status: PlaybackStatus = PlaybackStatus.Paused(PauseReason.USER),
        recovery: Recovery? = null,
        environment: String? = null,
        font: DisplayFont = DisplayFont.CLASSIC,
        theme: DisplayTheme = DisplayTheme.GLASS,
        nostalgic: Boolean = false,
    ) {
        RuntimeEnvironment.setQualifiers(qualifiers)
        val controller = FakeController(art, longTitle, fallback, status, recovery)
        // A playing song's seek bar moves on every frame: the clock is stepped by hand, or it never idles.
        if (status == PlaybackStatus.Playing) compose.mainClock.autoAdvance = false
        compose.setContent {
            PodiumTheme(darkTheme = true, glassTier = GlassTier.Solid, displayTheme = theme, display = VirtualDisplay(font = font)) {
                val palette = DeviceAppearance(FinishPreset.STEEL_GRAY, display = theme).palette(darkTheme = true)
                CompositionLocalProvider(
                    LocalInputRouter provides InputRouter(),
                    LocalOverlayHost provides OverlayHost(),
                    LocalArtworkLoader provides FakeArtwork,
                ) {
                    // Carbon's tone and atmosphere come from the playing cover, as the app provides them.
                    val carbon = theme == DisplayTheme.CARBON
                    val artState = rememberArtworkState(if (carbon) controller.snapshot.value.item?.artworkUri else null, 96.dp)
                    val cover = rememberCoverAnalysis(artState.bitmap, artState.settled)
                    CompositionLocalProvider(LocalAlbumTone provides rememberAlbumTone(cover?.palette)) {
                    Box(Modifier.fillMaxSize()) {
                        DeviceBody(palette, Modifier.fillMaxSize())
                        DeviceLayout(
                            modifier = Modifier.fillMaxSize(),
                            screen = {
                                if (carbon) AlbumAtmosphere(cover?.field, level = { 1f }, Modifier.fillMaxSize())
                                CompositionLocalProvider(LocalScreenInsets provides ScreenInsets(top = ScreenHeaderHeight, bottom = Spacing.edge)) {
                                    NowPlayingScreen(controller, FakeVolume, FakeFavorites, onUpNext = {}, environmentOf = { environment }, nostalgic = nostalgic)
                                }
                                ScreenHeader("Now Playing", canGoBack = true, onBack = {}, playing = status == PlaybackStatus.Playing)
                            },
                            wheel = { d -> PodWheel(onInput = {}, diameter = d, palette = palette) },
                            powerButton = { PowerButton(on = true, palette = palette, onToggle = {}) },
                        )
                    }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        if (theme == DisplayTheme.CARBON) {
            // The cover's analysis runs off the main thread: give it its moment, then let the tone settle.
            Thread.sleep(400)
            compose.mainClock.advanceTimeBy(2_000)
            compose.waitForIdle()
        }

        val displayNode = compose.onNodeWithTag(DisplayTestTag, useUnmergedTree = true).fetchSemanticsNode()
        val display = displayNode.boundsInRoot
        val onScreen = displayNode.descendants().filter { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it in DISPLAY_CONTENT || it == "Pause" }
        }
        val found = onScreen.flatMap { it.config[SemanticsProperties.ContentDescription] }.toSet()
        // While a song loads, the bar is replaced by the word; a playing song offers Pause; and the
        // nostalgic screen has no playback keys at all (D-109), which is the whole point of it.
        val expected = (if (status == PlaybackStatus.Loading) DISPLAY_CONTENT - "Song position" else DISPLAY_CONTENT)
            .let { if (status == PlaybackStatus.Playing) it - "Play" + "Pause" else it }
            .let { if (nostalgic) it - TRANSPORT else it }
        if (nostalgic) assertTrue(found.none { it in TRANSPORT }, "the nostalgic screen still has keys: ${found.filter { it in TRANSPORT }} ($name)")
        assertTrue(found.containsAll(expected), "missing on the display: ${expected - found} ($name)")
        // Every node drawn by the display, labelled or not, stays inside it.
        displayNode.descendants().forEach { node ->
            val label = node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
                ?: node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text ?: "node ${node.id}"
            assertTrue(display.contains(node.boundsInRoot), "$label at ${node.boundsInRoot} escapes the display $display ($name)")
        }

        assertEdgeMargins(display, stacked = qualifiers != COMPACT, name)

        // Normal Now Playing never names a source, even when another source's copy is playing (D-36).
        val texts = displayNode.descendants().flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
        assertTrue(texts.none { PROVIDER in it || "Playing from" in it }, "a source is named on Now Playing: $texts ($name)")
        if (fallback) assertEquals(PROVIDER, controller.snapshot.value.item?.servedByDisplayName, "…but the session still knows who serves it")

        val out = File("build/screenshots").apply { mkdirs() }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * One edge margin (D-103): the back key's housing and the status housings sit [Spacing.edge]
     * from the display's top and sides; stacked, the tray sits the same from its foot and sides.
     */
    private fun assertEdgeMargins(display: Rect, stacked: Boolean, name: String) {
        val edge = with(compose.density) { Spacing.edge.toPx() }
        // The back key's touch target reaches 4 dp past its 32 dp housing on every side.
        val overhang = with(compose.density) { 4.dp.toPx() }
        val trayPadding = with(compose.density) { 4.dp.toPx() }
        fun near(actual: Float, what: String) = assertTrue(kotlin.math.abs(actual - edge) <= 1.5f, "$what is ${actual}px from the display's edge, not $edge ($name)")
        // The title bar's back key (the Wheel's MENU says "Back" too, below the display).
        val back = compose.onAllNodesWithContentDescription("Back").fetchSemanticsNodes().map { it.boundsInRoot }.first { display.contains(it) }
        near(back.left + overhang - display.left, "the back key")
        near(back.top + overhang - display.top, "the back key's top")
        val status = compose.onNodeWithTag(StatusTestTag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        near(display.right - status.right, "the status housings")
        near(status.top - display.top, "the status housings' top")
        if (!stacked) return
        val first = compose.onNodeWithContentDescription("Shuffle").fetchSemanticsNode().boundsInRoot
        val last = compose.onNodeWithContentDescription("More").fetchSemanticsNode().boundsInRoot
        near(first.left - trayPadding - display.left, "the tray")
        near(display.right - (last.right + trayPadding), "the tray's end")
        near(display.bottom - first.bottom, "the tray's foot")
    }

    private fun SemanticsNode.descendants(): List<SemanticsNode> = children.flatMap { listOf(it) + it.descendants() }

    private fun Rect.contains(other: Rect) =
        other.left >= left - 0.5f && other.top >= top - 0.5f && other.right <= right + 0.5f && other.bottom <= bottom + 0.5f

    private class FakeController(
        art: Art,
        longTitle: Boolean,
        fallback: Boolean = false,
        status: PlaybackStatus = PlaybackStatus.Paused(PauseReason.USER),
        recovery: Recovery? = null,
    ) : PlaybackController {
        private val item = NowPlayingItem(
            uid = QueueUid("q1"),
            trackId = TrackId("test|1"),
            title = if (longTitle) "A Remarkably Long Song Title That Keeps Going (Extended Version)" else "Harbour Lights",
            artistDisplay = "Field Recordings Co.",
            albumTitle = "Coastline",
            artworkUri = if (art == Art.MISSING) null else "test://${art.name.lowercase()}",
            durationMs = 214_000,
            indexInQueue = 2,
            queueSize = 11,
            servedByDisplayName = if (fallback) PROVIDER else "On this device",
            resolutionPath = if (fallback) ResolutionPath.EXACT_FALLBACK else null,
            servedBy = if (fallback) SourceId("fake-provider") else null,
        )
        override val snapshot: StateFlow<PlaybackSnapshot> = MutableStateFlow(
            PlaybackSnapshot(status = status, intent = if (status == PlaybackStatus.Playing) PlayIntent.PLAY else PlayIntent.PAUSE, item = item, recovery = recovery),
        )
        override val queue: StateFlow<QueueView> = MutableStateFlow(QueueView())
        override fun positionMs() = 63_000L
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

    private object FakeVolume : VolumeController {
        override val state: StateFlow<VolumeState> = MutableStateFlow(VolumeState(level = 6, max = 15, isFixed = false))
        override fun step(delta: Int) = Unit
    }

    private object FakeFavorites : FavoritesRepository {
        override val favorites: StateFlow<Set<TrackId>> = MutableStateFlow(setOf(TrackId("test|1")))
        override fun toggle(id: TrackId) = Unit
    }

    /** Generated covers only — no third-party artwork in the repository. */
    private object FakeArtwork : ArtworkLoader {
        override suspend fun load(uri: String, sizePx: Int): ImageBitmap? = cover(uri)
        override fun peek(uri: String, sizePx: Int): ImageBitmap? = cover(uri)

        private fun cover(uri: String): ImageBitmap? {
            val (w, h, from, to) = when (uri.substringAfter("test://")) {
                "square" -> Quad(600, 600, 0xFF1D3557.toInt(), 0xFFE63946.toInt())
                "portrait" -> Quad(400, 600, 0xFF2D6A4F.toInt(), 0xFFD8F3DC.toInt())
                "landscape" -> Quad(640, 360, 0xFF3A0CA3.toInt(), 0xFFF72585.toInt())
                "bright" -> Quad(600, 600, 0xFFFFFFFF.toInt(), 0xFFF1F1EC.toInt())
                else -> return null
            }
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
                shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), from, to, Shader.TileMode.CLAMP)
            })
            return bitmap.asImageBitmap()
        }
    }

    private data class Quad(val w: Int, val h: Int, val from: Int, val to: Int)

    private companion object {
        const val PROVIDER = "Fake Provider"
        const val PHONE = "w411dp-h891dp-port-420dpi"
        const val SMALL = "w320dp-h568dp-port-xhdpi"
        const val COMPACT = "w360dp-h640dp-port-xhdpi"
        const val LARGE = "w600dp-h960dp-port-xhdpi"

        /** The playback keys, which only the new screen has (D-109). */
        val TRANSPORT = setOf("Previous track", "Play", "Pause", "Next track")

        /** Everything Now Playing puts on the display, by accessibility label. */
        val DISPLAY_CONTENT = setOf(
            "Artwork", "Previous track", "Play", "Next track", "Shuffle", "Repeat", "Favorite", "Up Next", "More", "Song position",
        )
    }
}
