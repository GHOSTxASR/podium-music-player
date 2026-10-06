package app.podium

import app.podium.core.common.Outcome
import app.podium.core.common.PodiumError
import app.podium.core.database.OnlineLibraryStore
import app.podium.core.model.ArtistId
import app.podium.core.model.PlaylistId
import app.podium.core.model.SourceId
import app.podium.core.model.Track
import app.podium.core.model.TrackId
import app.podium.feature.online.AccountState
import app.podium.feature.online.OnlineAccount
import app.podium.feature.online.OnlinePlaylist
import app.podium.feature.online.OnlinePlaylistContents
import app.podium.feature.online.OnlinePlaylistEntry
import app.podium.feature.online.OnlineRepository
import app.podium.feature.online.OnlineStatus
import app.podium.player.api.TrackCatalog
import app.podium.sources.api.AlbumSummary
import app.podium.sources.api.ArtistDetail
import app.podium.sources.api.ArtistSummary
import app.podium.sources.api.AuthState
import app.podium.sources.api.Capability
import app.podium.sources.api.ConnectedSource
import app.podium.sources.api.HealthOutcome
import app.podium.sources.api.HistoryEntry
import app.podium.sources.api.MusicEnvironment
import app.podium.sources.api.MusicSource
import app.podium.sources.api.PlaylistDetail
import app.podium.sources.api.PlaylistSummary
import app.podium.sources.api.SearchQuery
import app.podium.sources.api.SearchResults
import app.podium.sources.api.Shelf
import app.podium.sources.api.ShelfPage
import app.podium.sources.api.SourceHealth
import app.podium.sources.api.SourceHealthMonitor
import app.podium.sources.api.SourceRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ONLINE for the screens (YOUTUBE_MUSIC_ARCHITECTURE.md §9): one online music service, read through
 * its facets — never by name. Catalogue answers come from the service; the listener's own things
 * come from where they belong:
 *
 * - **Signed in**, liked songs are the account's (cached per account, written through to it), and
 *   the library and history are the account's own.
 * - **Signed out**, likes and playlists are kept on this device, as before.
 * - What Podium itself played online is its own record ([recentlyPlayed]), kept per account.
 *
 * Signing out removes everything kept for that account; local music, local favorites and the local
 * queue are never touched (D-34).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnlineMusicRepository(
    private val registry: SourceRegistry,
    private val health: SourceHealthMonitor,
    private val store: OnlineLibraryStore,
    private val catalog: TrackCatalog,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) : OnlineRepository {

    /** The online service, if it's on. */
    private fun source(): MusicSource? = registry.ordered(MusicEnvironment.ONLINE).firstOrNull()

    private val service: StateFlow<ConnectedSource?> = registry.connectedSources
        .map { all -> all.firstOrNull { it.enabled && it.descriptor.environment == MusicEnvironment.ONLINE } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** Whose things are shown: the signed-in account's key, or the device's. */
    val accountKey: StateFlow<String> = service
        .map { (it?.authenticationState as? AuthState.SignedIn)?.accountKey ?: OnlineLibraryStore.DEVICE }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, OnlineLibraryStore.DEVICE)

    override val status: StateFlow<OnlineStatus?> = service
        .map { s ->
            s?.let {
                val caps = it.capabilities
                OnlineStatus(
                    canSearch = caps.isUsable(Capability.SEARCH),
                    canBrowse = caps.isUsable(Capability.BROWSE),
                    canRecommend = caps.isUsable(Capability.RECOMMENDATIONS),
                    canLibrary = caps.isUsable(Capability.ACCOUNT_LIBRARY),
                    canHistory = caps.isUsable(Capability.HISTORY),
                    canExplore = false,
                    account = accountOf(it.authenticationState),
                )
            }
        }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, null)

    private fun accountOf(state: AuthState): OnlineAccount? = when (state) {
        AuthState.NotRequired -> null
        AuthState.SignedOut, is AuthState.Rejected -> OnlineAccount(AccountState.SIGNED_OUT)
        AuthState.SigningIn -> OnlineAccount(AccountState.SIGNING_IN)
        is AuthState.SignedIn -> OnlineAccount(AccountState.SIGNED_IN, state.accountName)
        AuthState.Expired -> OnlineAccount(AccountState.EXPIRED)
    }

    init {
        // A new account signed in: its likes are fetched. Nothing of another account is ever shown.
        scope.launch {
            accountKey.collect { key ->
                shelvesCache = null
                if (key != OnlineLibraryStore.DEVICE) refreshLibrary()
            }
        }
    }

    /**
     * Sign out: everything kept for the account goes first, then the service forgets the session.
     * Local music, favorites and the local queue are untouched.
     */
    suspend fun signOut() {
        val key = accountKey.value
        if (key != OnlineLibraryStore.DEVICE) store.forgetAccount(key)
        shelvesCache = null
        source()?.auth?.signOut() ?: registry.registered(MusicEnvironment.ONLINE).firstOrNull()?.auth?.signOut()
    }

    // --- Asking the service ----------------------------------------------------------------------------

    /**
     * One request to the service: bounded in time, and recorded in its health. A miss (it doesn't
     * have it) is not a failure; being offline counts against no one; an expired sign-in is the
     * account's business, not the service's health (§11).
     */
    private suspend fun <T> ask(block: suspend (MusicSource) -> Outcome<T>?): Outcome<T> {
        val source = source() ?: return Outcome.Failure(PodiumError.PolicyDisabled("online music is off"))
        val id = source.descriptor.id
        when (val h = health.health(id)) {
            is SourceHealth.Unreachable -> return Outcome.Failure(PodiumError.Network("resting after failures"))
            is SourceHealth.RateLimited -> return Outcome.Failure(PodiumError.RateLimited((h.untilMillis - now()).coerceAtLeast(0)))
            else -> Unit
        }
        val result = try {
            withTimeoutOrNull(TIMEOUT_MS) { block(source) ?: Outcome.Failure(PodiumError.Unsupported("not offered")) }
                ?: Outcome.Failure(PodiumError.Network("timeout"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome.Failure(PodiumError.Unexpected(e.javaClass.simpleName))
        }
        record(id, result)
        return result
    }

    private fun record(id: SourceId, result: Outcome<*>) {
        when (result) {
            is Outcome.Success -> health.record(id, HealthOutcome.SUCCESS)
            is Outcome.Failure -> when (val e = result.error) {
                is PodiumError.NotFound, is PodiumError.Unsupported, is PodiumError.NotPlayable -> health.record(id, HealthOutcome.MISS)
                is PodiumError.Network -> health.record(id, HealthOutcome.NETWORK_FAILURE)
                is PodiumError.Server -> health.record(id, HealthOutcome.SERVER_ERROR)
                is PodiumError.RateLimited -> health.record(id, HealthOutcome.RATE_LIMIT, e.retryAfterMillis)
                is PodiumError.Unexpected -> health.record(id, HealthOutcome.UNKNOWN)
                else -> Unit // offline, sign-in, policy: not the service's health
            }
        }
    }

    private fun <T> Outcome<T>.remembering(tracks: (T) -> List<Track>): Outcome<T> {
        if (this is Outcome.Success) catalog.remember(tracks(value))
        return this
    }

    // --- Catalogue ------------------------------------------------------------------------------------------

    override fun canStartRadio(track: Track): Boolean =
        source()?.let { it.descriptor.id == track.source.sourceId && it.capabilities.value.isUsable(Capability.RECOMMENDATIONS) } == true

    override fun canRelate(artist: ArtistId): Boolean =
        source()?.let { it.descriptor.id == artist.sourceId && it.recommendations != null } == true

    /** Shelves change slowly: kept a while, per account. */
    @Volatile private var shelvesCache: Triple<Long, String, List<Shelf>>? = null

    override suspend fun shelves(): Outcome<List<Shelf>> {
        val key = accountKey.value
        shelvesCache?.let { (at, account, shelves) -> if (now() - at < SHELVES_CACHE_MS && account == key) return Outcome.Success(shelves) }
        return ask { it.discovery?.shelves() }.also { r ->
            if (r is Outcome.Success) {
                shelvesCache = Triple(now(), key, r.value)
                catalog.remember(r.value.flatMap { it.tracks })
            }
        }
    }

    override suspend fun shelf(id: String, offset: Int, limit: Int): Outcome<ShelfPage> =
        ask { it.discovery?.shelf(id, offset, limit) }.remembering { it.tracks }

    override suspend fun genres(): Outcome<List<String>> = ask { it.discovery?.genres() }

    override suspend fun genre(name: String, offset: Int, limit: Int): Outcome<List<Track>> =
        ask { it.discovery?.genre(name, offset, limit) }.remembering { it }

    override fun search(text: String, offset: Int, limit: Int): Flow<Outcome<SearchResults>> = flow {
        val kinds = if (offset > 0) setOf(app.podium.sources.api.SearchKind.TRACKS) else app.podium.sources.api.SearchKind.entries.toSet()
        emit(ask { it.catalog?.search(SearchQuery(text, limit, offset, kinds)) }.remembering { it.tracks + it.videos })
    }

    override suspend fun artist(id: ArtistId): Outcome<ArtistDetail> =
        ask { s -> s.catalog?.takeIf { s.descriptor.id == id.sourceId }?.artist(id) }.remembering { it.tracks }

    override suspend fun relatedArtists(id: ArtistId): Outcome<List<ArtistSummary>> =
        ask { s -> s.recommendations?.takeIf { s.descriptor.id == id.sourceId }?.relatedArtists(id, RELATED_ARTISTS) }

    override suspend fun collection(id: PlaylistId): Outcome<PlaylistDetail> =
        ask { s -> s.catalog?.takeIf { s.descriptor.id == id.sourceId }?.playlist(id) }.remembering { it.tracks }

    // --- The account's library ------------------------------------------------------------------------------

    override suspend fun libraryPlaylists(offset: Int, limit: Int): Outcome<List<PlaylistSummary>> =
        ask { it.accountLibrary?.playlists(offset, limit) }

    override suspend fun libraryAlbums(offset: Int, limit: Int): Outcome<List<AlbumSummary>> =
        ask { it.accountLibrary?.albums(offset, limit) }

    override suspend fun libraryArtists(offset: Int, limit: Int): Outcome<List<ArtistSummary>> =
        ask { it.accountLibrary?.artists(offset, limit) }

    override suspend fun accountHistory(offset: Int, limit: Int): Outcome<List<HistoryEntry>> =
        ask { it.accountLibrary?.history(offset, limit) }.remembering { list -> list.map { e -> e.track } }

    @Volatile private var likesFetchedAt = 0L to ""

    /** Counts likes written from here, so an answer fetched before a write never undoes it. */
    private val likeWrites = java.util.concurrent.atomic.AtomicLong()

    override fun refreshLibrary() {
        val key = accountKey.value
        if (key == OnlineLibraryStore.DEVICE) return
        val (at, forKey) = likesFetchedAt
        if (forKey == key && now() - at < LIKES_REFRESH_MS) return
        likesFetchedAt = now() to key
        val writesBefore = likeWrites.get()
        scope.launch {
            val liked = ask { it.accountLibrary?.likedSongs(0, MAX_LIKES) }
            // Only if the same account is still signed in, and nothing was liked meanwhile.
            if (liked is Outcome.Success && accountKey.value == key && likeWrites.get() == writesBefore) {
                store.replaceLikes(key, liked.value)
            } else {
                likesFetchedAt = 0L to ""
            }
        }
    }

    // --- Likes ----------------------------------------------------------------------------------------------

    private fun currentSourceOnly(tracks: List<Track>): List<Track> {
        val id = source()?.descriptor?.id ?: return emptyList()
        return tracks.filter { it.source.sourceId == id }
    }

    override val likedIds: StateFlow<Set<TrackId>> = accountKey
        .flatMapLatest { key -> store.likedIds(key) }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    override val likedTracks: Flow<List<Track>> = accountKey
        .flatMapLatest { key -> store.likedTracks(key) }
        .map { currentSourceOnly(it).also(catalog::remember) }

    override fun setLiked(track: Track, liked: Boolean) {
        val key = accountKey.value
        likeWrites.incrementAndGet()
        scope.launch {
            store.setLiked(track, liked, key)
            if (key == OnlineLibraryStore.DEVICE) return@launch
            val written = ask { s -> s.accountLibrary?.takeIf { it.canWriteLikes && s.descriptor.id == track.source.sourceId }?.setLiked(track, liked) }
            // The account didn't take it: put the cache back the way the account has it.
            if (written is Outcome.Failure && accountKey.value == key) store.setLiked(track, !liked, key)
        }
    }

    // --- Podium's own playlists (on this device) -------------------------------------------------------------

    override val playlists: Flow<List<OnlinePlaylist>> =
        store.playlists().map { list -> list.map { OnlinePlaylist(it.id, it.name, it.trackCount) } }

    override fun playlist(id: String): Flow<OnlinePlaylistContents?> = store.playlist(id).map { d ->
        d?.let {
            catalog.remember(it.entries.map { e -> e.track })
            OnlinePlaylistContents(OnlinePlaylist(it.info.id, it.info.name, it.info.trackCount), it.entries.map { e -> OnlinePlaylistEntry(e.entryId, e.track) })
        }
    }

    override suspend fun createPlaylist(name: String, tracks: List<Track>): String {
        val sourceId = source()?.descriptor?.id ?: tracks.firstOrNull()?.source?.sourceId ?: SourceId("online")
        return store.createPlaylist(name, sourceId, tracks)
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

    // --- What Podium played -----------------------------------------------------------------------------------

    override val recentlyPlayed: Flow<List<Track>> = accountKey
        .flatMapLatest { key -> store.recentlyPlayed(account = key) }
        .map { currentSourceOnly(it).also(catalog::remember) }

    override fun clearHistory() {
        val key = accountKey.value
        scope.launch { store.clearHistory(key) }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
        const val SHELVES_CACHE_MS = 10 * 60 * 1000L
        const val LIKES_REFRESH_MS = 2 * 60 * 1000L
        const val MAX_LIKES = 500
        const val RELATED_ARTISTS = 10
    }
}
