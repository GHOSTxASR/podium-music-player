package app.podium.sources.audius

import app.podium.core.common.Outcome
import app.podium.core.common.PodiumError
import app.podium.core.model.ArtistId
import app.podium.core.model.Availability
import app.podium.core.model.Codec
import app.podium.core.model.PlaylistId
import app.podium.core.model.SourceRef
import app.podium.core.model.TrackId
import app.podium.sources.api.FacetResolution
import app.podium.sources.api.MissReason
import app.podium.sources.api.MusicEnvironment
import app.podium.sources.api.PlaybackTarget
import app.podium.sources.api.Purpose
import app.podium.sources.api.QualityRequest
import app.podium.sources.api.SearchKind
import app.podium.sources.api.SearchQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Recorded responses from the live API (2026-10-03), keyed by endpoint path. */
private class FixtureTransport(private val overrides: Map<String, () -> HttpResponse> = emptyMap()) : HttpTransport {
    val requested = mutableListOf<String>()

    override fun get(url: String): HttpResponse {
        requested += url
        val path = url.substringAfter(AudiusApi.BASE_URL).substringBefore('?')
        overrides[path]?.let { return it() }
        val name = when {
            path == "/tracks/trending" -> "trending"
            path == "/tracks/trending/underground" -> "trending"
            path == "/tracks/recommended" -> "recommended"
            path == "/playlists/trending" -> "playlists_trending"
            path == "/genres/popular" -> "genres"
            path == "/search/full" -> "search"
            path.matches(Regex("/tracks/[^/]+")) -> "track"
            path.matches(Regex("/users/[^/]+")) -> "user"
            path.matches(Regex("/users/[^/]+/tracks")) -> "user_tracks"
            path.matches(Regex("/users/[^/]+/related")) -> "related"
            path.matches(Regex("/playlists/[^/]+/tracks")) -> "playlist_tracks"
            path.matches(Regex("/playlists/[^/]+")) -> "playlist"
            else -> return HttpResponse(404, "{}".toByteArray())
        }
        val body = javaClass.getResource("/fixtures/$name.json")!!.readBytes()
        return HttpResponse(200, body)
    }
}

class AudiusMusicSourceTest {

    private val transport = FixtureTransport()
    private val source = AudiusMusicSource(AudiusApi(transport), Dispatchers.Unconfined)

    @Test
    fun `it is an online source on Audius' official API`() {
        assertEquals(MusicEnvironment.ONLINE, source.descriptor.environment)
        assertTrue(source.capabilities.value.isUsable(app.podium.sources.api.Capability.SEARCH))
        assertTrue(!source.capabilities.value.isUsable(app.podium.sources.api.Capability.DOWNLOADS))
    }

    @Test
    fun `shelves map songs to Podium's model with source-qualified ids`() = runTest {
        val shelves = assertIs<Outcome.Success<List<app.podium.sources.api.Shelf>>>(source.discovery.shelves()).value
        assertEquals(listOf("Trending this week", "Underground", "Popular playlists", "Trending this month"), shelves.map { it.title })
        val track = shelves.first().tracks.first()
        assertEquals(TrackId("audius|xkQaGx"), track.id)
        assertTrue(track.title.isNotBlank())
        assertEquals(ArtistId("audius|O5lQz"), track.artists.single().id)
        assertEquals(417_000L, track.durationMs)
        assertEquals(Availability.Playable, track.availability)
        assertTrue(track.source.providerUri!!.startsWith("https://audius.co/"))
        assertTrue(track.artwork!!.key.startsWith("https://"))
        // Only a codec is claimed; Podium measures the rest and never calls it lossless.
        assertEquals(listOf(Codec.MP3), track.advertisedQualities.map { it.codec })
        assertTrue(track.advertisedQualities.none { it.codec?.isLossless == true })
        assertTrue(shelves[2].playlists.isNotEmpty())
    }

    @Test
    fun `a playable song resolves to Audius' stream endpoint`() = runTest {
        val track = assertIs<Outcome.Success<app.podium.core.model.Track>>(source.catalog.track(SourceRef(AudiusMapper.SOURCE, "xkQaGx"))).value
        val r = assertIs<FacetResolution.Resolved>(source.playback.resolve(track, QualityRequest.Maximum, Purpose.PLAYBACK))
        val media = assertIs<PlaybackTarget.DirectStream>(r.target).media
        assertEquals("https://api.audius.co/v1/tracks/xkQaGx/stream?app_name=Podium", media.uri)
        assertEquals(Codec.MP3, media.claimedQuality?.codec)
    }

    @Test
    fun `gated songs are unavailable and never resolved`() = runTest {
        val gated = AudiusMapper.track(TrackDto(id = "g1", title = "Members only", stream_conditions = kotlinx.serialization.json.buildJsonObject { put("usdc_purchase", kotlinx.serialization.json.JsonPrimitive(1)) }))
        assertIs<Availability.Unavailable>(gated.availability)
        assertEquals(FacetResolution.Miss(MissReason.NOT_PERMITTED), source.playback.resolve(gated, QualityRequest.Maximum, Purpose.PLAYBACK))
        val notStreamable = AudiusMapper.track(TrackDto(id = "g2", title = "Hidden", is_streamable = false))
        assertIs<Availability.Unavailable>(notStreamable.availability)
    }

