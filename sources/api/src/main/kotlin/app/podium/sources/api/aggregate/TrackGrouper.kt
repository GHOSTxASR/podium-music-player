package app.podium.sources.api.aggregate

import app.podium.core.model.Availability
import app.podium.core.model.SourceId
import app.podium.core.model.Track
import app.podium.core.model.TrackId
import app.podium.sources.api.matching.MatchResult
import app.podium.sources.api.matching.MatchTier
import app.podium.sources.api.matching.TrackMatcher
import app.podium.sources.api.matching.TrackNormalizer
import app.podium.sources.api.resolve.EquivalenceStore

/**
 * One song as several sources have it (D-35). [track] is the copy Podium shows and plays first;
 * [alternates] are copies on other sources that the matcher judged EXACTly the same recording —
 * each one safe to play in place of every other (D-17), so one row can stand for all of them.
 * Every copy keeps its own source identity; nothing is rewritten into another source.
 */
data class TrackGroup(
    val track: Track,
    val alternates: List<Track> = emptyList(),
    /** The matcher's decision for each pair of members, for the resolver to reuse. */
    val decisions: Map<Pair<TrackId, TrackId>, MatchResult> = emptyMap(),
) {
    val members: List<Track> get() = listOf(track) + alternates
}

/** Tell the resolver which copies are interchangeable, so a miss on one plays another (D-35). */
fun EquivalenceStore.remember(group: TrackGroup) {
    val byId = group.members.associateBy { it.id }
    group.decisions.forEach { (pair, result) ->
        val a = byId[pair.first] ?: return@forEach
        val b = byId[pair.second] ?: return@forEach
        put(a, b, result)
    }
}

/**
 * Folds tracks from several sources into one row per recording (D-35) — conservatively, by the
 * rules the matcher already enforces for playback fallback:
 *
 * - **EXACT only.** STRONG and POSSIBLE never merge; live/remix/acoustic/sped-up/cover/explicit-vs-
 *   clean differences, length and artist rules are all the matcher's, unchanged.
 * - **One copy per source.** A source's own results are never merged with each other.
 * - **Ambiguity keeps rows apart.** A track that EXACT-matches two rows, or two tracks of one
 *   source that EXACT-match the same row, join nothing.
 *
 * The order is deterministic: the sources' results are interleaved by rank (sources in priority
 * order), and a row sits where its first copy came. The copy shown is the playable one from the
 * most preferred source.
 */
class TrackGrouper(
    private val matcher: TrackMatcher = TrackMatcher(),
    /** Pairs the listener said are not the same song (D-36): never grouped, whatever the matcher says. */
    private val rejected: (TrackId, TrackId) -> Boolean = { _, _ -> false },
) {

    /** One source's answer: its tracks in its own rank order. */
    data class Ranked(val source: SourceId, val tracks: List<Track>)

    /**
     * Group [results] (sources in priority order), continuing [existing] rows from earlier pages:
     * a new copy of a song already shown joins that row instead of appearing again. Returns every
     * row — [existing] first, in place — then the new ones.
     */
    fun group(results: List<Ranked>, existing: List<TrackGroup> = emptyList()): List<TrackGroup> {
        val rank = buildMap {
            results.forEach { putIfAbsent(it.source, size) }
            existing.flatMap { it.members }.forEach { putIfAbsent(it.source.sourceId, size) }
        }
        val session = Session(existing)
        val sameSource = results.associate { it.source to it.tracks }
        for (t in interleave(results)) {
            val s = t.source.sourceId
            if (session.contains(t.id)) continue
            val eligible = session.rows.indices.filter { r -> session.rows[r].none { it.source.sourceId == s } && session.rows[r].all { session.exact(it, t) } }
            val row = eligible.singleOrNull()
            val rival = row != null && sameSource[s].orEmpty().any { other -> other.id != t.id && session.rows[row].all { session.exact(it, other) } }
            if (row != null && !rival) session.join(row, t) else session.start(t)
        }
        // A row already on screen keeps the copy it shows; new copies only join it.
        return session.rows.mapIndexed { i, members -> session.toGroup(members, rank, keep = existing.getOrNull(i)?.track) }
    }

    /**
     * A list of songs with later copies of an earlier song removed (EXACT, from different sources):
     * for lists the listener built (liked songs, history), where each entry stays its own copy.
     */
    fun distinctRecordings(tracks: List<Track>): List<Track> {
        val session = Session(emptyList())
        val kept = mutableListOf<Track>()
        for (t in tracks) {
            if (kept.any { it.id == t.id }) continue
            if (kept.any { it.source.sourceId != t.source.sourceId && session.exact(it, t) }) continue
            kept += t
        }
        return kept
    }

    private fun interleave(results: List<Ranked>): List<Track> {
        val deepest = results.maxOfOrNull { it.tracks.size } ?: 0
        return (0 until deepest).flatMap { i -> results.mapNotNull { it.tracks.getOrNull(i) } }
    }

    /** The rows being built, with matcher answers cached (normalizing a title is the costly part). */
    private inner class Session(existing: List<TrackGroup>) {
        val rows: MutableList<MutableList<Track>> = existing.map { it.members.toMutableList() }.toMutableList()
        private val decisions = mutableMapOf<Pair<TrackId, TrackId>, MatchResult>()
        private val normalized = mutableMapOf<TrackId, Track>()

        init {
            existing.forEach { decisions.putAll(it.decisions) }
        }

        fun contains(id: TrackId) = rows.any { row -> row.any { it.id == id } }

        fun join(row: Int, t: Track) {
            rows[row] += t
        }

        fun start(t: Track) {
            rows += mutableListOf(t)
        }

        fun exact(a: Track, b: Track): Boolean = !rejected(a.id, b.id) && decision(a, b).tier == MatchTier.EXACT

        fun decision(a: Track, b: Track): MatchResult {
            val key = if (a.id.value <= b.id.value) a.id to b.id else b.id to a.id
            return decisions.getOrPut(key) { matcher.match(norm(a), norm(b)) }
        }

        private fun norm(t: Track) = normalized.getOrPut(t.id) { TrackNormalizer.normalize(t) }

        fun toGroup(members: List<Track>, rank: Map<SourceId, Int>, keep: Track?): TrackGroup {
            val byPreference = members.sortedBy { rank[it.source.sourceId] ?: Int.MAX_VALUE }
            val shown = keep ?: byPreference.firstOrNull { it.availability !is Availability.Unavailable } ?: byPreference.first()
            val pairs = members.flatMapIndexed { i, a -> members.drop(i + 1).map { b -> a to b } }
                .associate { (a, b) -> (if (a.id.value <= b.id.value) a.id to b.id else b.id to a.id) to decision(a, b) }
            return TrackGroup(shown, byPreference - shown, pairs)
        }
    }
}
