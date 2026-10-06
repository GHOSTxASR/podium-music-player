package app.podium.player.api

import app.podium.sources.api.PlaybackTarget
import kotlinx.coroutines.flow.StateFlow

/*
 * Playback owned by another app (PLAYBACK_TARGETS.md §5.2, YOUTUBE_MUSIC_ARCHITECTURE.md §8): the
 * app plays, under the listener's own account; Podium starts it, controls it through the platform's
 * media-session interface and mirrors what it reports. Nothing here knows which app it is.
 */

/** Whether Podium can reach the app that plays a remote target. */
enum class RemoteAccess {
    /** The app isn't installed. */
    NO_APP,

    /** The app is there, but Podium may not see or control its media session (the listener hasn't allowed it). */
    NEEDS_ACCESS,

    /** Podium can start, control and mirror the app's playback. */
    READY,
}

/** What the app's media session allows right now (from its own playback state). */
data class RemoteActions(
    val play: Boolean = false,
    val pause: Boolean = false,
    val seek: Boolean = false,
    val next: Boolean = false,
    val previous: Boolean = false,
    val skipToQueueItem: Boolean = false,
    val playFromUri: Boolean = false,
)

/** One entry of the app's own queue, as its session shows it. */
data class RemoteQueueItem(
    val id: Long,
    val title: String,
    val subtitle: String?,
    val mediaId: String?,
)

/** The app's media session as Podium sees it. Positions are extrapolated by [positionAt]. */
data class RemoteSessionState(
    /** A session of the app exists and Podium is attached to it. */
    val connected: Boolean = false,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0L,
    /** Elapsed-realtime millis when [positionMs] was reported. */
    val positionUpdatedAt: Long = 0L,
    val speed: Float = 1f,
    val durationMs: Long? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val mediaId: String? = null,
    val actions: RemoteActions = RemoteActions(),
    /** Null when the app doesn't show its queue to other apps. */
    val queue: List<RemoteQueueItem>? = null,
    val activeQueueItemId: Long? = null,
    /** The app reported an error (its own words are never shown). */
    val error: Boolean = false,
) {
    fun positionAt(nowElapsed: Long): Long {
        if (!playing || positionUpdatedAt <= 0L) return positionMs
        val advanced = positionMs + ((nowElapsed - positionUpdatedAt) * speed).toLong()
        return durationMs?.let { advanced.coerceIn(0, it) } ?: advanced.coerceAtLeast(0)
    }
}

/** How a hand-over to the app went. */
sealed interface RemoteStart {
    /** The app took the request through its media session; Podium stays in front and in control. */
    data object Controlled : RemoteStart

    /** The app was opened on the song (its own screen came forward). */
    data object OpenedApp : RemoteStart

    data class Failed(val reason: RemoteAccess?) : RemoteStart
}

/** The platform side of remote playback (an Android media-session controller in the app). */
interface RemotePlayback {
    val access: StateFlow<RemoteAccess>
    val state: StateFlow<RemoteSessionState>

    /** Hand [target] to its app: through the session when it accepts that, else by opening the app on it. */
    suspend fun start(target: PlaybackTarget.RemoteProvider): RemoteStart

    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun next()
    fun previous()
    fun skipToQueueItem(id: Long)

    /** Bring the app's own screen forward (always possible while it's installed). */
    fun openApp()

    /** Re-check access (after the listener changed a setting). */
    fun refresh()

    fun positionMs(): Long
}
