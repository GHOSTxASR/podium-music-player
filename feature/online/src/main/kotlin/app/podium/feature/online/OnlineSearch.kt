package app.podium.feature.online

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.podium.core.common.Outcome
import app.podium.core.common.PodiumError
import app.podium.core.designsystem.component.FocusList
import app.podium.core.designsystem.component.ListInputEffect
import app.podium.core.designsystem.component.LocalRowPadding
import app.podium.core.designsystem.component.LocalScreenInsets
import app.podium.core.designsystem.component.MenuPreview
import app.podium.core.designsystem.component.MenuRow
import app.podium.core.designsystem.component.TrackRow
import app.podium.core.designsystem.symbol.PodiumSymbol
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.designsystem.theme.Spacing
import app.podium.core.designsystem.type.PodiumText
import app.podium.core.interaction.rememberFocusListState
import app.podium.core.model.Availability
import app.podium.core.model.PlaylistId
import app.podium.core.model.Track
import app.podium.sources.api.AlbumSummary
import app.podium.sources.api.ArtistSummary
import app.podium.sources.api.PlaylistSummary
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch

private sealed interface SearchRow {
    data object Field : SearchRow
    data class Section(val title: String) : SearchRow
    data class Song(val track: Track) : SearchRow
    data object MoreSongs : SearchRow
    data class Artist(val artist: ArtistSummary) : SearchRow
    data class Album(val album: AlbumSummary) : SearchRow
    data class Playlist(val playlist: PlaylistSummary) : SearchRow
    data class Message(val text: String) : SearchRow
}

/**
 * ONLINE ▸ Search (D-34): a deeper part of the same paper, not a separate search page. The field
 * sits at the top of the list; results arrive as you type — songs, artists, albums, playlists —
 * and "More songs" pages further without loading the whole catalogue. Turning the Wheel moves
 * through results (and tucks the keyboard away).
 */
