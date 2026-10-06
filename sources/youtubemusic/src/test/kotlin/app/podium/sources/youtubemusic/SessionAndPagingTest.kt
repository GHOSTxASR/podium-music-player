package app.podium.sources.youtubemusic

import app.podium.core.common.Outcome
import app.podium.core.common.PodiumError
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionAndPagingTest {

    @Test
    fun `a session needs its API secret and never prints its cookies`() {
        val session = assertNotNull(WebSession.parse("SID=abc; SAPISID=sapi-secret/123; junk; =x; y="))
        assertEquals("SID=abc; SAPISID=sapi-secret/123", session.cookieHeader)
        assertFalse("abc" in session.toString())
        assertFalse("sapi" in session.toString())
        assertNull(WebSession.parse("SID=abc; HSID=def"))
        assertNull(WebSession.parse(""))
        assertNotNull(WebSession.parse("__Secure-3PAPISID=x"))
    }

    @Test
    fun `the authorization hash follows the web player's scheme`() {
        val session = WebSession.parse("SAPISID=sapi-secret/123")!!
        assertEquals(
            "SAPISIDHASH 1700000000_6a8d14d447d77ae944b9faea81d07b445bbc692a",
            session.authorization(1_700_000_000, "https://music.youtube.com"),
        )
    }

    @Test
    fun `filters prefer the answer's own parameters`() {
        assertEquals("FROM-ANSWER", SearchFilter.SONGS.fromAnswer(mapOf("songs" to "FROM-ANSWER")))
        assertEquals("CP", SearchFilter.COMMUNITY_PLAYLISTS.fromAnswer(mapOf("community playlists" to "CP")))
        assertNull(SearchFilter.ALBUMS.fromAnswer(emptyMap()))
        // { 2: { 17: { 3: 1 } } }
        assertEquals("EgWKAQIYAQ%3D%3D", SearchFilter.ALBUMS.encoded())
    }

    @Test
    fun `a cursor fetches pages only as far as asked and stops at the end`() = runTest {
        var moreCalls = 0
        val cursor = Cursor<Int>(
            first = { Outcome.Success(YtmPage(listOf(1, 2, 3), YouTubeMusicClient.Continuation("t1", true))) },
            more = { token ->
                moreCalls++
                when (token.token) {
                    "t1" -> Outcome.Success(YtmPage(listOf(4, 5, 6), YouTubeMusicClient.Continuation("t2", true)))
                    else -> Outcome.Success(YtmPage(listOf(7), null))
                }
            },
        )
        assertEquals(listOf(1, 2), (cursor.range(0, 2) as Outcome.Success).value)
        assertEquals(0, moreCalls)
        assertEquals(listOf(3, 4, 5), (cursor.range(2, 3) as Outcome.Success).value)
        assertEquals(1, moreCalls)
        assertEquals(listOf(6, 7), (cursor.range(5, 10) as Outcome.Success).value)
        assertTrue(cursor.exhausted)
        assertEquals(emptyList(), (cursor.range(7, 10) as Outcome.Success).value)
        assertEquals(2, moreCalls)
    }

    @Test
    fun `a cursor stops when a page repeats itself`() = runTest {
        var calls = 0
        val cursor = Cursor<Int>(
            first = { Outcome.Success(YtmPage(listOf(1), YouTubeMusicClient.Continuation("same", true))) },
            more = { calls++; Outcome.Success(YtmPage(listOf(1), YouTubeMusicClient.Continuation("same", true))) },
        )
        assertEquals(listOf(1), (cursor.range(0, 50) as Outcome.Success).value)
        assertEquals(1, calls)
        assertTrue(cursor.exhausted)
    }

    @Test
    fun `a cursor reports a failure only when it has nothing to show`() = runTest {
        val failing = Cursor<Int>(first = { Outcome.Failure(PodiumError.Offline) }, more = { Outcome.Failure(PodiumError.Offline) })
        assertIs<Outcome.Failure>(failing.range(0, 5))
        val partial = Cursor<Int>(
            first = { Outcome.Success(YtmPage(listOf(1, 2), YouTubeMusicClient.Continuation("t", true))) },
            more = { Outcome.Failure(PodiumError.Network()) },
        )
        assertEquals(listOf(2), (partial.range(1, 5) as Outcome.Success).value)
        assertIs<Outcome.Failure>(partial.range(2, 5))
    }

    @Test
    fun `artwork urls are resized only on hosts that resize`() {
        assertEquals(
            "https://lh3.googleusercontent.com/x=w544-h544-l90-rj",
            YouTubeMusicMapper.sizedArtworkUrl("https://lh3.googleusercontent.com/x=w60-h60-l90-rj", 544),
        )
        assertEquals(
            "https://yt3.ggpht.com/y=w1200-h1200-l90-rj",
            YouTubeMusicMapper.sizedArtworkUrl("https://yt3.ggpht.com/y=s88", 4000),
        )
        val video = "https://i.ytimg.com/vi/abc/hqdefault.jpg?sqp=x=y"
        assertEquals(video, YouTubeMusicMapper.sizedArtworkUrl(video, 300))
    }

    @Test
    fun `provider data round-trips and refuses garbage`() {
        val data = YouTubeMusicMapper.ProviderData(app.podium.core.model.MediaKind.MUSIC_VIDEO, "SET1")
        assertEquals(data, YouTubeMusicMapper.ProviderData.decode(data.encode()))
        assertNull(YouTubeMusicMapper.ProviderData.decode("k=NOPE"))
        assertNull(YouTubeMusicMapper.ProviderData.decode(null))
    }

    @Test
    fun `only the music service's hosts are reachable`() {
        assertTrue(UrlConnectionTransport.isAllowedHost("https://music.youtube.com/youtubei/v1/search"))
        assertTrue(UrlConnectionTransport.isAllowedHost("https://lh3.googleusercontent.com/a"))
        assertTrue(UrlConnectionTransport.isAllowedHost("https://i.ytimg.com/vi/a/hq.jpg"))
        assertFalse(UrlConnectionTransport.isAllowedHost("https://evil.example/music.youtube.com"))
        assertFalse(UrlConnectionTransport.isAllowedHost("https://music.youtube.com.evil.example/"))
        assertFalse(UrlConnectionTransport.isAllowedHost("https://www.youtube.com/watch"))
    }
}
