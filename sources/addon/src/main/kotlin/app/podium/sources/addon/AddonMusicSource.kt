package app.podium.sources.addon

import app.podium.core.common.Outcome
import app.podium.core.common.PodiumError
import app.podium.core.model.AlbumId
import app.podium.core.model.AlbumRef
import app.podium.core.model.ArtistCredit
import app.podium.core.model.ArtistId
import app.podium.core.model.Explicitness
import app.podium.core.model.MediaKind
import app.podium.core.model.PlaybackRoute
import app.podium.core.model.RecordingIdentifiers
import app.podium.core.model.SourceId
import app.podium.core.model.SourceRef
import app.podium.core.model.Track
import app.podium.core.model.TrackId
import app.podium.sources.api.AlbumDetail
import app.podium.sources.api.ArtistDetail
import app.podium.sources.api.Basis
import app.podium.sources.api.Capability
import app.podium.sources.api.CatalogFacet
import app.podium.sources.api.FacetResolution
import app.podium.sources.api.HealthOutcome
import app.podium.sources.api.MissReason
import app.podium.sources.api.MusicEnvironment
import app.podium.sources.api.MusicSource
import app.podium.sources.api.NetworkPolicy
import app.podium.sources.api.PlayableMedia
import app.podium.sources.api.PlaybackFacet
import app.podium.sources.api.PlaybackTarget
import app.podium.sources.api.Purpose
import app.podium.sources.api.QualityRequest
import app.podium.sources.api.SearchKind
import app.podium.sources.api.SearchQuery
import app.podium.sources.api.SearchResults
import app.podium.sources.api.SourceCapabilities
import app.podium.sources.api.SourceDescriptor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI
import java.security.MessageDigest

/** One addon the listener added: where it is, what it said about itself, and their settings for it. */
data class AddonConfig(
    val sourceId: SourceId,
    /** The addon's address, normalized ([Addons.address]). Can carry the listener's token: never shown or logged. */
    val base: String,
    val manifest: AddonManifest,
    val settings: Map<String, String> = emptyMap(),
)

/**
 * A custom source (D-91): an addon the listener added for its streams, speaking the BitChord addon
 * protocol ([AddonClient]). It isn't a catalogue Podium browses — songs still come from the online
 * catalogue — and it never enters the source registry. The resolver asks it, before the song's own
 * source, for an EXACT copy of the same edition ([app.podium.sources.api.resolve.StreamProviders]).
 *
 * - Search maps the addon's songs to [Track]s of this source (`"<sourceId>|<addon's id>"`).
 * - Resolve asks `/stream/{id}` and hands the player the address, telling it when it's DASH or HLS.
 *   Protected (DRM) streams are a miss: Podium plays open media only.
 * - The quality the addon names is kept as its claim; Podium's labels come from the decoder.
 */
