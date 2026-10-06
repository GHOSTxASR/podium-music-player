package app.podium.sources.youtubemusic

import app.podium.sources.api.NetworkPolicy
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** One HTTP answer. [retryAfterSeconds] comes from `Retry-After`, when a server sends it. */
internal class HttpResponse(val code: Int, val body: ByteArray, val retryAfterSeconds: Long? = null)

/** The network, as the client needs it. Tests replace it with scripted answers. */
internal fun interface HttpTransport {
    suspend fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse
}

/**
 * HttpURLConnection, cancellable for real: when the caller gives up the connection is closed and
 * the blocked read ends. Only https to the music service's own hosts is allowed, redirects are not
 * followed (an answer that redirects is a failure), and answers are capped in size. Neither URLs,
 * headers (which carry the session) nor exception messages leave this class.
 */
internal class UrlConnectionTransport(private val executor: Executor = SharedExecutor) : HttpTransport {

    override suspend fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
        if (!NetworkPolicy.permits(url) || !url.startsWith("https://") || !isAllowedHost(url)) throw IOException("refused")
        return suspendCancellableCoroutine { cont ->
            val connection = URI.create(url).toURL().openConnection() as HttpURLConnection
            cont.invokeOnCancellation { runCatching { connection.disconnect() } }
            executor.execute { cont.resumeWith(runCatching { exchange(connection, method, headers, body) }) }
        }
    }

    private fun exchange(connection: HttpURLConnection, method: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.requestMethod = method
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > MAX_BODY_BYTES) throw IOException("answer too large")
                }
                out.toByteArray()
            } ?: ByteArray(0)
            val retryAfter = connection.getHeaderField("Retry-After")?.trim()?.toLongOrNull()
            return HttpResponse(code, bytes, retryAfter)
        } catch (e: IOException) {
            // Never pass the original message on: it can contain the URL.
            throw IOException(e.javaClass.simpleName)
        } finally {
            connection.disconnect()
        }
    }

    internal companion object {
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 12_000
        private const val MAX_BODY_BYTES = 12 * 1024 * 1024

        /** The music service's API and page, and the hosts its artwork comes from. */
        private val ALLOWED_HOST_SUFFIXES = listOf("music.youtube.com", "googleusercontent.com", "ytimg.com", "ggpht.com")

        fun isAllowedHost(url: String): Boolean {
            val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return false
            return ALLOWED_HOST_SUFFIXES.any { host == it || host.endsWith(".$it") }
        }

        private val SharedExecutor: Executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "podium-ytm-io").apply { isDaemon = true }
        }
    }
}
