package app.podium.player.api

import app.podium.core.model.QueueUid
import app.podium.core.model.SourceId
import app.podium.core.model.Track
import app.podium.sources.api.PlayableMedia
import app.podium.sources.api.PlaybackTarget
import app.podium.sources.api.resolve.FallbackPolicy
import app.podium.sources.api.resolve.Selection

/** Why an item is in the queue (queue-and-autoplay.md §1). Sections appear in this order. */
enum class QueueOrigin { PLAY_NEXT, USER_QUEUED, CONTEXT, AUTOPLAY }

/**
 * One play slot. Carries everything needed to prevent an unexpected source change:
 * the canonical track, the preferred source, the fallback policy, and — once resolved — the pinned
 * selection (target + media). A pinned item is never re-resolved to a different source.
 */
data class QueueItem(
    val uid: QueueUid,
    val track: Track,
    val origin: QueueOrigin,
    val preferredSource: SourceId = track.source.sourceId,
    val fallbackPolicy: FallbackPolicy = FallbackPolicy.EXACT_ONLY,
    val selection: Selection? = null,
    /** Position in the original context order, used to undo shuffle. */
    val originalOrder: Int = 0,
) {
    val selectedTarget: PlaybackTarget? get() = selection?.target
    val pinnedMedia: PlayableMedia? get() = selection?.media
}

enum class RepeatMode { OFF, ALL, ONE }

/** Where the current context came from, shown as "Continuing from …". */
data class PlayContext(val label: String)

data class QueueState(
    val items: List<QueueItem> = emptyList(),
    val currentIndex: Int = -1,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val shuffleEnabled: Boolean = false,
    val context: PlayContext? = null,
) {
    val current: QueueItem? get() = items.getOrNull(currentIndex)
    val history: List<QueueItem> get() = if (currentIndex <= 0) emptyList() else items.subList(0, currentIndex)
    val upNext: List<QueueItem> get() = if (currentIndex < 0) items else items.drop(currentIndex + 1)

    /** Up Next as contiguous runs of the same origin, in play order (never reordered for display). */
    val sections: List<Pair<QueueOrigin, List<QueueItem>>> get() = upNext.runsBy { it.origin }

    val isEmpty: Boolean get() = items.isEmpty()
}

/**
 * Minimal operations to apply to the player's playlist so it mirrors the queue (ADR-006).
 * The queue manager is the only writer; the engine applies these in order.
 */
sealed interface QueueOp {
    data class ReplaceAll(val items: List<QueueItem>, val startIndex: Int) : QueueOp
    data class Insert(val index: Int, val items: List<QueueItem>) : QueueOp
    data class Remove(val index: Int, val count: Int = 1) : QueueOp
    data class Move(val from: Int, val to: Int) : QueueOp

    /** Replace a range (not containing the current item) in place, e.g. for shuffle. */
    data class ReplaceRange(val from: Int, val toExclusive: Int, val items: List<QueueItem>) : QueueOp

    data class SeekTo(val index: Int) : QueueOp
}

/** Split a list into contiguous runs sharing the same key, preserving order. */
fun <T, K> List<T>.runsBy(key: (T) -> K): List<Pair<K, List<T>>> {
    val runs = mutableListOf<Pair<K, MutableList<T>>>()
    for (item in this) {
        val k = key(item)
        if (runs.isNotEmpty() && runs.last().first == k) runs.last().second += item else runs += k to mutableListOf(item)
    }
    return runs
}
