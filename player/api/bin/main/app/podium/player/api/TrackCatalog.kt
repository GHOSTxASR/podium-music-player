package app.podium.player.api

import app.podium.core.common.Outcome
import app.podium.core.common.PodiumError
import app.podium.core.model.Track
import app.podium.core.model.TrackId
import app.podium.sources.api.SourceRegistry
import java.util.concurrent.ConcurrentHashMap

/**
 * Looks up canonical [Track]s by id for the playback service. Screens put the tracks they show into
 * the cache, so a "play" command only needs ids; anything not cached is fetched from its own
 * source's catalog. Provider-neutral: it only ever asks the registry.
 */
class TrackCatalog(private val registry: SourceRegistry) {
    private val cache = ConcurrentHashMap<TrackId, Track>()

    fun remember(tracks: Collection<Track>) {
        tracks.forEach { cache[it.id] = it }
    }

    fun cached(id: TrackId): Track? = cache[id]

    suspend fun get(id: TrackId): Outcome<Track> {
        cache[id]?.let { return Outcome.Success(it) }
        val source = registry.get(id.sourceId) ?: return Outcome.Failure(PodiumError.NotFound("source ${id.sourceId}"))
        val catalog = source.catalog ?: return Outcome.Failure(PodiumError.NotFound("track $id"))
        val result = catalog.track(app.podium.core.model.SourceRef(id.sourceId, id.providerKey))
        if (result is Outcome.Success) cache[id] = result.value
        return result
    }
}
