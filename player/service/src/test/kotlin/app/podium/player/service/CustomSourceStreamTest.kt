package app.podium.player.service

import android.content.Context
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.RobolectricUtil
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.podium.core.common.Clock
import app.podium.core.common.Outcome
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
import app.podium.sources.test.TestMusicSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Custom sources in the player (D-91): a song of the online catalogue plays from the listener's own
 * source when it has the exact recording, as DASH (a FLAC representation, like a lossless source
 * serves); everything else plays as before. Real extractors and DASH parsing on generated media.
 */
@RunWith(AndroidJUnit4::class)
class CustomSourceStreamTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val health = SourceHealthMonitor(Clock.System)
    private val registry = SourceRegistry(health)
    private val catalog = TrackCatalog(registry)
    private val tones = TestMusicSource(context, TestMusicSource.Variant.PRIMARY, durationScale = 0.1)
    private val toneFile: File = runBlocking {
        val track = tones.allTracks().first { it.title == "Tuning Fork" }
        val target = (tones.playback.resolve(track, QualityRequest.Maximum, Purpose.PLAYBACK) as FacetResolution.Resolved).target
        File(java.net.URI((target as PlaybackTarget.DirectStream).media.uri))
    }
    private val manifest: File = File(context.cacheDir, "dash-${System.nanoTime()}").apply { mkdirs() }.let { dir ->
        listOf("tone.mpd", "tone.mp4").forEach { name ->
            javaClass.classLoader!!.getResourceAsStream("dash/$name")!!.use { input -> File(dir, name).outputStream().use(input::copyTo) }
        }
        File(dir, "tone.mpd")
    }

    private fun song(source: SourceId, key: String, title: String) = Track(
        id = TrackId.of(source, key),
        source = SourceRef(source, key),
        title = title,
        artists = listOf(ArtistCredit("Band")),
        durationMs = 1_500,
        routes = setOf(PlaybackRoute.DIRECT),
    )

    /** The online catalogue: its songs play from a file (as YouTube Music's do). */
    private inner class Catalogue : MusicSource {
        override val descriptor = SourceDescriptor(SourceId("catalogue"), "Catalogue", "Catalogue", Basis.UNOFFICIAL_API, environment = MusicEnvironment.ONLINE)
        override val capabilities = MutableStateFlow(SourceCapabilities.available(Capability.DIRECT_STREAM, Capability.SEARCH))
        override val playback = object : PlaybackFacet {
            override val routes = setOf(PlaybackRoute.DIRECT)
            override suspend fun resolve(track: Track, quality: QualityRequest, purpose: Purpose): FacetResolution =
                FacetResolution.Resolved(
                    PlaybackTarget.DirectStream(
                        track.id,
                        PlayableMedia(uri = toneFile.toURI().toString(), mimeType = "audio/webm", sourceId = descriptor.id, cacheKey = "${track.id.value}|file"),
                    ),
                )
        }
    }

    /** The listener's custom source: it has "Night Drive" and "Second Song", as DASH. */
    private inner class Lossless : MusicSource {
        override val descriptor = SourceDescriptor(SourceId("custom-test"), "Lossless", "Lossless", Basis.USER_SERVER, environment = MusicEnvironment.ONLINE)
        override val capabilities = MutableStateFlow(SourceCapabilities.available(Capability.DIRECT_STREAM, Capability.SEARCH))
        val songs = listOf(song(descriptor.id, "ld1", "Night Drive"), song(descriptor.id, "ld2", "Second Song"))
        override val catalog = object : CatalogFacet {
            override suspend fun search(query: SearchQuery) =
                Outcome.Success(SearchResults(tracks = songs.filter { query.text.contains(it.title, ignoreCase = true) }))
            override suspend fun track(ref: SourceRef): Outcome<Track> = Outcome.Failure(app.podium.core.common.PodiumError.NotFound("t"))
            override suspend fun album(id: AlbumId): Outcome<AlbumDetail> = Outcome.Failure(app.podium.core.common.PodiumError.NotFound("a"))
            override suspend fun artist(id: ArtistId): Outcome<ArtistDetail> = Outcome.Failure(app.podium.core.common.PodiumError.NotFound("a"))
        }
        override val playback = object : PlaybackFacet {
            override val routes = setOf(PlaybackRoute.DIRECT)
            override suspend fun resolve(track: Track, quality: QualityRequest, purpose: Purpose): FacetResolution =
                FacetResolution.Resolved(
                    PlaybackTarget.DirectStream(
                        track.id,
                        PlayableMedia(
                            uri = manifest.toURI().toString(),
                            mimeType = MimeTypes.APPLICATION_MPD,
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
    private val customOn = MutableStateFlow(true)

    private val providers = object : StreamProviders {
        override fun ordered(environment: MusicEnvironment) = if (customOn.value && environment == MusicEnvironment.ONLINE) listOf(lossless) else emptyList()
        override fun get(id: SourceId) = lossless.takeIf { customOn.value && id == it.descriptor.id }
        override val serving = customOn.map { on -> if (on) setOf(lossless.descriptor.id) else emptySet() }
    }

    private val engine = PodiumPlaybackEngine(
        context,
        object : PlaybackDependencies {
            override val registry = this@CustomSourceStreamTest.registry
            override val health = this@CustomSourceStreamTest.health
            override val resolver = StreamResolver(registry, health, TrackMatcher(), Clock.System, preferred = providers)
            override val catalog = this@CustomSourceStreamTest.catalog
            override val artwork = ArtworkResolver(registry)
            override val clock = Clock.System
            override val streamProviders = providers
            override fun sessionActivity(context: Context) = null
        },
    ) { ctx, factory -> TestExoPlayerBuilder(ctx).setMediaSourceFactory(factory).build() }

    @After
    fun tearDown() = engine.release()

    private fun play(vararg songs: Track) {
        catalog.remember(songs.toList())
        runBlocking { assertTrue(engine.playContext(songs.map { it.id }, 0, "Test", shuffle = false)) }
    }

    /** Ready and without an error (waited for directly: the test helper fails on any error, even a recovered one). */
    private fun untilPlaying(title: String) = RobolectricUtil.runMainLooperUntil {
        engine.player.playbackState == Player.STATE_READY && engine.player.playerError == null &&
            engine.queue.state.value.current?.track?.title == title
    }

    @Test
    fun `a catalogue song plays from the custom source's DASH stream`() {
        val nightDrive = song(catalogue.descriptor.id, "c1", "Night Drive")
        play(nightDrive)
        untilPlaying("Night Drive")

        val selection = assertNotNull(engine.queue.state.value.current?.selection)
        assertEquals(ResolutionPath.PREFERRED_SOURCE, selection.path)
        assertEquals(lossless.descriptor.id, selection.servedBy)
        val item = engine.player.currentMediaItem!!
        assertEquals(MimeTypes.APPLICATION_MPD, item.localConfiguration?.mimeType)
        assertEquals(nightDrive.id.value, item.mediaMetadata.extras?.getString(PodiumExtras.TRACK_ID), "the listener's song, whoever serves it")
        assertEquals(Codec.FLAC, AudioQualityMapper.fromTracks(engine.player.currentTracks)?.codec, "measured from the media")
        assertNull(engine.lastError)
        engine.assertMirrorsPlayer()
    }

    @Test
    fun `a song the custom source doesn't have plays from its own file, as before`() {
        play(song(catalogue.descriptor.id, "c9", "Only Here"))
        untilPlaying("Only Here")
        val selection = assertNotNull(engine.queue.state.value.current?.selection)
        assertEquals(ResolutionPath.OWN_SOURCE, selection.path)
        assertNull(engine.player.currentMediaItem!!.localConfiguration?.mimeType)
        assertEquals(Codec.PCM, AudioQualityMapper.fromTracks(engine.player.currentTracks)?.codec)
    }

    @Test
    fun `a queued song built for DASH plays from its file once the custom source is off`() {
        val first = song(catalogue.descriptor.id, "c1", "Night Drive")
        val second = song(catalogue.descriptor.id, "c2", "Second Song")
        play(first, second)
        untilPlaying("Night Drive")
        // The next song was resolved ahead to the custom source, and rebuilt for DASH.
        RobolectricUtil.runMainLooperUntil { engine.player.getMediaItemAt(1).localConfiguration?.mimeType == MimeTypes.APPLICATION_MPD }

        customOn.value = false // its pinned stream goes with it
        engine.player.seekToNextMediaItem()
        untilPlaying("Second Song")

        val selection = assertNotNull(engine.queue.state.value.current?.selection)
        assertEquals(catalogue.descriptor.id, selection.servedBy)
        assertNull(engine.player.currentMediaItem!!.localConfiguration?.mimeType, "rebuilt for a file")
        assertNull(engine.lastError, "rebuilding an item isn't an error")
        engine.assertMirrorsPlayer()
    }
}
