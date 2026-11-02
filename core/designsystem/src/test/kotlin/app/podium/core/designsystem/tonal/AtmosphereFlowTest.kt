package app.podium.core.designsystem.tonal

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.podium.core.designsystem.theme.DisplayTheme
import app.podium.core.designsystem.theme.PodiumTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The cover's colours flow on Now Playing while the music plays, and hold still when it doesn't
 * (D-103). Drawn with Robolectric's native graphics, the clock stepped by hand.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AtmosphereFlowTest {

    @get:Rule
    val compose = createComposeRule()

    private var moving by mutableStateOf(false)

    private val field = run {
        // A cover with distinct colour regions, toned as Carbon's field.
        val px = IntArray(32 * 32) { i -> if ((i % 32) < 16) 0xFFD32F2F.toInt() else 0xFF1565C0.toInt() }
        val out = AtmosphereField.render(px, 32, 32)
        Bitmap.createBitmap(out, AtmosphereField.SIZE, AtmosphereField.SIZE, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    private fun frame(name: String): Bitmap {
        compose.onRoot().captureToImage()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(File("build/screenshots").apply { mkdirs() }, "flow-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }

    private fun differing(a: Bitmap, b: Bitmap): Int {
        var n = 0
        for (y in 0 until a.height step 4) for (x in 0 until a.width step 4) if (a.getPixel(x, y) != b.getPixel(x, y)) n++
        return n
    }

    @Test
    fun itFlowsWhilePlayingAndHoldsWhenPaused() {
        // Animations on (Robolectric's default reads as "Remove animations", which keeps the light still).
        val resolver = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver
        android.provider.Settings.Global.putFloat(resolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PodiumTheme(darkTheme = true, displayTheme = DisplayTheme.CARBON) {
                Box(Modifier.size(200.dp, 300.dp)) {
                    AlbumAtmosphere(field, level = { 1f }, Modifier.size(200.dp, 300.dp), moving = { moving })
                }
            }
        }
        compose.mainClock.advanceTimeBy(500)
        val still = frame("still")
        compose.mainClock.advanceTimeBy(3_000)
        assertEquals(0, differing(still, frame("still-later")), "paused, the light holds still")

        compose.runOnUiThread {
            moving = true
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeBy(5_000)
        val flowing = frame("flowing")
        assertTrue(differing(still, flowing) > 100, "playing, the colours move")

        compose.runOnUiThread {
            moving = false
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeBy(4_000)
        val settled = frame("settled")
        compose.mainClock.advanceTimeBy(2_000)
        assertEquals(0, differing(settled, frame("settled-later")), "paused again, it eases to a stop")
    }
}
