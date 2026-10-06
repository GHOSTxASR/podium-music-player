package app.podium.core.designsystem.shell

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.podium.core.designsystem.component.FocusList
import app.podium.core.designsystem.component.LocalOverlayHost
import app.podium.core.designsystem.component.LocalScreenInsets
import app.podium.core.designsystem.component.MenuRow
import app.podium.core.designsystem.component.OverlayHost
import app.podium.core.designsystem.component.PodWheel
import app.podium.core.designsystem.component.ScreenInsets
import app.podium.core.designsystem.theme.DisplayBackground
import app.podium.core.designsystem.theme.DisplayImage
import app.podium.core.designsystem.theme.DisplayTheme
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.designsystem.theme.TextContrast
import app.podium.core.designsystem.theme.VirtualDisplay
import app.podium.core.designsystem.type.DisplayFont
import app.podium.core.interaction.FocusListState
import app.podium.core.interaction.InputRouter
import app.podium.core.interaction.LocalInputRouter
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The customization on the device (D-41), rendered with Robolectric's native graphics: glitter in
 * three bodies, every display font, the Custom theme on light, mid-tone and picture backgrounds.
 * Screenshots go to build/screenshots/ for review; the checks are the ones a picture can prove.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CustomizationScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private var appearance by mutableStateOf(DeviceAppearance())
    private var image by mutableStateOf<DisplayImage?>(null)
    private val rows = listOf("Music", "Shuffle songs", "Now Playing", "Settings", "Björk, Jóga (live)", "1984: 0123456789")

    private fun setUp() {
        RuntimeEnvironment.setQualifiers("w411dp-h891dp-port-420dpi")
        compose.setContent {
            val a = appearance
            PodiumTheme(darkTheme = true, displayTheme = a.display, display = a.screen, displayImage = image) {
                val palette = a.palette(PodiumTheme.colors.isDark)
                CompositionLocalProvider(LocalInputRouter provides InputRouter(), LocalOverlayHost provides OverlayHost()) {
                    Box(Modifier.fillMaxSize()) {
                        DeviceBody(palette, Modifier.fillMaxSize())
                        DeviceLayout(
                            modifier = Modifier.fillMaxSize(),
                            screen = {
                                val insets = ScreenInsets(top = ScreenHeaderHeight, bottom = 8.dp)
                                CompositionLocalProvider(LocalScreenInsets provides insets) {
                                    FocusList(
                                        items = rows,
                                        state = FocusListState(),
                                        key = { it },
                                        contentPadding = insets.listPadding(),
                                        onActivate = {},
                                        modifier = Modifier.fillMaxSize(),
                                    ) { item, _, focused -> MenuRow(item, focused, value = if (item == "Settings") "On" else null) }
                                }
                                ScreenHeader("Podium", canGoBack = false, onBack = {}, playing = false)
                            },
                            wheel = { d -> PodWheel(onInput = {}, diameter = d, palette = palette) },
                            powerButton = { PowerButton(on = true, palette = palette, onToggle = {}) },
                        )
                    }
                }
            }
        }
    }

    private fun shot(name: String): Bitmap {
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(File("build/screenshots").apply { mkdirs() }, "custom-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }

    /** The body below the Wheel's bottom edge, away from the display, the Wheel and the buttons. */
    private fun bodyPixels(b: Bitmap): IntArray {
        val y0 = (b.height * 0.965).toInt()
        val pixels = IntArray(b.width * (b.height - y0))
        b.getPixels(pixels, 0, b.width, 0, y0, b.width, b.height - y0)
        return pixels
    }

    @Test
    fun glitterInTheBody() {
        setUp()
        appearance = DeviceAppearance(FinishPreset.STEEL_GRAY)
        val plain = bodyPixels(shot("body-steel-off"))
        // Off with other levels changed draws nothing: the body is exactly as before.
        appearance = DeviceAppearance(FinishPreset.STEEL_GRAY, glitter = Glitter(enabled = false, density = 1f, opacity = 1f))
        assertTrue(plain.contentEquals(bodyPixels(shot("body-steel-off-levels"))), "glitter off leaves the body unchanged")
        appearance = DeviceAppearance(FinishPreset.STEEL_GRAY, glitter = Glitter(enabled = true))
        assertFalse(plain.contentEquals(bodyPixels(shot("body-steel-glitter"))), "glitter shows when on")
        appearance = DeviceAppearance(FinishPreset.SILVER, glitter = Glitter(enabled = true))
        shot("body-silver-glitter")
        appearance = DeviceAppearance(display = DisplayTheme.CARBON, glitter = Glitter(enabled = true, density = 0.6f, opacity = 0.7f))
        shot("body-carbon-glitter")
    }

    @Test
    fun glitterFollowsTilt() {
        RuntimeEnvironment.setQualifiers("w411dp-h891dp-port-420dpi")
        var tilt by mutableStateOf(androidx.compose.ui.geometry.Offset.Zero)
        val body = DeviceAppearance(FinishPreset.STEEL_GRAY, glitter = Glitter(enabled = true, density = 0.6f, amount = 0.5f, glow = 1f))
        compose.setContent {
            PodiumTheme(darkTheme = true) {
                DeviceBody(body.palette(true), Modifier.fillMaxSize(), glitterTilt = tilt)
            }
        }
        val shots = mutableMapOf<String, Bitmap>()
        for ((name, t) in listOf("level" to androidx.compose.ui.geometry.Offset.Zero, "tilt-right" to androidx.compose.ui.geometry.Offset(-0.8f, 0f), "tilt-left" to androidx.compose.ui.geometry.Offset(0.8f, 0f))) {
            tilt = t
            shots[name] = shot("glitter-$name")
        }
        // Tilted right, the light (and the brightest flakes) moves left: the left half outshines the right.
        fun brightness(b: Bitmap, left: Boolean): Double {
            var sum = 0.0
            val x0 = if (left) 0 else b.width / 2
            for (y in 0 until b.height step 4) for (x in x0 until x0 + b.width / 2 step 4) {
                val c = b.getPixel(x, y)
                sum += ((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)
            }
            return sum
        }
        val right = shots.getValue("tilt-right")
        assertTrue(brightness(right, left = true) > brightness(right, left = false), "light moves left when tilted right")
        val left = shots.getValue("tilt-left")
        assertTrue(brightness(left, left = false) > brightness(left, left = true), "light moves right when tilted left")
    }

    @Test
    fun everyDisplayFont() {
        setUp()
        for (font in DisplayFont.entries) {
            appearance = DeviceAppearance(display = DisplayTheme.CARBON, screen = VirtualDisplay(font = font))
            shot("font-${font.name.lowercase()}")
        }
    }

    @Test
    fun customThemeBackgrounds() {
        setUp()
        appearance = DeviceAppearance(display = DisplayTheme.CUSTOM, screen = VirtualDisplay(solidArgb = 0xFFF2E6C8.toInt()))
        shot("theme-custom-paper")
        appearance = DeviceAppearance(display = DisplayTheme.CUSTOM, screen = VirtualDisplay(solidArgb = 0xFF0066FF.toInt(), font = DisplayFont.INDUSTRIAL))
        shot("theme-custom-blue")
        image = busyPicture()
        val withPicture = VirtualDisplay(background = DisplayBackground.IMAGE, solidArgb = 0xFF1B1B1B.toInt(), imageUri = "content://test", imageOpacity = 0.5f)
        appearance = DeviceAppearance(display = DisplayTheme.CUSTOM, screen = withPicture)
        shot("theme-custom-picture")
        appearance = DeviceAppearance(display = DisplayTheme.CUSTOM, screen = withPicture.copy(contrast = TextContrast.HIGH))
        shot("theme-custom-picture-high")
        appearance = DeviceAppearance(display = DisplayTheme.GLASS, screen = withPicture.copy(imageOpacity = 0.75f))
        shot("theme-glass-picture")
        // Carbon ignores the background (D-29): its display stays black.
        appearance = DeviceAppearance(display = DisplayTheme.CARBON, screen = withPicture)
        val carbon = shot("theme-carbon-ignores-picture")
        val probe = Color(carbon.getPixel(carbon.width / 2, (carbon.height * 0.47).toInt()))
        assertTrue(probe.luminance() < 0.02f, "Carbon's display stays black")
    }

    /** A loud, generated picture (no third-party images in the repository): diagonal colour stripes. */
    private fun busyPicture(): DisplayImage {
        val bitmap = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawRect(0f, 0f, 600f, 900f, Paint().apply { shader = LinearGradient(0f, 0f, 600f, 900f, 0xFFFF7A00.toInt(), 0xFF2A00FF.toInt(), Shader.TileMode.CLAMP) })
        val stripe = Paint().apply { color = 0xFFFFFFFF.toInt(); strokeWidth = 18f }
        for (i in -900..600 step 60) canvas.drawLine(i.toFloat(), 0f, i + 900f, 900f, stripe)
        val small = Bitmap.createScaledBitmap(bitmap, 1, 1, true)
        return DisplayImage(bitmap.asImageBitmap(), Color(small.getPixel(0, 0)).luminance())
    }
}
