package app.podium.core.designsystem.shell

import android.graphics.BlurMaskFilter
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.podium.core.designsystem.theme.DisplayStyle
import app.podium.core.designsystem.theme.PodiumColors
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.designsystem.theme.Spacing
import app.podium.core.designsystem.tonal.AlbumTone
import app.podium.core.designsystem.tonal.LocalAlbumTone
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** One thing the title bar shows about the device (D-103): the time, the output, the play state, the battery. */
@Immutable
class StatusItem(
    val key: String,
    /** Items with the same housing share one pill, side by side, a hairline between them. */
    val housing: Int,
    val content: @Composable () -> Unit,
)

/**
 * The title bar's status, every thing in a housing (D-103), alive like Apple's Dynamic Island.
 * Items sharing a housing sit in one pill with a hairline between them. When an item arrives its
 * pill stretches out to take it, overshooting a little like jelly and settling; when a whole
 * housing arrives beside another it buds off that one's side as a bubble, stretching a liquid neck
 * that thins and pinches off as it reaches its place; with nothing beside it, it pops up from a
 * point and settles. Leaving runs the other way. The housings are one outline (capsules and the
 * necks between them), painted in the display's material: Carbon's solid surface, Glass's clear
 * glass catching the cover's light, the instrument's hairline. Reduced motion: short fades.
 */
@Composable
fun StatusPills(items: List<StatusItem>, modifier: Modifier = Modifier, height: Dp = PillHeight) {
    val colors = PodiumTheme.colors
    val tone = LocalAlbumTone.current
    val reduced = PodiumTheme.motion.reduced
    val slots = remember { mutableStateListOf<PillSlot>().apply { items.forEach { add(PillSlot(it.key, it.housing, it.content, 1f)) } } }
    // Motions outlive a change of items: a new arrival never cuts another one's movement short.
    val scope = rememberCoroutineScope()
    // The latest content for every item still shown.
    SideEffect { items.forEach { item -> slots.firstOrNull { it.key == item.key }?.content = item.content } }
    LaunchedEffect(items.map { it.key to it.housing }) {
        val before = slots.toList()
        val next = ArrayList<PillSlot>()
        for (item in items) {
            val slot = before.firstOrNull { it.key == item.key } ?: PillSlot(item.key, item.housing, item.content, 0f)
            slot.housing = item.housing
            slot.leaving = false
            next += slot
        }
        // What has gone stays where it was, shrinking back into its neighbours.
        for (gone in before.filter { s -> items.none { it.key == s.key } }) {
            gone.leaving = true
            val at = before.indexOf(gone)
            val after = before.subList(0, at).lastOrNull { it in next }
            next.add(if (after == null) 0 else next.indexOf(after) + 1, gone)
        }
        slots.clear()
        slots.addAll(next)
        next.forEach { slot ->
            val target = if (slot.leaving) 0f else 1f
            if (slot.presence.targetValue == target && (slot.presence.value == target || slot.presence.isRunning)) return@forEach
            scope.launch {
                slot.presence.animateTo(
                    target,
                    when {
                        reduced -> tween(140)
                        target == 1f -> spring(dampingRatio = 0.55f, stiffness = 420f)
                        else -> spring(dampingRatio = 0.9f, stiffness = 620f)
                    },
                )
                if (slot.leaving && slot.presence.value <= 0.001f) slots.remove(slot)
            }
        }
    }
    val pillPx = with(LocalDensity.current) { height.toPx() }
    val geometry = remember { PillGeometry() }
    // The slots this composition shows, one box each. Layout uses this same list, never the live
    // one: the system may measure between a change to the slots and the composition that follows
    // it (content capture does), and the boxes and the slots must still pair up one to one.
    val shown = slots.toList()
    Layout(
        content = {
            shown.forEach { slot ->
                key(slot.key) {
                    Box(
                        Modifier.graphicsLayer {
                            val p = slot.presence.value.coerceIn(0f, 1f)
                            alpha = p * p
                            val s = 0.6f + 0.4f * p
                            scaleX = s
                            scaleY = s
                        },
                    ) { slot.content() }
                }
            }
        },
        modifier = modifier.drawBehind {
            // Laid out from the items' presences, and read here too: every step of a housing's
            // motion redraws it, not only the steps that change its size.
            shown.forEach { it.presence.value }
            paintHousings(geometry, colors, tone, pillPx)
        },
    ) { measurables, _ ->
        val placeables = measurables.map { it.measure(Constraints()) }
        val padding = SlotPadding.toPx()
        val widths = FloatArray(placeables.size) { i -> max(placeables[i].width + padding * 2, pillPx) }
        geometry.update(shown, widths, pillPx, HousingGap.toPx())
        layout(geometry.width.roundToInt().coerceAtLeast(0), pillPx.roundToInt()) {
            placeables.forEachIndexed { i, p ->
                val centre = geometry.slotCentres.getOrElse(i) { 0f }
                p.place((centre - p.width / 2f).roundToInt(), ((pillPx - p.height) / 2f).roundToInt())
            }
        }
    }
}

