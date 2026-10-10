package app.podium

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.podium.core.common.ManualClock
import app.podium.core.common.Outcome
import app.podium.core.common.PodiumError
import app.podium.core.database.DatabaseFavorites
import app.podium.core.database.OnlineLibraryStore
import app.podium.core.database.PodiumDatabase
import app.podium.core.model.AlbumId
import app.podium.core.model.ArtistId
import app.podium.core.model.PlaybackRoute
import app.podium.core.model.SourceId
import app.podium.core.model.SourceRef
import app.podium.core.model.Track
import app.podium.core.model.TrackId
import app.podium.feature.online.AccountState
import app.podium.player.api.TrackCatalog
import app.podium.sources.api.AccountLibraryFacet
import app.podium.sources.api.AlbumDetail
import app.podium.sources.api.AlbumSummary
import app.podium.sources.api.ArtistDetail
import app.podium.sources.api.ArtistSummary
import app.podium.sources.api.AuthFacet
import app.podium.sources.api.AuthState
import app.podium.sources.api.Basis
import app.podium.sources.api.Capability
import app.podium.sources.api.CatalogFacet
import app.podium.sources.api.HistoryEntry
import app.podium.sources.api.MusicEnvironment
import app.podium.sources.api.MusicSource
import app.podium.sources.api.PlaylistSummary
import app.podium.sources.api.SearchQuery
import app.podium.sources.api.SearchResults
import app.podium.sources.api.SourceCapabilities
import app.podium.sources.api.SourceDescriptor
import app.podium.sources.api.SourceHealth
import app.podium.sources.api.SourceHealthMonitor
import app.podium.sources.api.SourceRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** ONLINE's repository over a real in-memory database and a scriptable signed-in service. */
@RunWith(AndroidJUnit4::class)
class OnlineMusicRepositoryTest {

    private val service = SourceId("svc")

    private fun song(key: String, title: String = key) = Track(
        id = TrackId.of(service, key),
        source = SourceRef(service, key),
        title = title,
        artists = emptyList(),
        routes = setOf(PlaybackRoute.REMOTE),
    )

    /** A service with an account library; its account's likes live in [accountLikes]. */
    private inner class Service : MusicSource {
        val auth0 = MutableStateFlow<AuthState>(AuthState.SignedOut)
        val accountLikes = mutableListOf<Track>()
        var likeWrites = mutableListOf<Pair<TrackId, Boolean>>()
        var failWrites = false
        var searchAnswer: Outcome<SearchResults> = Outcome.Success(SearchResults.Empty)

        override val descriptor = SourceDescriptor(service, "Service", "Service", Basis.UNOFFICIAL_API, environment = MusicEnvironment.ONLINE)
        override val capabilities: StateFlow<SourceCapabilities> = MutableStateFlow(
            SourceCapabilities.available(Capability.SEARCH, Capability.BROWSE, Capability.ACCOUNT_LIBRARY, Capability.HISTORY, Capability.LIKES),
        )
        override val auth = object : AuthFacet {
            override val state: StateFlow<AuthState> = auth0
            override suspend fun signOut() {
                auth0.value = AuthState.SignedOut
            }
        }
        override val catalog = object : CatalogFacet {
            override suspend fun search(query: SearchQuery) = searchAnswer
            override suspend fun track(ref: SourceRef): Outcome<Track> = Outcome.Failure(PodiumError.NotFound("t"))
            override suspend fun album(id: AlbumId): Outcome<AlbumDetail> = Outcome.Failure(PodiumError.NotFound("a"))
            override suspend fun artist(id: ArtistId): Outcome<ArtistDetail> = Outcome.Failure(PodiumError.NotFound("a"))
        }
        override val accountLibrary = object : AccountLibraryFacet {
            override suspend fun likedSongs(offset: Int, limit: Int): Outcome<List<Track>> = Outcome.Success(accountLikes.drop(offset).take(limit))
            override suspend fun playlists(offset: Int, limit: Int): Outcome<List<PlaylistSummary>> = Outcome.Success(emptyList())
            override suspend fun albums(offset: Int, limit: Int): Outcome<List<AlbumSummary>> = Outcome.Success(emptyList())
            override suspend fun artists(offset: Int, limit: Int): Outcome<List<ArtistSummary>> = Outcome.Success(emptyList())
            override suspend fun history(offset: Int, limit: Int): Outcome<List<HistoryEntry>> = Outcome.Success(emptyList())
            override val canWriteLikes = true
            override suspend fun setLiked(track: Track, liked: Boolean): Outcome<Unit> {
                likeWrites += track.id to liked
                if (failWrites) return Outcome.Failure(PodiumError.Network())
                if (liked) accountLikes.add(0, track) else accountLikes.removeAll { it.id == track.id }
                return Outcome.Success(Unit)
            }
        }
    }

    private val db = PodiumDatabase.createInMemory(ApplicationProvider.getApplicationContext())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val health = SourceHealthMonitor(ManualClock())
    private val registry = SourceRegistry(health)
    private val source = Service().also { registry.register(it) }
    // Open the database (and create its schema) before anything runs concurrently on it.
    private val store = OnlineLibraryStore(db).also { runBlocking { it.likeCount() } }
    private val repository = OnlineMusicRepository(registry, health, store, TrackCatalog(registry), scope)

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun <T> eventually(block: suspend () -> T?): T = runBlocking { withTimeout(5_000) { var v: T? = block(); while (v == null) { kotlinx.coroutines.delay(20); v = block() }; v } }

