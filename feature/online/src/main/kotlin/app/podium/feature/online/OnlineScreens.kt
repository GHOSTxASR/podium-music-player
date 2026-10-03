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
import app.podium.core.designsystem.component.LocalScreenInsets
import app.podium.core.designsystem.component.MenuPreview
import app.podium.core.designsystem.component.MenuRow
import app.podium.core.designsystem.component.TrackRow
import app.podium.core.designsystem.symbol.PodiumSymbol
import app.podium.core.interaction.rememberFocusListState
import app.podium.core.model.Track
import app.podium.sources.api.PlaylistSummary
import app.podium.sources.api.Shelf
import kotlinx.coroutines.launch

/**
 * The ONLINE section (D-34): a separate world on the same paper. Its menu shows only what the
 * connected online source supports — no dead rows.
 */
@Composable
fun OnlineScreen(place: OnlinePlace, repository: OnlineRepository, actions: OnlineActions, navigate: (OnlinePlace) -> Unit, back: () -> Unit) {
    TrackPlaylists(repository)
    when (place) {
        OnlinePlace.Menu -> OnlineMenuScreen(repository, navigate)
        OnlinePlace.Home -> OnlineHomeScreen(repository, navigate)
        OnlinePlace.Explore -> OnlineExploreScreen(repository, navigate)
        OnlinePlace.Search -> OnlineSearchScreen(repository, actions, navigate)
        OnlinePlace.Liked -> OnlineLikedScreen(repository, actions, navigate)
        OnlinePlace.Playlists -> OnlinePlaylistsScreen(repository, navigate)
        OnlinePlace.Radio -> OnlineRadioScreen(repository, actions)
        OnlinePlace.History, OnlinePlace.Recent -> OnlineHistoryScreen(repository, actions, navigate)
        is OnlinePlace.Shelf -> OnlineShelfScreen(place, repository, actions, navigate)
        is OnlinePlace.Genre -> OnlineGenreScreen(place.name, repository, actions, navigate)
        is OnlinePlace.Artist -> OnlineArtistScreen(place, repository, actions, navigate)
        is OnlinePlace.Collection -> OnlineCollectionScreen(place, repository, actions, navigate)
        is OnlinePlace.MyPlaylist -> OnlineMyPlaylistScreen(place, repository, actions, navigate)
        is OnlinePlace.NamePlaylist -> OnlineNamePlaylistScreen(place, repository, navigate, back)
    }
}

@Composable
fun OnlineMenuScreen(repository: OnlineRepository, navigate: (OnlinePlace) -> Unit) {
    val source by repository.source.collectAsStateWithLifecycle()
    val current = source
    if (current == null) {
        OnlineMessage(null, null)
        return
    }
    val shelves = rememberRemote(current.name) { if (current.canBrowse) repository.shelves() else Outcome.Success(emptyList()) }
    val liked by repository.likedTracks.collectAsStateWithLifecycle(initialValue = emptyList())
    val playlists by repository.playlists.collectAsStateWithLifecycle(initialValue = emptyList())
    val recent by repository.recentlyPlayed.collectAsStateWithLifecycle(initialValue = emptyList())
    val trending = (shelves as? Remote.Ready)?.value.orEmpty()
    val trendingArt = trending.flatMap { shelfCovers(it) }.distinct().take(10)
    val entries = buildList {
        if (current.canBrowse) add(OnlineEntry("Home", MenuPreview.Carousel(trendingArt)) { navigate(OnlinePlace.Home) })
        if (current.canBrowse) add(OnlineEntry("Explore", MenuPreview.Artwork(trending.drop(1).flatMap { shelfCovers(it) }.distinct().take(8))) { navigate(OnlinePlace.Explore) })
        if (current.canSearch) add(OnlineEntry("Search", MenuPreview.Instrument, leading = PodiumSymbol.Search) { navigate(OnlinePlace.Search) })
        add(OnlineEntry("Liked songs", if (liked.isEmpty()) MenuPreview.None else MenuPreview.Artwork(covers(liked)), value = liked.size.takeIf { it > 0 }?.toString()) { navigate(OnlinePlace.Liked) })
        add(OnlineEntry("Playlists", MenuPreview.None, value = playlists.size.takeIf { it > 0 }?.toString()) { navigate(OnlinePlace.Playlists) })
        if (current.canRecommend) add(OnlineEntry("Radio", MenuPreview.Carousel(covers(recent).ifEmpty { trendingArt.reversed() })) { navigate(OnlinePlace.Radio) })
        add(OnlineEntry("History", if (recent.isEmpty()) MenuPreview.None else MenuPreview.Artwork(covers(recent))) { navigate(OnlinePlace.History) })
    }
    OnlinePaperMenu(entries, "online-menu")
}

