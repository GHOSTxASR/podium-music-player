package app.podium.player.service

import android.content.Context
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.RobolectricUtil
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.podium.core.common.Clock
import app.podium.core.common.PodiumError
import app.podium.core.model.PlaybackRoute
import app.podium.core.model.SourceId
import app.podium.core.model.SourceRef
import app.podium.core.model.Track
import app.podium.core.model.TrackId
import app.podium.player.api.TrackCatalog
import app.podium.sources.api.ArtworkResolver
import app.podium.sources.api.Basis
import app.podium.sources.api.Capability
import app.podium.sources.api.CapabilityState
import app.podium.sources.api.FacetResolution
import app.podium.sources.api.MusicEnvironment
import app.podium.sources.api.MusicSource
import app.podium.sources.api.PlayableMedia
import app.podium.sources.api.PlaybackFacet
import app.podium.sources.api.PlaybackTarget
import app.podium.sources.api.Purpose
import app.podium.sources.api.QualityRequest
import app.podium.sources.api.SourceCapabilities
import app.podium.sources.api.SourceDescriptor
import app.podium.sources.api.SourceHealth
import app.podium.sources.api.SourceHealthMonitor
import app.podium.sources.api.SourceRegistry
import app.podium.sources.api.resolve.StreamResolver
import app.podium.sources.api.matching.TrackMatcher
import app.podium.sources.test.TestMusicSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A stream URL refused mid-play (an expired or revoked signed URL: HTTP 403) is asked for afresh
 * from the same source and playback carries on; a refusal never reads as "sign in again" and never
 * benches the source.
 */
