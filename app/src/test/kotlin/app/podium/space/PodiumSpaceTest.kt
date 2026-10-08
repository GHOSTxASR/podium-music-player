package app.podium.space

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.podium.core.designsystem.component.PodWheel
import app.podium.core.designsystem.shell.DeviceAppearance
import app.podium.core.designsystem.shell.DeviceBody
import app.podium.core.designsystem.shell.DeviceLayout
import app.podium.core.designsystem.shell.FinishPreset
import app.podium.core.designsystem.shell.PowerButton
import app.podium.core.designsystem.shell.palette
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.interaction.InputRouter
import app.podium.core.interaction.LocalInputRouter
import app.podium.core.interaction.PodiumInput
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import app.podium.stickers.StickerBorder
import app.podium.stickers.StickerLayer
import app.podium.stickers.StickerStore
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Podium's space (D-54): a two-finger pinch takes the object into it, one finger never does, the
 * Wheel under the fingers lets go, and Return brings the flat app back. Screenshots of the object
 * in its space go to build/screenshots/ for review.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PodiumSpaceTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var space: PodiumSpaceState
    private val wheelInputs = mutableListOf<PodiumInput>()
    private var turnedOff = 0
    private val store by lazy { StickerStore(Files.createTempDirectory("stickers").toFile(), io = { it.run() }) }

    private fun podium(page: SpacePage = SpacePage.MAIN) {
        RuntimeEnvironment.setQualifiers("w411dp-h891dp-port-420dpi")
        compose.setContent {
            space = rememberPodiumSpaceState()
            PodiumTheme(darkTheme = true) {
                val palette = DeviceAppearance(FinishPreset.BURGUNDY).palette(darkTheme = true)
                CompositionLocalProvider(LocalInputRouter provides InputRouter()) {
                    PodiumSpace(
                        state = space,
                        edgeColor = palette.bodyBottom,
                        spaceTint = Color(0xFF050506),
                        modifier = Modifier.testTag("space"),
                        stickers = { StickerLayer(store) },
                        panel = {
                            SpacePanel(
                                space,
                                page,
                                onPage = {},
                                stickers = { StickerGallery(store, onAdd = {}, onOpen = {}, onArrange = {}) },
                                sticker = {},
                                help = { HelpContent {} },
                                onTurnOff = { turnedOff++ },
                            )
                        },
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            DeviceBody(palette, Modifier.fillMaxSize())
                            DeviceLayout(
                                modifier = Modifier.fillMaxSize(),
                                screen = { Box(Modifier.fillMaxSize()) },
                                wheel = { d -> PodWheel(onInput = { wheelInputs += it }, diameter = d, palette = palette, modifier = Modifier.testTag("wheel")) },
                                powerButton = { PowerButton(on = true, palette = palette, onToggle = {}) },
                            )
                        }
                    }
                }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(100)
    }

    @Test
    fun `a two-finger pinch takes Podium into its space`() {
        podium()
        compose.onNodeWithTag("space").performTouchInput {
            pinch(
                start0 = Offset(width * 0.25f, height * 0.35f), end0 = Offset(width * 0.45f, height * 0.48f),
                start1 = Offset(width * 0.75f, height * 0.65f), end1 = Offset(width * 0.55f, height * 0.52f),
                durationMillis = 400,
            )
        }
        compose.mainClock.advanceTimeBy(1_500)
        assertEquals(SpacePhase.PHYSICAL, space.phase)
        assertTrue(space.depth.value > 0.98f)
        screenshot("space-burgundy")
    }

    @Test
    fun `one finger turning the Wheel never enters the space`() {
        podium()
        compose.onNodeWithTag("wheel").performTouchInput {
            swipe(Offset(width * 0.85f, height * 0.5f), Offset(width * 0.5f, height * 0.9f), durationMillis = 300)
        }
        compose.mainClock.advanceTimeBy(800)
        assertEquals(SpacePhase.NORMAL, space.phase)
        assertTrue(wheelInputs.any { it is PodiumInput.Rotate }, "the Wheel still turns")
    }

    @Test
    fun `a pinch over the Wheel doesn't turn it`() {
        podium()
        compose.onNodeWithTag("wheel").performTouchInput {
            pinch(
                start0 = Offset(width * 0.1f, height * 0.5f), end0 = Offset(width * 0.42f, height * 0.5f),
                start1 = Offset(width * 0.9f, height * 0.5f), end1 = Offset(width * 0.58f, height * 0.5f),
                durationMillis = 400,
            )
        }
        compose.mainClock.advanceTimeBy(1_500)
        assertEquals(SpacePhase.PHYSICAL, space.phase)
        assertTrue(wheelInputs.none { it is PodiumInput.Rotate }, "no rotation from a pinch: $wheelInputs")
    }

    @Test
    fun `a small pinch springs back, and Return brings the flat Podium back`() {
        podium()
        compose.onNodeWithTag("space").performTouchInput {
            pinch(
                start0 = Offset(width * 0.3f, height * 0.4f), end0 = Offset(width * 0.33f, height * 0.42f),
                start1 = Offset(width * 0.7f, height * 0.6f), end1 = Offset(width * 0.67f, height * 0.58f),
                durationMillis = 300,
            )
        }
        compose.mainClock.advanceTimeBy(1_500)
        assertEquals(SpacePhase.NORMAL, space.phase, "too small to be a pinch")

        compose.runOnUiThread { space.enter() }
        compose.mainClock.advanceTimeBy(1_500)
        assertEquals(SpacePhase.PHYSICAL, space.phase)
        compose.runOnUiThread { space.leave() }
        compose.mainClock.advanceTimeBy(1_500)
        assertEquals(SpacePhase.NORMAL, space.phase)
        assertEquals(0f, space.depth.value)
    }

    @Test
    fun `the help page in the space`() {
        podium(SpacePage.HELP)
        compose.runOnUiThread { space.enter() }
        compose.mainClock.advanceTimeBy(1_500)
        screenshot("space-help")
    }

    @Test
    fun `every visit opens on the settings page, large`() {
        podium()
        compose.runOnUiThread { space.enter() }
        compose.mainClock.advanceTimeBy(1_500)
        assertEquals(SpaceFocus.SETTINGS, space.focus)
        assertEquals(0f, space.zoom.value)
        compose.onNodeWithText("Stickers").assertExists()
        compose.onNodeWithText("Turn off Podium").assertExists()
        compose.onNodeWithText("Personalize").assertDoesNotExist()
        screenshot("space-settings-large")

        // Podium, at the bottom: the Podium comes close, the page goes small into its corner.
        compose.onNode(hasText("Podium") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).performClick()
        compose.mainClock.advanceTimeBy(1_500)
        assertEquals(SpaceFocus.PODIUM, space.focus)
        assertTrue(space.zoom.value > 0.98f)
        screenshot("space-podium-close")

        // Settings brings the page back; and leaving and coming back starts large again.
        compose.onNode(hasText("Settings") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).performClick()
        compose.mainClock.advanceTimeBy(1_500)
        assertEquals(SpaceFocus.SETTINGS, space.focus)
        assertTrue(space.zoom.value < 0.02f)
        compose.runOnUiThread { space.focusPodium() }
        compose.mainClock.advanceTimeBy(1_500)
        compose.runOnUiThread { space.leave() }
        compose.mainClock.advanceTimeBy(1_500)
        compose.runOnUiThread { space.enter() }
        compose.mainClock.advanceTimeBy(1_500)
        assertEquals(SpaceFocus.SETTINGS, space.focus)
        assertEquals(0f, space.zoom.value)
    }

    @Test
    fun `a tap on the small Podium brings it close, and a tap when close returns to it`() {
        podium()
        compose.runOnUiThread { space.enter() }
        compose.mainClock.advanceTimeBy(1_500)
        compose.onNodeWithTag("space").performTouchInput { click(Offset(width * 0.2f, height * 0.45f)) }
        compose.mainClock.advanceTimeBy(1_500)
        assertEquals(SpaceFocus.PODIUM, space.focus)
        compose.onNodeWithTag("space").performTouchInput { click(Offset(width * 0.3f, height * 0.45f)) }
        compose.mainClock.advanceTimeBy(1_500)
        assertEquals(SpacePhase.NORMAL, space.phase)
    }

    @Test
    fun `the gallery shows every sticker, and Turn off asks the app to close`() {
        repeat(3) { i -> store.add(testSticker(i), testSticker(i), StickerBorder.WHITE, 0.5f) }
        store.place(store.stickers.value.first().id)
        podium()
        compose.runOnUiThread { space.enter() }
        compose.mainClock.advanceTimeBy(1_500)
        compose.onNodeWithContentDescription("Add a sticker").assertExists()
        assertEquals(3, compose.onAllNodesWithContentDescription("Sticker", substring = true).fetchSemanticsNodes().size)
        compose.mainClock.advanceTimeBy(500)
        screenshot("space-gallery")
        compose.onNodeWithText("Turn off Podium").performClick()
        assertEquals(1, turnedOff)
    }

    /** A stand-in sticker: a coloured disc on clear. */
    private fun testSticker(seed: Int): Bitmap {
        val b = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        val colors = intArrayOf(0xFFE5484D.toInt(), 0xFF46A758.toInt(), 0xFFF5D90A.toInt())
        c.drawCircle(60f, 60f, 50f, android.graphics.Paint().apply { color = colors[seed % colors.size]; isAntiAlias = true })
        return b
    }

    private fun screenshot(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(File("build/screenshots").apply { mkdirs() }, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
