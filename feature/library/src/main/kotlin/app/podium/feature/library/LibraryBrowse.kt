package app.podium.feature.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.podium.core.designsystem.artwork.ArtworkImage
import app.podium.core.designsystem.component.DetailHeader
import app.podium.core.designsystem.component.FocusList
import app.podium.core.designsystem.component.ListInputEffect
import app.podium.core.designsystem.component.LocalMiniatureFocusKey
import app.podium.core.designsystem.component.LocalOverlayHost
import app.podium.core.designsystem.component.LocalRowPadding
import app.podium.core.designsystem.component.LocalScreenInsets
import app.podium.core.designsystem.component.MenuAction
import app.podium.core.designsystem.component.MenuPreview
import app.podium.core.designsystem.component.MenuRow
import app.podium.core.designsystem.component.MenuSpec
import app.podium.core.designsystem.component.MessageState
import app.podium.core.designsystem.component.TrackRow
import app.podium.core.designsystem.component.formatDuration
import app.podium.core.designsystem.component.illuminated
import app.podium.core.designsystem.symbol.PodiumSymbol
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.designsystem.theme.Spacing
import app.podium.core.designsystem.type.PodiumText
import app.podium.core.interaction.rememberFocusListState
import app.podium.core.model.AlbumId
import app.podium.core.model.ArtistId
import app.podium.core.model.Track
import app.podium.player.api.FavoritesRepository
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Play [tracks] from [index], as a context labelled [label] ("Continuing from …"). */
typealias PlayTracks = (tracks: List<Track>, index: Int, label: String) -> Unit

@Composable
private fun rememberTracks(repository: LibraryRepository): Pair<List<Track>, Boolean> {
    val state by repository.songs.collectAsStateWithLifecycle()
    return (state as? LibraryState.Ready)?.tracks.orEmpty() to (state is LibraryState.Loading)
}

/** A value from the library, once it has loaded (null while loading). */
private class Loaded<T>(val value: T)

@Composable
private fun <T> rememberLoaded(vararg keys: Any?, flow: () -> Flow<T>): Loaded<T>? {
    val loaded = remember(*keys) { flow().map { Loaded(it) } }
    return loaded.collectAsStateWithLifecycle(initialValue = null).value
}

@Composable
private fun EmptyState(symbol: PodiumSymbol, title: String, message: String) {
    val insets = LocalScreenInsets.current
    Box(Modifier.fillMaxSize().padding(top = insets.top, bottom = insets.bottom), contentAlignment = Alignment.Center) {
        MessageState(symbol, title, message)
    }
}

private const val NoMusic = "Allow access to music on this phone from Music, or add a source."

/** Long-press menu for a song, shared by every song list. */
@Composable
private fun songMenu(tracks: List<Track>, onPlayNext: (Track) -> Unit, onAddToQueue: (Track) -> Unit): (Track) -> Unit {
    val overlay = LocalOverlayHost.current
    return { track ->
        overlay.show(MenuSpec(track.title, listOf(MenuAction("Play next") { onPlayNext(track) }, MenuAction("Add to Up Next") { onAddToQueue(track) })))
    }
}

/** Albums, A to Z. */
@Composable
fun AlbumsScreen(repository: LibraryRepository, onOpen: (AlbumId) -> Unit) {
    val loaded = rememberLoaded(repository) { repository.albums() }
    val albums = loaded?.value.orEmpty()
    val focus = rememberFocusListState("albums")
    ListInputEffect(focus, onActivate = { albums.getOrNull(it)?.let { a -> onOpen(a.id) } })
    when {
        loaded == null -> Unit
        albums.isEmpty() -> EmptyState(PodiumSymbol.Album, "No albums yet", NoMusic)
        else -> FocusList(
            items = albums,
            state = focus,
            key = { it.id.value },
            contentPadding = LocalScreenInsets.current.listPadding(),
            onActivate = { onOpen(albums[it].id) },
            preview = { MenuPreview.Artwork(listOfNotNull(it.artworkUri)) },
            modifier = Modifier.fillMaxSize(),
        ) { album, _, focused ->
            TrackRow(album.title, album.artist, focused, album.artworkUri, trailing = album.year?.toString())
        }
    }
}

private sealed interface AlbumRow {
    data object Header : AlbumRow
    data class Song(val track: Track, val index: Int) : AlbumRow
}

