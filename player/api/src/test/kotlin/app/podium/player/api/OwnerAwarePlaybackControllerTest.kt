package app.podium.player.api

import app.podium.core.common.Outcome
import app.podium.core.common.PodiumError
import app.podium.core.model.PlaybackRoute
import app.podium.core.model.PlaylistId
import app.podium.core.model.QueueUid
import app.podium.core.model.SourceId
import app.podium.core.model.SourceRef
import app.podium.core.model.Track
import app.podium.core.model.TrackId
import app.podium.core.common.Clock
import app.podium.sources.api.AlbumDetail
import app.podium.sources.api.ArtistDetail
import app.podium.sources.api.Basis
import app.podium.sources.api.CatalogFacet
import app.podium.sources.api.ControlsOwner
import app.podium.sources.api.FacetResolution
import app.podium.sources.api.MusicEnvironment
import app.podium.sources.api.MusicSource
import app.podium.sources.api.PlaybackFacet
import app.podium.sources.api.PlaybackTarget
import app.podium.sources.api.Purpose
import app.podium.sources.api.QualityRequest
import app.podium.sources.api.QueueOwnership
import app.podium.sources.api.RemoteContext
import app.podium.sources.api.RemotePolicy
import app.podium.sources.api.SearchQuery
import app.podium.sources.api.SearchResults
import app.podium.sources.api.SourceCapabilities
import app.podium.sources.api.SourceDescriptor
import app.podium.sources.api.SourceHealthMonitor
import app.podium.sources.api.SourceRegistry
import app.podium.sources.testing.FakeMusicSource
import app.podium.sources.testing.track
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OwnerAwarePlaybackControllerTest {

    /** Podium's own player, recording what it was asked. */
    private class FakeLocal : PlaybackController {
        val calls = mutableListOf<String>()
        override val snapshot = MutableStateFlow(PlaybackSnapshot())
        override val queue = MutableStateFlow(QueueView())
        var position = 0L
        override fun positionMs() = position
        override fun play() { calls += "play"; snapshot.value = snapshot.value.copy(intent = PlayIntent.PLAY) }
        override fun pause() { calls += "pause"; snapshot.value = snapshot.value.copy(intent = PlayIntent.PAUSE) }
        override fun togglePlayPause() { calls += "toggle" }
        override fun next() { calls += "next" }
        override fun previous() { calls += "previous" }
        override fun seekTo(positionMs: Long) { calls += "seek $positionMs" }
        override fun setRepeat(mode: RepeatMode) { calls += "repeat $mode" }
        override fun setShuffle(enabled: Boolean) { calls += "shuffle $enabled" }
        override fun playContext(tracks: List<TrackId>, startIndex: Int, contextLabel: String?, shuffle: Boolean, radio: Boolean) {
            calls += "context ${tracks.joinToString { it.value }} @$startIndex"
            queue.value = QueueView(tracks.mapIndexed { i, id -> QueueEntry(QueueUid("u$i"), id, id.value, "", null, null, QueueOrigin.CONTEXT, i == startIndex) }, startIndex)
            snapshot.value = PlaybackSnapshot(status = PlaybackStatus.Playing, intent = PlayIntent.PLAY)
        }
        override fun playNext(tracks: List<TrackId>) { calls += "next-up ${tracks.joinToString { it.value }}" }
        override fun addToQueue(tracks: List<TrackId>) { calls += "add ${tracks.joinToString { it.value }}" }
        override fun move(uid: QueueUid, toIndex: Int) { calls += "move" }
        override fun remove(uids: Set<QueueUid>) { calls += "remove" }
        override fun skipTo(uid: QueueUid) { calls += "skip ${uid.value}" }
        override fun clearUpcoming() { calls += "clear" }
    }

    /** The other app's media session. */
    private class FakeRemote : RemotePlayback {
        val calls = mutableListOf<String>()
        override val access = MutableStateFlow(RemoteAccess.READY)
        override val state = MutableStateFlow(RemoteSessionState())
        var answer: RemoteStart = RemoteStart.Controlled
        var started: PlaybackTarget.RemoteProvider? = null
        /** What the app does once asked: start the song with this session id. */
        var plays: String? = null
        override suspend fun start(target: PlaybackTarget.RemoteProvider): RemoteStart {
            started = target
            calls += "start ${target.providerItemRef}"
            plays?.let { state.value = playing(it) }
            return answer
        }
        override fun play() { calls += "play"; state.value = state.value.copy(playing = true) }
        override fun pause() { calls += "pause"; state.value = state.value.copy(playing = false) }
        override fun seekTo(positionMs: Long) { calls += "seek $positionMs" }
        override fun next() { calls += "next" }
        override fun previous() { calls += "previous" }
        override fun skipToQueueItem(id: Long) { calls += "skip $id" }
        override fun openApp() { calls += "open" }
        override fun refresh() = Unit
        override fun positionMs() = 42_000L

        fun playing(mediaId: String, title: String = "Session title") = RemoteSessionState(
            connected = true, playing = true, title = title, artist = "Session artist", mediaId = mediaId, durationMs = 180_000,
            actions = RemoteActions(play = true, pause = true, seek = true, next = true, previous = true, skipToQueueItem = true),
            queue = listOf(RemoteQueueItem(1, title, "Session artist", mediaId), RemoteQueueItem(2, "After", "Someone", "vid00000002")),
            activeQueueItemId = 1,
        )
    }

    /** A source whose songs only play in another app (like YouTube Music). */
    private class RemoteOnlySource(val songs: List<Track>) : MusicSource {
        override val descriptor = SourceDescriptor(SourceId("remote-src"), "Elsewhere Music", "Elsewhere", Basis.UNOFFICIAL_API, environment = MusicEnvironment.ONLINE)
        override val capabilities: StateFlow<SourceCapabilities> = MutableStateFlow(SourceCapabilities.None)
        override val catalog = object : CatalogFacet {
            override suspend fun search(query: SearchQuery) = Outcome.Success(SearchResults.Empty)
            override suspend fun track(ref: SourceRef): Outcome<Track> =
                songs.firstOrNull { it.source.providerKey == ref.providerKey }?.let { Outcome.Success(it) } ?: Outcome.Failure(PodiumError.NotFound("t"))
            override suspend fun album(id: app.podium.core.model.AlbumId): Outcome<AlbumDetail> = Outcome.Failure(PodiumError.NotFound("a"))
            override suspend fun artist(id: app.podium.core.model.ArtistId): Outcome<ArtistDetail> = Outcome.Failure(PodiumError.NotFound("a"))
        }
        override val playback = object : PlaybackFacet {
            override val routes = setOf(PlaybackRoute.REMOTE)
            override suspend fun resolve(track: Track, quality: QualityRequest, purpose: Purpose) =
                FacetResolution.Resolved(target(track.id, "app://song/${track.source.providerKey}"))
            override fun remoteTrackKey(sessionMediaId: String) = sessionMediaId.takeIf { it.startsWith("vid") }
            override fun remoteContext(context: RemoteContext, start: Track?) = when (context) {
                is RemoteContext.Collection -> target(start!!.id, "app://list/${context.id.providerKey}/${start.source.providerKey}")
                is RemoteContext.Radio -> target(context.seed.id, "app://radio/${context.seed.source.providerKey}")
                else -> null
            }
        }

        fun target(id: TrackId, ref: String) = PlaybackTarget.RemoteProvider(
            id, "app.package", ref,
            RemotePolicy(QueueOwnership.PROVIDER, false, false, ControlsOwner.PROVIDER, requiresProviderApp = "app.package"),
        )
    }

    private val song1 = track("Remote One", source = "remote-src", key = "vid00000001")
    private val song2 = track("Remote Two", source = "remote-src", key = "vid00000002")
    private val localSong = track("Local Song", source = "local", key = "l1")
    private val localSong2 = track("Local Two", source = "local", key = "l2")

    private val local = FakeLocal()
    private val remote = FakeRemote()
    private val registry = SourceRegistry(SourceHealthMonitor(Clock.System)).apply {
        register(FakeMusicSource("local", listOf(localSong, localSong2)))
        register(RemoteOnlySource(listOf(song1, song2)))
    }
    private val catalog = TrackCatalog(registry).apply { remember(listOf(song1, song2, localSong, localSong2)) }

    private fun TestScope.controller() =
        OwnerAwarePlaybackController(local, remote, catalog, registry, backgroundScope, startTimeoutMs = 5_000)

    @Test
    fun `local songs play exactly as before and nothing remote is touched`() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.playContext(listOf(localSong.id, localSong2.id), 1, "Songs")
        assertEquals(listOf("context local|l1, local|l2 @1"), local.calls)
        assertTrue(remote.calls.isEmpty())
        assertEquals(OwnerAwarePlaybackController.Owner.LOCAL, c.owner.value)
        assertEquals(local.snapshot.value, c.snapshot.value)
        c.next(); c.seekTo(10); c.setShuffle(true)
        assertEquals(listOf("next", "seek 10", "shuffle true"), local.calls.drop(1))
    }

    @Test
    fun `a remote song hands over with a hard cut and the local queue waits untouched`() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.playContext(listOf(localSong.id, localSong2.id), 0, "Songs")
        val localQueue = local.queue.value
        remote.plays = "vid00000001"
        c.playContext(listOf(song1.id, song2.id), 0, "Search")
        // The local player paused first; it was never given a remote song.
        assertEquals("pause", local.calls.last())
        assertTrue(local.calls.none { "remote-src" in it })
        assertEquals("start app://song/vid00000001", remote.calls.single())
        assertEquals(OwnerAwarePlaybackController.Owner.REMOTE, c.owner.value)
        assertEquals(localQueue, local.queue.value)

        val snap = c.snapshot.value
        assertIs<PlaybackOwner.Remote>(snap.owner)
        assertEquals("Elsewhere Music", (snap.owner as PlaybackOwner.Remote).displayName)
        assertEquals(PlaybackStatus.Playing, snap.status)
        assertEquals(song1.id, snap.item!!.trackId)
        assertEquals("Remote One", snap.item!!.title) // catalogue metadata wins over the session's
        assertTrue(snap.remote!!.controllable)
        assertTrue(snap.canResumeLocal)
        assertTrue(snap.controls.seek && snap.controls.next)
        assertFalse(snap.controls.shuffle || snap.controls.repeat)

        // Up Next mirrors the app's queue, read-only.
        val q = c.queue.value
        assertTrue(q.readOnly)
        assertEquals(listOf("Remote One", "Remote Two"), q.entries.map { it.title })
        assertEquals(0, q.currentIndex)
        c.skipTo(q.entries[1].uid)
        assertEquals("skip 2", remote.calls.last())
    }

    @Test
    fun `transport goes to whoever owns playback`() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        remote.plays = "vid00000001"
        c.playContext(listOf(song1.id), 0, null)
        c.pause(); c.play(); c.next(); c.previous(); c.seekTo(5_000)
        assertEquals(listOf("pause", "play", "next", "previous", "seek 5000"), remote.calls.drop(1))
        assertEquals(42_000L, c.positionMs())
        c.setShuffle(true); c.setRepeat(RepeatMode.ALL); c.clearUpcoming()
        assertTrue(local.calls.none { it.startsWith("shuffle") || it.startsWith("repeat") || it == "clear" })
    }

    @Test
    fun `an album hands over as a context starting at the chosen song`() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.playCollection(listOf(song1.id, song2.id), 1, "Album", RemoteContext.Collection(PlaylistId("remote-src|ALB1")))
        assertEquals("start app://list/ALB1/vid00000002", remote.calls.single())
        c.playContext(listOf(song1.id), 0, "Radio", radio = true)
        assertEquals("start app://radio/vid00000001", remote.calls.last())
    }

    @Test
    fun `playing a local song takes playback back and the remote app pauses`() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        remote.plays = "vid00000001"
        c.playContext(listOf(song1.id), 0, null)
        c.playContext(listOf(localSong.id), 0, "Songs")
        assertEquals("pause", remote.calls.last())
        assertEquals(OwnerAwarePlaybackController.Owner.LOCAL, c.owner.value)
        assertEquals("context local|l1 @0", local.calls.last())
    }

    @Test
    fun `back to my music resumes the paused local queue`() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.playContext(listOf(localSong.id, localSong2.id), 1, "Songs")
        remote.plays = "vid00000001"
        c.playContext(listOf(song1.id), 0, null)
        local.calls.clear()
        c.resumeLocal()
        assertEquals(listOf("play"), local.calls)
        assertEquals("pause", remote.calls.last())
        assertEquals(OwnerAwarePlaybackController.Owner.LOCAL, c.owner.value)
    }

    @Test
    fun `the local player starting from elsewhere takes over`() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        remote.plays = "vid00000001"
        c.playContext(listOf(song1.id), 0, null)
        // e.g. play pressed on Podium's own notification
        local.snapshot.value = local.snapshot.value.copy(intent = PlayIntent.PLAY)
        assertEquals(OwnerAwarePlaybackController.Owner.LOCAL, c.owner.value)
        assertEquals("pause", remote.calls.last())
    }

    @Test
    fun `music the listener starts in the other app is mirrored when nothing local plays`() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        remote.state.value = remote.playing("vid00000002")
        assertEquals(OwnerAwarePlaybackController.Owner.REMOTE, c.owner.value)
        assertEquals(song2.id, c.snapshot.value.item!!.trackId)
        // Something unrecognisable isn't adopted.
        val c2 = OwnerAwarePlaybackController(FakeLocal(), FakeRemote().also { it.state.value = it.playing("podcast-xyz") }, catalog, registry, backgroundScope)
        assertEquals(OwnerAwarePlaybackController.Owner.LOCAL, c2.owner.value)
    }

    @Test
    fun `without access Podium opens the app and says it can't control it`() = runTest(UnconfinedTestDispatcher()) {
        remote.access.value = RemoteAccess.NEEDS_ACCESS
        remote.answer = RemoteStart.OpenedApp
        val c = controller()
        c.playContext(listOf(song1.id), 0, null)
        val snap = c.snapshot.value
        assertEquals("Remote One", snap.item!!.title) // what was asked for, never invented
        assertFalse(snap.remote!!.controllable)
        assertEquals(RemoteProblem.NEEDS_ACCESS, snap.remote!!.problem)
        assertEquals(ControlSet.None, snap.controls)
        assertTrue(c.queue.value.hidden)
        c.togglePlayPause()
        assertEquals("open", remote.calls.last())
    }

    @Test
    fun `an app that never starts the song is reported`() = runTest {
        val c = controller()
        c.playContext(listOf(song1.id), 0, null)
        runCurrent()
        assertTrue(c.snapshot.value.remote!!.starting)
        assertEquals(PlaybackStatus.Loading, c.snapshot.value.status)
        advanceTimeBy(6_000)
        runCurrent()
        assertEquals(RemoteProblem.DID_NOT_START, c.snapshot.value.remote!!.problem)
        assertFalse(c.snapshot.value.remote!!.starting)
    }

    @Test
    fun `without the app nothing starts`() = runTest(UnconfinedTestDispatcher()) {
        remote.access.value = RemoteAccess.NO_APP
        remote.answer = RemoteStart.Failed(RemoteAccess.NO_APP)
        val c = controller()
        c.playContext(listOf(song1.id), 0, null)
        assertEquals(RemoteProblem.NO_APP, c.snapshot.value.remote!!.problem)
    }

    @Test
    fun `remote songs never join the local queue`() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.addToQueue(listOf(song1.id, localSong.id))
        c.playNext(listOf(song2.id))
        assertEquals(listOf("add local|l1"), local.calls)
        // A mixed list started from a local song keeps only what plays here.
        c.playContext(listOf(song1.id, localSong.id, localSong2.id), 2, "Mixed")
        assertEquals("context local|l1, local|l2 @1", local.calls.last())
        assertNull(remote.started)
    }
}
