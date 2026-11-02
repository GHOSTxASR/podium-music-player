package app.podium.feature.library

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.podium.core.designsystem.component.DetailHeader
import app.podium.core.designsystem.component.FocusList
import app.podium.core.designsystem.component.KeyboardAwayOnLeave
import app.podium.core.designsystem.component.ListInputEffect
import app.podium.core.designsystem.component.LoadingScreen
import app.podium.core.designsystem.component.LocalMiniature
import app.podium.core.designsystem.component.LocalOverlayHost
import app.podium.core.designsystem.component.LocalScreenInsets
import app.podium.core.designsystem.component.MenuAction
import app.podium.core.designsystem.component.MenuPreview
import app.podium.core.designsystem.component.MenuRow
import app.podium.core.designsystem.component.MenuSpec
import app.podium.core.designsystem.component.PaperTextField
import app.podium.core.designsystem.component.SectionHeader
import app.podium.core.designsystem.component.TrackRow
import app.podium.core.designsystem.component.formatDuration
import app.podium.core.designsystem.symbol.PodiumSymbol
import app.podium.core.interaction.InputTargetEffect
import app.podium.core.interaction.PodiumInput
import app.podium.core.interaction.WheelButton
import app.podium.core.interaction.WheelContext
import app.podium.core.interaction.rememberFocusListState
import app.podium.core.interaction.rememberPodiumHaptics
import app.podium.core.model.Track
import kotlinx.coroutines.launch

private sealed interface PlaylistsRow {
    val key: String

    data object New : PlaylistsRow { override val key = PlaylistKeys.NEW }

    /** Where the playlists will be, while there are none (not focusable). */
    data object Hint : PlaylistsRow { override val key = "hint" }

    data class One(val playlist: LibraryPlaylist) : PlaylistsRow { override val key = playlist.id }
}

/** Music ▸ Playlists (D-106): New playlist, then the listener's playlists, the latest changed first. */
@Composable
fun PlaylistsScreen(repository: PlaylistRepository, onOpen: (LibraryPlaylist) -> Unit, onNew: () -> Unit) {
    val playlists by repository.playlists.collectAsStateWithLifecycle()
    val rows = remember(playlists) {
        buildList {
            add(PlaylistsRow.New)
            playlists.orEmpty().forEach { add(PlaylistsRow.One(it)) }
            if (playlists?.isEmpty() == true) add(PlaylistsRow.Hint)
        }
    }
    val focus = rememberFocusListState("playlists")
    val activate: (Int) -> Unit = { i ->
        when (val row = rows.getOrNull(i)) {
            PlaylistsRow.New -> {
                PendingPlaylistSongs.songs = emptyList()
                onNew()
            }
            is PlaylistsRow.One -> onOpen(row.playlist)
            PlaylistsRow.Hint, null -> Unit
        }
    }
    ListInputEffect(focus, onActivate = activate)
    if (playlists == null) {
        LoadingScreen()
        return
    }
    FocusList(
        items = rows,
        state = focus,
        key = { it.key },
        contentPadding = LocalScreenInsets.current.listPadding(),
        onActivate = activate,
        focusable = { it !is PlaylistsRow.Hint },
        preview = { row -> (row as? PlaylistsRow.One)?.playlist?.covers?.takeIf { it.isNotEmpty() }?.let { MenuPreview.Artwork(it) } ?: MenuPreview.None },
        modifier = Modifier.fillMaxSize(),
    ) { row, _, focused ->
        when (row) {
            PlaylistsRow.New -> MenuRow("New playlist", focused, leading = PodiumSymbol.Queue)
            is PlaylistsRow.One -> TrackRow(
                row.playlist.name,
                if (row.playlist.trackCount == 0) "No songs yet" else songCount(row.playlist.trackCount),
                focused,
                row.playlist.covers.firstOrNull(),
            )
            PlaylistsRow.Hint -> SectionHeader("Make one here, or hold Center on any song and choose Add to playlist.")
        }
    }
}

private sealed interface PlaylistRow {
    val key: String

