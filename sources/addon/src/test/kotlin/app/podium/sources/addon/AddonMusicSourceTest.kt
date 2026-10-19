package app.podium.sources.addon

import app.podium.core.common.ManualClock
import app.podium.core.common.Outcome
import app.podium.core.model.Codec
import app.podium.core.model.Explicitness
import app.podium.core.model.SourceId
import app.podium.core.model.Track
import app.podium.sources.api.FacetResolution
import app.podium.sources.api.HealthOutcome
import app.podium.sources.api.MissReason
import app.podium.sources.api.MusicEnvironment
import app.podium.sources.api.MusicSource
import app.podium.sources.api.PlaybackTarget
import app.podium.sources.api.Purpose
import app.podium.sources.api.QualityRequest
import app.podium.sources.api.ResolutionPath
import app.podium.sources.api.SearchKind
import app.podium.sources.api.SearchQuery
import app.podium.sources.api.SourceHealthMonitor
import app.podium.sources.api.SourceRegistry
import app.podium.sources.api.matching.TrackMatcher
import app.podium.sources.api.resolve.InMemoryEquivalenceStore
import app.podium.sources.api.resolve.ResolveOutcome
import app.podium.sources.api.resolve.ResolveRequest
import app.podium.sources.api.resolve.StreamProviders
import app.podium.sources.api.resolve.StreamResolver
import app.podium.sources.testing.FakeOnlineMusicSource
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AddonMusicSourceTest {
    private val now = 5_000_000L
    private val manifest = AddonProtocol.manifest(Fixtures.MANIFEST.toByteArray())!!
    private val id = SourceId("custom-test")

    /** Scripted answers by path prefix; every requested URL is recorded. */
    private class Script : AddonTransport {
        val requested = mutableListOf<String>()
        val answers = linkedMapOf<String, () -> HttpResponse>()
        fun on(pathPrefix: String, code: Int = 200, body: String = "", retryAfter: Long? = null) {
            answers[pathPrefix] = { HttpResponse(code, body.toByteArray(), retryAfter) }
        }
        fun fail(pathPrefix: String) {
            answers[pathPrefix] = { throw IOException("SocketTimeoutException") }
        }
        override suspend fun get(url: String): HttpResponse {
            requested += url
            val path = url.removePrefix(Fixtures.BASE)
            return answers.entries.firstOrNull { path.startsWith(it.key) }?.value?.invoke() ?: HttpResponse(404, ByteArray(0))
        }
    }

    private val script = Script().apply {
        on("/search", body = Fixtures.SEARCH)
        on("/stream/", body = Fixtures.STREAM_DASH)
    }

    private fun source(settings: Map<String, String> = emptyMap()) =
        AddonMusicSource(AddonConfig(id, Fixtures.BASE, manifest, settings), { now }, script)

    private suspend fun AddonMusicSource.firstTrack(): Track =
        (catalog!!.search(SearchQuery("one more time")) as Outcome.Success).value.tracks.first()

    @Test
    fun `an online source the listener added, not a catalogue`() {
        val s = source()
        assertEquals(MusicEnvironment.ONLINE, s.descriptor.environment)
        assertEquals("Lossless test", s.descriptor.displayName)
        assertTrue(s.capabilities.value.isUsable(app.podium.sources.api.Capability.SEARCH))
        assertTrue(s.capabilities.value.isUsable(app.podium.sources.api.Capability.DIRECT_STREAM))
    }

    @Test
    fun `search sends the query and the listener's settings, and maps songs to this source`() = runTest {
        val tracks = (source(mapOf("quality" to "high")).catalog!!.search(SearchQuery("one more time")) as Outcome.Success).value.tracks
        assertEquals("${Fixtures.BASE}/search?q=one%20more%20time&quality=high", script.requested.single())
        val t = tracks.first()
        assertEquals("custom-test|lt_001", t.id.value)
        assertEquals("One More Time", t.title)
        assertEquals("Daft Punk", t.artistDisplay)
        assertEquals("Discovery", t.album?.title)
        assertEquals(320_000L, t.durationMs)
        assertEquals(Explicitness.UNKNOWN, t.explicitness, "the addon doesn't say")
        assertNull(t.artwork, "custom sources never show their own artwork")
    }

    @Test
    fun `search keeps to songs and the limit`() = runTest {
        val s = source()
        assertEquals(1, (s.catalog!!.search(SearchQuery("x", limit = 1)) as Outcome.Success).value.tracks.size)
        val albumsOnly = s.catalog!!.search(SearchQuery("x", kinds = setOf(SearchKind.ALBUMS)))
        assertEquals(0, (albumsOnly as Outcome.Success).value.tracks.size)
        assertEquals(1, script.requested.size, "nothing asked for a search without songs")
    }

    @Test
    fun `resolve gives the player a DASH stream with the addon's claim and an expiry`() = runTest {
        val s = source()
        val track = s.firstTrack()
        val r = assertIs<FacetResolution.Resolved>(s.playback!!.resolve(track, QualityRequest.Maximum, Purpose.PLAYBACK))
        assertEquals("${Fixtures.BASE}/stream/lt_001?quality=lossless", script.requested.last())
        val media = assertIs<PlaybackTarget.DirectStream>(r.target).media
        assertEquals("https://cdn.example.com/manifest/abc.mpd", media.uri)
        assertEquals("application/dash+xml", media.mimeType)
        assertEquals(24, media.claimedQuality?.bitDepth)
        assertEquals(44_100, media.claimedQuality?.sampleRateHz)
        assertNull(media.claimedQuality?.codec, "\"lossless\" alone doesn't name a codec")
        assertEquals(now + AddonMusicSource.STREAM_TTL_MS, media.expiresAtMillis)
        assertEquals(id, media.sourceId)
        assertEquals("custom-test|lt_001|quality=lossless", media.cacheKey)
    }

    @Test
    fun `ids with reserved characters travel safely in the path`() = runTest {
        script.on("/search", body = """{"tracks":[{"id":"a/b c?d","title":"T","artist":"A","duration":100}]}""")
        val s = source()
        s.playback!!.resolve(s.firstTrack(), QualityRequest.Maximum, Purpose.PLAYBACK)
        assertEquals("${Fixtures.BASE}/stream/a%2Fb%20c%3Fd?quality=lossless", script.requested.last())
    }

    @Test
    fun `a relative stream address is made whole, a progressive file sniffed by extension`() = runTest {
        script.on("/stream/", body = """{"url":"/files/lt_001.flac","quality":"FLAC 16-bit 44.1kHz"}""")
        val s = source()
        val media = (assertIs<FacetResolution.Resolved>(s.playback!!.resolve(s.firstTrack(), QualityRequest.Maximum, Purpose.PLAYBACK)).target as PlaybackTarget.DirectStream).media
        assertEquals("${Fixtures.BASE}/files/lt_001.flac", media.uri)
        assertEquals("audio/flac", media.mimeType)
        assertEquals(Codec.FLAC, media.claimedQuality?.codec)
        assertEquals(16, media.claimedQuality?.bitDepth)
    }

    @Test
    fun `protected streams are never played`() = runTest {
        script.on("/stream/", body = """{"url":"https://cdn.example.com/a.mpd","format":"dash","encrypted":true}""")
        val s = source()
        assertEquals(FacetResolution.Miss(MissReason.NOT_STREAMABLE), s.playback!!.resolve(s.firstTrack(), QualityRequest.Maximum, Purpose.PLAYBACK))
    }

    @Test
    fun `an unencrypted stream off the listener's network is refused`() = runTest {
        script.on("/stream/", body = """{"url":"http://cdn.example.com/a.flac"}""")
        val s = source()
        val r = assertIs<FacetResolution.Failed>(s.playback!!.resolve(s.firstTrack(), QualityRequest.Maximum, Purpose.PLAYBACK))
        assertEquals(HealthOutcome.INVALID_MEDIA, r.outcome)
    }

    @Test
    fun `HTTP answers become misses and failures, never with the address`() = runTest {
        val s = source()
        val track = s.firstTrack()
        suspend fun resolve() = s.playback!!.resolve(track, QualityRequest.Maximum, Purpose.PLAYBACK)

        script.on("/stream/", code = 404)
        assertEquals(FacetResolution.Miss(MissReason.NOT_FOUND), resolve())
        script.on("/stream/", code = 429, retryAfter = 30)
        assertEquals(FacetResolution.Failed(HealthOutcome.RATE_LIMIT, "rate limited", 30_000), resolve())
        script.on("/stream/", code = 503)
        assertEquals(FacetResolution.Failed(HealthOutcome.SERVER_ERROR, "HTTP 503"), resolve())
        script.on("/stream/", code = 403)
        assertEquals(HealthOutcome.SERVER_ERROR, assertIs<FacetResolution.Failed>(resolve()).outcome)
        script.on("/stream/", body = "<html>oops</html>")
        assertEquals(HealthOutcome.UNKNOWN, assertIs<FacetResolution.Failed>(resolve()).outcome)
        script.fail("/stream/")
        val network = assertIs<FacetResolution.Failed>(resolve())
        assertEquals(HealthOutcome.NETWORK_FAILURE, network.outcome)
        assertTrue(network.detail?.contains("example") != true)
    }

    @Test
    fun `only its own songs, and never for saving`() = runTest {
        val s = source()
        val foreign = FakeOnlineMusicSource("ytm").track("One More Time", artist = "Daft Punk")
        assertEquals(FacetResolution.Miss(MissReason.NOT_FOUND), s.playback!!.resolve(foreign, QualityRequest.Maximum, Purpose.PLAYBACK))
        assertEquals(FacetResolution.Miss(MissReason.NOT_PERMITTED), s.playback!!.resolve(s.firstTrack(), QualityRequest.Maximum, Purpose.DOWNLOAD))
    }

    @Test
    fun `check reads the manifest again`() = runTest {
        script.on("/manifest.json", body = Fixtures.MANIFEST)
        assertEquals("Lossless test", (source().check() as Outcome.Success).value.name)
        script.on("/manifest.json", code = 500)
        assertIs<Outcome.Failure>(source().check())
    }

    // --- With the resolver: what the listener actually gets ---------------------------------------

    private val clock = ManualClock(now)
    private val health = SourceHealthMonitor(clock)
    private val registry = SourceRegistry(health)
    private val catalogue = FakeOnlineMusicSource("ytm").also { registry.register(it) }

    private fun resolver(vararg custom: MusicSource) = StreamResolver(
        registry, health, TrackMatcher(), clock, equivalence = InMemoryEquivalenceStore(),
        preferred = object : StreamProviders {
            override fun ordered(environment: MusicEnvironment) = custom.filter { it.descriptor.environment == environment }
            override fun get(id: SourceId) = custom.firstOrNull { it.descriptor.id == id }
        },
    )

    private fun ytmSong(title: String = "One More Time", seconds: Double = 320.0, explicit: Boolean = false): Track {
        val t = catalogue.track(title, artist = "Daft Punk", durationSec = seconds)
        val withAlbum = t.copy(
            album = app.podium.core.model.AlbumRef("Discovery"),
            explicitness = if (explicit) Explicitness.EXPLICIT else Explicitness.UNKNOWN,
        )
        catalogue.put(withAlbum)
        return withAlbum
    }

    @Test
    fun `a catalogue song plays from the addon's exact copy, never its radio edit`() = runTest {
        val outcome = assertIs<ResolveOutcome.Resolved>(resolver(source()).resolve(ResolveRequest(ytmSong())))
        assertEquals(ResolutionPath.PREFERRED_SOURCE, outcome.selection.path)
        assertEquals(id, outcome.selection.servedBy)
        assertEquals("custom-test|lt_001", outcome.selection.servedTrack.id.value)
        assertEquals(0, catalogue.resolveCalls)
    }

    @Test
    fun `the radio edit plays from the addon only when that is the song`() = runTest {
        val edit = catalogue.track("One More Time (Radio Edit)", artist = "Daft Punk", durationSec = 235.0)
            .copy(album = app.podium.core.model.AlbumRef("Musique, Vol. 1")).also(catalogue::put)
        val outcome = assertIs<ResolveOutcome.Resolved>(resolver(source()).resolve(ResolveRequest(edit)))
        assertEquals("custom-test|lt_002", outcome.selection.servedTrack.id.value)
    }

    @Test
    fun `an explicit song stays on its own source when the addon doesn't say which edition it has`() = runTest {
        val outcome = assertIs<ResolveOutcome.Resolved>(resolver(source()).resolve(ResolveRequest(ytmSong(explicit = true))))
        assertEquals(ResolutionPath.OWN_SOURCE, outcome.selection.path)
        assertEquals(catalogue.sourceId, outcome.selection.servedBy)
    }

    @Test
    fun `a broken addon falls back to the song's own source`() = runTest {
        script.on("/search", code = 500)
        val outcome = assertIs<ResolveOutcome.Resolved>(resolver(source()).resolve(ResolveRequest(ytmSong())))
        assertEquals(catalogue.sourceId, outcome.selection.servedBy)
        script.on("/search", body = Fixtures.SEARCH)
        script.on("/stream/", code = 404)
        val missing = assertIs<ResolveOutcome.Resolved>(resolver(source()).resolve(ResolveRequest(ytmSong())))
        assertEquals(catalogue.sourceId, missing.selection.servedBy)
    }
}
