package app.podium.stickers

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.podium.core.designsystem.theme.CarbonColors
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.designsystem.theme.Spacing
import app.podium.core.designsystem.type.PodiumText
import app.podium.space.PanelButton
import app.podium.space.SegmentedKeys
import app.podium.space.SmallKey
import app.podium.space.SpaceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

private enum class MakerStep { LOADING, FINDING, CUT, BORDER, SAVING, FAILED }
private enum class CutTool(val label: String) { SUBJECT("Subject"), ADD("Add"), ERASE("Erase") }

/**
 * Making a sticker (D-55): the chosen picture, its subject found on the phone and lit (the rest
 * dimmed); a tap chooses another subject, Add and Erase refine the edge by hand, with undo and redo.
 * Then the border — off, black or white, thin to thick — previewed on a neutral grey, and saved.
 * The whole window, Carbon's ink, over the space; nothing leaves the phone.
 */
@Composable
fun StickerMaker(
    source: Uri,
    cutter: SubjectCutter,
    store: StickerStore,
    onSaved: (Sticker) -> Unit,
    onCancel: () -> Unit,
) = SpaceType {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ink = CarbonColors
    var step by remember { mutableStateOf(MakerStep.LOADING) }
    var picture by remember { mutableStateOf<Bitmap?>(null) }
    val pictureImage = remember(picture) { picture?.asImageBitmap() }
    val mask = remember { FloatArray(MASK * MASK) }
    var hasMask by remember { mutableStateOf(false) }
    val shade = remember { Shade() }
    val history = remember { MaskHistory(mask) }
    var tool by remember { mutableStateOf(CutTool.SUBJECT) }
    var note by remember { mutableStateOf<String?>(null) }
    var border by remember { mutableStateOf(StickerBorder.WHITE) }
    var thickness by remember { mutableFloatStateOf(0.5f) }
    var cutout by remember { mutableStateOf<Bitmap?>(null) }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }

    fun refresh() = shade.update(mask)

    fun find(x: Float, y: Float) {
        val pic = picture ?: return
        step = MakerStep.FINDING
        scope.launch {
            val found = try {
                withContext(Dispatchers.Default) { cutter.mask(pic, x, y) }
            } catch (t: Throwable) {
                null
            }
            if (found == null) {
                note = "Podium couldn't look into this picture. Choose another one."
                step = MakerStep.FAILED
                return@launch
            }
            if (hasMask) history.checkpoint()
            found.copyInto(mask)
            hasMask = true
            refresh()
            note = if (StickerPictures.coverage(found) < MIN_COVERAGE) {
                "No subject there. Tap the subject, or keep part of the picture with Add."
            } else {
                null
            }
            step = MakerStep.CUT
        }
    }

    LaunchedEffect(source) {
        val pic = withContext(Dispatchers.IO) { StickerPictures.decode(context, source) }
        if (pic == null) {
            note = "Podium couldn't open that picture. Choose another one."
            step = MakerStep.FAILED
        } else {
            picture = pic
            find(0.5f, 0.5f)
        }
    }

    fun toBorder() {
        val pic = picture ?: return
        val kept = mask.copyOf()
        scope.launch {
            val cut = withContext(Dispatchers.Default) { StickerArt.cutout(pic, StickerArt.alphaFrom(kept, MASK, pic.width, pic.height)) }
            if (cut == null) {
                note = "Nothing is kept yet. Tap the subject, or keep part of the picture with Add."
            } else {
                cutout = cut
                note = null
                step = MakerStep.BORDER
            }
        }
    }

    LaunchedEffect(cutout, border, thickness) {
        val cut = cutout ?: return@LaunchedEffect
        delay(40) // the slider moves on; only its resting value is drawn
        preview = withContext(Dispatchers.Default) { StickerArt.compose(cut, border, thickness).asImageBitmap() }
    }

    fun save() {
        val cut = cutout ?: return
        step = MakerStep.SAVING
        scope.launch {
            val sticker = withContext(Dispatchers.IO) {
                runCatching { store.add(StickerArt.compose(cut, border, thickness), cut, border, thickness) }.getOrNull()
            }
            if (sticker == null) {
                note = "Podium couldn't keep the sticker; the phone may be full. Free some space and try again."
                step = MakerStep.BORDER
            } else {
                onSaved(sticker)
            }
        }
    }

    BackHandler { if (step == MakerStep.BORDER) step = MakerStep.CUT else onCancel() }

    Column(
        Modifier
            .fillMaxSize()
            .background(ink.canvas)
            .pointerInput(Unit) { detectTapGestures { } } // nothing beneath is operated
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(Spacing.l),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PodiumText(
                if (step == MakerStep.BORDER || step == MakerStep.SAVING) "Border" else "New sticker",
                PodiumTheme.type.title,
                ink.labelPrimary,
                Modifier.weight(1f),
            )
            SmallKey("Cancel", onCancel)
        }
        Spacer(Modifier.height(Spacing.m))
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when (step) {
                MakerStep.BORDER, MakerStep.SAVING -> BorderPreview(preview)
                MakerStep.FAILED -> Unit
                else -> if (pictureImage != null) {
                    CutView(
                        picture = pictureImage,
                        shade = shade,
                        showShade = hasMask,
                        tool = tool,
                        enabled = step == MakerStep.CUT,
                        onChoose = ::find,
                        onStrokeStart = history::checkpoint,
                        onStroke = { x, y, rx, ry ->
                            if (StickerArt.brush(mask, MASK, x, y, rx, ry, add = tool == CutTool.ADD)) refresh()
                        },
                    )
                }
            }
            val working = when (step) {
                MakerStep.LOADING -> "Opening the picture…"
                MakerStep.FINDING -> "Finding the subject…"
                MakerStep.SAVING -> "Keeping your sticker…"
                else -> null
            }
            if (working != null) {
                Box(Modifier.background(ink.canvasRaised.copy(alpha = 0.92f), RoundedCornerShape(12.dp)).padding(horizontal = Spacing.l, vertical = Spacing.s)) {
                    PodiumText(working, PodiumTheme.type.rowSecondary, ink.labelPrimary)
                }
            }
        }
        Spacer(Modifier.height(Spacing.s))
        note?.let { PodiumText(it, PodiumTheme.type.caption, ink.labelSecondary, Modifier.padding(bottom = Spacing.s), maxLines = 3) }
        when (step) {
            MakerStep.LOADING, MakerStep.FINDING, MakerStep.CUT -> CutControls(
                tool = tool,
                onTool = { tool = it },
                canUndo = history.canUndo,
                canRedo = history.canRedo,
                onUndo = { if (history.undo()) refresh() },
                onRedo = { if (history.redo()) refresh() },
                onNext = ::toBorder,
                enabled = step == MakerStep.CUT,
            )
            MakerStep.BORDER, MakerStep.SAVING -> BorderControls(
                border = border,
                onBorder = { border = it },
                thickness = thickness,
                onThickness = { thickness = it },
                onBack = { step = MakerStep.CUT },
                onSave = ::save,
                enabled = step == MakerStep.BORDER,
            )
            MakerStep.FAILED -> PanelButton("Close", onCancel)
        }
    }
}

