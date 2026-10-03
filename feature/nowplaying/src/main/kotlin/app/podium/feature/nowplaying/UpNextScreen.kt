package app.podium.feature.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.podium.core.designsystem.component.FocusList
import app.podium.core.designsystem.component.ListInputEffect
import app.podium.core.designsystem.component.LocalOverlayHost
import app.podium.core.designsystem.component.LocalScreenInsets
import app.podium.core.designsystem.component.MenuAction
import app.podium.core.designsystem.component.MenuPreview
import app.podium.core.designsystem.component.MenuRow
import app.podium.core.designsystem.component.MenuSpec
import app.podium.core.designsystem.component.SectionHeader
import app.podium.core.designsystem.component.TrackRow
import app.podium.core.designsystem.component.formatDuration
import app.podium.core.designsystem.symbol.PodiumSymbol
import app.podium.core.designsystem.symbol.Symbol
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.designsystem.theme.Spacing
import app.podium.core.interaction.InputTargetEffect
import app.podium.core.interaction.PodiumInput
import app.podium.core.interaction.WheelButton
import app.podium.core.interaction.WheelContext
import app.podium.core.interaction.rememberFocusListState
import app.podium.core.interaction.rememberPodiumHaptics
import app.podium.core.model.QueueUid
import app.podium.player.api.PlaybackController
import app.podium.player.api.QueueEntry
import app.podium.player.api.QueueOrigin
import app.podium.player.api.QueueView
import app.podium.player.api.RepeatMode
import kotlin.math.roundToInt

private sealed interface UpNextRow {
    val key: String

    data object Shuffle : UpNextRow { override val key = "shuffle" }
    data object Repeat : UpNextRow { override val key = "repeat" }
    data object Empty : UpNextRow { override val key = "empty" }

    /** Section headers are their own rows, never focusable, so the lens only ever covers songs. */
    data class Header(val text: String, override val key: String) : UpNextRow

    data class Entry(val entry: QueueEntry) : UpNextRow {
        override val key = entry.uid.value
    }
}

private fun headerFor(origin: QueueOrigin, context: String?): String = when (origin) {
    QueueOrigin.PLAY_NEXT -> "Playing next"
    QueueOrigin.USER_QUEUED -> "Your queue"
    QueueOrigin.CONTEXT -> context?.let { "Continuing from $it" } ?: "Continuing"
    QueueOrigin.AUTOPLAY -> "Autoplay"
}

/**
 * Up Next (queue-and-autoplay.md §1): Shuffle and Repeat as iPod-style setting rows, the song
 * that's playing, then the queue as runs in true play order. Center on a song skips to it; its
 * menu can play it next, move it with the Wheel, or remove it. Everything goes through the real
 * queue — nothing here is local state pretending to be the queue.
 */
