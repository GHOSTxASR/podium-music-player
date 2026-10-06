package app.podium.sources.api.aggregate

import app.podium.core.common.ManualClock
import app.podium.core.common.Outcome
import app.podium.core.common.PodiumError
import app.podium.core.model.ArtistId
import app.podium.core.model.PlaylistId
import app.podium.sources.api.MusicEnvironment
import app.podium.sources.api.SearchQuery
import app.podium.sources.api.SourceHealthMonitor
import app.podium.sources.api.SourceRegistry
import app.podium.sources.api.resolve.InMemoryEquivalenceStore
import app.podium.sources.testing.FakeMusicSource
import app.podium.sources.testing.FakeOnlineMusicSource
import app.podium.sources.testing.FakeOnlineMusicSource.Behavior
import app.podium.sources.testing.track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MultiSourceCatalogTest {
    private val clock = ManualClock(1_000_000)
    private val health = SourceHealthMonitor(clock)
    private val registry = SourceRegistry(health)
    private val equivalence = InMemoryEquivalenceStore()

    /** Source A: "Song Alpha", "Song Beta", "Song Shared". Source B: "Song Gamma", "Song Shared". */
    private val a = FakeOnlineMusicSource("a").apply {
        track("Song Alpha")
        track("Song Beta")
        track("Song Shared", artist = "Band", key = "shared-on-a")
    }
    private val b = FakeOnlineMusicSource("b").apply {
        track("Song Gamma")
        track("Song Shared", artist = "Band", key = "shared-on-b", durationSec = 201.0)
    }

    private fun TestScope.catalog(timeout: Long = 8_000): MultiSourceCatalog {
        if (registry.get(a.sourceId) == null) registry.register(a)
        if (registry.get(b.sourceId) == null) registry.register(b)
        val fanOut = SourceFanOut(health, timeout, CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))
        return MultiSourceCatalog(registry, MusicEnvironment.ONLINE, fanOut, TrackGrouper(), equivalence)
    }

    private suspend fun MultiSourceCatalog.searchAll(text: String, offset: Int = 0) = search(SearchQuery(text, limit = 20, offset = offset)).last()

    @Test
    fun `one song on two sources is one result, with both copies kept`() = runTest {
        val result = catalog().searchAll("Song Shared")
        val row = result.groups.single()
        assertEquals(a.sourceId, row.track.source.sourceId, "the preferred source's copy is shown")
        assertEquals(listOf(b.sourceId), row.alternates.map { it.source.sourceId })
        assertEquals(listOf(row.track), result.results.tracks)
        assertEquals(row.alternates, equivalence.exactEquivalents(row.track), "the resolver knows the other copy")
        assertTrue(result.complete)
        assertNull(result.error)
    }

    @Test
    fun `priority is a preference, never a filter, the lower source's songs stay visible`() = runTest {
        val c = catalog()
        registry.setPriority(listOf(a.sourceId, b.sourceId))
        val titles = c.searchAll("song").results.tracks.map { it.title }
        assertTrue("Song Gamma" in titles, "only b has it, and b is second")
        registry.setPriority(listOf(b.sourceId, a.sourceId))
        assertTrue("Song Alpha" in c.searchAll("song").results.tracks.map { it.title }, "only a has it, and a is now second")
    }

    @Test
    fun `search unifies every source, interleaved by rank and priority`() = runTest {
        val result = catalog().searchAll("song")
        assertEquals(listOf("Song Alpha", "Song Gamma", "Song Beta", "Song Shared"), result.results.tracks.map { it.title })
        assertEquals("a", result.results.tracks.last().source.sourceId.value)
    }

    @Test
    fun `priority decides which copy is shown and which source leads`() = runTest {
        val c = catalog()
        registry.setPriority(listOf(b.sourceId, a.sourceId))
        val result = c.searchAll("song")
        assertEquals(listOf("Song Gamma", "Song Alpha", "Song Shared", "Song Beta"), result.results.tracks.map { it.title })
        assertEquals(b.sourceId, result.groups.first { it.track.title == "Song Shared" }.track.source.sourceId)
    }

    @Test
    fun `a source with unique songs adds them without duplicates`() = runTest {
        val titles = catalog().searchAll("song").results.tracks.map { it.title }
        assertEquals(titles.distinct(), titles)
        assertTrue("Song Gamma" in titles)
    }

    @Test
    fun `one source missing doesn't hide the others`() = runTest {
        a.searchBehavior = Behavior.Miss
        val result = catalog().searchAll("song")
        assertEquals(listOf("Song Gamma", "Song Shared"), result.results.tracks.map { it.title })
        assertNull(result.error)
    }

    @Test
    fun `one source failing doesn't hide the others`() = runTest {
        a.searchBehavior = Behavior.Fail(PodiumError.Server(500))
        val result = catalog().searchAll("song")
        assertEquals(listOf("Song Gamma", "Song Shared"), result.results.tracks.map { it.title })
        assertNull(result.error)
    }

    @Test
    fun `when every source fails the reason is given, offline first`() = runTest {
        a.searchBehavior = Behavior.Fail(PodiumError.Server(500))
        b.searchBehavior = Behavior.Fail(PodiumError.Offline)
        val result = catalog().searchAll("song")
        assertEquals(PodiumError.Offline, result.error)
        assertTrue(result.results.tracks.isEmpty())
    }

    @Test
    fun `a source that hangs times out and search still answers`() = runTest {
        a.searchBehavior = Behavior.Hang()
        val result = catalog(timeout = 3_000).searchAll("song")
        assertEquals(listOf("Song Gamma", "Song Shared"), result.results.tracks.map { it.title })
        assertTrue(testScheduler.currentTime <= 3_000)
    }

    @Test
    fun `results arrive as sources answer, ending complete`() = runTest {
        a.searchBehavior = Behavior.Slow(2_000)
        val views = catalog().search(SearchQuery("song")).toList()
        assertEquals(2, views.size)
        assertEquals(1, views[0].pending)
        assertEquals(listOf("Song Gamma", "Song Shared"), views[0].results.tracks.map { it.title }, "the fast source shows first")
        assertTrue(views[1].complete)
        assertEquals(4, views[1].results.tracks.size)
    }

    @Test
    fun `a disabled source isn't asked`() = runTest {
        val c = catalog()
        registry.setEnabled(b.sourceId, false)
        val result = c.searchAll("song")
        assertTrue(result.results.tracks.all { it.source.sourceId == a.sourceId })
        assertEquals(0, b.searchCalls)
    }

    @Test
    fun `a local source never answers an online search`() = runTest {
        val local = FakeMusicSource("local", listOf(track("Song Local", source = "local")))
        registry.register(local)
        val result = catalog().searchAll("song")
        assertTrue(result.results.tracks.none { it.source.sourceId == local.sourceId })
        assertEquals(0, local.searchCalls)
    }

    @Test
    fun `a newer search replaces an older one, never the reverse`() = runTest {
        a.searchBehavior = Behavior.Slow(5_000)
        b.searchBehavior = Behavior.Slow(5_000)
        val c = catalog()
        val shown = mutableListOf<String>()
        flow {
            emit("song alpha")
            delay(100)
            emit("song gamma")
        }.collectLatest { text -> c.search(SearchQuery(text)).collect { view -> shown += view.results.tracks.map { it.title } } }
        assertEquals(listOf("Song Gamma"), shown.distinct(), "nothing from the stale search ever arrives")
    }

    @Test
    fun `more songs continues each source where it stopped, and shown songs stay once`() = runTest {
        a.searchBehavior = Behavior.Answer
        val c = catalog()
        val first = c.search(SearchQuery("song", limit = 1)).last()
        assertEquals(listOf("Song Alpha", "Song Gamma"), first.results.tracks.map { it.title })
        val second = c.search(SearchQuery("song", limit = 1, offset = 2)).last()
        assertEquals(listOf(1, 1), listOf(a.searchOffsets.last(), b.searchOffsets.last()), "each source continues from its own place")
        assertEquals(listOf("Song Beta", "Song Shared"), second.results.tracks.map { it.title })
        val third = c.search(SearchQuery("song", limit = 1, offset = 4)).last()
        assertTrue(third.results.tracks.none { it.title == "Song Shared" }, "the other copy joins the shown row instead of repeating it")
    }

    @Test
    fun `an artist opens on the source it came from`() = runTest {
        val c = catalog()
        val detail = assertIs<Outcome.Success<app.podium.sources.api.ArtistDetail>>(c.artist(b.artistId)).value
        assertEquals(b.artistName, detail.summary.name)
        assertTrue(detail.tracks.all { it.source.sourceId == b.sourceId })
    }

    @Test
    fun `a playlist opens on the source it came from, even when another is preferred`() = runTest {
        val c = catalog()
        val detail = assertIs<Outcome.Success<app.podium.sources.api.PlaylistDetail>>(c.playlist(b.playlistId)).value
        assertEquals("Mix of b", detail.summary.title)
        val sameKeyOnA = assertIs<Outcome.Success<app.podium.sources.api.PlaylistDetail>>(c.playlist(PlaylistId.of(a.sourceId, b.playlistId.providerKey))).value
        assertEquals("Mix of a", sameKeyOnA.summary.title, "the same key on another source is another playlist")
    }

    @Test
    fun `an album opens on the source it came from`() = runTest {
        val detail = assertIs<Outcome.Success<app.podium.sources.api.AlbumDetail>>(catalog().album(app.podium.core.model.AlbumId.of(b.sourceId, "x"))).value
        assertEquals("Album of b", detail.summary.title)
    }

    @Test
    fun `something from a source that is turned off says so`() = runTest {
        val c = catalog()
        registry.setEnabled(b.sourceId, false)
        assertIs<PodiumError.PolicyDisabled>(assertIs<Outcome.Failure>(c.artist(b.artistId)).error)
    }

    @Test
    fun `an unknown source is not found, never another source's`() = runTest {
        assertIs<PodiumError.NotFound>(assertIs<Outcome.Failure>(catalog().artist(ArtistId.of(app.podium.core.model.SourceId("gone"), "artist"))).error)
    }

    @Test
    fun `shelves of the same name merge, others stay their own`() = runTest {
        val shelves = assertIs<Outcome.Success<List<app.podium.sources.api.Shelf>>>(catalog().shelves()).value
        assertEquals(listOf("Trending", "Only on a", "Only on b"), shelves.map { it.title })
        val trending = shelves.first()
        assertEquals(2, ShelfIds.decode(trending.id).size, "the merged shelf remembers both sources")
        assertEquals(listOf("Song Alpha", "Song Gamma", "Song Beta", "Song Shared"), trending.tracks.map { it.title })
    }

    @Test
    fun `a merged shelf pages each source from its own place`() = runTest {
        val c = catalog()
        val trending = (c.shelves() as Outcome.Success).value.first()
        val first = assertIs<Outcome.Success<app.podium.sources.api.ShelfPage>>(c.shelf(trending.id, 0, 2)).value
        assertEquals(listOf("Song Alpha", "Song Gamma", "Song Beta", "Song Shared"), first.tracks.map { it.title })
        val second = assertIs<Outcome.Success<app.podium.sources.api.ShelfPage>>(c.shelf(trending.id, 4, 2)).value
        assertTrue(second.tracks.none { it.title == "Song Shared" }, "A's copy joins the shown row")
    }

    @Test
    fun `genres appear once, and a genre asks only the sources that have it`() = runTest {
        val c = catalog()
        val genres = assertIs<Outcome.Success<List<String>>>(c.genres()).value
        assertEquals(listOf("Electronic", "Pop a", "Pop b"), genres)
        assertIs<Outcome.Success<List<app.podium.core.model.Track>>>(c.genre("Pop b", 0, 10))
        assertEquals(0, a.genreCalls)
        assertEquals(1, b.genreCalls)
        c.genre("Electronic", 0, 10)
        assertEquals(1, a.genreCalls, "a shared genre asks both")
    }

    @Test
    fun `no online source at all is said plainly`() = runTest {
        val empty = MultiSourceCatalog(
            SourceRegistry(health), MusicEnvironment.ONLINE,
            SourceFanOut(health, 1_000, CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))), TrackGrouper(), equivalence,
        )
        assertIs<PodiumError.NotFound>(empty.search(SearchQuery("x")).last().error)
        assertIs<Outcome.Failure>(empty.shelves())
    }

    @Test
    fun `radio is offered from a song whose source, or whose twin's source, recommends`() = runTest {
        val c = catalog()
        a.withdraw(app.podium.sources.api.Capability.RECOMMENDATIONS)
        val shared = c.searchAll("Song Shared").groups.single()
        assertEquals(a.sourceId, shared.track.source.sourceId)
        assertTrue(c.recommends(shared.track), "its copy on b recommends")
        val alpha = c.searchAll("Song Alpha").groups.single().track
        assertTrue(!c.recommends(alpha), "only a has it, and a doesn't recommend")
        assertTrue(c.recommends(b.artistId))
        assertTrue(!c.recommends(a.artistId))
    }
}
