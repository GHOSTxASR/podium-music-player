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
import app.podium.player.api.TrackCatalog
import app.podium.sources.api.SourceHealthMonitor
import app.podium.sources.api.SourceRegistry
import app.podium.sources.testing.FakeMusicSource
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Filling in a song's album and artist pages from its own details (D-79). */
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
        val pages = SongPages(catalog, registry)
        val found = pages.of(TrackId.of(s, "v1"))!!
        assertTrue(SongPages.hasPages(found))
        assertTrue(SongPages.hasPages(catalog.cached(TrackId.of(s, "v1"))!!), "remembered")
    }
}
