@file:OptIn(UnstableApi::class)

package app.podium.player.service

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import app.podium.core.common.Outcome
import app.podium.core.common.PodiumError
import app.podium.core.model.QueueUid
import app.podium.core.model.TrackId
import app.podium.player.api.PlayContext
import app.podium.player.api.QueueItemResolver
import app.podium.player.api.QueueManager
import app.podium.player.api.QueueOp
import app.podium.player.api.RepeatMode
import app.podium.player.api.persistedShape
import app.podium.sources.api.PlaybackTarget
import app.podium.sources.api.Purpose
import app.podium.sources.api.resolve.ResolveOutcome
import app.podium.sources.api.resolve.Selection
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns ExoPlayer and keeps it a mirror of the [QueueManager] (ADR-006).
 *
 * - Queue mutations come in as commands, go through the QueueManager (single writer), and the
 *   resulting [QueueOp]s are applied to the player.
 * - Each item is resolved through the [QueueItemResolver] **before** it opens (pre-resolution on
 *   transition, or on demand from the loader thread) and pinned. A playing item is never switched
 *   to another source (D-18).
 * - Only DirectStream targets are played here; other routes belong to other engines (S5).
 *
 * Must be used from the application (main) thread, except [resolveForLoader].
 */
class PodiumPlaybackEngine(
    context: Context,
    private val deps: PlaybackDependencies,
    /** Builds the player around the engine's resolving media-source factory (tests inject their own). */
    playerFactory: ((Context, MediaSource.Factory) -> ExoPlayer)? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val queue = QueueManager()
    private val itemResolver = QueueItemResolver(queue, deps.resolver)
    private val inFlight = ConcurrentHashMap<QueueUid, Deferred<ResolveOutcome>>()
    private var consecutiveErrors = 0
    private var skipJob: Job? = null

    /** Invoked whenever session extras (current resolution, errors) change. */
    var onExtrasChanged: (Bundle) -> Unit = {}

    var lastError: PodiumError? = null
        private set

    private val mediaSourceFactory: MediaSource.Factory =
        DefaultMediaSourceFactory(ResolvingDataSource.Factory(DefaultDataSource.Factory(context), QueueDataSpecResolver()))

    val player: ExoPlayer = playerFactory?.invoke(context, mediaSourceFactory) ?: buildPlayer(context)

    private fun buildPlayer(context: Context): ExoPlayer {
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
    }

    init {
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val uid = mediaItem?.mediaId?.let(::QueueUid) ?: return
                queue.onCurrentChanged(uid)
                preResolveAround(uid)
                publishExtras()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    consecutiveErrors = 0
                    if (lastError != null) {
                        lastError = null
                        publishExtras()
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) = handleError(error)

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) keepPositionSaved() else savePosition()
            }
        })
        restoreSavedQueue()
        saveQueueChanges()
    }

    // --- Across runs (D-31) ------------------------------------------------------------------------

    private var positionJob: Job? = null

    /**
     * Bring back the queue from the last run, paused where it stopped. The player isn't prepared,
     * so nothing is opened or buffered until the listener presses play (the session prepares on
     * play). A command that arrives first wins; the restore then does nothing.
     */
    private fun restoreSavedQueue() {
        val store = deps.queueStore ?: return
        scope.launch {
            val saved = runCatching { store.load() }.onFailure { Log.w(TAG, "could not load the saved queue", it) }.getOrNull() ?: return@launch
            val ops = queue.restore(saved)
            if (ops.isEmpty()) return@launch
            deps.catalog.remember(saved.items.map { it.track })
            apply(ops)
            player.seekTo(saved.currentIndex, saved.positionMs)
            setRepeat(saved.repeatMode)
            Log.d(TAG, "restored ${saved.items.size} queued songs")
        }
    }

    /** Save the queue whenever its contents, order, current song or modes change (not on pins). */
    @OptIn(FlowPreview::class)
    private fun saveQueueChanges() {
        val store = deps.queueStore ?: return
        scope.launch {
            queue.state
                .distinctUntilChangedBy { it.persistedShape }
                .drop(1) // the empty queue a fresh engine starts with isn't news
                .debounce(SAVE_DEBOUNCE_MS)
                .collect { state ->
                    runCatching { store.save(state, player.currentPosition) }.onFailure { Log.w(TAG, "could not save the queue", it) }
                }
        }
    }

    private fun savePosition() {
        val store = deps.queueStore ?: return
        positionJob?.cancel()
        val index = queue.state.value.currentIndex.takeIf { it >= 0 } ?: return
        val position = player.currentPosition
        scope.launch { runCatching { store.savePosition(index, position) } }
    }

    private fun keepPositionSaved() {
        if (deps.queueStore == null) return
        positionJob?.cancel()
        positionJob = scope.launch {
            while (isActive) {
                delay(POSITION_SAVE_INTERVAL_MS)
                savePosition()
            }
        }
    }

    // --- Commands ----------------------------------------------------------------------------------

    suspend fun playContext(trackIds: List<TrackId>, startIndex: Int, label: String?, shuffle: Boolean): Boolean {
        val tracks = trackIds.mapNotNull { (deps.catalog.get(it) as? Outcome.Success)?.value }
        if (tracks.isEmpty()) return false
        val start = startIndex.coerceIn(0, tracks.lastIndex)
        apply(queue.setContext(tracks, start, label?.let(::PlayContext), shuffle))
        player.shuffleModeEnabled = false
        player.prepare()
        player.play()
        return true
    }

    suspend fun playNext(trackIds: List<TrackId>) = mutateWithTracks(trackIds) { queue.playNext(it) }

    suspend fun addToQueue(trackIds: List<TrackId>) = mutateWithTracks(trackIds) { queue.addToQueue(it) }

    private suspend fun mutateWithTracks(
        trackIds: List<TrackId>,
        block: (List<app.podium.core.model.Track>) -> List<QueueOp>,
    ): Boolean {
        val tracks = trackIds.mapNotNull { (deps.catalog.get(it) as? Outcome.Success)?.value }
        if (tracks.isEmpty()) return false
        val wasEmpty = queue.state.value.isEmpty
        apply(block(tracks))
        if (wasEmpty) {
            player.prepare()
            player.play()
        }
        queue.state.value.current?.let { preResolveAround(it.uid) }
        return true
    }

    fun move(uid: QueueUid, toIndex: Int) = apply(queue.move(uid, toIndex))

    fun remove(uids: Set<QueueUid>) = apply(queue.remove(uids))

    fun skipTo(uid: QueueUid) {
        apply(queue.skipTo(uid))
        player.playWhenReady = true
    }

    fun clearUpcoming() = apply(queue.clearUpcoming())

    fun setShuffle(enabled: Boolean) = apply(queue.setShuffle(enabled))

    fun setRepeat(mode: RepeatMode) {
        queue.setRepeat(mode)
        player.repeatMode = when (mode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        }
    }

    /** Apply queue operations to the player so its playlist mirrors the queue exactly. */
    private fun apply(ops: List<QueueOp>) {
        for (op in ops) when (op) {
            is QueueOp.ReplaceAll -> player.setMediaItems(op.items.map(QueueMediaItems::toMediaItem), op.startIndex, 0L)
            is QueueOp.Insert -> player.addMediaItems(op.index, op.items.map(QueueMediaItems::toMediaItem))
            is QueueOp.Remove -> player.removeMediaItems(op.index, op.index + op.count)
            is QueueOp.Move -> player.moveMediaItem(op.from, op.to)
            is QueueOp.ReplaceRange -> player.replaceMediaItems(op.from, op.toExclusive, op.items.map(QueueMediaItems::toMediaItem))
            is QueueOp.SeekTo -> player.seekTo(op.index, 0L)
        }
        if (BuildConfigFlags.checkMirror) assertMirrorsPlayer()
        queue.state.value.current?.let { preResolveAround(it.uid) }
        publishExtras()
    }

    /** Debug invariant: the player's playlist is exactly the queue, in order. */
    fun assertMirrorsPlayer() {
        val queueIds = queue.state.value.items.map { it.uid.value }
        val playerIds = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        check(queueIds == playerIds) { "Queue and player diverged: $queueIds vs $playerIds" }
    }

    // --- Resolution ----------------------------------------------------------------------------------

    /** Resolve the current item and the next one before they open, so fallbacks happen before playback. */
    private fun preResolveAround(uid: QueueUid) {
        startResolve(uid)
        val items = queue.state.value.items
        val index = items.indexOfFirst { it.uid == uid }
        items.getOrNull(index + 1)?.let { startResolve(it.uid) }
    }

    private fun startResolve(uid: QueueUid): Deferred<ResolveOutcome> {
        inFlight[uid]?.let { if (it.isActive) return it }
        val item = queue.item(uid)
        if (item?.selection != null && item.selection!!.isUsableAt(deps.clock.nowMillis())) {
            return CompletableDeferred(
                ResolveOutcome.Resolved(item.selection!!, reused = true, attempts = emptyList()),
            )
        }
        val deferred = CompletableDeferred<ResolveOutcome>()
        inFlight[uid] = deferred
        scope.launch {
            val outcome = runCatching { itemResolver.resolve(uid, Purpose.PLAYBACK) }
                .getOrElse { ResolveOutcome.Failed(app.podium.sources.api.HealthOutcome.UNKNOWN, it.message, emptyList()) }
            if (outcome is ResolveOutcome.Resolved) {
                Log.d(TAG, "resolved $uid via ${outcome.selection.path} from ${outcome.selection.servedBy}")
            } else {
                Log.w(TAG, "could not resolve $uid: $outcome")
            }
            deferred.complete(outcome)
            inFlight.remove(uid, deferred)
            if (uid == queue.state.value.current?.uid) publishExtras()
        }
        return deferred
    }

    /**
     * Called on ExoPlayer's loader thread. Returns the pinned selection, waiting for an in-flight
     * resolution (started on the main thread) if needed. Never resolves on this thread itself.
     */
    internal fun resolveForLoader(uid: QueueUid): Selection {
        queue.item(uid)?.selection?.takeIf { it.isUsableAt(deps.clock.nowMillis()) }?.let { return it }
        val started = CompletableDeferred<Deferred<ResolveOutcome>>()
        scope.launch { started.complete(startResolve(uid)) }
        val outcome = runBlocking {
            withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { started.await().await() }
        } ?: throw PodiumResolveException(app.podium.sources.api.HealthOutcome.NETWORK_FAILURE, "Timed out resolving $uid")
        return when (outcome) {
            is ResolveOutcome.Resolved -> outcome.selection
            is ResolveOutcome.Miss -> throw PodiumResolveException(null, "No playable copy for $uid (${outcome.reason})")
            is ResolveOutcome.Failed -> throw PodiumResolveException(outcome.outcome, "Resolution failed for $uid")
        }
    }

    private inner class QueueDataSpecResolver : ResolvingDataSource.Resolver {
        override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
            val uid = QueueMediaItems.uidOf(dataSpec.uri) ?: return dataSpec
            val selection = resolveForLoader(uid)
            val target = selection.target as? PlaybackTarget.DirectStream
                ?: throw PodiumResolveException(null, "Route ${selection.target::class.simpleName} needs another engine")
            return dataSpec.withUri(Uri.parse(target.media.uri)).withAdditionalHeaders(target.media.headers)
        }
    }

    // --- Errors ------------------------------------------------------------------------------------------

    /**
     * Classify, record source health for the source that served the item, then skip after a short
     * pause (SkipAfter). Three consecutive failures halt instead of skipping through the whole queue.
     */
    private fun handleError(error: PlaybackException) {
        val classified = PlaybackErrors.classify(error)
        lastError = classified
        val current = queue.state.value.current
        current?.selection?.servedBy?.let { deps.health.record(it, PlaybackErrors.healthOutcomeOf(classified)) }
        consecutiveErrors++
        publishExtras()
        Log.w(TAG, "playback error on ${current?.uid}: $classified (${error.errorCodeName})")
        skipJob?.cancel()
        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS || !player.hasNextMediaItem()) return
        skipJob = scope.launch {
            delay(SKIP_AFTER_MS)
            player.seekToNextMediaItem()
            player.prepare()
            player.playWhenReady = true
        }
    }

    // --- Session extras: who serves the current item, and how ----------------------------------------

    private fun publishExtras() {
        val current = queue.state.value.current
        val selection = current?.selection
        val claimed = selection?.media?.claimedQuality
        val extras = Bundle().apply {
            putString(PodiumExtras.CURRENT_UID, current?.uid?.value)
            putString(PodiumExtras.SERVED_BY, selection?.servedBy?.let { deps.registry.get(it)?.descriptor?.displayName })
            putString(PodiumExtras.RESOLUTION_PATH, selection?.path?.name)
            putString(PodiumExtras.CLAIMED_CODEC, claimed?.codec?.name)
            claimed?.bitrateKbps?.let { putInt(PodiumExtras.CLAIMED_BITRATE, it) }
            claimed?.sampleRateHz?.let { putInt(PodiumExtras.CLAIMED_SAMPLE_RATE, it) }
            claimed?.bitDepth?.let { putInt(PodiumExtras.CLAIMED_BIT_DEPTH, it) }
            putString(PodiumExtras.CONTEXT_LABEL, queue.state.value.context?.label)
            putString(PodiumExtras.LAST_ERROR, lastError?.let { it::class.simpleName })
        }
        onExtrasChanged(extras)
    }

    fun release() {
        // The last word on where playback stood; brief, and only when the service goes away.
        deps.queueStore?.let { store ->
            val index = queue.state.value.currentIndex
            if (index >= 0) runBlocking { withTimeoutOrNull(500) { runCatching { store.savePosition(index, player.currentPosition) } } }
        }
        scope.cancel()
        player.release()
    }

    companion object {
        private const val TAG = "PodiumEngine"
        private const val RESOLVE_TIMEOUT_MS = 15_000L
        private const val SKIP_AFTER_MS = 1_500L
        private const val MAX_CONSECUTIVE_ERRORS = 3
        private const val SAVE_DEBOUNCE_MS = 750L
        private const val POSITION_SAVE_INTERVAL_MS = 10_000L
    }
}

/** Debug-time invariant checks; flipped off for release by the app. */
object BuildConfigFlags {
    @Volatile var checkMirror: Boolean = true
}
