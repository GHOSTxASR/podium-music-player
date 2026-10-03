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
 * Asks the seeds' own source for recommendations (its RecommendationFacet), never another source:
 * an online radio stays on that source, and local songs never slip into online autoplay. A source
 * without the RECOMMENDATIONS capability gives nothing; Podium doesn't make suggestions up.
 */
class SourceRecommendationEngine(private val registry: SourceRegistry) : RecommendationEngine {

    override suspend fun recommend(request: RecommendationRequest): List<Track> {
        val sourceId = request.seeds.lastOrNull()?.source?.sourceId ?: return emptyList()
        val facet = facetOf(sourceId) ?: return emptyList()
        return when (val r = facet.related(request.seeds.filter { it.source.sourceId == sourceId }, request.limit, request.exclude)) {
            is Outcome.Success -> r.value.filter { it.source.sourceId == sourceId }
            is Outcome.Failure -> emptyList()
        }
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
 * Decides when the queue needs more and which suggestions are fit to add (D-34): nothing already
 * queued or (with avoid-repeats) recently played, no second copy of the same recording — judged
 * by the matcher, never by title alone — and no artist twice in a row when it can be helped.
 * Explicit queue items are never touched; suggestions only ever go at the end.
 */
class AutoplayEngine(private val matcher: TrackMatcher = TrackMatcher()) {

    /** The queue is about to run out: the current song is the last or second-to-last. */
    fun needsMore(state: QueueState, settings: AutoplaySettings): Boolean =
        settings.enabled && settings.recommendations && state.current != null && state.upNext.size < LOW_WATER

    /** Seeds: the last few songs from the current song's source, the current song last. */
    fun seeds(state: QueueState): List<Track> {
        val current = state.current?.track ?: return emptyList()
        val source = current.source.sourceId
        return (state.items.take(state.currentIndex + 1).map { it.track }.filter { it.source.sourceId == source }).takeLast(SEEDS)
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
            if (track.source.sourceId != source) continue // never cross environments or sources
            if (track.id in excluded || accepted.any { it.id == track.id }) continue
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
