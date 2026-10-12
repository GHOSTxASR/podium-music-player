package app.podium

import app.podium.core.model.AlbumId
import app.podium.core.model.AlbumRef
import app.podium.core.model.ArtistCredit
import app.podium.core.model.ArtistId
import app.podium.core.model.SourceId
import app.podium.core.model.SourceRef
import app.podium.core.model.Track
import app.podium.core.model.TrackId
import app.podium.core.common.Clock
import app.podium.core.common.Outcome
import app.podium.core.common.PodiumError
import app.podium.player.api.TrackCatalog
import app.podium.sources.api.AlbumDetail
import app.podium.sources.api.AlbumSummary
import app.podium.sources.api.ArtistDetail
import app.podium.sources.api.ArtistSummary
import app.podium.sources.api.Basis
import app.podium.sources.api.Capability
import app.podium.sources.api.CatalogFacet
import app.podium.sources.api.MusicSource
import app.podium.sources.api.SearchKind
import app.podium.sources.api.SearchQuery
import app.podium.sources.api.SearchResults
import app.podium.sources.api.SourceCapabilities
import app.podium.sources.api.SourceDescriptor
import app.podium.sources.api.SourceHealthMonitor
import app.podium.sources.api.SourceRegistry
import app.podium.sources.testing.FakeMusicSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Filling in a song's album and artist pages from its own details (D-79), dependably (D-80). */
class SongPagesTest {
    private val s = SourceId("online")

    private fun song(album: AlbumRef?, vararg artists: ArtistCredit) = Track(
        id = TrackId.of(s, "v1"), source = SourceRef(s, "v1"), title = "After Hours", artists = artists.toList(), album = album,
    )

    private val weeknd = ArtistCredit("The Weeknd", id = ArtistId.of(s, "UC1"))
    private val album = AlbumRef("After Hours", AlbumId.of(s, "MPREb_1"))

    @Test
    fun `a song listed without pages gets them from its own details`() {
        val listed = song(AlbumRef("After Hours"), ArtistCredit("The Weeknd"))
        val filled = SongPages.withPages(listed, song(album, weeknd))
        assertEquals(album.id, filled.album?.id)
        assertEquals("After Hours", filled.album?.title)
        assertEquals(weeknd.id, filled.artists.single().id)
        assertTrue(SongPages.hasPages(filled))
        assertFalse(SongPages.hasPages(listed))
    }

    @Test
    fun `one line naming two artists gives way to their own credits`() {
        val daft = ArtistCredit("Daft Punk", id = ArtistId.of(s, "UC2"))
        val listed = song(null, ArtistCredit("The Weeknd & Daft Punk"))
        assertEquals(listOf(weeknd, daft), SongPages.withPages(listed, song(album, weeknd, daft)).artists)
    }

    @Test
    fun `what the song already links stays, and nothing new leaves it as it was`() {
        val other = ArtistCredit("The Weeknd", id = ArtistId.of(s, "UC9"))
        val linked = song(album, weeknd)
        assertEquals(linked, SongPages.withPages(linked, song(AlbumRef("Else", AlbumId.of(s, "MPREb_9")), other)))
        val bare = song(null, ArtistCredit("Someone"))
        assertSame(bare, SongPages.withPages(bare, null))
        assertEquals(bare, SongPages.withPages(bare, song(null, ArtistCredit("Someone"))))
    }

    @Test
    fun `a song cached without pages is looked up on its source once, and remembered filled in`() = runTest {
        val registry = SourceRegistry(SourceHealthMonitor(Clock.System))
        registry.register(FakeMusicSource("online", tracks = listOf(song(album, weeknd))))
        val catalog = TrackCatalog(registry)
        catalog.remember(listOf(song(AlbumRef("After Hours"), ArtistCredit("The Weeknd"))))
        val pages = SongPages(catalog, registry, backgroundScope)
        val found = pages.of(TrackId.of(s, "v1"))!!
        assertTrue(SongPages.hasPages(found))
        assertTrue(SongPages.hasPages(catalog.cached(TrackId.of(s, "v1"))!!), "remembered")
    }

    // --- D-80: the lookup holds up on the phone ---------------------------------------------------------

    private val listed = song(AlbumRef("After Hours"), ArtistCredit("The Weeknd"))
    private val id = TrackId.of(s, "v1")

    private fun setUp(source: ScriptedSource): Pair<TrackCatalog, SourceRegistry> {
        val registry = SourceRegistry(SourceHealthMonitor(Clock.System))
        registry.register(source)
        return TrackCatalog(registry).also { it.remember(listOf(listed)) } to registry
    }

