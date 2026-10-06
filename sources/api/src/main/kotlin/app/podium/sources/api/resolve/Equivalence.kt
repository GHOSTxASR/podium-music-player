package app.podium.sources.api.resolve

import app.podium.core.model.Track
import app.podium.core.model.TrackId
import app.podium.sources.api.matching.MatchResult
import app.podium.sources.api.matching.MatchTier

/** Remembers matcher decisions so equivalence is computed once and can be overridden by the user. */
interface EquivalenceStore {
    fun get(a: Track, b: Track): MatchResult?
    fun put(a: Track, b: Track, result: MatchResult)

    /** The user said "not the same song": never match these again. */
    fun reject(a: Track, b: Track)

    /**
     * Copies on other sources already known to be EXACTly the same recording as [track] (D-35) —
     * learnt when several sources answered the same question. Never anything below EXACT.
     */
    fun exactEquivalents(track: Track): List<Track> = emptyList()
}

/**
 * Decisions in memory, bounded: the most recently touched [capacity] songs keep their EXACT links
 * (the copies themselves, so the resolver can play one without searching again). The user's
 * rejections are kept regardless.
 */
class InMemoryEquivalenceStore(private val capacity: Int = 4_000) : EquivalenceStore {
    private val decisions = mutableMapOf<Pair<String, String>, MatchResult>()

    /** Song → its EXACT equivalents, in least-recently-used order. */
    private val links = LinkedHashMap<String, MutableMap<String, Track>>(64, 0.75f, true)

    /** Forget the least recently used songs beyond [capacity]: their links, and every link to them. */
    private fun evict() {
        while (links.size > capacity) {
            val eldest = links.keys.first()
            val others = links.remove(eldest)?.keys.orEmpty()
            others.forEach { other ->
                links[other]?.remove(eldest)
                decisions.remove(pairKey(eldest, other))
            }
        }
    }

    private fun pairKey(a: String, b: String) = if (a <= b) a to b else b to a

    private fun key(a: Track, b: Track) = pairKey(a.id.value, b.id.value)

    @Synchronized override fun get(a: Track, b: Track) = decisions[key(a, b)]

    @Synchronized override fun put(a: Track, b: Track, result: MatchResult) {
        val k = key(a, b)
        if (isRejected(k)) return
        decisions[k] = result
        if (result.tier == MatchTier.EXACT && a.id != b.id) {
            links.getOrPut(a.id.value) { mutableMapOf() }[b.id.value] = b
            links.getOrPut(b.id.value) { mutableMapOf() }[a.id.value] = a
            evict()
        } else {
            unlink(a.id, b.id)
        }
    }

    @Synchronized override fun reject(a: Track, b: Track) {
        decisions[key(a, b)] = REJECTED
        unlink(a.id, b.id)
    }

    @Synchronized override fun exactEquivalents(track: Track): List<Track> =
        links[track.id.value]?.values?.toList().orEmpty()

    private fun isRejected(k: Pair<String, String>) = decisions[k] === REJECTED

    private fun unlink(a: TrackId, b: TrackId) {
        links[a.value]?.remove(b.value)
        links[b.value]?.remove(a.value)
    }

    private companion object {
        val REJECTED = MatchResult(MatchTier.NO_MATCH, 0f, emptyList())
    }
}
