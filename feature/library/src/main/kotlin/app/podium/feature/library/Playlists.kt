package app.podium.feature.library

import androidx.compose.runtime.staticCompositionLocalOf
import app.podium.core.designsystem.component.MenuAction
import app.podium.core.designsystem.component.MenuSpec
import app.podium.core.designsystem.component.OverlayHost
import app.podium.core.interaction.PodiumHaptics
import app.podium.core.model.Track
import app.podium.core.model.TrackId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** A playlist of the music on this phone (D-106), as lists show it; [covers] are a few of its songs' covers. */
data class LibraryPlaylist(val id: String, val name: String, val trackCount: Int, val covers: List<String> = emptyList())

/** One song in a playlist. A song can be there twice; [entryId] tells them apart. */
data class LibraryPlaylistEntry(val entryId: Long, val track: Track)

data class PlaylistDetail(val playlist: LibraryPlaylist, val entries: List<LibraryPlaylistEntry>)

/**
 * The listener's playlists of the music on this phone (D-106): the library's own, apart from online
 * playlists (D-34), and online songs never join them. Songs are kept by id, so one that leaves the
 * library drops out of view until it's back.
 */
interface PlaylistRepository {
    /** Every playlist, the most recently changed first; null until read. */
    val playlists: StateFlow<List<LibraryPlaylist>?>

    fun playlist(id: String): Flow<PlaylistDetail?>

    /** A new playlist named [name] holding [songs]; returns its id. */
    suspend fun create(name: String, songs: List<TrackId> = emptyList()): String

    fun rename(id: String, name: String)

    fun delete(id: String)

    /** Adds [songs] at the end, in order. */
    /**
     * Adds [songs] to the playlist, leaving out any it already holds (D-110). [onAdded] is told how
     * many actually went in, on the main thread, so the caller can tell "added" from "already there".
     */
    fun add(id: String, songs: List<TrackId>, onAdded: (Int) -> Unit = {})

    /**
     * Of the listener's playlists, those that already hold every one of [songs] (D-110): the picker
     * marks them, so it is plain before choosing which ones have nothing to take.
     */
    suspend fun playlistsHoldingAll(songs: List<TrackId>): Set<String> = emptySet()

    fun remove(id: String, entryId: Long)

    /** Moves an entry so it ends up at [toIndex] among the songs the playlist shows. */
    fun move(id: String, entryId: Long, toIndex: Int)
}

/**
 * Add to playlist, from the library's song menus and Now Playing (D-106): one of the listener's
 * playlists, or a new one. The app provides it; without it (previews, tests) menus leave it out.
 */
class PlaylistPicker(
    private val repository: PlaylistRepository,
    private val haptics: PodiumHaptics?,
    /** Where the one quick read of what the playlists already hold runs (D-110). */
    private val scope: CoroutineScope,
    /** Opens naming a new playlist; the songs wait in [PendingPlaylistSongs]. */
    private val onNewPlaylist: () -> Unit,
) {
    fun show(overlay: OverlayHost, songs: List<TrackId>) {
        if (songs.isEmpty()) return
        scope.launch {
            // One indexed read before the menu opens, so each playlist can say whether it has them.
            val holding = repository.playlistsHoldingAll(songs)
            val playlists = repository.playlists.value.orEmpty()
            overlay.show(
                MenuSpec(
                    if (songs.size == 1) "Add to playlist" else "Add ${songs.size} songs to a playlist",
                    buildList {
                        add(
                            MenuAction("New playlist") {
                                PendingPlaylistSongs.songs = songs
                                onNewPlaylist()
                            },
                        )
                        playlists.forEach { p ->
                            val already = p.id in holding
                            add(
                                MenuAction(p.name, note = if (already) ADDED else null) {
                                    // Nothing goes in when it is already there (D-110): the reject is
                                    // the established "that did nothing", not a failure.
                                    repository.add(p.id, songs) { added ->
                                        if (added > 0) haptics?.confirm() else haptics?.reject()
                                    }
                                },
                            )
                        }
                    },
                ),
            )
        }
    }
}

/** What a playlist that already holds the songs says, as Add songs says it of a row (D-106, D-110). */
const val ADDED = "Added"

val LocalPlaylistPicker = staticCompositionLocalOf<PlaylistPicker?> { null }

/** Songs waiting for the playlist that's being named: one step of one flow, never saved. */
object PendingPlaylistSongs {
    var songs: List<TrackId> = emptyList()
}

internal fun songCount(n: Int) = if (n == 1) "1 song" else "$n songs"

/** A playlist's length: "48 min", "1 hr 12 min"; null when nothing in it says how long it is. */
internal fun playlistLength(tracks: List<Track>): String? {
    val ms = tracks.sumOf { it.durationMs ?: 0L }
    if (ms <= 0L) return null
    val minutes = ((ms + 30_000) / 60_000).coerceAtLeast(1)
    val hours = minutes / 60
    return if (hours == 0L) "$minutes min" else if (minutes % 60 == 0L) "$hours hr" else "$hours hr ${minutes % 60} min"
}
