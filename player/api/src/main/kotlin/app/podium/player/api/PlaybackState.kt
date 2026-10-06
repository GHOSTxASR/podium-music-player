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
    /** Display name of the source actually serving the audio. */
    val servedByDisplayName: String?,
    /** How it was reached; non-OWN_SOURCE paths are surfaced to the user. */
    val resolutionPath: ResolutionPath?,
    /** The source actually serving the audio — for history, never for display (D-35). */
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
) {
    val isActive: Boolean get() = item != null
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
) {
    val upNext: List<QueueEntry> get() = if (currentIndex < 0) entries else entries.drop(currentIndex + 1)
    /** Contiguous runs of the same origin, in play order. */
    val sections: List<Pair<QueueOrigin, List<QueueEntry>>> get() = upNext.runsBy { it.origin }
}