/** One item, alive: arriving or leaving with its presence (0–1, overshooting a little on arrival). */
@Stable
private class PillSlot(val key: String, housing: Int, content: @Composable () -> Unit, presence: Float) {
    var housing by mutableIntStateOf(housing)
    var content by mutableStateOf(content)
    var leaving by mutableStateOf(false)
    val presence: Animatable<Float, AnimationVector1D> = Animatable(presence)
}

private class Housing(val left: Float, val right: Float, val presence: Float)

/** Where the housings and their items are, worked out at layout and read while drawing. */
private class PillGeometry {
    var width = 0f
    var slotCentres = FloatArray(0)
    var housings: List<Housing> = emptyList()
    var separators: List<Pair<Float, Float>> = emptyList()

    fun update(slots: List<PillSlot>, widths: FloatArray, height: Float, gap: Float) {
        val centres = FloatArray(slots.size)
        val built = ArrayList<Housing>()
        val lines = ArrayList<Pair<Float, Float>>()
        var x = 0f
        var i = 0
        var previous: Float? = null
        while (i < slots.size) {
            var j = i
            while (j + 1 < slots.size && slots[j + 1].housing == slots[i].housing) j++
            val p = (i..j).maxOf { slots[it].presence.value }.coerceIn(0f, 1.2f)
            val content = (i..j).sumOf { (widths[it] * slots[it].presence.value.coerceIn(0f, 1.15f)).toDouble() }.toFloat()
            // Arriving alone, a housing is first a growing bubble, then stretches into its pill.
            val w = max(content, height * min(1f, p) * 0.9f)
            // The gap to the housing before opens as this one arrives: it buds off that one's side.
            if (previous != null) x += gap * min(1f, min(p, previous))
            val left = x
            var cursor = left + (w - content) / 2f
            for (k in i..j) {
                val sw = widths[k] * slots[k].presence.value.coerceIn(0f, 1.15f)
                centres[k] = cursor + sw / 2f
                if (k > i) lines += cursor to min(slots[k - 1].presence.value, slots[k].presence.value).coerceIn(0f, 1f)
                cursor += sw
            }
            built += Housing(left, left + w, p)
            x = left + w
            previous = p
            i = j + 1
        }
        width = x
        slotCentres = centres
        housings = built
        separators = lines
    }
}

/** The housings' outline: capsules, and liquid necks between neighbours still parting. */
private fun DrawScope.housingOutline(g: PillGeometry, pill: Float, round: Boolean): Path {
    val gap = HousingGap.toPx()
    val outline = Path()
    val cy = pill / 2f
    g.housings.forEach { h ->
        val scale = 0.55f + 0.45f * h.presence.coerceIn(0f, 1f)
        val hh = pill * scale
        val r = if (round) hh / 2f else ShelfCorner.toPx()
        val rect = Rect(h.left, cy - hh / 2f, h.right, cy + hh / 2f)
        val one = Path().apply { addRoundRect(RoundRect(rect, CornerRadius(min(r, rect.width / 2f)))) }
        outline.op(outline, one, PathOperation.Union)
    }
    if (round) {
        g.housings.zipWithNext().forEach { (a, b) ->
            val parting = b.left - a.right
            // Joined while close, pinching off as the bubble reaches its place.
            val stick = smoothStep(gap * 0.8f, gap * 0.12f, parting) * min(a.presence, b.presence).coerceIn(0f, 1f)
            if (stick <= 0.02f) return@forEach
            val ra = pill * (0.55f + 0.45f * a.presence.coerceIn(0f, 1f)) / 2f
            val rb = pill * (0.55f + 0.45f * b.presence.coerceIn(0f, 1f)) / 2f
            neck(Offset(a.right - ra, cy), ra, Offset(b.left + rb, cy), rb, 0.55f * stick)?.let { outline.op(outline, it, PathOperation.Union) }
        }
    }
    return outline
}

