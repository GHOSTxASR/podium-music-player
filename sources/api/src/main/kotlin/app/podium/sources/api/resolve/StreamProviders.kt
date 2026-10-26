package app.podium.sources.api.resolve

import app.podium.core.model.SourceId
import app.podium.sources.api.MusicEnvironment
import app.podium.sources.api.MusicSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Sources the listener added for their streams only (D-91, "custom sources"). Before a song plays
 * from its own source, these are asked — in the listener's order — for an EXACT copy of it; the
 * song's own source is the fallback. They are not catalogues: never browsed, never searched by the
 * listener, never "the online service". So they live here, apart from the [app.podium.sources.api.SourceRegistry]
 * that the catalogue and the online screens read.
 */
interface StreamProviders {
    /** The providers that serve songs of [environment] now (turned on), most preferred first. */
    fun ordered(environment: MusicEnvironment): List<MusicSource>

    /** A provider that serves now, by id; null when there is none or it is turned off. */
    fun get(id: SourceId): MusicSource?

    /**
     * The ids of the providers that serve, as that changes. One turned off or removed drops out, and
     * the streams it gave queued songs go with it (the song playing keeps its stream, D-18).
     */
    val serving: Flow<Set<SourceId>> get() = flowOf(emptySet())

    companion object {
        /** No custom sources: every song plays from its own source, exactly as before D-91. */
        val None: StreamProviders = object : StreamProviders {
            override fun ordered(environment: MusicEnvironment): List<MusicSource> = emptyList()
            override fun get(id: SourceId): MusicSource? = null
        }
    }
}
