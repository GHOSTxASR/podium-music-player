package app.podium.sources.youtubemusic

import app.podium.core.common.Outcome
import app.podium.core.model.MediaKind
import app.podium.sources.api.Capability
import app.podium.sources.api.InMemoryCredentialStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Live radio (D-90): finding stations, and choosing the stream a station plays. */
class YouTubeLiveRadioTest {

    private fun video(id: String, title: String, channel: String, live: Boolean, watching: String?) = """
        {"videoRenderer":{"videoId":"$id",
          "thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/vi/$id/hqdefault.jpg","width":480,"height":270}]},
          "title":{"runs":[{"text":"$title"}]},
          "ownerText":{"runs":[{"text":"$channel","navigationEndpoint":{"browseEndpoint":{"browseId":"UC$id"}}}]},
          ${watching?.let { "\"viewCountText\":{\"runs\":[{\"text\":\"$it\"},{\"text\":\" watching\"}]}," }.orEmpty()}
          "badges":[${if (live) """{"metadataBadgeRenderer":{"style":"BADGE_STYLE_TYPE_LIVE_NOW","label":"LIVE"}}""" else ""}]
        }}"""

    private val answer = """
        {"contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{"sectionListRenderer":{"contents":[
          {"itemSectionRenderer":{"contents":[
            ${video("aaaaaaaaaa1", "Small lofi radio", "Tiny beats", live = true, watching = "12")},
            ${video("aaaaaaaaaa2", "lofi hip hop radio", "Big beats", live = true, watching = "31K")},
            ${video("aaaaaaaaaa3", "Lofi mix (recorded)", "Old beats", live = false, watching = null)},
            ${video("aaaaaaaaaa4", "Jazz lofi radio", "Mid beats", live = true, watching = "1,204")},
            ${video("aaaaaaaaaa5", "Quiet lofi radio", "No count", live = true, watching = null)},
            ${video("aaaaaaaaaa2", "lofi hip hop radio", "Big beats", live = true, watching = "31K")}
          ]}}
        ]}}}}}"""

    @Test
    fun `stations are the streams live now, once each, the most listened to first`() {
        val stations = LiveParser.stations(Json.parseToJsonElement(answer) as JsonObject)
        assertEquals(listOf("aaaaaaaaaa2", "aaaaaaaaaa4", "aaaaaaaaaa1", "aaaaaaaaaa5"), stations.map { it.videoId })
        assertTrue(stations.all { it.kind == MediaKind.LIVE && it.durationMs == null })
        assertEquals("Big beats", stations.first().artists.single().name)
        assertNull(stations.first().artists.single().id, "a channel isn't an artist page")
        assertEquals(480, stations.first().thumbnails.single().width)
    }

    private val master = """
        #EXTM3U
        #EXT-X-INDEPENDENT-SEGMENTS
        #EXT-X-MEDIA:URI="https://manifest.googlevideo.com/api/manifest/hls_playlist/expire/1792422161/id/x.1/itag/233/sgoap/gir%3Dyes%3Bitag%3D139/playlist/index.m3u8",TYPE=AUDIO,GROUP-ID="233",NAME="Default",DEFAULT=YES,AUTOSELECT=YES
        #EXT-X-MEDIA:URI="https://manifest.googlevideo.com/api/manifest/hls_playlist/expire/1792422161/id/x.1/itag/234/sgoap/gir%3Dyes%3Bitag%3D140/playlist/index.m3u8",TYPE=AUDIO,GROUP-ID="234",NAME="Default",DEFAULT=YES,AUTOSELECT=YES
        #EXT-X-STREAM-INF:BANDWIDTH=546239,CODECS="avc1.4D4015,mp4a.40.5",RESOLUTION=426x240,FRAME-RATE=30,AUDIO="233",CLOSED-CAPTIONS=NONE
        https://manifest.googlevideo.com/api/manifest/hls_playlist/expire/1792422161/id/x.1/itag/229/playlist/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=1209862,CODECS="avc1.4D401E,mp4a.40.2",RESOLUTION=640x360,FRAME-RATE=30,AUDIO="234",CLOSED-CAPTIONS=NONE
        https://manifest.googlevideo.com/api/manifest/hls_playlist/expire/1792422161/id/x.1/itag/230/playlist/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=290288,CODECS="avc1.42C00B,mp4a.40.5",RESOLUTION=256x144,FRAME-RATE=15,AUDIO="233",CLOSED-CAPTIONS=NONE
        https://manifest.googlevideo.com/api/manifest/hls_playlist/expire/1792422161/id/x.1/itag/269/playlist/index.m3u8
    """.trimIndent()