/**
 * The picture, fitted, with everything that isn't the subject dimmed. In Subject, a tap chooses
 * what to keep; in Add and Erase a finger paints the edge (a ring shows the brush).
 */
@Composable
private fun CutView(
    picture: ImageBitmap,
    shade: Shade,
    showShade: Boolean,
    tool: CutTool,
    enabled: Boolean,
    onChoose: (Float, Float) -> Unit,
    onStrokeStart: () -> Unit,
    onStroke: (x: Float, y: Float, rx: Float, ry: Float) -> Unit,
) {
    val choose by rememberUpdatedState(onChoose)
    val strokeStart by rememberUpdatedState(onStrokeStart)
    val stroke by rememberUpdatedState(onStroke)
    val live by rememberUpdatedState(enabled)
    var brushAt by remember { mutableStateOf<Offset?>(null) }
    Canvas(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .semantics {
                contentDescription = when (tool) {
                    CutTool.SUBJECT -> "The picture. Tap the subject to keep it."
                    CutTool.ADD -> "The picture. Paint to keep more of it."
                    CutTool.ERASE -> "The picture. Paint to leave part of it out."
                }
            }
            .pointerInput(tool, picture) {
                val brush = BRUSH.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val box = fit(picture.width, picture.height, Size(size.width.toFloat(), size.height.toFloat()))
                    if (!live) return@awaitEachGesture
                    fun at(p: Offset) = Offset((p.x - box.left) / box.width, (p.y - box.top) / box.height)
                    if (tool == CutTool.SUBJECT) {
                        val up = waitForUpOrCancellation()
                        if (up != null && box.contains(up.position)) {
                            val f = at(up.position)
                            choose(f.x, f.y)
                        }
                        return@awaitEachGesture
                    }
                    strokeStart()
                    var last = down.position
                    val rx = brush / box.width
                    val ry = brush / box.height
                    at(last).let { stroke(it.x, it.y, rx, ry) }
                    brushAt = last
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val now = change.position
                        // Fill the gap between touches so a quick stroke stays a line.
                        val steps = ceil(hypot(now.x - last.x, now.y - last.y) / (brush * 0.4f)).toInt().coerceAtLeast(1)
                        for (i in 1..steps) {
                            val p = last + (now - last) * (i / steps.toFloat())
                            at(p).let { stroke(it.x, it.y, rx, ry) }
                        }
                        last = now
                        brushAt = now
                        change.consume()
                    }
                    brushAt = null
                }
            },
    ) {
        val box = fit(picture.width, picture.height, size)
        val dstOffset = IntOffset(box.left.roundToInt(), box.top.roundToInt())
        val dstSize = IntSize(box.width.roundToInt(), box.height.roundToInt())
        drawImage(picture, IntOffset.Zero, IntSize(picture.width, picture.height), dstOffset, dstSize, filterQuality = FilterQuality.Medium)
        if (showShade) {
            shade.version // redraw when the mask changes
            drawImage(shade.image, IntOffset.Zero, IntSize(MASK, MASK), dstOffset, dstSize, filterQuality = FilterQuality.Low)
        }
        brushAt?.let { drawBrush(it, BRUSH.toPx(), tool == CutTool.ADD) }
    }
}

