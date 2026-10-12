package app.podium.player.service

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.RobolectricUtil
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.podium.core.common.Clock
import app.podium.core.model.Track
import app.podium.player.api.AudioEffects
import app.podium.player.api.SleepTimer
import app.podium.player.api.TrackCatalog
import app.podium.sources.api.ArtworkResolver
import app.podium.sources.api.SourceHealthMonitor
import app.podium.sources.api.SourceRegistry
import app.podium.sources.api.matching.TrackMatcher
import app.podium.sources.api.resolve.StreamResolver
import app.podium.sources.test.TestMusicSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Crossfade (D-77) and the sleep timer (D-75) on the engine, with Media3's test players decoding
 * generated tones. Both players share one test clock, so their positions can be compared.
 */
@RunWith(AndroidJUnit4::class)
class CrossfadeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val health = SourceHealthMonitor(Clock.System)
    private val registry = SourceRegistry(health)
    private val tones = TestMusicSource(context, TestMusicSource.Variant.PRIMARY, durationScale = 0.6)
    private val catalog = TrackCatalog(registry)
    private val effects = MutableStateFlow(AudioEffects())
    private val timerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val timer = SleepTimer(timerScope, { android.os.SystemClock.elapsedRealtime() })
    private val clock = FakeClock(/* isAutoAdvancing = */ true)
    private val helpers = mutableListOf<ExoPlayer>()

    private val deps = object : PlaybackDependencies {
        override val registry = this@CrossfadeTest.registry
        override val health = this@CrossfadeTest.health
        override val resolver = StreamResolver(registry, health, TrackMatcher(), Clock.System)
        override val catalog = this@CrossfadeTest.catalog
        override val artwork = ArtworkResolver(registry)
        override val clock = Clock.System
        override val effects = this@CrossfadeTest.effects
        override val sleepTimer = this@CrossfadeTest.timer
        override fun sessionActivity(context: Context) = null
    }

    private val engine: PodiumPlaybackEngine

    init {
        registry.register(tones)
        catalog.remember(tones.allTracks())
        engine = PodiumPlaybackEngine(
            context,
            deps,
            helperFactory = { ctx, factory -> TestExoPlayerBuilder(ctx).setClock(clock).setMediaSourceFactory(factory).build().also { helpers += it } },
        ) { ctx, factory -> TestExoPlayerBuilder(ctx).setClock(clock).setMediaSourceFactory(factory).build() }
    }

    @After
    fun tearDown() {
        engine.release()
        timerScope.cancel()
    }

    private fun t(title: String): Track = tones.allTracks().first { it.title == title }

    private fun play(vararg titles: String) = runBlocking {
        assertTrue(engine.playContext(titles.map { t(it).id }, 0, "Test", shuffle = false))
        engine.player.play()
    }

    private fun transitions(): MutableList<Int> {
        val reasons = mutableListOf<Int>()
        engine.player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                reasons += reason
            }
        })
        return reasons
    }

    @Test
    fun `with crossfade on, the next song starts while the last one fades, then plays alone at full volume`() {
        effects.value = AudioEffects(crossfadeSeconds = 2)
        val reasons = transitions()
        play("Tuning Fork", "Coda") // different albums: they crossfade
        RobolectricUtil.runMainLooperUntil({ engine.player.currentMediaItemIndex == 1 }, 60_000, clock)
        // The engine moved on before the first song's end, carrying its tail in the second player.
        assertEquals(listOf(Player.MEDIA_ITEM_TRANSITION_REASON_SEEK), reasons.filter { it != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED })
        val helper = assertNotNull(helpers.singleOrNull(), "one second player")
        assertTrue(helper.volume > 0f || helper.playbackState != Player.STATE_READY, "the old song is heard fading out")
        assertEquals("Coda", engine.queue.state.value.current?.track?.title, "the queue moved with the player")
        // Once the fade is over: the second player is silent and stopped, the first at full volume.
        RobolectricUtil.runMainLooperUntil({ engine.player.volume == 1f && helper.playbackState == Player.STATE_IDLE }, 60_000, clock)
        engine.assertMirrorsPlayer()
    }

    @Test
    fun `an album's next track runs straight on, without a crossfade`() {
        effects.value = AudioEffects(crossfadeSeconds = 2)
        val reasons = transitions()
        play("Tuning Fork", "Major Triad") // tracks 1 and 2 of one album
        RobolectricUtil.runMainLooperUntil({ engine.player.currentMediaItemIndex == 1 }, 60_000, clock)
        assertEquals(listOf(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO), reasons.filter { it != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED })
        assertTrue(helpers.isEmpty(), "no second player was needed")
    }

    @Test
    fun `the sleep timer at the end of the song pauses there, before the next one`() {
        timer.stopAtEndOfSong()
        play("Tuning Fork", "Coda")
        RobolectricUtil.runMainLooperUntil({ !engine.player.playWhenReady }, 60_000, clock)
        assertEquals(0, engine.player.currentMediaItemIndex, "still on the first song, at its end")
        assertEquals(app.podium.player.api.SleepTimerState.Off, timer.state.value)
    }
}
