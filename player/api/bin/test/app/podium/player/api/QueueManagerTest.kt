package app.podium.player.api

import app.podium.core.model.Track
import app.podium.sources.testing.track
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class QueueManagerTest {
    private val album = (1..6).map { track("Track $it", "Artist", key = "t$it") }
    private fun titles(q: QueueManager) = q.state.value.items.map { it.track.title }
    private fun upNextTitles(q: QueueManager) = q.state.value.upNext.map { it.track.title }

    @Test
    fun `playing a context starts at the chosen track with earlier tracks as history`() {
        val q = QueueManager()
        val ops = q.setContext(album, 2, PlayContext("Album"))
        assertEquals("Track 3", q.state.value.current?.track?.title)
        assertEquals(2, q.state.value.history.size)
        assertIs<QueueOp.ReplaceAll>(ops.single())
    }

    @Test
    fun `play next goes right after the current item, newest first`() {
        val q = QueueManager()
        q.setContext(album, 0, null)
        q.playNext(listOf(track("A", key = "a")))
        q.playNext(listOf(track("B", key = "b")))
        assertEquals(listOf("B", "A", "Track 2"), upNextTitles(q).take(3))
        assertEquals(QueueOrigin.PLAY_NEXT, q.state.value.upNext.first().origin)
    }

    @Test
    fun `add to queue goes after the user's items, before the rest of the context`() {
        val q = QueueManager()
        q.setContext(album, 0, null)
        q.playNext(listOf(track("Next", key = "n")))
        q.addToQueue(listOf(track("Q1", key = "q1")))
        q.addToQueue(listOf(track("Q2", key = "q2")))
        assertEquals(listOf("Next", "Q1", "Q2", "Track 2"), upNextTitles(q).take(4))
    }

    @Test
    fun `starting a new context keeps the user's queue`() {
        val q = QueueManager()
        q.setContext(album, 0, null)
        q.addToQueue(listOf(track("Mine", key = "mine")))
        q.setContext(listOf(track("X", key = "x"), track("Y", key = "y")), 0, PlayContext("Other"))
        assertEquals(listOf("Mine", "Y"), upNextTitles(q))
    }

    @Test
    fun `removing the current item makes the next one current`() {
        val q = QueueManager()
        q.setContext(album, 1, null)
        val current = q.state.value.current!!
        q.remove(setOf(current.uid))
        assertEquals("Track 3", q.state.value.current?.track?.title)
    }

    @Test
    fun `moving an item joins the section it lands in`() {
        val q = QueueManager()
        q.setContext(album, 0, null)
        q.playNext(listOf(track("Next", key = "n")))
        val t5 = q.state.value.items.first { it.track.title == "Track 5" }
        q.move(t5.uid, q.state.value.currentIndex + 1)
        val moved = q.state.value.upNext.first()
        assertEquals("Track 5", moved.track.title)
        assertEquals(QueueOrigin.PLAY_NEXT, moved.origin)
        q.checkInvariants()
    }

    @Test
    fun `shuffle reorders upcoming context items visibly and undoes cleanly`() {
        val q = QueueManager()
        q.setContext(album, 0, null)
        q.addToQueue(listOf(track("Mine", key = "mine")))
        val before = upNextTitles(q)
        val ops = q.setShuffle(true, seed = 42)
        assertIs<QueueOp.ReplaceRange>(ops.single())
        assertEquals("Mine", upNextTitles(q).first(), "user-queued items stay first")
        assertEquals(before.toSet(), upNextTitles(q).toSet())
        q.setShuffle(false)
        assertEquals(before, upNextTitles(q))
    }

    @Test
    fun `shuffle is reproducible from its seed`() {
        val a = QueueManager().apply { setContext(album, 0, null); setShuffle(true, seed = 7) }
        val b = QueueManager().apply { setContext(album, 0, null); setShuffle(true, seed = 7) }
        assertEquals(titles(a), titles(b))
    }

    @Test
    fun `skip to keeps skipped items as history`() {
        val q = QueueManager()
        q.setContext(album, 0, null)
        val target = q.state.value.items[4]
        assertEquals(listOf<QueueOp>(QueueOp.SeekTo(4)), q.skipTo(target.uid))
        assertEquals(6, q.state.value.items.size)
        assertEquals(target.uid, q.state.value.current?.uid)
    }

    @Test
    fun `clear upcoming leaves history and current`() {
        val q = QueueManager()
        q.setContext(album, 2, null)
        q.clearUpcoming()
        assertEquals(3, q.state.value.items.size)
        assertTrue(q.state.value.upNext.isEmpty())
    }

    @Test
    fun `going back shows Up Next as runs in true play order`() {
        val q = QueueManager()
        q.setContext(album.take(3), 1, null)
        q.playNext(listOf(track("Next", key = "n")))
        q.onCurrentChanged(q.state.value.items[0].uid) // ⏮ ⏮ back to Track 1
        val runs = q.state.value.sections.map { (origin, items) -> origin to items.map { it.track.title } }
        assertEquals(
            listOf(
                QueueOrigin.CONTEXT to listOf("Track 2"),
                QueueOrigin.PLAY_NEXT to listOf("Next"),
                QueueOrigin.CONTEXT to listOf("Track 3"),
            ),
            runs,
        )
    }

    @Test
    fun `moving is limited to upcoming items`() {
        val q = QueueManager()
        q.setContext(album, 5, null) // current is the last item: nothing upcoming
        assertTrue(q.move(q.state.value.items[0].uid, 3).isEmpty())
        assertEquals(5, q.state.value.currentIndex)
    }

    @Test
    fun `the same track may be queued twice with distinct uids`() {
        val q = QueueManager()
        val t = track("Again", key = "again")
        q.setContext(listOf(t), 0, null)
        q.addToQueue(listOf(t))
        val items = q.state.value.items
        assertEquals(2, items.size)
        assertTrue(items[0].uid != items[1].uid)
    }

    @Test
    fun `invariants hold under random operation sequences`() {
        val random = Random(1234)
        repeat(200) { run ->
            val q = QueueManager()
            q.setContext(album, random.nextInt(album.size), null)
            repeat(40) {
                val items = q.state.value.items
                val pick: () -> Track = { track("R${random.nextInt(1000)}", key = "r${random.nextInt(1_000_000)}") }
                when (random.nextInt(8)) {
                    0 -> q.playNext(listOf(pick()))
                    1 -> q.addToQueue(listOf(pick(), pick()))
                    2 -> if (items.isNotEmpty()) q.move(items.random(random).uid, random.nextInt(items.size + 1))
                    3 -> if (items.size > 1) q.remove(setOf(items.random(random).uid))
                    4 -> if (items.isNotEmpty()) q.skipTo(items.random(random).uid)
                    5 -> q.setShuffle(random.nextBoolean(), random.nextLong())
                    6 -> if (items.isNotEmpty()) q.onCurrentChanged(items.random(random).uid)
                    7 -> q.setContext(album, random.nextInt(album.size), null, shuffle = random.nextBoolean())
                }
                q.checkInvariants()
            }
            assertTrue(run >= 0)
        }
    }
}
