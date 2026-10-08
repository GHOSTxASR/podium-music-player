package app.podium.player.api

import app.podium.core.common.PodiumError
import app.podium.core.model.QualityReport
import app.podium.core.model.QueueUid
import app.podium.core.model.SourceId
import app.podium.core.model.TrackId
import app.podium.sources.api.Attribution
import app.podium.sources.api.ResolutionPath

/** Exclusive playback status (playback-state-machine.md). Orthogonal facts live beside it. */
sealed interface PlaybackStatus {
    data object Idle : PlaybackStatus
    data object Loading : PlaybackStatus
    data object Buffering : PlaybackStatus
    data object Playing : PlaybackStatus
    data class Paused(val reason: PauseReason) : PlaybackStatus
    data object Ended : PlaybackStatus
    data class Error(val error: PodiumError) : PlaybackStatus
}

enum class PauseReason { USER, FOCUS_LOSS, BECAME_NOISY, REMOTE, OTHER }

/** What the user asked for; drives the play/pause glyph so it never flickers during buffering. */
enum class PlayIntent { PLAY, PAUSE }

/** Engine-neutral facts the status is derived from (no Media3 types here). */
enum class EnginePhase { IDLE, BUFFERING, READY, ENDED }

data class PlayerFacts(
    val phase: EnginePhase,
    val playWhenReady: Boolean,
    val isPlaying: Boolean,
    val hasItems: Boolean,
    /** Whether the current item has been READY at least once (BUFFERING after that = rebuffering). */
    val currentItemWasReady: Boolean,
    val suppressed: Boolean = false,
    val error: PodiumError? = null,
    val pauseReason: PauseReason = PauseReason.USER,
)

/** Pure mapping from engine facts to Podium's status (playback-state-machine.md §5). */
object PlaybackStatusMapper {
    fun map(f: PlayerFacts): PlaybackStatus = when {
        f.error != null -> PlaybackStatus.Error(f.error)
        !f.hasItems -> PlaybackStatus.Idle
        f.phase == EnginePhase.IDLE -> PlaybackStatus.Idle
        f.phase == EnginePhase.ENDED -> PlaybackStatus.Ended
        f.phase == EnginePhase.BUFFERING && !f.currentItemWasReady -> PlaybackStatus.Loading
        f.phase == EnginePhase.BUFFERING -> PlaybackStatus.Buffering
        f.isPlaying -> PlaybackStatus.Playing
        f.playWhenReady && f.suppressed -> PlaybackStatus.Paused(PauseReason.FOCUS_LOSS)
        else -> PlaybackStatus.Paused(f.pauseReason)
    }
}

/** Who owns the audio right now (PLAYBACK_TARGETS.md §8). Rendered generically by the UI. */
sealed interface PlaybackOwner {
    data object Podium : PlaybackOwner
    data class Remote(val displayName: String, val attribution: Attribution?) : PlaybackOwner
    data class Embedded(val displayName: String) : PlaybackOwner
}

/** What the active engine supports. The UI disables what isn't here; it never checks engine types. */
data class ControlSet(
    val playPause: Boolean = true,
    val seek: Boolean = true,
    val next: Boolean = true,
    val previous: Boolean = true,
    val shuffle: Boolean = true,
    val repeat: Boolean = true,
    val volume: Boolean = true,
) {
    companion object {
        val None = ControlSet(false, false, false, false, false, false, false)
    }
}

/** The current item as the UI needs it — provider-neutral display data only. */
data class NowPlayingItem(
    val uid: QueueUid,
    val trackId: TrackId,
    val title: String,
    val artistDisplay: String,
    val albumTitle: String?,
    val artworkUri: String?,
    val durationMs: Long?,
    val indexInQueue: Int,
    val queueSize: Int,
    /**
     * Provenance, for history, logs and diagnostics — never shown in normal Now Playing (D-36):
     * the source actually serving the audio (its display name and id) and how it was reached.
     */
    val servedByDisplayName: String?,
    val resolutionPath: ResolutionPath?,
    val servedBy: SourceId? = null,
)

data class PlaybackSnapshot(
    val status: PlaybackStatus = PlaybackStatus.Idle,
    val intent: PlayIntent = PlayIntent.PAUSE,
    val item: NowPlayingItem? = null,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val shuffleEnabled: Boolean = false,
    val owner: PlaybackOwner = PlaybackOwner.Podium,
    val controls: ControlSet = ControlSet(),
    val quality: QualityReport = QualityReport(),
    /** Set while another app plays (owner is [PlaybackOwner.Remote]): what Podium can do about it. */
    val remote: RemoteStatus? = null,
    /** The local queue is still there, paused, while another owner plays: "Back to my music" can resume it. */
    val canResumeLocal: Boolean = false,
    /** What the player is doing about a problem with the current song, while it does it (shown instead of the error). */
    val recovery: Recovery? = null,
) {
    val isActive: Boolean get() = item != null
}

/** The player's answer to a problem with the current song, while it's under way. */
enum class Recovery {
    /** The stream was refused (it expired, say): the same source is being asked for a fresh one. */
    REFRESHING_STREAM,

    /** The song can't play: the next one starts in a moment. */
    SKIPPING,
}

/** Remote playback as the UI shows it, provider-neutral (YOUTUBE_MUSIC_ARCHITECTURE.md §8.4). */
data class RemoteStatus(
    /** Podium sees and controls the app's playback; false = it can only open the app. */
    val controllable: Boolean,
    /** Why it isn't controllable or didn't start, when that's the case. */
    val problem: RemoteProblem? = null,
    /** Podium is waiting for the app to start the song. */
    val starting: Boolean = false,
)

enum class RemoteProblem {
    /** Podium may not see the app's playback: the listener can allow it in Settings. */
    NEEDS_ACCESS,

    /** The app that plays this music isn't installed. */
    NO_APP,

    /** The app didn't start the song in time. */
    DID_NOT_START,

    /** The app's playback ended or it closed. */
    ENDED,
}

/** One row of Up Next as the UI sees it. */
data class QueueEntry(
    val uid: QueueUid,
    val trackId: TrackId,
    val title: String,
    val artistDisplay: String,
    val artworkUri: String?,
    val durationMs: Long?,
    val origin: QueueOrigin,
    val isCurrent: Boolean,
)

data class QueueView(
    val entries: List<QueueEntry> = emptyList(),
    val currentIndex: Int = -1,
    val contextLabel: String? = null,
    /**
     * Another app owns this queue (remote playback): Podium shows what it reports and can't edit it.
     * When the app doesn't report it at all, [entries] is empty and [hidden] is true.
     */
    val readOnly: Boolean = false,
    val hidden: Boolean = false,
) {
    val upNext: List<QueueEntry> get() = if (currentIndex < 0) entries else entries.drop(currentIndex + 1)
    /** Contiguous runs of the same origin, in play order. */
    val sections: List<Pair<QueueOrigin, List<QueueEntry>>> get() = upNext.runsBy { it.origin }
}