/** One album: its cover and details, then its songs in disc and track order. */
@Composable
fun AlbumScreen(
    repository: LibraryRepository,
    albumId: AlbumId,
    onPlay: PlayTracks,
    onPlayNext: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
) {
    val loaded = rememberLoaded(repository, albumId) { repository.album(albumId) }
    val detail = loaded?.value
    val album = detail?.album
    val songs = detail?.tracks.orEmpty()
    val focus = rememberFocusListState("album:${albumId.value}")
    val rows = remember(songs) { listOf(AlbumRow.Header) + songs.mapIndexed { i, t -> AlbumRow.Song(t, i) } }
    val menu = songMenu(songs, onPlayNext, onAddToQueue)
    val activate: (Int) -> Unit = { i -> (rows.getOrNull(i) as? AlbumRow.Song)?.let { album?.let { a -> onPlay(songs, it.index, a.title) } } }
    ListInputEffect(focus, onActivate = activate, onLongPress = { i -> (rows.getOrNull(i) as? AlbumRow.Song)?.let { menu(it.track) } })
    when {
        loaded == null -> Unit
        album == null -> EmptyState(PodiumSymbol.Album, "This album isn't here anymore", "Its songs may have been removed. Go back to Albums.")
        else -> FocusList(
            items = rows,
            state = focus,
            key = { if (it is AlbumRow.Song) it.track.id.value else "header" },
            contentPadding = LocalScreenInsets.current.listPadding(),
            onActivate = activate,
            onLongPress = { i -> (rows[i] as? AlbumRow.Song)?.let { menu(it.track) } },
            focusable = { it is AlbumRow.Song },
            preview = { MenuPreview.Artwork(listOfNotNull(album.artworkUri)) },
            modifier = Modifier.fillMaxSize(),
        ) { row, _, focused ->
            when (row) {
                AlbumRow.Header -> DetailHeader(album.artworkUri, album.title, album.artist, listOfNotNull(songCount(album.trackCount), album.year?.toString()))
                is AlbumRow.Song -> TrackRow(
                    title = row.track.title,
                    subtitle = row.track.artistDisplay,
                    focused = focused,
                    artworkUri = null,
                    trailing = formatDuration(row.track.durationMs),
                    number = row.track.trackNumber ?: (row.index + 1),
                )
            }
        }
    }
}

private fun songCount(n: Int) = if (n == 1) "1 song" else "$n songs"

private fun albumCount(n: Int) = if (n == 1) "1 album" else "$n albums"

/** Artists, A to Z. */
@Composable
fun ArtistsScreen(repository: LibraryRepository, onOpen: (ArtistId) -> Unit) {
    val loaded = rememberLoaded(repository) { repository.artists() }
    val artists = loaded?.value.orEmpty()
    val focus = rememberFocusListState("artists")
    ListInputEffect(focus, onActivate = { artists.getOrNull(it)?.let { a -> onOpen(a.id) } })
    when {
        loaded == null -> Unit
        artists.isEmpty() -> EmptyState(PodiumSymbol.Person, "No artists yet", NoMusic)
        else -> FocusList(
            items = artists,
            state = focus,
            key = { it.id.value },
            contentPadding = LocalScreenInsets.current.listPadding(),
            onActivate = { onOpen(artists[it].id) },
            preview = { a -> a.artworkUri?.let { MenuPreview.Artwork(listOf(it), round = true) } ?: MenuPreview.Artwork(a.covers) },
            modifier = Modifier.fillMaxSize(),
        ) { artist, _, focused ->
            TrackRow(
                artist.name,
                albumCount(artist.albumCount),
                focused,
                artist.artworkUri,
                trailing = artist.trackCount.toString(),
                roundArtwork = true,
            )
        }
    }
}

private sealed interface ArtistRow {
    data object Header : ArtistRow
    data object AllSongs : ArtistRow
    data class AlbumEntry(val album: LibraryAlbum) : ArtistRow
}

