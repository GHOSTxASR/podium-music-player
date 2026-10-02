package app.podium.core.interaction

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
import kotlin.math.max

/**
 * The single focus of a list (interaction-model.md §1, §5). Rotation moves it, touch scrolling
 * leaves it alone, and the next rotation snaps it back into view first.
 */
@Stable
class FocusListState(initialIndex: Int = 0, val listState: LazyListState = LazyListState()) {
    var focusedIndex by mutableIntStateOf(initialIndex)
        private set

    var itemCount by mutableIntStateOf(0)

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
        val target = (base + delta).coerceIn(0, itemCount - 1)
        if (target == focusedIndex) return false
        focusedIndex = target
        focusMoves++
        return true
    }

    fun focus(index: Int) {
        if (index in 0 until max(itemCount, 1) && index != focusedIndex) {
            focusedIndex = index
            focusMoves++
        }
    }

    fun clamp() {
        if (itemCount > 0 && focusedIndex > itemCount - 1) focusedIndex = itemCount - 1
    }

    /** Indices fully inside the readable region (between the content paddings). */
    fun visibleIndices(): IntRange {
        val info = listState.layoutInfo
        val end = info.viewportEndOffset - info.afterContentPadding
        val visible = info.visibleItemsInfo.filter { it.offset >= 0 && it.offset + it.size <= end }
        return if (visible.isEmpty()) IntRange.EMPTY else visible.first().index..visible.last().index
    }

    /**
     * Scroll so the focused item sits at least one row inside the readable region. A one-row step
     * glides; anything further — a fast spin that outran the last glide — jumps, so the list is
     * never behind the focus. Call with the latest focus only (cancel the previous call).
     */
    suspend fun keepFocusedInView() {
        val info = listState.layoutInfo
        val end = info.viewportEndOffset - info.afterContentPadding
        val item = info.visibleItemsInfo.firstOrNull { it.index == focusedIndex }
        if (item == null) {
            // The focused row isn't even laid out: place it one row inside the edge it went past.
            val visible = info.visibleItemsInfo
            val rowSize = visible.firstOrNull()?.size ?: 0
            if (visible.isNotEmpty() && focusedIndex > visible.last().index && rowSize > 0) {
                val rowsAbove = ((end - 2 * rowSize) / rowSize).coerceAtLeast(0)
                listState.scrollToItem(max(0, focusedIndex - rowsAbove))
            } else {
                listState.scrollToItem(max(0, focusedIndex - 1))
            }
            return
        }
        val margin = item.size
        val top = item.offset
        val bottom = item.offset + item.size
        val delta = when {
            top < margin && focusedIndex > 0 -> (top - margin).toFloat()
            bottom > end - margin && focusedIndex < itemCount - 1 -> (bottom - (end - margin)).toFloat()
            else -> 0f
        }
        when {
            delta == 0f -> Unit
            abs(delta) <= item.size * 1.05f -> listState.animateScrollBy(delta, tween(GLIDE_MILLIS, easing = FastOutSlowInEasing))
            else -> listState.scrollBy(delta)
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
