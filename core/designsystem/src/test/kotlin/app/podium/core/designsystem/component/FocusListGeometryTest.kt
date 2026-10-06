package app.podium.core.designsystem.component

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import app.podium.core.interaction.FocusListState
import app.podium.core.interaction.InputRouter
import app.podium.core.interaction.LocalInputRouter
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The focus list at its edges (D-40, UI_DEVICE_ACCEPTANCE.md §1), inside the device at a phone and a
 * small screen: lists of 1, 2, 3, 5, 10 and 50 rows (some with titles too long to fit), the focus
 * walked with the Wheel, one detent at a time, to the first, a middle and the last row and back.
 *
 * Checked every time:
 * - the focused row is entirely inside the readable region (between the title and the bottom);
 * - no row inside the readable region is cut off at either side;
 * - a list that fits starts at the top — no empty space above the first row;
 * - in a long list the focused row rides at the middle, and the first and last rows sit flush with
 *   the region's edges when focused.
 *
 * Screenshots go to build/screenshots/ for review.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FocusListGeometryTest {

    @get:Rule
    val compose = createComposeRule()

    private class Scenario(val count: Int) {
        val state = FocusListState()
        val items = (1..count).map { i -> if (i % 3 == 2) "A title far too long to fit on one row, number $i" else "Item $i" }
    }

    @Test fun carbonPhone() = sweep("carbon", PHONE, DisplayTheme.CARBON)

    @Test fun boneSmall() = sweep("bone-small", SMALL, DisplayTheme.BONE)

    @Test fun glassPhone() = sweep("glass", PHONE, DisplayTheme.GLASS, counts = listOf(3, 50))

    private fun sweep(prefix: String, qualifiers: String, theme: DisplayTheme, counts: List<Int> = listOf(1, 2, 3, 5, 10, 50)) {
        RuntimeEnvironment.setQualifiers(qualifiers)
        var scenario by mutableStateOf(Scenario(1))
        compose.setContent {
            PodiumTheme(displayTheme = theme) {
                val palette = DeviceAppearance(display = theme).palette(darkTheme = true)
                CompositionLocalProvider(LocalInputRouter provides InputRouter(), LocalOverlayHost provides OverlayHost()) {
                    Box(Modifier.fillMaxSize()) {
                        DeviceBody(palette, Modifier.fillMaxSize())
                        DeviceLayout(
                            modifier = Modifier.fillMaxSize(),
                            screen = {
                                CompositionLocalProvider(LocalScreenInsets provides INSETS) {
                                    key(scenario) {
                                        FocusList(
                                            items = scenario.items,
                                            state = scenario.state,
                                            key = { it },
                                            contentPadding = INSETS.listPadding(),
                                            onActivate = {},
                                            modifier = Modifier.fillMaxSize(),
                                        ) { item, _, focused -> MenuRow(item, focused) }
                                    }
                                }
                                ScreenHeader("Focus", canGoBack = true, onBack = {}, playing = false)
                            },
                            wheel = { d -> PodWheel(onInput = {}, diameter = d, palette = palette) },
                            powerButton = { PowerButton(on = true, palette = palette, onToggle = {}) },
                        )
                    }
                }
            }
        }
        for (count in counts) {
            scenario = Scenario(count)
            compose.mainClock.advanceTimeBy(400)
            val last = count - 1
            check("$prefix-$count-first", scenario, Expect.TOP)
            if (count >= 3) {
                walk(scenario.state, count / 2)
                check("$prefix-$count-middle", scenario, Expect.CENTRE)
            }
            if (count >= 2) {
                walk(scenario.state, last)
                check("$prefix-$count-last", scenario, Expect.BOTTOM)
                // And all the way back up.
                walk(scenario.state, 0)
                check("$prefix-$count-back-to-first", scenario, Expect.TOP)
            }
        }
    }

    private enum class Expect { TOP, CENTRE, BOTTOM }

    /** Turn the Wheel one detent at a time until [target] is focused. */
    private fun walk(state: FocusListState, target: Int) {
        while (state.focusedIndex != target) {
            compose.runOnIdle { state.moveBy(if (target > state.focusedIndex) 1 else -1) }
            compose.mainClock.advanceTimeBy(140)
        }
        compose.mainClock.advanceTimeBy(1_200)
        compose.waitForIdle()
    }

    private fun check(name: String, scenario: Scenario, expect: Expect) {
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        val display = compose.onNodeWithTag(DisplayTestTag, useUnmergedTree = true).fetchSemanticsNode()
        val density = compose.density.density
        val readableTop = display.boundsInRoot.top + (INSETS.top.value + 8f) * density
        val readableBottom = display.boundsInRoot.bottom - (INSETS.bottom.value + 8f) * density
        val nodes = display.descendants()

        val focused = nodes.firstOrNull { it.config.getOrNull(SemanticsProperties.Selected) == true }
        assertNotNull(focused, "$name: a row is focused")
        val f = focused.unclipped()
        val tolerance = 2f * density
        assertTrue(f.top >= readableTop - tolerance && f.bottom <= readableBottom + tolerance, "$name: the focused row $f is inside the readable region $readableTop..$readableBottom")

        // No row inside the readable region is cut off (the list used to clip rows bent along the
        // arc), and no text outside the focused row (whose long title scrolls inside its own box).
        val focusedText = focused.descendants().map { it.id }.toSet()
        nodes.filter { it.config.getOrNull(SemanticsProperties.Selected) != null || (it.config.getOrNull(SemanticsProperties.Text) != null && it.id !in focusedText) }
            .forEach { node ->
                val u = node.unclipped()
                if (u.top < readableTop || u.bottom > readableBottom) return@forEach
                val c = node.boundsInRoot
                val label = node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text ?: "row"
                assertTrue(abs(c.left - u.left) < 1f && abs(c.right - u.right) < 1f, "$name: '$label' is clipped: $c of $u")
            }

        val rows = nodes.filter { it.config.getOrNull(SemanticsProperties.Selected) != null }.map { it.unclipped() }
        val list = scenario.state.listState
        val fits = !list.canScrollForward && !list.canScrollBackward
        when {
            // A list that fits starts at the top: nothing above its first row.
            fits || expect == Expect.TOP -> {
                val firstTop = rows.minOf { it.top }
                assertTrue(abs(firstTop - readableTop) <= tolerance, "$name: the first row starts at the top ($firstTop vs $readableTop)")
            }
            // The last row sits on the bottom: nothing below it.
            expect == Expect.BOTTOM -> assertTrue(abs(f.bottom - readableBottom) <= tolerance, "$name: the last row sits on the bottom (${f.bottom} vs $readableBottom)")
            // In the middle of a long list the focused row rides at the middle, unless an end is
            // closer than half the region (then that end holds, as above).
            list.canScrollForward && list.canScrollBackward -> {
                val middle = (readableTop + readableBottom) / 2f
                assertTrue(abs(f.center.y - middle) <= tolerance, "$name: the focused row rides at the middle (${f.center.y} vs $middle)")
            }
        }

        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(File("build/screenshots").apply { mkdirs() }, "focus-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The node's bounds in the root with transforms applied but without any clipping. */
    private fun SemanticsNode.unclipped(): Rect {
        val coordinates = layoutInfo.coordinates
        return coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = false)
    }

    private fun SemanticsNode.descendants(): List<SemanticsNode> = children.flatMap { listOf(it) + it.descendants() }

    private companion object {
        const val PHONE = "w411dp-h891dp-port-420dpi"
        const val SMALL = "w360dp-h640dp-port-xhdpi"
        val INSETS = ScreenInsets(top = ScreenHeaderHeight, bottom = 8.dp)
    }
}