private fun shelfCovers(shelf: Shelf): List<String> =
    covers(shelf.tracks) + shelf.playlists.mapNotNull { it.artwork?.uri }

/**
 * ONLINE ▸ Home: the source's own shelves (trending, underground, popular playlists…) and the
 * listener's (recently played, liked, playlists). Each previews its artwork beyond the arc.
 */
@Composable
fun OnlineHomeScreen(repository: OnlineRepository, navigate: (OnlinePlace) -> Unit) {
    val source by repository.source.collectAsStateWithLifecycle()
    val shelves = rememberRemote(Unit) { repository.shelves() }
    val liked by repository.likedTracks.collectAsStateWithLifecycle(initialValue = emptyList())
    val recent by repository.recentlyPlayed.collectAsStateWithLifecycle(initialValue = emptyList())
    when (shelves) {
        Remote.Loading -> Unit
        is Remote.Failed -> OnlineMessage(shelves.error, source?.name)
        is Remote.Ready -> {
            val entries = buildList {
                shelves.value.forEach { shelf ->
                    add(
                        OnlineEntry(shelf.title, MenuPreview.Carousel(shelfCovers(shelf).take(12))) {
                            navigate(OnlinePlace.Shelf(shelf.id, shelf.title))
                        },
                    )
                }
                if (recent.isNotEmpty()) add(OnlineEntry("Recently played", MenuPreview.Artwork(covers(recent))) { navigate(OnlinePlace.Recent) })
                if (liked.isNotEmpty()) add(OnlineEntry("Your liked songs", MenuPreview.Artwork(covers(liked))) { navigate(OnlinePlace.Liked) })
                add(OnlineEntry("Your playlists", MenuPreview.None) { navigate(OnlinePlace.Playlists) })
            }
            OnlinePaperMenu(entries, "online-home")
        }
    }
}