@Composable
fun UpNextScreen(controller: PlaybackController) {
    val queue by controller.queue.collectAsStateWithLifecycle()
    val snapshot by controller.snapshot.collectAsStateWithLifecycle()
    val overlay = LocalOverlayHost.current
    val haptics = rememberPodiumHaptics()

    // Wheel move mode: the song lifts, turning places it among the upcoming songs, Center drops it.
    var moving by remember { mutableStateOf<QueueUid?>(null) }
    var moveTo by remember { mutableIntStateOf(-1) }
    // Touch drag: the list stays still and an insertion line shows where the song will land.
    var dragging by remember { mutableStateOf<QueueUid?>(null) }
    var dragFrom by remember { mutableIntStateOf(-1) }
    var dragSteps by remember { mutableIntStateOf(0) }
    val latestQueue by rememberUpdatedState(queue)
    val dragTarget = (dragFrom + dragSteps).coerceIn(queue.currentIndex + 1, queue.entries.lastIndex.coerceAtLeast(queue.currentIndex + 1))
    val shown = moving?.let { uid -> queue.previewMove(uid, moveTo) } ?: queue

    val rows = buildList {
        add(UpNextRow.Shuffle)
        add(UpNextRow.Repeat)
        shown.entries.getOrNull(shown.currentIndex)?.let {
            add(UpNextRow.Header("Now playing", "h-now"))
            add(UpNextRow.Entry(it))
        }
        val sections = shown.sections
        if (sections.isEmpty()) add(UpNextRow.Empty)
        sections.forEachIndexed { s, (origin, entries) ->
            add(UpNextRow.Header(headerFor(origin, shown.contextLabel), "h-$s-${entries.first().uid.value}"))
            entries.forEach { add(UpNextRow.Entry(it)) }
        }
    }

    val focus = rememberFocusListState("upnext")
    val activate: (Int) -> Unit = { index ->
        when (val row = rows[index]) {
            UpNextRow.Shuffle -> controller.setShuffle(!snapshot.shuffleEnabled)
            UpNextRow.Repeat -> controller.setRepeat(
                when (snapshot.repeatMode) {
                    RepeatMode.OFF -> RepeatMode.ALL
                    RepeatMode.ALL -> RepeatMode.ONE
                    RepeatMode.ONE -> RepeatMode.OFF
                },
            )
            is UpNextRow.Entry -> if (!row.entry.isCurrent) controller.skipTo(row.entry.uid)
            else -> Unit
        }
    }
    val menu: (Int) -> Unit = { index ->
        (rows[index] as? UpNextRow.Entry)?.entry?.takeIf { !it.isCurrent }?.let { entry ->
            overlay.show(
                MenuSpec(
                    entry.title,
                    listOf(
                        MenuAction("Play next") { controller.move(entry.uid, queue.currentIndex + 1) },
                        MenuAction("Move", enabled = queue.upNext.size > 1) {
                            moving = entry.uid
                            moveTo = queue.entries.indexOfFirst { it.uid == entry.uid }
                        },
                        MenuAction("Remove from Up Next") { controller.remove(setOf(entry.uid)) },
                    ),
                ),
            )
        }
    }
    ListInputEffect(focus, onActivate = activate, onLongPress = menu)

    moving?.let { uid ->
        // Registered after the list, so it's on top while a song is being moved.
        InputTargetEffect(WheelContext.QUEUE) { input ->
            when (input) {
                is PodiumInput.Rotate -> {
                    val next = (moveTo + input.detents).coerceIn(queue.currentIndex + 1, queue.entries.lastIndex)
                    if (next == moveTo) haptics.boundary()
                    moveTo = next
                    true
                }
                is PodiumInput.Press -> when (input.button) {
                    WheelButton.CENTER -> {
                        controller.move(uid, moveTo)
                        haptics.confirm()
                        moving = null
                        true
                    }
                    WheelButton.MENU -> {
                        moving = null
                        true
                    }
                    else -> false
                }
                else -> true
            }
        }
        // Keep the lifted song under the lens as it travels.
        val row = rows.indexOfFirst { it is UpNextRow.Entry && it.entry.uid == uid }
        LaunchedEffect(row) { if (row >= 0) focus.focus(row) }
    }

    FocusList(
        items = rows,
        state = focus,
        key = { it.key },
        contentPadding = LocalScreenInsets.current.listPadding(),
        onActivate = activate,
        onLongPress = menu,
        focusable = { it !is UpNextRow.Header && it !is UpNextRow.Empty },
        preview = { row -> (row as? UpNextRow.Entry)?.entry?.artworkUri?.let { MenuPreview.Artwork(listOf(it)) } ?: MenuPreview.None },
        modifier = Modifier.fillMaxSize(),
    ) { row, _, focused ->
        when (row) {
            UpNextRow.Shuffle -> MenuRow("Shuffle", focused, value = if (snapshot.shuffleEnabled) "On" else "Off", showChevron = false)
            UpNextRow.Repeat -> MenuRow(
                "Repeat",
                focused,
                value = when (snapshot.repeatMode) {
                    RepeatMode.OFF -> "Off"
                    RepeatMode.ALL -> "All"
                    RepeatMode.ONE -> "One"
                },
                showChevron = false,
            )
            UpNextRow.Empty -> MenuRow("Nothing up next", focused = false, showChevron = false, enabled = false)
            is UpNextRow.Header -> SectionHeader(row.text)
            is UpNextRow.Entry -> {
                val uid = row.entry.uid
                val index = queue.entries.indexOfFirst { it.uid == uid }
                val lineAbove = dragging != null && dragSteps < 0 && index == dragTarget
                val lineBelow = dragging != null && dragSteps > 0 && index == dragTarget
                Column {
                    if (lineAbove) InsertionLine()
                    TrackRow(
                        title = row.entry.title,
                        subtitle = row.entry.artistDisplay,
                        focused = focused,
                        artworkUri = row.entry.artworkUri,
                        trailing = formatDuration(row.entry.durationMs),
                        note = when (uid) {
                            moving -> "Turn to move, press to place"
                            dragging -> "Drag to move"
                            else -> null
                        },
                        active = row.entry.isCurrent,
                        trailingContent = if (row.entry.isCurrent) {
                            null
                        } else {
                            {
                                DragHandle(
                                    title = row.entry.title,
                                    onStart = {
                                        dragging = uid
                                        dragFrom = latestQueue.entries.indexOfFirst { it.uid == uid }
                                        dragSteps = 0
                                    },
                                    onSteps = { dragSteps = it },
                                    onEnd = {
                                        if (dragSteps != 0) {
                                            controller.move(uid, dragTarget)
                                            haptics.confirm()
                                        }
                                        dragging = null
                                    },
                                    onCancel = { dragging = null },
                                    onMove = { delta ->
                                        val from = latestQueue.entries.indexOfFirst { it.uid == uid }
                                        controller.move(uid, from + delta)
                                    },
                                )
                            }
                        },
                    )
                    if (lineBelow) InsertionLine()
                }
            }
        }
    }
}