    @Test
    fun `search returns songs, artists, albums and playlists, paged`() = runTest {
        val r = assertIs<Outcome.Success<app.podium.sources.api.SearchResults>>(source.catalog.search(SearchQuery("lofi", limit = 2, offset = 4))).value
        assertTrue(r.tracks.isNotEmpty())
        assertTrue(r.artists.isNotEmpty())
        assertTrue(transport.requested.last().contains("offset=4") && transport.requested.last().contains("limit=2"))
        val onlyArtists = assertIs<Outcome.Success<app.podium.sources.api.SearchResults>>(source.catalog.search(SearchQuery("lofi", kinds = setOf(SearchKind.ARTISTS)))).value
        assertTrue(onlyArtists.tracks.isEmpty() && onlyArtists.artists.isNotEmpty())
    }

    @Test
    fun `an artist page has their popular songs and playlists`() = runTest {
        val detail = assertIs<Outcome.Success<app.podium.sources.api.ArtistDetail>>(source.catalog.artist(ArtistId("audius|O5lQz"))).value
        assertTrue(detail.summary.name.isNotBlank())
        assertTrue(detail.tracks.isNotEmpty())
        assertTrue(transport.requested.any { it.contains("/users/O5lQz/tracks") && it.contains("sort=plays") })
    }

    @Test
    fun `a playlist comes with its songs`() = runTest {
        val detail = assertIs<Outcome.Success<app.podium.sources.api.PlaylistDetail>>(source.catalog.playlist(PlaylistId("audius|xPjKvK9"))).value
        assertEquals("Walk with me", detail.summary.title)
        assertTrue(detail.tracks.isNotEmpty())
    }

    @Test
    fun `recommendations leave out what's excluded and ask by the seeds' genre`() = runTest {
        val seed = assertIs<Outcome.Success<app.podium.core.model.Track>>(source.catalog.track(SourceRef(AudiusMapper.SOURCE, "xkQaGx"))).value
        val all = assertIs<Outcome.Success<List<app.podium.core.model.Track>>>(source.recommendations.related(listOf(seed), 3)).value
        val skip = all.first().id
        val r = assertIs<Outcome.Success<List<app.podium.core.model.Track>>>(source.recommendations.related(listOf(seed), 3, exclude = setOf(skip))).value
        assertTrue(r.none { it.id == skip })
        val call = transport.requested.last { it.contains("/tracks/recommended") }
        assertTrue(call.contains("exclusion_list=xkQaGx"), call)
        assertTrue(call.contains("genre="), call)
    }

    @Test
    fun `an artist's radio mixes their songs with related artists'`() = runTest {
        val radio = assertIs<Outcome.Success<List<app.podium.core.model.Track>>>(source.recommendations.artistRadio(ArtistId("audius|O5lQz"), 10)).value
        assertTrue(radio.isNotEmpty())
        assertEquals(radio.size, radio.map { it.id }.toSet().size, "no duplicates")
    }

    @Test
    fun `losing the network is reported as offline, and resolving stops`() = runTest {
        val offlineSource = AudiusMusicSource(AudiusApi(HttpTransport { throw UnknownHostException("api.audius.co") }), Dispatchers.Unconfined)
        assertEquals(Outcome.Failure(PodiumError.Offline), offlineSource.discovery.genres())
        source.simulateOffline = true
        assertEquals(Outcome.Failure(PodiumError.Offline), source.discovery.genres())
        val track = AudiusMapper.track(TrackDto(id = "x", title = "x"))
        assertEquals(FacetResolution.Miss(MissReason.SOURCE_UNAVAILABLE), source.playback.resolve(track, QualityRequest.Maximum, Purpose.PLAYBACK))
    }

    @Test
    fun `HTTP failures are classified`() = runTest {
        val limited = AudiusMusicSource(AudiusApi(HttpTransport { HttpResponse(429, ByteArray(0), mapOf("retry-after" to "3")) }), Dispatchers.Unconfined)
        assertEquals(Outcome.Failure(PodiumError.RateLimited(3_000)), limited.discovery.genres())
        val broken = AudiusMusicSource(AudiusApi(HttpTransport { HttpResponse(502, ByteArray(0)) }), Dispatchers.Unconfined)
        assertEquals(Outcome.Failure(PodiumError.Server(502)), broken.discovery.genres())
        val flaky = AudiusMusicSource(AudiusApi(HttpTransport { throw IOException("reset") }), Dispatchers.Unconfined)
        assertIs<Outcome.Failure>(flaky.discovery.genres())
    }

    @Test
    fun `artwork picks the size it needs`() {
        val url = "https://node/content/abc/480x480.jpg"
        assertEquals("https://node/content/abc/150x150.jpg", AudiusMapper.sizedArtwork(url, 96))
        assertEquals("https://node/content/abc/1000x1000.jpg", AudiusMapper.sizedArtwork(url, 900))
        assertNotNull(AudiusMapper.date("2026-09-26T23:00:27Z")).let { assertEquals(2026 to 9, it.year to it.month) }
    }
}
