package app.podium

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.podium.core.common.Clock
import app.podium.core.database.LibraryStore
import app.podium.core.database.PodiumDatabase
import app.podium.core.model.AlbumId
import app.podium.core.model.AlbumRef
import app.podium.core.model.ArtistCredit
import app.podium.core.model.ArtistId
import app.podium.core.model.ArtworkRef
import app.podium.core.model.SourceId
import app.podium.core.model.SourceRef
import app.podium.core.model.Track
import app.podium.core.model.TrackId
import app.podium.feature.library.LibraryState
import app.podium.player.api.TrackCatalog
import app.podium.sources.api.AlbumSummary
import app.podium.sources.api.ArtistSummary
import app.podium.sources.api.Capability
import app.podium.sources.api.CapabilityState
import app.podium.sources.api.LibraryFacet
import app.podium.sources.api.MusicSource
import app.podium.sources.api.SourceCapabilities
import app.podium.sources.api.SourceDescriptor
import app.podium.sources.api.SourceHealthMonitor
import app.podium.sources.api.SourceRegistry
import app.podium.sources.api.Basis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The app's library over a real in-memory database and scriptable library sources. */
@RunWith(AndroidJUnit4::class)
class DatabaseLibraryRepositoryTest {

    private val db = PodiumDatabase.createInMemory(ApplicationProvider.getApplicationContext())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val registry = SourceRegistry(SourceHealthMonitor(Clock.System))
    private val store = LibraryStore(db)
    private val catalog = TrackCatalog(registry) { store.track(it) }
    private val only = MutableStateFlow<SourceId?>(null)

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun repository() = DatabaseLibraryRepository(registry, store, catalog, scope, only)

    private fun <T> eventually(flow: Flow<T>, predicate: (T) -> Boolean): T =
        runBlocking { withTimeout(5_000) { flow.first(predicate) } }

    private fun titles(state: LibraryState) = (state as? LibraryState.Ready)?.tracks?.map { it.title }

    @Test
    fun `a source's library is synced into the database and listed in library order`() {
        val phone = LibrarySourceStub("phone", listOf(song("phone", "Bravo"), song("phone", "alpha")))
        registry.register(phone)
        val library = repository()
        assertEquals(listOf("alpha", "Bravo"), titles(eventually(library.songs) { it is LibraryState.Ready && it.tracks.size == 2 }))
        // The songs are also in the catalog, so play commands by id need no source round trip.
        assertEquals("alpha", catalog.cached(TrackId.of(SourceId("phone"), "alpha"))?.title)
    }

    @Test
    fun `the library is loading until every usable source has synced, then empty is empty`() {
        val phone = LibrarySourceStub("phone", emptyList(), emitted = false)
        registry.register(phone)
        val library = repository()
        runBlocking { kotlinx.coroutines.delay(300) }
        assertIs<LibraryState.Loading>(library.songs.value)
        phone.emit(emptyList())
        eventually(library.songs) { it == LibraryState.Ready(emptyList()) }
    }

    @Test
    fun `albums and artists never say none before the first sync`() {
        val phone = LibrarySourceStub("phone", emptyList(), emitted = false)
        registry.register(phone)
        val library = repository()
        val albums = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val artists = java.util.Collections.synchronizedList(mutableListOf<Int>())
        scope.launch { library.albums().collect { albums += it.size } }
        scope.launch { library.artists().collect { artists += it.size } }
        runBlocking { kotlinx.coroutines.delay(300) }
        assertEquals(emptyList<Int>(), albums, "no answer yet, rather than an empty list")
        assertEquals(emptyList<Int>(), artists)
        phone.emit(listOf(song("phone", "Alpha")))
        eventually(library.albums()) { it.size == 1 }
        eventually(library.artists()) { it.size == 1 }
        assertTrue(0 !in albums && 0 !in artists, "never empty on the way: $albums $artists")
    }

    @Test
    fun `changes at the source reach the library`() {
        val phone = LibrarySourceStub("phone", listOf(song("phone", "Alpha")))
        registry.register(phone)
        val library = repository()
        eventually(library.songs) { titles(it) == listOf("Alpha") }
        phone.emit(listOf(song("phone", "Alpha"), song("phone", "Charlie")))
        eventually(library.songs) { titles(it) == listOf("Alpha", "Charlie") }
        phone.emit(listOf(song("phone", "Charlie")))
        eventually(library.songs) { titles(it) == listOf("Charlie") }
    }

    @Test
    fun `a library that stops being usable is hidden, and comes back with its access`() {
        val phone = LibrarySourceStub("phone", listOf(song("phone", "Alpha")))
        registry.register(phone)
        val library = repository()
        eventually(library.songs) { titles(it) == listOf("Alpha") }
        phone.setCapability(Capability.LIBRARY, CapabilityState(app.podium.sources.api.CapabilityStatus.REQUIRES_PERMISSION, "Music access was turned off"))
        eventually(library.songs) { titles(it) == emptyList<String>() }
        phone.setCapability(Capability.LIBRARY, CapabilityState.Available)
        eventually(library.songs) { titles(it) == listOf("Alpha") }
    }

