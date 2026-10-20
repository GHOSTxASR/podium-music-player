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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextInput
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
import app.podium.core.designsystem.shell.PowerButton
import app.podium.core.designsystem.shell.ScreenHeader
import app.podium.core.designsystem.shell.ScreenHeaderHeight
import app.podium.core.designsystem.shell.palette
import app.podium.core.designsystem.theme.DisplayTheme
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.interaction.InputRouter
import app.podium.core.interaction.LocalInputRouter
import app.podium.core.interaction.PodiumInput
import app.podium.core.interaction.WheelButton
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Settings ▸ Custom sources (D-91) inside the device on a small screen: every node stays inside the
 * display, the Wheel drives it, and the words say what's going on. Screenshots go to build/screenshots/.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CustomSourcesLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    private val quality = CustomSourceOption(
        "quality", "Quality", CustomSourceOption.Kind.CHOICE, "lossless", "Lossless",
        listOf(CustomSourceOption.Choice("Lossless", "lossless"), CustomSourceOption.Choice("High", "high")),
    )

    private inner class Fake : CustomSourcesSettings {
        override val sources = MutableStateFlow(
            listOf(
                CustomSourceState("custom-1", "Lossless test", "addon.example.com", true, CustomSourceStatus.Ready, listOf(quality)),
                CustomSourceState(
                    "custom-2", "Second", "second.example.com", true,
                    CustomSourceStatus.Problem("Not answering. Songs play as usual, and Podium keeps trying it."), emptyList(),
                ),
            ),
        )
        val added = mutableListOf<String>()
        var answer: AddSourceResult = AddSourceResult.Problem("No source answered at that address. Check it and try again.")
        override suspend fun add(address: String, allowLocalUnencrypted: Boolean): AddSourceResult = answer.also { added += address }
        override fun setEnabled(id: String, enabled: Boolean) = change(id) { it.copy(enabled = enabled) }
        override fun move(id: String, offset: Int) {
            val list = sources.value.toMutableList()
            val from = list.indexOfFirst { it.id == id }
            list.add((from + offset).coerceIn(0, list.lastIndex), list.removeAt(from))
            sources.value = list
        }
        override fun setOption(id: String, key: String, value: String) = Unit
        override fun check(id: String) = change(id) { it.copy(status = CustomSourceStatus.Checking) }
        override fun remove(id: String) {
            sources.value = sources.value.filterNot { it.id == id }
        }
        private fun change(id: String, f: (CustomSourceState) -> CustomSourceState) {
            sources.value = sources.value.map { if (it.id == id) f(it) else it }
        }
    }

    private val router = InputRouter()
    private val overlay = OverlayHost()

    @Test
    fun `the list shows each source and how it's doing, then Add a source`() {
        val fake = Fake()
        var opened: String? = null
        show("custom-sources", "Custom sources") { CustomSourcesScreen(fake, onAdd = {}, onOpen = { opened = it }) }
        compose.onNodeWithText("Lossless test").assertIsDisplayed()
        compose.onNodeWithText("Not answering").assertIsDisplayed()
        compose.onNodeWithText("Add a source").assertIsDisplayed()
        press(WheelButton.CENTER)
        assertEquals("custom-1", opened)
    }

    @Test
    fun `an empty list explains what a custom source is for`() {
        val fake = Fake().apply { sources.value = emptyList() }
        var adding = false
        show("custom-sources-empty", "Custom sources") { CustomSourcesScreen(fake, onAdd = { adding = true }, onOpen = {}) }
        compose.onNodeWithText("For your own streams", substring = true).assertExists()
        press(WheelButton.CENTER)
        assertTrue(adding)
    }

    @Test
    fun `a source's menu turns it off, moves it and asks before removing`() {
        val fake = Fake()
        var removed = false
        show("custom-source", "Lossless test") { CustomSourceScreen(fake, "custom-1", onEditText = {}, onRemoved = { removed = true }) }
        compose.onNodeWithText("Quality").assertIsDisplayed()
        compose.onNodeWithText("Asked 1st").assertIsDisplayed()
        press(WheelButton.CENTER)
        assertEquals(false, fake.sources.value.first().enabled, "Center on the first row turns it off")
        // Down to Move down (Use, Quality, Move up, Move down).
        rotate(3)
        press(WheelButton.CENTER)
        assertEquals("custom-1", fake.sources.value.last().id)
        // Remove asks first.
        rotate(10)
        press(WheelButton.CENTER)
        assertEquals("Remove Lossless test?", overlay.menu?.title)
        overlay.menu!!.actions.first { it.label == "Remove" }.onSelect()
        compose.waitForIdle()
        assertTrue(removed)
    }

    @Test
    fun `a source that isn't answering says so on its menu`() {
        val fake = Fake()
        show("custom-source-problem", "Second") { CustomSourceScreen(fake, "custom-2", onEditText = {}, onRemoved = {}) }
        compose.onNodeWithText("Not answering. Songs play as usual, and Podium keeps trying it.").assertExists()
        compose.onNodeWithText("second.example.com").assertExists()
    }

    @Test
    fun `adding asks the address and explains a problem`() {
        val fake = Fake()
        var added: String? = null
        show("custom-source-add", "Add a source") { AddCustomSourceScreen(fake, onAdded = { added = it }) }
        compose.onNodeWithText("Type or paste the source's address, like https://addon.example.com.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Source address").performTextInput("https://nothing.example.com")
        press(WheelButton.CENTER)
        assertEquals(listOf("https://nothing.example.com"), fake.added)
        compose.onNodeWithText("No source answered at that address. Check it and try again.").assertIsDisplayed()
        fake.answer = AddSourceResult.Added("custom-3", "Third")
        press(WheelButton.CENTER)
        assertEquals("custom-3", added)
        capture("custom-source-add-problem")
    }

    @Test
    fun `the words`() {
        val list = Fake().sources.value
        assertEquals("None", customSourcesSummary(emptyList()))
        assertEquals("Lossless test", customSourcesSummary(list.take(1)))
        assertEquals("2 of 2 on", customSourcesSummary(list))
        assertEquals("Off", customSourcesSummary(list.map { it.copy(enabled = false) }))
        assertEquals(listOf("1st", "2nd", "3rd", "4th", "11th", "12th", "21st"), listOf(1, 2, 3, 4, 11, 12, 21).map(::ordinal))
    }

    private fun rotate(detents: Int) {
        compose.runOnIdle { router.dispatch(PodiumInput.Rotate(detents)) }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    private fun press(button: WheelButton) {
        compose.runOnIdle { router.dispatch(PodiumInput.Press(button)) }
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
    }

    private fun show(name: String, title: String, screen: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers("w360dp-h640dp-port-xhdpi")
        val appearance = DeviceAppearance(display = DisplayTheme.CARBON)
        compose.setContent {
            PodiumTheme(darkTheme = true, displayTheme = appearance.display, display = appearance.screen) {
                val palette = appearance.palette(PodiumTheme.colors.isDark)
                CompositionLocalProvider(LocalInputRouter provides router, LocalOverlayHost provides overlay) {
                    Box(Modifier.fillMaxSize()) {
                        DeviceBody(palette, Modifier.fillMaxSize())
                        DeviceLayout(
                            modifier = Modifier.fillMaxSize(),
                            screen = {
                                CompositionLocalProvider(LocalScreenInsets provides ScreenInsets(top = ScreenHeaderHeight, bottom = 8.dp)) { screen() }
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
        capture(name)
    }

    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(File("build/screenshots").apply { mkdirs() }, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun SemanticsNode.descendants(): List<SemanticsNode> = children.flatMap { listOf(it) + it.descendants() }

    private fun Rect.contains(other: Rect) =
        other.left >= left - 0.5f && other.top >= top - 0.5f && other.right <= right + 0.5f && other.bottom <= bottom + 0.5f
}
