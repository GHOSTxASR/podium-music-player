package app.podium.sources.api.resolve

import app.podium.core.common.Clock
import app.podium.core.common.Outcome
import app.podium.core.common.PodiumError
import app.podium.core.model.SourceId
import app.podium.core.model.Track
import app.podium.sources.api.Capability
import app.podium.sources.api.FacetResolution
import app.podium.sources.api.HealthOutcome
import app.podium.sources.api.MissReason
import app.podium.sources.api.MusicSource
import app.podium.sources.api.PlayableMedia
import app.podium.sources.api.PlaybackTarget
import app.podium.sources.api.Purpose
import app.podium.sources.api.QualityRequest
import app.podium.sources.api.ResolutionPath
import app.podium.sources.api.SearchQuery
import app.podium.sources.api.SourceHealthMonitor
import app.podium.sources.api.SourceRegistry
import app.podium.sources.api.aggregate.SourceFanOut
import app.podium.sources.api.matching.MatchQueryBuilder
import app.podium.sources.api.matching.MatchResult
import app.podium.sources.api.matching.MatchTier
import app.podium.sources.api.matching.TrackMatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Whether a queue item may fall back to another source's copy. Only EXACT matches ever qualify. */
enum class FallbackPolicy {
    /** Fallback to an EXACT match on another source, before playback starts. Default. */
    EXACT_ONLY,

    /** Never leave the track's own source (or an owned copy of this exact track). */
    NONE,
}

/**
 * The result of resolution for one play, pinned to a queue item so the same track is never resolved
 * twice and never switches source mid-track.
 */
data class Selection(
    val target: PlaybackTarget,
    val path: ResolutionPath,
    /** The source whose copy plays. Differs from the queued track's source after a fallback. */
    val servedBy: SourceId,
    /** The track actually served (the matched equivalent after a fallback). */
    val servedTrack: Track,
    val match: MatchResult? = null,
    val resolvedAtMillis: Long,
) {
    val media: PlayableMedia? get() = (target as? PlaybackTarget.DirectStream)?.media

    /** Still usable at [nowMillis]? Remote/embedded targets don't expire here. */
    fun isUsableAt(nowMillis: Long): Boolean = media?.isUsableAt(nowMillis) ?: true
}

data class ResolveRequest(
    val track: Track,
    val purpose: Purpose = Purpose.PLAYBACK,
    val quality: QualityRequest = QualityRequest.Maximum,
    /** A previous selection for this queue item; reused while valid. */
    val pinned: Selection? = null,
    val fallbackPolicy: FallbackPolicy = FallbackPolicy.EXACT_ONLY,
    /**
     * True when re-resolving because the pinned URL stopped working mid-track: only the same source
     * may answer — audio is never swapped to a different copy mid-track (D-18).
     */
    val refreshPinnedSourceOnly: Boolean = false,
)

/** One step of the pipeline, for explainability and logs. */
data class ResolveAttempt(
    val step: ResolutionPath,
    val source: SourceId?,
    val kind: Kind,
    val healthOutcome: HealthOutcome? = null,
    val missReason: MissReason? = null,
    val detail: String? = null,
) {
    enum class Kind { RESOLVED, REUSED, MISS, FAILED, SKIPPED }

    override fun toString(): String =
        "$step@${source?.value ?: "-"}: $kind" + listOfNotNull(healthOutcome, missReason, detail).joinToString(prefix = " ", separator = " ")
}

sealed interface ResolveOutcome {
    val attempts: List<ResolveAttempt>

    data class Resolved(val selection: Selection, val reused: Boolean, override val attempts: List<ResolveAttempt>) :
        ResolveOutcome

    data class Miss(val reason: MissReason, override val attempts: List<ResolveAttempt>) : ResolveOutcome

    data class Failed(val outcome: HealthOutcome, val detail: String?, override val attempts: List<ResolveAttempt>) :
        ResolveOutcome
}

/** A copy the user owns (a verified download, a local file) of this exact track or an EXACT equivalent. */
fun interface OwnedCopyLocator {
    suspend fun find(track: Track): Pair<Track, PlayableMedia>?
}

