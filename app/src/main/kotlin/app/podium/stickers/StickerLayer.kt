package app.podium.stickers

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Which stuck sticker is being arranged (its placement id), shared by the editor and its bar. */
@Stable
class StickerEditorState {
    var selected: String? by mutableStateOf(null)
}

/**
 * The listener's stickers, stuck on the Podium (D-55): drawn in the object's own coordinates so
 * they move, turn and recede with it. Only drawn — never touched — so the Wheel and the screen
 * beneath work as ever. Its own layer: the screen changing never redraws the stickers.
 */
@Composable
fun StickerLayer(store: StickerStore, modifier: Modifier = Modifier) {
    val placements by store.placements.collectAsStateWithLifecycle()
    if (placements.isEmpty()) return
    val images = rememberStickerImages(store, placements.mapTo(HashSet()) { it.stickerId })
    Canvas(modifier.fillMaxSize().graphicsLayer()) {
        for (p in placements.sortedBy { it.z }) {
            val image = images[p.stickerId] ?: continue
            drawSticker(image, p)
        }
    }
}

/**
 * Arranging (STICKER_EDITING): a touch on a sticker chooses it and brings it to the top; one finger
 * moves it, two fingers resize and turn it (anywhere, so small stickers stay easy). A touch away
 * from every sticker lets go of the chosen one. Covers the object, so nothing beneath is operated.
 */
@Composable
fun StickerEditor(store: StickerStore, editor: StickerEditorState, modifier: Modifier = Modifier) {
    val placements by store.placements.collectAsStateWithLifecycle()
    val images = rememberStickerImages(store, placements.mapTo(HashSet()) { it.stickerId })
    val latestImages by rememberUpdatedState(images)
    val minTouch = with(androidx.compose.ui.platform.LocalDensity.current) { 48.dp.toPx() }
    Canvas(
        modifier
            .fillMaxSize()
            .semantics { contentDescription = "Your Podium's stickers. Drag one to move it; two fingers resize and turn it." }
            .pointerInput(store, editor) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val box = Size(size.width.toFloat(), size.height.toFloat())
                    val hit = hitTest(store.placements.value, latestImages, down.position, box, minTouch)
                    if (hit != null) {
                        editor.selected = hit.id
                        store.raise(hit.id)
                    }
                    var targetId = hit?.id ?: editor.selected
                    var travelled = 0f
                    var transformed = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed == 0) break
                        travelled += event.changes.sumOf { abs(it.positionChange().x.toDouble()) + abs(it.positionChange().y.toDouble()) }.toFloat()
                        // One finger moves only the sticker it touched; two fingers work on the chosen one.
                        val id = targetId
                        if (id != null && (hit != null || pressed >= 2)) {
                            val current = store.placements.value.firstOrNull { it.id == id }
                            if (current == null) {
                                targetId = null
                            } else {
                                val pan = event.calculatePan()
                                val zoom = event.calculateZoom()
                                val turn = event.calculateRotation()
                                store.update(
                                    current.copy(
                                        x = current.x + pan.x / box.width,
                                        y = current.y + pan.y / box.height,
                                        scale = current.scale * zoom,
                                        rotation = current.rotation + turn,
                                    ),
                                    persistNow = false,
                                )
                                transformed = true
                            }
                        }
                        event.changes.forEach { it.consume() }
                    }
                    if (transformed) {
                        targetId?.let { id -> store.placements.value.firstOrNull { it.id == id }?.let(store::update) }
                    } else if (hit == null && travelled < viewConfiguration.touchSlop) {
                        editor.selected = null
                    }
                }
            },
    ) {
        val selected = editor.selected?.let { id -> placements.firstOrNull { it.id == id } }
        if (selected != null) {
            val image = images[selected.stickerId]
            if (image != null) drawSelection(image, selected)
        }
    }
}

/** The sticker images for [ids], decoded off the main thread; the last set stays while the next loads. */
@Composable
internal fun rememberStickerImages(store: StickerStore, ids: Set<String>, maxSide: Int = StickerArt.MAX_SIDE): Map<String, ImageBitmap> {
    var images by remember(store) { mutableStateOf<Map<String, ImageBitmap>>(emptyMap()) }
    LaunchedEffect(store, ids, maxSide) {
        val known = images
        images = withContext(Dispatchers.IO) {
            ids.mapNotNull { id -> (known[id] ?: store.image(id, maxSide)?.asImageBitmap())?.let { id to it } }.toMap()
        }
    }
    return images
}

/** One sticker at its placement: centred at (x, y), [StickerPlacement.scale] of the object's width wide, turned. */
internal fun DrawScope.drawSticker(image: ImageBitmap, p: StickerPlacement) {
    val w = p.scale * size.width
    val h = w * image.height / image.width
    val cx = p.x * size.width
    val cy = p.y * size.height
    withTransform({ rotate(p.rotation, Offset(cx, cy)) }) {
        drawImage(
            image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset((cx - w / 2).roundToInt(), (cy - h / 2).roundToInt()),
            dstSize = IntSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1)),
            filterQuality = FilterQuality.Medium,
        )
    }
}

/** The chosen sticker's outline: a fine dashed line, lit on dark, around its turned bounds. */
private fun DrawScope.drawSelection(image: ImageBitmap, p: StickerPlacement) {
    val w = p.scale * size.width
    val h = w * image.height / image.width
    val cx = p.x * size.width
    val cy = p.y * size.height
    val pad = 6.dp.toPx()
    withTransform({ rotate(p.rotation, Offset(cx, cy)) }) {
        val topLeft = Offset(cx - w / 2 - pad, cy - h / 2 - pad)
        val box = Size(w + pad * 2, h + pad * 2)
        drawRect(Color.Black.copy(alpha = 0.45f), topLeft, box, style = Stroke(width = 3.dp.toPx()))
        drawRect(
            Color.White,
            topLeft,
            box,
            style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx()))),
        )
    }
}

/**
 * The top-most placement under [point] (object pixels), or null. A sticker's bounds are its turned
 * rectangle, at least [minTouch] across so even a tiny sticker can be taken hold of.
 */
internal fun hitTest(
    placements: List<StickerPlacement>,
    images: Map<String, ImageBitmap>,
    point: Offset,
    box: Size,
    minTouch: Float,
): StickerPlacement? {
    for (p in placements.sortedByDescending { it.z }) {
        val image = images[p.stickerId] ?: continue
        val w = p.scale * box.width
        val h = w * image.height / image.width
        if (contains(p, w, h, point, box, minTouch)) return p
    }
    return null
}

/** Whether [point] falls in the placement's turned [w]×[h] rectangle (at least [minTouch] across). */
internal fun contains(p: StickerPlacement, w: Float, h: Float, point: Offset, box: Size, minTouch: Float): Boolean {
    val dx = point.x - p.x * box.width
    val dy = point.y - p.y * box.height
    val a = Math.toRadians(-p.rotation.toDouble())
    val lx = dx * cos(a) - dy * sin(a)
    val ly = dx * sin(a) + dy * cos(a)
    return abs(lx) <= maxOf(w, minTouch) / 2 && abs(ly) <= maxOf(h, minTouch) / 2
}
