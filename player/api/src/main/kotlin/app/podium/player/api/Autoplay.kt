package app.podium.player.api

import app.podium.core.common.Outcome
import app.podium.core.model.ArtistId
import app.podium.core.model.SourceId
import app.podium.core.model.Track
import app.podium.core.model.TrackId
import app.podium.sources.api.Capability
import app.podium.sources.api.SourceRegistry
import app.podium.sources.api.matching.MatchTier
import app.podium.sources.api.matching.TrackMatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Settings ▸ Autoplay (D-34). */
data class AutoplaySettings(
    /** Keep the music going when the queue runs out. */
    val enabled: Boolean = true,
    /** Let the online source recommend what comes next. */
    val recommendations: Boolean = true,
    /** Skip songs played recently, and the same artist twice in a row. */
    val avoidRepeats: Boolean = true,
)

/** Where suggestions come from, in the order an engine tries them. */
enum class RecommendationStrategy { SOURCE_RECOMMENDATIONS, RELATED_TRACKS, RELATED_ARTISTS, TRENDING, USER_LIBRARY, HISTORY }

data class RecommendationRequest(
    /** The songs to go on from, most recent last. All from one source. */
    val seeds: List<Track>,
    val exclude: Set<TrackId>,
    val limit: Int,
)

/** Provider-neutral suggestions for what to play next (D-34). */
interface RecommendationEngine {
    suspend fun recommend(request: RecommendationRequest): List<Track>
    suspend fun artistRadio(artist: ArtistId, source: SourceId, limit: Int, exclude: Set<TrackId>): List<Track>
}

/**
 * Asks the sources for what comes next (their RecommendationFacets) — every enabled source of the
 * seeds' environment that recommends, at once (D-35), never another environment: local songs never
 * slip into online autoplay and vice versa (D-34). A source is asked about the seeds it holds: its
 * own copies, or copies already known to be EXACTly the same recording ([equivalents]); a source
 * holding none of them isn't asked, because it can't relate to them. The current song's source
 * leads, then the others by the listener's priority; suggestions are interleaved and each keeps its
 * own source. Podium doesn't make suggestions up — a source that doesn't recommend gives nothing.
 * Duplicates across sources are the autoplay engine's job (matcher, EXACT).
 */
class SourceRecommendationEngine(
    private val registry: SourceRegistry,
    private val equivalents: (Track) -> List<Track> = { emptyList() },
) : RecommendationEngine {

    override suspend fun recommend(request: RecommendationRequest): List<Track> {
        val lead = request.seeds.lastOrNull()?.source?.sourceId ?: return emptyList()
        val environment = registry.get(lead)?.descriptor?.environment ?: return emptyList()
        val sources = registry.ordered(environment)
            .filter { it.recommendations != null && it.capabilities.value.isUsable(Capability.RECOMMENDATIONS) }
            .sortedBy { if (it.descriptor.id == lead) 0 else 1 } // stable: the rest keep priority order
        val asks = sources.mapNotNull { source ->
            val id = source.descriptor.id
            val seeds = request.seeds.flatMap { seed -> if (seed.source.sourceId == id) listOf(seed) else equivalents(seed).filter { it.source.sourceId == id } }
                .distinctBy { it.id }
            if (seeds.isEmpty()) null else source to seeds
        }
        val answers = coroutineScope {
            asks.map { (source, seeds) ->
                async {
                    when (val r = source.recommendations!!.related(seeds, request.limit, request.exclude)) {
                        is Outcome.Success -> r.value.filter { it.source.sourceId == source.descriptor.id }
                        is Outcome.Failure -> emptyList()
                    }
                }
            }.awaitAll()
        }
        val deepest = answers.maxOfOrNull { it.size } ?: 0
        return (0 until deepest).flatMap { i -> answers.mapNotNull { it.getOrNull(i) } }.distinctBy { it.id }
    }

    override suspend fun artistRadio(artist: ArtistId, source: SourceId, limit: Int, exclude: Set<TrackId>): List<Track> {
        val facet = facetOf(source) ?: return emptyList()
        return when (val r = facet.artistRadio(artist, limit, exclude)) {
            is Outcome.Success -> r.value.filter { it.source.sourceId == source }
            is Outcome.Failure -> emptyList()
        }
    }

    private fun facetOf(id: SourceId) = registry.get(id)
        ?.takeIf { registry.isEnabled(id) && it.capabilities.value.isUsable(Capability.RECOMMENDATIONS) }
        ?.recommendations
}

