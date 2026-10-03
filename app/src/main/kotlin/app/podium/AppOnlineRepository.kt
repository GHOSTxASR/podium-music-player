package app.podium

import app.podium.core.common.Outcome
import app.podium.core.common.PodiumError
import app.podium.core.database.OnlineLibraryStore
import app.podium.core.model.ArtistId
import app.podium.core.model.PlaylistId
import app.podium.core.model.Track
import app.podium.core.model.TrackId
import app.podium.feature.online.OnlinePlaylist
import app.podium.feature.online.OnlinePlaylistContents
import app.podium.feature.online.OnlinePlaylistEntry
import app.podium.feature.online.OnlineRepository
import app.podium.feature.online.OnlineSource
import app.podium.player.api.TrackCatalog
import app.podium.sources.api.ArtistDetail
import app.podium.sources.api.ArtistSummary
import app.podium.sources.api.Capability
import app.podium.sources.api.MusicEnvironment
import app.podium.sources.api.MusicSource
import app.podium.sources.api.PlaylistDetail
import app.podium.sources.api.SearchQuery
import app.podium.sources.api.SearchResults
import app.podium.sources.api.Shelf
import app.podium.sources.api.ShelfPage
import app.podium.sources.api.SourceRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ONLINE for the screens (D-34). Catalogue calls go to the online source (found by environment and
 * capability, never by name); likes, playlists and history are ONLINE's own store. Every online
 * song the screens get is put in the playback catalog (so play commands by id work) and its
 * metadata kept when it's liked, saved or played.
 */
class AppOnlineRepository(
    private val registry: SourceRegistry,
    private val store: OnlineLibraryStore,
    private val catalog: TrackCatalog,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) : OnlineRepository {

    /** The first enabled ONLINE source. */
    private fun online(): MusicSource? = registry.ordered().firstOrNull { it.descriptor.environment == MusicEnvironment.ONLINE }

    override val source: StateFlow<OnlineSource?> = registry.connectedSources
        .map { sources ->
            sources.firstOrNull { it.enabled && it.descriptor.environment == MusicEnvironment.ONLINE }?.let { s ->
                OnlineSource(
                    name = s.descriptor.displayName,
                    canSearch = s.capabilities.isUsable(Capability.SEARCH),
                    canBrowse = s.capabilities.isUsable(Capability.BROWSE),
                    canRecommend = s.capabilities.isUsable(Capability.RECOMMENDATIONS),
                )
            }
        }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, null)

    private var shelvesCache: Pair<Long, List<Shelf>>? = null

    override suspend fun shelves(): Outcome<List<Shelf>> {
        shelvesCache?.let { (at, shelves) -> if (now() - at < CACHE_MS) return Outcome.Success(shelves) }
        val facet = online()?.discovery ?: return Outcome.Failure(PodiumError.NotFound("online source"))
        return facet.shelves().also { r ->
            if (r is Outcome.Success) {
                shelvesCache = now() to r.value
                catalog.remember(r.value.flatMap { it.tracks })
            }
        }
    }

    override suspend fun shelf(id: String, offset: Int, limit: Int): Outcome<ShelfPage> =
        online()?.discovery?.shelf(id, offset, limit)?.alsoTracks { it.tracks } ?: missing()

    override suspend fun genres(): Outcome<List<String>> = online()?.discovery?.genres() ?: missing()

    override suspend fun genre(name: String, offset: Int, limit: Int): Outcome<List<Track>> =
        online()?.discovery?.genre(name, offset, limit)?.alsoTracks { it } ?: missing()

    override suspend fun search(text: String, offset: Int, limit: Int): Outcome<SearchResults> =
        online()?.catalog?.search(SearchQuery(text, limit, offset))?.alsoTracks { it.tracks } ?: missing()

    override suspend fun artist(id: ArtistId): Outcome<ArtistDetail> =
        online()?.catalog?.artist(id)?.alsoTracks { it.tracks } ?: missing()

    override suspend fun relatedArtists(id: ArtistId): Outcome<List<ArtistSummary>> =
        online()?.recommendations?.relatedArtists(id, 10) ?: Outcome.Success(emptyList())

    override suspend fun collection(id: PlaylistId): Outcome<PlaylistDetail> =
        online()?.catalog?.playlist(id)?.alsoTracks { it.tracks } ?: missing()

    private fun <T> Outcome<T>.alsoTracks(tracks: (T) -> List<Track>): Outcome<T> {
        if (this is Outcome.Success) catalog.remember(tracks(value))
        return this
    }

    private fun <T> missing(): Outcome<T> = Outcome.Failure(PodiumError.NotFound("online source"))

    // --- ONLINE's own library ---------------------------------------------------------------------------

    override val likedIds: StateFlow<Set<TrackId>> = store.likedIds().stateIn(scope, SharingStarted.Eagerly, emptySet())

    override val likedTracks: Flow<List<Track>> = store.likedTracks().map { it.also(catalog::remember) }

    override fun setLiked(track: Track, liked: Boolean) {
        scope.launch { store.setLiked(track, liked) }
    }

    override val playlists: Flow<List<OnlinePlaylist>> =
        store.playlists().map { list -> list.map { OnlinePlaylist(it.id, it.name, it.trackCount) } }

    override fun playlist(id: String): Flow<OnlinePlaylistContents?> = store.playlist(id).map { d ->
        d?.let {
            catalog.remember(it.entries.map { e -> e.track })
            OnlinePlaylistContents(OnlinePlaylist(it.info.id, it.info.name, it.info.trackCount), it.entries.map { e -> OnlinePlaylistEntry(e.entryId, e.track) })
        }
    }

    override suspend fun createPlaylist(name: String, tracks: List<Track>): String {
        val source = online()?.descriptor?.id ?: tracks.firstOrNull()?.source?.sourceId ?: app.podium.core.model.SourceId("online")
        return store.createPlaylist(name, source, tracks)
    }

    override fun renamePlaylist(id: String, name: String) {
        scope.launch { store.rename(id, name) }
    }

    override fun deletePlaylist(id: String) {
        scope.launch { store.delete(id) }
    }

    override fun addToPlaylist(id: String, tracks: List<Track>) {
        scope.launch { store.add(id, tracks) }
    }

    override fun removeFromPlaylist(id: String, entryId: Long) {
        scope.launch { store.remove(id, entryId) }
    }

    override fun movePlaylistEntry(id: String, entryId: Long, toIndex: Int) {
        scope.launch { store.move(id, entryId, toIndex) }
    }

    override val recentlyPlayed: Flow<List<Track>> = store.recentlyPlayed().map { it.also(catalog::remember) }

    override fun clearHistory() {
        scope.launch { store.clearHistory() }
    }

    private companion object {
        const val CACHE_MS = 10 * 60 * 1000L
    }
}
