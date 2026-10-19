package app.podium.sources.api.resolve

import app.podium.core.model.SourceId
import app.podium.sources.api.MusicEnvironment
import app.podium.sources.api.MusicSource

/**
 * Sources the listener added for their streams only (D-91, "custom sources"). Before a song plays
 * from its own source, these are asked — in the listener's order — for an EXACT copy of it; the
 * song's own source is the fallback. They are not catalogues: never browsed, never searched by the
 * listener, never "the online service". So they live here, apart from the [app.podium.sources.api.SourceRegistry]
 * that the catalogue and the online screens read.
 */
interface StreamProviders {
    /** The enabled providers for songs of [environment], most preferred first. */
    fun ordered(environment: MusicEnvironment): List<MusicSource>

    /** A provider by id, enabled or not; null when there is none. */
    fun get(id: SourceId): MusicSource?

    companion object {
        /** No custom sources: every song plays from its own source, exactly as before D-91. */
        val None: StreamProviders = object : StreamProviders {
            override fun ordered(environment: MusicEnvironment): List<MusicSource> = emptyList()
            override fun get(id: SourceId): MusicSource? = null
        }
    }
}
