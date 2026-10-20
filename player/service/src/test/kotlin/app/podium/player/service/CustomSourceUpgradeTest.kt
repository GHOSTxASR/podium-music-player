package app.podium.player.service

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.podium.core.common.Clock
import app.podium.core.common.Outcome
import app.podium.core.common.PodiumError
import app.podium.core.model.AlbumId
import app.podium.core.model.ArtistCredit
import app.podium.core.model.ArtistId
import app.podium.core.model.Codec
import app.podium.core.model.PlaybackRoute
import app.podium.core.model.SourceId
import app.podium.core.model.SourceRef
import app.podium.core.model.Track
import app.podium.core.model.TrackId
import app.podium.player.api.TrackCatalog
import app.podium.sources.api.AlbumDetail
import app.podium.sources.api.ArtistDetail
import app.podium.sources.api.ArtworkResolver
import app.podium.sources.api.Basis
import app.podium.sources.api.Capability
import app.podium.sources.api.CatalogFacet
import app.podium.sources.api.FacetResolution
import app.podium.sources.api.MusicEnvironment
import app.podium.sources.api.MusicSource
import app.podium.sources.api.PlayableMedia
import app.podium.sources.api.PlaybackFacet
import app.podium.sources.api.PlaybackTarget
import app.podium.sources.api.Purpose
import app.podium.sources.api.QualityRequest
import app.podium.sources.api.ResolutionPath
import app.podium.sources.api.SearchQuery
import app.podium.sources.api.SearchResults
import app.podium.sources.api.SourceCapabilities
import app.podium.sources.api.SourceDescriptor
import app.podium.sources.api.SourceHealthMonitor
import app.podium.sources.api.SourceRegistry
import app.podium.sources.api.matching.TrackMatcher
import app.podium.sources.api.resolve.StreamProviders
import app.podium.sources.api.resolve.StreamResolver
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Custom sources first (D-92), in the player: a song that started from the catalogue because the
 * custom source was too slow moves to the custom source's copy once it has loaded — mid-song, by a
 * short crossfade on the second player — and the first player switches to it at the next pause.
 * Queued songs move before they play. Media3's test players, real extractors, generated audio.
 */
