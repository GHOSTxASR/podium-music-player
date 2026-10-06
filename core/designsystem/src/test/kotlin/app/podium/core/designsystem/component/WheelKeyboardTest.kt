package app.podium.core.designsystem.component

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.podium.core.designsystem.shell.DeviceAppearance
import app.podium.core.designsystem.shell.DeviceBody
import app.podium.core.designsystem.shell.DeviceLayout
import app.podium.core.designsystem.shell.FinishPreset
import app.podium.core.designsystem.shell.PowerButton
import app.podium.core.designsystem.shell.ScreenHeader
import app.podium.core.designsystem.shell.ScreenHeaderHeight
import app.podium.core.designsystem.shell.palette
import app.podium.core.designsystem.theme.DisplayTheme
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.interaction.InputRouter
import app.podium.core.interaction.LocalInputRouter
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Wheel becomes the keyboard (D-45): focusing a field opens it out, the keys type into the
 * field, the close key folds it back. Frames of the morph go to build/screenshots/.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WheelKeyboardTest {

    @get:Rule
    val compose = createComposeRule()

    private fun shot(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(File("build/screenshots").apply { mkdirs() }, "keyboard-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Press a key the way a finger does, then let the main thread settle and a frame pass. */
    private fun tap(description: String) {
        compose.onNodeWithContentDescription(description).performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(50)
        compose.waitForIdle()
    }

    private fun run(prefix: String, appearance: DeviceAppearance) {
        RuntimeEnvironment.setQualifiers("w411dp-h891dp-port-420dpi")
        val host = KeyboardHost()
        var text by mutableStateOf("")
        val focus = FocusRequester()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PodiumTheme(darkTheme = true, displayTheme = appearance.display) {
                val palette = appearance.palette(PodiumTheme.colors.isDark)
                CompositionLocalProvider(
                    LocalInputRouter provides InputRouter(),
                    LocalOverlayHost provides OverlayHost(),
                    LocalKeyboardHost provides host,
                    LocalKeyboardStyle provides KeyboardStyle.PODIUM,
                ) {
                    Box(Modifier.fillMaxSize()) {
                        DeviceBody(palette, Modifier.fillMaxSize())
                        DeviceLayout(
                            modifier = Modifier.fillMaxSize(),
                            screen = {
                                CompositionLocalProvider(LocalScreenInsets provides ScreenInsets(top = ScreenHeaderHeight, bottom = 8.dp)) {
                                    Box(Modifier.fillMaxSize().padding(top = ScreenHeaderHeight + 16.dp, start = 16.dp, end = 16.dp)) {
                                        PodiumTextField(
                                            value = text,
                                            onValueChange = { text = it },
                                            textStyle = PodiumTheme.type.row.copy(color = PodiumTheme.colors.labelPrimary),
                                            description = "Search online",
                                            focusRequester = remember { focus },
                                            action = KeyboardAction.SEARCH,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                }
                                ScreenHeader("Search", canGoBack = true, onBack = {}, playing = false)
                            },
                            wheel = { d -> WheelKeyboard(host, d, palette) { PodWheel(onInput = {}, diameter = d, palette = palette) } },
                            powerButton = { PowerButton(on = true, palette = palette, onToggle = {}) },
                            keyboardOpen = host.isOpen,
                        )
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(300)
        shot("$prefix-0-wheel")
        assertFalse(host.isOpen)
        compose.runOnIdle { focus.requestFocus() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(16)
        assertTrue(host.isOpen, "focusing the field opens the keyboard")
        compose.mainClock.advanceTimeBy(60)
        shot("$prefix-1-opening")
        compose.mainClock.advanceTimeBy(100)
        shot("$prefix-2-opening")
        compose.mainClock.advanceTimeBy(1_200)
        // Type "Hi 5" with the keys themselves.
        tap("Shift")
        tap("H")
        tap("i")
        tap("Space")
        tap("Numbers and symbols")
        tap("5")
        tap("Letters")
        assertEquals("Hi 5", text)
        shot("$prefix-3-typing")
        tap("Delete")
        assertEquals("Hi ", text)
        // The close key folds it back into the Wheel.
        tap("Close keyboard")
        compose.mainClock.advanceTimeBy(70)
        shot("$prefix-4-closing")
        compose.mainClock.advanceTimeBy(1_500)
        assertFalse(host.isOpen)
        compose.onNodeWithContentDescription("Wheel").assertExists()
        shot("$prefix-5-wheel-again")
        // A tap on the field (a finger, in touch mode) opens it again.
        tap("Search online")
        assertTrue(host.isOpen, "tapping the field opens the keyboard")
    }

    @Test fun steelGray() = run("steel", DeviceAppearance(FinishPreset.STEEL_GRAY))

    @Test fun carbon() = run("carbon", DeviceAppearance(display = DisplayTheme.CARBON))

    @Test fun glass() = run("glass", DeviceAppearance())
}
