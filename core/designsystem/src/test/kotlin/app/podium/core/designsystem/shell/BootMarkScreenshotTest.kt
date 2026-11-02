package app.podium.core.designsystem.shell

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.podium.core.designsystem.theme.PodiumTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The boot (D-102), frame by frame, drawn with Robolectric's native graphics: the lens with the
 * lined P, the spin, the wordmark unrolling, the wordmark at rest (silver, flaked, no colour), and
 * the dive, where the menu behind shows through the O. Frames go to build/screenshots/ for review.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BootMarkScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private var time by mutableFloatStateOf(0f)
    private var behind by mutableStateOf(Color.Black)
    private var started = false

    /** The frame at [t] ms, over [under] (the menu stands in as a colour: the boot is drawn in front of it). */
    private fun frame(name: String, t: Float, under: Color = Color.Black): Bitmap {
        time = t
        behind = under
        if (!started) {
            started = true
            RuntimeEnvironment.setQualifiers("w411dp-h891dp-port-420dpi")
            compose.setContent {
                PodiumTheme(darkTheme = true) {
                    Box(Modifier.size(360.dp, 500.dp).background(behind)) {
                        BootMark(time = { time }, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureToImage() // Robolectric draws a new layer only when captured.
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(File("build/screenshots").apply { mkdirs() }, "boot-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }

    @Test fun theLensHoldsTheLinedP() {
        val b = frame("1-lens", 900f)
        assertTrue(count(b) { r, g, bb -> r > 160 || g > 160 || bb > 160 } > 500, "the P and the lens's rim are lit")
    }

    @Test fun thePSpins() {
        frame("2-spin", 1650f)
    }

    @Test fun theWordmarkUnrolls() {
        frame("3-unroll-early", 1900f)
        frame("3-unroll", 2100f)
    }

    /** At rest the wordmark is silver with its flakes: no colour of its own. */
    @Test fun theWordmarkRestsInSilver() {
        val b = frame("4-wordmark", 2750f)
        var bright = 0
        var coloured = 0
        for (y in 0 until b.height) for (x in 0 until b.width) {
            val c = b.getPixel(x, y)
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val bb = c and 0xFF
            if (r > 190 && g > 190 && bb > 190) bright++
            if (maxOf(r, g, bb) - minOf(r, g, bb) > 40) coloured++
        }
        assertTrue(bright > 2_000, "the wordmark is there ($bright bright pixels)")
        assertEquals(0, coloured, "the wordmark has no colour")
    }

    /** Diving: the menu behind shows through the O's counter, black around it. */
    @Test fun theDiveRevealsWhatIsBehindThroughTheO() {
        val green = Color(0xFF2E7D32)
        val mid = frame("5-dive", 3200f, under = green)
        assertEquals(0xFF2E7D32.toInt(), mid.getPixel(mid.width / 2, mid.height / 2), "the centre shows the menu through the O")
        val late = frame("6-dive-late", 3540f, under = green)
        val greenPixels = count(late) { r, g, b -> g > 100 && r < 80 && b < 80 }
        assertTrue(greenPixels > late.width * late.height * 0.95, "by the end the menu fills the display ($greenPixels)")
    }

    private fun count(b: Bitmap, test: (Int, Int, Int) -> Boolean): Int {
        var n = 0
        for (y in 0 until b.height) for (x in 0 until b.width) {
            val c = b.getPixel(x, y)
            if (test((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)) n++
        }
        return n
    }
}
