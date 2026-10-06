package app.podium.sources.subsonic

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The real transport against a loopback server: redirects are followed only where D-09 allows. */
class UrlConnectionTransportTest {
    private val hits = AtomicInteger()
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { exchange ->
            hits.incrementAndGet()
            val (code, location) = when (exchange.requestURI.path) {
                "/ok" -> 200 to null
                "/same-host" -> 302 to "/ok"
                "/off-network" -> 302 to "http://music.example.com/rest/stream"
                "/loop" -> 302 to "/loop"
                else -> 404 to null
            }
            location?.let { exchange.responseHeaders.add("Location", it) }
            val body = if (code == 200) "fine".toByteArray() else ByteArray(0)
            exchange.sendResponseHeaders(code, if (body.isEmpty()) -1 else body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"
    private val transport = UrlConnectionTransport("Podium test")

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    fun `a redirect on the same server is followed`() = runBlocking {
        val response = transport.get("$base/same-host")
        assertEquals(200, response.code)
        assertEquals("fine", response.body.decodeToString())
    }

    @Test
    fun `a redirect to unencrypted http off the listener's network is not followed`() = runBlocking {
        val response = transport.get("$base/off-network")
        assertEquals(302, response.code)
        assertEquals(1, hits.get())
    }

    @Test
    fun `redirects stop after a few hops`() = runBlocking {
        assertEquals(302, transport.get("$base/loop").code)
        assertEquals(UrlConnectionTransport.MAX_REDIRECTS + 1, hits.get())
    }

    @Test
    fun `nothing is sent unencrypted off the listener's network`() {
        assertFailsWith<IllegalStateException> { runBlocking { transport.get("http://music.example.com/rest/ping") } }
    }

    @Test
    fun `https never redirects down to http`() {
        assertFalse(UrlConnectionTransport.mayFollow("https://music.example.com/rest/stream", "http://192.168.1.20/a.mp3"))
        assertTrue(UrlConnectionTransport.mayFollow("https://music.example.com/rest/stream", "https://cdn.example.com/a.mp3"))
        assertTrue(UrlConnectionTransport.mayFollow("http://192.168.1.20/rest/stream", "https://music.example.com/a.mp3"))
        assertFalse(UrlConnectionTransport.mayFollow("http://192.168.1.20/rest/stream", "http://cdn.example.com/a.mp3"))
    }
}
