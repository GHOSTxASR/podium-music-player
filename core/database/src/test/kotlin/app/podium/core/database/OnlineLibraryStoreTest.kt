package app.podium.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.podium.core.model.SourceId
import app.podium.core.model.TrackId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class OnlineLibraryStoreTest {

    private val db = testDatabase()
    private var clock = 1_000L
    private var ids = 0
    private val online = OnlineLibraryStore(db, { clock++ }, { "p${++ids}" })
    private val library = LibraryStore(db)
    private val Audius = SourceId("audius")

    @After
    fun close() = db.close()

    private fun onlineSong(title: String) = song(title, source = Audius, artist = "Online Artist", album = "Online")

    @Test
    fun `online likes are separate from local favorites, both ways`() = runTest {
        val local = song("Local song")
        library.sync(Local, "This phone", listOf(local), emptyMap())
        val localFavorites = DatabaseFavorites(db, backgroundScope)
        localFavorites.import(listOf(local.id))

        val remote = onlineSong("Online song")
        online.setLiked(remote, true)

        assertEquals(setOf(remote.id), online.likedIds().first())
        assertEquals(listOf("local|local-song"), db.likes().likedIds().first())
        assertEquals(listOf("Online song"), online.likedTracks().first().map { it.title })

        online.setLiked(remote, false)
        assertTrue(online.likedIds().first().isEmpty())
        assertEquals(listOf("local|local-song"), db.likes().likedIds().first(), "unliking online leaves local favorites alone")
    }

    @Test
    fun `online songs never appear in the local library`() = runTest {
        library.sync(Local, "This phone", listOf(song("Local song")), emptyMap())
        online.remember(listOf(onlineSong("Online song")))
        online.setLiked(onlineSong("Liked online"), true)
        assertEquals(listOf("Local song"), library.songs(listOf(Local)).first().map { it.title })
        // Even asked for by source, cached online songs aren't library songs.
        assertTrue(library.songs(listOf(Audius)).first().isEmpty())
        assertTrue(library.search("online", listOf(Local, Audius)).isEmpty())
    }

    @Test
    fun `caching an online song never overwrites a library source's row`() = runTest {
        val shared = song("Shared", source = Audius, key = "same")
        library.sync(Audius, "Server", listOf(shared), emptyMap())
        online.remember(listOf(shared.copy(title = "Shared (stale)")))
        assertEquals("Shared", library.track(shared.id)!!.title)
    }

    @Test
    fun `playlists can be created, filled, reordered, renamed and deleted`() = runTest {
        val a = onlineSong("Alpha")
        val b = onlineSong("Bravo")
        val c = onlineSong("Charlie")
        val id = online.createPlaylist("Road trip", Audius, listOf(a, b))
        online.add(id, listOf(c, a)) // a duplicate is allowed
        var detail = assertNotNull(online.playlist(id).first())
        assertEquals(listOf("Alpha", "Bravo", "Charlie", "Alpha"), detail.entries.map { it.track.title })
        assertEquals(4, online.playlists().first().single().trackCount)

        // Move Charlie to the top, then the last Alpha between Bravo and Charlie… positions stay fractional.
        online.move(id, detail.entries[2].entryId, 0)
        detail = online.playlist(id).first()!!
        assertEquals(listOf("Charlie", "Alpha", "Bravo", "Alpha"), detail.entries.map { it.track.title })
        online.move(id, detail.entries[3].entryId, 1)
        detail = online.playlist(id).first()!!
        assertEquals(listOf("Charlie", "Alpha", "Alpha", "Bravo"), detail.entries.map { it.track.title })

        online.remove(id, detail.entries[1].entryId)
        assertEquals(listOf("Charlie", "Alpha", "Bravo"), online.playlist(id).first()!!.entries.map { it.track.title })

        online.rename(id, "  Long drive ")
        assertEquals("Long drive", online.playlists().first().single().name)
        online.delete(id)
        assertNull(online.playlist(id).first())
        assertTrue(online.playlists().first().isEmpty())
    }

    @Test
    fun `history is recorded per listen with how much was heard`() = runTest {
        val a = onlineSong("Alpha") // 180 s
        val b = onlineSong("Bravo")
        online.record(a, startedAt = 10, playedMs = 90_000)
        online.record(b, startedAt = 20, playedMs = 180_000)
        online.record(a, startedAt = 30, playedMs = 30_000)
        online.record(b, startedAt = 40, playedMs = 0) // a skip before it started isn't a listen
        assertEquals(listOf("Alpha", "Bravo"), online.recentlyPlayed().first().map { it.title })
        assertEquals(setOf(a.id), online.playedSince(25))
        assertEquals(3, db.online().historyCount())
    }

    @Test
    fun `likes keep their own source, even for the same provider id on two sources`() = runTest {
        val other = SourceId("other")
        val onAudius = song("Shared", source = Audius, key = "123")
        val onOther = song("Shared", source = other, key = "123")
        online.setLiked(onAudius, true)
        assertEquals(setOf(TrackId("audius|123")), online.likedIds().first(), "liking one copy doesn't like the other")
        online.setLiked(onOther, true)
        assertEquals(setOf(onAudius.id, onOther.id), online.likedIds().first())
        online.setLiked(onAudius, false)
        assertEquals(setOf(onOther.id), online.likedIds().first())
        assertEquals(other, online.likedTracks().first().single().source.sourceId)
    }

    @Test
    fun `a listen records the song chosen and who served it`() = runTest {
        val chosen = onlineSong("Alpha")
        online.record(chosen, startedAt = 10, playedMs = 60_000, servedBy = SourceId("other"))
        online.record(chosen, startedAt = 20, playedMs = 60_000, servedBy = Audius)
        val (second, first) = online.listens()
        assertEquals("audius|alpha", first.trackId)
        assertEquals("audius", first.sourceId)
        assertEquals("alpha", first.providerId)
        assertEquals("other", first.servedBy)
        assertEquals(null, second.servedBy, "its own source serving it isn't noted twice")
    }

    @Test
    fun `a playlist keeps each song's own source`() = runTest {
        val fromAudius = onlineSong("Alpha")
        val fromOther = song("Gamma", source = SourceId("other"), key = "g")
        val id = online.createPlaylist("Mixed", Audius, listOf(fromAudius, fromOther))
        val entries = online.playlist(id).first()!!.entries.map { it.track }
        assertEquals(listOf(Audius, SourceId("other")), entries.map { it.source.sourceId })
        assertEquals(listOf(fromAudius.id, fromOther.id), entries.map { it.id })
    }

    @Test
    fun `online history doesn't touch the local library or favorites`() = runTest {
        online.record(onlineSong("Alpha"), startedAt = 10, playedMs = 60_000)
        assertTrue(db.likes().likedIds().first().isEmpty())
        assertTrue(library.songs(listOf(Local, Audius)).first().isEmpty())
        assertEquals(TrackId("audius|alpha"), online.recentlyPlayed().first().single().id)
    }
}
