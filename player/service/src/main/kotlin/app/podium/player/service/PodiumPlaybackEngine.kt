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
import app.podium.player.api.AutoplayEngine
import app.podium.player.api.PlayContext
import app.podium.player.api.QueueItemResolver
import app.podium.player.api.QueueManager
import app.podium.player.api.QueueOp
import app.podium.player.api.QueueOrigin
import app.podium.player.api.RecommendationRequest
import app.podium.player.api.RepeatMode
import app.podium.player.api.persistedShape
import app.podium.sources.api.AuthState
import app.podium.sources.api.HealthOutcome
import app.podium.sources.api.NetworkPolicy
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns ExoPlayer and keeps it a mirror of the [QueueManager] (ADR-006).
 *
 * - Queue mutations come in as commands, go through the QueueManager (single writer), and the
 *   resulting [QueueOp]s are applied to the player.
 * - Each item is resolved through the [QueueItemResolver] **before** it opens (pre-resolution on
 *   transition, or on demand from the loader thread) and pinned. A playing item never changes
 *   source (D-18), with one exception: an EXACT copy from the listener's custom sources that was
 *   too slow to answer at first takes over once it has loaded (D-92, [applyUpgrade]).
 * - Only DirectStream targets are played here; other routes belong to other engines (S5).
 *
 * Must be used from the application (main) thread, except [resolveForLoader].
 */
