package app.podium.feature.nowplaying

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.podium.core.designsystem.artwork.ArtworkLoader
import app.podium.core.designsystem.artwork.LocalArtworkLoader
import app.podium.core.designsystem.component.LocalOverlayHost
import app.podium.core.designsystem.component.LocalScreenInsets
import app.podium.core.designsystem.component.OverlayHost
import app.podium.core.designsystem.component.ScreenInsets
import app.podium.core.designsystem.glass.GlassTier
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.interaction.InputRouter
import app.podium.core.interaction.LocalInputRouter
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
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertTrue

/**
 * The cover moving on when the song changes (D-78), filmed frame by frame: the playing cover leaves
 * left as the next comes in from the right; Previous runs the other way. Frames go to
 * build/screenshots/ for review.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NowPlayingSlideTest {

    @get:Rule
    val compose = createComposeRule()

    private fun item(uid: String, index: Int, art: String) = NowPlayingItem(
        uid = QueueUid(uid), trackId = TrackId("test|$uid"), title = "Song $uid", artistDisplay = "Artist",
        albumTitle = "Album $uid", artworkUri = "test://$art", durationMs = 200_000, indexInQueue = index, queueSize = 3,
        servedByDisplayName = null, resolutionPath = null,
    )

    private val a = item("a", 0, "green")
    private val b = item("b", 1, "blue")
    private val c = item("c", 2, "red")
    private val queueOrder = listOf(a, b, c)

    private val controller = SlideController(b)

    /** Film the change from [from] to [to]; returns the new cover's horizontal centre in each frame (null: not on screen). */
    private fun film(name: String, from: NowPlayingItem, to: NowPlayingItem, newColor: (Int, Int, Int) -> Boolean): List<Float?> {
        RuntimeEnvironment.setQualifiers("w360dp-h740dp-port-420dpi")
        controller.show(from)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PodiumTheme(darkTheme = true, glassTier = GlassTier.Solid) {
                CompositionLocalProvider(
                    LocalInputRouter provides InputRouter(),
                    LocalOverlayHost provides OverlayHost(),
                    LocalArtworkLoader provides SolidCovers,
                    LocalScreenInsets provides ScreenInsets(top = 24.dp, bottom = 8.dp),
                ) {
                    Box(Modifier.fillMaxSize()) { NowPlayingScreen(controller, FixedVolume, NoFavorites, onUpNext = {}) }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_500)
        controller.show(to)
        val out = File("build/screenshots").apply { mkdirs() }
        return listOf(0L, 60L, 60L, 60L, 60L, 60L, 60L, 900L).mapIndexed { i, step ->
            compose.mainClock.advanceTimeBy(step)
            compose.onRoot().captureToImage() // Robolectric draws a new layer only when captured.
            val frame = compose.onRoot().captureToImage().asAndroidBitmap()
            frame.compress(Bitmap.CompressFormat.PNG, 100, File(out, "slide-$name-$i.png").outputStream())
            centreOf(frame, newColor)
        }
    }

    private fun centreOf(frame: Bitmap, color: (Int, Int, Int) -> Boolean): Float? {
        var sum = 0L
        var count = 0
        for (y in 0 until frame.height step 2) for (x in 0 until frame.width step 2) {
            val p = frame.getPixel(x, y)
            if (color((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)) {
                sum += x
                count++
            }
        }
        return if (count < 200) null else sum.toFloat() / count / frame.width
    }

    private val isBlue = { r: Int, g: Int, b: Int -> b > 170 && r < 70 && g < 70 }
    private val isRed = { r: Int, g: Int, b: Int -> r > 170 && g < 70 && b < 70 }
    private val isGreen = { r: Int, g: Int, b: Int -> g > 150 && r < 70 && b < 70 }

    @Test
    fun `the next song's cover comes in from the right as the playing one leaves`() {
        val centres = film("next", b, c, isRed)
        val settled = centres.last()!!
        val arriving = centres.drop(1).dropLast(1).filterNotNull()
        assertTrue(arriving.isNotEmpty(), "the new cover is seen on its way in: $centres")
        assertTrue(arriving.first() > settled + 0.05f, "it starts out to the right of where it settles: $centres")
        assertTrue(arriving.zipWithNext().all { (x, y) -> y <= x + 0.01f }, "and moves left the whole way: $centres")
    }

    @Test
    fun `previous runs the other way`() {
        val centres = film("previous", b, a, isGreen)
        val settled = centres.last()!!
        val arriving = centres.drop(1).dropLast(1).filterNotNull()
        assertTrue(arriving.isNotEmpty(), "the earlier cover is seen on its way back: $centres")
        assertTrue(arriving.first() < settled - 0.02f, "it comes in from the left: $centres")
    }

    @Test
    fun `a song with the same cover still moves on`() {
        val sameCover = c.copy(artworkUri = b.artworkUri)
        val centres = film("same-cover", b, sameCover, isBlue)
        val settled = centres.last()!!
        assertTrue(centres.drop(1).dropLast(1).filterNotNull().any { kotlin.math.abs(it - settled) > 0.05f }, "the cover slid: $centres")
    }

    private inner class SlideController(first: NowPlayingItem) : PlaybackController {
        private val _snapshot = MutableStateFlow(PlaybackSnapshot(status = PlaybackStatus.Paused(PauseReason.USER), intent = PlayIntent.PAUSE, item = first))
        private val _queue = MutableStateFlow(QueueView())
        override val snapshot: StateFlow<PlaybackSnapshot> = _snapshot
        override val queue: StateFlow<QueueView> = _queue

        fun show(item: NowPlayingItem) {
            _queue.value = QueueView(
                entries = queueOrder.map {
                    QueueEntry(it.uid, it.trackId, it.title, it.artistDisplay, it.artworkUri, it.durationMs, QueueOrigin.CONTEXT, isCurrent = it.uid == item.uid)
                },
                currentIndex = item.indexInQueue,
            )
            _snapshot.value = _snapshot.value.copy(item = item)
        }

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

    /** Flat test colours, so each cover can be found in a frame. */
    private object SolidCovers : ArtworkLoader {
        override suspend fun load(uri: String, sizePx: Int): ImageBitmap? = peek(uri, sizePx)
        override fun peek(uri: String, sizePx: Int): ImageBitmap? {
            val color = when (uri.substringAfter("test://")) {
                "green" -> 0xFF00C800.toInt()
                "blue" -> 0xFF0000DC.toInt()
                "red" -> 0xFFDC0000.toInt()
                else -> return null
            }
            val bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).drawRect(0f, 0f, 300f, 300f, Paint().apply { this.color = color })
            return bitmap.asImageBitmap()
        }
    }
}
