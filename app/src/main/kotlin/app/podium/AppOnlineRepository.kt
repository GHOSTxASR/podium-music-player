package app.podium

import app.podium.core.common.Outcome
import app.podium.core.database.OnlineLibraryStore
import app.podium.core.model.ArtistId
import app.podium.core.model.PlaylistId
import app.podium.core.model.SourceId
import app.podium.core.model.Track
import app.podium.core.model.TrackId
import app.podium.feature.online.OnlinePlaylist
import app.podium.feature.online.OnlinePlaylistContents
import app.podium.feature.online.OnlinePlaylistEntry
import app.podium.feature.online.OnlineRepository
import app.podium.feature.online.OnlineStatus
import app.podium.player.api.TrackCatalog
import app.podium.sources.api.ArtistDetail
import app.podium.sources.api.ArtistSummary
import app.podium.sources.api.Capability
import app.podium.sources.api.MusicEnvironment
import app.podium.sources.api.PlaylistDetail
import app.podium.sources.api.SearchQuery
import app.podium.sources.api.SearchResults
import app.podium.sources.api.Shelf
import app.podium.sources.api.ShelfPage
import app.podium.sources.api.SourceRegistry
import app.podium.sources.api.aggregate.MultiSourceCatalog
import app.podium.sources.api.aggregate.TrackGrouper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ONLINE for the screens (D-34, D-35). Catalogue questions go to every enabled online source at
 * once through the [MultiSourceCatalog] — chosen by environment and capability, never by name —
 * and come back as one answer, each song once. Likes, playlists and history are ONLINE's own store:
 * every record keeps the copy (and source) it was made from; lists show each recording once.
 * Every online song the screens get is put in the playback catalog (so play commands by id work).
 */
class AppOnlineRepository(
    private val registry: SourceRegistry,
    private val onlineCatalog: MultiSourceCatalog,
    private val store: OnlineLibraryStore,
    private val catalog: TrackCatalog,
    private val scope: CoroutineScope,
    private val grouper: TrackGrouper = TrackGrouper(),
    private val now: () -> Long = System::currentTimeMillis,
) : OnlineRepository {

    override val status: StateFlow<OnlineStatus?> = registry.connectedSources
        .map { sources ->
            val online = sources.filter { it.enabled && it.descriptor.environment == MusicEnvironment.ONLINE }
            if (online.isEmpty()) null
            else OnlineStatus(
                canSearch = online.any { it.capabilities.isUsable(Capability.SEARCH) },
                canBrowse = online.any { it.capabilities.isUsable(Capability.BROWSE) },
                canRecommend = online.any { it.capabilities.isUsable(Capability.RECOMMENDATIONS) },
            )
        }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, null)

    override fun canStartRadio(track: Track): Boolean = onlineCatalog.recommends(track)

    override fun canRelate(artist: ArtistId): Boolean = onlineCatalog.recommends(artist)

    // --- Catalogue ------------------------------------------------------------------------------------

    /** Shelves change slowly; kept a while, and dropped when the set of online sources changes. */
    private var shelvesCache: Triple<Long, List<SourceId>, List<Shelf>>? = null

    private fun onlineSourceIds() = registry.ordered(MusicEnvironment.ONLINE).map { it.descriptor.id }

    override suspend fun shelves(): Outcome<List<Shelf>> {
        val sources = onlineSourceIds()
        shelvesCache?.let { (at, cachedFor, shelves) -> if (now() - at < CACHE_MS && cachedFor == sources) return Outcome.Success(shelves) }
        return onlineCatalog.shelves().also { r ->
            if (r is Outcome.Success) {
                shelvesCache = Triple(now(), sources, r.value)
                catalog.remember(r.value.flatMap { it.tracks })
            }
        }
    }

    override suspend fun shelf(id: String, offset: Int, limit: Int): Outcome<ShelfPage> =
        onlineCatalog.shelf(id, offset, limit).alsoTracks { it.tracks }

    override suspend fun genres(): Outcome<List<String>> = onlineCatalog.genres()

    override suspend fun genre(name: String, offset: Int, limit: Int): Outcome<List<Track>> =
        onlineCatalog.genre(name, offset, limit).alsoTracks { it }

    override fun search(text: String, offset: Int, limit: Int): Flow<Outcome<SearchResults>> =
        onlineCatalog.search(SearchQuery(text, limit, offset)).mapNotNull { view ->
            when {
                view.error != null -> Outcome.Failure(view.error!!)
                // Nothing yet from anyone still answering: keep waiting rather than say "nothing found".
                !view.complete && view.results == SearchResults.Empty -> null
                else -> Outcome.Success(view.results).also { catalog.remember(view.results.tracks) }
            }
        }

    override suspend fun artist(id: ArtistId): Outcome<ArtistDetail> =
        onlineCatalog.artist(id).alsoTracks { it.tracks }

    override suspend fun relatedArtists(id: ArtistId): Outcome<List<ArtistSummary>> =
        onlineCatalog.relatedArtists(id, RELATED_ARTISTS)

    override suspend fun collection(id: PlaylistId): Outcome<PlaylistDetail> =
        onlineCatalog.playlist(id).alsoTracks { it.tracks }

    private fun <T> Outcome<T>.alsoTracks(tracks: (T) -> List<Track>): Outcome<T> {
        if (this is Outcome.Success) catalog.remember(tracks(value))
        return this
    }

    // --- ONLINE's own library ---------------------------------------------------------------------------

    override val likedIds: StateFlow<Set<TrackId>> = store.likedIds().stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** Each liked recording once: two copies liked on two sources stay two likes, shown as one song. */
    override val likedTracks: Flow<List<Track>> = store.likedTracks().map { grouper.distinctRecordings(it).also(catalog::remember) }

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

    /**
     * A playlist kept on this device. Its songs keep their own sources; the playlist is filed under
     * the most preferred online source (where it would be mirrored if that source ever holds playlists).
     */
    override suspend fun createPlaylist(name: String, tracks: List<Track>): String {
        val source = onlineSourceIds().firstOrNull() ?: tracks.firstOrNull()?.source?.sourceId ?: SourceId("online")
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

    /** Listening history by recording: a song played from two sources is one entry (the latest). */
    override val recentlyPlayed: Flow<List<Track>> = store.recentlyPlayed().map { grouper.distinctRecordings(it).also(catalog::remember) }

    override fun clearHistory() {
        scope.launch { store.clearHistory() }
    }

    private companion object {
        const val CACHE_MS = 10 * 60 * 1000L
        const val RELATED_ARTISTS = 10
    }
}
