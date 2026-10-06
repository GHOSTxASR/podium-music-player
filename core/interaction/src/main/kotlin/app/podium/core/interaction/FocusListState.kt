package app.podium.core.interaction

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlin.math.abs

/**
 * The single focus of a list (interaction-model.md §1, §5). Rotation moves it, touch scrolling
 * leaves it alone, and the next rotation snaps it back into view first.
 */
@Stable
class FocusListState(initialIndex: Int = 0, val listState: LazyListState = LazyListState()) {
    var focusedIndex by mutableIntStateOf(initialIndex)
        private set

    var itemCount by mutableIntStateOf(0)

    /** Which rows can hold focus (section headers can't). Set by the list each composition. */
    var isFocusable: (Int) -> Boolean = { true }

    /** Incremented whenever focus moves by input (not by clamping), so the lens can animate. */
    var focusMoves by mutableIntStateOf(0)
        private set

    /** Move by [delta] items. @return false if focus was already at the boundary in that direction. */
    fun moveBy(delta: Int): Boolean {
        if (itemCount == 0 || delta == 0) return false
        val visible = visibleIndices()
        // After a touch scroll moved the focused row off-screen, rotation starts from what's visible.
        val base = if (!visible.isEmpty() && focusedIndex !in visible) {
            if (focusedIndex < visible.first) visible.first else visible.last
        } else focusedIndex
        val target = stepFrom(base, delta)
        if (target == focusedIndex) return false
        focusedIndex = target
        focusMoves++
        return true
    }

    fun focus(index: Int) {
        if (index in 0 until kotlin.math.max(itemCount, 1) && index != focusedIndex) {
            focusedIndex = index
            focusMoves++
        }
    }

    fun clamp() {
        if (itemCount > 0 && focusedIndex > itemCount - 1) focusedIndex = itemCount - 1
        if (itemCount > 0 && !isFocusable(focusedIndex)) {
            focusedIndex = stepFrom(focusedIndex, 1).takeIf(isFocusable) ?: stepFrom(focusedIndex, -1)
        }
    }

    /** [delta] focusable rows away from [from], stopping at the last focusable row each way. */
    private fun stepFrom(from: Int, delta: Int): Int {
        val dir = if (delta > 0) 1 else -1
        var index = from
        var remaining = kotlin.math.abs(delta)
        var i = from + dir
        while (remaining > 0 && i in 0 until itemCount) {
            if (isFocusable(i)) {
                index = i
                remaining--
            }
            i += dir
        }
        return index
    }

    /** Indices fully inside the readable region (between the content paddings). */
    fun visibleIndices(): IntRange {
        val info = listState.layoutInfo
        val end = info.viewportEndOffset - info.afterContentPadding
        val visible = info.visibleItemsInfo.filter { it.offset >= 0 && it.offset + it.size <= end }
        return if (visible.isEmpty()) IntRange.EMPTY else visible.first().index..visible.last().index
    }

    /**
     * Scroll so the focused row sits where [scroll] wants it (FocusGeometry): at the middle of the
     * readable region on the paper, or with a neighbour in view in a plain list — the first and last
     * rows flush with the region's edges, never partly hidden. A short step glides with [glide];
     * anything further — a fast spin that outran the last glide — jumps, so the list is never
     * behind the focus. Call with the latest focus only (cancel the previous call).
     */
    suspend fun keepFocusedInView(
        scroll: FocusScroll = FocusScroll.EDGE,
        glide: AnimationSpec<Float> = tween(GLIDE_MILLIS, easing = FastOutSlowInEasing),
    ) {
        var info = listState.layoutInfo
        var jumped = false
        if (info.visibleItemsInfo.none { it.index == focusedIndex }) {
            // The focused row isn't even laid out: bring it in, then place it.
            if (focusedIndex !in 0 until info.totalItemsCount) return
            listState.scrollToItem(focusedIndex)
            info = listState.layoutInfo
            jumped = true
        }
        val rows = info.visibleItemsInfo
        val item = rows.firstOrNull { it.index == focusedIndex } ?: return
        fun row(index: Int) = rows.firstOrNull { it.index == index }?.let { FocusGeometry.Row(it.offset, it.size) }
        val delta = FocusGeometry.scrollDelta(
            mode = scroll,
            readable = info.viewportEndOffset - info.afterContentPadding,
            focused = FocusGeometry.Row(item.offset, item.size),
            previous = row(focusedIndex - 1),
            next = row(focusedIndex + 1),
            hasPrevious = focusedIndex > 0,
            hasNext = focusedIndex < info.totalItemsCount - 1,
        )
        when {
            abs(delta) < 0.5f -> Unit
            jumped || abs(delta) > item.size * 1.5f -> listState.scrollBy(delta)
            else -> listState.animateScrollBy(delta, glide)
        }
    }

    companion object {
        private const val GLIDE_MILLIS = 120

        val Saver: Saver<FocusListState, *> = listSaver(
            save = { listOf(it.focusedIndex, it.listState.firstVisibleItemIndex, it.listState.firstVisibleItemScrollOffset) },
            restore = { FocusListState(it[0], LazyListState(it[1], it[2])) },
        )
    }
}

@Composable
fun rememberFocusListState(key: String? = null): FocusListState =
    rememberSaveable(key, saver = FocusListState.Saver) { FocusListState() }
