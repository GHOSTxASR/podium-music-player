package app.podium.feature.nowplaying

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
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
import app.podium.core.designsystem.theme.CarbonColors
import app.podium.core.designsystem.theme.DisplayTheme
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.interaction.InputRouter
import app.podium.core.interaction.LocalInputRouter
import app.podium.core.lyrics.Lyrics
import app.podium.core.lyrics.LyricsAttribution
import app.podium.core.lyrics.LyricsLine
import app.podium.core.lyrics.LyricsRequest
import app.podium.core.lyrics.LyricsResult
import app.podium.core.model.QueueUid
import app.podium.core.model.TrackId
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
import kotlin.test.assertTrue

/**
 * The lyric screen inside the device, rendered with Robolectric's native graphics: line by line the
 * display alternates between Bone's paper and Carbon's black, and the words stay inside it.
 * Screenshots go to build/screenshots/ for review.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LyricsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val lines = listOf(
        LyricsLine(10_000, "called me on the telephone"),
        LyricsLine(14_000, "i wish that i could be like the cool kids"),
        LyricsLine(18_000, "and everything they say is a little bit too long to fit on one small screen without wrapping"),
    )

    @Test fun firstLineIsLight() = check("lyrics-line-1", 11_000, PHONE, expectLight = true)

    @Test fun secondLineIsDark() = check("lyrics-line-2", 15_000, PHONE, expectLight = false)

    @Test fun longLineSmallScreen() = check("lyrics-line-3-small", 19_000, SMALL, expectLight = true)

    @Test fun beforeTheFirstLine() = check("lyrics-intro", 0, PHONE, expectLight = true)

    // Words arrive as they are sung: early in the line only its first words are in.
    @Test fun wordsArriveOneByOne() = check("lyrics-line-1-early", 10_700, PHONE, expectLight = true)

    @Test fun secondLineHalfSung() = check("lyrics-line-2-half", 15_200, PHONE, expectLight = false)

    @Test fun consent() = check("lyrics-consent", 0, PHONE, expectLight = true, answer = LyricsResult.NeedsConsent)

    @Test fun notFound() = check("lyrics-not-found", 0, SMALL, expectLight = true, answer = LyricsResult.NotFound)

    private fun check(name: String, position: Long, qualifiers: String, expectLight: Boolean, answer: LyricsResult? = null) {
        RuntimeEnvironment.setQualifiers(qualifiers)
        val gateway = object : LyricsGateway {
            override val attribution = LyricsAttribution("Lyrics Service", "https://example.com")
            override suspend fun lyrics(request: LyricsRequest) = answer ?: LyricsResult.Found(Lyrics.Synced(lines), attribution)
            override fun allow() = Unit
        }
        compose.setContent {
            PodiumTheme(darkTheme = true, glassTier = GlassTier.Solid, displayTheme = DisplayTheme.CARBON) {
                val palette = DeviceAppearance(FinishPreset.STEEL_GRAY, display = DisplayTheme.CARBON).palette(darkTheme = true)
                CompositionLocalProvider(LocalInputRouter provides InputRouter(), LocalOverlayHost provides OverlayHost()) {
                    Box(Modifier.fillMaxSize()) {
                        DeviceBody(palette, Modifier.fillMaxSize())
                        DeviceLayout(
                            modifier = Modifier.fillMaxSize(),
                            screen = { LyricsScreen(Controller(position), gateway) },
                            wheel = { d -> PodWheel(onInput = {}, diameter = d, palette = palette) },
                            powerButton = { PowerButton(on = true, palette = palette, onToggle = {}) },
                        )
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(File("build/screenshots").apply { mkdirs() }, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // Sample the display's paper near its top-left corner (inside the screen, away from the words).
        val paper = dominantNear(bitmap)
        val light = BoneColors.canvas.toArgbInt()
        val dark = CarbonColors.canvas.toArgbInt()
        val expected = if (expectLight) light else dark
        assertTrue(close(paper, expected), "the display should be ${if (expectLight) "light" else "dark"} ($name): ${Integer.toHexString(paper)}")
    }

    private fun androidx.compose.ui.graphics.Color.toArgbInt(): Int = android.graphics.Color.argb(
        (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(),
    )

    /** The most common colour in a band across the upper part of the screen area. */
    private fun dominantNear(b: Bitmap): Int {
        val counts = HashMap<Int, Int>()
        val y0 = (b.height * 0.12).toInt()
        val y1 = (b.height * 0.16).toInt()
        for (y in y0 until y1) for (x in (b.width * 0.2).toInt() until (b.width * 0.8).toInt() step 3) {
            val c = b.getPixel(x, y)
            counts[c] = (counts[c] ?: 0) + 1
        }
        return counts.maxBy { it.value }.key
    }

    private fun close(a: Int, b: Int): Boolean {
        fun ch(c: Int, s: Int) = (c shr s) and 0xFF
        return listOf(16, 8, 0).all { kotlin.math.abs(ch(a, it) - ch(b, it)) <= 6 }
    }

    private class Controller(private val position: Long) : PlaybackController {
        override val snapshot = MutableStateFlow(
            PlaybackSnapshot(
                status = PlaybackStatus.Playing,
                intent = PlayIntent.PLAY,
                item = NowPlayingItem(QueueUid("q"), TrackId("test|1"), "Telephone", "Someone", "Album", null, 200_000, 0, 1, null, null),
            ),
        )
        override val queue = MutableStateFlow(QueueView())
        override fun positionMs() = position
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

    private companion object {
        const val PHONE = "w411dp-h891dp-port-420dpi"
        const val SMALL = "w320dp-h568dp-port-xhdpi"
    }
}
