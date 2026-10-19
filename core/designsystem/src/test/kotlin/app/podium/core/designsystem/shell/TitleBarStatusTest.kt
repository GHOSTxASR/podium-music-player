package app.podium.core.designsystem.shell

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.podium.core.designsystem.theme.DisplayTheme
import app.podium.core.designsystem.theme.PodiumTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Full screen's title bar (D-85): the time on the left, the battery on the right, neither ever over
 * the title — with or without the back arrow. Screenshots in build/screenshots/title-bar-*.png.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TitleBarStatusTest {

    @get:Rule
    val compose = createComposeRule()

    private var back by mutableStateOf(false)

    private fun DpRect.toRect() = Rect(left.value, top.value, right.value, bottom.value)

    private fun check(name: String, canGoBack: Boolean) {
        back = canGoBack
        compose.waitForIdle()
        val title = compose.onNodeWithText("Songs by artist").getBoundsInRoot().toRect()
        val time = compose.onNodeWithContentDescription("Time", substring = true).getBoundsInRoot().toRect()
        val battery = compose.onNodeWithContentDescription("Battery", substring = true).getBoundsInRoot().toRect()
        assertFalse(title.overlaps(time), "the time is clear of the title: $time / $title")
        assertFalse(title.overlaps(battery), "the battery is clear of the title: $battery / $title")
        assertTrue(time.right < battery.left, "time on the left, battery on the right")
        val b = compose.onRoot().captureToImage().asAndroidBitmap()
        File(File("build/screenshots").apply { mkdirs() }, "title-bar-$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun `the time and the battery sit either side of the title`() {
        compose.setContent {
            PodiumTheme(darkTheme = true, displayTheme = DisplayTheme.CARBON) {
                Box(Modifier.width(360.dp).background(Color.Black)) {
                    ScreenHeader(
                        title = "Songs by artist",
                        canGoBack = back,
                        onBack = {},
                        playing = true,
                        clock = true,
                        battery = BatteryLevel(64, charging = true),
                    )
                }
            }
        }
        check("home", canGoBack = false)
        check("deeper", canGoBack = true)
    }
}
