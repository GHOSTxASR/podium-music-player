package app.podium

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The whole app, rendered (Robolectric, native graphics) with the debug build's generated library
 * and no music access: it boots to Home, offers music access first, walks Home → Music → Albums and
 * back. Frames go to build/screenshots/app-*.png.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppShellTest {

    @get:Rule
    val compose = createComposeRule()

    private fun shot(name: String): Bitmap {
        compose.waitForIdle()
        // Robolectric draws only when a test captures, so a layer made since the last capture (the
        // focused row's text) records on that first draw and can come out blank. A phone draws
        // every frame; capture twice to see what it shows.
        compose.onRoot().captureToImage()
        val b = compose.onRoot().captureToImage().asAndroidBitmap()
        File(File("build/screenshots").apply { mkdirs() }, "app-$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return b
    }

    private fun exists(text: String) = compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun open(text: String) {
        compose.onAllNodesWithText(text, useUnmergedTree = true)[0].performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(900)
    }

    /** The display's list, left of the next-column preview. */
    private fun list(b: Bitmap): IntArray {
        val w = (b.width * 0.6).toInt()
        val h = (b.height * 0.55).toInt()
        return IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }
    }

    @Test
    fun bootsHomeMusicAlbumsAndBack() {
        RuntimeEnvironment.setQualifiers("w411dp-h891dp-port-420dpi")
        val graph = ApplicationProvider.getApplicationContext<PodiumApplication>().graph
        compose.mainClock.autoAdvance = false
        // The first-launch tour (D-57) has its own tests; here the shell itself is under test.
        graph.guide.markSeen()
        compose.setContent { PodiumApp(graph, onSourceAction = {}) }
        repeat(60) { if (graph.power.value != Power.ON) compose.mainClock.advanceTimeBy(150) }
        assertEquals(Power.ON, graph.power.value, "boots")
        compose.mainClock.advanceTimeBy(600)
        shot("home")
        assertTrue(exists("Allow music access"), "without access, Home offers it first")
        assertTrue(exists("Music") && exists("Settings"))

        open("Music")
        val settled = list(shot("music"))
        compose.mainClock.advanceTimeBy(3_000)
        // The access row fits its row: nothing in the list moves once the screen has arrived.
        assertTrue(settled.contentEquals(list(shot("music-later"))), "the Music list holds still")

        open("Albums")
        shot("albums")
        assertTrue(exists("Long Form"), "the generated albums are listed")

        compose.onAllNodesWithContentDescription("Back", useUnmergedTree = true)[0].performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(900)
        shot("back")
        assertTrue(exists("Cover Flow"), "back on Music")
    }
}