    @Test
    fun `signed in, liked songs are the account's, fetched into a cache of their own`() {
        source.accountLikes += listOf(song("a"), song("b"))
        source.auth0.value = AuthState.SignedIn("Listener", "acct-1")
        val liked = eventually { runBlocking { repository.likedTracks.first() }.takeIf { it.size == 2 } }
        assertEquals(listOf("a", "b"), liked.map { it.title })
        assertEquals(AccountState.SIGNED_IN, repository.status.value?.account?.state)
        assertEquals("Listener", repository.status.value?.account?.name)
        runBlocking { assertEquals(2, store.likeCount("acct-1")) }
    }

    @Test
    fun `a like writes through to the account, and a refused write is undone`() {
        source.auth0.value = AuthState.SignedIn("Listener", "acct-1")
        eventually { repository.accountKey.value.takeIf { it == "acct-1" } }
        repository.setLiked(song("c"), true)
        eventually { source.likeWrites.takeIf { it.isNotEmpty() } }
        assertEquals(listOf(TrackId.of(service, "c") to true), source.likeWrites)
        eventually { repository.likedIds.value.takeIf { TrackId.of(service, "c") in it } }

        source.failWrites = true
        repository.setLiked(song("d"), true)
        eventually { source.likeWrites.takeIf { it.size == 2 } }
        // The cache goes back to what the account has.
        eventually { runBlocking { store.likedIds("acct-1").first() }.takeIf { TrackId.of(service, "d") !in it } }
    }

    @Test
    fun `signing out removes that account's things and leaves local favorites and other accounts alone`() = runBlocking {
        source.accountLikes += song("a")
        source.auth0.value = AuthState.SignedIn("Listener", "acct-1")
        eventually { runBlocking { store.likeCount("acct-1") }.takeIf { it == 1 } }
        store.setLiked(song("other"), true, account = "acct-2")
        store.record(song("heard"), startedAt = 1, playedMs = 10_000, account = "acct-1")
        val favorites = DatabaseFavorites(db, scope)
        favorites.toggle(TrackId("local|song"))
        eventually { runBlocking { db.likes().likedIds().first() }.takeIf { it.isNotEmpty() } }

        repository.signOut()

        assertEquals(AuthState.SignedOut, source.auth0.value)
        assertEquals(0, store.likeCount("acct-1"))
        assertTrue(store.listens(account = "acct-1").isEmpty())
        assertEquals(1, store.likeCount("acct-2"))
        assertEquals(listOf("local|song"), db.likes().likedIds().first())
        // Signed out, what shows is the device's own (empty) online library, not the account's.
        eventually { repository.accountKey.value.takeIf { it == OnlineLibraryStore.DEVICE } }
        assertTrue(repository.likedTracks.first().isEmpty())
    }

    @Test
    fun `a different account never sees the first one's likes`() {
        source.accountLikes += song("a")
        source.auth0.value = AuthState.SignedIn("First", "acct-1")
        eventually { runBlocking { store.likeCount("acct-1") }.takeIf { it == 1 } }
        source.accountLikes.clear()
        source.accountLikes += song("z")
        source.auth0.value = AuthState.SignedIn("Second", "acct-2")
        val liked = eventually { runBlocking { repository.likedTracks.first() }.takeIf { it.singleOrNull()?.title == "z" } }
        assertEquals(listOf("z"), liked.map { it.title })
    }

    @Test
    fun `a miss doesn't hurt the service's health, failures do, offline counts against no one`() = runBlocking {
        source.searchAnswer = Outcome.Failure(PodiumError.NotFound("nothing"))
        repeat(5) { repository.search("x", 0, 10).first() }
        assertEquals(SourceHealth.Healthy, health.health(service))
        source.searchAnswer = Outcome.Failure(PodiumError.Offline)
        repeat(5) { repository.search("x", 0, 10).first() }
        assertEquals(SourceHealth.Healthy, health.health(service))
        source.searchAnswer = Outcome.Failure(PodiumError.Server(503))
        repeat(3) { repository.search("x", 0, 10).first() }
        assertIs<SourceHealth.Unreachable>(health.health(service))
        // While resting, nothing is asked and the answer says why.
        assertIs<PodiumError.Network>((repository.search("x", 0, 10).first() as Outcome.Failure).error)
        Unit
    }

    @Test
    fun `online music off is said plainly`() = runBlocking {
        registry.setEnabled(service, false)
        assertIs<PodiumError.PolicyDisabled>((repository.search("x", 0, 10).first() as Outcome.Failure).error)
        Unit
    }

    @Test
    fun `cold start with already signed-in source does not throw NullPointerException during construction`() {
        val authRegistry = SourceRegistry(SourceHealthMonitor(ManualClock()))
        val signedInSource = Service().apply {
            auth0.value = AuthState.SignedIn("ExistingUser", "acct-existing")
            accountLikes += listOf(song("saved-song"))
        }
        authRegistry.register(signedInSource)

        // Using Dispatchers.Main.immediate (same as AppGraph.appScope in production)
        val immediateScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val repo = OnlineMusicRepository(
                registry = authRegistry,
                health = SourceHealthMonitor(ManualClock()),
                store = store,
                catalog = TrackCatalog(authRegistry),
                scope = immediateScope,
            )
            assertEquals("acct-existing", repo.accountKey.value)
            assertEquals(AccountState.SIGNED_IN, repo.status.value?.account?.state)
            assertEquals("ExistingUser", repo.status.value?.account?.name)
        } finally {
            immediateScope.cancel()
        }
    }
}