/** One shelf, paged as the focus nears the end. Songs play from the one chosen; playlists open. */
@Composable
fun OnlineShelfScreen(place: OnlinePlace.Shelf, repository: OnlineRepository, actions: OnlineActions, navigate: (OnlinePlace) -> Unit) {
    val source by repository.source.collectAsStateWithLifecycle()
    val tracks = remember(place.id) { mutableStateListOf<Track>() }
    val playlists = remember(place.id) { mutableStateListOf<PlaylistSummary>() }
    var state by remember(place.id) { mutableStateOf<Remote<Unit>>(Remote.Loading) }
    var exhausted by remember(place.id) { mutableStateOf(false) }
    var loading by remember(place.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val loadMore: () -> Unit = {
        if (!loading && !exhausted) {
            loading = true
            scope.launch {
                when (val r = repository.shelf(place.id, tracks.size + playlists.size, PAGE)) {
                    is Outcome.Success -> {
                        val newTracks = r.value.tracks.filter { t -> tracks.none { it.id == t.id } }
                        val newPlaylists = r.value.playlists.filter { p -> playlists.none { it.id == p.id } }
                        tracks += newTracks
                        playlists += newPlaylists
                        if (newTracks.isEmpty() && newPlaylists.isEmpty()) exhausted = true
                        state = Remote.Ready(Unit)
                    }
                    is Outcome.Failure -> if (tracks.isEmpty() && playlists.isEmpty()) state = Remote.Failed(r.error) else exhausted = true
                }
                loading = false
            }
        }
    }
    LaunchedEffect(place.id) { loadMore() }
    when (val s = state) {
        Remote.Loading -> Unit
        is Remote.Failed -> OnlineMessage(s.error, source?.name)
        is Remote.Ready -> if (playlists.isNotEmpty()) {
            PlaylistList(playlists, "shelf:${place.id}", navigate, onNearEnd = loadMore, exhausted = exhausted)
        } else {
            OnlineTrackList(tracks, "shelf:${place.id}", place.title, repository, actions, navigate, onNearEnd = loadMore, exhausted = exhausted)
        }
    }
}

/** ONLINE ▸ Explore: the source's genres, most popular first. */
@Composable
fun OnlineExploreScreen(repository: OnlineRepository, navigate: (OnlinePlace) -> Unit) {
    val source by repository.source.collectAsStateWithLifecycle()
    val genres = rememberRemote(Unit) { repository.genres() }
    when (genres) {
        Remote.Loading -> Unit
        is Remote.Failed -> OnlineMessage(genres.error, source?.name)
        is Remote.Ready -> OnlinePaperMenu(genres.value.map { g -> OnlineEntry(g, MenuPreview.None) { navigate(OnlinePlace.Genre(g)) } }, "online-explore")
    }
}

/** A genre: its radio first, then its music as the source ranks it. */
@Composable
fun OnlineGenreScreen(genre: String, repository: OnlineRepository, actions: OnlineActions, navigate: (OnlinePlace) -> Unit) {
    val source by repository.source.collectAsStateWithLifecycle()
    val tracks = remember(genre) { mutableStateListOf<Track>() }
    var state by remember(genre) { mutableStateOf<Remote<Unit>>(Remote.Loading) }
    var exhausted by remember(genre) { mutableStateOf(false) }
    var loading by remember(genre) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val loadMore: () -> Unit = {
        if (!loading && !exhausted) {
            loading = true
            scope.launch {
                when (val r = repository.genre(genre, tracks.size, PAGE)) {
                    is Outcome.Success -> {
                        val fresh = r.value.filter { t -> tracks.none { it.id == t.id } }
                        tracks += fresh
                        if (fresh.isEmpty()) exhausted = true
                        state = Remote.Ready(Unit)
                    }
                    is Outcome.Failure -> if (tracks.isEmpty()) state = Remote.Failed(r.error) else exhausted = true
                }
                loading = false
            }
        }
    }
    LaunchedEffect(genre) { loadMore() }
    when (val s = state) {
        Remote.Loading -> Unit
        is Remote.Failed -> OnlineMessage(s.error, source?.name)
        is Remote.Ready -> OnlineTrackList(
            tracks, "genre:$genre", genre, repository, actions, navigate,
            lead = if (source?.canRecommend == true) listOf(Lead("$genre radio", PodiumSymbol.PlaylistPlay) { actions.startGenreRadio(genre) }) else emptyList(),
            onNearEnd = loadMore, exhausted = exhausted,
        )
    }
}

@Composable
fun OnlineLikedScreen(repository: OnlineRepository, actions: OnlineActions, navigate: (OnlinePlace) -> Unit) {
    val liked by repository.likedTracks.collectAsStateWithLifecycle(initialValue = null)
    val songs = liked ?: return
    if (songs.isEmpty()) {
        CenteredMessage(PodiumSymbol.Favorite, "No liked songs yet", "Hold Center on an online song and choose Like.")
        return
    }
    OnlineTrackList(
        songs, "online-liked", "Liked songs", repository, actions, navigate,
        lead = listOf(Lead("Shuffle", PodiumSymbol.Shuffle) { actions.shuffle(songs, "Liked songs") }),
    )
}

@Composable
fun OnlineHistoryScreen(repository: OnlineRepository, actions: OnlineActions, navigate: (OnlinePlace) -> Unit) {
    val recent by repository.recentlyPlayed.collectAsStateWithLifecycle(initialValue = null)
    val songs = recent ?: return
    if (songs.isEmpty()) {
        CenteredMessage(PodiumSymbol.Queue, "Nothing played yet", "Online songs you listen to appear here, newest first.")
        return
    }
    val overlayHost = overlay()
    OnlineTrackList(
        songs, "online-history", "History", repository, actions, navigate,
        lead = listOf(
            Lead("Clear history", null) {
                overlayHost.show(
                    app.podium.core.designsystem.component.MenuSpec(
                        "Clear online history?",
                        listOf(
                            app.podium.core.designsystem.component.MenuAction("Clear history") { repository.clearHistory() },
                            app.podium.core.designsystem.component.MenuAction("Keep it") {},
                        ),
                    ),
                )
            },
        ),
    )
}

/** ONLINE ▸ Radio: from what's playing, from songs you played, or from a genre. */
@Composable
fun OnlineRadioScreen(repository: OnlineRepository, actions: OnlineActions) {
    val nowPlaying by actions.nowPlaying.collectAsStateWithLifecycle()
    val recent by repository.recentlyPlayed.collectAsStateWithLifecycle(initialValue = emptyList())
    val genres = rememberRemote(Unit) { repository.genres() }
    val entries = buildList {
        nowPlaying?.let { t -> add(OnlineEntry("From ${t.title}", MenuPreview.Artwork(listOfNotNull(t.artwork?.uri)), chevron = false) { actions.startRadio(t) }) }
        recent.filter { it.id != nowPlaying?.id }.take(6).forEach { t ->
            add(OnlineEntry("From ${t.title}", MenuPreview.Artwork(listOfNotNull(t.artwork?.uri)), chevron = false) { actions.startRadio(t) })
        }
        (genres as? Remote.Ready)?.value?.take(10)?.forEach { g ->
            add(OnlineEntry("$g radio", MenuPreview.None, chevron = false) { actions.startGenreRadio(g) })
        }
    }
    if (entries.isEmpty()) {
        if (genres is Remote.Failed) OnlineMessage(genres.error, repository.source.value?.name)
        return
    }
    OnlinePaperMenu(entries, "online-radio")
}

/** A row above a song list that isn't a song (Shuffle, Radio…). */
data class Lead(val label: String, val symbol: PodiumSymbol?, val action: () -> Unit)

private sealed interface Row {
    data class Action(val lead: Lead) : Row
    data class Song(val track: Track, val index: Int) : Row
}

/**
 * The shared online song list: lead rows, then songs. Center plays from the chosen song (the list
 * becomes the context, labelled [label]); long-press Center opens the song's menu.
 */
@Composable
fun OnlineTrackList(
    tracks: List<Track>,
    stateKey: String,
    label: String,
    repository: OnlineRepository,
    actions: OnlineActions,
    navigate: (OnlinePlace) -> Unit,
    lead: List<Lead> = emptyList(),
    onNearEnd: () -> Unit = {},
    exhausted: Boolean = true,
) {
    val source by repository.source.collectAsStateWithLifecycle()
    val likedIds by repository.likedIds.collectAsStateWithLifecycle()
    val overlayHost = overlay()
    val rows = remember(tracks.toList(), lead) { lead.map { Row.Action(it) } + tracks.mapIndexed { i, t -> Row.Song(t, i) } }
    val focus = rememberFocusListState(stateKey)
    val playable = remember(tracks.toList()) { tracks.filter { it.availability !is app.podium.core.model.Availability.Unavailable } }
    val activate: (Int) -> Unit = { i ->
        when (val row = rows.getOrNull(i)) {
            is Row.Action -> row.lead.action()
            is Row.Song -> playable.indexOfFirst { it.id == row.track.id }.takeIf { it >= 0 }?.let { actions.play(playable, it, label) }
            null -> Unit
        }
    }
    val longPress: (Int) -> Unit = { i ->
        (rows.getOrNull(i) as? Row.Song)?.let { showTrackMenu(overlayHost, it.track, repository, actions, navigate, source?.canRecommend == true) }
    }
    ListInputEffect(focus, onActivate = activate, onLongPress = longPress)
    PageNearEnd(focus, rows.size, exhausted, onNearEnd)
    FocusList(
        items = rows,
        state = focus,
        key = {
            when (it) {
                is Row.Action -> "lead:${it.lead.label}"
                is Row.Song -> "${it.track.id.value}#${it.index}"
            }
        },
        contentPadding = LocalScreenInsets.current.listPadding(),
        onActivate = activate,
        onLongPress = longPress,
        preview = { row ->
            when (row) {
                is Row.Action -> MenuPreview.Carousel(covers(tracks, 10))
                is Row.Song -> MenuPreview.Artwork(listOfNotNull(row.track.artwork?.uri))
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { row, _, focused ->
        when (row) {
            is Row.Action -> MenuRow(row.lead.label, focused, leading = row.lead.symbol, showChevron = false)
            is Row.Song -> OnlineTrackRow(row.track, focused, liked = row.track.id in likedIds)
        }
    }
}

/** A list of playlists or albums from the source; Center opens one. */
@Composable
fun PlaylistList(playlists: List<PlaylistSummary>, stateKey: String, navigate: (OnlinePlace) -> Unit, onNearEnd: () -> Unit = {}, exhausted: Boolean = true) {
    val focus = rememberFocusListState(stateKey)
    val open: (Int) -> Unit = { i -> playlists.getOrNull(i)?.let { navigate(OnlinePlace.Collection(it.id, it.title, it.isAlbum)) } }
    ListInputEffect(focus, onActivate = open)
    PageNearEnd(focus, playlists.size, exhausted, onNearEnd)
    FocusList(
        items = playlists,
        state = focus,
        key = { it.id.value },
        contentPadding = LocalScreenInsets.current.listPadding(),
        onActivate = open,
        preview = { MenuPreview.Artwork(listOfNotNull(it.artwork?.uri)) },
        modifier = Modifier.fillMaxSize(),
    ) { p, _, focused ->
        TrackRow(p.title, p.ownerName, focused, p.artwork?.uri, trailing = p.trackCount?.toString())
    }
}

internal const val PAGE = 25
