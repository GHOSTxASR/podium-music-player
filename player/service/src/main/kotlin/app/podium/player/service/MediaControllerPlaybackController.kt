package app.podium.player.service

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.podium.core.model.AudioQuality
import app.podium.core.model.Codec
import app.podium.core.model.QualityReport
import app.podium.core.model.QueueUid
import app.podium.core.model.TrackId
import app.podium.player.api.EnginePhase
import app.podium.player.api.NowPlayingItem
import app.podium.player.api.PauseReason
import app.podium.player.api.PlayIntent
import app.podium.player.api.PlaybackController
import app.podium.player.api.PlaybackSnapshot
import app.podium.player.api.PlaybackStatusMapper
import app.podium.player.api.PlayerFacts
import app.podium.player.api.QueueEntry
import app.podium.player.api.QueueOrigin
import app.podium.player.api.QueueView
import app.podium.player.api.RepeatMode
import app.podium.sources.api.ResolutionPath
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

/**
 * The UI's PlaybackController, backed by a Media3 MediaController connected to [PlaybackService].
 * Must be used from the main thread. Commands issued before the connection completes are queued.
 */
class MediaControllerPlaybackController(
    private val context: Context,
    private val scope: CoroutineScope,
) : PlaybackController {

    private val _snapshot = MutableStateFlow(PlaybackSnapshot())
    override val snapshot: StateFlow<PlaybackSnapshot> = _snapshot.asStateFlow()

    private val _queue = MutableStateFlow(QueueView())
    override val queue: StateFlow<QueueView> = _queue.asStateFlow()

    private val connected = CompletableDeferred<MediaController>()
    private var controller: MediaController? = null
    private var sessionExtras: Bundle = Bundle.EMPTY
    private var readyMediaId: String? = null
    private var lastPauseReason = PauseReason.USER

    private var connecting = false

    fun connect() {
        if (connecting || controller != null) return
        connecting = true
        scope.launch {
            try {
                val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
                val built = MediaController.Builder(context, token)
                    .setListener(object : MediaController.Listener {
                        override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
                            sessionExtras = extras
                            publish()
                        }
                    })
                    .buildAsync()
                    .await()
                controller = built
                sessionExtras = built.sessionExtras
                built.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) = publish()

                    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                        lastPauseReason = when (reason) {
                            Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> PauseReason.FOCUS_LOSS
                            Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> PauseReason.BECAME_NOISY
                            Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> PauseReason.REMOTE
                            else -> PauseReason.USER
                        }
                    }
                })
                connected.complete(built)
                publish()
            } catch (t: Throwable) {
                connecting = false
                Log.e(TAG, "Could not connect to the playback service", t)
            }
        }
    }

    fun release() {
        controller?.release()
        controller = null
    }

    private fun withController(block: (MediaController) -> Unit) {
        controller?.let(block) ?: scope.launch { block(connected.await()) }
    }

    private fun send(action: String, args: Bundle) = withController { c ->
        c.sendCustomCommand(PodiumSessionCommands.command(action), args)
    }

    // --- Commands ---------------------------------------------------------------------------------

    override fun positionMs(): Long = controller?.currentPosition ?: 0L

    override fun play() = withController { it.play() }
    override fun pause() = withController { it.pause() }
    override fun togglePlayPause() = withController { if (it.playWhenReady) it.pause() else it.play() }
    override fun next() = withController { it.seekToNext() }
    override fun previous() = withController { it.seekToPrevious() }
    override fun seekTo(positionMs: Long) = withController { it.seekTo(positionMs) }

    override fun setRepeat(mode: RepeatMode) = withController {
        it.repeatMode = when (mode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        }
    }

    override fun setShuffle(enabled: Boolean) = withController { it.shuffleModeEnabled = enabled }

    override fun playContext(tracks: List<TrackId>, startIndex: Int, contextLabel: String?, shuffle: Boolean) =
        send(
            PodiumSessionCommands.SET_CONTEXT,
            Bundle().apply {
                putStringArray(PodiumSessionCommands.ARG_TRACK_IDS, tracks.map { it.value }.toTypedArray())
                putInt(PodiumSessionCommands.ARG_START_INDEX, startIndex)
                putString(PodiumSessionCommands.ARG_CONTEXT_LABEL, contextLabel)
                putBoolean(PodiumSessionCommands.ARG_SHUFFLE, shuffle)
            },
        )

    override fun playNext(tracks: List<TrackId>) = send(
        PodiumSessionCommands.PLAY_NEXT,
        Bundle().apply { putStringArray(PodiumSessionCommands.ARG_TRACK_IDS, tracks.map { it.value }.toTypedArray()) },
    )

    override fun addToQueue(tracks: List<TrackId>) = send(
        PodiumSessionCommands.ADD_TO_QUEUE,
        Bundle().apply { putStringArray(PodiumSessionCommands.ARG_TRACK_IDS, tracks.map { it.value }.toTypedArray()) },
    )

    override fun move(uid: QueueUid, toIndex: Int) = send(
        PodiumSessionCommands.MOVE,
        Bundle().apply {
            putString(PodiumSessionCommands.ARG_UID, uid.value)
            putInt(PodiumSessionCommands.ARG_TO_INDEX, toIndex)
        },
    )

    override fun remove(uids: Set<QueueUid>) = send(
        PodiumSessionCommands.REMOVE,
        Bundle().apply { putStringArray(PodiumSessionCommands.ARG_UIDS, uids.map { it.value }.toTypedArray()) },
    )

    override fun skipTo(uid: QueueUid) = send(
        PodiumSessionCommands.SKIP_TO,
        Bundle().apply { putString(PodiumSessionCommands.ARG_UID, uid.value) },
    )

    override fun clearUpcoming() = send(PodiumSessionCommands.CLEAR_UPCOMING, Bundle())

    // --- State -------------------------------------------------------------------------------------

    private fun publish() {
        val c = controller ?: return
        val current = c.currentMediaItem
        if (c.playbackState == Player.STATE_READY) readyMediaId = current?.mediaId
        val error = c.playerError?.let { PlaybackErrors.fromCode(it.errorCode) }
        val facts = PlayerFacts(
            phase = when (c.playbackState) {
                Player.STATE_BUFFERING -> EnginePhase.BUFFERING
                Player.STATE_READY -> EnginePhase.READY
                Player.STATE_ENDED -> EnginePhase.ENDED
                else -> EnginePhase.IDLE
            },
            playWhenReady = c.playWhenReady,
            isPlaying = c.isPlaying,
            hasItems = c.mediaItemCount > 0,
            currentItemWasReady = current != null && current.mediaId == readyMediaId,
            suppressed = c.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE,
            error = error,
            pauseReason = lastPauseReason,
        )
        val extras = sessionExtras
        val uid = current?.mediaId
        val sameItem = extras.getString(PodiumExtras.CURRENT_UID) == uid
        val item = current?.let { toNowPlaying(it, c.currentMediaItemIndex, c.mediaItemCount, extras.takeIf { sameItem }) }
        _snapshot.value = PlaybackSnapshot(
            status = PlaybackStatusMapper.map(facts),
            intent = if (c.playWhenReady) PlayIntent.PLAY else PlayIntent.PAUSE,
            item = item,
            repeatMode = when (c.repeatMode) {
                Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                else -> RepeatMode.OFF
            },
            shuffleEnabled = c.shuffleModeEnabled,
            quality = QualityReport(
                sourceClaimed = if (sameItem) claimedQuality(extras) else null,
                actualMedia = AudioQualityMapper.fromTracks(c.currentTracks),
            ),
        )
        _queue.value = QueueView(
            entries = (0 until c.mediaItemCount).map { index -> toEntry(c.getMediaItemAt(index), index == c.currentMediaItemIndex) },
            currentIndex = c.currentMediaItemIndex,
            contextLabel = extras.getString(PodiumExtras.CONTEXT_LABEL),
        )
    }

    private fun toNowPlaying(item: MediaItem, index: Int, size: Int, extras: Bundle?): NowPlayingItem {
        val md = item.mediaMetadata
        return NowPlayingItem(
            uid = QueueUid(item.mediaId),
            trackId = TrackId(md.extras?.getString(PodiumExtras.TRACK_ID) ?: "unknown|${item.mediaId}"),
            title = md.title?.toString().orEmpty(),
            artistDisplay = md.artist?.toString().orEmpty(),
            albumTitle = md.albumTitle?.toString(),
            artworkUri = md.artworkUri?.toString(),
            durationMs = md.durationMs,
            indexInQueue = index,
            queueSize = size,
            servedByDisplayName = extras?.getString(PodiumExtras.SERVED_BY),
            resolutionPath = extras?.getString(PodiumExtras.RESOLUTION_PATH)?.let { runCatching { ResolutionPath.valueOf(it) }.getOrNull() },
        )
    }

    private fun toEntry(item: MediaItem, isCurrent: Boolean): QueueEntry {
        val md = item.mediaMetadata
        return QueueEntry(
            uid = QueueUid(item.mediaId),
            trackId = TrackId(md.extras?.getString(PodiumExtras.TRACK_ID) ?: "unknown|${item.mediaId}"),
            title = md.title?.toString().orEmpty(),
            artistDisplay = md.artist?.toString().orEmpty(),
            artworkUri = md.artworkUri?.toString(),
            durationMs = md.durationMs,
            origin = md.extras?.getString(PodiumExtras.ORIGIN)?.let { runCatching { QueueOrigin.valueOf(it) }.getOrNull() }
                ?: QueueOrigin.CONTEXT,
            isCurrent = isCurrent,
        )
    }

    private fun claimedQuality(extras: Bundle): AudioQuality? {
        val codec = extras.getString(PodiumExtras.CLAIMED_CODEC)?.let { runCatching { Codec.valueOf(it) }.getOrNull() }
            ?: return null
        fun int(key: String) = if (extras.containsKey(key)) extras.getInt(key) else null
        return AudioQuality(
            codec = codec,
            bitrateKbps = int(PodiumExtras.CLAIMED_BITRATE),
            sampleRateHz = int(PodiumExtras.CLAIMED_SAMPLE_RATE),
            bitDepth = int(PodiumExtras.CLAIMED_BIT_DEPTH),
        )
    }

    private companion object {
        const val TAG = "PodiumController"
    }
}
