package app.podium.sources.youtubemusic

import app.podium.core.common.Outcome
import app.podium.core.common.PodiumError
import app.podium.core.model.AlbumId
import app.podium.core.model.ArtistId
import app.podium.core.model.ArtworkRef
import app.podium.core.model.Explicitness
import app.podium.core.model.MediaKind
import app.podium.core.model.PlaybackRoute
import app.podium.core.model.PlaylistId
import app.podium.core.model.TrackId
import app.podium.sources.api.AuthState
import app.podium.sources.api.Capability
import app.podium.sources.api.CapabilityState
import app.podium.sources.api.CapabilityStatus
import app.podium.sources.api.FacetResolution
import app.podium.sources.api.InMemoryCredentialStore
import app.podium.sources.api.MissReason
import app.podium.sources.api.PlaybackTarget
import app.podium.sources.api.Purpose
import app.podium.sources.api.QualityRequest
import app.podium.sources.api.QueueOwnership
import app.podium.sources.api.RemoteContext
import app.podium.sources.api.SearchKind
import app.podium.sources.api.SearchQuery
import app.podium.sources.api.SignInResult
import app.podium.sources.api.WebSignIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun ok(body: String) = HttpResponse(200, body.toByteArray())

class YouTubeMusicSourceTest {

    /** Answers by endpoint and request content; records what was sent. */
    private class FakeService : HttpTransport {
        data class Sent(val method: String, val url: String, val headers: Map<String, String>, val body: String)

        val sent = mutableListOf<Sent>()
        var answer: (Sent) -> HttpResponse = { HttpResponse(404, ByteArray(0)) }

        override suspend fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
            val s = Sent(method, url, headers, body?.toString(Charsets.UTF_8).orEmpty())
            sent += s
            if (method == "GET" && url == "https://music.youtube.com/") return ok(Fixtures.PAGE_HTML)
            return answer(s)
        }

