package app.podium

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.podium.core.common.Clock
import app.podium.core.common.Outcome
import app.podium.core.database.OnlineLibraryStore
import app.podium.core.database.PodiumDatabase
import app.podium.core.model.SourceId
import app.podium.player.api.TrackCatalog
import app.podium.sources.api.Capability
import app.podium.sources.api.InMemorySourcePreferencesStore
import app.podium.sources.api.MusicEnvironment
import app.podium.sources.api.SourceHealthMonitor
import app.podium.sources.api.SourceRegistry
import app.podium.sources.api.SourceSettings
import app.podium.sources.api.aggregate.MultiSourceCatalog
import app.podium.sources.api.aggregate.SourceFanOut
import app.podium.sources.api.aggregate.TrackGrouper
import app.podium.sources.api.resolve.InMemoryEquivalenceStore
import app.podium.sources.testing.FakeMusicSource
import app.podium.sources.testing.FakeOnlineMusicSource
import app.podium.sources.testing.FakeOnlineMusicSource.Behavior
import app.podium.sources.testing.track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ONLINE over a real in-memory database and two scriptable online sources (D-35). */
@RunWith(AndroidJUnit4::class)
class AppOnlineRepositoryTest {

    private val db = PodiumDatabase.createInMemory(ApplicationProvider.getApplicationContext())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val health = SourceHealthMonitor(Clock.System)
    private val registry = SourceRegistry(health)
    private val equivalence = InMemoryEquivalenceStore()
    private val store = OnlineLibraryStore(db)
    private val catalog = TrackCatalog(registry)

    private val a = FakeOnlineMusicSource("a").apply {
        track("Song Alpha")
        track("Song Shared", artist = "Band", key = "shared-a")
    }
    private val b = FakeOnlineMusicSource("b").apply {
        track("Song Gamma")
        track("Song Shared", artist = "Band", key = "shared-b", durationSec = 201.0)
    }

    init {
        registry.register(FakeMusicSource("local", listOf(track("Local", source = "local"))))
        registry.register(a)
        registry.register(b)
    }

    private val repository = AppOnlineRepository(
        registry,
        MultiSourceCatalog(registry, MusicEnvironment.ONLINE, SourceFanOut(health, 2_000), TrackGrouper(), equivalence),
        store, catalog, scope,
    )

    @After
    fun close() {
        scope.cancel()
        db.close()
    }

