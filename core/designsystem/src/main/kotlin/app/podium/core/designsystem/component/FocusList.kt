package app.podium.core.designsystem.component

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
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
fun isSolidFocus(): Boolean = LocalGlassTier.current == GlassTier.Solid && !PodiumTheme.colors.isIndustrial

/**
 * A list with one focus. Rotation moves the focus; touch taps focus-and-activate; touch scrolling
 * leaves focus where it is.
 *
 * By default it is laid out on the paper (D-29, [Paper]): the previous column peeks in at the left,
 * rows bend along an arc mirroring the Wheel's right side, the selection indicator rides on that
 * arc, and [preview] shows the focused item's next column beyond it. Overlays pass `paper = false`
 * for a plain full-width list. Glass draws a stained capsule beneath the focused row (D-25); Carbon
 * and Bone light the row instead.
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
    /** Rows that can't hold focus (section headers): skipped by the wheel, never under the lens. */
    focusable: (T) -> Boolean = { true },
    /** The next column for the focused item, previewed beyond the arc. */
    preview: (T) -> MenuPreview = { MenuPreview.None },
    paper: Boolean = true,
    row: @Composable (item: T, index: Int, focused: Boolean) -> Unit,
) {
    val colors = PodiumTheme.colors
    val motion = PodiumTheme.motion
    val density = LocalDensity.current
    val solidFocus = isSolidFocus()
    val listState = state.listState
    val activate by rememberUpdatedState(onActivate)
    val longPress by rememberUpdatedState(onLongPress)

    val currentFocusable by rememberUpdatedState(focusable)
    SideEffect {
        state.itemCount = items.size
        state.isFocusable = { i -> items.getOrNull(i)?.let(currentFocusable) ?: false }
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

    // Paper lists bend each row along the arc and fade/soften it toward the ends; plain lists don't.
    val rows: @Composable (Modifier, (Modifier.(index: Int) -> Modifier)?) -> Unit = { listModifier, along ->
        LazyColumn(
            state = listState,
            contentPadding = contentPadding,
            verticalArrangement = if (paper) Arrangement.Center else Arrangement.Top,
            modifier = listModifier,
        ) {
            itemsIndexed(items, key = { _, item -> key(item) }) { index, item ->
                val focused = index == state.focusedIndex
                Box(
                    Modifier
                        .fillMaxWidth()
                        .then(if (along != null) Modifier.along(index) else Modifier)
                        .semantics { selected = focused }
                        .pointerInput(index) {
                            if (!focusable(item)) return@pointerInput
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

    if (!paper) {
        Box(modifier) {
            Canvas(Modifier.fillMaxSize()) {
                if (!lensVisible) return@Canvas
                drawLens(colors.isIndustrial, colors.isDark, colors.highlight, colors.labelPrimary, solidFocus, 0f, size.width, lensTop.value, lensHeight.value, dotX = null)
            }
            rows(Modifier.fillMaxSize(), null)
        }
        return
    }

    val miniature = LocalMiniature.current
    // In a miniature, show the item that led to the current column as focused.
    LocalMiniatureFocusKey.current?.let { focusKey ->
        LaunchedEffect(items, focusKey) {
            val i = items.indexOfFirst { key(it).toString() == focusKey }
            if (i >= 0) state.focus(i)
        }
    }

    BoxWithConstraints(modifier.clipToBounds()) {
        val g = PaperGeometry(maxWidth, maxHeight, contentPadding.calculateTopPadding(), contentPadding.calculateBottomPadding())
        val decor = LocalPaperDecor.current()

        if (!miniature) {
            // The previous column, live, at middle-left: a tile like the next column's at the right,
            // mirrored off the left edge, showing that screen zoomed to its list.
            LocalPaperPeek.current?.let { peek ->
                val corner = previewCorner()
                Box(
                    Modifier
                        .offset(x = g.leftBoxX, y = g.boxTop)
                        .size(g.boxHeight)
                        .then(decor)
                        .distant()
                        .clip(RoundedCornerShape(corner))
                        .background(colors.canvasRaised),
                ) {
                    Box(
                        Modifier
                            .offset(x = g.peekOriginX - g.leftBoxX, y = g.peekOriginY - g.boxTop)
                            .wrapContentSize(Alignment.TopStart, unbounded = true)
                            .requiredSize(g.width, g.height)
                            .graphicsLayer {
                                scaleX = g.peekScale
                                scaleY = g.peekScale
                                transformOrigin = TransformOrigin(0f, 0f)
                            },
                    ) { peek(Modifier.fillMaxSize()) }
                }
            }

            // The next column at middle-right, beyond the arc, running off the display's edge.
            val focusedItem = items.getOrNull(state.focusedIndex)
            Box(
                Modifier
                    .offset(x = g.rightBoxX, y = g.boxTop)
                    .size(g.boxHeight)
                    .then(decor),
            ) {
                AnimatedContent(
                    targetState = focusedItem?.let(preview) ?: MenuPreview.None,
                    transitionSpec = { fadeIn(tween(if (motion.reduced) 0 else 320)) togetherWith fadeOut(tween(if (motion.reduced) 0 else 200)) },
                    label = "nextColumn",
                ) { p -> PreviewPane(p, g.boxHeight) }
            }
        }

        // The arc, the lens and the indicator riding on the arc.
        Canvas(Modifier.fillMaxSize()) {
            val path = Path()
            val steps = 48
            for (i in 0..steps) {
                val y = g.padTop + g.readable * (i / steps.toFloat())
                val x = g.arcX(y).toPx()
                if (i == 0) path.moveTo(x, y.toPx()) else path.lineTo(x, y.toPx())
            }
            drawPath(path, colors.labelTertiary.copy(alpha = if (colors.isDark) 0.45f else 0.6f), style = Stroke(width = 1.dp.toPx()))
            if (lensVisible) {
                val centre = lensTop.value + lensHeight.value / 2f
                val centreDp = centre.toDp()
                val left = (g.columnLeft - g.bend(centreDp - g.centreY)).toPx()
                val right = (g.arcX(centreDp) - Paper.ArcGap / 2).toPx()
                drawLens(colors.isIndustrial, colors.isDark, colors.highlight, colors.labelPrimary, solidFocus, left, right, lensTop.value, lensHeight.value, dotX = g.arcX(centreDp).toPx())
            }
        }

        // Each row rides the arc: shifted left by the curve at its height, and fading, shrinking a
        // little and softening toward the ends — a drum, not a sheet. The focused row stays crisp.
        val along: Modifier.(Int) -> Modifier = { index ->
            graphicsLayer {
                val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return@graphicsLayer
                val y = (info.offset + info.size / 2f).toDp() + g.padTop
                translationX = -g.bend(y - g.centreY).toPx()
                val d = g.depth(y)
                val focusedRow = index == state.focusedIndex
                alpha = (if (focusedRow) maxOf(0.9f, 1f - 0.6f * d * d) else 1f - 0.6f * d * d) * g.within(y)
                val scale = 1f - 0.05f * d * d
                scaleX = scale
                scaleY = scale
                val soften = ((d - 0.45f) / 0.55f).coerceIn(0f, 1f)
                renderEffect = if (!focusedRow && soften > 0f && Build.VERSION.SDK_INT >= 31) {
                    val r = (3.dp.toPx() * soften * soften).coerceAtLeast(0.01f)
                    BlurEffect(r, r, TileMode.Decal)
                } else null
            }
        }
        CompositionLocalProvider(LocalRowPadding provides 10.dp) {
            rows(Modifier.offset(x = g.columnLeft).width(g.columnWidth).fillMaxHeight(), along)
        }
    }
}

/**
 * The focus lens between [left] and [right]. Glass: a stained capsule (or the solid classic bar).
 * Industrial: a barely-there band — the row is lit rather than highlighted. On the paper the
 * indicator dot sits on the arc at [dotX].
 */
private fun DrawScope.drawLens(
    industrial: Boolean,
    dark: Boolean,
    highlight: Color,
    labelPrimary: Color,
    solid: Boolean,
    left: Float,
    right: Float,
    lensTop: Float,
    lensHeight: Float,
    dotX: Float?,
) {
    val inset = if (dotX == null) 8.dp.toPx() else 0f
    val vInset = 3.dp.toPx()
    val top = lensTop + vInset
    val h = (lensHeight - vInset * 2).coerceAtLeast(0f)
    val origin = Offset(left + inset, top)
    val size = Size((right - left - inset * 2).coerceAtLeast(0f), h)
    if (industrial) {
        drawRect(labelPrimary.copy(alpha = if (dark) 0.045f else 0.055f), Offset(left, top), Size((right - left).coerceAtLeast(0f), h))
        val dot = Offset(dotX ?: (left + IndicatorInsetDp.dp.toPx()), top + h / 2)
        if (dark) drawCircle(highlight.copy(alpha = 0.22f), radius = 8.dp.toPx(), center = dot)
        drawCircle(highlight, radius = 3.dp.toPx(), center = dot)
        return
    }
    val r = CornerRadius(14.dp.toPx())
    if (solid) {
        drawRoundRect(highlight, origin, size, r)
    } else {
        // Stained glass: translucent highlight, a soft top sheen, and a rim catching light.
        drawRoundRect(highlight.copy(alpha = if (dark) 0.30f else 0.18f), origin, size, r)
        drawRoundRect(
            brush = Brush.verticalGradient(
                listOf(Color.White.copy(alpha = if (dark) 0.10f else 0.30f), Color.Transparent),
                startY = top,
                endY = top + h * 0.6f,
            ),
            topLeft = origin,
            size = size,
            cornerRadius = r,
        )
        drawRoundRect(
            brush = Brush.linearGradient(
                listOf(Color.White.copy(alpha = if (dark) 0.45f else 0.85f), Color.Transparent, Color.White.copy(alpha = 0.12f)),
                start = origin,
                end = Offset(origin.x + size.width, origin.y + size.height),
            ),
            topLeft = origin,
            size = size,
            cornerRadius = r,
            style = Stroke(width = 1.dp.toPx()),
        )
    }
    if (dotX != null) drawCircle(highlight, radius = 3.dp.toPx(), center = Offset(dotX, top + h / 2))
}

/** Where plain (non-paper) industrial lists put their indicator, from the list's leading edge. */
private const val IndicatorInsetDp = 9
