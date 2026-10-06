package app.podium.core.database

import androidx.room3.withWriteTransaction
import app.podium.core.model.Track
import app.podium.core.model.TrackId
import app.podium.sources.api.matching.MatchResult
import app.podium.sources.api.matching.MatchTier
import app.podium.sources.api.resolve.EquivalenceStore
import app.podium.sources.api.resolve.InMemoryEquivalenceStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Cross-source equivalence that survives restarts (D-36). The resolver, the catalogue and autoplay
 * read the in-memory [memory] synchronously; every change is written through to `track_equivalence`
 * in order, and [load] fills memory back from it at startup.
 *
 * - Only an **EXACT** match the matcher made, or the listener's **"not the same song"**, is stored.
 *   Nothing below EXACT is kept, so nothing uncertain survives a restart.
 * - The listener's word stands: a rejection is never overwritten by a later automatic match — not
 *   in memory, not on disk — so a wrong match can't quietly come back.
 * - Decisions don't depend on sources being on, reachable or in any order: the resolver checks
 *   those when it uses a copy.
 */
class DatabaseEquivalenceStore(
    private val db: PodiumDatabase,
    scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val memory: InMemoryEquivalenceStore = InMemoryEquivalenceStore(),
) : EquivalenceStore {

    private val dao = db.equivalence()
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        // One writer, in order: a rejection made right after a match is never overtaken by it.
        scope.launch {
            for (write in writes) {
                runCatching { write() }.onFailure { android.util.Log.w("PodiumEquivalence", "could not store a decision", it) }
            }
        }
    }

    override fun get(a: Track, b: Track): MatchResult? = memory.get(a, b)

    override fun isRejected(a: TrackId, b: TrackId): Boolean = memory.isRejected(a, b)

    override fun exactEquivalents(track: Track): List<Track> = memory.exactEquivalents(track)

    override fun put(a: Track, b: Track, result: MatchResult) {
        if (memory.isRejected(a.id, b.id)) return
        memory.put(a, b, result)
        if (result.tier == MatchTier.EXACT && a.id != b.id) writes.trySend { persistExact(a, b, result) }
    }

    override fun reject(a: Track, b: Track) = reject(a.id, b.id)

    /** "Not the same song", by ids: final, and kept across restarts. */
    fun reject(a: TrackId, b: TrackId) {
        if (a == b) return
        memory.reject(a, b)
        writes.trySend { persistRejection(a, b) }
    }

    /** Read every stored decision into memory. Returns how many there were. */
    suspend fun load(): Int {
        val rows = dao.all()
        val ids = rows.filter { it.tier == TrackEquivalenceEntity.EXACT }.flatMap { listOf(it.trackA, it.trackB) }.distinct()
        val tracks = ids.chunked(500).flatMap { db.library().tracks(it) }.associate { it.id to TrackMapping.toTrack(it) }
        rows.forEach { row ->
            when (row.tier) {
                TrackEquivalenceEntity.REJECTED -> memory.reject(TrackId(row.trackA), TrackId(row.trackB))
                TrackEquivalenceEntity.EXACT -> {
                    val a = tracks[row.trackA] ?: return@forEach
                    val b = tracks[row.trackB] ?: return@forEach
                    memory.put(a, b, MatchResult(MatchTier.EXACT, row.confidence, emptyList()))
                }
            }
        }
        return rows.size
    }

    /** Wait until every change made so far is on disk (for tests and orderly shutdown). */
    suspend fun flush() {
        val done = CompletableDeferred<Unit>()
        writes.send { done.complete(Unit) }
        done.await()
    }

    private fun ordered(a: TrackId, b: TrackId) = if (a.value <= b.value) a.value to b.value else b.value to a.value

    private suspend fun persistExact(a: Track, b: Track, result: MatchResult) {
        // The copies themselves, so the pair can be rebuilt after a restart.
        db.cacheTracks(listOf(a, b), now)
        val (x, y) = ordered(a.id, b.id)
        db.withWriteTransaction {
            if (dao.get(x, y)?.decidedBy == TrackEquivalenceEntity.USER) return@withWriteTransaction
            dao.put(
                TrackEquivalenceEntity(
                    trackA = x,
                    trackB = y,
                    tier = TrackEquivalenceEntity.EXACT,
                    confidence = result.confidence,
                    evidenceJson = json.encodeToString(ListSerializer(String.serializer()), result.evidence.map { it.description }),
                    decidedBy = TrackEquivalenceEntity.AUTO,
                    decidedAt = now(),
                ),
            )
        }
    }

    private suspend fun persistRejection(a: TrackId, b: TrackId) {
        val (x, y) = ordered(a, b)
        dao.put(TrackEquivalenceEntity(x, y, TrackEquivalenceEntity.REJECTED, 0f, "[]", TrackEquivalenceEntity.USER, now()))
    }

    private companion object {
        val json = Json
    }
}
