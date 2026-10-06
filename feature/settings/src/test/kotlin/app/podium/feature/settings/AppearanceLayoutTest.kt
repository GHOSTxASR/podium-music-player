package app.podium.feature.settings

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.podium.core.designsystem.component.LocalOverlayHost
import app.podium.core.designsystem.component.LocalScreenInsets
import app.podium.core.designsystem.component.OverlayHost
import app.podium.core.designsystem.component.PodWheel
import app.podium.core.designsystem.component.ScreenInsets
import app.podium.core.designsystem.shell.DeviceAppearance
import app.podium.core.designsystem.shell.DeviceBody
import app.podium.core.designsystem.shell.DeviceLayout
import app.podium.core.designsystem.shell.DisplayTestTag
import app.podium.core.designsystem.shell.FinishPreset
import app.podium.core.designsystem.shell.Glitter
import app.podium.core.designsystem.shell.PowerButton
import app.podium.core.designsystem.shell.ScreenHeader
import app.podium.core.designsystem.shell.ScreenHeaderHeight
import app.podium.core.designsystem.shell.palette
import app.podium.core.designsystem.theme.DisplayTheme
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.interaction.InputRouter
import app.podium.core.interaction.LocalInputRouter
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertTrue

/**
 * Settings ▸ Appearance (D-41) inside the device at a small and a phone screen: every node stays
 * inside the display. Screenshots go to build/screenshots/ for review.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppearanceLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    private class Settings(initial: DeviceAppearance) : DeviceSettingsRepository {
        override val appearance = MutableStateFlow(initial)
        override val preview = MutableStateFlow<DeviceAppearance?>(null)
        override val startupSound = MutableStateFlow(true)
        override val haptics = MutableStateFlow(true)
        override val clicks = MutableStateFlow(false)
        override val autoplay = MutableStateFlow(true)
        override val onlineRecommendations = MutableStateFlow(true)
        override val avoidRepeats = MutableStateFlow(true)
        override val stayInPodium = MutableStateFlow(true)
        override val onlineLyrics = MutableStateFlow(false)
        override fun setStayInPodium(enabled: Boolean) = Unit
        override fun setOnlineLyrics(enabled: Boolean) = Unit
        override fun setAppearance(appearance: DeviceAppearance) { this.appearance.value = appearance }
        override fun setPreview(appearance: DeviceAppearance?) { preview.value = appearance }
        override fun setStartupSound(enabled: Boolean) = Unit
        override fun setHaptics(enabled: Boolean) = Unit
        override fun setClicks(enabled: Boolean) = Unit
        override fun setAutoplay(enabled: Boolean) = Unit
        override fun setOnlineRecommendations(enabled: Boolean) = Unit
        override fun setAvoidRepeats(enabled: Boolean) = Unit
    }

    private val glitterOn = DeviceAppearance(FinishPreset.BURGUNDY, glitter = Glitter(enabled = true))
    private val carbon = DeviceAppearance(display = DisplayTheme.CARBON)

    @Test fun appearanceSmall() = check("appearance-small", SMALL, carbon, "Appearance") { AppearanceScreen(it, {}, {}) }

    @Test fun deviceBodyGlitter() = check("device-body", PHONE, glitterOn, "Device body") { DeviceBodyScreen(it, {}, {}, {}) }

    @Test fun deviceBodyCarbonSmall() = check("device-body-carbon-small", SMALL, carbon, "Device body") { DeviceBodyScreen(it, {}, {}, {}) }

    @Test fun virtualDisplayCarbon() = check("virtual-display-carbon", SMALL, carbon, "Virtual display") {
        VirtualDisplayScreen(it, BackgroundImageStatus.NONE, {}, {}, {}, {}, {})
    }

    @Test fun fontPicker() = check("fonts", PHONE, carbon, "Font") { FontScreen(it) }

    @Test fun backgroundPicker() = check("background", SMALL, DeviceAppearance(display = DisplayTheme.CUSTOM), "Background") {
        BackgroundScreen(it, BackgroundImageStatus.NONE) {}
    }

    @Test fun levelEditor() = check("level-glitter-size", SMALL, glitterOn, "Glitter size") { LevelScreen(it, AppearanceLevel.GlitterSize) }

    private fun check(name: String, qualifiers: String, appearance: DeviceAppearance, title: String, screen: @Composable (DeviceSettingsRepository) -> Unit) {
        RuntimeEnvironment.setQualifiers(qualifiers)
        val settings = Settings(appearance)
        compose.setContent {
            val a = settings.preview.value ?: settings.appearance.value
            PodiumTheme(darkTheme = true, displayTheme = a.display, display = a.screen) {
                val palette = a.palette(PodiumTheme.colors.isDark)
                CompositionLocalProvider(LocalInputRouter provides InputRouter(), LocalOverlayHost provides OverlayHost()) {
                    Box(Modifier.fillMaxSize()) {
                        DeviceBody(palette, Modifier.fillMaxSize())
                        DeviceLayout(
                            modifier = Modifier.fillMaxSize(),
                            screen = {
                                CompositionLocalProvider(LocalScreenInsets provides ScreenInsets(top = ScreenHeaderHeight, bottom = 8.dp)) { screen(settings) }
                                ScreenHeader(title, canGoBack = true, onBack = {}, playing = false)
                            },
                            wheel = { d -> PodWheel(onInput = {}, diameter = d, palette = palette) },
                            powerButton = { PowerButton(on = true, palette = palette, onToggle = {}) },
                        )
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        val display = compose.onNodeWithTag(DisplayTestTag, useUnmergedTree = true).fetchSemanticsNode()
        display.descendants().forEach { node ->
            if (node.boundsInRoot.isEmpty) return@forEach
            val label = node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text ?: "node ${node.id}"
            assertTrue(display.boundsInRoot.contains(node.boundsInRoot), "$label at ${node.boundsInRoot} escapes the display ($name)")
        }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(File("build/screenshots").apply { mkdirs() }, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun SemanticsNode.descendants(): List<SemanticsNode> = children.flatMap { listOf(it) + it.descendants() }

    private fun Rect.contains(other: Rect) =
        other.left >= left - 0.5f && other.top >= top - 0.5f && other.right <= right + 0.5f && other.bottom <= bottom + 0.5f

    private companion object {
        const val PHONE = "w411dp-h891dp-port-420dpi"
        const val SMALL = "w360dp-h640dp-port-xhdpi"
    }
}
