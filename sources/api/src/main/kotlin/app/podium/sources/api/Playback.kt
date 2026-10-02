package app.podium.sources.api

import app.podium.core.model.AudioQuality
import app.podium.core.model.SourceId
import app.podium.core.model.Track
import app.podium.core.model.TrackId

/**
 * How a resolved track plays. Three fundamentally different models (ADR-013, PLAYBACK_TARGETS.md):
 * never coerce one into another.
 */
sealed interface PlaybackTarget {
    val trackId: TrackId

    /** Podium owns playback: Media3 decodes [media]. */
    data class DirectStream(override val trackId: TrackId, val media: PlayableMedia) : PlaybackTarget

    /** Another app/device/server owns the audio; Podium sends commands through [controllerId]. */
    data class RemoteProvider(
        override val trackId: TrackId,
        val controllerId: String,
        val providerItemRef: String,
        val policy: RemotePolicy,
    ) : PlaybackTarget

    /** A provider's official embeddable player, visible inside Podium. */
    data class Embedded(
        override val trackId: TrackId,
        val embedRef: String,
        val constraints: EmbedConstraints,
    ) : PlaybackTarget
}

/**
 * A provider-neutral description of openable media. There is deliberately no "isLossless" field:
 * [claimedQuality] is what the source says this stream is; the actual quality is measured later by
 * the decoder and reported separately (core.model.QualityReport).
 */
data class PlayableMedia(
    val uri: String,
    val mimeType: String? = null,
    val claimedQuality: AudioQuality? = null,
    val durationMs: Long? = null,
    /** Epoch millis after which [uri] must not be used. Null = no known expiry. */
    val expiresAtMillis: Long? = null,
    /** Request headers the source binds to this URI (never logged). */
    val headers: Map<String, String> = emptyMap(),
    val sourceId: SourceId,
    /** Stable across URL refreshes so caches don't fragment: "<trackId>|<tierKey>". */
    val cacheKey: String,
) {
    fun isUsableAt(nowMillis: Long, marginMillis: Long = EXPIRY_MARGIN_MILLIS): Boolean =
        expiresAtMillis == null || expiresAtMillis - marginMillis > nowMillis

    companion object {
        const val EXPIRY_MARGIN_MILLIS = 60_000L
    }
}

enum class QueueOwnership { PODIUM, PROVIDER }

enum class ControlsOwner { PODIUM, PROVIDER }

data class RemotePolicy(
    val queueOwnership: QueueOwnership,
    val mixesWithOtherSources: Boolean,
    val allowsTransitions: Boolean,
    val systemControlsOwner: ControlsOwner,
    val requiresProviderApp: String? = null,
    val requiresSubscription: Boolean = false,
    val attribution: Attribution? = null,
)

data class EmbedConstraints(
    val requiresVisibleSurface: Boolean = true,
    val minWidthDp: Int = 0,
    val minHeightDp: Int = 0,
    val foregroundOnly: Boolean = true,
    val audioOnlyAllowed: Boolean = false,
)

enum class Purpose { PLAYBACK, PREFETCH, DOWNLOAD }

/** Quality the caller is willing to pay for, decided per request (not once from settings). */
sealed interface QualityRequest {
    data object Maximum : QualityRequest
    data class Capped(val maxKbps: Int) : QualityRequest
}

/** How a selection was reached. Surfaced in the UI when it isn't the track's own source. */
enum class ResolutionPath {
    OWN_SOURCE,
    OWNED_COPY,
    EXACT_FALLBACK,
}

enum class MissReason {
    NOT_FOUND,
    NOT_PERMITTED,
    NOT_STREAMABLE,
    UNSUPPORTED_ROUTE,
    SOURCE_UNAVAILABLE,
    NO_SOURCE,
}

/** What a source's PlaybackFacet returns for one of its own tracks. */
sealed interface FacetResolution {
    data class Resolved(val target: PlaybackTarget) : FacetResolution
    data class Miss(val reason: MissReason) : FacetResolution
    data class Failed(val outcome: HealthOutcome, val detail: String? = null, val retryAfterMillis: Long? = null) :
        FacetResolution
}
