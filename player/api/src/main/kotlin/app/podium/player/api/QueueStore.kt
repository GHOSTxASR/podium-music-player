package app.podium.player.api

import app.podium.core.model.Track

/**
 * The queue as it is saved between runs (D-31): the songs in play order, which one is current,
 * where in it playback stood, and the modes. Slot ids aren't saved; a restored queue gets new ones.
 */
data class SavedQueue(
    val items: List<SavedQueueItem>,
    val currentIndex: Int,
    val positionMs: Long,
    val repeatMode: RepeatMode,
    val shuffleEnabled: Boolean,
    val contextLabel: String?,
) {
    init {
        require(items.isNotEmpty()) { "An empty queue isn't saved" }
        require(currentIndex in items.indices) { "currentIndex out of range" }
    }
}

data class SavedQueueItem(val track: Track, val origin: QueueOrigin, val originalOrder: Int)

/** Where the playback service keeps the queue across restarts. */
interface QueueStore {
    suspend fun load(): SavedQueue?

    /** Replace the saved queue; an empty [QueueState] clears it. */
    suspend fun save(state: QueueState, positionMs: Long)

    /** Cheap update while playing: only where playback stands. */
    suspend fun savePosition(currentIndex: Int, positionMs: Long)
}

/** The saved form of a live queue, or null when it's empty. */
fun QueueState.toSaved(positionMs: Long): SavedQueue? {
    if (items.isEmpty() || currentIndex !in items.indices) return null
    return SavedQueue(
        items = items.map { SavedQueueItem(it.track, it.origin, it.originalOrder) },
        currentIndex = currentIndex,
        positionMs = positionMs.coerceAtLeast(0),
        repeatMode = repeatMode,
        shuffleEnabled = shuffleEnabled,
        contextLabel = context?.label,
    )
}

/** What a restart must bring back: slots, order, the current one and the modes (not resolutions). */
val QueueState.persistedShape: Any
    get() = listOf(items.map { listOf(it.uid, it.track.id, it.origin, it.originalOrder) }, currentIndex, repeatMode, shuffleEnabled, context)

