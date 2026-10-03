package app.podium.player.api

import app.podium.core.model.QueueUid
import app.podium.core.model.TrackId
import kotlinx.coroutines.flow.StateFlow

/**
 * The only way the UI controls playback (ADR-003). The implementation talks to the playback
 * service through a Media3 MediaController — the same channel system controls use.
 */
interface PlaybackController {
    val snapshot: StateFlow<PlaybackSnapshot>
    val queue: StateFlow<QueueView>

    /** Read lazily in the draw phase; deliberately not a StateFlow (performance). */
    fun positionMs(): Long

    fun play()
    fun pause()
    fun togglePlayPause()
    fun next()

    /** Restarts the current item if more than 3 s in, otherwise goes to the previous item. */
    fun previous()
    fun seekTo(positionMs: Long)
    fun setRepeat(mode: RepeatMode)
    fun setShuffle(enabled: Boolean)

    /**
     * Play [tracks] as a context starting at [startIndex]. Tracks must be known to the TrackCatalog.
     * [radio] marks a radio the listener started (D-34): its songs are RADIO items, not a context.
     */
    fun playContext(tracks: List<TrackId>, startIndex: Int, contextLabel: String?, shuffle: Boolean = false, radio: Boolean = false)
    fun playNext(tracks: List<TrackId>)
    fun addToQueue(tracks: List<TrackId>)
    fun move(uid: QueueUid, toIndex: Int)
    fun remove(uids: Set<QueueUid>)
    fun skipTo(uid: QueueUid)
    fun clearUpcoming()
}

/** Device output volume (D-16): the wheel on Now Playing adjusts system media volume. */
interface VolumeController {
    val state: StateFlow<VolumeState>
    fun step(delta: Int)
}

data class VolumeState(val level: Int, val max: Int, val isFixed: Boolean) {
    val fraction: Float get() = if (max <= 0) 0f else level.toFloat() / max
}
