package app.podium.feature.online

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.podium.core.common.Outcome
import app.podium.core.designsystem.component.FocusList
import app.podium.core.designsystem.component.ListInputEffect
import app.podium.core.designsystem.component.LoadingScreen
import app.podium.core.designsystem.component.LocalScreenInsets
import app.podium.core.designsystem.component.MenuPreview
import app.podium.core.designsystem.component.MenuRow
import app.podium.core.designsystem.component.TrackRow
import app.podium.core.designsystem.symbol.PodiumSymbol
import app.podium.core.interaction.rememberFocusListState
import app.podium.core.model.PlaylistId
import app.podium.core.model.Track
import kotlinx.coroutines.launch

/**
 * ONLINE ▸ Library (D-38): the account's own collection — liked songs, playlists, albums, artists —
 * and Podium's own playlists kept on this phone. Signed out, only what's on this phone, and a way
 * to sign in.
 */
@Composable
fun OnlineLibraryScreen(repository: OnlineRepository, actions: OnlineActions, navigate: (OnlinePlace) -> Unit) {
    val status by repository.status.collectAsStateWithLifecycle()
    val liked by repository.likedTracks.collectAsStateWithLifecycle(initialValue = emptyList())
    val playlists by repository.playlists.collectAsStateWithLifecycle(initialValue = emptyList())
    val account = status?.account
    val canLibrary = status?.canLibrary == true
    val entries = buildList {
        add(OnlineEntry("Liked songs", if (liked.isEmpty()) MenuPreview.None else MenuPreview.Artwork(covers(liked)), value = liked.size.takeIf { it > 0 }?.toString()) { navigate(OnlinePlace.Liked) })
        if (canLibrary) {
            add(OnlineEntry("Playlists", MenuPreview.None, leading = PodiumSymbol.PlaylistPlay) { navigate(OnlinePlace.LibraryPlaylists) })
            add(OnlineEntry("Albums", MenuPreview.None, leading = PodiumSymbol.Album) { navigate(OnlinePlace.LibraryAlbums) })
            add(OnlineEntry("Artists", MenuPreview.None, leading = PodiumSymbol.Person) { navigate(OnlinePlace.LibraryArtists) })
        }
        add(OnlineEntry(if (canLibrary) "On this phone" else "Playlists", MenuPreview.None, value = playlists.size.takeIf { it > 0 }?.toString()) { navigate(OnlinePlace.Playlists) })
        if (account != null && !account.signedIn) {
            add(OnlineEntry(if (account.state == AccountState.EXPIRED) "Sign in again" else "Sign in for your library", MenuPreview.None, leading = PodiumSymbol.Person, chevron = false) { actions.signIn() })
        }
    }
    OnlinePaperMenu(entries, "online-library")
}

@Composable
fun OnlineLibraryPlaylistsScreen(repository: OnlineRepository, navigate: (OnlinePlace) -> Unit) =
    PagedLibraryList(
        stateKey = "online-library-playlists",
        load = repository::libraryPlaylists,
        key = { it.id.value },
        empty = "No playlists yet" to "Playlists you make or save appear here.",
        preview = { MenuPreview.Artwork(listOfNotNull(it.artwork?.uri)) },
        onOpen = { navigate(OnlinePlace.Collection(it.id, it.title, it.isAlbum)) },
    ) { p, focused -> TrackRow(p.title, p.ownerName, focused, p.artwork?.uri, trailing = p.trackCount?.toString()) }

@Composable
fun OnlineLibraryAlbumsScreen(repository: OnlineRepository, navigate: (OnlinePlace) -> Unit) =
    PagedLibraryList(
        stateKey = "online-library-albums",
        load = repository::libraryAlbums,
        key = { it.id.value },
        empty = "No albums yet" to "Albums you save appear here.",
        preview = { MenuPreview.Artwork(listOfNotNull(it.artwork?.uri)) },
        onOpen = { navigate(OnlinePlace.Collection(PlaylistId(it.id.value), it.title, isAlbum = true)) },
    ) { a, focused -> TrackRow(a.title, a.artistDisplay, focused, a.artwork?.uri, trailing = a.year?.toString()) }

@Composable
fun OnlineLibraryArtistsScreen(repository: OnlineRepository, navigate: (OnlinePlace) -> Unit) =
    PagedLibraryList(
        stateKey = "online-library-artists",
        load = repository::libraryArtists,
        key = { it.id.value },
        empty = "No artists yet" to "Artists of the songs you like appear here.",
        preview = { a -> a.artwork?.uri?.let { MenuPreview.Artwork(listOf(it), round = true) } ?: MenuPreview.None },
        onOpen = { navigate(OnlinePlace.Artist(it.id, it.name)) },
    ) { a, focused -> OnlineArtistRow(a, focused) }