@Composable
fun OnlineSearchScreen(repository: OnlineRepository, actions: OnlineActions, navigate: (OnlinePlace) -> Unit) {
    val likedIds by repository.likedIds.collectAsStateWithLifecycle()
    val overlayHost = overlay()
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<Remote<app.podium.sources.api.SearchResults>?>(null) }
    var songs by remember { mutableStateOf<List<Track>>(emptyList()) }
    var moreExhausted by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val fieldFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        snapshotFlow { query.trim() }.distinctUntilChanged().collectLatest { text ->
            if (text.length < MIN_QUERY) {
                results = null
                songs = emptyList()
                return@collectLatest
            }
            delay(DEBOUNCE_MS)
            results = Remote.Loading
            // Every online source answers in its own time; what's known so far shows straight away.
            // A newer search cancels this one (collectLatest), so a stale answer never lands.
            repository.search(text, 0, RESULTS).collect { r ->
                results = when (r) {
                    is Outcome.Success -> Remote.Ready(r.value).also {
                        songs = r.value.tracks
                        moreExhausted = r.value.tracks.size < RESULTS
                    }
                    is Outcome.Failure -> Remote.Failed(r.error)
                }
            }
        }
    }
    // A miniature copy (the previous-column tile) must never take focus or call the keyboard.
    val miniature = app.podium.core.designsystem.component.LocalMiniature.current
    if (!miniature) {
        // Only a fresh search calls the keyboard; coming back to results leaves the Wheel free.
        LaunchedEffect(Unit) { if (query.isEmpty()) fieldFocus.requestFocus() }
        KeyboardAwayOnLeave()
    }

    val ready = (results as? Remote.Ready)?.value
    val rows = remember(results, songs, moreExhausted) {
        buildList {
            add(SearchRow.Field)
            when (val r = results) {
                null -> Unit
                Remote.Loading -> Unit
                is Remote.Failed -> add(SearchRow.Message(failureText(r.error)))
                is Remote.Ready -> {
                    val v = r.value
                    if (songs.isEmpty() && v.artists.isEmpty() && v.albums.isEmpty() && v.playlists.isEmpty()) {
                        add(SearchRow.Message("Nothing found. Try other words."))
                    }
                    if (songs.isNotEmpty()) {
                        add(SearchRow.Section("Songs"))
                        songs.forEach { add(SearchRow.Song(it)) }
                        if (!moreExhausted) add(SearchRow.MoreSongs)
                    }
                    if (v.artists.isNotEmpty()) {
                        add(SearchRow.Section("Artists"))
                        v.artists.forEach { add(SearchRow.Artist(it)) }
                    }
                    if (v.albums.isNotEmpty()) {
                        add(SearchRow.Section("Albums"))
                        v.albums.forEach { add(SearchRow.Album(it)) }
                    }
                    if (v.playlists.isNotEmpty()) {
                        add(SearchRow.Section("Playlists"))
                        v.playlists.forEach { add(SearchRow.Playlist(it)) }
                    }
                }
            }
        }
    }
    val playable = songs.filter { it.availability !is Availability.Unavailable }
    val loadMoreSongs: () -> Unit = {
        scope.launch {
            when (val r = repository.search(query.trim(), songs.size, RESULTS).last()) {
                is Outcome.Success -> {
                    val fresh = r.value.tracks.filter { t -> songs.none { it.id == t.id } }
                    songs = songs + fresh
                    if (fresh.isEmpty() || r.value.tracks.size < RESULTS) moreExhausted = true
                }
                is Outcome.Failure -> moreExhausted = true
            }
        }
    }
    val focus = rememberFocusListState("online-search")
    val activate: (Int) -> Unit = { i ->
        when (val row = rows.getOrNull(i)) {
            SearchRow.Field -> fieldFocus.requestFocus()
            is SearchRow.Song -> playable.indexOfFirst { it.id == row.track.id }.takeIf { it >= 0 }?.let { actions.play(playable, it, "Search") }
            SearchRow.MoreSongs -> loadMoreSongs()
            is SearchRow.Artist -> navigate(OnlinePlace.Artist(row.artist.id, row.artist.name))
            is SearchRow.Album -> navigate(OnlinePlace.Collection(PlaylistId(row.album.id.value), row.album.title, isAlbum = true))
            is SearchRow.Playlist -> navigate(OnlinePlace.Collection(row.playlist.id, row.playlist.title, row.playlist.isAlbum))
            else -> Unit
        }
    }
    val longPress: (Int) -> Unit = { i ->
        (rows.getOrNull(i) as? SearchRow.Song)?.let { showTrackMenu(overlayHost, it.track, repository, actions, navigate) }
    }
    ListInputEffect(focus, onActivate = activate, onLongPress = longPress)
    LaunchedEffect(focus) {
        // The Wheel takes over from the keyboard.
        snapshotFlow { focus.focusMoves }.collect { if (it > 0) focusManager.clearFocus() }
    }
    FocusList(
        items = rows,
        state = focus,
        key = {
            when (it) {
                SearchRow.Field -> "field"
                is SearchRow.Section -> "section:${it.title}"
                is SearchRow.Song -> "song:${it.track.id.value}"
                SearchRow.MoreSongs -> "more"
                is SearchRow.Artist -> "artist:${it.artist.id.value}"
                is SearchRow.Album -> "album:${it.album.id.value}"
                is SearchRow.Playlist -> "playlist:${it.playlist.id.value}"
                is SearchRow.Message -> "message"
            }
        },
        contentPadding = LocalScreenInsets.current.listPadding(),
        onActivate = activate,
        onLongPress = longPress,
        focusable = { it !is SearchRow.Section && it !is SearchRow.Message },
        preview = { row ->
            when (row) {
                is SearchRow.Song -> MenuPreview.Artwork(listOfNotNull(row.track.artwork?.uri))
                is SearchRow.Artist -> row.artist.artwork?.uri?.let { MenuPreview.Artwork(listOf(it), round = true) } ?: MenuPreview.None
                is SearchRow.Album -> MenuPreview.Artwork(listOfNotNull(row.album.artwork?.uri))
                is SearchRow.Playlist -> MenuPreview.Artwork(listOfNotNull(row.playlist.artwork?.uri))
                else -> if (ready != null) MenuPreview.Carousel(covers(songs, 10)) else MenuPreview.Instrument
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { row, _, focused ->
        when (row) {
            SearchRow.Field -> SearchField(query, { query = it }, fieldFocus, onDone = { focusManager.clearFocus() })
            is SearchRow.Section -> SectionLabel(row.title)
            is SearchRow.Song -> OnlineTrackRow(row.track, focused, liked = row.track.id in likedIds)
            SearchRow.MoreSongs -> MenuRow("More songs", focused, leading = PodiumSymbol.More, showChevron = false)
            is SearchRow.Artist -> OnlineArtistRow(row.artist, focused)
            is SearchRow.Album -> TrackRow(row.album.title, row.album.artistDisplay, focused, row.album.artwork?.uri, trailing = row.album.year?.toString())
            is SearchRow.Playlist -> TrackRow(row.playlist.title, row.playlist.ownerName, focused, row.playlist.artwork?.uri, trailing = row.playlist.trackCount?.toString())
            is SearchRow.Message -> SectionLabel(row.text)
        }
    }
}

private fun failureText(error: PodiumError) = when (error) {
    PodiumError.Offline -> "You're offline. Music on this phone still plays."
    is PodiumError.RateLimited -> "Too many requests. Wait a moment and type again."
    is PodiumError.NotFound -> "Turn on an online source in Settings to search."
    else -> "Couldn't reach online music. Check your connection."
}

/** The search box: a hairline field on the paper, in the theme's own type. */
@Composable
internal fun SearchField(
    value: String,
    onChange: (String) -> Unit,
    focus: FocusRequester,
    onDone: () -> Unit,
    placeholder: String = "Songs, artists, albums",
    description: String = "Search online",
    search: Boolean = true,
) {
    val colors = PodiumTheme.colors
    val type = PodiumTheme.type
    val shape = RoundedCornerShape(if (colors.isIndustrial) 2.dp else 10.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = LocalRowPadding.current, vertical = Spacing.s)
            .border(1.dp, colors.labelTertiary, shape)
            .padding(horizontal = Spacing.m, vertical = Spacing.s),
    ) {
        if (value.isEmpty()) PodiumText(placeholder, type.row, colors.labelTertiary)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = type.row.copy(color = colors.labelPrimary),
            cursorBrush = SolidColor(colors.labelPrimary),
            keyboardOptions = KeyboardOptions(imeAction = if (search) ImeAction.Search else ImeAction.Done),
            keyboardActions = KeyboardActions(onSearch = { onDone() }, onDone = { onDone() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = description },
        )
    }
}

private const val MIN_QUERY = 2
private const val DEBOUNCE_MS = 350L
private const val RESULTS = 20
