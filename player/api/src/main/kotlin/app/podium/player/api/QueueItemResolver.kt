package app.podium.player.api

import app.podium.core.model.QueueUid
import app.podium.sources.api.MissReason
import app.podium.sources.api.Purpose
import app.podium.sources.api.QualityRequest
import app.podium.sources.api.resolve.ResolveOutcome
import app.podium.sources.api.resolve.ResolveRequest
import app.podium.sources.api.resolve.StreamResolver

/**
 * Connects the queue to the resolver. Each queue item is resolved at most once per play: the
 * resulting selection is pinned on the item, and later requests reuse the pin while it is valid.
 *
 * Call from the thread that owns the [QueueManager] (the playback service's main thread).
 */
class QueueItemResolver(
    private val queue: QueueManager,
    private val resolver: StreamResolver,
    private val quality: () -> QualityRequest = { QualityRequest.Maximum },
) {
    /** Resolve before playback starts (current item or the one about to play). */
    suspend fun resolve(uid: QueueUid, purpose: Purpose = Purpose.PLAYBACK): ResolveOutcome {
        val item = queue.item(uid) ?: return ResolveOutcome.Miss(MissReason.NOT_FOUND, emptyList())
        val outcome = resolver.resolve(
            ResolveRequest(
                track = item.track,
                purpose = purpose,
                quality = quality(),
                pinned = item.selection,
                fallbackPolicy = item.fallbackPolicy,
            ),
        )
        if (outcome is ResolveOutcome.Resolved && !outcome.reused) queue.pin(uid, outcome.selection)
        return outcome
    }

    /**
     * The pinned URL stopped working while the item was playing (e.g. HTTP 403 after expiry).
     * Only the same source may answer — the audio is never swapped to another copy mid-track.
     */
    suspend fun refreshPinned(uid: QueueUid): ResolveOutcome {
        val item = queue.item(uid) ?: return ResolveOutcome.Miss(MissReason.NOT_FOUND, emptyList())
        val outcome = resolver.resolve(
            ResolveRequest(
                track = item.track,
                quality = quality(),
                pinned = item.selection,
                fallbackPolicy = item.fallbackPolicy,
                refreshPinnedSourceOnly = true,
            ),
        )
        if (outcome is ResolveOutcome.Resolved) queue.pin(uid, outcome.selection)
        return outcome
    }
}
