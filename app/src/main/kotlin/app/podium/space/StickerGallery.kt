package app.podium.space

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.podium.core.designsystem.theme.CarbonColors
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.designsystem.theme.Spacing
import app.podium.core.designsystem.type.PodiumText
import app.podium.stickers.Sticker
import app.podium.stickers.StickerStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The sticker gallery (D-55): every sticker the listener has made, newest first, behind an "Add a
 * sticker" tile; a dot marks the ones stuck on the Podium. A tile opens that sticker.
 */
@Composable
fun ColumnScope.StickerGallery(store: StickerStore, onAdd: () -> Unit, onOpen: (Sticker) -> Unit, onArrange: () -> Unit) {
    val ink = CarbonColors
    val stickers by store.stickers.collectAsStateWithLifecycle()
    val placements by store.placements.collectAsStateWithLifecycle()
    val stuck = remember(placements) { placements.mapTo(HashSet()) { it.stickerId } }
    val newestFirst = remember(stickers) { stickers.sortedByDescending { it.createdAt } }
    if (stickers.isEmpty()) {
        PodiumText(
            "Make a sticker from any picture: Podium keeps its subject, on this phone, and you stick it anywhere on your Podium.",
            PodiumTheme.type.caption,
            ink.labelTertiary,
            Modifier.padding(bottom = Spacing.s),
            maxLines = 4,
        )
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = TileMin),
        modifier = Modifier.weight(1f).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        item(key = "add") { AddTile(onAdd) }
        items(newestFirst, key = { it.id }) { sticker ->
            StickerTile(store, sticker, stuck = sticker.id in stuck) { onOpen(sticker) }
        }
    }
    if (placements.isNotEmpty()) {
        Spacer(Modifier.height(Spacing.xs))
        PanelRow("Arrange stickers", "Move, resize, turn or take them off", onClick = onArrange)
    }
}

/** One sticker, larger: is it on the Podium, stick it on (again), arrange, take it off, delete it. */
@Composable
fun ColumnScope.StickerDetail(
    store: StickerStore,
    stickerId: String?,
    onStick: (Sticker) -> Unit,
    onArrange: () -> Unit,
    onDeleted: () -> Unit,
) {
    val ink = CarbonColors
    val stickers by store.stickers.collectAsStateWithLifecycle()
    val placements by store.placements.collectAsStateWithLifecycle()
    val sticker = stickers.firstOrNull { it.id == stickerId }
    if (sticker == null) {
        PodiumText("This sticker is gone.", PodiumTheme.type.caption, ink.labelTertiary)
        return
    }
    val times = placements.count { it.stickerId == sticker.id }
    var confirming by remember(sticker.id) { mutableStateOf(false) }
    Box(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .background(ink.canvas, TileShape),
        contentAlignment = Alignment.Center,
    ) {
        StickerImage(store, sticker.id, Modifier.fillMaxSize().padding(Spacing.m), maxSide = 512)
    }
    Spacer(Modifier.height(Spacing.s))
    PodiumText(
        when (times) {
            0 -> "Not on your Podium yet"
            1 -> "On your Podium"
            else -> "On your Podium $times times"
        },
        PodiumTheme.type.caption,
        ink.labelSecondary,
        Modifier.padding(bottom = Spacing.s),
    )
    PanelButton(if (times == 0) "Stick it on your Podium" else "Stick on another", { onStick(sticker) })
    if (times > 0) {
        PanelRow("Arrange stickers", null, onClick = onArrange)
        PanelRow("Take it off your Podium", null, chevron = false) {
            placements.filter { it.stickerId == sticker.id }.forEach { store.removePlacement(it.id) }
        }
    }
    PanelRow(
        if (confirming) "Tap again to delete it" else "Delete sticker",
        if (confirming) "It comes off your Podium too" else null,
        tint = ink.critical,
        chevron = false,
    ) {
        if (!confirming) {
            confirming = true
        } else {
            store.deleteSticker(sticker.id)
            onDeleted()
        }
    }
}

@Composable
private fun AddTile(onAdd: () -> Unit) {
    val ink = CarbonColors
    Column(
        Modifier
            .aspectRatio(1f)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onAdd)
            .semantics {
                role = Role.Button
                contentDescription = "Add a sticker"
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val r = 14.dp.toPx()
            drawRoundRect(
                ink.labelTertiary,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(r),
                style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
            )
            val c = Offset(size.width / 2, size.height / 2)
            val arm = min(size.width, size.height) * 0.16f
            val w = 2.dp.toPx()
            drawLine(ink.labelPrimary, c - Offset(arm, 0f), c + Offset(arm, 0f), w, StrokeCap.Round)
            drawLine(ink.labelPrimary, c - Offset(0f, arm), c + Offset(0f, arm), w, StrokeCap.Round)
        }
        PodiumText("Add a sticker", PodiumTheme.type.caption, ink.labelSecondary, Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun StickerTile(store: StickerStore, sticker: Sticker, stuck: Boolean, onOpen: () -> Unit) {
    val ink = CarbonColors
    Column(
        Modifier
            .aspectRatio(1f)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onOpen)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = if (stuck) "Sticker, on your Podium" else "Sticker"
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(ink.canvas, TileShape)
                .border(1.dp, ink.separator, TileShape),
        ) {
            StickerImage(store, sticker.id, Modifier.fillMaxSize().padding(6.dp), maxSide = 256)
            if (stuck) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(6.dp).background(ink.highlightText, CircleShape))
        }
        // Keeps tiles the same height as the Add tile's label row.
        Spacer(Modifier.height(TileLabel))
    }
}

/** A sticker's picture, decoded off the main thread at [maxSide], fitted and centred. */
@Composable
internal fun StickerImage(store: StickerStore, id: String, modifier: Modifier, maxSide: Int) {
    var image by remember(id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(store, id, maxSide) {
        image = withContext(Dispatchers.IO) { store.image(id, maxSide)?.asImageBitmap() }
    }
    Canvas(modifier) {
        val img = image ?: return@Canvas
        val s = min(size.width / img.width, size.height / img.height)
        val w = img.width * s
        val h = img.height * s
        drawImage(
            img,
            IntOffset.Zero,
            IntSize(img.width, img.height),
            IntOffset(((size.width - w) / 2).roundToInt(), ((size.height - h) / 2).roundToInt()),
            IntSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1)),
            filterQuality = FilterQuality.Medium,
        )
    }
}

private val TileShape = RoundedCornerShape(14.dp)
private val TileMin = 76.dp
private val TileLabel = 18.dp
