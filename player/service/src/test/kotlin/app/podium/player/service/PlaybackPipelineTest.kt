package app.podium.player.service

import android.content.Context
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.podium.core.common.Clock
import app.podium.core.model.Codec
import app.podium.core.model.QualityLabel
import app.podium.core.model.QualityReport
import app.podium.core.model.Track
import app.podium.player.api.TrackCatalog
import app.podium.sources.api.ArtworkResolver
import app.podium.sources.api.ResolutionPath
import app.podium.sources.api.SourceHealthMonitor
import app.podium.sources.api.SourceRegistry
import app.podium.sources.api.matching.MatchTier
import app.podium.sources.api.matching.TrackMatcher
import app.podium.sources.api.resolve.StreamResolver
import app.podium.sources.test.TestMusicSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration: MusicSource → Track → QueueItem → StreamResolver → PlayableMedia → Media3.
 * Real extractors and data sources decode real (generated) audio files; only the renderers are
 * Media3's test fakes, because Robolectric has no audio output.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackPipelineTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val health = SourceHealthMonitor(Clock.System)
    private val registry = SourceRegistry(health)
    private val primary = TestMusicSource(context, TestMusicSource.Variant.PRIMARY, durationScale = 0.1)
    private val mirror = TestMusicSource(context, TestMusicSource.Variant.MIRROR, durationScale = 0.1)
    private val catalog = TrackCatalog(registry)

    private val deps = object : PlaybackDependencies {
        override val registry = this@PlaybackPipelineTest.registry
        override val health = this@PlaybackPipelineTest.health
        override val resolver = StreamResolver(registry, health, TrackMatcher(), Clock.System)
        override val catalog = this@PlaybackPipelineTest.catalog
        override val artwork = ArtworkResolver(registry)
        override val clock = Clock.System
        override fun sessionActivity(context: Context) = null
    }

    private val engine: PodiumPlaybackEngine

    init {
        registry.register(primary)
        registry.register(mirror)
        catalog.remember(primary.allTracks() + mirror.allTracks())
        engine = PodiumPlaybackEngine(context, deps) { ctx, mediaSourceFactory ->
            TestExoPlayerBuilder(ctx).setMediaSourceFactory(mediaSourceFactory).build()
        }
    }

    @After
    fun tearDown() = engine.release()

    private fun t(title: String): Track = primary.allTracks().first { it.title == title }

    private fun play(vararg titles: String) = runBlocking {
        assertTrue(engine.playContext(titles.map { t(it).id }, 0, "Test", shuffle = false))
    }

    @Test
    fun `a queued track is resolved, pinned and decoded by Media3`() {
        play("Tuning Fork", "Major Triad")
        TestPlayerRunHelper.run(engine.player).untilState(Player.STATE_READY)

        val current = assertNotNull(engine.queue.state.value.current)
        val selection = assertNotNull(current.selection, "the playing item must be pinned")
        assertEquals(ResolutionPath.OWN_SOURCE, selection.path)
        assertTrue(selection.media!!.uri.startsWith("file:"), selection.media!!.uri)

        val measured = assertNotNull(AudioQualityMapper.fromTracks(engine.player.currentTracks))
        assertEquals(Codec.PCM, measured.codec)
        assertEquals(16, measured.bitDepth)
        assertEquals(44_100, measured.sampleRateHz)
        assertEquals("PCM 16-bit/44.1 kHz", QualityLabel.format(measured))

        TestPlayerRunHelper.run(engine.player).untilState(Player.STATE_ENDED)
        assertTrue(engine.queue.state.value.items.all { it.selection != null }, "every played item was pinned")
        assertEquals("Major Triad", engine.queue.state.value.current?.track?.title)
        engine.assertMirrorsPlayer()
    }

    @Test
    fun `an exact copy from another source is chosen before playback starts`() {
        play("Missing Master")
        TestPlayerRunHelper.run(engine.player).untilState(Player.STATE_READY)

        val selection = assertNotNull(engine.queue.state.value.current?.selection)
        assertEquals(ResolutionPath.EXACT_FALLBACK, selection.path)
        assertEquals(mirror.descriptor.id, selection.servedBy)
        assertEquals("Missing Master", selection.servedTrack.title, "never the Live decoy")
        assertEquals(MatchTier.EXACT, selection.match?.tier)
    }

    @Test
    fun `a source's quality claim never becomes the label`() {
        play("High Resolution Claim")
        TestPlayerRunHelper.run(engine.player).untilState(Player.STATE_READY)

        val selection = assertNotNull(engine.queue.state.value.current?.selection)
        val report = QualityReport(
            sourceClaimed = selection.media?.claimedQuality,
            actualMedia = AudioQualityMapper.fromTracks(engine.player.currentTracks),
        )
        assertEquals(Codec.FLAC, report.sourceClaimed?.codec)
        assertTrue(report.codecMismatch)
        assertEquals("PCM 16-bit/44.1 kHz", QualityLabel.forNowPlaying(report))
    }

    @Test
    fun `broken media is reported and skipped`() {
        play("Broken Tape", "Tuning Fork")
        TestPlayerRunHelper.run(engine.player).untilPlayerError()
        assertNotNull(engine.lastError)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        TestPlayerRunHelper.run(engine.player).untilState(Player.STATE_READY)
        assertEquals("Tuning Fork", engine.queue.state.value.current?.track?.title)
        assertTrue(health.canAttempt(primary.descriptor.id), "bad media is per item, the source stays usable")
    }

    @Test
    fun `queue edits keep the player's playlist an exact mirror`() {
        play("Tuning Fork", "Major Triad", "Minor Turn", "Low Hum")
        TestPlayerRunHelper.run(engine.player).untilState(Player.STATE_READY)
        runBlocking {
            engine.playNext(listOf(t("Coda").id))
            engine.addToQueue(listOf(t("Slow Drift").id, t("Night Train").id))
        }
        engine.assertMirrorsPlayer()
        val items = engine.queue.state.value.items
        engine.move(items.last().uid, engine.queue.state.value.currentIndex + 1)
        engine.assertMirrorsPlayer()
        engine.remove(setOf(items[2].uid))
        engine.assertMirrorsPlayer()
        engine.setShuffle(true)
        engine.assertMirrorsPlayer()
        engine.setShuffle(false)
        engine.assertMirrorsPlayer()
        engine.clearUpcoming()
        engine.assertMirrorsPlayer()
        assertTrue(engine.queue.state.value.upNext.isEmpty())
    }
}
