package app.podium.feature.nowplaying

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.podium.core.designsystem.component.FocusList
import app.podium.core.designsystem.component.ListInputEffect
import app.podium.core.designsystem.component.LocalOverlayHost
import app.podium.core.designsystem.component.LocalScreenInsets
import app.podium.core.designsystem.component.MenuAction
import app.podium.core.designsystem.component.MenuRow
import app.podium.core.designsystem.component.MenuSpec
import app.podium.core.designsystem.component.SectionHeader
import app.podium.core.designsystem.component.TrackRow
import app.podium.core.designsystem.component.formatDuration
import app.podium.core.interaction.rememberFocusListState
import app.podium.player.api.PlaybackController
import app.podium.player.api.QueueEntry
import app.podium.player.api.QueueOrigin
import app.podium.player.api.RepeatMode

private sealed interface UpNextRow {
    val key: String

    data object Shuffle : UpNextRow { override val key = "shuffle" }
    data object Repeat : UpNextRow { override val key = "repeat" }
    data object Empty : UpNextRow { override val key = "empty" }

    /** A queue entry, optionally preceded by its section header (headers are never focusable). */
    data class Entry(val entry: QueueEntry, val header: String?) : UpNextRow {
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
 * Up Next (queue-and-autoplay.md §1): Shuffle and Repeat as iPod-style setting rows, the current
 * song, then the queue as runs in true play order. Center on a song skips to it.
 */
@Composable
fun UpNextScreen(controller: PlaybackController) {
    val queue by controller.queue.collectAsStateWithLifecycle()
    val snapshot by controller.snapshot.collectAsStateWithLifecycle()
    val overlay = LocalOverlayHost.current

    val rows = buildList<UpNextRow> {
        add(UpNextRow.Shuffle)
        add(UpNextRow.Repeat)
        queue.entries.getOrNull(queue.currentIndex)?.let { add(UpNextRow.Entry(it, "Now playing")) }
        val sections = queue.sections
        if (sections.isEmpty()) add(UpNextRow.Empty)
        sections.forEach { (origin, entries) ->
            entries.forEachIndexed { i, e -> add(UpNextRow.Entry(e, if (i == 0) headerFor(origin, queue.contextLabel) else null)) }
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
            UpNextRow.Empty -> Unit
            is UpNextRow.Entry -> if (!row.entry.isCurrent) controller.skipTo(row.entry.uid)
        }
    }
    val menu: (Int) -> Unit = { index ->
        (rows[index] as? UpNextRow.Entry)?.entry?.takeIf { !it.isCurrent }?.let { entry ->
            overlay.show(
                MenuSpec(
                    entry.title,
                    listOf(
                        MenuAction("Play next") { controller.move(entry.uid, queue.currentIndex + 1) },
                        MenuAction("Remove from Up Next") { controller.remove(setOf(entry.uid)) },
                    ),
                ),
            )
        }
    }
    ListInputEffect(focus, onActivate = activate, onLongPress = menu)

    FocusList(
        items = rows,
        state = focus,
        key = { it.key },
        contentPadding = LocalScreenInsets.current.listPadding(),
        onActivate = activate,
        onLongPress = menu,
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
            is UpNextRow.Entry -> Column {
                row.header?.let { SectionHeader(it) }
                TrackRow(
                    title = row.entry.title,
                    subtitle = row.entry.artistDisplay,
                    focused = focused,
                    artworkUri = row.entry.artworkUri,
                    trailing = formatDuration(row.entry.durationMs),
                )
            }
        }
    }
}
