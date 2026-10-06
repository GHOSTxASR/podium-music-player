package app.podium.core.designsystem.component

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
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
import app.podium.core.designsystem.shell.DeviceAppearance
import app.podium.core.designsystem.shell.DeviceBody
import app.podium.core.designsystem.shell.DeviceLayout
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

/** The loading screen (D-43) in each theme; it appears only after a short wait. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LoadingScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun loadingInEveryTheme() {
        RuntimeEnvironment.setQualifiers("w411dp-h891dp-port-420dpi")
        var theme by mutableStateOf(DisplayTheme.CARBON)
        compose.setContent {
            PodiumTheme(darkTheme = true, displayTheme = theme) {
                val palette = DeviceAppearance(display = theme).palette(PodiumTheme.colors.isDark)
                CompositionLocalProvider(LocalInputRouter provides InputRouter(), LocalOverlayHost provides OverlayHost()) {
                    Box(Modifier.fillMaxSize()) {
                        DeviceBody(palette, Modifier.fillMaxSize())
                        DeviceLayout(
                            modifier = Modifier.fillMaxSize(),
                            screen = {
                                CompositionLocalProvider(LocalScreenInsets provides ScreenInsets(top = ScreenHeaderHeight, bottom = 8.dp)) {
                                    LoadingScreen()
                                }
                                ScreenHeader("Trending", canGoBack = true, onBack = {}, playing = false)
                            },
                            wheel = { d -> PodWheel(onInput = {}, diameter = d, palette = palette) },
                            powerButton = { PowerButton(on = true, palette = palette, onToggle = {}) },
                        )
                    }
                }
            }
        }
        for (t in listOf(DisplayTheme.CARBON, DisplayTheme.BONE, DisplayTheme.GLASS)) {
            theme = t
            compose.mainClock.advanceTimeBy(600)
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Loading").assertExists()
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            File(File("build/screenshots").apply { mkdirs() }, "loading-${t.name.lowercase()}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
