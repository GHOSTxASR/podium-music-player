package app.podium.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.podium.core.model.SourceId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The listener's playlists of the music on this phone (D-106). */
@RunWith(AndroidJUnit4::class)
class LocalPlaylistStoreTest {

    private val db = testDatabase()
    private var clock = 1_000L
    private val playlists = LocalPlaylistStore(db) { clock++ }
    private val library = LibraryStore(db)

    @After
    fun close() = db.close()

    private val alpha = song("Alpha", album = "First")
    private val bravo = song("Bravo", album = "Second")
    private val charlie = song("Charlie", album = "Third")
    private val delta = song("Delta", album = "First")

    private suspend fun titles(id: String) = playlists.playlist(id).first()!!.entries.map { it.track.title }

    @Test
    fun `playlists can be made, filled, reordered, renamed and deleted`() = runTest {
        library.sync(Local, "This phone", listOf(alpha, bravo, charlie, delta), emptyMap())
        val id = playlists.create("Evening", listOf(alpha.id, bravo.id))
        playlists.add(id, listOf(charlie.id, delta.id))
        assertEquals(listOf("Alpha", "Bravo", "Charlie", "Delta"), titles(id))
        assertEquals(4, playlists.playlists().first().single().trackCount)

        var entries = playlists.playlist(id).first()!!.entries
        playlists.move(id, entries[2].entryId, 0)
        assertEquals(listOf("Charlie", "Alpha", "Bravo", "Delta"), titles(id))
        entries = playlists.playlist(id).first()!!.entries
        playlists.move(id, entries[0].entryId, 3)
        assertEquals(listOf("Alpha", "Bravo", "Delta", "Charlie"), titles(id), "to the end")

        entries = playlists.playlist(id).first()!!.entries
        playlists.remove(id, entries[1].entryId)
        assertEquals(listOf("Alpha", "Delta", "Charlie"), titles(id))

        playlists.rename(id, "  Late night ")
        assertEquals("Late night", playlists.playlists().first().single().name)
        playlists.rename(id, "   ")
        assertEquals("Late night", playlists.playlists().first().single().name, "a blank name changes nothing")

        playlists.delete(id)
        assertNull(playlists.playlist(id).first())
        assertTrue(playlists.playlists().first().isEmpty())
        assertTrue(db.localPlaylists().positions(id).isEmpty(), "its songs go with it")
    }

    @Test
    fun `the latest changed playlist comes first, and a song can be in one twice`() = runTest {
        library.sync(Local, "This phone", listOf(alpha, bravo), emptyMap())
        val first = playlists.create("First")
        val second = playlists.create("Second")
        assertEquals(listOf("Second", "First"), playlists.playlists().first().map { it.name })
        playlists.add(first, listOf(alpha.id))
        assertEquals(listOf("First", "Second"), playlists.playlists().first().map { it.name })
        playlists.add(first, listOf(alpha.id))
        assertEquals(listOf("Alpha", "Alpha"), titles(first))
        assertEquals(2, playlists.playlists().first().first().trackCount)
    }

    @Test
    fun `a song that leaves the library hides from its playlists until it's back`() = runTest {
        library.sync(Local, "This phone", listOf(alpha, bravo, charlie), emptyMap())
        val id = playlists.create("Evening", listOf(alpha.id, bravo.id, charlie.id))
        library.sync(Local, "This phone", listOf(alpha, charlie), emptyMap())
        assertEquals(listOf("Alpha", "Charlie"), titles(id))
        assertEquals(2, playlists.playlists().first().single().trackCount)

        // Moves count among the songs shown: Charlie to the top passes Alpha, not the hidden Bravo.
        val entries = playlists.playlist(id).first()!!.entries
        playlists.move(id, entries[1].entryId, 0)
        assertEquals(listOf("Charlie", "Alpha"), titles(id))

        library.sync(Local, "This phone", listOf(alpha, bravo, charlie), emptyMap())
        assertEquals(3, titles(id).size, "back where it was kept")
        assertEquals(listOf("Charlie", "Alpha"), titles(id).filter { it != "Bravo" })
    }

    @Test
    fun `moving into the same gap again and again keeps the order right`() = runTest {
        val songs = (1..6).map { song("Song $it") }
        library.sync(Local, "This phone", songs, emptyMap())
        val id = playlists.create("Gaps", songs.map { it.id })
        // Halving one gap 60 times exhausts it; the store renumbers and carries on.
        repeat(60) {
            val entries = playlists.playlist(id).first()!!.entries
            val last = entries.last()
            playlists.move(id, last.entryId, 1)
        }
        val order = titles(id)
        assertEquals(6, order.size)
        assertEquals("Song 1", order.first())
        // Each move takes the last song to second place: after 60 (a multiple of 5) the order is back.
        assertEquals(songs.map { it.title }, order)
    }

    @Test
    fun `covers are the playlist's distinct covers in its order`() = runTest {
        library.sync(Local, "This phone", listOf(alpha, bravo, charlie, delta), emptyMap())
        val id = playlists.create("Covers", listOf(delta.id, alpha.id, bravo.id, charlie.id))
        val covers = playlists.playlists().first().single().covers.map { it.key }
        assertEquals(listOf("al-first", "al-second", "al-third"), covers, "Delta and Alpha share First's cover")
        assertEquals(covers, assertNotNull(playlists.playlist(id).first()).info.covers.map { it.key })
    }

    @Test
    fun `local playlists and online playlists never see each other`() = runTest {
        library.sync(Local, "This phone", listOf(alpha), emptyMap())
        val online = OnlineLibraryStore(db)
        val remote = song("Signal", source = SourceId("audius"), key = "123")
        online.createPlaylist("Online mix", SourceId("audius"), listOf(remote))
        playlists.create("Local mix", listOf(alpha.id))
        assertEquals(listOf("Local mix"), playlists.playlists().first().map { it.name })
        assertEquals(listOf("Online mix"), online.playlists().first().map { it.name })
        // An online song (kept in `track`, not in the library) never shows in a local playlist.
        val id = playlists.playlists().first().single().id
        playlists.add(id, listOf(remote.id))
        assertEquals(listOf("Alpha"), titles(id))
    }
}
