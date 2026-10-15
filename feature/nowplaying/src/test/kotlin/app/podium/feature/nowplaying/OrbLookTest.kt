package app.podium.feature.nowplaying

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.podium.core.designsystem.orb.OrbFrame
import app.podium.core.designsystem.orb.OrbRenderer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.hypot
import kotlin.test.assertTrue

/**
 * The orb's look (D-83), rendered by its real shader with Robolectric's native graphics: a dark
 * hollow (where the camera goes through), a lit ring of ivory and gold, light kept inside the orb's
 * own square, brighter with the music, nothing at all when it is gone. Screenshots land in
 * build/screenshots/orb-*.png.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OrbLookTest {

    @get:Rule
    val compose = createComposeRule()

    private class Lum(val hollow: Double, val ring: Double, val corners: Double, val mean: Double)

    private val renderer = OrbRenderer()
    private var shown by mutableStateOf<OrbFrame?>(null)
    private var started = false

    private fun render(name: String, setup: OrbFrame.() -> Unit): Lum {
        shown = OrbFrame().apply { time = 3.7f; rotation = 0.6f; setup() }
        if (!started) {
            started = true
            compose.setContent {
                val frame = shown
                Canvas(Modifier.size(300.dp).background(OrbRenderer.Black)) {
                    if (frame != null) renderer.draw(this, center, size.minDimension * 0.46f, frame)
                }
            }
        }
        compose.waitForIdle()
        val b = compose.onRoot().captureToImage().asAndroidBitmap()
        File(File("build/screenshots").apply { mkdirs() }, "orb-$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val cx = b.width / 2.0
        val cy = b.height / 2.0
        val r = minOf(b.width, b.height) * 0.46
        var hollow = 0.0
        var hollowN = 0
        var ring = 0.0
        var corners = 0.0
        var cornersN = 0
        var all = 0.0
        for (y in 0 until b.height step 2) for (x in 0 until b.width step 2) {
            val c = b.getPixel(x, y)
            val l = ((c shr 16 and 0xFF) * 0.2126 + (c shr 8 and 0xFF) * 0.7152 + (c and 0xFF) * 0.0722) / 255.0
            val d = hypot(x - cx, y - cy) / r
            all += l
            if (d < 0.18) {
                hollow += l
                hollowN++
            }
            if (d in 0.6..0.9) ring = maxOf(ring, l)
            if (d > 1.12) {
                corners = maxOf(corners, l)
                cornersN++
            }
        }
        return Lum(hollow / hollowN, ring, corners, all / ((b.width / 2) * (b.height / 2)))
    }

    @Test
    fun `a dark hollow inside a lit ring, its light kept to the orb`() {
        val rest = render("rest") {}
        assertTrue(rest.hollow < 0.04, "the hollow is dark: ${rest.hollow}")
        assertTrue(rest.ring > 0.35, "the ring is lit: ${rest.ring}")
        assertTrue(rest.corners < 0.04, "no light beyond the orb: ${rest.corners}")
    }

    @Test
    fun `the music brightens and moves it, within bounds`() {
        val rest = render("rest-again") {}
        val loud = render("loud") { energy = 1f; low = 1f; high = 1f; glint = 1f }
        assertTrue(loud.mean > rest.mean * 1.1, "brighter with the music: ${rest.mean} → ${loud.mean}")
        assertTrue(loud.hollow < 0.06, "the hollow stays dark, even loud: ${loud.hollow}")
        assertTrue(loud.corners < 0.05, "the glow never washes the black: ${loud.corners}")
    }

    @Test
    fun `gone is gone`() {
        val gone = render("gone") { alpha = 0f }
        assertTrue(gone.ring < 0.03 && gone.mean < 0.03, "nothing drawn: ${gone.mean}")
    }
}