/** One artist: all their songs, then their albums. */
@Composable
fun ArtistScreen(repository: LibraryRepository, artistId: ArtistId, onOpenAlbum: (AlbumId) -> Unit, onPlay: PlayTracks) {
    val loaded = rememberLoaded(repository, artistId) { repository.artist(artistId) }
    val detail = loaded?.value
    val artist = detail?.artist
    val rows = remember(detail) { listOf(ArtistRow.Header, ArtistRow.AllSongs) + detail?.albums.orEmpty().map { ArtistRow.AlbumEntry(it) } }
    val focus = rememberFocusListState("artist:${artistId.value}")
    val activate: (Int) -> Unit = { i ->
        when (val row = rows.getOrNull(i)) {
            ArtistRow.AllSongs -> detail?.let { onPlay(it.tracks, 0, it.artist.name) }
            is ArtistRow.AlbumEntry -> onOpenAlbum(row.album.id)
            ArtistRow.Header, null -> Unit
        }
    }
    ListInputEffect(focus, onActivate = activate)
    when {
        loaded == null -> Unit
        artist == null -> EmptyState(PodiumSymbol.Person, "This artist isn't here anymore", "Their songs may have been removed. Go back to Artists.")
        else -> FocusList(
            items = rows,
            state = focus,
            key = {
                when (it) {
                    ArtistRow.Header -> "header"
                    ArtistRow.AllSongs -> "all"
                    is ArtistRow.AlbumEntry -> it.album.id.value
                }
            },
            contentPadding = LocalScreenInsets.current.listPadding(),
            onActivate = activate,
            focusable = { it !is ArtistRow.Header },
            preview = { row ->
                when (row) {
                    is ArtistRow.AlbumEntry -> MenuPreview.Artwork(listOfNotNull(row.album.artworkUri))
                    else -> MenuPreview.Artwork(artist.covers)
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) { row, _, focused ->
            when (row) {
                ArtistRow.Header -> DetailHeader(
                    artist.artworkUri,
                    artist.name,
                    albumCount(artist.albumCount),
                    listOf(songCount(artist.trackCount)),
                    round = true,
                )
                ArtistRow.AllSongs -> MenuRow("All songs", focused, leading = PodiumSymbol.PlaylistPlay, showChevron = false)
                is ArtistRow.AlbumEntry -> TrackRow(row.album.title, songCount(row.album.trackCount), focused, row.album.artworkUri, trailing = row.album.year?.toString())
            }
        }
    }
}

/** The songs the listener has marked as favorites, A to Z. */
@Composable
fun FavoritesScreen(
    repository: LibraryRepository,
    favorites: FavoritesRepository,
    onPlay: PlayTracks,
    onPlayNext: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
) {
    val (tracks, loading) = rememberTracks(repository)
    val favoriteIds by favorites.favorites.collectAsStateWithLifecycle()
    val songs = remember(tracks, favoriteIds) { tracks.filter { it.id in favoriteIds } }
    val focus = rememberFocusListState("favorites")
    val menu = songMenu(tracks, onPlayNext, onAddToQueue)
    ListInputEffect(focus, onActivate = { onPlay(songs, it, "Favorites") }, onLongPress = { menu(songs[it]) })
    when {
        loading -> Unit
        songs.isEmpty() -> EmptyState(PodiumSymbol.Favorite, "No favorites yet", "On Now Playing, choose the heart or More to add a song.")
        else -> FocusList(
            items = songs,
            state = focus,
            key = { it.id.value },
            contentPadding = LocalScreenInsets.current.listPadding(),
            onActivate = { onPlay(songs, it, "Favorites") },
            onLongPress = { menu(songs[it]) },
            preview = { MenuPreview.Artwork(listOfNotNull(it.artwork?.uri)) },
            modifier = Modifier.fillMaxSize(),
        ) { track, _, focused ->
            TrackRow(track.title, track.artistDisplay, focused, track.artwork?.uri, trailing = formatDuration(track.durationMs))
        }
    }
}

/**
 * Cover Flow (D-29): album covers as the whole screen. The centre cover faces you; its neighbours
 * stand angled to either side like a record rack, smaller and dimmer with distance. Matte around
 * the art — no reflections, no glow. The Wheel moves through it; Center opens the album; drag or
 * tap works too.
 */
@Composable
fun CoverFlowScreen(repository: LibraryRepository, onOpen: (AlbumId) -> Unit) {
    val loaded = rememberLoaded(repository) { repository.albums() }
    val loading = loaded == null
    val albums = loaded?.value.orEmpty()
    val insets = LocalScreenInsets.current
    val colors = PodiumTheme.colors
    val type = PodiumTheme.type
    val motion = PodiumTheme.motion
    val focus = rememberFocusListState("coverflow")
    SideEffect { focus.itemCount = albums.size }
    ListInputEffect(focus, onActivate = { albums.getOrNull(it)?.let { a -> onOpen(a.id) } })
    if (loading) return
    if (albums.isEmpty()) {
        EmptyState(PodiumSymbol.Album, "No albums yet", NoMusic)
        return
    }

    val position = remember { Animatable(focus.focusedIndex.toFloat()) }
    val scope = rememberCoroutineScope()
    // As a miniature (the column before an album), rest on the album that was opened.
    LocalMiniatureFocusKey.current?.let { key ->
        LaunchedEffect(albums, key) {
            val i = albums.indexOfFirst { it.id.value == key }
            if (i >= 0) {
                focus.focus(i)
                position.snapTo(i.toFloat())
            }
        }
    }
    LaunchedEffect(focus.focusedIndex) {
        val target = focus.focusedIndex.toFloat()
        if (motion.reduced) position.snapTo(target) else position.animateTo(target, spring(dampingRatio = 0.92f, stiffness = 380f))
    }

    BoxWithConstraints(Modifier.fillMaxSize().padding(top = insets.top, bottom = insets.bottom)) {
        val cover = minOf(maxWidth * 0.5f, maxHeight * 0.58f)
        val stageY = (maxHeight - cover) * 0.38f
        val spacingPx = cover.value * 0.62f
        Box(
            Modifier
                .fillMaxWidth()
                .height(cover)
                .offset(y = stageY)
                .pointerInput(albums.size) {
                    detectHorizontalDragGestures(
                        onDragEnd = { focus.focus(position.value.roundToInt().coerceIn(0, albums.lastIndex)) },
                    ) { change, dx ->
                        change.consume()
                        scope.launch { position.snapTo((position.value - dx / (spacingPx * density)).coerceIn(0f, albums.lastIndex.toFloat())) }
                    }
                },
        ) {
            val centre = position.value
            val window = (centre.roundToInt() - 4)..(centre.roundToInt() + 4)
            for (i in window) {
                val album = albums.getOrNull(i) ?: continue
                val d = i - centre
                val near = d.coerceIn(-1f, 1f)
                // Within one step the cover travels to its slot; beyond, neighbours stack closer.
                val x = if (abs(d) <= 1f) d * cover.value * 0.62f else sign(d) * (cover.value * 0.62f + (abs(d) - 1f) * cover.value * 0.24f)
                Box(
                    Modifier
                        .zIndex(-abs(d))
                        .align(Alignment.Center)
                        .offset(x = x.dp)
                        .size(cover)
                        .graphicsLayer {
                            rotationY = -near * 58f
                            cameraDistance = 14f * density
                            val s = 1f - 0.16f * abs(near)
                            scaleX = s
                            scaleY = s
                            alpha = (1f - (abs(d) - 2.5f).coerceAtLeast(0f)).coerceIn(0f, 1f)
                        }
                        .pointerInput(i) {
                            detectTapGestures { if (i == focus.focusedIndex) onOpen(album.id) else focus.focus(i) }
                        }
                        .semantics { contentDescription = "${album.title} by ${album.artist}" },
                ) {
                    ArtworkImage(album.artworkUri, cover, fallbackText = album.title, modifier = Modifier.fillMaxSize())
                }
            }
        }
        val current = albums.getOrNull(focus.focusedIndex)
        AnimatedContent(
            targetState = current,
            transitionSpec = { fadeIn(tween(if (motion.reduced) 0 else 200)) togetherWith fadeOut(tween(if (motion.reduced) 0 else 120)) },
            modifier = Modifier.align(Alignment.TopCenter).offset(y = stageY + cover + Spacing.l).padding(horizontal = Spacing.xl),
            label = "coverFlowTitle",
        ) { album ->
            if (album != null) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    PodiumText(album.title, illuminated(type.rowFocused, true), colors.labelPrimary, textAlign = TextAlign.Center)
                    PodiumText(album.artist, type.rowSecondary, colors.labelSecondary, textAlign = TextAlign.Center)
                }
            }
        }
    }
}
