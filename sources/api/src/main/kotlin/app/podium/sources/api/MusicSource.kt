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

    /** Curated lists for browsing: charts, new music, genres (D-34). */
    val discovery: DiscoveryFacet? get() = null
    val downloads: DownloadFacet? get() = null
    val auth: AuthFacet? get() = null

    /** For libraries read from storage: which folders are read (D-32). */
    val folders: FolderFacet? get() = null

    /**
     * The listener's library at an online account: liked songs, saved playlists, albums, artists,
     * history. Read from the account; never synced into the local library (D-34).
     */
    val accountLibrary: AccountLibraryFacet? get() = null
}
