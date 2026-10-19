package app.podium.sources.addon

import app.podium.core.common.Outcome
import app.podium.core.model.SourceId
import app.podium.sources.api.FacetResolution
import app.podium.sources.api.PlaybackTarget
import app.podium.sources.api.Purpose
import app.podium.sources.api.QualityRequest
import app.podium.sources.api.SearchQuery
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import java.io.IOException
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** The real transport against a stand-in addon on this machine (http is allowed on the own network, D-09). */
class AddonTransportTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/addon/manifest.json") { it.reply(200, Fixtures.MANIFEST) }
        createContext("/addon/search") { ex ->
            val q = ex.requestURI.rawQuery.orEmpty()
            ex.reply(if ("q=one%20more%20time" in q && "quality=lossless" in q) 200 else 400, Fixtures.SEARCH)
        }
        createContext("/addon/stream/") { ex -> ex.reply(200, """{"url":"media/${ex.requestURI.path.substringAfterLast('/')}.flac","quality":"FLAC 16-bit"}""") }
        createContext("/moved/manifest.json") { ex ->
            ex.responseHeaders.add("Location", "/addon/manifest.json")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        createContext("/away/manifest.json") { ex ->
            ex.responseHeaders.add("Location", "http://music.example.com/manifest.json")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        createContext("/huge/manifest.json") { ex ->
            ex.sendResponseHeaders(200, 0)
            ex.responseBody.use { out -> repeat(3 * 1024) { out.write(ByteArray(1024) { ' '.code.toByte() }) } }
        }
        start()
    }
    private val origin = "http://127.0.0.1:${server.address.port}"

    @After fun stop() = server.stop(0)

    private fun com.sun.net.httpserver.HttpExchange.reply(code: Int, body: String) {
        val bytes = body.toByteArray()
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(code, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    @Test
    fun `adds, searches and resolves against a real server`() = runBlocking<Unit> {
        val base = (Addons.address("$origin/addon/manifest.json").base)!!
        val manifest = (Addons.manifest(base) as Outcome.Success).value
        assertEquals("Lossless test", manifest.name)
        val source = AddonMusicSource(AddonConfig(SourceId("custom-local"), base, manifest))
        val track = (source.catalog!!.search(SearchQuery("one more time")) as Outcome.Success).value.tracks.first()
        val r = assertIs<FacetResolution.Resolved>(source.playback!!.resolve(track, QualityRequest.Maximum, Purpose.PLAYBACK))
        assertEquals("$origin/addon/media/lt_001.flac", (r.target as PlaybackTarget.DirectStream).media.uri)
    }

    @Test
    fun `redirects are followed, each hop under the network policy`() = runBlocking<Unit> {
        assertEquals("Lossless test", (Addons.manifest("$origin/moved") as Outcome.Success).value.name)
        assertIs<Outcome.Failure>(Addons.manifest("$origin/away"), "an http hop off the own network is refused")
    }

    @Test
    fun `answers are capped in size`() {
        assertFailsWith<IOException> { runBlocking { UrlConnectionAddonTransport().get("$origin/huge/manifest.json") } }
    }

    @Test
    fun `unencrypted addresses off the own network are never opened`() {
        assertFailsWith<IOException> { runBlocking { UrlConnectionAddonTransport().get("http://music.example.com/manifest.json") } }
        assertFailsWith<IOException> { runBlocking { UrlConnectionAddonTransport().get("file:///etc/passwd") } }
    }
}
