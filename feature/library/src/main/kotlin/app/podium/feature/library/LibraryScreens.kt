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
import app.podium.core.designsystem.component.MenuRow
import app.podium.core.designsystem.component.MenuSpec
import app.podium.core.designsystem.component.MessageState
import app.podium.core.designsystem.component.TrackRow
import app.podium.core.designsystem.component.formatDuration
import app.podium.core.designsystem.symbol.PodiumSymbol
import app.podium.core.interaction.rememberFocusListState
import app.podium.core.model.Track
import app.podium.sources.api.CapabilityAction

private data class HomeItem(val label: String, val action: () -> Unit, val showChevron: Boolean = true)

/** The main menu (navigation-map.md §1). Only implemented destinations appear — nothing faked. */
@Composable
fun HomeScreen(
    nowPlayingActive: Boolean,
    onMusic: () -> Unit,
    onShuffleSongs: () -> Unit,
    onNowPlaying: () -> Unit,
    onSettings: () -> Unit,
) {
    val items = remember(nowPlayingActive) {
        buildList {
            add(HomeItem("Music", onMusic))
            add(HomeItem("Shuffle songs", onShuffleSongs, showChevron = false))
            if (nowPlayingActive) add(HomeItem("Now Playing", onNowPlaying))
            add(HomeItem("Settings", onSettings))
        }
    }
    val focus = rememberFocusListState("home")
    ListInputEffect(focus, onActivate = { items[it].action() })
    FocusList(
        items = items,
        state = focus,
        key = { it.label },
        contentPadding = LocalScreenInsets.current.listPadding(),
        onActivate = { items[it].action() },
        modifier = Modifier.fillMaxSize(),
    ) { item, _, focused ->
        MenuRow(item.label, focused, showChevron = item.showChevron)
    }
}

private sealed interface MusicItem {
    data class Action(val action: SourceAction) : MusicItem
    data class Songs(val count: Int?) : MusicItem
}

/** The Music menu. Sources that need the user (e.g. permission) offer their action here. */
@Composable
fun MusicScreen(
    repository: LibraryRepository,
    onSongs: () -> Unit,
    onSourceAction: (CapabilityAction) -> Unit,
) {
    val songs by repository.songs.collectAsStateWithLifecycle()
    val actions by repository.pendingActions.collectAsStateWithLifecycle()
    val count = (songs as? LibraryState.Ready)?.tracks?.size
    val items = buildList {
        actions.forEach { add(MusicItem.Action(it)) }
        add(MusicItem.Songs(count))
    }
    val focus = rememberFocusListState("music")
    val activate: (Int) -> Unit = { index ->
        when (val item = items[index]) {
            is MusicItem.Action -> onSourceAction(item.action.action)
            is MusicItem.Songs -> onSongs()
        }
    }
    ListInputEffect(focus, onActivate = activate)
    FocusList(
        items = items,
        state = focus,
        key = { if (it is MusicItem.Action) "action:${it.action.sourceName}" else "songs" },
        contentPadding = LocalScreenInsets.current.listPadding(),
        onActivate = activate,
        modifier = Modifier.fillMaxSize(),
    ) { item, _, focused ->
        when (item) {
            is MusicItem.Action -> MenuRow(item.action.note, focused, leading = PodiumSymbol.Lock, showChevron = false)
            is MusicItem.Songs -> MenuRow("Songs", focused, value = item.count?.toString())
        }
    }
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