/**
 * Decides when the queue needs more and which suggestions are fit to add (D-34, D-35): nothing
 * already queued or (with avoid-repeats) recently played — nor a copy of one on another source —
 * no second copy of the same recording, judged by the matcher, never by title alone, and no
 * artist twice in a row when it can be helped. Suggestions may come from any source of the current
 * song's environment, never another environment ([sameEnvironment]; by default: the same source
 * only). Explicit queue items are never touched; suggestions only ever go at the end.
 */
class AutoplayEngine(
    private val matcher: TrackMatcher = TrackMatcher(),
    private val sameEnvironment: (SourceId, SourceId) -> Boolean = { a, b -> a == b },
    /** Copies of a song already known to be the same recording on other sources. */
    private val equivalents: (Track) -> List<Track> = { emptyList() },
) {

    /** The queue is about to run out: the current song is the last or second-to-last. */
    fun needsMore(state: QueueState, settings: AutoplaySettings): Boolean =
        settings.enabled && settings.recommendations && state.current != null && state.upNext.size < LOW_WATER

    /** Seeds: the last few songs from the current song's environment, the current song last. */
    fun seeds(state: QueueState): List<Track> {
        val current = state.current?.track ?: return emptyList()
        val source = current.source.sourceId
        return (state.items.take(state.currentIndex + 1).map { it.track }.filter { sameEnvironment(it.source.sourceId, source) }).takeLast(SEEDS)
    }

    /** What to ask the source to leave out. */
    fun exclusions(state: QueueState, recentlyPlayed: Set<TrackId>, settings: AutoplaySettings): Set<TrackId> =
        state.items.mapTo(HashSet()) { it.track.id } + if (settings.avoidRepeats) recentlyPlayed else emptySet()

    /** The suggestions fit to append, in order, at most [limit]. */
    fun pick(candidates: List<Track>, state: QueueState, recentlyPlayed: Set<TrackId>, settings: AutoplaySettings, limit: Int = BATCH): List<Track> {
        val source = state.current?.track?.source?.sourceId ?: return emptyList()
        val queued = state.items.map { it.track }
        val excluded = exclusions(state, recentlyPlayed, settings)
        val accepted = mutableListOf<Track>()
        val held = mutableListOf<Track>()
        var lastArtist = queued.lastOrNull()?.artistDisplay?.lowercase()
        for (track in candidates) {
            if (accepted.size >= limit) break
            if (!sameEnvironment(track.source.sourceId, source)) continue // never cross environments
            if (track.id in excluded || accepted.any { it.id == track.id }) continue
            if (equivalents(track).any { it.id in excluded }) continue // its twin is queued or was just played
            if (isSameRecording(track, queued + accepted)) continue
            val artist = track.artistDisplay.lowercase()
            if (settings.avoidRepeats && artist == lastArtist) {
                held += track
                continue
            }
            accepted += track
            lastArtist = artist
            // Give a held-back song its turn once another artist has played in between.
            held.firstOrNull { it.artistDisplay.lowercase() != lastArtist }?.let {
                if (accepted.size < limit) {
                    accepted += it
                    held -= it
                    lastArtist = it.artistDisplay.lowercase()
                }
            }
        }
        // When every suggestion is by the same artist, it can't be helped: better music than silence.
        held.forEach { if (accepted.size < limit) accepted += it }
        return accepted
    }

    private fun isSameRecording(track: Track, others: List<Track>): Boolean =
        others.any { other -> other.id == track.id || matcher.match(track, other).tier == MatchTier.EXACT }

    companion object {
        const val LOW_WATER = 2
        const val SEEDS = 5
        const val BATCH = 10
    }
}