/**
 * Turns a queued track into a playback [Selection] (MUSIC_SOURCE_ARCHITECTURE §8):
 *
 *   pinned selection (still valid) → owned copy → the track's own source → EXACT-match fallback
 *
 * Fallback never changes the recording: it requires an EXACT match, which by construction never
 * crosses live/studio, remix/original, explicit/clean, or different-artist boundaries. It happens
 * only here, before playback starts; a playing item is never re-resolved to a different source.
 */
class StreamResolver(
    private val registry: SourceRegistry,
    private val health: SourceHealthMonitor,
    private val matcher: TrackMatcher,
    private val clock: Clock,
    private val ownedCopies: List<OwnedCopyLocator> = emptyList(),
    private val equivalence: EquivalenceStore = InMemoryEquivalenceStore(),
    private val fallbackSearchLimit: Int = 10,
    /** How long one source may take to resolve or search before it counts as a failure and the next is tried. */
    private val sourceTimeoutMillis: Long = DEFAULT_SOURCE_TIMEOUT_MILLIS,
) {
    private val inFlightLock = Mutex()
    private val inFlight = mutableMapOf<String, CompletableDeferred<ResolveOutcome>>()

    suspend fun resolve(request: ResolveRequest): ResolveOutcome {
        request.pinned?.let { pinned ->
            if (!request.refreshPinnedSourceOnly && pinned.isUsableAt(clock.nowMillis())) {
                return ResolveOutcome.Resolved(
                    pinned,
                    reused = true,
                    attempts = listOf(ResolveAttempt(pinned.path, pinned.servedBy, ResolveAttempt.Kind.REUSED)),
                )
            }
        }
        // Concurrent requests for the same track and quality share one resolution.
        val key = "${request.track.id.value}|${request.quality}|${request.refreshPinnedSourceOnly}"
        val (deferred, owner) = inFlightLock.withLock {
            inFlight[key]?.let { it to false } ?: CompletableDeferred<ResolveOutcome>().also { inFlight[key] = it }.let { it to true }
        }
        if (!owner) return deferred.await()
        return try {
            val result = doResolve(request)
            deferred.complete(result)
            result
        } catch (t: Throwable) {
            deferred.completeExceptionally(t)
            throw t
        } finally {
            inFlightLock.withLock { inFlight.remove(key) }
        }
    }

    private suspend fun doResolve(request: ResolveRequest): ResolveOutcome {
        val track = request.track
        val attempts = mutableListOf<ResolveAttempt>()

        if (request.refreshPinnedSourceOnly) {
            val pinnedSource = request.pinned?.servedBy ?: track.source.sourceId
            val servedTrack = request.pinned?.servedTrack ?: track
            val path = request.pinned?.path ?: ResolutionPath.OWN_SOURCE
            // The pinned stream was refused: the source mustn't answer with the same one.
            runCatching { registry.get(pinnedSource)?.playback?.forgetStream(servedTrack) }
            return resolveFrom(pinnedSource, servedTrack, request, path, null, attempts)
                ?: lastFailure(attempts)
        }

        // The listener chose a song in one environment; it plays in that environment (D-36). An
        // unknown source (no longer registered) has no environment to fall back within.
        val environment = environmentOf(track.source.sourceId)

        // 1. An owned copy (download / local file) of this track — the same song, or an EXACT copy
        //    in the same environment; never a copy from the other environment.
        for (locator in ownedCopies) {
            val found = locator.find(track) ?: continue
            val (ownedTrack, media) = found
            if (ownedTrack.id != track.id && (environment == null || environmentOf(ownedTrack.source.sourceId) != environment)) continue
            attempts += ResolveAttempt(ResolutionPath.OWNED_COPY, media.sourceId, ResolveAttempt.Kind.RESOLVED)
            return ResolveOutcome.Resolved(
                Selection(
                    target = PlaybackTarget.DirectStream(ownedTrack.id, media),
                    path = ResolutionPath.OWNED_COPY,
                    servedBy = media.sourceId,
                    servedTrack = ownedTrack,
                    resolvedAtMillis = clock.nowMillis(),
                ),
                reused = false,
                attempts = attempts,
            )
        }

        // 2. The track's own source.
        resolveFrom(track.source.sourceId, track, request, ResolutionPath.OWN_SOURCE, null, attempts)
            ?.let { return it }

        // 3. EXACT-match fallback on other enabled sources of the same environment, in user priority
        //    order — never LOCAL <-> ONLINE (D-36). A copy already known to be EXACT (several sources
        //    answered the same search, D-35) is used directly; otherwise the source is searched and
        //    the matcher decides.
        if (request.fallbackPolicy == FallbackPolicy.EXACT_ONLY && request.purpose != Purpose.PREFETCH && environment != null) {
            for (source in registry.ordered(environment)) {
                if (source.descriptor.id == track.source.sourceId) continue
                val candidate = knownExactEquivalent(source, track) ?: findExactEquivalent(source, track, attempts) ?: continue
                resolveFrom(
                    source.descriptor.id, candidate.first, request, ResolutionPath.EXACT_FALLBACK, candidate.second, attempts,
                )?.let { return it }
            }
        }
        return lastFailure(attempts)
    }

    /** Resolve [track] from [sourceId]; null means "try the next step". Records health. */
    private suspend fun resolveFrom(
        sourceId: SourceId,
        track: Track,
        request: ResolveRequest,
        path: ResolutionPath,
        match: MatchResult?,
        attempts: MutableList<ResolveAttempt>,
    ): ResolveOutcome? {
        val source = registry.get(sourceId)
        if (source == null || !registry.isEnabled(sourceId)) {
            attempts += ResolveAttempt(path, sourceId, ResolveAttempt.Kind.SKIPPED, missReason = MissReason.SOURCE_UNAVAILABLE)
            return null
        }
        if (!health.canAttempt(sourceId)) {
            attempts += ResolveAttempt(
                path, sourceId, ResolveAttempt.Kind.SKIPPED,
                missReason = MissReason.SOURCE_UNAVAILABLE,
                detail = health.health(sourceId)::class.simpleName,
            )
            return null
        }
        val playback = source.playback
        if (playback == null || !source.capabilities.value.isUsable(Capability.DIRECT_STREAM) &&
            !source.capabilities.value.isUsable(Capability.REMOTE_PLAYBACK) &&
            !source.capabilities.value.isUsable(Capability.EMBEDDED_PLAYBACK)
        ) {
            attempts += ResolveAttempt(path, sourceId, ResolveAttempt.Kind.SKIPPED, missReason = MissReason.UNSUPPORTED_ROUTE)
            return null
        }
        val result = try {
            withTimeoutOrNull(playback.resolveTimeoutMillis ?: sourceTimeoutMillis) { playback.resolve(track, request.quality, request.purpose) }
                ?: FacetResolution.Failed(HealthOutcome.NETWORK_FAILURE, "timed out")
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            // A source that throws (a malformed answer, a bug) has failed — that isn't a miss.
            FacetResolution.Failed(HealthOutcome.UNKNOWN, t.javaClass.simpleName)
        }
        return when (result) {
            is FacetResolution.Resolved -> {
                health.record(sourceId, HealthOutcome.SUCCESS)
                attempts += ResolveAttempt(path, sourceId, ResolveAttempt.Kind.RESOLVED)
                ResolveOutcome.Resolved(
                    Selection(result.target, path, sourceId, track, match, clock.nowMillis()),
                    reused = false,
                    attempts = attempts,
                )
            }
            is FacetResolution.Miss -> {
                health.record(sourceId, HealthOutcome.MISS)
                attempts += ResolveAttempt(path, sourceId, ResolveAttempt.Kind.MISS, missReason = result.reason)
                null
            }
            is FacetResolution.Failed -> {
                health.record(sourceId, result.outcome, result.retryAfterMillis)
                attempts += ResolveAttempt(path, sourceId, ResolveAttempt.Kind.FAILED, healthOutcome = result.outcome, detail = result.detail)
                null
            }
        }
    }

    private fun environmentOf(source: SourceId) = registry.get(source)?.descriptor?.environment

    /** A copy on [source] already decided EXACT for [track] (never anything less). */
    private fun knownExactEquivalent(source: MusicSource, track: Track): Pair<Track, MatchResult>? {
        val copy = equivalence.exactEquivalents(track).firstOrNull { it.source.sourceId == source.descriptor.id } ?: return null
        val decision = equivalence.get(track, copy)?.takeIf { it.tier == MatchTier.EXACT } ?: return null
        return copy to decision
    }

    private suspend fun findExactEquivalent(
        source: MusicSource,
        track: Track,
        attempts: MutableList<ResolveAttempt>,
    ): Pair<Track, MatchResult>? {
        val catalog = source.catalog ?: return null
        if (!source.capabilities.value.isUsable(Capability.SEARCH)) return null
        if (!health.canAttempt(source.descriptor.id)) return null
        val candidates = linkedMapOf<String, Track>()
        for (query in MatchQueryBuilder.queries(track)) {
            val r = try {
                withTimeoutOrNull(sourceTimeoutMillis) { catalog.search(SearchQuery(query, fallbackSearchLimit)) }
                    ?: Outcome.Failure(PodiumError.Network("Timed out"))
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Outcome.Failure(PodiumError.Unexpected(t.javaClass.simpleName))
            }
            when (r) {
                is Outcome.Success -> r.value.tracks.forEach { candidates.putIfAbsent(it.id.value, it) }
                is Outcome.Failure -> {
                    // A broken search is a failure and counts as one; finding nothing is a miss.
                    SourceFanOut.outcomeOf(r.error, signsIn = source.auth != null)?.let { health.record(source.descriptor.id, it) }
                    attempts += ResolveAttempt(
                        ResolutionPath.EXACT_FALLBACK, source.descriptor.id, ResolveAttempt.Kind.SKIPPED, detail = "search failed",
                    )
                    return null
                }
            }
            if (candidates.isNotEmpty()) break
        }
        val viable = candidates.values.filter { candidate ->
            val known = equivalence.get(track, candidate)
            known == null || known.tier != MatchTier.NO_MATCH || known.evidence.isNotEmpty()
        }
        val best = matcher.best(track, viable) ?: run {
            attempts += ResolveAttempt(ResolutionPath.EXACT_FALLBACK, source.descriptor.id, ResolveAttempt.Kind.SKIPPED, detail = "no candidate")
            return null
        }
        equivalence.put(track, best.first, best.second)
        if (best.second.tier != MatchTier.EXACT) {
            attempts += ResolveAttempt(
                ResolutionPath.EXACT_FALLBACK, source.descriptor.id, ResolveAttempt.Kind.SKIPPED,
                detail = "best match was ${best.second.tier}",
            )
            return null
        }
        return best
    }

    /** The track's own source's outcome decides the overall result; a failure beats a miss. */
    private fun lastFailure(attempts: List<ResolveAttempt>): ResolveOutcome {
        val failed = attempts.firstOrNull { it.kind == ResolveAttempt.Kind.FAILED }
        if (failed != null) {
            return ResolveOutcome.Failed(failed.healthOutcome ?: HealthOutcome.UNKNOWN, failed.detail, attempts)
        }
        val miss = attempts.firstOrNull { it.kind == ResolveAttempt.Kind.MISS || it.kind == ResolveAttempt.Kind.SKIPPED }
        return ResolveOutcome.Miss(miss?.missReason ?: MissReason.NO_SOURCE, attempts)
    }

    companion object {
        /**
         * Inside the player's own wait for a resolution, with room for one fallback. Sources that
         * need longer say so ([app.podium.sources.api.PlaybackFacet.resolveTimeoutMillis]).
         */
        const val DEFAULT_SOURCE_TIMEOUT_MILLIS = 6_000L
    }
}