/** A list read page by page from the account's library, on the paper. */
@Composable
private fun <T> PagedLibraryList(
    stateKey: String,
    load: suspend (offset: Int, limit: Int) -> Outcome<List<T>>,
    key: (T) -> String,
    empty: Pair<String, String>,
    preview: (T) -> MenuPreview,
    onOpen: (T) -> Unit,
    row: @Composable (T, Boolean) -> Unit,
) {
    val items = remember(stateKey) { mutableStateListOf<T>() }
    var state by remember(stateKey) { mutableStateOf<Remote<Unit>>(Remote.Loading) }
    var exhausted by remember(stateKey) { mutableStateOf(false) }
    var loading by remember(stateKey) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val loadMore: () -> Unit = {
        if (!loading && !exhausted) {
            loading = true
            scope.launch {
                when (val r = load(items.size, PAGE)) {
                    is Outcome.Success -> {
                        val fresh = r.value.filter { n -> items.none { key(it) == key(n) } }
                        items += fresh
                        if (fresh.isEmpty() || r.value.size < PAGE) exhausted = true
                        state = Remote.Ready(Unit)
                    }
                    is Outcome.Failure -> if (items.isEmpty()) state = Remote.Failed(r.error) else exhausted = true
                }
                loading = false
            }
        }
    }
    LaunchedEffect(stateKey) { loadMore() }
    val focus = rememberFocusListState(stateKey)
    val open: (Int) -> Unit = { i -> items.getOrNull(i)?.let(onOpen) }
    ListInputEffect(focus, onActivate = open)
    PageNearEnd(focus, items.size, exhausted, loadMore)
    when (val s = state) {
        Remote.Loading -> LoadingScreen()
        is Remote.Failed -> OnlineMessage(s.error)
        is Remote.Ready -> if (items.isEmpty()) {
            CenteredMessage(PodiumSymbol.Library, empty.first, empty.second)
        } else {
            FocusList(
                items = items.toList(),
                state = focus,
                key = key,
                contentPadding = LocalScreenInsets.current.listPadding(),
                onActivate = open,
                preview = preview,
                modifier = Modifier.fillMaxSize(),
            ) { item, _, focused -> row(item, focused) }
        }
    }
}

private sealed interface HistoryRow {
    data class Period(val label: String) : HistoryRow
    data class Song(val track: Track, val index: Int) : HistoryRow
    data object PlayedHere : HistoryRow
}

/**
 * ONLINE ▸ History. Signed in, it's the account's own history, as the service grouped it ("Today",
 * "Yesterday"…) — the service recorded those plays itself; Podium never writes to it. "Played on
 * Podium" is Podium's own record of what it played. Signed out, only Podium's own record exists.
 */
@Composable
fun OnlineAccountHistoryScreen(repository: OnlineRepository, actions: OnlineActions, navigate: (OnlinePlace) -> Unit) {
    val history = rememberRemote(Unit) { repository.accountHistory(0, HISTORY_LIMIT) }
    val likedIds by repository.likedIds.collectAsStateWithLifecycle()
    val overlayHost = overlay()
    val entries = (history as? Remote.Ready)?.value.orEmpty()
    val songs = remember(entries) { entries.map { it.track } }
    val rows = remember(entries) {
        buildList {
            add(HistoryRow.PlayedHere)
            var last: String? = null
            entries.forEachIndexed { i, e ->
                val period = e.period
                if (period != null && period != last) add(HistoryRow.Period(period))
                last = period
                add(HistoryRow.Song(e.track, i))
            }
        }
    }
    val focus = rememberFocusListState("online-account-history")
    val playable = remember(songs) { songs.filter { it.availability !is app.podium.core.model.Availability.Unavailable } }
    val activate: (Int) -> Unit = { i ->
        when (val row = rows.getOrNull(i)) {
            HistoryRow.PlayedHere -> navigate(OnlinePlace.Recent)
            is HistoryRow.Song -> playable.indexOfFirst { it.id == row.track.id }.takeIf { it >= 0 }?.let { actions.play(playable, it, "History") }
            else -> Unit
        }
    }
    val longPress: (Int) -> Unit = { i ->
        (rows.getOrNull(i) as? HistoryRow.Song)?.let { showTrackMenu(overlayHost, it.track, repository, actions, navigate) }
    }
    ListInputEffect(focus, onActivate = activate, onLongPress = longPress)
    when (history) {
        Remote.Loading -> {
            LoadingScreen()
            return
        }
        is Remote.Failed -> {
            OnlineMessage(history.error)
            return
        }
        is Remote.Ready -> Unit
    }
    FocusList(
        items = rows,
        state = focus,
        key = {
            when (it) {
                HistoryRow.PlayedHere -> "played-here"
                is HistoryRow.Period -> "period:${it.label}"
                is HistoryRow.Song -> "${it.track.id.value}#${it.index}"
            }
        },
        contentPadding = LocalScreenInsets.current.listPadding(),
        onActivate = activate,
        onLongPress = longPress,
        focusable = { it !is HistoryRow.Period },
        preview = { row ->
            when (row) {
                is HistoryRow.Song -> MenuPreview.Artwork(listOfNotNull(row.track.artwork?.uri))
                else -> MenuPreview.Carousel(covers(songs, 10))
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { row, _, focused ->
        when (row) {
            HistoryRow.PlayedHere -> MenuRow("Played on Podium", focused, leading = PodiumSymbol.Queue)
            is HistoryRow.Period -> SectionLabel(row.label)
            is HistoryRow.Song -> OnlineTrackRow(row.track, focused, liked = row.track.id in likedIds)
        }
    }
}

private const val HISTORY_LIMIT = 200
