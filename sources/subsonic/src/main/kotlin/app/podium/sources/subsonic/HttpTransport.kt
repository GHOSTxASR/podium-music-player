package app.podium.sources.subsonic

import app.podium.sources.api.NetworkPolicy
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** One HTTP response: status, body, its content type, and where a redirect points. */
class HttpResponse(val code: Int, val body: ByteArray, val contentType: String? = null, val location: String? = null)

/** The network, as the server client needs it. Tests replace it with scripted responses. */
fun interface HttpTransport {
    suspend fun get(url: String): HttpResponse
}

/**
 * Plain HttpURLConnection (no extra dependency), cancellable for real: when the caller gives up
 * (a per-source timeout, a newer search), the connection is closed and the blocked read ends —
 * a slow server can't hold a thread, or Podium, hostage. URLs carry credentials, so neither they
 * nor exception messages (which can contain them) leave this class.
 */
class UrlConnectionTransport(
    private val userAgent: String,
    private val executor: Executor = SharedExecutor,
) : HttpTransport {

    /**
     * Redirects are followed here, not by the platform, so each hop is held to [NetworkPolicy]:
     * never from https down to http, never unencrypted off the listener's network. A refused hop
     * comes back as the redirect itself, which the caller treats as a server failure.
     */
    override suspend fun get(url: String): HttpResponse {
        var current = url
        var hops = 0
        while (true) {
            val response = getOnce(current)
            val next = response.location?.let { runCatching { URI(current).resolve(it).toString() }.getOrNull() }
            if (response.code !in 300..399 || next == null || hops == MAX_REDIRECTS || !mayFollow(current, next)) return response
            current = next
            hops++
        }
    }

    private suspend fun getOnce(url: String): HttpResponse {
        check(NetworkPolicy.permits(url)) { "refused by the network policy" }
        return suspendCancellableCoroutine { cont ->
            val connection = URI.create(url).toURL().openConnection() as HttpURLConnection
            cont.invokeOnCancellation { runCatching { connection.disconnect() } }
            executor.execute {
                // A resume after cancellation is ignored by the continuation.
                cont.resumeWith(runCatching { read(connection) })
            }
        }
    }

    private fun read(connection: HttpURLConnection): HttpResponse {
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Accept", "application/json, image/*")
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { it.readBytes() } ?: ByteArray(0)
            return HttpResponse(code, body, connection.contentType, connection.getHeaderField("Location"))
        } finally {
            connection.disconnect()
        }
    }

    internal companion object {
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 10_000
        const val MAX_REDIRECTS = 3

        fun mayFollow(from: String, to: String): Boolean =
            NetworkPolicy.permits(to) && (to.startsWith("https:", ignoreCase = true) || !from.startsWith("https:", ignoreCase = true))

        private val SharedExecutor: Executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "podium-server-io").apply { isDaemon = true }
        }
    }
}
