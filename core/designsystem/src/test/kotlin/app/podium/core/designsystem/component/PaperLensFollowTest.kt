package app.podium.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.podium.core.designsystem.theme.DisplayTheme
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.interaction.FocusGeometry
import app.podium.core.interaction.FocusListState
import app.podium.core.interaction.InputRouter
import app.podium.core.interaction.LocalInputRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * On the paper the list follows the lens (interaction-model.md §5.1): it is scrolled from the lens's
 * own animation frames, so the two can't drift apart however fast the Wheel turns — the indicator
 * holds still at the middle of a long list while the rows move under it.
 */
@RunWith(AndroidJUnit4::class)
class PaperLensFollowTest {

    @get:Rule
    val compose = createComposeRule()

    /** Rows of two heights, so the lens is interpolated between rows that differ. */
    private fun rowHeight(i: Int) = if (i % 7 == 3) 90.dp else 60.dp

    @Test
    fun `wherever the lens is between two rows, the list puts it at the middle`() {
        val state = FocusListState()
        lateinit var scope: CoroutineScope
        compose.setContent {
            scope = rememberCoroutineScope()
            LazyColumn(state = state.listState, modifier = Modifier.height(700.dp), contentPadding = PaddingValues(top = 52.dp, bottom = 80.dp)) {
                items(COUNT) { i -> Box(Modifier.fillMaxWidth().height(rowHeight(i))) }
            }
        }
        compose.runOnIdle { state.itemCount = COUNT }

        var position = 0f
        var sawMiddle = false
        while (position <= COUNT - 1f) {
            val p = position
            compose.runOnIdle { assertTrue(state.centreLensNow(p), "at $p the lens's rows are laid out") }
            compose.waitForIdle()
            compose.runOnIdle {
                val (centre, readable) = lensCentre(state, p)
                val list = state.listState
                when {
                    // An end stops the list; the lens travels on toward the real top or bottom.
                    !list.canScrollBackward -> assertTrue(centre <= readable / 2f + 1f, "at $p (top) the lens is at or above the middle")
                    !list.canScrollForward -> assertTrue(centre >= readable / 2f - 1f, "at $p (bottom) the lens is at or below the middle")
                    else -> {
                        sawMiddle = true
                        assertTrue(abs(centre - readable / 2f) <= 1f, "at $p the lens sits at the middle ($centre vs ${readable / 2f})")
                    }
                }
            }
            position += 0.37f
        }
        assertTrue(sawMiddle, "the walk crossed the middle of the list")

        // A jump far along the list: its rows aren't laid out, so the list brings them in first.
        compose.runOnIdle { state.listState.requestScrollToItem(0) }
        compose.waitForIdle()
        compose.runOnIdle {
            state.focus(80)
            assertTrue(!state.centreLensNow(80f), "row 80 isn't laid out from the top")
        }
        compose.runOnIdle { scope.launch { state.centreLens(80f) } }
        compose.waitForIdle()
        compose.runOnIdle {
            val (centre, readable) = lensCentre(state, 80f)
            assertTrue(abs(centre - readable / 2f) <= 1f, "after the jump the lens sits at the middle ($centre vs ${readable / 2f})")
        }
    }

    @Test
    fun `a fast spin keeps the focused row in view and the scroll moving one way, then settles at the middle`() {
        val state = FocusListState()
        val items = (0 until COUNT).map { "Song $it" }
        compose.setContent {
            PodiumTheme(displayTheme = DisplayTheme.CARBON) {
                CompositionLocalProvider(LocalInputRouter provides InputRouter(), LocalOverlayHost provides OverlayHost()) {
                    Box(Modifier.size(400.dp, 700.dp)) {
                        FocusList(
                            items = items,
                            state = state,
                            key = { it },
                            contentPadding = PaddingValues(top = 52.dp, bottom = 80.dp),
                            onActivate = {},
                        ) { item, _, focused -> MenuRow(item, focused) }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(400)
        compose.mainClock.autoAdvance = false

        // Two rows a detent, a detent a frame: faster than any spring can settle in between.
        var lastScroll = -1
        repeat(30) {
            compose.runOnIdle { state.moveBy(2) }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                val list = state.listState
                val scroll = list.firstVisibleItemIndex * 10_000 + list.firstVisibleItemScrollOffset
                assertTrue(scroll >= lastScroll, "the list only moves down during a downward spin ($scroll after $lastScroll)")
                lastScroll = scroll
                val info = list.layoutInfo
                val focused = info.visibleItemsInfo.firstOrNull { it.index == state.focusedIndex }
                if (focused != null) {
                    assertTrue(focused.offset + focused.size > 0 && focused.offset < info.viewportEndOffset - info.afterContentPadding, "the focused row stays in view")
                }
            }
        }

        compose.mainClock.autoAdvance = true
        compose.mainClock.advanceTimeBy(1_200)
        compose.waitForIdle()
        assertEquals(60, compose.runOnIdle { state.focusedIndex })
        compose.runOnIdle {
            val info = state.listState.layoutInfo
            val readable = info.viewportEndOffset - info.afterContentPadding
            val row = info.visibleItemsInfo.first { it.index == state.focusedIndex }
            assertTrue(abs(row.offset + row.size / 2f - readable / 2f) <= 1f, "the focused row settles at the middle")
        }
        compose.onAllNodes(SemanticsMatcher("selected") { it.config.getOrNull(SemanticsProperties.Selected) == true })
            .fetchSemanticsNodes().let { assertEquals(1, it.size, "exactly one row is focused") }
    }

    /** The lens's centre in the readable region's coordinates, and the region's height. */
    private fun lensCentre(state: FocusListState, position: Float): Pair<Float, Float> {
        val info = state.listState.layoutInfo
        val lens = FocusGeometry.lens(position, state.focusedIndex) { i ->
            info.visibleItemsInfo.firstOrNull { it.index == i }?.let { FocusGeometry.Row(it.offset, it.size) }
        } ?: error("no lens at $position")
        return (lens.first + lens.second / 2f) to (info.viewportEndOffset - info.afterContentPadding).toFloat()
    }

    private companion object {
        const val COUNT = 100
    }
}
