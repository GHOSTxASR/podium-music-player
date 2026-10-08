package app.podium.sources.youtubemusic

import app.podium.core.model.PlaybackRoute
import app.podium.core.model.SourceId
import app.podium.core.model.SourceRef
import app.podium.core.model.Track
import app.podium.core.model.TrackId
import app.podium.sources.api.FacetResolution
import app.podium.sources.api.MissReason
import app.podium.sources.api.PlaybackTarget
import app.podium.sources.api.QualityRequest
import app.podium.sources.api.PlayableMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YouTubeMusicStreamResolverTest {

    private val downloader = YouTubeMusicDownloader({ "TestBrowser/1.0" })
    private val resolver = YouTubeMusicStreamResolver(
        sourceId = YouTubeMusicSource.SOURCE_ID,
        downloader = downloader,
        region = { "US" }
    )

    @Test
    fun `extractExpiryMillis parses valid expire parameter`() {
        val url = "https://rr---sn.googlevideo.com/videoplayback?expire=1712345678&sparams=expire"
        val expiry = resolver.extractExpiryMillis(url)
        assertEquals(1712345678000L, expiry)
    }

    @Test
    fun `extractExpiryMillis returns null for url without expire parameter`() {
        val url = "https://rr---sn.googlevideo.com/videoplayback?sparams=id"
        assertNull(resolver.extractExpiryMillis(url))
        assertNull(resolver.extractExpiryMillis("https://rr---sn.googlevideo.com/videoplayback"))
    }

    @Test
    fun `resolve returns Miss NO_SOURCE when track has foreign sourceId`() = runTest {
        val foreignTrack = Track(
            id = TrackId.of(SourceId("local"), "local123"),
            title = "Local Song",
            artistDisplay = "Local Artist",
            artists = emptyList(),
            album = null,
            durationMs = 180000L,
            source = SourceRef(SourceId("local"), "local123"),
            routes = setOf(PlaybackRoute.DIRECT)
        )

        val result = resolver.resolve(foreignTrack, QualityRequest.Maximum)
        val miss = assertIs<FacetResolution.Miss>(result)
        assertEquals(MissReason.NO_SOURCE, miss.reason)
    }

    @Test
    fun `resolve returns Miss NOT_FOUND when videoId is blank`() = runTest {
        val blankTrack = Track(
            id = TrackId.of(YouTubeMusicSource.SOURCE_ID, "   "),
            title = "Blank Song",
            artistDisplay = "Artist",
            artists = emptyList(),
            album = null,
            durationMs = 180000L,
            source = SourceRef(YouTubeMusicSource.SOURCE_ID, "   "),
            routes = setOf(PlaybackRoute.DIRECT)
        )

        val result = resolver.resolve(blankTrack, QualityRequest.Maximum)
        val miss = assertIs<FacetResolution.Miss>(result)
        assertEquals(MissReason.NOT_FOUND, miss.reason)
    }

    private fun song(videoId: String) = Track(
        id = TrackId.of(YouTubeMusicSource.SOURCE_ID, videoId),
        title = "Song $videoId",
        artistDisplay = "Artist",
        artists = emptyList(),
        album = null,
        durationMs = 200_000L,
        source = SourceRef(YouTubeMusicSource.SOURCE_ID, videoId),
        routes = setOf(PlaybackRoute.DIRECT),
    )

    /** Extractions counted and held until [release], without the network. */
    private class CountingResolver(private val clock: () -> Long = { 1_000_000L }) : YouTubeMusicStreamResolver(
        YouTubeMusicSource.SOURCE_ID, YouTubeMusicDownloader({ "TestBrowser/1.0" }), now = clock,
    ) {
        val extractions = AtomicInteger()
        val release = CountDownLatch(1)

        override fun extract(track: Track, videoId: String, quality: QualityRequest): FacetResolution {
            val n = extractions.incrementAndGet()
            release.await(5, TimeUnit.SECONDS)
            val media = PlayableMedia(
                uri = "https://rr1.googlevideo.com/videoplayback?expire=${clock() / 1000 + 21_600}&n=$n",
                sourceId = YouTubeMusicSource.SOURCE_ID,
                cacheKey = "${track.id.value}|251",
                expiresAtMillis = clock() + 21_600_000L,
            )
            return FacetResolution.Resolved(PlaybackTarget.DirectStream(track.id, media))
        }
    }

    private fun FacetResolution.uri() = ((this as FacetResolution.Resolved).target as PlaybackTarget.DirectStream).media.uri

    @Test
    fun `bitrates are reported in kbps whichever figure the extractor has`() {
        assertEquals(160, YouTubeMusicStreamResolver.kbpsOf(averageKbps = 160, bitsPerSecond = 0))
        assertEquals(129, YouTubeMusicStreamResolver.kbpsOf(averageKbps = 0, bitsPerSecond = 129_472))
        assertNull(YouTubeMusicStreamResolver.kbpsOf(averageKbps = 0, bitsPerSecond = 0))
    }

    @Test
    fun `callers asking for the same song share one extraction`() = runBlocking {
        val resolver = CountingResolver()
        val a = async(Dispatchers.Default) { resolver.resolve(song("shared00001"), QualityRequest.Maximum) }
        val b = async(Dispatchers.Default) { resolver.resolve(song("shared00001"), QualityRequest.Maximum) }
        delay(200)
        resolver.release.countDown()
        assertEquals(a.await().uri(), b.await().uri())
        assertEquals(1, resolver.extractions.get())
    }

    @Test
    fun `a caller that stops waiting doesn't throw the extraction away`() = runBlocking {
        val resolver = CountingResolver()
        // The player gave up (a timeout)...
        assertNull(withTimeoutOrNull(100) { resolver.resolve(song("slow0000001"), QualityRequest.Maximum) })
        resolver.release.countDown()
        // ...and asking again gets the extraction that carried on, not a second one.
        val again = resolver.resolve(song("slow0000001"), QualityRequest.Maximum)
        assertTrue(again.uri().endsWith("n=1"))
        assertEquals(1, resolver.extractions.get())
    }

    @Test
    fun `a refused stream is forgotten, so the next request extracts afresh`() = runBlocking {
        val resolver = CountingResolver().also { it.release.countDown() }
        val first = resolver.resolve(song("refused0001"), QualityRequest.Maximum)
        assertEquals(first.uri(), resolver.resolve(song("refused0001"), QualityRequest.Maximum).uri(), "kept for a retry")
        resolver.forget(song("refused0001"))
        val fresh = resolver.resolve(song("refused0001"), QualityRequest.Maximum)
        assertTrue(fresh.uri().endsWith("n=2"))
        assertEquals(2, resolver.extractions.get())
    }

    @Test
    fun `a kept result is dropped once it's old`() = runBlocking {
        var now = 1_000_000L
        val resolver = CountingResolver { now }.also { it.release.countDown() }
        resolver.resolve(song("aging000001"), QualityRequest.Maximum)
        now += YouTubeMusicStreamResolver.RECENT_MILLIS + 1
        resolver.resolve(song("aging000001"), QualityRequest.Maximum)
        assertEquals(2, resolver.extractions.get())
    }

    /** Reaches YouTube for real: run with PODIUM_LIVE_NETWORK=1 (never in an offline build). */
    @Test
    fun `resolve fetches playable stream for public track`() = runTest {
        Assume.assumeTrue("needs the network", System.getenv("PODIUM_LIVE_NETWORK") == "1")
        val track = Track(
            id = TrackId.of(YouTubeMusicSource.SOURCE_ID, "kJQP7kiw5Fk"),
            title = "Despacito",
            artistDisplay = "Luis Fonsi",
            artists = emptyList(),
            album = null,
            durationMs = 240000L,
            source = SourceRef(YouTubeMusicSource.SOURCE_ID, "kJQP7kiw5Fk"),
            routes = setOf(PlaybackRoute.DIRECT)
        )

        val result = resolver.resolve(track, QualityRequest.Maximum)
        val resolved = assertIs<FacetResolution.Resolved>(result)
        val directStream = assertIs<PlaybackTarget.DirectStream>(resolved.target)
        assertTrue(directStream.media.uri.startsWith("https://"))
        assertTrue(directStream.media.uri.contains("googlevideo.com"))
        assertTrue(directStream.media.mimeType?.startsWith("audio/") == true)
        assertTrue((directStream.media.claimedQuality?.bitrateKbps ?: 0) > 0)
        assertNotNull(directStream.media.expiresAtMillis)
        assertTrue(directStream.media.expiresAtMillis!! > System.currentTimeMillis())
    }
}
