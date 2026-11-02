package app.podium.core.designsystem.orb

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs
import kotlin.test.assertTrue

/**
 * The live orb's look (D-107), drawn for real: Carbon's ivory body with its two dark eyes, the shape
 * changing between playing (round) and stopped (a settled pebble), the eyes following the gaze,
 * blinking shut, and the instrument drawing it as a hairline instead of filling it. Screenshots land
 * in build/screenshots/live-orb-*.png.
 *
 * Glass has no captured backdrop in a test, so it falls to its solid tier — which is the right thing
 * to measure here anyway: the shape and the eyes, not the blur.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiveOrbLookTest {

    @get:Rule
    val compose = createComposeRule()

    private val size = 240

    /** Room round the orb for the stars, which fly well past its own square. */
    private val pad = 120

    /** What one rendering shows, as shares of the orb's own box. */
    private class Shot(
        /** How much of the box the body fills, and how much of its corners. */
        val body: Double,
        val corners: Double,
        /** The eyes: how much dark ink sits inside the body, and where its weight is (−1 to 1 across). */
        val ink: Double,
        val inkAt: Double,
        /** The body's own brightness where no eye is. */
        val bodyLight: Double,
        /** How much of each eye shows, right over left: 1 is a matched pair. */
        val inkSplit: Double,
        /** How much is drawn in the band above the orb, where only stars ever are. */
        val above: Double,
    )

    private var theme by mutableStateOf(DisplayTheme.CARBON)
    private var settled by mutableStateOf(false)
    private val face = OrbFace()
    private var started = false

    private fun render(name: String, display: DisplayTheme, stopped: Boolean, setup: OrbFace.() -> Unit = {}): Shot {
        compose.runOnUiThread {
            theme = display
            settled = stopped
            face.scaleX = 1f
            face.scaleY = 1f
            face.bob = 0f
            face.shiftX = 0f
            face.tilt = 0f
            face.spark = 0f
            face.gazeX = 0f
            face.gazeY = 0f
            face.eyeWidth = 1f
            face.eyeHeight = 1f
            face.eyeBend = 0f
            face.eyeLean = 0f
            face.eyeSkew = 0f
            face.setup()
            face.moved()
        }
        if (!started) {
            started = true
            // Room enough for the orb and the stars over it; the default screen is narrower than both.
            RuntimeEnvironment.setQualifiers("w600dp-h600dp")
            compose.setContent {
                PodiumTheme(darkTheme = true, displayTheme = theme) {
                    // Each theme over its own display, so what is saved is what the listener sees.
                    Box(
                        Modifier.background(PodiumTheme.colors.canvas).padding(pad.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        LiveOrb(diameter = size.dp, settled = settled, face = face, pressed = { false })
                    }
                }
            }
        }
        compose.waitForIdle()
        val b = compose.onRoot().captureToImage().asAndroidBitmap()
        File(File("build/screenshots").apply { mkdirs() }, "live-orb-$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return measure(b)
    }

    private fun lum(c: Int): Double =
        ((c shr 16 and 0xFF) * 0.2126 + (c shr 8 and 0xFF) * 0.7152 + (c and 0xFF) * 0.0722) / 255.0

    private fun measure(b: Bitmap): Shot {
        // The orb's own box inside the padding; everything is measured in its coordinates. The
        // display's own colour is read from the padding, so every theme is judged against its ground.
        val left = pad
        val top = pad
        val side = b.width - pad * 2
        val ground = lum(b.getPixel(2, 2))
        fun at(x: Int, y: Int) = lum(b.getPixel(left + x, top + y))
        fun middle(fx: Double, fy: Double) = fx > 0.2 && fx < 0.8 && fy > 0.2 && fy < 0.8
        // The body's own tone: the middle is mostly body, so its middle value is the body's.
        val inner = ArrayList<Double>()
        for (y in 0 until side) for (x in 0 until side) {
            if (middle(x.toDouble() / side, y.toDouble() / side)) inner += at(x, y)
        }
        inner.sort()
        val bodyTone = inner[inner.size / 2]
        var bodyN = 0
        var cornerN = 0
        var cornerBody = 0
        var inkN = 0
        var inkX = 0.0
        var inkLeft = 0
        var inkRight = 0
        var total = 0
        for (y in 0 until side) for (x in 0 until side) {
            val l = at(x, y)
            val fx = x.toDouble() / side
            val fy = y.toDouble() / side
            total++
            // Anything that is not the display behind it is the orb.
            if (abs(l - ground) > 0.05) bodyN++
            // Fourteen per cent in from a corner: outside a circle, inside a settled pebble.
            if ((fx < 0.14 || fx > 0.86) && (fy < 0.14 || fy > 0.86)) {
                cornerN++
                if (abs(l - ground) > 0.05) cornerBody++
            }
            // The eyes are whatever stands well clear of the body's own tone, light ink or dark.
            if (middle(fx, fy) && abs(l - bodyTone) > 0.25) {
                inkN++
                inkX += fx - 0.5
                if (fx < 0.5) inkLeft++ else inkRight++
            }
        }
        // The band over the orb, where nothing but a star is ever drawn.
        var aboveN = 0
        var aboveDrawn = 0
        for (y in 0 until top) for (x in 0 until b.width) {
            aboveN++
            if (abs(lum(b.getPixel(x, y)) - ground) > 0.05) aboveDrawn++
        }
        return Shot(
            body = bodyN.toDouble() / total,
            corners = cornerBody.toDouble() / cornerN,
            ink = inkN.toDouble() / total,
            inkAt = if (inkN == 0) 0.0 else inkX / inkN / 0.5,
            bodyLight = bodyTone,
            inkSplit = if (inkLeft == 0) 0.0 else inkRight.toDouble() / inkLeft,
            above = aboveDrawn.toDouble() / aboveN,
        )
    }

    @Test
    fun `Carbon's orb is a solid ivory body with two dark eyes`() {
        val playing = render("carbon-playing", DisplayTheme.CARBON, stopped = false)
        assertTrue(playing.bodyLight > 0.75, "the body is ivory white: ${playing.bodyLight}")
        assertTrue(playing.ink > 0.004, "and it has eyes: ${playing.ink}")
        // The eyes are a pair, so their weight sits in the middle however dark they are.
        assertTrue(abs(playing.inkAt) < 0.1, "a matched pair, looking straight ahead: ${playing.inkAt}")
    }

    @Test
    fun `playing it is round, stopped it has settled`() {
        val playing = render("carbon-round", DisplayTheme.CARBON, stopped = false)
        val stopped = render("carbon-settled", DisplayTheme.CARBON, stopped = true)
        assertTrue(playing.corners < 0.10, "a round orb leaves its corners empty: ${playing.corners}")
        assertTrue(stopped.corners > playing.corners * 2, "a settled one reaches into them: ${playing.corners} → ${stopped.corners}")
        assertTrue(stopped.body > playing.body * 1.05, "and covers more of its box: ${playing.body} → ${stopped.body}")
    }

    @Test
    fun `the eyes go where it looks`() {
        val left = render("carbon-left", DisplayTheme.CARBON, stopped = true) { gazeX = -1f }
        val ahead = render("carbon-ahead", DisplayTheme.CARBON, stopped = true)
        val right = render("carbon-right", DisplayTheme.CARBON, stopped = true) { gazeX = 1f }
        assertTrue(left.inkAt < ahead.inkAt - 0.05, "left: ${left.inkAt} vs ${ahead.inkAt}")
        assertTrue(right.inkAt > ahead.inkAt + 0.05, "right: ${right.inkAt} vs ${ahead.inkAt}")
    }

    @Test
    fun `a blink all but closes them, and a squint widens them`() {
        val open = render("carbon-open", DisplayTheme.CARBON, stopped = true)
        val shut = render("carbon-blink", DisplayTheme.CARBON, stopped = true) { eyeHeight = 0.06f }
        val happy = render("carbon-happy", DisplayTheme.CARBON, stopped = true) { eyeHeight = 0.28f; eyeWidth = 2f; eyeBend = 1f }
        assertTrue(shut.ink < open.ink * 0.3, "the eyes close: ${open.ink} → ${shut.ink}")
        assertTrue(shut.ink > 0, "but are still there")
        assertTrue(happy.ink < open.ink, "a squint shows less of them than open eyes: ${happy.ink}")
        assertTrue(happy.ink > shut.ink * 2, "and far more than a blink")
    }

    @Test
    fun `the instrument draws the orb instead of filling it`() {
        // Custom is the one display left that draws rather than fills (D-108).
        val drawn = render("instrument", DisplayTheme.CUSTOM, stopped = true)
        val filled = render("carbon-for-instrument", DisplayTheme.CARBON, stopped = true)
        assertTrue(drawn.body < filled.body * 0.4, "a hairline and its eyes, not a filled body: ${drawn.body} vs ${filled.body}")
        assertTrue(drawn.body > 0.002, "but it is drawn: ${drawn.body}")
        assertTrue(drawn.ink > 0.004, "and it has eyes: ${drawn.ink}")
    }

    @Test
    fun `it says what it feels with its eyes`() {
        // Cross: wide eyes with their inner ends dropped. Hurt: bowed down with their inner ends up.
        val cross = render("carbon-cross", DisplayTheme.CARBON, stopped = true) { eyeHeight = 0.28f; eyeWidth = 1.85f; eyeLean = 18f }
        val hurt = render("carbon-hurt", DisplayTheme.CARBON, stopped = true) { eyeHeight = 0.24f; eyeWidth = 1.70f; eyeBend = -0.7f; eyeLean = -12f }
        val asking = render("carbon-asking", DisplayTheme.CARBON, stopped = true) { eyeHeight = 1.06f; eyeSkew = 0.7f; tilt = 10f }
        val asleep = render("carbon-asleep", DisplayTheme.CARBON, stopped = true) { eyeHeight = 0.12f; eyeWidth = 1.15f }
        val wide = render("carbon-wide", DisplayTheme.CARBON, stopped = true) { eyeHeight = 1.3f; eyeWidth = 1.1f }
        // Each is a different amount of eye on the face, so none of them draws as another.
        val inks = listOf("cross" to cross.ink, "hurt" to hurt.ink, "asking" to asking.ink, "asleep" to asleep.ink, "wide" to wide.ink)
        for (shot in inks) assertTrue(shot.second > 0.0008, "${shot.first} still has eyes: ${shot.second}")
        assertTrue(asleep.ink < cross.ink, "an eye all but shut shows less than a cross one")
        assertTrue(wide.ink > cross.ink, "and eyes out on stalks show more")
        // Asking: one eye narrowed, so the two are no longer a matched pair.
        assertTrue(asking.inkSplit > 1.3, "one eye is narrower than the other: ${asking.inkSplit}")
        assertTrue(abs(wide.inkSplit - 1.0) < 0.15, "where a plain face's two eyes match: ${wide.inkSplit}")
    }

    @Test
    fun `it throws a few stars off its head`() {
        val quiet = render("carbon-no-stars", DisplayTheme.CARBON, stopped = false)
        val stars = render("carbon-stars", DisplayTheme.CARBON, stopped = false) { spark = 0.5f }
        assertTrue(stars.above > quiet.above * 3, "something is in the air over it: ${quiet.above} → ${stars.above}")
        val gone = render("carbon-stars-gone", DisplayTheme.CARBON, stopped = false) { spark = 1f }
        assertTrue(gone.above <= quiet.above * 1.2, "and nothing once they are gone: ${gone.above}")
    }

    @Test
    fun `Glass draws the same orb in its own material`() {
        val glass = render("glass", DisplayTheme.GLASS, stopped = false)
        assertTrue(glass.body > 0.5, "a body, not an outline: ${glass.body}")
        assertTrue(glass.corners < 0.12, "round while the music plays: ${glass.corners}")
        assertTrue(glass.ink > 0.004, "with its eyes in the display's own ink: ${glass.ink}")
    }
}
