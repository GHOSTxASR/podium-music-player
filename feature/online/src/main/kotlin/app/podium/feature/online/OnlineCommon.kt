package app.podium.feature.online

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.podium.core.common.Outcome
import app.podium.core.common.PodiumError
import app.podium.core.designsystem.component.FocusList
import app.podium.core.designsystem.component.ListInputEffect
import app.podium.core.designsystem.component.LocalOverlayHost
import app.podium.core.designsystem.component.LocalScreenInsets
import app.podium.core.designsystem.component.MenuAction
import app.podium.core.designsystem.component.MenuPreview
import app.podium.core.designsystem.component.MenuRow
import app.podium.core.designsystem.component.MenuSpec
import app.podium.core.designsystem.component.MessageState
import app.podium.core.designsystem.component.OverlayHost
import app.podium.core.designsystem.component.TrackRow
import app.podium.core.designsystem.component.formatDuration
import app.podium.core.designsystem.symbol.PodiumSymbol
import app.podium.core.interaction.FocusListState
import app.podium.core.interaction.rememberFocusListState
import app.podium.core.model.Availability
import app.podium.core.model.Track
import app.podium.sources.api.ArtistSummary
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/** A call to the online source, as a screen shows it. */
sealed interface Remote<out T> {
    data object Loading : Remote<Nothing>
    data class Ready<T>(val value: T) : Remote<T>
    data class Failed(val error: PodiumError) : Remote<Nothing>
}

/** Run [load] once per [key]; the screen shows nothing while it runs (the disk light flickers). */
@Composable
fun <T> rememberRemote(vararg key: Any?, load: suspend () -> Outcome<T>): Remote<T> {
    var state by remember(*key) { mutableStateOf<Remote<T>>(Remote.Loading) }
    LaunchedEffect(*key) {
        state = when (val r = load()) {
            is Outcome.Success -> Remote.Ready(r.value)
            is Outcome.Failure -> Remote.Failed(r.error)
        }
    }
    return state
}

/**
 * Why online content isn't here, and what to do next. Local music is never affected. Never names a
 * source: the listener asked for music, not for a provider (D-35).
 */
@Composable
fun OnlineMessage(error: PodiumError?) {
    val (title, message) = when (error) {
        PodiumError.Offline -> "You're offline" to "Online music needs a connection. Music on this phone still plays."
        is PodiumError.RateLimited -> "Too many requests" to "Wait a moment, then go back and try again."
        is PodiumError.NotFound -> "This isn't available" to "It may have been removed. Go back and pick something else."
        is PodiumError.PolicyDisabled -> "This isn't available right now" to "Its online source is turned off. Turn it on in Settings, Online sources."
        null -> "No online source" to "Turn on an online source in Settings. Music on this phone still plays."
        else -> "Couldn't reach online music" to "Check your connection, then go back and try again."
    }
    CenteredMessage(if (error == PodiumError.Offline) PodiumSymbol.Offline else PodiumSymbol.Error, title, message)
}

@Composable
fun CenteredMessage(symbol: PodiumSymbol, title: String, message: String) {
    val insets = LocalScreenInsets.current
    Box(Modifier.fillMaxSize().padding(top = insets.top, bottom = insets.bottom), contentAlignment = Alignment.Center) {
        MessageState(symbol, title, message)
    }
}

/** A menu on the paper: labels, an artwork preview each, Center opens. */
data class OnlineEntry(
    val label: String,
    val preview: MenuPreview,
    val value: String? = null,
    val leading: PodiumSymbol? = null,
    val chevron: Boolean = true,
    val action: () -> Unit,
)

@Composable
fun OnlinePaperMenu(entries: List<OnlineEntry>, stateKey: String, onLongPress: (Int) -> Unit = {}) {
    val focus = rememberFocusListState(stateKey)
    ListInputEffect(focus, onActivate = { entries.getOrNull(it)?.action?.invoke() }, onLongPress = onLongPress)
    FocusList(
        items = entries,
        state = focus,
        key = { it.label },
        contentPadding = LocalScreenInsets.current.listPadding(),
        onActivate = { entries.getOrNull(it)?.action?.invoke() },
        onLongPress = onLongPress,
        preview = { it.preview },
        modifier = Modifier.fillMaxSize(),
    ) { entry, _, focused ->
        MenuRow(entry.label, focused, value = entry.value, leading = entry.leading, showChevron = entry.chevron)
    }
}

