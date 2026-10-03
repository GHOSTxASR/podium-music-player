package app.podium.feature.online

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.podium.core.designsystem.component.DetailHeader
import app.podium.core.designsystem.component.FocusList
import app.podium.core.designsystem.component.ListInputEffect
import app.podium.core.designsystem.component.LocalScreenInsets
import app.podium.core.designsystem.component.MenuAction
import app.podium.core.designsystem.component.MenuPreview
import app.podium.core.designsystem.component.MenuRow
import app.podium.core.designsystem.component.MenuSpec
import app.podium.core.designsystem.symbol.PodiumSymbol
import app.podium.core.interaction.rememberFocusListState
import app.podium.core.model.Availability
import kotlinx.coroutines.launch

/**
 * ONLINE ▸ Playlists (D-34): the listener's online playlists, separate from anything local. Until a
 * source can hold them they live on the device; every one belongs to its online source.
 */
@Composable
fun OnlinePlaylistsScreen(repository: OnlineRepository, navigate: (OnlinePlace) -> Unit) {
    val playlists by repository.playlists.collectAsStateWithLifecycle(initialValue = null)
    val list = playlists ?: return
    val entries = listOf(
        OnlineEntry("New playlist", MenuPreview.None, leading = PodiumSymbol.Queue, chevron = true) {
            PendingPlaylist.tracks = emptyList()
            navigate(OnlinePlace.NamePlaylist())
        },
    ) + list.map { p ->
        OnlineEntry(p.name, MenuPreview.None, value = if (p.trackCount == 1) "1 song" else "${p.trackCount} songs") {
            navigate(OnlinePlace.MyPlaylist(p.id, p.name))
        }
    }
    OnlinePaperMenu(entries, "online-playlists")
}

private sealed interface MineRow {
    data object Header : MineRow
    data object Play : MineRow
    data object Shuffle : MineRow
    data object Rename : MineRow
    data object Delete : MineRow
    data class Entry(val entry: OnlinePlaylistEntry, val index: Int) : MineRow
}