    @Test
    fun `the debug scope narrows what is shown, not what is stored`() {
        registry.register(LibrarySourceStub("phone", listOf(song("phone", "Alpha"))))
        registry.register(LibrarySourceStub("tones", listOf(song("tones", "Tone"))))
        val library = repository()
        eventually(library.songs) { titles(it)?.size == 2 }
        only.value = SourceId("tones")
        eventually(library.songs) { titles(it) == listOf("Tone") }
        only.value = null
        eventually(library.songs) { titles(it)?.size == 2 }
    }

    @Test
    fun `albums and artists come from the database, with artist pictures only from the source`() {
        val portrait = ArtworkRef(SourceId("phone"), "artist/ns")
        registry.register(
            LibrarySourceStub(
                "phone",
                listOf(song("phone", "Alpha"), song("phone", "Bravo")),
                artistPictures = mapOf(ArtistId.of(SourceId("phone"), "ar-ns") to portrait),
            ),
        )
        val library = repository()
        val albums = eventually(library.albums()) { it.isNotEmpty() }
        assertEquals(listOf("Woodland"), albums.map { it.title })
        assertEquals(2, albums.single().trackCount)
        val artist = eventually(library.artists()) { it.isNotEmpty() }.single()
        assertEquals(portrait.uri, artist.artworkUri)

        val page = eventually(library.album(AlbumId.of(SourceId("phone"), "al-woodland")).filterNotNull()) { true }
        assertEquals(listOf("Alpha", "Bravo"), page.tracks.map { it.title })
        val artistPage = eventually(library.artist(artist.id).filterNotNull()) { true }
        assertEquals(1, artistPage.albums.size)
    }

    @Test
    fun `an album page is empty while its source is out of scope`() {
        registry.register(LibrarySourceStub("phone", listOf(song("phone", "Alpha"))))
        registry.register(LibrarySourceStub("tones", listOf(song("tones", "Tone"))))
        val library = repository()
        eventually(library.songs) { titles(it)?.size == 2 }
        only.value = SourceId("tones")
        val page = runBlocking { withTimeout(5_000) { library.album(AlbumId.of(SourceId("phone"), "al-woodland")).first() } }
        assertNull(page)
    }

    @Test
    fun `the catalog finds a stored song by id after a restart, before any source has synced`() {
        registry.register(LibrarySourceStub("phone", listOf(song("phone", "Alpha"))))
        val library = repository()
        eventually(library.songs) { titles(it) == listOf("Alpha") }
        val freshCatalog = TrackCatalog(SourceRegistry(SourceHealthMonitor(Clock.System))) { store.track(it) }
        val found = runBlocking { freshCatalog.get(TrackId.of(SourceId("phone"), "alpha")) }
        assertTrue(found is app.podium.core.common.Outcome.Success && found.value.title == "Alpha")
    }
}

private fun song(source: String, title: String): Track {
    val id = SourceId(source)
    val key = title.lowercase()
    return Track(
        id = TrackId.of(id, key),
        source = SourceRef(id, key),
        title = title,
        artists = listOf(ArtistCredit("Northern Sines", id = ArtistId.of(id, "ar-ns"))),
        album = AlbumRef("Woodland", AlbumId.of(id, "al-woodland"), "Northern Sines"),
        durationMs = 180_000,
    )
}

/** A library source whose songs and capabilities the test changes. */
private class LibrarySourceStub(
    id: String,
    initial: List<Track>,
    emitted: Boolean = true,
    private val artistPictures: Map<ArtistId, ArtworkRef> = emptyMap(),
) : MusicSource {
    private val tracks = MutableStateFlow(if (emitted) initial else null)
    override val descriptor = SourceDescriptor(SourceId(id), displayName = "Stub $id", providerName = "Stub", basis = Basis.LOCAL_DEVICE)
    private val _capabilities = MutableStateFlow(SourceCapabilities.available(Capability.LIBRARY))
    override val capabilities: StateFlow<SourceCapabilities> = _capabilities

    fun emit(list: List<Track>) {
        tracks.value = list
    }

    fun setCapability(capability: Capability, state: CapabilityState) {
        _capabilities.value = SourceCapabilities(_capabilities.value.states + (capability to state))
    }

    override val library = object : LibraryFacet {
        override fun tracks(): Flow<List<Track>> = tracks.filterNotNull()
        override fun albums(): Flow<List<AlbumSummary>> = MutableStateFlow(emptyList())
        override fun artists(): Flow<List<ArtistSummary>> = tracks.filterNotNull().map { list ->
            list.mapNotNull { it.artists.firstOrNull()?.id }.distinct().map { ArtistSummary(it, "Northern Sines", artistPictures[it]) }
        }
    }
}
