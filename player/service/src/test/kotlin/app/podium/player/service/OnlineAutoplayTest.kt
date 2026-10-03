package app.podium.player.service

import android.content.Context
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.podium.core.common.Clock
import app.podium.core.common.Outcome
import app.podium.core.model.ArtistId
import app.podium.core.model.SourceId
import app.podium.core.model.Track
import app.podium.core.model.TrackId
import app.podium.player.api.AutoplaySettings
import app.podium.player.api.QueueOrigin
import app.podium.player.api.RecommendationEngine
import app.podium.player.api.SourceRecommendationEngine
import app.podium.player.api.TrackCatalog
import app.podium.sources.api.ArtistSummary
import app.podium.sources.api.ArtworkResolver
import app.podium.sources.api.Basis
import app.podium.sources.api.Capability
import app.podium.sources.api.FacetResolution
import app.podium.sources.api.MusicSource
import app.podium.sources.api.PlaybackFacet
import app.podium.sources.api.PlaybackTarget
import app.podium.sources.api.Purpose
import app.podium.sources.api.QualityRequest
import app.podium.sources.api.RecommendationFacet
import app.podium.sources.api.SourceCapabilities
import app.podium.sources.api.SourceDescriptor
import app.podium.sources.api.SourceHealthMonitor
import app.podium.sources.api.SourceRegistry
import app.podium.sources.api.matching.TrackMatcher
import app.podium.sources.api.resolve.StreamResolver
import app.podium.sources.test.TestMusicSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLooper
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The generated test tones, presented as an ONLINE source with recommendations. */
private class OnlineStandIn(private val inner: TestMusicSource) : MusicSource {
    val id = SourceId("online-test")
    override val descriptor = SourceDescriptor(id, "Online test", "Test", Basis.OFFICIAL_API)
    override val capabilities = MutableStateFlow(SourceCapabilities.available(Capability.DIRECT_STREAM, Capability.RECOMMENDATIONS))
    val asked = mutableListOf<Set<TrackId>>()

    private fun wrap(t: Track) = t.copy(id = TrackId.of(id, t.source.providerKey), source = t.source.copy(sourceId = id))
    private fun unwrap(t: Track) = t.copy(id = TrackId.of(inner.descriptor.id, t.source.providerKey), source = t.source.copy(sourceId = inner.descriptor.id))

    val tracks: List<Track> = inner.allTracks().filter { it.title != "Missing Master" && it.title != "Broken Tape" }.map(::wrap)

    override val playback = object : PlaybackFacet {
        override val routes = setOf(app.podium.core.model.PlaybackRoute.DIRECT)
        override suspend fun resolve(track: Track, quality: QualityRequest, purpose: Purpose): FacetResolution =
            when (val r = inner.playback.resolve(unwrap(track), quality, purpose)) {
                is FacetResolution.Resolved -> FacetResolution.Resolved(PlaybackTarget.DirectStream(track.id, (r.target as PlaybackTarget.DirectStream).media))
                else -> r
            }
    }

    override val recommendations = object : RecommendationFacet {
        override suspend fun related(seeds: List<Track>, limit: Int, exclude: Set<TrackId>): Outcome<List<Track>> {
            asked += exclude
            return Outcome.Success(tracks.filter { it.id !in exclude }.take(limit))
        }

        override suspend fun artistRadio(artist: ArtistId, limit: Int, exclude: Set<TrackId>) = Outcome.Success(emptyList<Track>())
        override suspend fun relatedArtists(artist: ArtistId, limit: Int) = Outcome.Success(emptyList<ArtistSummary>())
    }
}

@RunWith(AndroidJUnit4::class)
class OnlineAutoplayTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val health = SourceHealthMonitor(Clock.System)
    private val registry = SourceRegistry(health)
    private val local = TestMusicSource(context, TestMusicSource.Variant.PRIMARY, durationScale = 0.1)
    // A second, unregistered copy of the tones behind the online stand-in.
    private val online = OnlineStandIn(TestMusicSource(context, TestMusicSource.Variant.PRIMARY, durationScale = 0.1))
    private val catalog = TrackCatalog(registry)
    private val settings = MutableStateFlow(AutoplaySettings())

    private val engine: PodiumPlaybackEngine

    init {
        registry.register(local)
        registry.register(online)
        catalog.remember(local.allTracks() + online.tracks)
        val deps = object : PlaybackDependencies {
            override val registry = this@OnlineAutoplayTest.registry
            override val health = this@OnlineAutoplayTest.health
            override val resolver = StreamResolver(registry, health, TrackMatcher(), Clock.System)
            override val catalog = this@OnlineAutoplayTest.catalog
            override val artwork = ArtworkResolver(registry)
            override val clock = Clock.System
            override val autoplay = settings
            override val recommendations: RecommendationEngine = SourceRecommendationEngine(registry)
            override fun sessionActivity(context: Context) = null
        }
        // Constructing the engine with autoplay wired must not crash (it once read an uninitialised field).
        engine = PodiumPlaybackEngine(context, deps) { ctx, factory -> TestExoPlayerBuilder(ctx).setMediaSourceFactory(factory).build() }
    }

    @After
    fun tearDown() = engine.release()

    private fun settle() = repeat(20) { ShadowLooper.idleMainLooper(); Thread.sleep(20) }

    @Test
    fun `an online song at the end of the queue brings more from its own source`() {
        val seed = online.tracks.first()
        runBlocking { engine.playContext(listOf(seed.id), 0, "Test", shuffle = false) }
        settle()
        val items = engine.queue.state.value.items
        assertTrue(items.size > 1, "autoplay extended the queue: ${items.size}")
        val added = items.drop(1)
        assertTrue(added.all { it.origin == QueueOrigin.AUTOPLAY })
        assertTrue(added.all { it.track.source.sourceId == online.id }, "never another source")
        assertEquals(items.size, items.map { it.track.id }.toSet().size, "no duplicates")
        assertTrue(seed.id in online.asked.first(), "the playing song was excluded from the request")
        engine.assertMirrorsPlayer()
    }

    @Test
    fun `autoplay off leaves the queue alone`() {
        settings.value = AutoplaySettings(enabled = false)
        runBlocking { engine.playContext(listOf(online.tracks.first().id), 0, "Test", shuffle = false) }
        settle()
        assertEquals(1, engine.queue.state.value.items.size)
    }

    @Test
    fun `local music is never autoplayed with online suggestions`() {
        runBlocking { engine.playContext(listOf(local.allTracks().first().id), 0, "Test", shuffle = false) }
        settle()
        assertEquals(1, engine.queue.state.value.items.size)
        assertTrue(online.asked.isEmpty())
    }

    @Test
    fun `a radio the listener starts is made of radio items`() {
        runBlocking { engine.playContext(online.tracks.take(3).map { it.id }, 0, "Radio", shuffle = false, radio = true) }
        settle()
        assertTrue(engine.queue.state.value.items.take(3).all { it.origin == QueueOrigin.RADIO })
    }
}
