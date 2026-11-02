package app.podium.core.designsystem.shell

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.podium.core.designsystem.glass.GlassTier
import app.podium.core.designsystem.theme.DisplayTheme
import app.podium.core.designsystem.theme.PodiumTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The title bar's housings (D-103), drawn with Robolectric's native graphics: the time in one,
 * the output, the play state and the battery sharing another, on Carbon and Glass; and a battery
 * arriving, frame by frame, as it buds off the side of its housing. Frames go to build/screenshots/.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StatusPillsScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private var battery by mutableStateOf<BatteryLevel?>(null)
    private var clock by mutableStateOf(true)

    private fun show(theme: DisplayTheme) {
        RuntimeEnvironment.setQualifiers("w411dp-h891dp-port-420dpi")
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PodiumTheme(darkTheme = true, glassTier = GlassTier.Solid, displayTheme = theme) {
                Column(Modifier.width(380.dp).background(PodiumTheme.colors.canvas)) {
                    Box {
                        ScreenHeader("Music", canGoBack = true, onBack = {}, playing = true, output = OutputIndicator.BLUETOOTH, clock = clock, battery = battery)
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
    }

    private fun save(name: String) {
        compose.onRoot().captureToImage()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(File("build/screenshots").apply { mkdirs() }, "pills-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun carbonHousings() {
        battery = BatteryLevel(76, charging = true)
        show(DisplayTheme.CARBON)
        save("carbon")
        compose.onNodeWithContentDescription("Playing").assertExists()
    }

    @Test fun glassHousings() {
        battery = BatteryLevel(76, charging = false)
        show(DisplayTheme.GLASS)
        save("glass")
    }

    /** The battery arrives: its housing stretches with a jelly overshoot and settles. */
    @Test fun anItemArrives() {
        show(DisplayTheme.CARBON)
        save("arrive-0")
        battery = BatteryLevel(40, charging = false)
        for (ms in listOf(48, 96, 160, 260, 600)) {
            compose.mainClock.advanceTimeBy(if (ms == 48) 48 else 50L.coerceAtLeast((ms - 48).toLong() / 3))
            save("arrive-$ms")
        }
    }

    /** The time's housing arrives beside the other: it buds off its side as a bubble. */
    @Test fun aHousingBudsOff() {
        clock = false
        show(DisplayTheme.CARBON)
        save("bud-0")
        clock = true
        listOf(32, 64, 96, 128, 176, 240, 600).fold(0) { at, ms ->
            compose.mainClock.advanceTimeBy((ms - at).toLong())
            save("bud-$ms")
            ms
        }
    }
}