class AddonMusicSource internal constructor(
    val config: AddonConfig,
    private val now: () -> Long,
    transport: AddonTransport,
) : MusicSource {

    constructor(config: AddonConfig, now: () -> Long = System::currentTimeMillis) : this(config, now, UrlConnectionAddonTransport())

    private val client = AddonClient(config.base, transport)
    private val settings: Map<String, String> = config.manifest.settle(config.settings)
    private val settingsKey = settings.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }

    override val descriptor = SourceDescriptor(
        id = config.sourceId,
        displayName = config.manifest.name,
        providerName = config.manifest.name,
        basis = Basis.USER_SERVER,
        environment = MusicEnvironment.ONLINE,
    )

    private val caps = MutableStateFlow(
        SourceCapabilities.available(
            *listOfNotNull(
                Capability.SEARCH.takeIf { config.manifest.canSearch },
                Capability.DIRECT_STREAM.takeIf { config.manifest.canStream },
            ).toTypedArray(),
        ),
    )
    override val capabilities: StateFlow<SourceCapabilities> = caps.asStateFlow()

    override val catalog: CatalogFacet? = if (config.manifest.canSearch) Catalog() else null
    override val playback: PlaybackFacet? = if (config.manifest.canStream) Playback() else null

    /** Asks the addon for its manifest again: is it there, and is it still the same addon? */
    suspend fun check(): Outcome<AddonManifest> = client.manifest()

    private inner class Catalog : CatalogFacet {
        override suspend fun search(query: SearchQuery): Outcome<SearchResults> {
            if (SearchKind.TRACKS !in query.kinds || query.text.isBlank()) return Outcome.Success(SearchResults.Empty)
            return when (val r = client.search(query.text, settings)) {
                is Outcome.Success -> Outcome.Success(
                    SearchResults(tracks = r.value.asSequence().drop(query.offset).take(query.limit).map { it.toTrack(descriptor.id) }.toList()),
                )
                is Outcome.Failure -> r
            }
        }

        override suspend fun track(ref: SourceRef): Outcome<Track> = Outcome.Failure(PodiumError.Unsupported("track lookup"))

        override suspend fun album(id: AlbumId): Outcome<AlbumDetail> = Outcome.Failure(PodiumError.Unsupported("albums"))

        override suspend fun artist(id: ArtistId): Outcome<ArtistDetail> = Outcome.Failure(PodiumError.Unsupported("artists"))
    }

    private inner class Playback : PlaybackFacet {
        override val routes: Set<PlaybackRoute> = setOf(PlaybackRoute.DIRECT)

        override val resolveTimeoutMillis: Long = RESOLVE_TIMEOUT_MS

        override suspend fun resolve(track: Track, quality: QualityRequest, purpose: Purpose): FacetResolution {
            if (track.source.sourceId != descriptor.id) return FacetResolution.Miss(MissReason.NOT_FOUND)
            if (purpose == Purpose.DOWNLOAD) return FacetResolution.Miss(MissReason.NOT_PERMITTED)
            return when (val r = client.stream(track.source.providerKey, settings)) {
                is Outcome.Success -> media(track, r.value)
                is Outcome.Failure -> failure(r.error)
            }
        }

        private fun media(track: Track, stream: AddonStream): FacetResolution {
            // Podium plays open media only; it never works around protection.
            if (stream.encrypted) return FacetResolution.Miss(MissReason.NOT_STREAMABLE)
            if (!UrlConnectionAddonTransport.allowed(stream.url)) {
                return FacetResolution.Failed(HealthOutcome.INVALID_MEDIA, "stream address refused")
            }
            val at = now()
            val expires = stream.expiresAtMillis?.takeIf { it > at + PlayableMedia.EXPIRY_MARGIN_MILLIS * 2 } ?: (at + STREAM_TTL_MS)
            val media = PlayableMedia(
                uri = stream.url,
                mimeType = StreamFacts.mimeType(stream),
                claimedQuality = StreamFacts.claimedQuality(stream),
                durationMs = track.durationMs,
                expiresAtMillis = expires,
                headers = stream.headers,
                sourceId = descriptor.id,
                cacheKey = "${track.id.value}|$settingsKey",
            )
            return FacetResolution.Resolved(PlaybackTarget.DirectStream(track.id, media))
        }

        private fun failure(error: PodiumError): FacetResolution = when (error) {
            is PodiumError.NotFound -> FacetResolution.Miss(MissReason.NOT_FOUND)
            is PodiumError.RateLimited -> FacetResolution.Failed(HealthOutcome.RATE_LIMIT, "rate limited", error.retryAfterMillis)
            is PodiumError.Network -> FacetResolution.Failed(HealthOutcome.NETWORK_FAILURE, "network")
            // The addon has no sign-in to fix; a refusal is the addon's problem, not the listener's.
            is PodiumError.AuthRequired -> FacetResolution.Failed(HealthOutcome.SERVER_ERROR, "refused")
            is PodiumError.Server -> FacetResolution.Failed(HealthOutcome.SERVER_ERROR, "HTTP ${error.httpStatus}")
            else -> FacetResolution.Failed(HealthOutcome.UNKNOWN, "unreadable answer")
        }
    }

    companion object {
        /** Finding a song's stream is a lookup on the addon's side, but some addons are slow to answer. */
        const val RESOLVE_TIMEOUT_MS = 15_000L

        /** How long an addon's stream address is used when the addon doesn't say. */
        const val STREAM_TTL_MS = 15 * 60_000L
    }
}

internal fun AddonTrack.toTrack(sourceId: SourceId): Track = Track(
    id = TrackId.of(sourceId, id),
    source = SourceRef(sourceId, id),
    title = title,
    artists = artist?.let { listOf(ArtistCredit(it)) }.orEmpty(),
    artistDisplay = artist.orEmpty(),
    album = album?.let { AlbumRef(it) },
    durationMs = durationMs,
    identifiers = RecordingIdentifiers(isrc = isrc),
    explicitness = when (explicit) {
        true -> Explicitness.EXPLICIT
        false -> Explicitness.NOT_EXPLICIT
        null -> Explicitness.UNKNOWN
    },
    routes = setOf(PlaybackRoute.DIRECT),
    kind = MediaKind.SONG,
)

/** Adding an addon: reading the address, fetching the manifest, naming the source. */
object Addons {
    private const val MANIFEST = "/manifest.json"

    /**
     * The address the listener typed or pasted, normalized: with or without `/manifest.json`, https
     * anywhere, or http to a server on their own network once they agree (D-09).
     */
    fun address(typed: String): NetworkPolicy.Verdict {
        var t = typed.trim().trimEnd('/')
        if (t.endsWith(MANIFEST, ignoreCase = true)) t = t.dropLast(MANIFEST.length)
        return NetworkPolicy.check(t)
    }

    /** A stable id for the addon at [base] that doesn't reveal the address. */
    fun sourceIdFor(base: String): SourceId {
        val digest = MessageDigest.getInstance("SHA-256").digest(base.toByteArray())
        return SourceId("custom-" + digest.take(5).joinToString("") { "%02x".format(it) })
    }

    /** What the listener sees of the address: the host alone (the rest can carry a token). */
    fun host(base: String): String = runCatching { URI(base).host }.getOrNull() ?: "unknown address"

    /** Fetches the addon's manifest from [base] (an address from [address]). */
    suspend fun manifest(base: String): Outcome<AddonManifest> = manifest(base, UrlConnectionAddonTransport())

    internal suspend fun manifest(base: String, transport: AddonTransport): Outcome<AddonManifest> = AddonClient(base, transport).manifest()
}
