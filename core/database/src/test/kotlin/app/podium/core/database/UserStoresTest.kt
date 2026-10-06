package app.podium.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.podium.core.model.QueueUid
import app.podium.core.model.TrackId
import app.podium.player.api.PlayContext
import app.podium.player.api.QueueItem
import app.podium.player.api.QueueOrigin
import app.podium.player.api.QueueState
import app.podium.player.api.RepeatMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.room3.useWriterConnection
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class UserStoresTest {

    private val db = testDatabase()

    @After
    fun close() = db.close()

    @Test
    fun `favorites are written through and read back by the next run`() = runTest {
        val id = TrackId("local|alpha")
        val first = DatabaseFavorites(db, backgroundScope)
        first.toggle(id)
        assertEquals(setOf(id), first.favorites.value)

        val next = DatabaseFavorites(db, backgroundScope)
        assertEquals(setOf(id), realTime { next.favorites.filter { it.isNotEmpty() }.first() })

        next.toggle(id)
        assertTrue(next.favorites.value.isEmpty())
        assertTrue(realTime { db.likes().likedIds().filter { it.isEmpty() }.first() }.isEmpty())
    }

    @Test
    fun `old favorites are imported once`() = runTest {
        val favorites = DatabaseFavorites(db, backgroundScope)
        favorites.import(listOf(TrackId("local|a"), TrackId("local|b")))
        favorites.import(listOf(TrackId("local|a")))
        assertEquals(listOf("local|a", "local|b"), db.likes().likedIds().first().sorted())
    }

    private fun queueOf(vararg titles: String, current: Int, origin: QueueOrigin = QueueOrigin.CONTEXT) = QueueState(
        items = titles.mapIndexed { i, t -> QueueItem(QueueUid("q$i"), song(t), origin, originalOrder = titles.size - 1 - i) },
        currentIndex = current,
        repeatMode = RepeatMode.ALL,
        shuffleEnabled = true,
        context = PlayContext("Woodland"),
    )

    @Test
    fun `the queue round-trips with its position, modes and songs, even songs outside the library`() = runTest {
        val store = DatabaseQueueStore(db)
        store.save(queueOf("Alpha", "Bravo", "Charlie", current = 1), positionMs = 42_000)
        val saved = assertNotNull(store.load())
        assertEquals(listOf("Alpha", "Bravo", "Charlie"), saved.items.map { it.track.title })
        assertEquals(listOf(2, 1, 0), saved.items.map { it.originalOrder })
        assertEquals(1, saved.currentIndex)
        assertEquals(42_000, saved.positionMs)
        assertEquals(RepeatMode.ALL, saved.repeatMode)
        assertTrue(saved.shuffleEnabled)
        assertEquals("Woodland", saved.contextLabel)
        assertEquals(song("Bravo"), saved.items[1].track)

        store.savePosition(2, 5_000)
        assertEquals(2 to 5_000L, store.load()!!.let { it.currentIndex to it.positionMs })
    }

    @Test
    fun `a mixed-source queue comes back with every song's own source, preferred source and fallback`() = runTest {
        val a = song("Alpha", source = app.podium.core.model.SourceId("audius"), key = "1")
        val b = song("Bravo", source = app.podium.core.model.SourceId("other"), key = "1") // same provider key, other source
        val c = song("Charlie", source = app.podium.core.model.SourceId("audius"), key = "3")
        val store = DatabaseQueueStore(db)
        store.save(QueueState(listOf(a, b, c).mapIndexed { i, t -> QueueItem(QueueUid("q$i"), t, QueueOrigin.CONTEXT, originalOrder = i) }, 1), 0)
        val restored = assertNotNull(store.load())
        assertEquals(listOf(a.id, b.id, c.id), restored.items.map { it.track.id })
        // What the restored items are rebuilt with: each one's own source, EXACT-only fallback.
        val queue = app.podium.player.api.QueueManager()
        queue.restore(restored)
        val items = queue.state.value.items
        assertEquals(listOf("audius", "other", "audius"), items.map { it.preferredSource.value })
        assertTrue(items.all { it.fallbackPolicy == app.podium.sources.api.resolve.FallbackPolicy.EXACT_ONLY })
        assertTrue(items.all { it.selection == null }, "resolutions are never saved: each play resolves afresh")
    }

    @Test
    fun `saving the queue never overwrites the library's copy of a song`() = runTest {
        val library = LibraryStore(db)
        library.sync(Local, "This phone", listOf(song("Alpha")), emptyMap())
        val stale = song("Alpha").copy(title = "Alpha (old title)")
        DatabaseQueueStore(db).save(
            QueueState(listOf(QueueItem(QueueUid("q1"), stale, QueueOrigin.CONTEXT)), 0),
            positionMs = 0,
        )
        assertEquals("Alpha", library.track(stale.id)!!.title)
    }

    @Test
    fun `an empty queue clears what was saved`() = runTest {
        val store = DatabaseQueueStore(db)
        store.save(queueOf("Alpha", current = 0), 0)
        store.save(QueueState(), 0)
        assertNull(store.load())
    }

    @Test
    fun `songs that can't be found drop out and the current song keeps its place`() = runTest {
        val store = DatabaseQueueStore(db)
        store.save(queueOf("Alpha", "Bravo", "Charlie", current = 2), positionMs = 9_000)
        db.library().tracks(listOf("local|bravo")).single().let { db.withTrackDeleted(it.id) }
        val saved = assertNotNull(store.load())
        assertEquals(listOf("Alpha", "Charlie"), saved.items.map { it.track.title })
        assertEquals(1, saved.currentIndex)
        assertEquals(9_000, saved.positionMs)
    }

    @Test
    fun `a backup is taken before a schema upgrade, not on a normal open`() {
        val dir = Files.createTempDirectory("podium-db").toFile()
        val file = File(dir, "podium.db")
        val header = ByteArray(100).also {
            "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII).copyInto(it)
            it[63] = 1 // user_version = 1
        }
        file.writeBytes(header + ByteArray(400))
        assertEquals(1, DatabaseBackup.userVersion(file))

        DatabaseBackup.beforeOpen(file, targetVersion = 1)
        assertTrue(dir.listFiles()!!.none { it.name.contains(".bak-") })
        DatabaseBackup.beforeOpen(file, targetVersion = 2)
        assertTrue(File(dir, "podium.db.bak-v1").isFile)
        dir.deleteRecursively()
    }
}

/** The database works on real threads: wait in real time, not the test scheduler's virtual time. */
private suspend fun <T> realTime(block: suspend () -> T): T = withContext(Dispatchers.Default) { withTimeout(5_000) { block() } }

/** Test-only: delete a song row outright (as a future garbage collection would). */
private suspend fun PodiumDatabase.withTrackDeleted(id: String) {
    useWriterConnection { connection ->
        connection.usePrepared("DELETE FROM track WHERE id = ?") {
            it.bindText(1, id)
            it.step()
        }
    }
}
