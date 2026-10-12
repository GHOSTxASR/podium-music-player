package app.podium.feature.nowplaying.line

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.podium.core.designsystem.component.LocalOverlayHost
import app.podium.core.designsystem.component.OverlayHost
import app.podium.core.designsystem.component.PodWheel
import app.podium.core.designsystem.glass.GlassTier
import app.podium.core.designsystem.shell.DeviceAppearance
import app.podium.core.designsystem.shell.DeviceBody
import app.podium.core.designsystem.shell.DeviceLayout
import app.podium.core.designsystem.shell.FinishPreset
import app.podium.core.designsystem.shell.PowerButton
import app.podium.core.designsystem.shell.palette
import app.podium.core.designsystem.theme.BoneColors
import app.podium.core.designsystem.theme.DisplayTheme
import app.podium.core.designsystem.theme.LyricsTheme
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.designsystem.theme.VirtualDisplay
import app.podium.core.interaction.InputRouter
import app.podium.core.interaction.LocalInputRouter
import app.podium.core.lyrics.Lyrics
import app.podium.core.lyrics.LyricsAttribution
import app.podium.core.lyrics.LyricsLine
import app.podium.core.lyrics.LyricsRequest
import app.podium.core.lyrics.LyricsResult
import app.podium.core.model.QueueUid
import app.podium.core.model.TrackId
import app.podium.feature.nowplaying.LyricsGateway
import app.podium.feature.nowplaying.LyricsScreen
import app.podium.player.api.NowPlayingItem
import app.podium.player.api.PlayIntent
import app.podium.player.api.PlaybackController
import app.podium.player.api.PlaybackSnapshot
import app.podium.player.api.PlaybackStatus
import app.podium.player.api.QueueView
import app.podium.player.api.RepeatMode
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Continuous line theme inside the device (Robolectric native graphics): the real lyrics screen,
 * the real renderer. Frames at several playback positions land in build/screenshots/ for review;
 * the assertions check what can be checked in pixels — the sheet is black, ink appears as the song
 * goes on, the same moment draws the same pixels whichever way it was reached, and the classic theme
 * is untouched.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContinuousLineScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val lines = listOf(
        LyricsLine(10_000, "When the night is quiet"),
        LyricsLine(13_500, "and everything they say"),
        LyricsLine(17_000, "I wish that I could hold the stars"),
        LyricsLine(21_000, ""),
        LyricsLine(32_000, "Hold on"),
        LyricsLine(35_000, "and everything they say is a little bit too long to fit on one small screen without wrapping"),
    )

    @Test
    fun drawsTheSongAsOneLine() {
        val controller = Controller(0)
        show(controller, Lyrics.Synced(lines))
        val shots = listOf(5_000L, 11_200L, 13_000L, 15_500L, 19_500L, 25_000L, 31_000L, 33_500L, 41_000L, 56_000L)
        val inked = shots.map { position ->
            controller.position = position
            settle()
            val bitmap = capture("line-${position / 100}")
            assertTrue(paperIsBlack(bitmap), "the sheet is matte black at $position")
            inkCount(bitmap)
        }
        // Ink appears as the first phrase is written.
        assertTrue(inked[1] > inked[0], "ink grows while the first phrase is written: $inked")
    }

    @Test
    fun theSameMomentDrawsTheSamePictureWhicheverWayItIsReached() {
        val controller = Controller(0)
        show(controller, Lyrics.Synced(lines))
        controller.position = 15_500
        settle()
        val forward = capture("line-same-forward")
        assertTrue(goldCount(forward) > 0, "the pen carries its gold light while writing")
        // On to the instrumental, then back again (a rewind): the pen retraces to the same place.
        controller.position = 27_000
        settle()
        controller.position = 15_500
        settle()
        val back = capture("line-same-back")
        // Repeatedly seeking to the same moment: still the same picture.
        controller.position = 11_000
        settle()
        controller.position = 15_500
        settle()
        val again = capture("line-same-again")
        assertEquals(0, differences(forward, back), "a rewind restores the same drawing")
        assertEquals(0, differences(forward, again), "seeking to the same moment again draws the same")
    }

    @Test
    fun aSongWithoutWordsIsDrawnAsInstrumentalLineArt() {
        val controller = Controller(40_000)
        show(controller, Lyrics.Instrumental)
        settle()
        val bitmap = capture("line-instrumental")
        assertTrue(paperIsBlack(bitmap))
        assertTrue(inkCount(bitmap) > 200, "instrumental line art is drawn")
    }

    @Test
    fun plainLyricsShowTheLineWritten() {
        val controller = Controller(0)
        show(controller, Lyrics.Plain(listOf("When the night is quiet", "and everything they say")))
        settle()
        val bitmap = capture("line-plain")
        assertTrue(inkCount(bitmap) > 200, "the first line is shown written")
    }

    @Test
    fun accentsAndOtherScriptsAreDrawnToo() {
        val controller = Controller(12_500)
        show(controller, Lyrics.Synced(listOf(LyricsLine(10_000, "Café señor über Ærø"), LyricsLine(14_000, "Любовь 愛してる"))))
        settle()
        capture("line-accents")
        controller.position = 16_500
        settle()
        val other = capture("line-other-scripts")
        assertTrue(inkCount(other) > 200)
    }

    @Test
    fun classicThemeIsUnchanged() {
        val controller = Controller(11_000)
        show(controller, Lyrics.Synced(lines), theme = LyricsTheme.CLASSIC)
        settle()
        val bitmap = capture("line-classic")
        // The classic first line is on Bone's paper, exactly as before.
        val paper = bitmap.getPixel(bitmap.width / 2, (bitmap.height * 0.13).toInt())
        assertEquals(BoneColors.canvas.toArgb(), paper)
    }

    @Test
    fun switchingThemesLeavesThePlaybackAlone() {
        val controller = Controller(11_200)
        show(controller, Lyrics.Synced(lines))
        val line = capture("line-switch-before")
        assertTrue(paperIsBlack(line))
        // To Classic and back, as from Settings: the screen changes, the music doesn't.
        theme = LyricsTheme.CLASSIC
        settle()
        val classic = capture("line-switch-classic")
        // Classic's first line is on Bone's paper.
        assertEquals(BoneColors.canvas.toArgb(), classic.getPixel(classic.width / 2, (classic.height * 0.13).toInt()))
        theme = LyricsTheme.CONTINUOUS_LINE
        settle()
        val back = capture("line-switch-back")
        assertEquals(0, controller.commands, "no play, pause, seek or queue command")
        assertEquals(11_200L, controller.position, "the position is untouched")
        assertEquals(0, differences(line, back), "the same moment draws the same picture after switching back")
    }

    @Test
    fun aNewSongStartsItsOwnDrawing() {
        val controller = Controller(12_000)
        val other = Lyrics.Synced(listOf(LyricsLine(9_000, "Somewhere far away"), LyricsLine(14_000, "the river bends")))
        show(controller, Lyrics.Synced(lines), byTrack = mapOf("test|other" to other))
        val first = capture("line-track-a")
        controller.change("test|other")
        settle()
        compose.onNodeWithContentDescription("Somewhere far away").assertExists()
        val second = capture("line-track-b")
        assertTrue(differences(first, second) > 1_000, "the new song's own words are drawn")
        // Back and forth: the second song draws the same each time, nothing carried over.
        controller.change("test|line")
        settle()
        controller.change("test|other")
        settle()
        assertEquals(0, differences(second, capture("line-track-b-again")))
    }

    @Test
    fun reducedMotionKeepsTheLineWithoutTheGlow() {
        android.provider.Settings.Global.putFloat(RuntimeEnvironment.getApplication().contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        try {
            val controller = Controller(15_500)
            show(controller, Lyrics.Synced(lines))
            val bitmap = capture("line-reduced")
            assertTrue(paperIsBlack(bitmap))
            assertTrue(inkCount(bitmap) > 200, "the words are still written")
            assertEquals(0, goldCount(bitmap), "no glow or shimmer under reduced motion")
        } finally {
            android.provider.Settings.Global.putFloat(RuntimeEnvironment.getApplication().contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }
    }

    private var theme by mutableStateOf(LyricsTheme.CONTINUOUS_LINE)

    private fun show(
        controller: Controller,
        lyrics: Lyrics,
        theme: LyricsTheme = LyricsTheme.CONTINUOUS_LINE,
        qualifiers: String = PHONE,
        byTrack: Map<String, Lyrics> = emptyMap(),
    ) {
        RuntimeEnvironment.setQualifiers(qualifiers)
        this.theme = theme
        val gateway = object : LyricsGateway {
            override val attribution = LyricsAttribution("Lyrics Service", "https://example.com")
            override suspend fun lyrics(request: LyricsRequest) = LyricsResult.Found(byTrack[request.key] ?: lyrics, attribution)
            override fun allow() = Unit
        }
        compose.setContent {
            PodiumTheme(darkTheme = true, glassTier = GlassTier.Solid, displayTheme = DisplayTheme.CARBON, display = VirtualDisplay(lyricsTheme = this.theme)) {
                val palette = DeviceAppearance(FinishPreset.STEEL_GRAY, display = DisplayTheme.CARBON).palette(darkTheme = true)
                CompositionLocalProvider(LocalInputRouter provides InputRouter(), LocalOverlayHost provides OverlayHost()) {
                    Box(Modifier.fillMaxSize()) {
                        DeviceBody(palette, Modifier.fillMaxSize())
                        DeviceLayout(
                            modifier = Modifier.fillMaxSize(),
                            screen = { LyricsScreen(controller, gateway) },
                            wheel = { d -> PodWheel(onInput = {}, diameter = d, palette = palette) },
                            powerButton = { PowerButton(on = true, palette = palette, onToggle = {}) },
                        )
                    }
                }
            }
        }
        settle()
    }

    /** Lets the composition be prepared (off the main thread) and the pen arrive. */
    private fun settle() {
        repeat(3) {
            Thread.sleep(150)
            compose.mainClock.advanceTimeBy(1_200)
            compose.waitForIdle()
        }
    }

    private fun capture(name: String): Bitmap {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(File("build/screenshots").apply { mkdirs() }, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }

    /** The display's corner (inside the screen, away from the drawing) is the line's matte black. */
    private fun paperIsBlack(b: Bitmap): Boolean {
        val c = b.getPixel((b.width * 0.12).toInt(), (b.height * 0.05).toInt())
        return listOf(16, 8, 0).all { ((c shr it) and 0xFF) < 24 }
    }

    /** Bright pixels in the display area: the ink. */
    private fun inkCount(b: Bitmap): Int {
        var count = 0
        for (y in (b.height * 0.03).toInt() until (b.height * 0.5).toInt()) for (x in 0 until b.width) {
            val c = b.getPixel(x, y)
            if (((c shr 16) and 0xFF) > 110 && ((c shr 8) and 0xFF) > 100) count++
        }
        return count
    }

    /** Gold pixels (the glow, the shimmer, the glints): warm, clearly more red than blue. */
    private fun goldCount(b: Bitmap): Int {
        var count = 0
        for (y in 0 until (b.height * 0.7).toInt()) for (x in 0 until b.width) {
            val c = b.getPixel(x, y)
            val r = (c shr 16) and 0xFF
            val bl = c and 0xFF
            if (r > 60 && r - bl > 40) count++
        }
        return count
    }

    private fun differences(a: Bitmap, b: Bitmap): Int {
        var n = 0
        for (y in 0 until (a.height * 0.5).toInt()) for (x in 0 until a.width) if (a.getPixel(x, y) != b.getPixel(x, y)) n++
        return n
    }

    /** A paused player at a set position that counts every command it is given. */
    private class Controller(initial: Long) : PlaybackController {
        var position = initial
        var commands = 0
        override val snapshot = MutableStateFlow(
            PlaybackSnapshot(
                status = PlaybackStatus.Paused(app.podium.player.api.PauseReason.USER),
                intent = PlayIntent.PAUSE,
                item = item("test|line"),
            ),
        )

        fun change(key: String) {
            snapshot.value = snapshot.value.copy(item = item(key))
        }

        private fun item(key: String) = NowPlayingItem(QueueUid(key), TrackId(key), "Quiet night $key", "Someone", "Album", null, 60_000, 0, 1, null, null)
        override val queue = MutableStateFlow(QueueView())
        override fun positionMs() = position
        override fun play() { commands++ }
        override fun pause() { commands++ }
        override fun togglePlayPause() { commands++ }
        override fun next() { commands++ }
        override fun previous() { commands++ }
        override fun seekTo(positionMs: Long) {
            commands++
            position = positionMs
        }
        override fun setRepeat(mode: RepeatMode) { commands++ }
        override fun setShuffle(enabled: Boolean) { commands++ }
        override fun playContext(tracks: List<TrackId>, startIndex: Int, contextLabel: String?, shuffle: Boolean, radio: Boolean) { commands++ }
        override fun playNext(tracks: List<TrackId>) { commands++ }
        override fun addToQueue(tracks: List<TrackId>) { commands++ }
        override fun move(uid: QueueUid, toIndex: Int) { commands++ }
        override fun remove(uids: Set<QueueUid>) { commands++ }
        override fun skipTo(uid: QueueUid) { commands++ }
        override fun clearUpcoming() { commands++ }
    }

    private companion object {
        const val PHONE = "w411dp-h891dp-port-420dpi"
    }
}
