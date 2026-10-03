package app.podium.sources.api

import kotlinx.coroutines.flow.StateFlow

/**
 * One connected music source. A source implements only the facets it supports; an absent facet
 * means the matching capability is UNAVAILABLE. Sources never know about each other — combining
 * them (priority, fallback, matching) is the registry's and resolver's job.
 *
 * Normative spec: docs/architecture/MUSIC_SOURCE_ARCHITECTURE.md.
 */
interface MusicSource {
    val descriptor: SourceDescriptor

    /** Effective capabilities: declared ∩ runtime probes ∩ account state ∩ policy. */
    val capabilities: StateFlow<SourceCapabilities>

    val catalog: CatalogFacet? get() = null
    val library: LibraryFacet? get() = null
    val playback: PlaybackFacet? get() = null
    val artwork: ArtworkFacet? get() = null
    val lyrics: LyricsFacet? get() = null
    val recommendations: RecommendationFacet? get() = null
    val downloads: DownloadFacet? get() = null
    val auth: AuthFacet? get() = null
}