        fun api(endpoint: String) = sent.filter { it.url.contains("/youtubei/v1/$endpoint?") }
    }

    private val service = FakeService()
    private val credentials = InMemoryCredentialStore()
    private val app = MutableStateFlow(CapabilityState.Available)
    private val source by lazy { YouTubeMusicSource(credentials, service, { "TestBrowser/1.0" }, { "GB" }, app, { 1_700_000_000_000L }) }

    private val session = "SID=abc; HSID=def; SAPISID=sapi-secret/123; __Secure-3PAPISID=sapi-secret/123"

    @Test
    fun `search returns songs from the songs-only search and the other kinds from the main answer`() = runTest {
        service.answer = { s ->
            when {
                s.url.contains("/search?") && "SONGS-PARAMS" in s.body -> ok(Fixtures.searchSongs)
                s.url.contains("/search?") -> ok(Fixtures.searchAll)
                else -> HttpResponse(404, ByteArray(0))
            }
        }
        val results = (source.catalog.search(SearchQuery("night owls")) as Outcome.Success).value
        assertEquals(listOf("ytmusic|aaaaaaaaaa1", "ytmusic|aaaaaaaaaa2"), results.tracks.map { it.id.value })
        assertTrue(results.tracks.all { it.kind == MediaKind.SONG && it.routes == setOf(PlaybackRoute.REMOTE) })
        assertEquals(listOf("ytmusic|vvvvvvvvvv1"), results.videos.map { it.id.value })
        assertEquals(MediaKind.VIDEO, results.videos.single().kind)
        assertEquals(listOf("ytmusic|MPREb_dawn000"), results.albums.map { it.id.value })
        assertEquals(listOf("ytmusic|UCnightowls00000000000"), results.artists.map { it.id.value })
        assertEquals(listOf("ytmusic|PLowls0001"), results.playlists.map { it.id.value })
        // Episodes never show up as songs or videos.
        assertTrue((results.tracks + results.videos).none { it.id.value.endsWith("eeeeeeeeee1") })
        // The request carried the page's client version, the region, and no session.
        val first = service.api("search").first()
        assertTrue("1.20261005.01.00" in first.body)
        assertTrue("\"gl\":\"GB\"" in first.body)
        assertNull(first.headers["Authorization"])
        assertNull(first.headers["Cookie"])
        assertEquals("TestBrowser/1.0", first.headers["User-Agent"])
    }

    @Test
    fun `more songs continue the songs-only search from where it stopped`() = runTest {
        service.answer = { s ->
            when {
                "SONGS-TOKEN-1" in s.url -> ok(Fixtures.searchSongsMore)
                s.url.contains("/search?") && "SONGS-PARAMS" in s.body -> ok(Fixtures.searchSongs)
                s.url.contains("/search?") -> ok(Fixtures.searchAll)
                else -> HttpResponse(404, ByteArray(0))
            }
        }
        source.catalog.search(SearchQuery("night owls", limit = 2))
        val more = (source.catalog.search(SearchQuery("night owls", limit = 2, offset = 2, kinds = setOf(SearchKind.TRACKS))) as Outcome.Success).value
        assertEquals(listOf("ytmusic|aaaaaaaaaa3"), more.tracks.map { it.id.value })
        // Without a filter from the answer, the encoded songs filter is used.
        assertEquals("EgWKAQIIAQ%3D%3D", SearchFilter.SONGS.encoded())
    }

    @Test
    fun `album songs inherit the album's artist, art and year`() = runTest {
        service.answer = { s -> if ("MPREb_dawn000" in s.body) ok(Fixtures.album) else HttpResponse(404, ByteArray(0)) }
        val album = (source.catalog.album(AlbumId("ytmusic|MPREb_dawn000")) as Outcome.Success).value
        assertEquals("Dawn", album.summary.title)
        assertEquals("Night Owls", album.summary.artistDisplay)
        val first = album.tracks.first()
        assertEquals("Night Owls", first.artistDisplay)
        assertEquals(ArtistId("ytmusic|UCnightowls00000000000"), first.artists.single().id)
        assertEquals("Dawn", first.album?.title)
        assertNotNull(first.artwork)
        assertEquals(2021, first.releaseDate?.year)
        // An unplayable row says so.
        assertIs<app.podium.core.model.Availability.Unavailable>(album.tracks[1].availability)
        // The album's playlist is now known, so the album can be handed over as a context.
        val handoff = assertNotNull(source.playback!!.remoteContext(RemoteContext.Collection(PlaylistId("ytmusic|MPREb_dawn000")), first))
        assertEquals("https://music.youtube.com/watch?v=aaaaaaaaaa1&list=OLAK5uy_dawn", handoff.providerItemRef)
    }

    @Test
    fun `a playlist loads its continuation`() = runTest {
        service.answer = { s ->
            when {
                "PL-TOKEN" in s.body -> ok(Fixtures.playlistMore)
                "VLPLowls0001" in s.body -> ok(Fixtures.playlist)
                else -> HttpResponse(404, ByteArray(0))
            }
        }
        val playlist = (source.catalog.playlist(PlaylistId("ytmusic|PLowls0001")) as Outcome.Success).value
        assertEquals(3, playlist.tracks.size)
        assertEquals("Owl songs", playlist.summary.title)
        assertFalse(playlist.summary.isAlbum)
    }

    @Test
    fun `home shelves become shelves of songs, albums and artists`() = runTest {
        service.answer = { s ->
            when {
                "HOME-TOKEN" in s.url -> ok(Fixtures.homeMore)
                "FEmusic_home" in s.body -> ok(Fixtures.home)
                else -> HttpResponse(404, ByteArray(0))
            }
        }
        val shelves = (source.discovery!!.shelves() as Outcome.Success).value
        assertEquals(listOf("Quick picks", "Albums for you", "Recommended artists"), shelves.map { it.title })
        assertEquals(1, shelves[0].tracks.size)
        assertTrue(shelves[1].playlists.single().isAlbum)
        assertEquals("Night Owls", shelves[2].artists.single().name)
        val page = (source.discovery!!.shelf(shelves[0].id, 0, 10) as Outcome.Success).value
        assertEquals(1, page.tracks.size)
        // Genres aren't offered: no Explore.
        assertEquals(emptyList(), (source.discovery!!.genres() as Outcome.Success).value)
    }

    @Test
    fun `radio follows the catalogue's own radio and leaves out the seed and exclusions`() = runTest {
        service.answer = { s ->
            when {
                "RADIO-TOKEN" in s.url -> ok(Fixtures.radioMore)
                "RDAMVMaaaaaaaaaa1" in s.body -> ok(Fixtures.radio)
                else -> HttpResponse(404, ByteArray(0))
            }
        }
        val seed = (source.catalog.search(SearchQuery("x")) as? Outcome.Success)?.value?.tracks?.firstOrNull()
            ?: YouTubeMusicMapper(YouTubeMusicSource.SOURCE_ID).track(YtmSong("aaaaaaaaaa1", "First Light", emptyList()))
        val related = (source.recommendations!!.related(listOf(seed), 5, setOf(TrackId("ytmusic|cccccccccc1"))) as Outcome.Success).value
        assertEquals(listOf("ytmusic|bbbbbbbbbb1"), related.map { it.id.value })
    }

    @Test
    fun `every song plays in the official app, never as a stream`() = runTest {
        val track = YouTubeMusicMapper(YouTubeMusicSource.SOURCE_ID).track(YtmSong("aaaaaaaaaa1", "First Light", emptyList()))
        val resolved = source.playback!!.resolve(track, QualityRequest.Maximum, Purpose.PLAYBACK)
        val target = assertIs<PlaybackTarget.RemoteProvider>(assertIs<FacetResolution.Resolved>(resolved).target)
        assertEquals(YouTubeMusicSource.PROVIDER_APP_PACKAGE, target.controllerId)
        assertEquals("https://music.youtube.com/watch?v=aaaaaaaaaa1", target.providerItemRef)
        assertEquals(QueueOwnership.PROVIDER, target.policy.queueOwnership)
        assertEquals(FacetResolution.Miss(MissReason.NOT_PERMITTED), source.playback!!.resolve(track, QualityRequest.Maximum, Purpose.DOWNLOAD))
        assertFalse(source.capabilities.value.isUsable(Capability.DIRECT_STREAM))
        assertEquals("aaaaaaaaaa1", source.playback!!.remoteTrackKey("aaaaaaaaaa1"))
        assertNull(source.playback!!.remoteTrackKey("not a video id"))
        // Without the official app, nothing resolves.
        app.value = CapabilityState(CapabilityStatus.REQUIRES_PROVIDER_APP, "Install YouTube Music")
        assertEquals(FacetResolution.Miss(MissReason.UNSUPPORTED_ROUTE), source.playback!!.resolve(track, QualityRequest.Maximum, Purpose.PLAYBACK))
        // No request ever went to a player endpoint.
        assertTrue(service.sent.none { "/player" in it.url })
    }

    @Test
    fun `signing in checks the session, keeps it sealed in the store and signs every request`() = runTest {
        service.answer = { s ->
            when {
                s.url.contains("account/account_menu") -> ok(Fixtures.accountMenu)
                s.url.contains("/browse?") && "VLLM" in s.body -> ok(Fixtures.playlist)
                else -> HttpResponse(404, ByteArray(0))
            }
        }
        assertEquals(AuthState.SignedOut, source.auth!!.state.value)
        assertEquals(CapabilityStatus.REQUIRES_SIGN_IN, source.capabilities.value[Capability.ACCOUNT_LIBRARY].status)
        val result = source.auth!!.signIn(mapOf(WebSignIn.SESSION_KEY to session))
        assertEquals(SignInResult.SignedIn, result)
        val state = assertIs<AuthState.SignedIn>(source.auth!!.state.value)
        assertEquals("Listener Name", state.accountName)
        assertTrue(state.accountKey.startsWith("ytm-"))
        assertFalse("Listener" in state.accountKey)
        assertEquals(session.split("; ").sorted(), credentials.read(YouTubeMusicSource.SOURCE_ID)!!["session"]!!.split("; ").sorted())
        assertTrue(source.capabilities.value.isUsable(Capability.ACCOUNT_LIBRARY))

        val liked = (source.accountLibrary!!.likedSongs(0, 10) as Outcome.Success).value
        assertEquals(2, liked.size)
        val signed = service.api("browse").last()
        assertEquals("SAPISIDHASH 1700000000_6a8d14d447d77ae944b9faea81d07b445bbc692a", signed.headers["Authorization"])
        assertTrue(signed.headers["Cookie"]!!.contains("SAPISID=sapi-secret/123"))
    }

    @Test
    fun `a session the service no longer accepts expires and is forgotten`() = runTest {
        credentials.write(YouTubeMusicSource.SOURCE_ID, mapOf("session" to session, "account-name" to "Listener Name"))
        service.answer = { ok(Fixtures.signedOutAnswer) }
        assertIs<AuthState.SignedIn>(source.auth!!.state.value)
        val r = source.accountLibrary!!.likedSongs(0, 10)
        assertIs<PodiumError.AuthExpired>((r as Outcome.Failure).error)
        assertEquals(AuthState.Expired, source.auth!!.state.value)
        assertNull(credentials.read(YouTubeMusicSource.SOURCE_ID))
        assertEquals(CapabilityStatus.REQUIRES_SIGN_IN, source.capabilities.value[Capability.LIKES].status)
    }

    @Test
    fun `a refused sign-in keeps nothing`() = runTest {
        service.answer = { HttpResponse(401, ByteArray(0)) }
        val result = source.auth!!.signIn(mapOf(WebSignIn.SESSION_KEY to session))
        assertIs<SignInResult.Refused>(result)
        assertNull(credentials.read(YouTubeMusicSource.SOURCE_ID))
        assertEquals(AuthState.SignedOut, source.auth!!.state.value)
        // A session without its API secret is refused before anything is sent.
        val before = service.sent.size
        assertIs<SignInResult.Refused>(source.auth!!.signIn(mapOf(WebSignIn.SESSION_KEY to "SID=abc")))
        assertEquals(before, service.sent.size)
    }

    @Test
    fun `signing out forgets the session and the account's lists`() = runTest {
        credentials.write(YouTubeMusicSource.SOURCE_ID, mapOf("session" to session, "account-name" to "Listener Name"))
        source.auth!!.signOut()
        assertEquals(AuthState.SignedOut, source.auth!!.state.value)
        assertNull(credentials.read(YouTubeMusicSource.SOURCE_ID))
        assertIs<PodiumError.AuthRequired>((source.accountLibrary!!.likedSongs(0, 10) as Outcome.Failure).error)
    }

    @Test
    fun `library playlists leave out liked music, which has its own place`() = runTest {
        credentials.write(YouTubeMusicSource.SOURCE_ID, mapOf("session" to session, "account-name" to "Listener Name"))
        service.answer = { s -> if ("FEmusic_liked_playlists" in s.body) ok(Fixtures.libraryPlaylists) else HttpResponse(404, ByteArray(0)) }
        val playlists = (source.accountLibrary!!.playlists(0, 10) as Outcome.Success).value
        assertEquals(listOf("Road trip"), playlists.map { it.title })
    }

    @Test
    fun `history keeps the service's grouping`() = runTest {
        credentials.write(YouTubeMusicSource.SOURCE_ID, mapOf("session" to session, "account-name" to "Listener Name"))
        service.answer = { s -> if ("FEmusic_history" in s.body) ok(Fixtures.history) else HttpResponse(404, ByteArray(0)) }
        val history = (source.accountLibrary!!.history(0, 10) as Outcome.Success).value
        assertEquals(listOf("Today", "Yesterday"), history.map { it.period })
    }

    @Test
    fun `likes write to the account`() = runTest {
        credentials.write(YouTubeMusicSource.SOURCE_ID, mapOf("session" to session, "account-name" to "Listener Name"))
        service.answer = { ok("""{"responseContext":{}}""") }
        val track = YouTubeMusicMapper(YouTubeMusicSource.SOURCE_ID).track(YtmSong("aaaaaaaaaa1", "First Light", emptyList(), explicit = true))
        assertEquals(Explicitness.EXPLICIT, track.explicitness)
        assertIs<Outcome.Success<Unit>>(source.accountLibrary!!.setLiked(track, true))
        assertIs<Outcome.Success<Unit>>(source.accountLibrary!!.setLiked(track, false))
        assertEquals(1, service.api("like/like").size)
        assertEquals(1, service.api("like/removelike").size)
        assertTrue("\"videoId\":\"aaaaaaaaaa1\"" in service.api("like/like").single().body)
    }

    @Test
    fun `failures are classified, never thrown`() = runTest {
        service.answer = { HttpResponse(429, ByteArray(0), retryAfterSeconds = 30) }
        assertEquals(PodiumError.RateLimited(30_000), (source.catalog.search(SearchQuery("x")) as Outcome.Failure).error)
        service.answer = { HttpResponse(503, ByteArray(0)) }
        assertEquals(PodiumError.Server(503), (source.catalog.search(SearchQuery("x")) as Outcome.Failure).error)
        service.answer = { ok("not json") }
        assertEquals(PodiumError.Server(null), (source.catalog.search(SearchQuery("x")) as Outcome.Failure).error)
        service.answer = { throw IOException("SocketTimeoutException") }
        assertIs<PodiumError.Network>((source.catalog.search(SearchQuery("x")) as Outcome.Failure).error)
        service.answer = { throw UnknownHostException() }
        assertEquals(PodiumError.Offline, (source.catalog.search(SearchQuery("x")) as Outcome.Failure).error)
    }

    @Test
    fun `a stale client version is refreshed once from the page`() = runTest {
        var calls = 0
        service.answer = { calls++; if (calls == 1) HttpResponse(400, ByteArray(0)) else ok(Fixtures.searchNothing) }
        assertIs<Outcome.Success<*>>(source.catalog.search(SearchQuery("x", kinds = setOf(SearchKind.ARTISTS))))
        assertEquals(2, service.sent.count { it.method == "GET" && it.url == "https://music.youtube.com/" })
    }

    @Test
    fun `artwork is fetched at the requested size from the image host`() = runTest {
        service.answer = { s -> if (s.method == "POST") HttpResponse(404, ByteArray(0)) else ok("JPEGDATA") }
        val ref = ArtworkRef(YouTubeMusicSource.SOURCE_ID, "https://lh3.googleusercontent.com/abc=w120-h120-l90-rj")
        assertNotNull(source.artwork!!.load(ref, 300))
        assertEquals("https://lh3.googleusercontent.com/abc=w300-h300-l90-rj", service.sent.last().url)
        assertNull(service.sent.last().headers["Cookie"])
    }
}
