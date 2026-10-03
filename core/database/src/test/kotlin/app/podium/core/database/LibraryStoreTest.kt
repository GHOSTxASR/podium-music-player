package app.podium.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.podium.core.model.AlbumId
import app.podium.core.model.AlbumRef
import app.podium.core.model.ArtistCredit
import app.podium.core.model.ArtistId
import app.podium.core.model.ArtistRole
import app.podium.core.model.AudioQuality
import app.podium.core.model.Availability
import app.podium.core.model.Codec
import app.podium.core.model.Explicitness
import app.podium.core.model.PartialDate
import app.podium.core.model.PlaybackRoute
import app.podium.core.model.RecordingIdentifiers
import app.podium.core.model.SourceRef
import app.podium.core.model.VariantNote
import app.podium.core.model.VersionInfo
import app.podium.core.model.VersionTag
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class LibraryStoreTest {

    private val db = testDatabase()
    private var clock = 1_000L
    private val store = LibraryStore(db) { clock }

    @After
    fun close() = db.close()

    @Test
    fun `a song survives the round trip through the database unchanged`() = runTest {
        val rich = song("Coastline, part 1").copy(
            source = SourceRef(Local, "coastline-part-1", providerUri = "content://media/1", providerData = "{\"x\":1}"),
            artists = listOf(
                ArtistCredit("Field Recordings Co.", ArtistRole.PRIMARY, ArtistId.of(Local, "ar-frc")),
                ArtistCredit("Guest", ArtistRole.FEATURED),
            ),
            artistDisplay = "Field Recordings Co. feat. Guest",
            identifiers = RecordingIdentifiers("USRC17607839", "mbid-1"),
            explicitness = Explicitness.EXPLICIT,
            releaseDate = PartialDate(1999, 7, 4),
            version = VersionInfo("coastline part 1", setOf(VersionTag.LIVE), setOf(VariantNote.REMASTER), "live at the harbour", listOf("official audio")),
            advertisedQualities = listOf(AudioQuality(Codec.FLAC, "flac", null, 96_000, 24, 2)),
            availability = Availability.Unavailable("Not on this device"),
            routes = setOf(PlaybackRoute.DIRECT, PlaybackRoute.REMOTE),
        )
        store.sync(Local, "This phone", listOf(rich), emptyMap())
        assertEquals(rich, store.track(rich.id))
    }

    @Test
    fun `songs come back in library order, ignoring case, accents and leading articles`() = runTest {
        val titles = listOf("the Zebra", "Éclair", "apple", "A Bridge", "Delta")
        store.sync(Local, "This phone", titles.map { song(it) }, emptyMap())
        assertEquals(listOf("apple", "A Bridge", "Delta", "Éclair", "the Zebra"), store.songs(listOf(Local)).first().map { it.title })
    }

    @Test
    fun `an unchanged sync writes nothing, a changed song is rewritten, a missing one is marked removed`() = runTest {
        val a = song("Alpha", number = 1)
        val b = song("Bravo", number = 2)
        assertEquals(SyncResult(added = 2, updated = 0, removed = 0), store.sync(Local, "This phone", listOf(a, b), emptyMap()))
        assertFalse(store.sync(Local, "This phone", listOf(a, b), emptyMap()).changed)

        clock = 2_000L
        val renamed = a.copy(title = "Alpha (2024 mix)")
        assertEquals(SyncResult(added = 0, updated = 1, removed = 1), store.sync(Local, "This phone", listOf(renamed), emptyMap()))
        assertEquals(listOf("Alpha (2024 mix)"), store.songs(listOf(Local)).first().map { it.title })
        // A removed song is kept for whatever still refers to it (the queue), just not listed.
        assertNotNull(store.track(b.id))
    }

    @Test
    fun `a song that comes back is listed again`() = runTest {
        val a = song("Alpha")
        store.sync(Local, "This phone", listOf(a), emptyMap())
        store.sync(Local, "This phone", emptyList(), emptyMap())
        assertTrue(store.songs(listOf(Local)).first().isEmpty())
        store.sync(Local, "This phone", listOf(a), emptyMap())
        assertEquals(listOf(a), store.songs(listOf(Local)).first())
    }

    @Test
    fun `reads are scoped to the sources asked for, and syncing one source leaves the others alone`() = runTest {
        store.sync(Local, "This phone", listOf(song("Alpha")), emptyMap())
        store.sync(Server, "Home server", listOf(song("Bravo", source = Server)), emptyMap())
        store.sync(Local, "This phone", listOf(song("Alpha"), song("Charlie")), emptyMap())
        assertEquals(listOf("Alpha", "Charlie"), store.songs(listOf(Local)).first().map { it.title })
        assertEquals(listOf("Alpha", "Bravo", "Charlie"), store.songs(listOf(Local, Server)).first().map { it.title })
        assertTrue(store.songs(emptyList()).first().isEmpty())
    }

    @Test
    fun `albums are derived from their songs, in track order`() = runTest {
        store.sync(
            Local, "This phone",
            listOf(
                song("Second", number = 2), song("First", number = 1), song("Bonus", disc = 2, number = 1),
                song("Solo", album = "Coastline", artist = "Field Recordings Co.", year = 2021),
            ),
            emptyMap(),
        )
        val albums = store.albums(listOf(Local)).first()
        assertEquals(listOf("Coastline", "Woodland"), albums.map { it.title })
        assertEquals(3, albums.single { it.title == "Woodland" }.trackCount)

        val woodland = assertNotNull(store.album(AlbumId.of(Local, "al-woodland")).first())
        assertEquals(listOf("First", "Second", "Bonus"), woodland.tracks.map { it.title })
        assertEquals("Northern Sines", woodland.album.artist)
        assertEquals(2024, woodland.album.year)
    }

    @Test
    fun `songs without album metadata are filed under an unknown album per artist`() = runTest {
        val loose = song("Loose").copy(album = null)
        store.sync(Local, "This phone", listOf(loose), emptyMap())
        assertEquals(listOf("Unknown album"), store.albums(listOf(Local)).first().map { it.title })
    }

    @Test
    fun `artists carry counts, pictures only when a source has them, and a few covers`() = runTest {
        val portrait = "podium-art://local/artist%2Fns"
        store.sync(
            Local, "This phone",
            listOf(song("One"), song("Two", album = "Lakeside"), song("Three", artist = "Field Recordings Co.", album = "Coastline")),
            mapOf(ArtistId.of(Local, "ar-northern sines") to portrait),
        )
        val artists = store.artists(listOf(Local)).first()
        assertEquals(listOf("Field Recordings Co.", "Northern Sines"), artists.map { it.name })
        val ns = artists.single { it.name == "Northern Sines" }
        assertEquals(portrait, ns.artworkUri)
        assertEquals(2, ns.albumCount)
        assertEquals(2, ns.trackCount)
        assertEquals(2, ns.covers.size)
        assertEquals(null, artists.single { it.name == "Field Recordings Co." }.artworkUri)

        val detail = assertNotNull(store.artist(ns.id).first())
        assertEquals(listOf("Lakeside", "Woodland"), detail.albums.map { it.title }.sorted())
        assertEquals(listOf("One", "Two"), detail.tracks.map { it.title })
    }

    @Test
    fun `search matches word starts in titles, artists and albums, ignoring accents`() = runTest {
        store.sync(
            Local, "This phone",
            listOf(song("Café del mar"), song("Revolver", artist = "The Beatles", album = "Revolver"), song("Night train")),
            emptyMap(),
        )
        assertEquals(listOf("Café del mar"), store.search("cafe", listOf(Local)).map { it.title })
        assertEquals(listOf("Revolver"), store.search("beat rev", listOf(Local)).map { it.title })
        assertEquals(listOf("Café del mar", "Night train"), store.search("north", listOf(Local)).map { it.title }.sorted())
        assertTrue(store.search("ain", listOf(Local)).isEmpty(), "matches word starts, not the middle of words")
        assertTrue(store.search("  ", listOf(Local)).isEmpty())
        assertTrue(store.search("cafe", listOf(Server)).isEmpty())
    }

    @Test
    fun `the search index follows renames and removals`() = runTest {
        val a = song("Alpha")
        store.sync(Local, "This phone", listOf(a), emptyMap())
        store.sync(Local, "This phone", listOf(a.copy(title = "Omega")), emptyMap())
        assertTrue(store.search("alpha", listOf(Local)).isEmpty())
        assertEquals(1, store.search("omega", listOf(Local)).size)
        store.sync(Local, "This phone", emptyList(), emptyMap())
        assertTrue(store.search("omega", listOf(Local)).isEmpty())
    }

    @Test
    fun `album ids never merge across sources`() = runTest {
        store.sync(Local, "This phone", listOf(song("Alpha")), emptyMap())
        store.sync(Server, "Home server", listOf(song("Alpha", source = Server)), emptyMap())
        val albums = store.albums(listOf(Local, Server)).first()
        assertEquals(2, albums.size)
        assertEquals(setOf(AlbumId.of(Local, "al-woodland"), AlbumId.of(Server, "al-woodland")), albums.map { it.id }.toSet())
    }

    @Test
    fun `the source's own album id survives, even when its title is empty`() = runTest {
        val odd = song("Alpha").copy(album = AlbumRef("", AlbumId.of(Local, "al-x"), null))
        store.sync(Local, "This phone", listOf(odd), emptyMap())
        assertEquals(odd, store.track(odd.id))
    }
}