class PodiumPlaybackEngine(
    context: Context,
    private val deps: PlaybackDependencies,
    /** Builds crossfade's second player (tests inject their own); by default like the first, without audio focus. */
    private val helperFactory: ((Context, MediaSource.Factory) -> ExoPlayer)? = null,
    /** Builds the player around the engine's resolving media-source factory (tests inject their own). Last, for a trailing lambda. */
    playerFactory: ((Context, MediaSource.Factory) -> ExoPlayer)? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val queue = QueueManager()
    private val itemResolver = QueueItemResolver(queue, deps.resolver)
    private val inFlight = ConcurrentHashMap<QueueUid, Deferred<ResolveOutcome>>()
    private var consecutiveErrors = 0

    // Declared before init: the watchers started there run immediately (main dispatcher).
    private val autoplay = AutoplayEngine(
        // Suggestions may come from any source of the current song's environment, never another (D-34, D-35).
        sameEnvironment = { a, b ->
            val environment = deps.registry.get(a)?.descriptor?.environment
            environment != null && environment == deps.registry.get(b)?.descriptor?.environment
        },
        equivalents = { deps.equivalence?.exactEquivalents(it).orEmpty() },
    )
    private var autoplayJob: Job? = null
    private var positionJob: Job? = null
    private var skipJob: Job? = null

    // Custom sources first (D-92). Declared before init too: the source watcher started there reads them.

    /** The custom sources serving when last looked. */
    private var knownCustom: Set<app.podium.core.model.SourceId> = emptySet()

    /** Songs no custom source has (every one answered): not asked again until the sources change. */
    private val customAnswered = HashSet<QueueUid>()

    /** When asking again for a song gave up, by song (D-96). */
    private val lastUpgradeTry = HashMap<QueueUid, Long>()
    private val upgradeJobs = HashMap<QueueUid, Job>()
    private val upgradeGate = kotlinx.coroutines.sync.Mutex()

    /** A custom source's copy found for a song while it played, waiting for a pause or seek. */
    private val pendingSwitch = HashMap<QueueUid, Selection>()

    /** The copies crossfade's second player carries songs from, by song (read on its loader thread). */
    private val carrySelections = ConcurrentHashMap<QueueUid, Selection>()

    /** The song being heard from a carried copy, and that copy (for the session, D-92). */
    private var carried: Pair<QueueUid, Selection>? = null

    /** Invoked whenever session extras (current resolution, errors) change. */
    var onExtrasChanged: (Bundle) -> Unit = {}

    var lastError: PodiumError? = null
        private set

    private val mediaSourceFactory: MediaSource.Factory =
        DefaultMediaSourceFactory(ResolvingDataSource.Factory(DefaultDataSource.Factory(context), QueueDataSpecResolver()))

    /** Settings ▸ Equaliser (D-76), in each player's own audio path. */
    private val equalizer = EqualizerProcessor()
    private var helperEqualizer: EqualizerProcessor? = null

    /** Settings ▸ Equaliser ▸ Effect (D-89), likewise in each player's path. */
    private val sound = SoundEffectProcessor().also { it.update(deps.effects.value.sound) }
    private var helperSound: SoundEffectProcessor? = null
    private var helperMeter: AudioMeter? = null

    val player: ExoPlayer = playerFactory?.invoke(context, mediaSourceFactory) ?: buildPlayer(context)

    private fun buildPlayer(context: Context): ExoPlayer {
        return ExoPlayer.Builder(context, EffectsRenderersFactory(context, equalizer, sound, deps.audioMeter))
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

    /**
     * Crossfade's second player (D-77): the same media and equaliser, silent until it carries the
     * end of a song. It never takes audio focus or the session; the engine's [player] keeps both.
     */
    private fun buildHelper(context: Context): ExoPlayer = (helperFactory?.invoke(context, mediaSourceFactory)
        ?: ExoPlayer.Builder(
            context,
            EffectsRenderersFactory(
                context,
                EqualizerProcessor().also { eq -> eq.update(deps.effects.value.equalizer); helperEqualizer = eq },
                SoundEffectProcessor().also { fx -> fx.update(deps.effects.value.sound); helperSound = fx },
                // Measured only while a carried copy is lined up with the song (D-96).
                AudioMeter().also { helperMeter = it },
            ),
        )
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ false,
            )
            .build())

    // The player's volume is the sleep timer's share times crossfade's (D-75, D-77); never the phone's volume.
    private var sleepShare = 1f
    private var fadeShare = 1f
    private var helperShare = 0f

    private fun applyVolumes() {
        player.volume = sleepShare * fadeShare
        crossfader.helperPlayer?.volume = sleepShare * helperShare
    }

    private val crossfader: Crossfader = Crossfader(
        main = player,
        newHelper = { buildHelper(context) },
        songAt = ::crossfadeSong,
        setMainShare = { share ->
            fadeShare = share
            applyVolumes()
        },
        setHelperShare = { helper, share ->
            helperShare = share
            helper.volume = sleepShare * share
        },
        scope = scope,
        blocked = { player.pauseAtEndOfMediaItems },
        onCarrying = { uid -> onCarrying(QueueUid(uid)) },
        onCarryEnded = ::onCarryEnded,
        soundAlignment = {
            val song = deps.audioMeter
            val copy = helperMeter
            if (song != null && copy != null) SoundAlignment(song, copy) else null
        },
    )

    /** What crossfade's rules need of the song at a position in the player's playlist. */
    private fun crossfadeSong(index: Int): app.podium.player.api.CrossfadeRules.Song? {
        if (index !in 0 until player.mediaItemCount) return null
        val uid = QueueUid(player.getMediaItemAt(index).mediaId)
        val track = queue.state.value.items.firstOrNull { it.uid == uid }?.track ?: return null
        // A live station has no end to fade from: the player's "duration" is only its rewind window (D-90).
        if (track.kind == app.podium.core.model.MediaKind.LIVE) return app.podium.player.api.CrossfadeRules.Song(null, null, null)
        val duration = track.durationMs ?: player.duration.takeIf { index == player.currentMediaItemIndex && it != C.TIME_UNSET && it > 0 }
        return app.podium.player.api.CrossfadeRules.Song(track.album?.id?.value, track.trackNumber, duration)
    }

    init {
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val uid = mediaItem?.mediaId?.let(::QueueUid) ?: return
                queue.onCurrentChanged(uid)
                // A copy found for a song that has since stopped playing streams from there next time (D-92).
                pendingSwitch.keys.filter { it != uid }.forEach { other -> pendingSwitch.remove(other)?.let { switchTo(other, it, null) } }
                preResolveAround(uid)
                publishExtras()
            }

            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                // The listener sought: the song may as well reload from the copy found for it (D-92).
                if (reason == Player.DISCONTINUITY_REASON_SEEK) switchPendingCurrent()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    consecutiveErrors = 0
                    refreshedAfterError.clear()
                    reopened.clear()
                    if (recovery != null) {
                        recovery = null
                        publishExtras()
                    }
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

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // Paused: the song switches to the copy found for it, unheard (D-92).
                if (!playWhenReady) switchPendingCurrent()
                val timer = deps.sleepTimer ?: return
                if (playWhenReady) {
                    timer.resumed()
                } else if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && player.pauseAtEndOfMediaItems) {
                    // Paused where the sleep timer asked: at the end of the song or the album.
                    player.pauseAtEndOfMediaItems = false
                    timer.endReached()
                }
            }
        })
        restoreSavedQueue()
        saveQueueChanges()
        keepOnlineMusicGoing()
        forgetStreamsOfSourcesThatStop()
        followEffects()
        followSleepTimer()
    }

    // --- Equaliser and crossfade (D-76, D-77) -------------------------------------------------------

    private fun followEffects() {
        scope.launch {
            deps.effects.collect { effects ->
                equalizer.update(effects.equalizer)
                helperEqualizer?.update(effects.equalizer)
                sound.update(effects.sound)
                helperSound?.update(effects.sound)
                crossfader.seconds = effects.crossfadeSeconds
            }
        }
    }

    // --- Sleep timer (D-75) -------------------------------------------------------------------------

    /**
     * The timer's fade is the player's own volume; when it runs out the player pauses (in silence,
     * the volume stays down until the music plays again). The end of a song or an album is the
     * player's own pause at the end of an item, set only for the song that ends it.
     */
    private fun followSleepTimer() {
        val timer = deps.sleepTimer ?: return
        scope.launch {
            timer.volume.collect { v ->
                sleepShare = v
                applyVolumes()
            }
        }
        scope.launch {
            timer.expired.collect {
                crossfader.stop()
                player.pause()
            }
        }
        scope.launch {
            combine(timer.state, queue.state) { state, q -> state to q }.collect { (state, q) ->
                player.pauseAtEndOfMediaItems = when (state) {
                    app.podium.player.api.SleepTimerState.EndOfSong -> true
                    app.podium.player.api.SleepTimerState.EndOfAlbum -> {
                        // Only the album's last song (the next one is another album's, or none).
                        val current = q.current?.track
                        val next = q.items.getOrNull(q.currentIndex + 1)?.track
                        current != null && (next == null || current.album?.id == null || next.album?.id != current.album?.id)
                    }
                    else -> false
                }
            }
        }
    }

    // --- Sources that stop serving -------------------------------------------------------------------

    /**
     * A source that stops serving — turned off, signed out, removed — takes its resolved streams
     * with it: queued items it served lose their pins and resolve afresh before they play (a pinned
     * URL can carry that source's sign-in). The item playing keeps its pin: audio never changes
     * source mid-track (D-18).
     */
    private fun forgetStreamsOfSourcesThatStop() {
        scope.launch {
            combine(
                deps.registry.connectedSources.map { all -> all.filter { it.enabled && it.authenticationState.serves() }.map { it.sourceId } },
                deps.streamProviders.serving,
            ) { registered, custom -> registered.toHashSet() to custom }
                .distinctUntilChanged()
                .collect { (registered, custom) ->
                    val serving = registered + custom
                    val state = queue.state.value
                    state.items
                        .filter { it.uid != state.current?.uid && it.selection?.servedBy?.let { s -> s !in serving } == true }
                        .forEach { queue.unpin(it.uid) }
                    // A custom source added or turned on (D-92): the songs about to play may be there.
                    if (!knownCustom.containsAll(custom)) {
                        customAnswered.clear()
                        lastUpgradeTry.clear()
                        val index = state.currentIndex
                        if (index >= 0) state.items.drop(index).take(UPGRADE_AHEAD).forEach { item ->
                            item.selection?.let { considerUpgrade(item.uid, ResolveOutcome.Resolved(it, reused = true, attempts = emptyList())) }
                        }
                    }
                    knownCustom = custom
                }
        }
    }

    // --- Custom sources first (D-92) ---------------------------------------------------------------------

    /**
     * An online song resolved to its own source while custom sources are there: when they ran out
     * of time or failed — rather than all answering that they haven't got it — they're asked again
     * in the background, and a copy that turns up takes over ([applyUpgrade]).
     */
    private fun considerUpgrade(uid: QueueUid, outcome: ResolveOutcome.Resolved) {
        val item = queue.item(uid) ?: return
        if (outcome.selection.path == app.podium.sources.api.ResolutionPath.PREFERRED_SOURCE) return
        if (item.track.kind == app.podium.core.model.MediaKind.LIVE) return
        if (item.fallbackPolicy != app.podium.sources.api.resolve.FallbackPolicy.EXACT_ONLY) return
        val environment = deps.registry.get(item.track.source.sourceId)?.descriptor?.environment ?: return
        if (deps.streamProviders.ordered(environment).isEmpty() || uid in customAnswered) return
        if (!outcome.reused && outcome.attempts.any { it.step == app.podium.sources.api.ResolutionPath.PREFERRED_SOURCE } &&
            !app.podium.sources.api.resolve.StreamResolver.mayStillAnswer(outcome.attempts)
        ) {
            customAnswered += uid
            return
        }
        upgradeLater(uid)
    }

    /**
     * Keeps asking the custom sources for [uid]'s song in the background until they say (D-96): at
     * once, then after 15 s, 30 s, 1, 2 and 4 minutes. While every one of them is resting (asked too
     * often, or not answering lately) it waits for the rest to end instead of asking; a source
     * answering again (a check in Settings, another song) cuts any wait short. It stops when a copy
     * turns up ([applyUpgrade]), when every source answers that it hasn't got the song, or when the
     * song has played or left the queue. One song is asked for at a time.
     */
    private fun upgradeLater(uid: QueueUid) {
        if (upgradeJobs[uid]?.isActive == true) return
        lastUpgradeTry[uid]?.let { if (deps.clock.nowMillis() - it < UPGRADE_RETRY_MS) return }
        upgradeJobs[uid] = scope.launch {
            for (attempt in 0..UPGRADE_BACKOFF_MS.size) {
                if (attempt > 0) restOrRecovery(UPGRADE_BACKOFF_MS[attempt - 1])
                while (true) {
                    val environment = upgradeWanted(uid) ?: return@launch
                    val rest = deps.resolver.preferredRestEndsAt(environment)?.minus(deps.clock.nowMillis()) ?: break
                    if (rest <= 0) break
                    restOrRecovery(rest.coerceAtMost(UPGRADE_MAX_WAIT_MS))
                }
                val outcome = upgradeGate.withLock {
                    val track = queue.item(uid)?.takeIf { upgradeWanted(uid) != null }?.track ?: return@launch
                    runCatching { deps.resolver.preferredCopy(track) }.getOrNull()
                }
                when (outcome) {
                    is ResolveOutcome.Resolved -> return@launch applyUpgrade(uid, outcome.selection)
                    is ResolveOutcome.Miss -> {
                        customAnswered += uid
                        return@launch
                    }
                    else -> Log.d(TAG, "custom sources couldn't say for $uid yet (ask ${attempt + 1})")
                }
            }
            // Still no answer: asked again when the song comes round, after a while.
            lastUpgradeTry[uid] = deps.clock.nowMillis()
        }
    }

    /** The environment whose custom sources [uid]'s song is still worth asking for, or null. */
    private fun upgradeWanted(uid: QueueUid): app.podium.sources.api.MusicEnvironment? {
        val state = queue.state.value
        val index = state.items.indexOfFirst { it.uid == uid }
        if (index < 0 || index < state.currentIndex || uid in customAnswered) return null
        val item = state.items[index]
        if (item.selection?.path == app.podium.sources.api.ResolutionPath.PREFERRED_SOURCE) return null
        val environment = deps.registry.get(item.track.source.sourceId)?.descriptor?.environment ?: return null
        return environment.takeIf { deps.streamProviders.ordered(it).isNotEmpty() }
    }

    /** Waits [millis], or until one of the custom sources answers again, whichever comes first. */
    private suspend fun restOrRecovery(millis: Long) {
        val before = deps.health.states.value
        withTimeoutOrNull(millis) {
            deps.health.states.first { now ->
                now.any { (id, health) ->
                    health == app.podium.sources.api.SourceHealth.Healthy && before[id] != health && deps.streamProviders.get(id) != null
                }
            }
        }
    }

    /**
     * A custom source's copy of [uid]'s song, found after it was resolved elsewhere (D-92). A song
     * not playing just streams from there; one that is paused, or hasn't started, switches now,
     * unheard; one that is playing is carried over to it mid-song by crossfade's second player, or —
     * when that can't be done just now — switches at the next pause or seek.
     */
    private fun applyUpgrade(uid: QueueUid, selection: Selection) {
        val item = queue.item(uid) ?: return
        if (item.selection?.path == app.podium.sources.api.ResolutionPath.PREFERRED_SOURCE) return
        val index = indexInPlayer(uid) ?: return
        if (index != player.currentMediaItemIndex) return switchTo(uid, selection, null)
        val started = player.playbackState == Player.STATE_READY && player.currentPosition >= NOT_STARTED_MS
        if (!player.playWhenReady || !started) return switchTo(uid, selection, player.currentPosition)
        carrySelections[uid] = selection
        if (crossfader.carry(QueueMediaItems.carryItem(uid, StreamKind.of(selection.media)))) {
            Log.d(TAG, "carrying $uid over to ${selection.servedBy}")
        } else {
            carrySelections.remove(uid)
            pendingSwitch[uid] = selection
        }
    }

    private fun onCarrying(uid: QueueUid) {
        val selection = carrySelections[uid] ?: return
        carried = uid to selection
        Log.d(TAG, "$uid is now heard from ${selection.servedBy}")
        publishExtras()
    }

    private fun onCarryEnded(end: Crossfader.CarryEnd) {
        if (released) return
        val uid = QueueUid(end.uid)
        val selection = carrySelections[uid] ?: return
        carried = null
        Log.d(TAG, "carrying $uid ended: ${end.reason}")
        when (end.reason) {
            // Paused: [main] switches to the copy where it was heard, unheard.
            Crossfader.CarryReason.PAUSED -> switchTo(uid, selection, if (end.heard) end.positionMs else player.currentPosition)
            Crossfader.CarryReason.SOUGHT -> switchTo(uid, selection, player.currentPosition)
            // The song is over (or another plays): next time it streams from the copy.
            Crossfader.CarryReason.ENDED, Crossfader.CarryReason.MOVED_ON ->
                switchTo(uid, selection, if (indexInPlayer(uid) == player.currentMediaItemIndex) 0L else null)
            Crossfader.CarryReason.GAVE_UP, Crossfader.CarryReason.FAILED, Crossfader.CarryReason.CANCELLED -> pendingSwitch[uid] = selection
        }
        publishExtras()
    }

    private fun switchPendingCurrent() {
        val uid = queue.state.value.current?.uid ?: return
        val selection = pendingSwitch.remove(uid) ?: return
        switchTo(uid, selection, player.currentPosition)
    }

    /**
     * Pin [selection] to [uid] and rebuild its media item for it, so the player opens the new stream
     * and never one it had already started loading. A song playing carries on at [positionMs].
     */
    private fun switchTo(uid: QueueUid, selection: Selection, positionMs: Long?) {
        pendingSwitch.remove(uid)
        queue.item(uid) ?: return
        queue.pin(uid, selection)
        val index = indexInPlayer(uid)
        if (index != null) {
            val playing = index == player.currentMediaItemIndex
            val position = if (playing) positionMs ?: player.currentPosition else 0L
            Log.d(TAG, "$uid streams from ${selection.servedBy} from here on")
            player.replaceMediaItem(index, QueueMediaItems.withKind(player.getMediaItemAt(index), StreamKind.of(selection.media), selection.servedBy))
            if (playing && position > 0) player.seekTo(index, position)
        }
        publishExtras()
    }

    private fun indexInPlayer(uid: QueueUid): Int? =
        (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == uid.value }

    private fun AuthState.serves() = this !is AuthState.SignedOut && this !is AuthState.Rejected && this !is AuthState.Expired

    // --- Autoplay (D-34) -------------------------------------------------------------------------------

    /**
     * When an online song is playing and the queue is about to run out, ask that song's source for
     * more (never another source, never local music) and append what passes the autoplay rules.
     */
    private fun keepOnlineMusicGoing() {
        val engine = deps.recommendations ?: return
        scope.launch {
            combine(queue.state, deps.autoplay) { state, _ -> state }
                .collect { state ->
                    // Always the latest settings, even if the queue changed first.
                    val settings = deps.autoplay.value
                    if (autoplayJob?.isActive == true || !autoplay.needsMore(state, settings)) return@collect
                    val current = state.current?.track ?: return@collect
                    val environment = deps.registry.get(current.source.sourceId)?.descriptor?.environment
                    if (environment != app.podium.sources.api.MusicEnvironment.ONLINE) return@collect
                    autoplayJob = scope.launch {
                        val recent = if (settings.avoidRepeats) deps.playedSince(deps.clock.nowMillis() - RECENT_WINDOW_MS) else emptySet()
                        val request = RecommendationRequest(autoplay.seeds(state), autoplay.exclusions(state, recent, settings), AutoplayEngine.BATCH * 2)
                        val candidates = runCatching { engine.recommend(request) }.onFailure { Log.w(TAG, "autoplay failed", it) }.getOrDefault(emptyList())
                        val latest = queue.state.value
                        val picked = autoplay.pick(candidates, latest, recent, settings)
                        if (picked.isNotEmpty() && latest.current?.track?.id == current.id) {
                            deps.catalog.remember(picked)
                            apply(queue.appendAutoplay(picked))
                            Log.d(TAG, "autoplay added ${picked.size}")
                        }
                    }
                }
        }
    }

    // --- Across runs (D-31) ------------------------------------------------------------------------


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

    suspend fun playContext(trackIds: List<TrackId>, startIndex: Int, label: String?, shuffle: Boolean, radio: Boolean = false): Boolean {
        val tracks = trackIds.mapNotNull { (deps.catalog.get(it) as? Outcome.Success)?.value }
        if (tracks.isEmpty()) return false
        val start = startIndex.coerceIn(0, tracks.lastIndex)
        apply(queue.setContext(tracks, start, label?.let(::PlayContext), shuffle, origin = if (radio) QueueOrigin.RADIO else QueueOrigin.CONTEXT))
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
            val reused = ResolveOutcome.Resolved(item.selection!!, reused = true, attempts = emptyList())
            considerUpgrade(uid, reused)
            return CompletableDeferred(reused)
        }
        val deferred = CompletableDeferred<ResolveOutcome>()
        inFlight[uid] = deferred
        scope.launch {
            val outcome = runCatching { itemResolver.resolve(uid, Purpose.PLAYBACK) }
                .getOrElse { ResolveOutcome.Failed(app.podium.sources.api.HealthOutcome.UNKNOWN, it.message, emptyList()) }
            if (outcome is ResolveOutcome.Resolved) {
                Log.d(TAG, "resolved $uid via ${outcome.selection.path} from ${outcome.selection.servedBy}")
                // Before the loader waiting on this hears of it: its item may need rebuilding first.
                matchStreamKind(uid, outcome.selection)
                considerUpgrade(uid, outcome)
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

    /**
     * The player opens a manifest with a different kind of source than a file (D-91): when [uid]'s
     * stream is another kind than its item was built for, the item is rebuilt for it. Live stations
     * are built as live HLS from the start (D-90) and never change. A song already playing resumes
     * where it was.
     */
    private fun matchStreamKind(uid: QueueUid, selection: Selection) {
        if (queue.item(uid)?.track?.kind == app.podium.core.model.MediaKind.LIVE) return
        val index = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == uid.value } ?: return
        val held = player.getMediaItemAt(index)
        if (QueueMediaItems.fits(held.localConfiguration?.uri ?: return, selection)) return
        val kind = StreamKind.of(selection.media)
        val playing = index == player.currentMediaItemIndex
        val position = if (playing) player.currentPosition else 0L
        Log.d(TAG, "rebuilding $uid to open a $kind stream")
        val tagged = QueueMediaItems.servedByOf(held.localConfiguration!!.uri)?.let { selection.servedBy }
        player.replaceMediaItem(index, QueueMediaItems.withKind(held, kind, tagged))
        if (playing && position > 0) player.seekTo(index, position)
    }

    private inner class QueueDataSpecResolver : ResolvingDataSource.Resolver {
        override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
            // The copy crossfade's second player carries a song from (D-92).
            QueueMediaItems.carryUidOf(dataSpec.uri)?.let { uid ->
                val selection = carrySelections[uid] ?: throw PodiumResolveException(HealthOutcome.MISS, "Nothing to carry $uid from")
                return open(dataSpec, selection.target as? PlaybackTarget.DirectStream ?: throw PodiumResolveException(null, "Not a stream"))
            }
            val uid = QueueMediaItems.uidOf(dataSpec.uri) ?: return dataSpec
            val selection = resolveForLoader(uid)
            val target = selection.target as? PlaybackTarget.DirectStream
                ?: throw PodiumResolveException(null, "Route ${selection.target::class.simpleName} needs another engine")
            // A manifest read as a file (or the other way round) would fail as bad media, and a stream
            // from another source than the item was rebuilt for isn't the one it started loading: the
            // item is rebuilt for its stream instead (D-91, D-92). Live stations are always built as
            // live HLS (D-90).
            if (!QueueMediaItems.fits(dataSpec.uri, selection) && queue.item(uid)?.track?.kind != app.podium.core.model.MediaKind.LIVE) {
                throw PodiumReopenException(uid, StreamKind.of(target.media))
            }
            return open(dataSpec, target)
        }

        private fun open(dataSpec: DataSpec, target: PlaybackTarget.DirectStream): DataSpec {
            // The platform allows cleartext only so the listener's own server can be reached; every
            // source is held to that here, whatever it hands over (D-09). A per-item refusal.
            if (!NetworkPolicy.permits(target.media.uri)) {
                throw PodiumResolveException(HealthOutcome.INVALID_MEDIA, "Refused an unencrypted stream off the listener's network")
            }
            return dataSpec.withUri(Uri.parse(target.media.uri)).withAdditionalHeaders(target.media.headers)
        }
    }

    // --- Errors ------------------------------------------------------------------------------------------

    /**
     * Classify, record source health for the source that served the item, then skip after a short
     * pause (SkipAfter). Three consecutive failures halt instead of skipping through the whole queue.
     */
    private fun handleError(error: PlaybackException) {
        // Not a failure: the item was built for another kind of stream than it resolved to (D-91).
        // Rebuilt for its stream (if that hasn't happened already), it plays from where it was.
        // Twice at most per song: a stream that keeps changing kind is a failure like any other.
        PlaybackErrors.reopenOf(error)?.let { reopen ->
            val times = (reopened[reopen.uid] ?: 0) + 1
            reopened[reopen.uid] = times
            if (times <= MAX_REOPENS) {
                queue.item(reopen.uid)?.selection?.let { matchStreamKind(reopen.uid, it) }
                if (player.playerError != null) player.prepare()
                return
            }
        }
        val current = queue.state.value.current
        val refused = PlaybackErrors.isRefusedStream(error) && current?.selection?.media != null
        // A stream URL that's refused (expired after a long pause, revoked) is asked for afresh from
        // the same source, once, and playback carries on where it was (D-18: same source, same copy).
        if (refused && current != null && refreshedAfterError.add(current.uid)) {
            recovery = app.podium.player.api.Recovery.REFRESHING_STREAM
            publishExtras()
            refreshAndResume(current.uid, player.currentPosition)
            return
        }
        // A refused stream says nothing about the listener's account: it's this song, not a sign-in.
        val classified = if (refused) PodiumError.NotPlayable("stream refused") else PlaybackErrors.classify(error)
        lastError = classified
        val outcome = if (refused) HealthOutcome.INVALID_MEDIA else PlaybackErrors.healthOutcomeOf(classified)
        current?.selection?.servedBy?.let { deps.health.record(it, outcome) }
        consecutiveErrors++
        publishExtras()
        Log.w(TAG, "playback error on ${current?.uid}: $classified (${error.errorCodeName})")
        skipJob?.cancel()
        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS || !player.hasNextMediaItem()) {
            recovery = null
            publishExtras()
            return
        }
        recovery = app.podium.player.api.Recovery.SKIPPING
        publishExtras()
        skipJob = scope.launch {
            delay(SKIP_AFTER_MS)
            recovery = null
            player.seekToNextMediaItem()
            player.prepare()
            player.playWhenReady = true
        }
    }

    /** Items whose refused stream was asked for afresh; cleared once playback is going again. */
    private val refreshedAfterError = HashSet<QueueUid>()

    /** How often each item was rebuilt for another kind of stream (D-91); cleared once playback is going again. */
    private val reopened = HashMap<QueueUid, Int>()

    /** What the engine is doing about the current song's problem, for the session (and Now Playing). */
    private var recovery: app.podium.player.api.Recovery? = null

    private fun refreshAndResume(uid: QueueUid, positionMs: Long) {
        scope.launch {
            val outcome = runCatching { itemResolver.refreshPinned(uid) }
                .getOrElse { ResolveOutcome.Failed(HealthOutcome.UNKNOWN, it.javaClass.simpleName, emptyList()) }
            if (queue.state.value.current?.uid != uid) return@launch // the listener moved on meanwhile
            if (outcome is ResolveOutcome.Resolved) {
                Log.d(TAG, "a fresh stream for $uid after the last was refused; resuming at ${positionMs}ms")
                player.seekTo(player.currentMediaItemIndex, positionMs)
                player.prepare() // keeps the listener's play/pause intent
            } else {
                Log.w(TAG, "no fresh stream for $uid: $outcome")
                player.playerError?.let(::handleError) ?: handleError(
                    PlaybackException("stream refused", null, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS),
                )
            }
        }
    }

    // --- Session extras: who serves the current item, and how ----------------------------------------

    private fun publishExtras() {
        val current = queue.state.value.current
        // While a copy carries the song (D-92), that copy is what's heard: it serves it.
        val carriedNow = carried?.takeIf { it.first == current?.uid }
        val selection = carriedNow?.second ?: current?.selection
        val claimed = selection?.media?.claimedQuality
        val heard = carriedNow?.let { crossfader.helperPlayer?.currentTracks?.let(AudioQualityMapper::fromTracks) }
        val extras = Bundle().apply {
            putString(PodiumExtras.CURRENT_UID, current?.uid?.value)
            putString(PodiumExtras.SERVED_BY, selection?.servedBy?.let { (deps.registry.get(it) ?: deps.streamProviders.get(it))?.descriptor?.displayName })
            putString(PodiumExtras.SERVED_BY_ID, selection?.servedBy?.value)
            putString(PodiumExtras.RESOLUTION_PATH, selection?.path?.name)
            putString(PodiumExtras.CLAIMED_CODEC, claimed?.codec?.name)
            claimed?.bitrateKbps?.let { putInt(PodiumExtras.CLAIMED_BITRATE, it) }
            claimed?.sampleRateHz?.let { putInt(PodiumExtras.CLAIMED_SAMPLE_RATE, it) }
            claimed?.bitDepth?.let { putInt(PodiumExtras.CLAIMED_BIT_DEPTH, it) }
            heard?.codec?.let { codec ->
                putString(PodiumExtras.HEARD_CODEC, codec.name)
                heard.container?.let { putString(PodiumExtras.HEARD_CONTAINER, it) }
                heard.bitrateKbps?.let { putInt(PodiumExtras.HEARD_BITRATE, it) }
                heard.sampleRateHz?.let { putInt(PodiumExtras.HEARD_SAMPLE_RATE, it) }
                heard.bitDepth?.let { putInt(PodiumExtras.HEARD_BIT_DEPTH, it) }
                heard.channels?.let { putInt(PodiumExtras.HEARD_CHANNELS, it) }
            }
            putString(PodiumExtras.CONTEXT_LABEL, queue.state.value.context?.label)
            putString(PodiumExtras.LAST_ERROR, lastError?.let { it::class.simpleName })
            putString(PodiumExtras.RECOVERY, recovery?.name)
        }
        onExtrasChanged(extras)
    }

    private var released = false

    fun release() {
        released = true
        // The last word on where playback stood; brief, and only when the service goes away.
        deps.queueStore?.let { store ->
            val index = queue.state.value.currentIndex
            if (index >= 0) runBlocking { withTimeoutOrNull(500) { runCatching { store.savePosition(index, player.currentPosition) } } }
        }
        scope.cancel()
        crossfader.release()
        player.release()
    }

    companion object {
        private const val TAG = "PodiumEngine"
        /** Longer than any source's own resolve budget (PlaybackFacet.resolveTimeoutMillis), so the source decides. */
        private const val RESOLVE_TIMEOUT_MS = 25_000L
        private const val SKIP_AFTER_MS = 1_500L
        private const val MAX_CONSECUTIVE_ERRORS = 3

        /** Rebuilds of one item for another kind of stream before its error counts (D-91). */
        private const val MAX_REOPENS = 2

        /** Custom sources asked again for a song that ran out of asks, no sooner than this (D-92, D-96). */
        private const val UPGRADE_RETRY_MS = 5 * 60_000L

        /** The waits between background asks for one song (D-96). */
        private val UPGRADE_BACKOFF_MS = listOf(15_000L, 30_000L, 60_000L, 120_000L, 240_000L)

        /** The longest wait for a resting custom source before looking again. */
        private const val UPGRADE_MAX_WAIT_MS = 5 * 60_000L

        /** The song playing and the next few: asked again when a custom source is added (D-92). */
        private const val UPGRADE_AHEAD = 4

        /** A song this little way in hasn't really started: it can switch streams outright (D-92). */
        private const val NOT_STARTED_MS = 1_500L
        private const val SAVE_DEBOUNCE_MS = 750L
        private const val POSITION_SAVE_INTERVAL_MS = 10_000L
        private const val RECENT_WINDOW_MS = 6 * 60 * 60 * 1000L
    }
}

/** Debug-time invariant checks; flipped off for release by the app. */
object BuildConfigFlags {
    @Volatile var checkMirror: Boolean = true
}