/** The queue as it would look with [uid] moved to [to] (upcoming songs only). */
private fun QueueView.previewMove(uid: QueueUid, to: Int): QueueView {
    val from = entries.indexOfFirst { it.uid == uid }
    if (from <= currentIndex || to <= currentIndex || to !in entries.indices) return this
    val list = entries.toMutableList()
    val moved = list.removeAt(from)
    list.add(to, moved)
    // Same rule as QueueManager.move: the song joins the section it lands in.
    val before = list.getOrNull(to - 1)?.takeIf { to - 1 > currentIndex }
    val after = list.getOrNull(to + 1)
    list[to] = moved.copy(origin = before?.origin ?: after?.origin ?: moved.origin)
    return copy(entries = list)
}

/** Where a dragged song will land. */
@Composable
private fun InsertionLine() {
    val colors = PodiumTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.gutter)
            .height(2.dp)
            .background(colors.labelPrimary.copy(alpha = 0.8f), RoundedCornerShape(1.dp)),
    )
}

/**
 * The reorder handle: drag vertically to move the song (counted in rows); TalkBack gets "Move up"
 * and "Move down" actions instead.
 */
@Composable
private fun DragHandle(
    title: String,
    onStart: () -> Unit,
    onSteps: (Int) -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit,
    onMove: (Int) -> Unit,
) {
    val colors = PodiumTheme.colors
    val rowPx = with(LocalDensity.current) { Spacing.trackRow.toPx() }
    Box(
        Modifier
            .size(44.dp)
            .pointerInput(title) {
                var travelled = 0f
                detectDragGestures(
                    onDragStart = {
                        travelled = 0f
                        onStart()
                    },
                    onDragEnd = onEnd,
                    onDragCancel = onCancel,
                ) { change, amount ->
                    change.consume()
                    travelled += amount.y
                    onSteps((travelled / rowPx).roundToInt())
                }
            }
            .semantics {
                contentDescription = "Reorder $title"
                customActions = listOf(
                    CustomAccessibilityAction("Move up") { onMove(-1); true },
                    CustomAccessibilityAction("Move down") { onMove(1); true },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Symbol(PodiumSymbol.DragHandle, colors.labelTertiary, size = 20.dp, weight = 500)
    }
}
