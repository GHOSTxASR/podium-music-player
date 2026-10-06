package app.podium.player.api

import app.podium.core.common.getOrNull
import app.podium.core.model.PlaybackRoute
import app.podium.core.model.QueueUid
import app.podium.core.model.SourceId
import app.podium.core.model.Track
import app.podium.core.model.TrackId
import app.podium.sources.api.FacetResolution
import app.podium.sources.api.MusicSource
import app.podium.sources.api.PlaybackTarget
import app.podium.sources.api.Purpose
import app.podium.sources.api.QualityRequest
import app.podium.sources.api.RemoteContext
import app.podium.sources.api.ResolutionPath
import app.podium.sources.api.SourceRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The PlaybackController the UI uses (YOUTUBE_MUSIC_ARCHITECTURE.md §8): one facade over two owners
 * that never play at once.
 *
 * - **LOCAL**: Podium's own player ([local], Media3 in the playback service) with its queue — music
 *   on the device and any source Podium may stream itself. Untouched by everything here.
 * - **REMOTE**: another app plays (the source's [PlaybackTarget.RemoteProvider]); Podium starts it,
 *   controls it and mirrors what its media session reports ([remote]).
 *
 * Changing owner is a hard cut through the [PlaybackRouter]: the outgoing owner pauses first. The
 * local queue is never touched by remote playback — it waits, paused, exactly as it was, and
 * [resumeLocal] (or playing any local song) brings it back. Remote songs never enter the local queue:
 * a song whose source can only play remotely is never handed to [local] (D-34 "no mixed queues").
 */
class OwnerAwarePlaybackController(
    private val local: PlaybackController,
    private val remote: RemotePlayback,
    private val catalog: TrackCatalog,
    private val registry: SourceRegistry,
    private val scope: CoroutineScope,
    private val startTimeoutMs: Long = START_TIMEOUT_MS,
) : PlaybackController {

    enum class Owner { LOCAL, REMOTE }

    private val _owner = MutableStateFlow(Owner.LOCAL)
    val owner: StateFlow<Owner> = _owner.asStateFlow()

    /** What Podium last handed to the remote app, until its session confirms (or doesn't). */
    private data class Handoff(
        val track: Track,
        val source: SourceId,
        val starting: Boolean = true,
        val problem: RemoteProblem? = null,
    )

    private val handoff = MutableStateFlow<Handoff?>(null)

    /** Songs recognised in the remote app's session, with their catalogue metadata (artwork, album). */
    private val recognised = MutableStateFlow<Map<TrackId, Track>>(emptyMap())

    private var lastStart: RemoteStart? = null
    private var startJob: Job? = null

    private val localEngine = object : PlaybackEngine {
        override val route = PlaybackRoute.DIRECT
        override val controls = ControlSet()
        override suspend fun prepare(target: PlaybackTarget, startPositionMs: Long, playWhenReady: Boolean) = Unit
        override fun play() = local.play()
        override fun pause() = local.pause()
        override fun stop() = local.pause() // the local queue stays, paused, for later
        override fun seekTo(positionMs: Long) = local.seekTo(positionMs)
        override fun positionMs() = local.positionMs()
    }

    private val remoteEngine = object : PlaybackEngine {
        override val route = PlaybackRoute.REMOTE
        override val controls: ControlSet get() = remoteControls(remote.state.value, remote.access.value)
        override suspend fun prepare(target: PlaybackTarget, startPositionMs: Long, playWhenReady: Boolean) {
            lastStart = remote.start(target as PlaybackTarget.RemoteProvider)
        }
        override fun play() = remote.play()
        override fun pause() = remote.pause()
        override fun stop() {
            if (remote.state.value.playing) remote.pause()
        }
        override fun seekTo(positionMs: Long) = remote.seekTo(positionMs)
        override fun positionMs() = remote.positionMs()
    }

    private val router = PlaybackRouter(listOf(localEngine, remoteEngine)).also { it.handOver(PlaybackRoute.DIRECT) }

    private val remoteOwns get() = _owner.value == Owner.REMOTE
    private val controllable get() = remote.access.value == RemoteAccess.READY && remote.state.value.connected

    // --- What the UI sees ------------------------------------------------------------------------------

    private data class RemoteView(
        val state: RemoteSessionState,
        val access: RemoteAccess,
        val handoff: Handoff?,
        val recognised: Map<TrackId, Track>,
    )

    private val remoteView = combine(remote.state, remote.access, handoff, recognised) { s, a, h, r -> RemoteView(s, a, h, r) }

    override val snapshot: StateFlow<PlaybackSnapshot> =
        combine(local.snapshot, local.queue, _owner, remoteView) { localSnapshot, localQueue, owner, view ->
            if (owner == Owner.LOCAL) localSnapshot else remoteSnapshot(view, localQueue.entries.isNotEmpty())
        }.stateIn(scope, SharingStarted.Eagerly, local.snapshot.value)

    override val queue: StateFlow<QueueView> =
        combine(local.queue, _owner, remoteView) { localQueue, owner, view ->
            if (owner == Owner.LOCAL) localQueue else remoteQueue(view)
        }.stateIn(scope, SharingStarted.Eagerly, local.queue.value)

    private fun remoteSnapshot(view: RemoteView, canResumeLocal: Boolean): PlaybackSnapshot {
        val (s, access, h, known) = view
        val source = (h?.source ?: remoteSource()?.descriptor?.id)?.let(registry::get)
        val sessionId = recognise(source, s.mediaId)
        val sessionTrack = sessionId?.let { known[it] ?: catalog.cached(it) }
        val canSee = access == RemoteAccess.READY && s.connected
        // Until the app confirms, and whenever Podium can't see it, show what was asked for — never invent.
        val showRequested = h != null && (!canSee || h.starting)
        val track = if (showRequested) h!!.track else sessionTrack
        val title = if (showRequested) track?.title else track?.title ?: s.title
        val problem = when {
            access == RemoteAccess.NO_APP -> RemoteProblem.NO_APP
            access == RemoteAccess.NEEDS_ACCESS -> RemoteProblem.NEEDS_ACCESS
            h?.problem != null -> h.problem
            !s.connected && h != null && !h.starting -> RemoteProblem.ENDED
            else -> null
        }
        val item = if (title == null) null else {
            val queue = s.queue.orEmpty()
            val index = queue.indexOfFirst { it.id == s.activeQueueItemId }
            NowPlayingItem(
                uid = QueueUid("remote:${(if (showRequested) null else s.mediaId) ?: track?.id?.providerKey ?: "now"}"),
                trackId = track?.id ?: sessionId ?: TrackId.of(source?.descriptor?.id ?: SourceId(UNKNOWN), "session"),
                title = title,
                artistDisplay = if (showRequested) track?.artistDisplay.orEmpty() else track?.artistDisplay ?: s.artist.orEmpty(),
                albumTitle = if (showRequested) track?.album?.title else track?.album?.title ?: s.album,
                artworkUri = track?.artwork?.uri,
                durationMs = (if (canSee) s.durationMs?.takeIf { it > 0 } else null) ?: track?.durationMs,
                indexInQueue = index.coerceAtLeast(0),
                queueSize = queue.size.coerceAtLeast(1),
                servedByDisplayName = source?.descriptor?.displayName,
                resolutionPath = ResolutionPath.OWN_SOURCE,
                servedBy = source?.descriptor?.id,
            )
        }
        val starting = h?.starting == true && access == RemoteAccess.READY
        val status = when {
            starting -> PlaybackStatus.Loading
            !canSee -> PlaybackStatus.Paused(PauseReason.REMOTE)
            s.error -> PlaybackStatus.Error(app.podium.core.common.PodiumError.NotPlayable("remote"))
            s.buffering -> PlaybackStatus.Buffering
            s.playing -> PlaybackStatus.Playing
            else -> PlaybackStatus.Paused(PauseReason.REMOTE)
        }
        return PlaybackSnapshot(
            status = status,
            intent = if (starting || (canSee && (s.playing || s.buffering))) PlayIntent.PLAY else PlayIntent.PAUSE,
            item = item,
            owner = PlaybackOwner.Remote(source?.descriptor?.displayName.orEmpty(), source?.descriptor?.attribution),
            controls = remoteControls(s, access),
            remote = RemoteStatus(controllable = canSee, problem = problem, starting = starting),
            canResumeLocal = canResumeLocal,
        )
    }

    private fun remoteQueue(view: RemoteView): QueueView {
        val (s, access, _, known) = view
        if (access != RemoteAccess.READY || !s.connected || s.queue == null) return QueueView(readOnly = true, hidden = true)
        val source = remoteSource()
        val entries = s.queue.map { q ->
            val id = recognise(source, q.mediaId)
            val track = id?.let { known[it] ?: catalog.cached(it) }
            QueueEntry(
                uid = QueueUid("$REMOTE_UID${q.id}"),
                trackId = id ?: TrackId.of(source?.descriptor?.id ?: SourceId(UNKNOWN), "queue-${q.id}"),
                title = track?.title ?: q.title,
                artistDisplay = track?.artistDisplay ?: q.subtitle.orEmpty(),
                artworkUri = track?.artwork?.uri,
                durationMs = track?.durationMs,
                origin = QueueOrigin.CONTEXT,
                isCurrent = q.id == s.activeQueueItemId,
            )
        }
        return QueueView(entries = entries, currentIndex = entries.indexOfFirst { it.isCurrent }, readOnly = true)
    }

    private fun remoteControls(s: RemoteSessionState, access: RemoteAccess): ControlSet {
        if (access != RemoteAccess.READY || !s.connected) return ControlSet.None
        val a = s.actions
        return ControlSet(
            playPause = a.play || a.pause,
            seek = a.seek,
            next = a.next,
            previous = a.previous,
            shuffle = false,
            repeat = false,
            volume = true, // the Wheel's volume is the system's media volume (D-16), whoever plays
        )
    }

    /** The source whose songs play in another app (provider-neutral: by playback route). */
    private fun remoteSource(): MusicSource? =
        registry.ordered().firstOrNull { it.playback?.routes?.let { r -> PlaybackRoute.REMOTE in r && PlaybackRoute.DIRECT !in r } == true }

    private fun recognise(source: MusicSource?, mediaId: String?): TrackId? {
        if (source == null || mediaId.isNullOrBlank()) return null
        val key = source.playback?.remoteTrackKey(mediaId) ?: return null
        return TrackId.of(source.descriptor.id, key)
    }

    /** Whether a song's source can only play it in another app. */
    private fun playsRemotely(id: TrackId): Boolean {
        val routes = registry.get(id.sourceId)?.playback?.routes ?: return false
        return PlaybackRoute.REMOTE in routes && PlaybackRoute.DIRECT !in routes
    }

    // --- Ownership changes the listener didn't make through Podium -----------------------------------

    init {
        // Something started the local player (its notification, a headset, Podium's own UI): it owns now.
        scope.launch {
            local.snapshot.map { it.intent }.distinctUntilChanged().drop(1).collect { intent ->
                if (intent == PlayIntent.PLAY && remoteOwns) takeLocal(resume = false)
            }
        }
        // The listener started music in the other app itself: Podium mirrors and controls it.
        scope.launch {
            combine(remote.state, remote.access) { s, a -> s to a }.collect { (s, a) ->
                if (!remoteOwns && a == RemoteAccess.READY && s.playing && local.snapshot.value.intent != PlayIntent.PLAY &&
                    recognise(remoteSource(), s.mediaId) != null
                ) {
                    handoff.value = null
                    router.handOver(PlaybackRoute.REMOTE)
                    _owner.value = Owner.REMOTE
                }
            }
        }
        // Fetch the catalogue's metadata (artwork, album) for whatever the app plays or queues next.
        scope.launch {
            remote.state.map { s -> listOfNotNull(s.mediaId) + s.queue.orEmpty().mapNotNull { it.mediaId }.take(QUEUE_LOOKAHEAD) }
                .distinctUntilChanged()
                .collect { ids ->
                    val source = remoteSource() ?: return@collect
                    for (mediaId in ids) {
                        val id = recognise(source, mediaId) ?: continue
                        if (id in recognised.value) continue
                        val track = catalog.cached(id) ?: catalog.get(id).getOrNull() ?: continue
                        recognised.value = (recognised.value + (id to track)).entries.toList().takeLast(RECOGNISED_LIMIT).associate { it.key to it.value }
                    }
                }
        }
    }

    private fun takeLocal(resume: Boolean) {
        startJob?.cancel()
        handoff.value = null
        router.handOver(PlaybackRoute.DIRECT)
        _owner.value = Owner.LOCAL
        if (resume) local.play()
    }

    // --- Commands -----------------------------------------------------------------------------------------

    override fun positionMs(): Long = if (remoteOwns) remote.positionMs() else local.positionMs()

    override fun play() = when {
        !remoteOwns -> local.play()
        controllable -> remote.play()
        else -> remote.openApp()
    }

    override fun pause() {
        if (!remoteOwns) local.pause() else if (controllable) remote.pause()
    }

    override fun togglePlayPause() = when {
        !remoteOwns -> local.togglePlayPause()
        !controllable -> remote.openApp()
        remote.state.value.playing || remote.state.value.buffering -> remote.pause()
        else -> remote.play()
    }

    override fun next() {
        if (!remoteOwns) local.next() else if (controllable && remote.state.value.actions.next) remote.next()
    }

    override fun previous() {
        if (!remoteOwns) local.previous() else if (controllable && remote.state.value.actions.previous) remote.previous()
    }

    override fun seekTo(positionMs: Long) {
        if (!remoteOwns) local.seekTo(positionMs) else if (controllable && remote.state.value.actions.seek) remote.seekTo(positionMs)
    }

    // Shuffle and repeat belong to the remote app's own queue: Podium doesn't change them there.
    override fun setRepeat(mode: RepeatMode) {
        if (!remoteOwns) local.setRepeat(mode)
    }

    override fun setShuffle(enabled: Boolean) {
        if (!remoteOwns) local.setShuffle(enabled)
    }

    override fun playContext(tracks: List<TrackId>, startIndex: Int, contextLabel: String?, shuffle: Boolean, radio: Boolean) =
        start(tracks, startIndex, contextLabel, shuffle, radio, origin = null)

    override fun playCollection(tracks: List<TrackId>, startIndex: Int, contextLabel: String?, origin: RemoteContext, shuffle: Boolean) =
        start(tracks, startIndex, contextLabel, shuffle, origin is RemoteContext.Radio || origin is RemoteContext.ArtistRadio, origin)

    private fun start(tracks: List<TrackId>, startIndex: Int, label: String?, shuffle: Boolean, radio: Boolean, origin: RemoteContext?) {
        if (tracks.isEmpty()) return
        val index = startIndex.coerceIn(0, tracks.lastIndex)
        val chosen = tracks[index]
        if (!playsRemotely(chosen)) {
            // Local music, exactly as before. Songs that can only play elsewhere never enter this queue.
            val direct = tracks.filterNot(::playsRemotely)
            if (remoteOwns) takeLocal(resume = false)
            local.playContext(direct, direct.indexOf(chosen).coerceAtLeast(0), label, shuffle, radio)
            return
        }
        startRemote(chosen, radio, origin)
    }

    private fun startRemote(chosen: TrackId, radio: Boolean, origin: RemoteContext?) {
        startJob?.cancel()
        startJob = scope.launch {
            val track = catalog.get(chosen).getOrNull() ?: return@launch
            val source = registry.get(chosen.sourceId) ?: return@launch
            val facet = source.playback ?: return@launch
            handoff.value = Handoff(track, source.descriptor.id)
            router.handOver(PlaybackRoute.REMOTE) // hard cut: the local player pauses first
            _owner.value = Owner.REMOTE
            val target = (origin ?: if (radio) RemoteContext.Radio(track) else null)?.let { facet.remoteContext(it, track) }
                ?: ((facet.resolve(track, QualityRequest.Maximum, Purpose.PLAYBACK) as? FacetResolution.Resolved)?.target as? PlaybackTarget.RemoteProvider)
            if (target == null) {
                handoff.value = Handoff(track, source.descriptor.id, starting = false, problem = when (remote.access.value) {
                    RemoteAccess.NO_APP -> RemoteProblem.NO_APP
                    else -> RemoteProblem.DID_NOT_START
                })
                return@launch
            }
            router.activate(target)
            when (val result = lastStart) {
                is RemoteStart.Failed -> {
                    handoff.value = Handoff(track, source.descriptor.id, starting = false, problem = if (result.reason == RemoteAccess.NO_APP) RemoteProblem.NO_APP else RemoteProblem.DID_NOT_START)
                    return@launch
                }
                else -> Unit
            }
            if (remote.access.value != RemoteAccess.READY) {
                // Opened in the app, but Podium can't see it: say so (NEEDS_ACCESS), keep the request on show.
                handoff.value = Handoff(track, source.descriptor.id, starting = false)
                return@launch
            }
            val confirmed = withTimeoutOrNull(startTimeoutMs) {
                remote.state.first { s ->
                    s.connected && (s.playing || s.buffering) && recognise(source, s.mediaId).let { it == null || it == track.id }
                }
            }
            handoff.value = Handoff(track, source.descriptor.id, starting = false, problem = if (confirmed == null) RemoteProblem.DID_NOT_START else null)
            if (confirmed != null) handoff.value = null
        }
    }

    override fun playNext(tracks: List<TrackId>) {
        val direct = tracks.filterNot(::playsRemotely)
        if (direct.isNotEmpty()) local.playNext(direct)
    }

    override fun addToQueue(tracks: List<TrackId>) {
        val direct = tracks.filterNot(::playsRemotely)
        if (direct.isNotEmpty()) local.addToQueue(direct)
    }

    override fun move(uid: QueueUid, toIndex: Int) {
        if (!remoteOwns) local.move(uid, toIndex)
    }

    override fun remove(uids: Set<QueueUid>) {
        if (!remoteOwns) local.remove(uids)
    }

    override fun skipTo(uid: QueueUid) {
        if (uid.value.startsWith(REMOTE_UID)) {
            uid.value.removePrefix(REMOTE_UID).toLongOrNull()?.let { if (controllable) remote.skipToQueueItem(it) }
        } else if (!remoteOwns) local.skipTo(uid)
    }

    override fun clearUpcoming() {
        if (!remoteOwns) local.clearUpcoming()
    }

    override fun resumeLocal() {
        if (remoteOwns) takeLocal(resume = true) else local.play()
    }

    override fun openRemoteApp() = remote.openApp()

    private companion object {
        const val START_TIMEOUT_MS = 12_000L
        const val REMOTE_UID = "remote:"
        const val UNKNOWN = "remote"
        const val QUEUE_LOOKAHEAD = 5
        const val RECOGNISED_LIMIT = 64
    }
}
