package app.podium.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.podium.core.designsystem.glass.GlassTier
import app.podium.core.designsystem.glass.LocalGlassTier
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.interaction.FocusListState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Whether the focus lens is the solid classic bar (Solid tier): rows then draw white text. */
@Composable
fun isSolidFocus(): Boolean = LocalGlassTier.current == GlassTier.Solid

/**
 * A list with one focus, shown by the focus lens: a stained glass capsule that glides beneath the
 * focused row (D-25: beneath, so the text the user is reading stays crisp). Rotation moves the
 * focus; touch taps focus-and-activate; touch scrolling leaves focus where it is.
 */
@Composable
fun <T> FocusList(
    items: List<T>,
    state: FocusListState,
    key: (T) -> Any,
    contentPadding: PaddingValues,
    onActivate: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: (index: Int) -> Unit = {},
    row: @Composable (item: T, index: Int, focused: Boolean) -> Unit,
) {
    val colors = PodiumTheme.colors
    val motion = PodiumTheme.motion
    val solid = isSolidFocus()
    val listState = state.listState
    val activate by rememberUpdatedState(onActivate)
    val longPress by rememberUpdatedState(onLongPress)

    SideEffect {
        state.itemCount = items.size
        state.clamp()
    }

    // The lens follows the focused row — animated when focus moves, glued to it while scrolling —
    // and never leaves the readable region: at an edge it holds still and the list moves under it.
    val lensTop = remember { Animatable(0f) }
    val lensHeight = remember { Animatable(0f) }
    var lensVisible by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        var lastMoves = state.focusMoves
        snapshotFlow {
            val info = listState.layoutInfo
            val end = info.viewportEndOffset - info.afterContentPadding
            val item = info.visibleItemsInfo.firstOrNull { it.index == state.focusedIndex }
            val top = item?.let { it.offset.coerceIn(0, (end - it.size).coerceAtLeast(0)) + info.beforeContentPadding }
            Triple(state.focusMoves, top?.toFloat(), item?.size?.toFloat())
        }.collect { (moves, top, height) ->
            if (top == null || height == null) {
                // A spin briefly outran the layout: hold the lens where it is until the list catches
                // up (a frame or two). Only a touch scroll that moved the row away hides it.
                if (moves == lastMoves) lensVisible = false
                lastMoves = moves
                return@collect
            }
            val animate = lensVisible && moves != lastMoves && !motion.reduced
            lastMoves = moves
            lensVisible = true
            if (animate) {
                launch { lensTop.animateTo(top, motion.focus()) }
                launch { lensHeight.animateTo(height, motion.focus()) }
            } else {
                lensTop.snapTo(top)
                lensHeight.snapTo(height)
            }
        }
    }
    // Keep the focused row inside the readable region; a newer move cancels an unfinished scroll.
    LaunchedEffect(state) {
        snapshotFlow { state.focusMoves }.collectLatest { state.keepFocusedInView() }
    }

    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            if (!lensVisible) return@Canvas
            val inset = 8.dp.toPx()
            val vInset = 3.dp.toPx()
            val top = lensTop.value + vInset
            val h = (lensHeight.value - vInset * 2).coerceAtLeast(0f)
            val r = CornerRadius(14.dp.toPx())
            val origin = Offset(inset, top)
            val size = Size(size.width - inset * 2, h)
            if (solid) {
                drawRoundRect(colors.highlight, origin, size, r)
            } else {
                // Stained glass: translucent highlight, a soft top sheen, and a rim catching light.
                drawRoundRect(colors.highlight.copy(alpha = if (colors.isDark) 0.30f else 0.18f), origin, size, r)
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = if (colors.isDark) 0.10f else 0.30f), Color.Transparent),
                        startY = top,
                        endY = top + h * 0.6f,
                    ),
                    topLeft = origin,
                    size = size,
                    cornerRadius = r,
                )
                drawRoundRect(
                    brush = Brush.linearGradient(
                        listOf(Color.White.copy(alpha = if (colors.isDark) 0.45f else 0.85f), Color.Transparent, Color.White.copy(alpha = 0.12f)),
                        start = origin,
                        end = Offset(origin.x + size.width, origin.y + size.height),
                    ),
                    topLeft = origin,
                    size = size,
                    cornerRadius = r,
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
        }
        LazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(items, key = { _, item -> key(item) }) { index, item ->
                val focused = index == state.focusedIndex
                Box(
                    Modifier
                        .fillMaxWidth()
                        .semantics { selected = focused }
                        .pointerInput(index) {
                            detectTapGestures(
                                onTap = {
                                    state.focus(index)
                                    activate(index)
                                },
                                onLongPress = {
                                    state.focus(index)
                                    longPress(index)
                                },
                            )
                        },
                ) { row(item, index, focused) }
            }
        }
    }
}
