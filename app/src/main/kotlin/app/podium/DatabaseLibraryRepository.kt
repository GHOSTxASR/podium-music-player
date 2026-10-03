package app.podium

import android.util.Log
import app.podium.core.database.LibraryStore
import app.podium.core.database.StoredAlbum
import app.podium.core.database.StoredArtist
import app.podium.core.model.AlbumId
import app.podium.core.model.ArtistId
import app.podium.core.model.SourceId
import app.podium.feature.library.AlbumDetail
import app.podium.feature.library.ArtistDetail
import app.podium.feature.library.LibraryAlbum
import app.podium.feature.library.LibraryArtist
import app.podium.feature.library.LibraryRepository
import app.podium.feature.library.LibraryState
import app.podium.feature.library.SourceAction
import app.podium.player.api.TrackCatalog
import app.podium.sources.api.Capability
import app.podium.sources.api.SourceRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The library from the database (D-31). Each usable library source is synced into [store] in the
 * background whenever it reports a change; screens read the store, so the library is there the
 * moment Podium opens, before any source has rescanned.
 *
 * Reads only cover sources whose library is usable right now (a revoked permission hides that
 * source's songs; they come back with it) and, in debug builds, the narrowed scope.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DatabaseLibraryRepository(
    private val registry: SourceRegistry,
    private val store: LibraryStore,
    private val catalog: TrackCatalog,
    private val scope: CoroutineScope,
    /** Debug builds can narrow what the library shows to one source for device tests (never persisted). */
    onlySource: StateFlow<SourceId?> = MutableStateFlow(null),
) : LibraryRepository {

    /** Library sources that can contribute now, with their names. */
    private val usable: Flow<List<Pair<SourceId, String>>> = registry.connectedSources
        .map { sources ->
            sources.filter { it.enabled && it.capabilities.isUsable(Capability.LIBRARY) }
                .map { it.sourceId to it.descriptor.displayName }
        }
        .distinctUntilChanged()

    /** What screens see: the usable sources, narrowed in debug builds. */
    private val readScope: StateFlow<List<SourceId>?> = usable
        .combine(onlySource) { sources, only -> sources.map { it.first }.filter { only == null || it == only } }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** Sources synced at least once in this run. Until then an empty store means "still loading". */
    private val synced = MutableStateFlow<Set<SourceId>>(emptySet())

    init {
        scope.launch {
            usable.collectLatest { sources ->
                coroutineScope { sources.forEach { (id, name) -> launch { keepSynced(id, name) } } }
            }
        }
    }

    private suspend fun keepSynced(id: SourceId, name: String) {
        val library = registry.get(id)?.library ?: return
        combine(library.tracks(), library.artists()) { tracks, artists ->
            tracks to artists.mapNotNull { a -> a.artwork?.let { a.id to it.uri } }.toMap()
        }
            .conflate()
            .collect { (tracks, pictures) ->
                runCatching { store.sync(id, name, tracks, pictures) }
                    .onSuccess { if (it.changed) Log.d(TAG, "synced $id: $it") }
                    .onFailure { Log.w(TAG, "could not sync $id", it) }
                synced.update { it + id }
            }
    }

    private fun <T> scoped(block: (List<SourceId>) -> Flow<T>): Flow<T> =
        readScope.flatMapLatest { ids -> if (ids == null) kotlinx.coroutines.flow.emptyFlow() else block(ids) }

    override val songs: StateFlow<LibraryState> = scoped { ids ->
        combine(store.songs(ids), synced) { tracks, done ->
            if (tracks.isEmpty() && !done.containsAll(ids)) LibraryState.Loading else LibraryState.Ready(tracks)
        }
    }
        .onEach { (it as? LibraryState.Ready)?.let { ready -> catalog.remember(ready.tracks) } }
        .stateIn(scope, SharingStarted.Eagerly, LibraryState.Loading)

    override fun albums(): Flow<List<LibraryAlbum>> = scoped { ids -> store.albums(ids) }.map { it.map(::album) }

    override fun album(id: AlbumId): Flow<AlbumDetail?> = scoped { ids ->
        store.album(id).map { detail -> detail?.takeIf { d -> d.tracks.any { it.id.sourceId in ids } }?.let { AlbumDetail(album(it.album), it.tracks) } }
    }

    override fun artists(): Flow<List<LibraryArtist>> = scoped { ids -> store.artists(ids) }.map { it.map(::artist) }

    override fun artist(id: ArtistId): Flow<ArtistDetail?> = scoped { ids ->
        store.artist(id).map { detail ->
            detail?.takeIf { d -> d.tracks.any { it.id.sourceId in ids } }?.let { ArtistDetail(artist(it.artist), it.albums.map(::album), it.tracks) }
        }
    }

    override val pendingActions: StateFlow<List<SourceAction>> = registry.connectedSources
        .map { sources ->
            sources.filter { it.enabled }.mapNotNull { source ->
                val state = source.capabilities[Capability.LIBRARY]
                val action = state.action
                if (state.status.isActionable && action != null) {
                    SourceAction(source.descriptor.displayName, state.note ?: source.descriptor.displayName, action)
                } else null
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private companion object {
        const val TAG = "PodiumLibrary"

        fun album(a: StoredAlbum) = LibraryAlbum(a.id, a.title, a.artist, a.artistId, a.artworkUri, a.year, a.trackCount)

        fun artist(a: StoredArtist) = LibraryArtist(a.id, a.name, a.artworkUri, a.albumCount, a.trackCount, a.covers)
    }
}
