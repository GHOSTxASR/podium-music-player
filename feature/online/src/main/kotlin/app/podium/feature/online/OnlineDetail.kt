package app.podium.feature.online

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.podium.core.designsystem.component.DetailHeader
import app.podium.core.designsystem.component.FocusList
import app.podium.core.designsystem.component.ListInputEffect
import app.podium.core.designsystem.component.LoadingScreen
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
import app.podium.core.model.Track
import app.podium.sources.api.AlbumSummary
import app.podium.sources.api.ArtistSummary
import app.podium.sources.api.PlaylistSummary

private sealed interface ArtistRow {
    data object Header : ArtistRow
    data object Radio : ArtistRow
    data object PlayAll : ArtistRow
    data class Section(val title: String) : ArtistRow
    data class Song(val track: Track, val index: Int) : ArtistRow
    data class Album(val album: AlbumSummary) : ArtistRow
    data class Playlist(val playlist: PlaylistSummary) : ArtistRow
    data class Related(val artist: ArtistSummary) : ArtistRow
}

/**
 * An online artist (D-34): picture and name, their radio, popular songs, albums and EPs,
 * playlists, and related artists — whatever the source provides.
 */
@Composable
fun OnlineArtistScreen(place: OnlinePlace.Artist, repository: OnlineRepository, actions: OnlineActions, navigate: (OnlinePlace) -> Unit) {
    val canRelate = repository.canRelate(place.id)
    val detail = rememberRemote(place.id) { repository.artist(place.id) }
    val related = rememberRemote(place.id) { if (canRelate) repository.relatedArtists(place.id) else app.podium.core.common.Outcome.Success(emptyList()) }
    val likedIds by repository.likedIds.collectAsStateWithLifecycle()
    val overlayHost = overlay()
    val d = (detail as? Remote.Ready)?.value
    val songs = d?.tracks.orEmpty().filter { it.availability !is Availability.Unavailable }
    val rows = remember(d, related) {
        if (d == null) emptyList()
        else buildList {
            add(ArtistRow.Header)
            if (canRelate) add(ArtistRow.Radio)
            if (songs.isNotEmpty()) add(ArtistRow.PlayAll)
            if (d.tracks.isNotEmpty()) {
                add(ArtistRow.Section("Popular"))
                d.tracks.take(10).forEachIndexed { i, t -> add(ArtistRow.Song(t, i)) }
            }
            if (d.albums.isNotEmpty()) {
                add(ArtistRow.Section("Albums and EPs"))
                d.albums.forEach { add(ArtistRow.Album(it)) }
            }
            if (d.playlists.isNotEmpty()) {
                add(ArtistRow.Section("Playlists"))
                d.playlists.forEach { add(ArtistRow.Playlist(it)) }
            }
            val rel = (related as? Remote.Ready)?.value.orEmpty()
            if (rel.isNotEmpty()) {
                add(ArtistRow.Section("Related artists"))
                rel.forEach { add(ArtistRow.Related(it)) }
            }
        }
    }
    val focus = rememberFocusListState("online-artist:${place.id.value}")
    val activate: (Int) -> Unit = { i ->
        when (val row = rows.getOrNull(i)) {
            ArtistRow.Radio -> d?.let { actions.startArtistRadio(it.summary) }
            ArtistRow.PlayAll -> actions.play(songs, 0, place.name)
            is ArtistRow.Song -> songs.indexOfFirst { it.id == row.track.id }.takeIf { it >= 0 }?.let { actions.play(songs, it, place.name) }
            is ArtistRow.Album -> navigate(OnlinePlace.Collection(app.podium.core.model.PlaylistId(row.album.id.value), row.album.title, isAlbum = true))
            is ArtistRow.Playlist -> navigate(OnlinePlace.Collection(row.playlist.id, row.playlist.title, row.playlist.isAlbum))
            is ArtistRow.Related -> navigate(OnlinePlace.Artist(row.artist.id, row.artist.name))
            else -> Unit
        }
    }
    val longPress: (Int) -> Unit = { i ->
        (rows.getOrNull(i) as? ArtistRow.Song)?.let { showTrackMenu(overlayHost, it.track, repository, actions, navigate) }
    }
    ListInputEffect(focus, onActivate = activate, onLongPress = longPress)
    when (detail) {
        Remote.Loading -> {
            LoadingScreen()
            return
        }
        is Remote.Failed -> {
            OnlineMessage(detail.error)
            return
        }
        is Remote.Ready -> Unit
    }
    val artist = d!!.summary
    FocusList(
        items = rows,
        state = focus,
        key = {
            when (it) {
                ArtistRow.Header -> "header"
                ArtistRow.Radio -> "radio"
                ArtistRow.PlayAll -> "play"
                is ArtistRow.Section -> "section:${it.title}"
                is ArtistRow.Song -> "song:${it.track.id.value}"
                is ArtistRow.Album -> "album:${it.album.id.value}"
                is ArtistRow.Playlist -> "playlist:${it.playlist.id.value}"
                is ArtistRow.Related -> "related:${it.artist.id.value}"
            }
        },
        contentPadding = LocalScreenInsets.current.listPadding(),
        onActivate = activate,
        onLongPress = longPress,
        focusable = { it !is ArtistRow.Header && it !is ArtistRow.Section },
        preview = { row ->
            when (row) {
                is ArtistRow.Song -> MenuPreview.Artwork(listOfNotNull(row.track.artwork?.uri))
                is ArtistRow.Album -> MenuPreview.Artwork(listOfNotNull(row.album.artwork?.uri))
                is ArtistRow.Playlist -> MenuPreview.Artwork(listOfNotNull(row.playlist.artwork?.uri))
                is ArtistRow.Related -> row.artist.artwork?.uri?.let { MenuPreview.Artwork(listOf(it), round = true) } ?: MenuPreview.None
                else -> MenuPreview.Carousel(covers(d.tracks, 10))
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { row, _, focused ->
        when (row) {
            ArtistRow.Header -> DetailHeader(
                artist.artwork?.uri,
                artist.name,
                artist.trackCount?.let { if (it == 1) "1 song" else "$it songs" } ?: "Artist",
                emptyList(),
                round = true,
            )
            ArtistRow.Radio -> MenuRow("${artist.name} radio", focused, leading = PodiumSymbol.PlaylistPlay, showChevron = false)
            ArtistRow.PlayAll -> MenuRow("Play popular songs", focused, leading = PodiumSymbol.Play, showChevron = false)
            is ArtistRow.Section -> SectionLabel(row.title)
            is ArtistRow.Song -> OnlineTrackRow(row.track, focused, liked = row.track.id in likedIds)
            is ArtistRow.Album -> TrackRow(row.album.title, row.album.year?.toString() ?: "Album", focused, row.album.artwork?.uri, trailing = row.album.trackCount?.toString())
            is ArtistRow.Playlist -> TrackRow(row.playlist.title, row.playlist.ownerName, focused, row.playlist.artwork?.uri, trailing = row.playlist.trackCount?.toString())
            is ArtistRow.Related -> OnlineArtistRow(row.artist, focused)
        }
    }
}

private sealed interface CollectionRow {
    data object Header : CollectionRow
    data class Artist(val name: String, val id: app.podium.core.model.ArtistId) : CollectionRow
    data object Play : CollectionRow
    data object Shuffle : CollectionRow
    data object Save : CollectionRow
    data class Song(val track: Track, val index: Int) : CollectionRow
}

/** An online album or playlist: its cover (large in the preview), Play, Shuffle, Save, the songs. */
@Composable
fun OnlineCollectionScreen(place: OnlinePlace.Collection, repository: OnlineRepository, actions: OnlineActions, navigate: (OnlinePlace) -> Unit) {
    val detail = rememberRemote(place.id) { repository.collection(place.id) }
    val likedIds by repository.likedIds.collectAsStateWithLifecycle()
    val overlayHost = overlay()
    val d = (detail as? Remote.Ready)?.value
    val playable = d?.tracks.orEmpty().filter { it.availability !is Availability.Unavailable }
    // An album leads to its artist (the one most of its songs credit, when the source links them).
    val artist = remember(d) {
        if (d?.summary?.isAlbum != true) null
        else d.tracks.flatMap { it.artists.take(1) }.filter { it.id != null }.groupingBy { it.id!! to it.name }.eachCount()
            .maxByOrNull { it.value }?.key?.let { (id, name) -> CollectionRow.Artist(name, id) }
    }
    val rows = remember(d, artist) {
        if (d == null) emptyList()
        else listOf(CollectionRow.Header) + listOfNotNull(artist) +
            (if (playable.isNotEmpty()) listOf(CollectionRow.Play, CollectionRow.Shuffle, CollectionRow.Save) else emptyList()) +
            d.tracks.mapIndexed { i, t -> CollectionRow.Song(t, i) }
    }
    val focus = rememberFocusListState("online-collection:${place.id.value}")
    val label = d?.summary?.title ?: place.title
    val activate: (Int) -> Unit = { i ->
        when (val row = rows.getOrNull(i)) {
            is CollectionRow.Artist -> navigate(OnlinePlace.Artist(row.id, row.name))
            // The whole album or playlist goes to whoever plays it, from the chosen song (§8.5).
            CollectionRow.Play -> actions.playCollection(playable, 0, label, place.id)
            CollectionRow.Shuffle -> actions.playCollection(playable, 0, label, place.id, shuffle = true)
            CollectionRow.Save -> showAddToPlaylist(overlayHost, playable, repository, navigate)
            is CollectionRow.Song -> playable.indexOfFirst { it.id == row.track.id }.takeIf { it >= 0 }?.let { actions.playCollection(playable, it, label, place.id) }
            else -> Unit
        }
    }
    val longPress: (Int) -> Unit = { i ->
        (rows.getOrNull(i) as? CollectionRow.Song)?.let { showTrackMenu(overlayHost, it.track, repository, actions, navigate) }
    }
    ListInputEffect(focus, onActivate = activate, onLongPress = longPress)
    when (detail) {
        Remote.Loading -> {
            LoadingScreen()
            return
        }
        is Remote.Failed -> {
            OnlineMessage(detail.error)
            return
        }
        is Remote.Ready -> Unit
    }
    val summary = d!!.summary
    FocusList(
        items = rows,
        state = focus,
        key = {
            when (it) {
                CollectionRow.Header -> "header"
                is CollectionRow.Artist -> "artist"
                CollectionRow.Play -> "play"
                CollectionRow.Shuffle -> "shuffle"
                CollectionRow.Save -> "save"
                is CollectionRow.Song -> "${it.track.id.value}#${it.index}"
            }
        },
        contentPadding = LocalScreenInsets.current.listPadding(),
        onActivate = activate,
        onLongPress = longPress,
        focusable = { it !is CollectionRow.Header },
        // The cover stays the picture beside every row: the album artwork is the point.
        preview = { row ->
            when (row) {
                is CollectionRow.Song -> MenuPreview.Artwork(listOfNotNull(row.track.artwork?.uri ?: summary.artwork?.uri))
                else -> MenuPreview.Artwork(listOfNotNull(summary.artwork?.uri))
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { row, _, focused ->
        when (row) {
            CollectionRow.Header -> DetailHeader(
                summary.artwork?.uri,
                summary.title,
                summary.ownerName,
                listOfNotNull(
                    summary.trackCount?.let { if (it == 1) "1 song" else "$it songs" },
                    summary.year?.toString(),
                    if (summary.isAlbum) "Album" else "Playlist",
                ),
            )
            is CollectionRow.Artist -> MenuRow(row.name, focused, leading = PodiumSymbol.Person)
            CollectionRow.Play -> MenuRow("Play", focused, leading = PodiumSymbol.Play, showChevron = false)
            CollectionRow.Shuffle -> MenuRow("Shuffle", focused, leading = PodiumSymbol.Shuffle, showChevron = false)
            CollectionRow.Save -> MenuRow("Add all to a playlist", focused, leading = PodiumSymbol.Queue, showChevron = false)
            is CollectionRow.Song -> OnlineTrackRow(row.track, focused, liked = row.track.id in likedIds)
        }
    }
}

/** A quiet section label inside a list (not focusable). */
@Composable
internal fun SectionLabel(text: String) {
    val colors = PodiumTheme.colors
    PodiumText(
        text,
        PodiumTheme.type.footnote,
        colors.labelTertiary,
        Modifier.padding(start = LocalRowPadding.current, end = LocalRowPadding.current, top = Spacing.m, bottom = Spacing.xs),
    )
}