@RunWith(AndroidJUnit4::class)
class StreamRefreshTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val health = SourceHealthMonitor(Clock.System)
    private val registry = SourceRegistry(health)
    private val tones = TestMusicSource(context, TestMusicSource.Variant.PRIMARY, durationScale = 0.1)
    private val catalog = TrackCatalog(registry)
    private val server = TinyServer(audio = runBlocking { toneFile() })

    /** An online source whose signed URLs go stale: the first answer is refused; after it's forgotten, a fresh one plays. */
    private inner class ExpiringSource(var freshAfterForget: Boolean) : MusicSource {
        val forgotten = AtomicInteger()
        override val descriptor = SourceDescriptor(SourceId("expiring"), "Expiring", "Expiring", Basis.UNOFFICIAL_API, environment = MusicEnvironment.ONLINE)
        override val capabilities = MutableStateFlow(SourceCapabilities(mapOf(Capability.DIRECT_STREAM to CapabilityState.Available)))
        override val playback = object : PlaybackFacet {
            override val routes = setOf(PlaybackRoute.DIRECT)
            override suspend fun resolve(track: Track, quality: QualityRequest, purpose: Purpose): FacetResolution {
                val path = if (freshAfterForget && forgotten.get() > 0) "/fresh" else "/stale"
                return FacetResolution.Resolved(
                    PlaybackTarget.DirectStream(
                        track.id,
                        PlayableMedia(
                            uri = "http://127.0.0.1:${server.port}$path",
                            mimeType = "audio/wav",
                            sourceId = descriptor.id,
                            cacheKey = "${track.id.value}|$path",
                            expiresAtMillis = System.currentTimeMillis() + 6 * 60 * 60 * 1000L,
                        ),
                    ),
                )
            }
            override fun forgetStream(track: Track) {
                forgotten.incrementAndGet()
            }
        }

        fun song(key: String) = Track(
            id = TrackId.of(descriptor.id, key),
            title = "Song $key",
            artistDisplay = "Artist",
            artists = emptyList(),
            album = null,
            durationMs = 2_000,
            source = SourceRef(descriptor.id, key),
            routes = setOf(PlaybackRoute.DIRECT),
        )
    }

    private fun engineFor(source: ExpiringSource): PodiumPlaybackEngine {
        registry.register(source)
        val deps = object : PlaybackDependencies {
            override val registry = this@StreamRefreshTest.registry
            override val health = this@StreamRefreshTest.health
            override val resolver = StreamResolver(registry, health, TrackMatcher(), Clock.System)
            override val catalog = this@StreamRefreshTest.catalog
            override val artwork = ArtworkResolver(registry)
            override val clock = Clock.System
            override fun sessionActivity(context: Context) = null
        }
        return PodiumPlaybackEngine(context, deps) { ctx, factory -> TestExoPlayerBuilder(ctx).setMediaSourceFactory(factory).build() }
            .also { engine = it }
    }

    private var engine: PodiumPlaybackEngine? = null

    @After
    fun tearDown() {
        engine?.release()
        server.close()
    }

    private suspend fun toneFile(): File {
        registry.register(tones)
        val track = tones.allTracks().first { it.title == "Tuning Fork" }
        val target = (tones.playback.resolve(track, QualityRequest.Maximum, Purpose.PLAYBACK) as FacetResolution.Resolved).target
        return File(java.net.URI((target as PlaybackTarget.DirectStream).media.uri))
    }

    @Test
    fun `a refused stream is asked for afresh and the song carries on`() {
        val source = ExpiringSource(freshAfterForget = true)
        val engine = engineFor(source)
        val song = source.song("expiring001")
        catalog.remember(listOf(song))
        runBlocking { assertTrue(engine.playContext(listOf(song.id), 0, "Test", shuffle = false)) }

        // The first URL is refused; the same source is asked again (told to forget the refused one)
        // and playback goes on. (Waited for directly: the test helper fails on any error it sees,
        // even one the player recovered from.)
        RobolectricUtil.runMainLooperUntil { engine.player.playbackState == Player.STATE_READY && engine.player.playerError == null }

        assertEquals(1, source.forgotten.get())
        val selection = assertNotNull(engine.queue.state.value.current?.selection)
        assertTrue(selection.media!!.uri.endsWith("/fresh"), selection.media!!.uri)
        assertEquals(song.id, engine.queue.state.value.current?.track?.id, "the same song, not the next")
        assertEquals(null, engine.lastError)
        assertTrue(server.requests.contains("/stale") && server.requests.contains("/fresh"))
        assertIs<SourceHealth.Healthy>(health.health(source.descriptor.id))
    }

    @Test
    fun `a stream refused again isn't a sign-in problem and doesn't bench the source`() {
        val source = ExpiringSource(freshAfterForget = false)
        val engine = engineFor(source)
        val songs = listOf(source.song("refused0001"), source.song("refused0002"))
        catalog.remember(songs)
        runBlocking { assertTrue(engine.playContext(songs.map { it.id }, 0, "Test", shuffle = false)) }

        TestPlayerRunHelper.run(engine.player).untilPlayerError()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        TestPlayerRunHelper.run(engine.player).untilPlayerError()

        assertEquals(1, source.forgotten.get(), "asked afresh once, not in a loop")
        val error = engine.lastError
        assertIs<PodiumError.NotPlayable>(error, "not AuthRequired: nobody needs to sign in")
        assertTrue(health.canAttempt(source.descriptor.id), "one song's refused stream leaves the source usable")
    }

    /** Serves [audio] at /fresh and refuses everything else with 403, one connection at a time. */
    private class TinyServer(private val audio: File) {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort
        val requests: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

        init {
            thread(isDaemon = true, name = "tiny-server") {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    client.use { c ->
                        val reader = c.getInputStream().bufferedReader()
                        val path = reader.readLine()?.split(' ')?.getOrNull(1) ?: return@use
                        while (reader.readLine()?.isNotEmpty() == true) Unit
                        requests += path
                        val out = c.getOutputStream()
                        if (path == "/fresh") {
                            val bytes = audio.readBytes()
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            out.write(bytes)
                        } else {
                            out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        }
                        out.flush()
                    }
                }
            }
        }

        fun close() = runCatching { socket.close() }
    }
}