@RunWith(AndroidJUnit4::class)
class CustomSourceUpgradeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val health = SourceHealthMonitor(Clock.System)
    private val registry = SourceRegistry(health)
    private val catalog = TrackCatalog(registry)
    /** Advanced by hand with the main looper, so the players keep pace with real loading (a racing clock outruns it). */
    private val clock = FakeClock(/* isAutoAdvancing = */ false)
    private val helpers = mutableListOf<ExoPlayer>()
    private val extras = MutableStateFlow(android.os.Bundle())

    private val files: File = File(context.cacheDir, "carry-${System.nanoTime()}").apply { mkdirs() }.also { dir ->
        listOf("song.mp3", "song.flac").forEach { name ->
            javaClass.classLoader!!.getResourceAsStream("carry/$name")!!.use { input -> File(dir, name).outputStream().use(input::copyTo) }
        }
    }

    private fun song(source: SourceId, key: String, title: String) = Track(
        id = TrackId.of(source, key),
        source = SourceRef(source, key),
        title = title,
        artists = listOf(ArtistCredit("Band")),
        durationMs = 32_000,
        routes = setOf(PlaybackRoute.DIRECT),
    )

    /** The online catalogue: its songs play from a lossy stream. */
    private inner class Catalogue : MusicSource {
        override val descriptor = SourceDescriptor(SourceId("catalogue"), "Catalogue", "Catalogue", Basis.UNOFFICIAL_API, environment = MusicEnvironment.ONLINE)
        override val capabilities = MutableStateFlow(SourceCapabilities.available(Capability.DIRECT_STREAM, Capability.SEARCH))
        override val playback = object : PlaybackFacet {
            override val routes = setOf(PlaybackRoute.DIRECT)
            override suspend fun resolve(track: Track, quality: QualityRequest, purpose: Purpose): FacetResolution = FacetResolution.Resolved(
                PlaybackTarget.DirectStream(
                    track.id,
                    PlayableMedia(uri = File(files, "song.mp3").toURI().toString(), mimeType = "audio/mpeg", sourceId = descriptor.id, cacheKey = "${track.id.value}|lossy"),
                ),
            )
        }
    }

    /** The listener's custom source: slow to search at first ([searchDelays], one per search), then not. */
    private inner class Lossless : MusicSource {
        override val descriptor = SourceDescriptor(SourceId("custom-test"), "Lossless", "Lossless", Basis.USER_SERVER, environment = MusicEnvironment.ONLINE)
        override val capabilities = MutableStateFlow(SourceCapabilities.available(Capability.DIRECT_STREAM, Capability.SEARCH))
        var songs = listOf(song(descriptor.id, "l1", "Night Drive"), song(descriptor.id, "l2", "Second Song"))
        val searchDelays = ArrayDeque<Long>()
        var searches = 0
        override val catalog = object : CatalogFacet {
            override suspend fun search(query: SearchQuery): Outcome<SearchResults> {
                searches++
                searchDelays.removeFirstOrNull()?.let { delay(it) }
                return Outcome.Success(SearchResults(tracks = songs.filter { query.text.contains(it.title, ignoreCase = true) }))
            }
            override suspend fun track(ref: SourceRef): Outcome<Track> = Outcome.Failure(PodiumError.NotFound("t"))
            override suspend fun album(id: AlbumId): Outcome<AlbumDetail> = Outcome.Failure(PodiumError.NotFound("a"))
            override suspend fun artist(id: ArtistId): Outcome<ArtistDetail> = Outcome.Failure(PodiumError.NotFound("a"))
        }
        override val playback = object : PlaybackFacet {
            override val routes = setOf(PlaybackRoute.DIRECT)
            override suspend fun resolve(track: Track, quality: QualityRequest, purpose: Purpose): FacetResolution = FacetResolution.Resolved(
                PlaybackTarget.DirectStream(
                    track.id,
                    PlayableMedia(
                        uri = File(files, "song.flac").toURI().toString(),
                        mimeType = "audio/flac",
                        sourceId = descriptor.id,
                        cacheKey = "${track.id.value}|lossless",
                        expiresAtMillis = System.currentTimeMillis() + 15 * 60_000,
                    ),
                ),
            )
        }
    }

    private val catalogue = Catalogue().also { registry.register(it) }
    private val lossless = Lossless()
    /** Whether the listener has the custom source on (it can be turned on while a song plays). */
    private val customOn = MutableStateFlow(true)
    private val providers = object : StreamProviders {
        override fun ordered(environment: MusicEnvironment) = if (customOn.value && environment == MusicEnvironment.ONLINE) listOf(lossless) else emptyList()
        override fun get(id: SourceId) = lossless.takeIf { customOn.value && id == it.descriptor.id }
        override val serving = customOn.map { on -> if (on) setOf(lossless.descriptor.id) else emptySet() }
    }

    private val engine = PodiumPlaybackEngine(
        context,
        object : PlaybackDependencies {
            override val registry = this@CustomSourceUpgradeTest.registry
            override val health = this@CustomSourceUpgradeTest.health
            // Custom sources get 300 ms on the first ask: a slow one loses the song to the catalogue.
            override val resolver = StreamResolver(registry, health, TrackMatcher(), Clock.System, preferred = providers, preferredBudgetMillis = 300)
            override val catalog = this@CustomSourceUpgradeTest.catalog
            override val artwork = ArtworkResolver(registry)
            override val clock = Clock.System
            override val streamProviders = providers
            override fun sessionActivity(context: Context) = null
        },
        helperFactory = { ctx, factory -> TestExoPlayerBuilder(ctx).setClock(clock).setMediaSourceFactory(factory).build().also { helpers += it } },
    ) { ctx, factory -> TestExoPlayerBuilder(ctx).setClock(clock).setMediaSourceFactory(factory).build() }.also { e ->
        e.onExtrasChanged = { extras.value = it }
    }

    @After
    fun tearDown() = engine.release()

    private fun play(vararg songs: Track) {
        catalog.remember(songs.toList())
        runBlocking { assertTrue(engine.playContext(songs.map { it.id }, 0, "Test", shuffle = false)) }
    }

    /**
     * Runs the players until [condition], in steps of 10 ms of the players' clock and the main
     * looper's together, each given a little real time for the loading threads; for at most
     * [timeoutMs] of real time, so a stalled player never hangs a test.
     */
    private fun until(timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw java.util.concurrent.TimeoutException("waited ${timeoutMs / 1000} s")
            clock.advanceTime(STEP_MS)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(STEP_MS))
            Thread.sleep(2)
        }
    }

    private companion object {
        const val STEP_MS = 10L
    }

    private fun codecOf(player: ExoPlayer): Codec? = AudioQualityMapper.fromTracks(player.currentTracks)?.codec

    @Test
    fun `a song that started from the catalogue moves to the custom source's copy mid-song, then for good at a pause`() {
        // The custom source is turned on only once the catalogue's stream is playing.
        customOn.value = false
        val nightDrive = song(catalogue.descriptor.id, "c1", "Night Drive")
        play(nightDrive)
        until { engine.player.playbackState == Player.STATE_READY && engine.player.currentPosition > 2_000 }
        assertEquals(ResolutionPath.OWN_SOURCE, engine.queue.state.value.current?.selection?.path, "the catalogue started it")
        assertEquals(Codec.MP3, codecOf(engine.player))
        customOn.value = true

        // The copy is found and loaded ahead on the second player, levelled, and crossfaded to.
        until { extras.value.getString(PodiumExtras.SERVED_BY_ID) == lossless.descriptor.id.value }
        val helper = assertNotNull(helpers.singleOrNull(), "the second player carries the song")
        assertTrue(helper.isPlaying)
        assertEquals(ResolutionPath.PREFERRED_SOURCE.name, extras.value.getString(PodiumExtras.RESOLUTION_PATH))
        assertEquals(Codec.FLAC.name, extras.value.getString(PodiumExtras.HEARD_CODEC), "the label follows what's heard")
        assertEquals(Codec.MP3, codecOf(engine.player), "the first player plays on, silent")
        assertEquals(0f, engine.player.volume)
        assertTrue(kotlin.math.abs(helper.currentPosition - engine.player.currentPosition) < 250, "level: ${helper.currentPosition} vs ${engine.player.currentPosition}")
        assertEquals(nightDrive.id, engine.queue.state.value.current?.track?.id, "the listener's song, whoever serves it")

        // Paused: the first player switches to the copy, where it was heard.
        val heardAt = helper.currentPosition
        engine.player.pause()
        until { engine.queue.state.value.current?.selection?.path == ResolutionPath.PREFERRED_SOURCE && engine.player.playbackState == Player.STATE_READY }
        assertEquals(Codec.FLAC, codecOf(engine.player))
        assertEquals(1f, engine.player.volume)
        assertTrue(kotlin.math.abs(engine.player.currentPosition - heardAt) < 500, "carries on where it was: ${engine.player.currentPosition} vs $heardAt")
        assertEquals("custom-test", engine.player.currentMediaItem?.localConfiguration?.uri?.getQueryParameter("from"))
        engine.player.play()
        until { engine.player.isPlaying }
        assertEquals(null, engine.lastError)
        engine.assertMirrorsPlayer()
    }

    @Test
    fun `a queued song moves to the custom source's copy before it plays`() {
        // Every first ask is too slow; the asks after answer at once.
        lossless.searchDelays += listOf(5_000L, 5_000L)
        val first = song(catalogue.descriptor.id, "c1", "Night Drive")
        val second = song(catalogue.descriptor.id, "c2", "Second Song")
        play(first, second)
        until { engine.queue.state.value.items.getOrNull(1)?.selection?.path == ResolutionPath.PREFERRED_SOURCE }
        assertEquals("custom-test", engine.player.getMediaItemAt(1).localConfiguration?.uri?.getQueryParameter("from"), "rebuilt for the copy")
        engine.player.seekToNextMediaItem()
        until { engine.player.playbackState == Player.STATE_READY && engine.queue.state.value.current?.track?.id == second.id }
        assertEquals(Codec.FLAC, codecOf(engine.player))
        assertEquals(null, engine.lastError)
    }

    @Test
    fun `a song no custom source has is never asked for again`() {
        lossless.songs = emptyList()
        play(song(catalogue.descriptor.id, "c9", "Only Here"))
        until { engine.player.playbackState == Player.STATE_READY }
        val searches = lossless.searches
        engine.player.pause()
        engine.player.play()
        until(5_000) { engine.player.isPlaying }
        assertEquals(searches, lossless.searches, "every custom source answered that it hasn't got it")
        assertEquals(ResolutionPath.OWN_SOURCE, engine.queue.state.value.current?.selection?.path)
    }
}
