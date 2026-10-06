package app.podium.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.podium.core.model.Explicitness
import app.podium.core.model.SourceId
import app.podium.core.model.Track
import app.podium.sources.api.aggregate.TrackGrouper
import app.podium.sources.api.aggregate.remember
import app.podium.sources.api.matching.MatchResult
import app.podium.sources.api.matching.MatchTier
import app.podium.sources.api.matching.TrackMatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Cross-source equivalence on disk (D-36): only EXACT is kept, the listener's "no" is final. */
@RunWith(AndroidJUnit4::class)
class DatabaseEquivalenceStoreTest {

    private val db = testDatabase()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var clock = 1_000L
    private val A = SourceId("a")
    private val B = SourceId("b")

    @After
    fun close() {
        scope.cancel()
        db.close()
    }

    /** A store as a fresh process would build it: same database, loaded from disk. */
    private suspend fun restart(): DatabaseEquivalenceStore = DatabaseEquivalenceStore(db, scope, { clock++ }).also { it.load() }

    private fun <T> run(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    private fun copy(title: String, source: SourceId, key: String, seconds: Long = 200, explicitness: Explicitness = Explicitness.UNKNOWN): Track =
        song(title, artist = "Band", album = "Record", source = source, key = key)
            .copy(durationMs = seconds * 1000, explicitness = explicitness)

    /** Group two copies the way search does, and remember what the grouper decided. */
    private fun DatabaseEquivalenceStore.learn(x: Track, y: Track) {
        TrackGrouper(TrackMatcher(), this::isRejected)
            .group(listOf(TrackGrouper.Ranked(x.source.sourceId, listOf(x)), TrackGrouper.Ranked(y.source.sourceId, listOf(y))))
            .forEach { remember(it) }
    }

    @Test
    fun `an exact match is kept, and survives a restart`() = run {
        val onA = copy("Signal", A, "1")
        val onB = copy("Signal", B, "9", seconds = 201)
        val store = restart()
        store.learn(onA, onB)
        store.flush()
        val row = db.equivalence().all().single()
        assertEquals(TrackEquivalenceEntity.EXACT, row.tier)
        assertEquals(TrackEquivalenceEntity.AUTO, row.decidedBy)
        assertTrue(row.evidenceJson.contains("Same title identity"), row.evidenceJson)

        val again = restart()
        assertEquals(listOf(onB.id), again.exactEquivalents(onA).map { it.id })
        assertEquals(listOf(onA.id), again.exactEquivalents(onB).map { it.id })
        assertEquals(MatchTier.EXACT, again.get(onA, onB)?.tier)
        assertEquals("Signal", again.exactEquivalents(onA).single().title, "the copy itself comes back, not just its id")
    }

    @Test
    fun `nothing below exact is ever kept`() = run {
        val store = restart()
        val onA = copy("Signal", A, "1")
        store.put(onA, copy("Signal", B, "9", seconds = 205), MatchResult(MatchTier.STRONG, 0.8f, emptyList()))
        store.put(onA, copy("Signal", B, "8"), MatchResult(MatchTier.POSSIBLE, 0.5f, emptyList()))
        store.flush()
        assertTrue(db.equivalence().all().isEmpty())
        assertTrue(restart().exactEquivalents(onA).isEmpty())
    }

    @Test
    fun `different versions, editions, covers and lengths are never stored as the same song`() = run {
        val studio = copy("Signal", A, "1")
        val store = restart()
        listOf(
            copy("Signal (Live)", B, "live"),
            copy("Signal (Remix)", B, "remix"),
            copy("Signal (Sped Up)", B, "sped"),
            copy("Signal (Nightcore)", B, "nightcore"),
            copy("Signal (Instrumental)", B, "inst"),
            copy("Other Song", B, "same-length"), // same length, different song
        ).forEach { store.learn(studio, it) }
        store.learn(copy("Signal", A, "e", explicitness = Explicitness.EXPLICIT), copy("Signal", B, "c", explicitness = Explicitness.CLEAN))
        store.learn(studio, song("Signal", artist = "Someone Else", album = "Record", source = B, key = "cover").copy(durationMs = 200_000))
        store.flush()
        assertTrue(db.equivalence().all().isEmpty(), "${db.equivalence().all()}")
    }

    @Test
    fun `not the same song is kept, survives a restart, and the pair is never re-linked`() = run {
        val onA = copy("Signal", A, "1")
        val onB = copy("Signal", B, "9")
        val store = restart()
        store.learn(onA, onB)
        store.reject(onA, onB)
        store.flush()
        val row = db.equivalence().all().single()
        assertEquals(TrackEquivalenceEntity.REJECTED, row.tier)
        assertEquals(TrackEquivalenceEntity.USER, row.decidedBy)

        // A new search finds the same two again: they stay apart, in memory and on disk.
        val again = restart()
        assertTrue(again.isRejected(onA.id, onB.id))
        again.learn(onA, onB)
        again.put(onA, onB, MatchResult(MatchTier.EXACT, 0.95f, emptyList()))
        again.flush()
        assertTrue(again.exactEquivalents(onA).isEmpty())
        assertEquals(TrackEquivalenceEntity.REJECTED, db.equivalence().all().single().tier)
    }

    @Test
    fun `the listener's word stands on disk even before the old decisions are loaded`() = run {
        val onA = copy("Signal", A, "1")
        val onB = copy("Signal", B, "9")
        restart().apply { reject(onA, onB); flush() }
        // A store that hasn't read the disk yet learns the pair: the rejection on disk wins.
        val cold = DatabaseEquivalenceStore(db, scope, { clock++ })
        cold.put(onA, onB, MatchResult(MatchTier.EXACT, 0.95f, emptyList()))
        cold.flush()
        assertEquals(TrackEquivalenceEntity.REJECTED, db.equivalence().all().single().tier)
        cold.load()
        assertTrue(cold.exactEquivalents(onA).isEmpty(), "loading applies the rejection")
    }

    @Test
    fun `three copies of one song are three pairs, each one the matcher actually made`() = run {
        val onA = copy("Signal", A, "1")
        val onB = copy("Signal", B, "9")
        val onC = copy("Signal", SourceId("c"), "5")
        val store = restart()
        TrackGrouper().group(
            listOf(TrackGrouper.Ranked(A, listOf(onA)), TrackGrouper.Ranked(B, listOf(onB)), TrackGrouper.Ranked(onC.source.sourceId, listOf(onC))),
        ).forEach(store::remember)
        store.flush()
        assertEquals(3, db.equivalence().all().size)
        assertEquals(setOf(onB.id, onC.id), restart().exactEquivalents(onA).map { it.id }.toSet())
    }

    @Test
    fun `remembering a pair never overwrites a library song's own row`() = run {
        val library = LibraryStore(db)
        val mine = song("Signal", artist = "Band", album = "Record", key = "lib").copy(durationMs = 200_000)
        library.sync(Local, "This phone", listOf(mine), emptyMap())
        val store = restart()
        store.put(mine.copy(title = "Stale title"), copy("Signal", Local, "lib2"), MatchResult(MatchTier.EXACT, 0.95f, emptyList()))
        store.flush()
        assertEquals("Signal", library.track(mine.id)?.title)
    }
}
