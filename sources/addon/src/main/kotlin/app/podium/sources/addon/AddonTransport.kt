package app.podium.sources.addon

import app.podium.sources.api.NetworkPolicy
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** One HTTP answer. [retryAfterSeconds] comes from `Retry-After`, when a server sends it. */
internal class HttpResponse(val code: Int, val body: ByteArray, val retryAfterSeconds: Long? = null)

/** The network, as an addon client needs it: GET and read. Tests replace it with scripted answers. */
internal fun interface AddonTransport {
    suspend fun get(url: String): HttpResponse
}

/**
 * HttpURLConnection, cancellable for real: when the caller gives up the connection is closed and the
 * blocked read ends. The addon's address is the listener's own, so any host may be asked, but only
 * under the network policy (https, or http on the listener's own network, D-09) — redirects
 * included, which are followed by hand so each hop is checked. Answers are capped in size. An
 * addon's address can carry the listener's token, so neither URLs nor exception messages leave this
 * class.
 */
internal class UrlConnectionAddonTransport(private val executor: Executor = SharedExecutor) : AddonTransport {

    override suspend fun get(url: String): HttpResponse {
        var target = url
        repeat(MAX_REDIRECTS + 1) {
            if (!allowed(target)) throw IOException("refused")
            val response = suspendCancellableCoroutine { cont ->
                val connection = URI.create(target).toURL().openConnection() as HttpURLConnection
                cont.invokeOnCancellation { runCatching { connection.disconnect() } }
                executor.execute { cont.resumeWith(runCatching { exchange(connection) }) }
            }
            val location = response.location
            if (response.code !in 300..399 || location == null) return response.answer
            target = runCatching { URI(target).resolve(location).toString() }.getOrNull() ?: throw IOException("bad redirect")
        }
        throw IOException("too many redirects")
    }

    private class Exchange(val answer: HttpResponse, val location: String?) {
        val code: Int get() = answer.code
    }

    private fun exchange(connection: HttpURLConnection): Exchange {
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", USER_AGENT)
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
            return Exchange(HttpResponse(code, bytes, retryAfter), connection.getHeaderField("Location"))
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
        private const val MAX_BODY_BYTES = 2 * 1024 * 1024
        private const val MAX_REDIRECTS = 3
        private const val USER_AGENT = "Podium"

        fun allowed(url: String): Boolean {
            val scheme = url.substringBefore("://", missingDelimiterValue = "").lowercase()
            return (scheme == "https" || scheme == "http") && NetworkPolicy.permits(url)
        }

        private val SharedExecutor: Executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "podium-addon-io").apply { isDaemon = true }
        }
    }
}