    data object Header : PlaylistRow { override val key = "header" }
    data object Play : PlaylistRow { override val key = "play" }
    data object Shuffle : PlaylistRow { override val key = "shuffle" }
    data object AddSongs : PlaylistRow { override val key = PlaylistKeys.ADD_SONGS }
    data object Rename : PlaylistRow { override val key = PlaylistKeys.RENAME }
    data object Delete : PlaylistRow { override val key = "delete" }

    data class Song(val entry: LibraryPlaylistEntry, val index: Int) : PlaylistRow { override val key = "entry:${entry.entryId}" }
}

/** The rows the playlist screens' next columns go back to (a miniature rests on them). */
object PlaylistKeys {
    const val NEW = "new"
    const val ADD_SONGS = "add-songs"
    const val RENAME = "rename"
}

/**
 * One playlist (D-106): Play, Shuffle, Add songs, Rename and Delete, then its songs. Center plays from
 * a song; holding Center offers Move (the Wheel carries the song, Center sets it down, Menu puts it
 * back), Remove from playlist, Play next, Add to Up Next and Add to playlist.
 */
@Composable
fun PlaylistScreen(
    repository: PlaylistRepository,
    playlistId: String,
    name: String,
    onPlay: PlayTracks,
    onShuffle: (tracks: List<Track>, label: String) -> Unit,
    onPlayNext: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onAddSongs: () -> Unit,
    onRename: (currentName: String) -> Unit,
    onDeleted: () -> Unit,
) {
    val loaded = rememberLoaded(repository, playlistId) { repository.playlist(playlistId) }
    val detail = loaded?.value
    val title = detail?.playlist?.name ?: name
    val stored = detail?.entries.orEmpty()
    // What the list shows: the stored order, or a move just made until the stored order catches up.
    var placed by remember(stored) { mutableStateOf(stored) }
    val latest by rememberUpdatedState(placed)
    val overlay = LocalOverlayHost.current
    val picker = LocalPlaylistPicker.current
    val haptics = rememberPodiumHaptics()

    // Wheel move mode, as in Up Next: the song lifts, turning carries it, Center sets it down.
    var moving by remember { mutableStateOf<Long?>(null) }
    var moveTo by remember { mutableIntStateOf(0) }
    val entries = moving?.let { placed.moved(it, moveTo) } ?: placed
    val tracks = entries.map { it.track }
    val covers = remember(stored) { stored.mapNotNull { it.track.artwork?.uri }.distinct().take(COVERS) }

    val rows = buildList {
        add(PlaylistRow.Header)
        if (entries.isNotEmpty()) {
            add(PlaylistRow.Play)
            add(PlaylistRow.Shuffle)
        }
        add(PlaylistRow.AddSongs)
        add(PlaylistRow.Rename)
        add(PlaylistRow.Delete)
        entries.forEachIndexed { i, e -> add(PlaylistRow.Song(e, i)) }
    }
    val place: (Long, Int) -> Unit = { entryId, to ->
        val from = latest.indexOfFirst { it.entryId == entryId }
        val target = to.coerceIn(0, latest.lastIndex)
        if (from >= 0 && target != from) {
            placed = latest.moved(entryId, target)
            repository.move(playlistId, entryId, target)
        }
    }

    val focus = rememberFocusListState("playlist:$playlistId")
    val activate: (Int) -> Unit = { i ->
        when (val row = rows.getOrNull(i)) {
            PlaylistRow.Play -> onPlay(tracks, 0, title)
            PlaylistRow.Shuffle -> onShuffle(tracks, title)
            PlaylistRow.AddSongs -> onAddSongs()
            PlaylistRow.Rename -> onRename(title)
            PlaylistRow.Delete -> overlay.show(
                MenuSpec(
                    "Delete “$title”?",
                    listOf(
                        MenuAction("Delete playlist") {
                            repository.delete(playlistId)
                            onDeleted()
                        },
                        MenuAction("Keep it") {},
                    ),
                ),
            )
            is PlaylistRow.Song -> onPlay(tracks, row.index, title)
            PlaylistRow.Header, null -> Unit
        }
    }
    val longPress: (Int) -> Unit = { i ->
        (rows.getOrNull(i) as? PlaylistRow.Song)?.let { row ->
            val track = row.entry.track
            overlay.show(
                MenuSpec(
                    track.title,
                    buildList {
                        add(
                            MenuAction("Move", enabled = entries.size > 1) {
                                // Picked up (D-105).
                                haptics.dragStart()
                                moving = row.entry.entryId
                                moveTo = row.index
                            },
                        )
                        add(
                            MenuAction("Remove from playlist") {
                                placed = latest.filterNot { it.entryId == row.entry.entryId }
                                repository.remove(playlistId, row.entry.entryId)
                            },
                        )
                        add(MenuAction("Play next") { onPlayNext(track) })
                        add(MenuAction("Add to Up Next") { onAddToQueue(track) })
                        if (picker != null) add(MenuAction("Add to playlist") { picker.show(overlay, listOf(track.id)) })
                    },
                ),
            )
        }
    }
    ListInputEffect(focus, onActivate = activate, onLongPress = longPress)

    moving?.let { entryId ->
        // Registered after the list, so it's on top while a song is being moved.
        InputTargetEffect(WheelContext.QUEUE) { input ->
            when (input) {
                is PodiumInput.Rotate -> {
                    val next = (moveTo + input.detents).coerceIn(0, latest.lastIndex)
                    // Past each song a slot's tick; at the ends, the stop.
                    if (next == moveTo) haptics.boundary() else haptics.dragStep()
                    moveTo = next
                    true
                }
                is PodiumInput.Press -> when (input.button) {
                    WheelButton.CENTER -> {
                        place(entryId, moveTo)
                        haptics.drop()
                        moving = null
                        true
                    }
                    WheelButton.MENU -> {
                        // Put back where it was.
                        haptics.reject()
                        moving = null
                        true
                    }
                    else -> false
                }
                else -> true
            }
        }
        // Keep the lifted song under the lens as it travels.
        val row = rows.indexOfFirst { it is PlaylistRow.Song && it.entry.entryId == entryId }
        LaunchedEffect(row) { if (row >= 0) focus.focus(row) }
    }

    if (loaded == null) {
        LoadingScreen()
        return
    }
    if (detail == null) {
        EmptyState(PodiumSymbol.Queue, "This playlist isn't here anymore", "It may have been deleted. Go back to Playlists.")
        return
    }
    FocusList(
        items = rows,
        state = focus,
        key = { it.key },
        contentPadding = LocalScreenInsets.current.listPadding(),
        onActivate = activate,
        onLongPress = longPress,
        focusable = { it !is PlaylistRow.Header },
        preview = { row ->
            when (row) {
                is PlaylistRow.Song -> MenuPreview.Artwork(listOfNotNull(row.entry.track.artwork?.uri))
                else -> if (covers.isEmpty()) MenuPreview.None else MenuPreview.Artwork(covers)
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { row, _, focused ->
        when (row) {
            PlaylistRow.Header -> DetailHeader(
                covers.firstOrNull(),
                title,
                if (entries.isEmpty()) "No songs yet" else songCount(entries.size),
                listOfNotNull(playlistLength(tracks)),
            )
            PlaylistRow.Play -> MenuRow("Play", focused, leading = PodiumSymbol.Play, showChevron = false)
            PlaylistRow.Shuffle -> MenuRow("Shuffle", focused, leading = PodiumSymbol.Shuffle, showChevron = false)
            PlaylistRow.AddSongs -> MenuRow("Add songs", focused)
            PlaylistRow.Rename -> MenuRow("Rename", focused)
            PlaylistRow.Delete -> MenuRow("Delete playlist", focused, showChevron = false)
            is PlaylistRow.Song -> TrackRow(
                title = row.entry.track.title,
                subtitle = row.entry.track.artistDisplay,
                focused = focused,
                artworkUri = row.entry.track.artwork?.uri,
                trailing = formatDuration(row.entry.track.durationMs),
                note = if (row.entry.entryId == moving) "Turn to move, press to place" else null,
            )
        }
    }
}

/** The entries with [entryId] moved so it ends up at [to]. */
internal fun List<LibraryPlaylistEntry>.moved(entryId: Long, to: Int): List<LibraryPlaylistEntry> {
    val from = indexOfFirst { it.entryId == entryId }
    if (from < 0 || to !in indices || to == from) return this
    val list = toMutableList()
    list.add(to, list.removeAt(from))
    return list
}

/**
 * A playlist ▸ Add songs (D-106): every song, A to Z. Center adds the focused song, and on a song
 * that's in the playlist takes its latest addition back out.
 */
@Composable
fun AddSongsScreen(library: LibraryRepository, repository: PlaylistRepository, playlistId: String) {
    val (tracks, loading) = rememberTracks(library)
    val detail = rememberLoaded(repository, playlistId) { repository.playlist(playlistId) }?.value
    // Each song in the playlist, with its entries (the latest last).
    val inPlaylist = remember(detail) { detail?.entries.orEmpty().groupBy({ it.track.id }, { it.entryId }) }
    val haptics = rememberPodiumHaptics()
    val focus = rememberFocusListState("add-songs:$playlistId")
    val toggle: (Int) -> Unit = { i ->
        tracks.getOrNull(i)?.let { track ->
            val entries = inPlaylist[track.id]
            if (entries.isNullOrEmpty()) {
                repository.add(playlistId, listOf(track.id))
                haptics.toggle(on = true)
            } else {
                repository.remove(playlistId, entries.max())
                haptics.toggle(on = false)
            }
        }
    }
    ListInputEffect(focus, onActivate = toggle)
    when {
        loading -> LoadingScreen("Reading your music")
        tracks.isEmpty() -> EmptyState(PodiumSymbol.Note, "No songs yet", noMusic(library))
        else -> FocusList(
            items = tracks,
            state = focus,
            key = { it.id.value },
            contentPadding = LocalScreenInsets.current.listPadding(),
            onActivate = toggle,
            preview = { MenuPreview.Artwork(listOfNotNull(it.artwork?.uri)) },
            modifier = Modifier.fillMaxSize(),
        ) { track, _, focused ->
            TrackRow(
                title = track.title,
                subtitle = track.artistDisplay,
                focused = focused,
                artworkUri = track.artwork?.uri,
                trailing = formatDuration(track.durationMs),
                note = if (track.id in inPlaylist) "Added" else null,
            )
        }
    }
}

/**
 * Naming a new playlist, with any songs waiting for it, or renaming one (D-106). Center on the last
 * row saves, as does the keyboard's Done.
 */
@Composable
fun NamePlaylistScreen(
    repository: PlaylistRepository,
    playlistId: String?,
    currentName: String,
    onRenamed: () -> Unit,
    /** A new playlist [id] named [name]; [withSongs] when songs were waiting for it. */
    onCreated: (id: String, name: String, withSongs: Boolean) -> Unit,
) {
    var name by rememberSaveable(playlistId) { mutableStateOf(currentName) }
    var saving by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val haptics = rememberPodiumHaptics()
    val save: () -> Unit = {
        val chosen = name.trim()
        when {
            chosen.isEmpty() -> haptics.reject()
            saving -> Unit
            playlistId != null -> {
                repository.rename(playlistId, chosen)
                haptics.confirm()
                onRenamed()
            }
            else -> {
                saving = true
                scope.launch {
                    val songs = PendingPlaylistSongs.songs
                    PendingPlaylistSongs.songs = emptyList()
                    val id = repository.create(chosen, songs)
                    haptics.confirm()
                    onCreated(id, chosen, songs.isNotEmpty())
                }
            }
        }
    }
    val rows = listOf("field", "done")
    val focus = rememberFocusListState("name-playlist")
    if (!LocalMiniature.current) {
        LaunchedEffect(Unit) {
            fieldFocus.requestFocus()
            focus.focus(1)
        }
        KeyboardAwayOnLeave()
    }
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
            PaperTextField(name, { name = it }, fieldFocus, save, placeholder = "Playlist name", description = "Playlist name")
        } else {
            MenuRow(if (playlistId == null) "Create playlist" else "Save name", focused, enabled = name.isNotBlank(), showChevron = false)
        }
    }
}

private const val COVERS = 8