private fun DrawScope.paintHousings(g: PillGeometry, colors: PodiumColors, tone: AlbumTone, pill: Float) {
    if (g.housings.isEmpty()) return
    val round = colors.style != DisplayStyle.INDUSTRIAL
    val outline = housingOutline(g, pill, round)
    when (colors.style) {
        DisplayStyle.TONAL -> {
            softShadow(outline, Color.Black.copy(alpha = 0.5f), 4.dp.toPx(), 1.5.dp.toPx())
            drawPath(outline, tone.surfaceLow)
            clipPath(outline) {
                drawPath(
                    outline,
                    Brush.verticalGradient(0f to Color.White.copy(alpha = 0.13f), 0.45f to Color.White.copy(alpha = 0.02f), 1f to Color.Black.copy(alpha = 0.25f)),
                    style = Stroke(2.dp.toPx()),
                )
            }
        }
        DisplayStyle.GLASS -> {
            val dark = colors.isDark
            softShadow(outline, Color.Black.copy(alpha = if (dark) 0.35f else 0.12f), 6.dp.toPx(), 2.dp.toPx())
            drawPath(outline, if (dark) Color.White.copy(alpha = 0.09f) else Color.White.copy(alpha = 0.55f))
            clipPath(outline) {
                // The cover's light, caught along the glass's upper edge.
                drawRect(Brush.verticalGradient(listOf(tone.accent.copy(alpha = if (dark) 0.22f else 0.14f), Color.Transparent), endY = pill * 0.7f))
                drawPath(
                    outline,
                    Brush.linearGradient(
                        listOf(Color.White.copy(alpha = if (dark) 0.5f else 0.9f), Color.Transparent, Color.White.copy(alpha = 0.16f)),
                        start = Offset.Zero,
                        end = Offset(size.width.coerceAtLeast(1f), pill),
                    ),
                    style = Stroke(2.dp.toPx()),
                )
            }
        }
        DisplayStyle.INDUSTRIAL -> {
            drawPath(outline, colors.labelPrimary.copy(alpha = 0.04f))
            drawPath(outline, colors.separator, style = Stroke(1.dp.toPx()))
        }
    }
    // The hairlines between items sharing a housing.
    val line = when (colors.style) {
        DisplayStyle.TONAL -> colors.labelPrimary.copy(alpha = 0.16f)
        DisplayStyle.GLASS -> Color.White.copy(alpha = if (colors.isDark) 0.22f else 0.5f)
        DisplayStyle.INDUSTRIAL -> colors.separator
    }
    val inset = pill * 0.27f
    g.separators.forEach { (x, alpha) ->
        if (alpha > 0.01f) drawLine(line.copy(alpha = line.alpha * alpha), Offset(x, inset), Offset(x, pill - inset), 1.dp.toPx())
    }
}

/** A soft shadow under [path], [offsetY] down, blurred by [radius]. */
private fun DrawScope.softShadow(path: Path, color: Color, radius: Float, offsetY: Float) {
    drawIntoCanvas { canvas ->
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color.toArgb()
            maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.nativeCanvas.save()
        canvas.nativeCanvas.translate(0f, offsetY)
        canvas.nativeCanvas.drawPath(path.asAndroidPath(), paint)
        canvas.nativeCanvas.restore()
    }
}

/**
 * The liquid neck between two circles (the facing ends of two capsules), as metaballs draw it:
 * curves leaving each circle and meeting the other, [spread] (0–0.6) setting how thick it is — at 0
 * it has no thickness, the moment it pinches off. Null when there's nothing to draw.
 */
private fun neck(c1: Offset, r1: Float, c2: Offset, r2: Float, spread: Float): Path? {
    val d = (c2 - c1).getDistance()
    if (d <= abs(r1 - r2) || d <= 0.001f || spread <= 0f) return null
    val overlap = d < r1 + r2
    val u1 = if (overlap) acos(((r1 * r1 + d * d - r2 * r2) / (2 * r1 * d)).coerceIn(-1f, 1f)) else 0f
    val u2 = if (overlap) acos(((r2 * r2 + d * d - r1 * r1) / (2 * r2 * d)).coerceIn(-1f, 1f)) else 0f
    val between = atan2(c2.y - c1.y, c2.x - c1.x)
    val maxSpread = acos(((r1 - r2) / d).coerceIn(-1f, 1f))
    val pi = PI.toFloat()
    val a1 = between + u1 + (maxSpread - u1) * spread
    val a2 = between - u1 - (maxSpread - u1) * spread
    val a3 = between + pi - u2 - (pi - u2 - maxSpread) * spread
    val a4 = between - pi + u2 + (pi - u2 - maxSpread) * spread
    fun at(c: Offset, r: Float, a: Float) = Offset(c.x + r * cos(a), c.y + r * sin(a))
    val p1 = at(c1, r1, a1)
    val p2 = at(c1, r1, a2)
    val p3 = at(c2, r2, a3)
    val p4 = at(c2, r2, a4)
    val handle = min(spread * 2.4f, (p1 - p3).getDistance() / (r1 + r2)) * min(1f, d * 2f / (r1 + r2))
    val h1 = at(p1, r1 * handle, a1 - pi / 2f)
    val h2 = at(p2, r1 * handle, a2 + pi / 2f)
    val h3 = at(p3, r2 * handle, a3 + pi / 2f)
    val h4 = at(p4, r2 * handle, a4 - pi / 2f)
    return Path().apply {
        moveTo(p1.x, p1.y)
        cubicTo(h1.x, h1.y, h3.x, h3.y, p3.x, p3.y)
        lineTo(p4.x, p4.y)
        cubicTo(h4.x, h4.y, h2.x, h2.y, p2.x, p2.y)
        close()
    }
}

/** 0 at [edge0], 1 at [edge1] (either order), eased. */
private fun smoothStep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** A housing's height: the title bar's, concentric with the display's corner. */
val PillHeight: Dp = Spacing.housing

/** Room either side of an item inside its housing. */
private val SlotPadding = 8.dp

/** Between two housings at rest. */
private val HousingGap = 6.dp

/** The instrument's housings are squared, like its keys. */
private val ShelfCorner = 4.dp
