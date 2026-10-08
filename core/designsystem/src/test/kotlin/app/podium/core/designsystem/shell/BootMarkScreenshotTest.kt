package app.podium.core.designsystem.shell

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
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
 * The boot's wordmark stage (D-71), drawn with Robolectric's native graphics: the silver wordmark
 * with its flakes and the wider progress bar. The screenshot goes to build/screenshots/ for review.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BootMarkScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun wordmarkIsSilverWithFlakesAndNoBlue() {
        RuntimeEnvironment.setQualifiers("w411dp-h891dp-port-420dpi")
        compose.setContent {
            PodiumTheme(darkTheme = true) {
                Box(Modifier.size(340.dp, 300.dp).background(Color.Black)) {
                    BootMark(word = { 1f }, bar = { 1f }, progress = { 0.6f }, Modifier.size(340.dp, 300.dp))
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureToImage() // Robolectric draws a new layer only when captured.
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(File("build/screenshots").apply { mkdirs() }, "boot-mark.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

        var bright = 0
        var specks = 0
        var blue = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val c = bitmap.getPixel(x, y)
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            if (b > r + 40 && b > g + 20) blue++
            if (r > 200 && g > 200 && b > 200) bright++
            // A dark flake inside a letter: darker than the silver around it, but not the black screen.
            if (r in 60..150 && x > 0 && ((bitmap.getPixel(x - 1, y) shr 16) and 0xFF) > 190) specks++
        }
        assertEquals(0, blue, "the boot screen has no blue")
        assertTrue(bright > 3000, "the wordmark is drawn in silver ($bright bright pixels)")
        assertTrue(specks > 40, "flakes are set into the letters ($specks dark specks)")
    }
}