    @Test
    fun `a lookup the screen gave up on still finishes, and the next ask has the pages without asking again`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source = ScriptedSource(details = { gate.await(); Outcome.Success(song(album, weeknd)) })
        val (catalog, registry) = setUp(source)
        val pages = SongPages(catalog, registry, backgroundScope)
        val screen = launch { pages.of(id) }
        runCurrent()
        screen.cancel() // e.g. the song changed, or Now Playing closed, while the lookup was out
        gate.complete(Unit)
        runCurrent()
        assertTrue(SongPages.hasPages(pages.of(id)!!))
        assertEquals(1, source.detailCalls)
    }

    @Test
    fun `two screens asking at once share one lookup`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source = ScriptedSource(details = { gate.await(); Outcome.Success(song(album, weeknd)) })
        val (catalog, registry) = setUp(source)
        val pages = SongPages(catalog, registry, backgroundScope)
        val first = async { pages.of(id) }
        val second = async { pages.of(id) }
        runCurrent()
        gate.complete(Unit)
        assertTrue(SongPages.hasPages(first.await()!!))
        assertTrue(SongPages.hasPages(second.await()!!), "the second waits for the lookup, not the bare song")
        assertEquals(1, source.detailCalls)
    }

    @Test
    fun `a lookup that failed is tried again`() = runTest {
        var online = false
        val source = ScriptedSource(
            details = { if (online) Outcome.Success(song(album, weeknd)) else Outcome.Failure(PodiumError.Network("offline")) },
            search = { if (online) Outcome.Success(SearchResults()) else Outcome.Failure(PodiumError.Network("offline")) },
        )
        val (catalog, registry) = setUp(source)
        val pages = SongPages(catalog, registry, backgroundScope)
        assertFalse(SongPages.hasPages(pages.of(id)!!))
        online = true
        assertTrue(SongPages.hasPages(pages.of(id)!!))
    }

    @Test
    fun `what was found holds when a list puts the song back without its pages`() = runTest {
        val source = ScriptedSource(details = { Outcome.Success(song(album, weeknd)) })
        val (catalog, registry) = setUp(source)
        val pages = SongPages(catalog, registry, backgroundScope)
        assertTrue(SongPages.hasPages(pages.of(id)!!))
        catalog.remember(listOf(listed)) // e.g. the liked songs list, read again from the database
        assertTrue(SongPages.hasPages(pages.of(id)!!))
        assertEquals(1, source.detailCalls)
    }

    @Test
    fun `pages the details leave out are found by exact name on the same source`() = runTest {
        val source = ScriptedSource(
            details = { Outcome.Success(listed) },
            search = { q ->
                Outcome.Success(
                    when (q.kinds.single()) {
                        SearchKind.ARTISTS -> SearchResults(artists = listOf(ArtistSummary(ArtistId.of(s, "UCx"), "The Weekend"), ArtistSummary(weeknd.id!!, "The Weeknd")))
                        SearchKind.ALBUMS -> SearchResults(
                            albums = listOf(
                                AlbumSummary(AlbumId.of(s, "MPREb_deluxe"), "After Hours (Deluxe)", "The Weeknd"),
                                AlbumSummary(AlbumId.of(s, "MPREb_tribute"), "After Hours", "Tribute Band"),
                                AlbumSummary(album.id!!, "After Hours", "The Weeknd"),
                            ),
                        )
                        else -> SearchResults()
                    },
                )
            },
        )
        val (catalog, registry) = setUp(source)
        val found = SongPages(catalog, registry, backgroundScope).of(id)!!
        assertEquals(weeknd.id, found.artists.single().id)
        assertEquals(album.id, found.album?.id, "the exact title by the song's artist, not a deluxe edition or a tribute")
    }

    @Test
    fun `a line naming two artists becomes one credit each, only when both are found`() = runTest {
        val bieber = ArtistCredit("Justin Bieber", id = ArtistId.of(s, "UCjb"))
        val laroi = ArtistCredit("The Kid LAROI", id = ArtistId.of(s, "UCkl"))
        val known = mapOf("the kid laroi" to laroi, "justin bieber" to bieber)
        fun source(vararg names: String) = ScriptedSource(
            details = { Outcome.Failure(PodiumError.NotFound("song")) },
            search = { q ->
                val hit = known[q.text.lowercase()]?.takeIf { it.name in names }
                Outcome.Success(SearchResults(artists = listOfNotNull(hit?.let { ArtistSummary(it.id!!, it.name) })))
            },
        )
        val line = song(null, ArtistCredit("The Kid LAROI & Justin Bieber"))

        val (catalog, registry) = setUp(source("The Kid LAROI", "Justin Bieber"))
        catalog.remember(listOf(line))
        assertEquals(listOf(laroi, bieber), SongPages(catalog, registry, backgroundScope).of(id)!!.artists)

        val (catalog2, registry2) = setUp(source("The Kid LAROI"))
        catalog2.remember(listOf(line))
        assertEquals(line.artists, SongPages(catalog2, registry2, backgroundScope).of(id)!!.artists, "never half a line")
    }

    /** A source whose details and search answer as each test says, counting the details asked for. */
    private inner class ScriptedSource(
        private val details: suspend () -> Outcome<Track>,
        private val search: suspend (SearchQuery) -> Outcome<SearchResults> = { Outcome.Success(SearchResults()) },
    ) : MusicSource {
        var detailCalls = 0
        override val descriptor = SourceDescriptor(s, "Online", "Test", Basis.UNOFFICIAL_API)
        override val capabilities: StateFlow<SourceCapabilities> = MutableStateFlow(SourceCapabilities.available(Capability.SEARCH))
        override val catalog: CatalogFacet = object : CatalogFacet {
            override suspend fun search(query: SearchQuery) = this@ScriptedSource.search(query)
            override suspend fun track(ref: SourceRef): Outcome<Track> {
                detailCalls++
                return details()
            }
            override suspend fun album(id: AlbumId): Outcome<AlbumDetail> = Outcome.Failure(PodiumError.NotFound("album"))
            override suspend fun artist(id: ArtistId): Outcome<ArtistDetail> = Outcome.Failure(PodiumError.NotFound("artist"))
        }
    }
}