/** One online playlist: Play, Shuffle, Rename, Delete, then its songs (hold Center to move or remove one). */
@Composable
fun OnlineMyPlaylistScreen(place: OnlinePlace.MyPlaylist, repository: OnlineRepository, actions: OnlineActions, navigate: (OnlinePlace) -> Unit) {
    val source by repository.source.collectAsStateWithLifecycle()
    val contents by remember(place.id) { repository.playlist(place.id) }.collectAsStateWithLifecycle(initialValue = null)
    val overlayHost = overlay()
    val c = contents
    val entries = c?.entries.orEmpty()
    val playable = entries.map { it.track }.filter { it.availability !is Availability.Unavailable }
    val rows = remember(c) {
        if (c == null) emptyList()
        else buildList {
            add(MineRow.Header)
            if (playable.isNotEmpty()) {
                add(MineRow.Play)
                add(MineRow.Shuffle)
            }
            add(MineRow.Rename)
            add(MineRow.Delete)
            entries.forEachIndexed { i, e -> add(MineRow.Entry(e, i)) }
        }
    }
    val focus = rememberFocusListState("online-mine:${place.id}")
    val name = c?.playlist?.name ?: place.name
    val activate: (Int) -> Unit = { i ->
        when (val row = rows.getOrNull(i)) {
            MineRow.Play -> actions.play(playable, 0, name)
            MineRow.Shuffle -> actions.shuffle(playable, name)
            MineRow.Rename -> navigate(OnlinePlace.NamePlaylist(place.id, name))
            MineRow.Delete -> overlayHost.show(
                MenuSpec("Delete “$name”?", listOf(MenuAction("Delete playlist") { repository.deletePlaylist(place.id) }, MenuAction("Keep it") {})),
            )
            is MineRow.Entry -> playable.indexOfFirst { it.id == row.entry.track.id }.takeIf { it >= 0 }?.let { actions.play(playable, it, name) }
            else -> Unit
        }
    }
    val longPress: (Int) -> Unit = { i ->
        (rows.getOrNull(i) as? MineRow.Entry)?.let { row ->
            val track = row.entry.track
            overlayHost.show(
                MenuSpec(
                    track.title,
                    buildList {
                        if (row.index > 0) add(MenuAction("Move up") { repository.movePlaylistEntry(place.id, row.entry.entryId, row.index - 1) })
                        if (row.index < entries.lastIndex) add(MenuAction("Move down") { repository.movePlaylistEntry(place.id, row.entry.entryId, row.index + 1) })
                        add(MenuAction("Remove from playlist") { repository.removeFromPlaylist(place.id, row.entry.entryId) })
                        add(MenuAction("More…") { showTrackMenu(overlayHost, track, repository, actions, navigate, source?.canRecommend == true) })
                    },
                ),
            )
        }
    }
    ListInputEffect(focus, onActivate = activate, onLongPress = longPress)
    if (c == null) return
    FocusList(
        items = rows,
        state = focus,
        key = {
            when (it) {
                MineRow.Header -> "header"
                MineRow.Play -> "play"
                MineRow.Shuffle -> "shuffle"
                MineRow.Rename -> "rename"
                MineRow.Delete -> "delete"
                is MineRow.Entry -> "entry:${it.entry.entryId}"
            }
        },
        contentPadding = LocalScreenInsets.current.listPadding(),
        onActivate = activate,
        onLongPress = longPress,
        focusable = { it !is MineRow.Header },
        preview = { row ->
            when (row) {
                is MineRow.Entry -> MenuPreview.Artwork(listOfNotNull(row.entry.track.artwork?.uri))
                else -> MenuPreview.Carousel(covers(entries.map { it.track }, 10))
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { row, _, focused ->
        when (row) {
            MineRow.Header -> DetailHeader(
                entries.firstNotNullOfOrNull { it.track.artwork?.uri },
                name,
                if (entries.size == 1) "1 song" else "${entries.size} songs",
                listOf("Online playlist on this device"),
            )
            MineRow.Play -> MenuRow("Play", focused, leading = PodiumSymbol.Play, showChevron = false)
            MineRow.Shuffle -> MenuRow("Shuffle", focused, leading = PodiumSymbol.Shuffle, showChevron = false)
            MineRow.Rename -> MenuRow("Rename", focused, showChevron = true)
            MineRow.Delete -> MenuRow("Delete playlist", focused, showChevron = false)
            is MineRow.Entry -> OnlineTrackRow(row.entry.track, focused)
        }
    }
}

/** Naming a new playlist (with any songs waiting for it) or renaming one; Center on Done saves. */
@Composable
fun OnlineNamePlaylistScreen(place: OnlinePlace.NamePlaylist, repository: OnlineRepository, navigate: (OnlinePlace) -> Unit, back: () -> Unit) {
    var name by rememberSaveable(place) { mutableStateOf(place.currentName) }
    val fieldFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val save: () -> Unit = {
        val chosen = name.trim()
        if (chosen.isNotEmpty()) {
            if (place.playlistId != null) {
                repository.renamePlaylist(place.playlistId, chosen)
                back()
            } else {
                scope.launch {
                    val tracks = PendingPlaylist.tracks
                    PendingPlaylist.tracks = emptyList()
                    repository.createPlaylist(chosen, tracks)
                    back()
                }
            }
        }
    }
    val rows = listOf("field", "done")
    val focus = rememberFocusListState("online-name")
    LaunchedEffect(Unit) { fieldFocus.requestFocus(); focus.focus(1) }
    val activate: (Int) -> Unit = { i -> if (i == 0) fieldFocus.requestFocus() else save() }
    ListInputEffect(focus, onActivate = activate)
    FocusList(
        items = rows,
        state = focus,
        key = { it },
        contentPadding = LocalScreenInsets.current.listPadding(),
        onActivate = activate,
        focusable = { it == "done" },
        modifier = Modifier.fillMaxSize(),
    ) { row, _, focused ->
        if (row == "field") {
            NameField(name, { name = it }, fieldFocus, save)
        } else {
            MenuRow(if (place.playlistId == null) "Create playlist" else "Save name", focused, enabled = name.isNotBlank(), showChevron = false)
        }
    }
}

@Composable
private fun NameField(value: String, onChange: (String) -> Unit, focus: FocusRequester, onDone: () -> Unit) =
    SearchField(value, onChange, focus, onDone, placeholder = "Playlist name", description = "Playlist name", search = false)
