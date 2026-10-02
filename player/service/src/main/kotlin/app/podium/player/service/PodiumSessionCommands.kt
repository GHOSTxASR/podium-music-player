package app.podium.player.service

import android.os.Bundle
import androidx.media3.session.SessionCommand

/**
 * Custom session commands for queue operations (ADR-006). Every queue mutation travels to the
 * service and is executed by the QueueManager — the single writer.
 */
object PodiumSessionCommands {
    const val SET_CONTEXT = "app.podium.queue.SET_CONTEXT"
    const val PLAY_NEXT = "app.podium.queue.PLAY_NEXT"
    const val ADD_TO_QUEUE = "app.podium.queue.ADD_TO_QUEUE"
    const val MOVE = "app.podium.queue.MOVE"
    const val REMOVE = "app.podium.queue.REMOVE"
    const val SKIP_TO = "app.podium.queue.SKIP_TO"
    const val CLEAR_UPCOMING = "app.podium.queue.CLEAR_UPCOMING"

    const val ARG_TRACK_IDS = "trackIds"
    const val ARG_START_INDEX = "startIndex"
    const val ARG_CONTEXT_LABEL = "contextLabel"
    const val ARG_SHUFFLE = "shuffle"
    const val ARG_UID = "uid"
    const val ARG_UIDS = "uids"
    const val ARG_TO_INDEX = "toIndex"

    val all: List<SessionCommand> =
        listOf(SET_CONTEXT, PLAY_NEXT, ADD_TO_QUEUE, MOVE, REMOVE, SKIP_TO, CLEAR_UPCOMING)
            .map { SessionCommand(it, Bundle.EMPTY) }

    fun command(action: String) = SessionCommand(action, Bundle.EMPTY)
}

/** Keys used in MediaItem extras and session extras. Provider-neutral display data only. */
object PodiumExtras {
    const val TRACK_ID = "podium.trackId"
    const val ORIGIN = "podium.origin"

    // Session extras describing the current item's resolution (who is serving it, and how).
    const val CURRENT_UID = "podium.current.uid"
    const val SERVED_BY = "podium.current.servedBy"
    const val RESOLUTION_PATH = "podium.current.path"
    const val CLAIMED_CODEC = "podium.current.claimed.codec"
    const val CLAIMED_BITRATE = "podium.current.claimed.kbps"
    const val CLAIMED_SAMPLE_RATE = "podium.current.claimed.hz"
    const val CLAIMED_BIT_DEPTH = "podium.current.claimed.bits"
    const val CONTEXT_LABEL = "podium.queue.context"
    const val LAST_ERROR = "podium.error.kind"
}