private fun DrawScope.drawBrush(at: Offset, radius: Float, add: Boolean) {
    drawCircle(Color.Black.copy(alpha = 0.5f), radius, at, style = Stroke(width = 3.dp.toPx()))
    drawCircle(if (add) Color.White else Color.White.copy(alpha = 0.7f), radius, at, style = Stroke(width = 1.5.dp.toPx()))
}

@Composable
private fun ColumnScope.CutControls(
    tool: CutTool,
    onTool: (CutTool) -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onNext: () -> Unit,
    enabled: Boolean,
) {
    val ink = CarbonColors
    PodiumText(
        when (tool) {
            CutTool.SUBJECT -> "Tap what you want to keep."
            CutTool.ADD -> "Paint over what should be kept."
            CutTool.ERASE -> "Paint over what should go."
        },
        PodiumTheme.type.caption,
        ink.labelTertiary,
        Modifier.padding(bottom = Spacing.s),
    )
    SegmentedKeys(CutTool.entries.map { it.label }, tool.ordinal, { onTool(CutTool.entries[it]) }, Modifier.fillMaxWidth())
    Spacer(Modifier.height(Spacing.s))
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        PanelButton("Undo", { if (enabled && canUndo) onUndo() }, Modifier.weight(1f), emphasized = false, color = if (canUndo) null else ink.labelTertiary)
        PanelButton("Redo", { if (enabled && canRedo) onRedo() }, Modifier.weight(1f), emphasized = false, color = if (canRedo) null else ink.labelTertiary)
        PanelButton("Next", { if (enabled) onNext() }, Modifier.weight(1.4f))
    }
}

/** The finished sticker on a neutral grey, so a white or a black border both read. */
@Composable
private fun BorderPreview(preview: ImageBitmap?) {
    Canvas(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(NeutralGrey)
            .semantics { contentDescription = "Your sticker" },
    ) {
        val image = preview ?: return@Canvas
        val box = fit(image.width, image.height, Size(size.width * 0.86f, size.height * 0.86f))
        drawImage(
            image,
            IntOffset.Zero,
            IntSize(image.width, image.height),
            IntOffset((box.left + size.width * 0.07f).roundToInt(), (box.top + size.height * 0.07f).roundToInt()),
            IntSize(box.width.roundToInt(), box.height.roundToInt()),
            filterQuality = FilterQuality.Medium,
        )
    }
}