    @Test
    fun `a station plays its audio-only AAC rendition, never the video`() {
        val pick = HlsAudio.pick(master)!!
        assertTrue(pick.audioOnly)
        assertEquals(140, pick.itag)
        assertTrue("itag/234/" in pick.uri)
        assertEquals(1_792_422_161_000L, HlsAudio.expiresAtMillis(pick.uri))
    }

    @Test
    fun `without an audio rendition, the smallest variant plays`() {
        val muxed = master.lines().filterNot { it.startsWith("#EXT-X-MEDIA") }.joinToString("\n")
        val pick = HlsAudio.pick(muxed)!!
        assertFalse(pick.audioOnly)
        assertTrue("itag/269/" in pick.uri)
        assertNull(HlsAudio.pick("#EXTM3U\n"))
    }

    @Test
    fun `tag attributes are read quoted or bare`() {
        val line = """#EXT-X-STREAM-INF:BANDWIDTH=290288,CODECS="avc1.42C00B,mp4a.40.5",AUDIO="233""""
        assertEquals("290288", HlsAudio.attribute(line, "BANDWIDTH"))
        assertEquals("avc1.42C00B,mp4a.40.5", HlsAudio.attribute(line, "CODECS"))
        assertEquals("233", HlsAudio.attribute(line, "AUDIO"))
        assertNull(HlsAudio.attribute(line, "URI"))
    }

    @Test
    fun `the source lists a genre's stations from the live search, signed out`() = runTest {
        val sent = mutableListOf<Pair<String, String>>()
        var refused = true
        val transport = HttpTransport { method, url, headers, body ->
            sent += url to body?.toString(Charsets.UTF_8).orEmpty()
            assertNull(headers["Cookie"], "nothing of the account goes with a station search")
            when {
                method == "GET" && url == "https://www.youtube.com/" ->
                    HttpResponse(200, """<script>ytcfg.set({"INNERTUBE_CLIENT_VERSION":"2.20261018.01.00"});</script>""".toByteArray())
                url.startsWith("https://www.youtube.com/youtubei/v1/search") && refused -> {
                    refused = false
                    HttpResponse(400, ByteArray(0))
                }
                url.startsWith("https://www.youtube.com/youtubei/v1/search") -> HttpResponse(200, answer.toByteArray())
                else -> HttpResponse(404, ByteArray(0))
            }
        }
        val source = YouTubeMusicSource(InMemoryCredentialStore(), transport, { "TestBrowser/1.0" }, { "IN" }, { 1_700_000_000_000L })
        assertTrue(source.capabilities.value.isUsable(Capability.LIVE_RADIO))
        val radio = source.liveRadio
        assertTrue("Lofi" in radio.genres())
        val stations = assertIs<Outcome.Success<List<app.podium.core.model.Track>>>(radio.stations("Lofi")).value
        assertEquals(4, stations.size)
        assertTrue(stations.all { it.kind == MediaKind.LIVE && it.durationMs == null })
        // A refused client version is learned from the page, then the search is asked again.
        val searches = sent.filter { it.first.contains("/search") }
        assertEquals(2, searches.size)
        assertTrue("2.20261018.01.00" in searches.last().second)
        assertTrue("\"params\":\"EgJAAQ%3D%3D\"" in searches.last().second)
        assertTrue("\"query\":\"lofi radio\"" in searches.last().second)
        assertIs<Outcome.Failure>(radio.stations("Not a genre"))
    }

    @Test
    fun `listeners are read in the site's forms`() {
        fun count(text: String) = LiveParser.listeners(Json.parseToJsonElement("""{"viewCountText":{"simpleText":"$text"}}"""))
        assertEquals(12L, count("12 watching"))
        assertEquals(1_204L, count("1,204 watching"))
        assertEquals(31_000L, count("31K watching"))
        assertEquals(1_200_000L, count("1.2M watching"))
        assertNull(count("watching"))
    }
}
