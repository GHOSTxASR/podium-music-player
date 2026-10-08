package app.podium.guide

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.podium.core.designsystem.shell.DeviceAppearance
import app.podium.core.designsystem.shell.FinishPreset
import app.podium.core.designsystem.shell.palette
import app.podium.core.designsystem.theme.PodiumTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tour's model (D-57): each checkpoint's shot renders (screenshots in build/screenshots/), and
 * doing the thing on the model passes it — around the Wheel, a press on the center, two fingers.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GuideTourTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var guide: GuideState

    private fun tour(step: GuideStep) {
        RuntimeEnvironment.setQualifiers("w411dp-h891dp-port-420dpi")
        val store = GuideStore(RuntimeEnvironment.getApplication())
        guide = GuideState(store)
        // The model animates for as long as it's shown: the clock is driven by hand from the start.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PodiumTheme(darkTheme = true) {
                GuideTour(guide, DeviceAppearance(FinishPreset.BURGUNDY).palette(darkTheme = true))
            }
        }
        onUi {
            guide.offer()
            if (step != GuideStep.WELCOME) {
                guide.begin()
                while (guide.step != step) guide.next()
            }
        }
        compose.mainClock.advanceTimeBy(2_500)
    }

    @Test
    fun `every checkpoint has its shot`() {
        tour(GuideStep.WELCOME)
        screenshot("tour-welcome")
        for (s in GuideStep.entries.drop(1)) {
            onUi { while (guide.step != s) guide.next() }
            // Mid-way through the demonstration, after the camera has settled.
            compose.mainClock.advanceTimeBy(2_600)
            screenshot("tour-${s.name.lowercase()}")
        }
    }

    @Test
    fun `turning the model's Wheel passes the first checkpoint`() {
        val geometry = StageGeometry()
        stage(GuideStep.TURN, geometry)
        val c = geometry.toScreen(WHEEL)
        val r = LABEL_R * geometry.scale
        compose.onNodeWithTag("stage").performTouchInput {
            down(c + polar(r, -150f))
            for (i in 1..24) {
                moveTo(c + polar(r, -150f + i * 10f))
                advanceEventTime(16)
            }
            up()
        }
        compose.mainClock.advanceTimeBy(100)
        assertEquals(1, passes, "a turn of 240° counts")
    }

    @Test
    fun `a press on the model's center passes, a press elsewhere doesn't`() {
        val geometry = StageGeometry()
        stage(GuideStep.CENTER, geometry)
        compose.onNodeWithTag("stage").performTouchInput { click(geometry.toScreen(WHEEL + Offset(0f, -LABEL_R))) }
        compose.mainClock.advanceTimeBy(100)
        assertEquals(0, passes, "Menu isn't the center")
        compose.onNodeWithTag("stage").performTouchInput { click(geometry.toScreen(WHEEL)) }
        compose.mainClock.advanceTimeBy(100)
        assertEquals(1, passes)
    }

    @Test
    fun `two fingers together on the model pass the pinch`() {
        val geometry = StageGeometry()
        stage(GuideStep.PINCH, geometry)
        compose.onNodeWithTag("stage").performTouchInput {
            pinch(
                start0 = Offset(width * 0.2f, height * 0.25f), end0 = Offset(width * 0.42f, height * 0.45f),
                start1 = Offset(width * 0.8f, height * 0.75f), end1 = Offset(width * 0.58f, height * 0.55f),
                durationMillis = 400,
            )
        }
        compose.mainClock.advanceTimeBy(100)
        assertTrue(passes >= 1)
    }

    private var passes = 0

    private fun stage(step: GuideStep, geometry: StageGeometry) {
        RuntimeEnvironment.setQualifiers("w411dp-h891dp-port-420dpi")
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PodiumTheme(darkTheme = true) {
                ModelStage(
                    step,
                    DeviceAppearance(FinishPreset.BURGUNDY).palette(darkTheme = true),
                    onPass = { passes++ },
                    modifier = Modifier.fillMaxSize().testTag("stage"),
                    geometry = geometry,
                )
            }
        }
        // Let the camera settle on the shot.
        compose.mainClock.advanceTimeBy(3_000)
    }

    /** A change made from the test, applied as the app's own would be. */
    private fun onUi(block: () -> Unit) = compose.runOnUiThread {
        block()
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
    }

    private fun polar(r: Float, deg: Float): Offset {
        val a = Math.toRadians(deg.toDouble())
        return Offset((r * cos(a)).toFloat(), (r * sin(a)).toFloat())
    }

    private fun screenshot(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(File("build/screenshots").apply { mkdirs() }, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
