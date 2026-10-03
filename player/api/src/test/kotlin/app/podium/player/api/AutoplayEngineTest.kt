package app.podium.player.api

import app.podium.core.model.QueueUid
import app.podium.sources.testing.track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoplayEngineTest {
    private val engine = AutoplayEngine()
    private val on = AutoplaySettings()

    private fun queueOf(vararg titles: Pair<String, String>, current: Int = titles.lastIndex) = QueueState(
        items = titles.mapIndexed { i, (t, a) -> QueueItem(QueueUid("q$i"), track(t, a, source = "online", key = t.lowercase()), QueueOrigin.CONTEXT) },
        currentIndex = current,
    )

    @Test
    fun `it asks for more only when the queue is about to run out, and only when allowed`() {
        val ending = queueOf("A" to "X", "B" to "Y", current = 1)
        assertTrue(engine.needsMore(ending, on))
        assertFalse(engine.needsMore(ending, on.copy(enabled = false)))
        assertFalse(engine.needsMore(ending, on.copy(recommendations = false)))
        assertFalse(engine.needsMore(queueOf("A" to "X", "B" to "Y", "C" to "Z", "D" to "W", current = 0), on))
        assertFalse(engine.needsMore(QueueState(), on))
    }

    @Test
    fun `nothing queued, recently played or from another source gets in`() {
        val state = queueOf("A" to "X")
        val candidates = listOf(
            track("A", "X", source = "online", key = "a"), // already queued
            track("Played", "Y", source = "online", key = "played"),
            track("Local", "Z", source = "local", key = "local"),
            track("Fresh", "W", source = "online", key = "fresh"),
        )
        val picked = engine.pick(candidates, state, recentlyPlayed = setOf(candidates[1].id), settings = on)
        assertEquals(listOf("Fresh"), picked.map { it.title })
        // Without avoid-repeats, recently played songs are fine again.
        assertEquals(listOf("Played", "Fresh"), engine.pick(candidates, state, setOf(candidates[1].id), on.copy(avoidRepeats = false)).map { it.title })
    }

    @Test
    fun `a second copy of the same recording is left out, judged by the matcher not the title`() {
        val state = queueOf("Song" to "Artist")
        val sameRecording = track("Song", "Artist", source = "online", key = "copy", isrc = null)
        val live = track("Song (Live)", "Artist", source = "online", key = "live")
        val picked = engine.pick(listOf(sameRecording, live, track("Other", "B", source = "online", key = "other")), state, emptySet(), on)
        assertFalse(picked.any { it.id == sameRecording.id }, "same song, same artist, different id: still the same recording")
        assertTrue(picked.any { it.id == live.id }, "a live take is a different recording")
    }

    @Test
    fun `the same artist twice in a row is avoided when it can be helped`() {
        val state = queueOf("A" to "X")
        val picked = engine.pick(
            listOf(track("B", "X", source = "online", key = "b"), track("C", "Y", source = "online", key = "c"), track("D", "X", source = "online", key = "d")),
            state, emptySet(), on,
        )
        assertEquals(listOf("C", "B", "D"), picked.map { it.title })
        // When it can't be helped, music beats silence.
        assertEquals(1, engine.pick(listOf(track("E", "X", source = "online", key = "e")), state, emptySet(), on).size)
    }

    @Test
    fun `seeds come from the current song's source, the current song last`() {
        val state = QueueState(
            items = listOf(
                QueueItem(QueueUid("1"), track("L", "X", source = "local", key = "l"), QueueOrigin.CONTEXT),
                QueueItem(QueueUid("2"), track("O1", "Y", source = "online", key = "o1"), QueueOrigin.CONTEXT),
                QueueItem(QueueUid("3"), track("O2", "Z", source = "online", key = "o2"), QueueOrigin.CONTEXT),
            ),
            currentIndex = 2,
        )
        assertEquals(listOf("O1", "O2"), engine.seeds(state).map { it.title })
    }
}
