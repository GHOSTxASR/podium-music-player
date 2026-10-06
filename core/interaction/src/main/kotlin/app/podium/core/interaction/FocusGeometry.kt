package app.podium.core.interaction

import kotlin.math.floor

/** How a list keeps its focused row in view (interaction-model.md §5.1). */
enum class FocusScroll {
    /**
     * The paper (D-29): the focused row rides at the middle of the readable region — the arc's apex
     * — and only near the start or end of the list does it travel to the real top or bottom, so
     * there is never empty space before the first row or after the last.
     */
    CENTRE,

    /** Plain lists (menus): the focus moves; the list scrolls only to keep a neighbour in view. */
    EDGE,
}

/**
 * The focus geometry, derived only from what the list measured (interaction-model.md §5.1): the
 * readable region (the viewport between the content paddings), the rows' offsets and sizes, and
 * where the list is scrolled. Pure, so every edge case is tested without a screen.
 *
 * Offsets are the lazy list's: 0 is the top of the readable region, [readable] its bottom.
 */
object FocusGeometry {

    /** A laid-out row: its top within the readable region and its height. */
    data class Row(val offset: Int, val size: Int) {
        val end: Int get() = offset + size
    }

    /**
     * How far to scroll (positive: the content moves up) so the focused row sits where [mode] wants
     * it. The list clamps the scroll at its ends, which is what puts the first row against the top
     * and the last against the bottom.
     *
     * [previous] / [next] are the neighbouring rows when laid out (null when they are off-screen or
     * don't exist); [hasPrevious] / [hasNext] say whether they exist at all.
     */
    fun scrollDelta(
        mode: FocusScroll,
        readable: Int,
        focused: Row,
        previous: Row?,
        next: Row?,
        hasPrevious: Boolean,
        hasNext: Boolean,
    ): Float {
        if (readable <= 0) return 0f
        if (mode == FocusScroll.CENTRE) return focused.offset + focused.size / 2f - readable / 2f
        // EDGE: the focused row and, where they exist, one neighbour each side, fully visible. A
        // neighbour that isn't laid out yet is assumed to be as tall as the focused row.
        var top = if (hasPrevious) previous?.offset ?: (focused.offset - focused.size) else focused.offset
        var bottom = if (hasNext) next?.end ?: (focused.end + focused.size) else focused.end
        if (bottom - top > readable) {
            // Rows too tall to show with their neighbours: the focused row alone.
            top = focused.offset
            bottom = focused.end
        }
        return when {
            top < 0 -> top.toFloat()
            bottom > readable -> (bottom - readable).toFloat()
            else -> 0f
        }
    }

    /**
     * How strongly an end of the list fades and softens (0..1): only as much as there is more of
     * the list beyond it, reaching full strength once a whole row is hidden. A list that starts at
     * the top has a crisp top end; a short list has crisp ends both ways — the fade promises more.
     */
    fun endStrength(hiddenPx: Float, rowPx: Float): Float =
        if (rowPx <= 0f) (if (hiddenPx > 0f) 1f else 0f) else (hiddenPx / rowPx).coerceIn(0f, 1f)

    /**
     * How much of the list is hidden above the readable region's top, given the first laid-out row
     * (index [firstIndex], at [firstOffset]).
     */
    fun hiddenAbove(firstIndex: Int, firstOffset: Int, firstSize: Int): Float =
        if (firstIndex > 0) firstSize.toFloat() else (-firstOffset).coerceAtLeast(0).toFloat()

    /** How much is hidden below the readable region's bottom, given the last laid-out row. */
    fun hiddenBelow(lastIndex: Int, lastEnd: Int, lastSize: Int, totalCount: Int, readable: Int): Float =
        if (lastIndex < totalCount - 1) lastSize.toFloat() else (lastEnd - readable).coerceAtLeast(0).toFloat()

    /**
     * Where the lens is while it moves from row to row: [position] is a fractional row index (2.5 is
     * half-way from row 2 to row 3); [rowAt] gives a laid-out row or null. The lens is glued to
     * the rows, so it moves with the list while the list scrolls. Null when nothing it could sit on
     * is laid out.
     */
    fun lens(position: Float, focusedIndex: Int, rowAt: (Int) -> Row?): Pair<Float, Float>? {
        val i = floor(position).toInt()
        val f = position - i
        val a = rowAt(i)
        val b = if (f > 0f) rowAt(i + 1) else null
        return when {
            a != null && b != null -> (a.offset + (b.offset - a.offset) * f) to (a.size + (b.size - a.size) * f)
            a != null && f == 0f -> a.offset.toFloat() to a.size.toFloat()
            else -> rowAt(focusedIndex)?.let { it.offset.toFloat() to it.size.toFloat() }
        }
    }
}
