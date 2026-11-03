package app.podium

import app.podium.core.database.LocalPlaylistInfo
import app.podium.core.database.LocalPlaylistStore
import app.podium.core.model.TrackId
import app.podium.feature.library.LibraryPlaylist
import app.podium.feature.library.LibraryPlaylistEntry
import app.podium.feature.library.PlaylistDetail
import app.podium.feature.library.PlaylistRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The listener's playlists of the music on this phone (D-106), in LOCAL's own tables
 * ([LocalPlaylistStore]), never online's (D-34). Changes are written on [scope]; screens see them
 * come back through the store's flows.
 */
class DatabasePlaylists(private val store: LocalPlaylistStore, private val scope: CoroutineScope) : PlaylistRepository {

    override val playlists: StateFlow<List<LibraryPlaylist>?> =
        store.playlists().map { list -> list.map { it.toLibrary() } }.stateIn(scope, SharingStarted.Eagerly, null)

    override fun playlist(id: String): Flow<PlaylistDetail?> = store.playlist(id).map { d ->
        d?.let { PlaylistDetail(it.info.toLibrary(), it.entries.map { e -> LibraryPlaylistEntry(e.entryId, e.track) }) }
    }

    override suspend fun create(name: String, songs: List<TrackId>): String = store.create(name, songs)

    override fun rename(id: String, name: String) {
        scope.launch { store.rename(id, name) }
    }

    override fun delete(id: String) {
        scope.launch { store.delete(id) }
    }

    override fun add(id: String, songs: List<TrackId>, onAdded: (Int) -> Unit) {
        scope.launch { onAdded(store.add(id, songs)) }
    }

    override suspend fun playlistsHoldingAll(songs: List<TrackId>): Set<String> = store.playlistsHoldingAll(songs)

    override fun remove(id: String, entryId: Long) {
        scope.launch { store.remove(id, entryId) }
    }

    override fun move(id: String, entryId: Long, toIndex: Int) {
        scope.launch { store.move(id, entryId, toIndex) }
    }

    private fun LocalPlaylistInfo.toLibrary() = LibraryPlaylist(id, name, trackCount, covers.map { it.uri })
}