/** An online song row: cover, title over artist, length; unplayable songs say why. */
@Composable
fun OnlineTrackRow(track: Track, focused: Boolean, liked: Boolean = false) {
    val unavailable = track.availability as? Availability.Unavailable
    TrackRow(
        title = track.title,
        subtitle = unavailable?.reason ?: track.artistDisplay,
        focused = focused,
        artworkUri = track.artwork?.uri,
        trailing = formatDuration(track.durationMs),
        note = if (liked) "Liked" else null,
    )
}

/** A few covers from some songs, for previews. */
fun covers(tracks: List<Track>, limit: Int = 8): List<String> = tracks.mapNotNull { it.artwork?.uri }.distinct().take(limit)

/** Load the next page when the focus gets near the end of what's loaded. */
@Composable
fun PageNearEnd(focus: FocusListState, loaded: Int, exhausted: Boolean, loadMore: () -> Unit) {
    LaunchedEffect(focus, loaded, exhausted) {
        if (exhausted) return@LaunchedEffect
        snapshotFlow { focus.focusedIndex }
            .filter { loaded > 0 && it >= loaded - PREFETCH }
            .distinctUntilChanged()
            .collect { loadMore() }
    }
}

private const val PREFETCH = 6

/**
 * The long-press menu for an online song (D-34): only what the song's source and ONLINE support —
 * Play next, Add to Up Next, Like, Add to playlist, Start radio, the artist, Share.
 */
fun showTrackMenu(
    overlay: OverlayHost,
    track: Track,
    repository: OnlineRepository,
    actions: OnlineActions,
    navigate: (OnlinePlace) -> Unit,
) {
    val canRecommend = repository.canStartRadio(track)
    val liked = track.id in repository.likedIds.value
    val playable = track.availability !is Availability.Unavailable
    val artist = track.artists.firstOrNull()
    overlay.show(
        MenuSpec(
            track.title,
            buildList {
                if (playable) {
                    add(MenuAction("Play next") { actions.playNext(track) })
                    add(MenuAction("Add to Up Next") { actions.addToQueue(track) })
                }
                add(MenuAction(if (liked) "Remove from liked songs" else "Like") { repository.setLiked(track, !liked) })
                add(MenuAction("Add to playlist") { showAddToPlaylist(overlay, listOf(track), repository, navigate) })
                if (canRecommend && playable) add(MenuAction("Start radio") { actions.startRadio(track) })
                if (artist?.id != null) add(MenuAction("Go to ${artist.name}") { navigate(OnlinePlace.Artist(artist.id!!, artist.name)) })
                if (track.source.providerUri != null) add(MenuAction("Share") { actions.share(track) })
            },
        ),
    )
}

/** Choose one of the online playlists, or start a new one with these songs. */
fun showAddToPlaylist(overlay: OverlayHost, tracks: List<Track>, repository: OnlineRepository, navigate: (OnlinePlace) -> Unit) {
    val playlists = repository.cachedPlaylists
    overlay.show(
        MenuSpec(
            "Add to playlist",
            listOf(MenuAction("New playlist") { PendingPlaylist.tracks = tracks; navigate(OnlinePlace.NamePlaylist()) }) +
                playlists.map { p -> MenuAction(p.name) { repository.addToPlaylist(p.id, tracks) } },
        ),
    )
}

/** Songs waiting to go into a playlist that's being named (transient: a step of one flow). */
object PendingPlaylist {
    var tracks: List<Track> = emptyList()
}

/** The latest playlists, for menus that can't wait on a flow. Kept current by the repository. */
val OnlineRepository.cachedPlaylists: List<OnlinePlaylist> get() = PlaylistCache.latest

internal object PlaylistCache {
    @Volatile var latest: List<OnlinePlaylist> = emptyList()
}

/** Keep [PlaylistCache] current while the ONLINE section is on screen. */
@Composable
fun TrackPlaylists(repository: OnlineRepository) {
    LaunchedEffect(repository) { repository.playlists.collect { PlaylistCache.latest = it } }
}

/** An artist row: picture (round), name, how many songs. */
@Composable
fun OnlineArtistRow(artist: ArtistSummary, focused: Boolean) {
    TrackRow(
        title = artist.name,
        subtitle = artist.trackCount?.let { if (it == 1) "1 song" else "$it songs" } ?: "Artist",
        focused = focused,
        artworkUri = artist.artwork?.uri,
        roundArtwork = true,
    )
}

/** Read the overlay host the screens share. */
@Composable
fun overlay(): OverlayHost = LocalOverlayHost.current

/** Leaving a screen with a text field puts the keyboard away, so it never covers the next screen's Wheel. */
@Composable
fun KeyboardAwayOnLeave() {
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
    }
}