    private fun <T> run(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    @Test
    fun `search shows one song per recording, never naming a source, and remembers every copy`() = run {
        val results = assertIs<Outcome.Success<app.podium.sources.api.SearchResults>>(repository.search("song", 0, 20).last()).value
        assertEquals(listOf("Song Alpha", "Song Gamma", "Song Shared"), results.tracks.map { it.title })
        val shared = results.tracks.last()
        assertEquals(a.sourceId, shared.source.sourceId)
        assertEquals(listOf(b.sourceId), equivalence.exactEquivalents(shared).map { it.source.sourceId })
        assertTrue(results.tracks.all { catalog.cached(it.id) != null }, "every shown song can be played by id")
    }

    @Test
    fun `while a source is still answering, nothing is shown rather than nothing found`() = run {
        a.searchBehavior = Behavior.Slow(300)
        b.searchBehavior = Behavior.Miss
        val emissions = repository.search("song", 0, 20).toList()
        assertEquals(1, emissions.size, "the empty partial view is held back")
        assertEquals(2, (emissions.single() as Outcome.Success).value.tracks.size)
    }

    @Test
    fun `when no source can answer, search says why`() = run {
        a.searchBehavior = Behavior.Fail(app.podium.core.common.PodiumError.Offline)
        b.searchBehavior = Behavior.Fail(app.podium.core.common.PodiumError.Offline)
        assertEquals(app.podium.core.common.PodiumError.Offline, assertIs<Outcome.Failure>(repository.search("song", 0, 20).last()).error)
    }

    @Test
    fun `status reflects every enabled online source, and disappears when none is on`() = run {
        assertTrue(repository.status.first { it != null }!!.canSearch)
        val settings = SourceSettings(registry, InMemorySourcePreferencesStore())
        settings.setEnabled(a.sourceId, false)
        settings.setEnabled(b.sourceId, false)
        assertNull(repository.status.first { it == null })
        settings.setEnabled(b.sourceId, true)
        assertTrue(repository.status.first { it != null }!!.canBrowse)
    }

    @Test
    fun `an artist and a playlist open on their own source`() = run {
        assertEquals(b.artistName, assertIs<Outcome.Success<app.podium.sources.api.ArtistDetail>>(repository.artist(b.artistId)).value.summary.name)
        assertEquals("Mix of b", assertIs<Outcome.Success<app.podium.sources.api.PlaylistDetail>>(repository.collection(b.playlistId)).value.summary.title)
    }

    @Test
    fun `likes stay with their own copy, and the liked list shows each recording once`() = run {
        val onA = a.tracks.first { it.title == "Song Shared" }
        val onB = b.tracks.first { it.title == "Song Shared" }
        repository.setLiked(onA, true)
        repository.setLiked(onB, true)
        repository.setLiked(b.tracks.first { it.title == "Song Gamma" }, true)
        val ids = repository.likedIds.first { it.size == 3 }
        assertEquals(setOf(onA.id, onB.id, b.tracks.first().id), ids, "two likes, one per copy")
        val liked = repository.likedTracks.first { it.size == 2 }
        assertEquals(1, liked.count { it.title == "Song Shared" })
    }

    @Test
    fun `history shows a song played from two sources once`() = run {
        val onA = a.tracks.first { it.title == "Song Shared" }
        val onB = b.tracks.first { it.title == "Song Shared" }
        store.record(onA, startedAt = 1, playedMs = 60_000)
        store.record(onB, startedAt = 2, playedMs = 60_000)
        store.record(a.tracks.first(), startedAt = 3, playedMs = 60_000)
        val recent = repository.recentlyPlayed.first { it.isNotEmpty() }
        assertEquals(listOf("Song Alpha", "Song Shared"), recent.map { it.title })
        assertEquals(b.sourceId, recent.last().source.sourceId, "the latest listen's copy")
        assertEquals(3, store.listens().size, "every listen is still recorded with its own source")
    }

    @Test
    fun `a new playlist keeps every song's own source`() = run {
        val id = repository.createPlaylist("Mixed", listOf(a.tracks.first(), b.tracks.first()))
        val contents = repository.playlist(id).first { it != null }!!
        assertEquals(listOf(a.sourceId, b.sourceId), contents.entries.map { it.track.source.sourceId })
    }

    @Test
    fun `radio is offered from a song when its source or its twin's recommends`() = run {
        a.withdraw(Capability.RECOMMENDATIONS)
        val shared = (repository.search("Song Shared", 0, 20).last() as Outcome.Success).value.tracks.single()
        assertTrue(repository.canStartRadio(shared))
        assertTrue(!repository.canStartRadio(a.tracks.first()))
        assertTrue(repository.canRelate(b.artistId))
        assertTrue(!repository.canRelate(a.artistId))
    }

    @Test
    fun `shelves and genres come from every source`() = run {
        val shelves = assertIs<Outcome.Success<List<app.podium.sources.api.Shelf>>>(repository.shelves()).value
        assertEquals(listOf("Trending", "Only on a", "Only on b"), shelves.map { it.title })
        val page = assertIs<Outcome.Success<app.podium.sources.api.ShelfPage>>(repository.shelf(shelves.first().id, 0, 10)).value
        assertTrue(page.tracks.map { it.source.sourceId }.toSet().containsAll(listOf(a.sourceId, b.sourceId)))
        assertEquals(listOf("Electronic", "Pop a", "Pop b"), (repository.genres() as Outcome.Success).value)
    }

    @Test
    fun `the same provider key on two sources stays two songs everywhere`() = run {
        val x = a.track("Collide", artist = "One", key = "123")
        val y = b.track("Different", artist = "Two", key = "123")
        repository.setLiked(x, true)
        repository.setLiked(y, true)
        assertEquals(setOf(x.id, y.id), repository.likedIds.first { it.size == 2 })
        assertTrue(x.id != y.id)
        assertEquals(setOf(SourceId("a"), SourceId("b")), repository.likedTracks.first { it.size == 2 }.map { it.source.sourceId }.toSet())
    }
}