@Composable
private fun ColumnScope.BorderControls(
    border: StickerBorder,
    onBorder: (StickerBorder) -> Unit,
    thickness: Float,
    onThickness: (Float) -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
    enabled: Boolean,
) {
    val ink = CarbonColors
    val borders = listOf(StickerBorder.NONE to "Off", StickerBorder.BLACK to "Black", StickerBorder.WHITE to "White")
    SegmentedKeys(borders.map { it.second }, borders.indexOfFirst { it.first == border }, { onBorder(borders[it].first) }, Modifier.fillMaxWidth())
    Spacer(Modifier.height(Spacing.s))
    if (border != StickerBorder.NONE) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PodiumText("Thickness", PodiumTheme.type.rowSecondary, ink.labelSecondary, Modifier.weight(1f))
            PodiumText(thicknessWord(thickness), PodiumTheme.type.caption, ink.labelTertiary)
        }
        Spacer(Modifier.height(Spacing.xs))
        ThicknessBar(thickness, onThickness)
        Spacer(Modifier.height(Spacing.s))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        PanelButton("Back", { if (enabled) onBack() }, Modifier.weight(1f), emphasized = false)
        PanelButton("Save sticker", { if (enabled) onSave() }, Modifier.weight(1.6f))
    }
}

private fun thicknessWord(t: Float) = when {
    t < 0.25f -> "Fine"
    t < 0.6f -> "Medium"
    t < 0.85f -> "Thick"
    else -> "Bold"
}

/** A plain bar: the filled part lit, a round thumb; a touch or a drag sets it. */
@Composable
private fun ThicknessBar(value: Float, onValue: (Float) -> Unit) {
    val ink = CarbonColors
    val set by rememberUpdatedState(onValue)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .semantics {
                contentDescription = "Border thickness"
                stateDescription = thicknessWord(value)
                progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f)
                setProgress { set(it.coerceIn(0f, 1f)); true }
            }
            .pointerInput(Unit) { detectTapGestures { set((it.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    set((change.position.x / size.width).coerceIn(0f, 1f))
                }
            },
    ) {
        val r = 9.dp.toPx()
        val y = size.height / 2
        val track = 4.dp.toPx()
        val x = r + (size.width - 2 * r) * value
        drawLine(ink.separator, Offset(r, y), Offset(size.width - r, y), strokeWidth = track, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(ink.labelPrimary, Offset(r, y), Offset(x, y), strokeWidth = track, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawCircle(ink.labelPrimary, r, Offset(x, y))
    }
}

/** [w]×[h] fitted inside [into], centred. */
private fun fit(w: Int, h: Int, into: Size): Rect {
    val s = min(into.width / w, into.height / h)
    val fw = w * s
    val fh = h * s
    val left = (into.width - fw) / 2
    val top = (into.height - fh) / 2
    return Rect(left, top, left + fw, top + fh)
}

/** The dimming over the picture, one pixel per mask cell, rebuilt as the mask changes. */
@Stable
private class Shade {
    private val pixels = IntArray(MASK * MASK)
    private val bitmap = Bitmap.createBitmap(MASK, MASK, Bitmap.Config.ARGB_8888)
    val image: ImageBitmap = bitmap.asImageBitmap()
    var version by mutableIntStateOf(0)
        private set

    fun update(mask: FloatArray) {
        StickerPictures.shade(mask, pixels)
        bitmap.setPixels(pixels, 0, MASK, 0, 0, MASK, MASK)
        version++
    }
}

/** Undo and redo for the mask: whole copies (a megabyte each), the last [LIMIT] kept. */
@Stable
internal class MaskHistory(private val mask: FloatArray) {
    private val undos = ArrayDeque<FloatArray>()
    private val redos = ArrayDeque<FloatArray>()
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    /** The mask as it is now, before a change. */
    fun checkpoint() {
        undos.addLast(mask.copyOf())
        if (undos.size > LIMIT) undos.removeFirst()
        redos.clear()
        sync()
    }

    fun undo(): Boolean {
        val previous = undos.removeLastOrNull() ?: return false
        redos.addLast(mask.copyOf())
        previous.copyInto(mask)
        sync()
        return true
    }

    fun redo(): Boolean {
        val next = redos.removeLastOrNull() ?: return false
        undos.addLast(mask.copyOf())
        next.copyInto(mask)
        sync()
        return true
    }

    private fun sync() {
        canUndo = undos.isNotEmpty()
        canRedo = redos.isNotEmpty()
    }

    companion object {
        const val LIMIT = 16
    }
}

private const val MASK = SubjectCutter.SIZE
private const val MIN_COVERAGE = 0.002f
private val BRUSH = 22.dp
private val NeutralGrey = Color(0xFF7A7A80)
