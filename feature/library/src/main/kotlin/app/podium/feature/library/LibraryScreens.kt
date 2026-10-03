package app.podium.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.podium.core.designsystem.component.FocusList
import app.podium.core.designsystem.component.ListInputEffect
import app.podium.core.designsystem.component.LocalOverlayHost
import app.podium.core.designsystem.component.LocalScreenInsets
import app.podium.core.designsystem.component.MenuAction
import app.podium.core.designsystem.component.MenuPreview
import app.podium.core.designsystem.component.MenuRow
import app.podium.core.designsystem.component.MenuSpec
import app.podium.core.designsystem.component.MessageState
import app.podium.core.designsystem.component.TrackRow
import app.podium.core.designsystem.component.formatDuration
import app.podium.core.designsystem.symbol.PodiumSymbol
import app.podium.core.interaction.rememberFocusListState
import app.podium.core.model.Track
import app.podium.player.api.FavoritesRepository
import app.podium.sources.api.CapabilityAction

/** A menu entry: its label, what its next column looks like, and what choosing it does. */
private data class MenuEntry(
    val label: String,
    val preview: MenuPreview,
    val action: () -> Unit,
    val leading: PodiumSymbol? = null,
    val chevron: Boolean = true,
)

@Composable
private fun PaperMenu(entries: List<MenuEntry>, stateKey: String) {
    val focus = rememberFocusListState(stateKey)
    ListInputEffect(focus, onActivate = { entries[it].action() })
    FocusList(
        items = entries,
        state = focus,
        key = { it.label },
        contentPadding = LocalScreenInsets.current.listPadding(),
        onActivate = { entries[it].action() },
        preview = { it.preview },
        modifier = Modifier.fillMaxSize(),
    ) { entry, _, focused ->
        MenuRow(entry.label, focused, leading = entry.leading, showChevron = entry.chevron)
    }
}

/**
 * The main menu (navigation-map.md §1, D-29), on the paper like every list: the focused item's
 * next column is previewed beyond the arc. Only implemented destinations appear — nothing faked.
 */
@Composable
fun HomeScreen(
    repository: LibraryRepository,
    nowPlayingArtwork: String?,
    nowPlayingActive: Boolean,
    onMusic: () -> Unit,
    onShuffleSongs: () -> Unit,
    onNowPlaying: () -> Unit,
    onSettings: () -> Unit,
) {
    val songs by repository.songs.collectAsStateWithLifecycle()
    val tracks = (songs as? LibraryState.Ready)?.tracks.orEmpty()
    val art = remember(tracks) { LibraryIndex.artwork(tracks) }
    val shuffled = remember(art) { art.shuffled() }
    val entries = buildList {
        add(MenuEntry("Music", MenuPreview.Artwork(art), onMusic))
        add(MenuEntry("Shuffle songs", MenuPreview.Carousel(shuffled), onShuffleSongs, chevron = false))
        if (nowPlayingActive) add(MenuEntry("Now Playing", MenuPreview.Artwork(listOfNotNull(nowPlayingArtwork)), onNowPlaying))
        add(MenuEntry("Settings", MenuPreview.Instrument, onSettings))
    }
    PaperMenu(entries, "home")
}

/** The Music menu. Sources that need the user (e.g. permission) offer their action first. */
@Composable
fun MusicScreen(
    repository: LibraryRepository,
    favorites: FavoritesRepository,
    onCoverFlow: () -> Unit,
    onAlbums: () -> Unit,
    onArtists: () -> Unit,
    onSongs: () -> Unit,
    onFavorites: () -> Unit,
    onSourceAction: (CapabilityAction) -> Unit,
) {
    val songs by repository.songs.collectAsStateWithLifecycle()
    val actions by repository.pendingActions.collectAsStateWithLifecycle()
    val favoriteIds by favorites.favorites.collectAsStateWithLifecycle()
    val tracks = (songs as? LibraryState.Ready)?.tracks.orEmpty()
    val albums by remember(repository) { repository.albums() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val artists by remember(repository) { repository.artists() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val albumArt = remember(albums) { albums.mapNotNull { it.artworkUri }.take(10) }
    val artistArt = remember(artists) { artists.mapNotNull { it.artworkUri }.take(8) }
    val songArt = remember(tracks) { LibraryIndex.artwork(tracks) }
    val favoriteArt = remember(tracks, favoriteIds) { tracks.filter { it.id in favoriteIds }.mapNotNull { it.artwork?.uri }.distinct().take(8) }
    val entries = buildList {
        actions.forEach { add(MenuEntry(it.note, MenuPreview.None, { onSourceAction(it.action) }, leading = PodiumSymbol.Lock, chevron = false)) }
        add(MenuEntry("Cover Flow", MenuPreview.Carousel(albumArt), onCoverFlow))
        add(MenuEntry("Albums", MenuPreview.Artwork(albumArt), onAlbums))
        add(MenuEntry("Artists", if (artistArt.isEmpty()) MenuPreview.None else MenuPreview.Artwork(artistArt, round = true), onArtists))
        add(MenuEntry("Songs", MenuPreview.Artwork(songArt), onSongs))
        add(MenuEntry("Favorites", if (favoriteArt.isEmpty()) MenuPreview.None else MenuPreview.Artwork(favoriteArt), onFavorites))
    }
    PaperMenu(entries, "music")
}

/**
 * Every song from every library source, A to Z. Center plays the list from the focused song;
 * long-press Center offers Play next / Add to Up Next.
 */
@Composable
fun SongsScreen(
    repository: LibraryRepository,
    onPlay: (tracks: List<Track>, index: Int) -> Unit,
    onPlayNext: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
) {
    val state by repository.songs.collectAsStateWithLifecycle()
    val insets = LocalScreenInsets.current
    val overlay = LocalOverlayHost.current
    val tracks = (state as? LibraryState.Ready)?.tracks.orEmpty()
    val focus = rememberFocusListState("songs")
    val showMenu: (Int) -> Unit = { index ->
        val track = tracks[index]
        overlay.show(
            MenuSpec(
                title = track.title,
                actions = listOf(
                    MenuAction("Play next") { onPlayNext(track) },
                    MenuAction("Add to Up Next") { onAddToQueue(track) },
                ),
            ),
        )
    }
    ListInputEffect(focus, onActivate = { onPlay(tracks, it) }, onLongPress = showMenu)

    when {
        state is LibraryState.Loading -> Unit
        tracks.isEmpty() -> Box(Modifier.fillMaxSize().padding(top = insets.top, bottom = insets.bottom), contentAlignment = Alignment.Center) {
            MessageState(PodiumSymbol.Note, "No songs yet", "Allow access to music on this phone from Music, or add a source.")
        }
        else -> FocusList(
            items = tracks,
            state = focus,
            key = { it.id.value },
            contentPadding = insets.listPadding(),
            onActivate = { onPlay(tracks, it) },
            onLongPress = showMenu,
            preview = { track -> MenuPreview.Artwork(listOfNotNull(track.artwork?.uri)) },
            modifier = Modifier.fillMaxSize(),
        ) { track, _, focused ->
            TrackRow(
                title = track.title,
                subtitle = track.artistDisplay,
                focused = focused,
                artworkUri = track.artwork?.uri,
                trailing = formatDuration(track.durationMs),
            )
        }
    }
}
