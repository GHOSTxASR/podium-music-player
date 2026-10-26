package app.podium.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

/** What the update check needs of the network (D-99); tests script it. */
internal interface UpdateNet {
    /** A small text answer (the releases API). */
    suspend fun text(url: String): String

    /** [url] saved to [to], [onProgress] told bytes so far and the total when known. */
    suspend fun download(url: String, to: File, onProgress: (Long, Long?) -> Unit)
}

/**
 * GitHub over https only, and only GitHub: its API, its release pages and the host its downloads are
 * served from. Redirects are followed by hand so every hop is checked. Nothing about the listener goes
 * with the requests; nothing of the answers is logged.
 */
internal class HttpsUpdateNet(private val userAgent: String) : UpdateNet {
    override suspend fun text(url: String): String = withContext(Dispatchers.IO) {
        open(url, accept = "application/vnd.github+json").use { c ->
            c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > MAX_TEXT_BYTES) throw IOException("answer too large")
                }
                out.toByteArray().decodeToString()
            }
        }
    }

    override suspend fun download(url: String, to: File, onProgress: (Long, Long?) -> Unit) = withContext(Dispatchers.IO) {
        open(url, accept = "application/octet-stream").use { c ->
            val total = c.contentLengthLong.takeIf { it > 0 }
            if (total != null && total > MAX_APK_BYTES) throw IOException("too large")
            to.parentFile?.mkdirs()
            c.inputStream.use { input ->
                to.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        if (done > MAX_APK_BYTES) throw IOException("too large")
                        onProgress(done, total)
                    }
                    if (total != null && done != total) throw IOException("cut short")
                }
            }
        }
    }

    /** An open, successful connection to [url] after at most [MAX_HOPS] checked redirects. */
    private fun open(url: String, accept: String): Connection {
        var at = url
        repeat(MAX_HOPS) {
            check(at)
            val c = URL(at).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", userAgent)
            c.setRequestProperty("Accept", accept)
            val code = c.responseCode
            when (code) {
                in 200..299 -> return Connection(c)
                301, 302, 303, 307, 308 -> {
                    val next = c.getHeaderField("Location") ?: run { c.disconnect(); throw IOException("redirect without a place") }
                    c.disconnect()
                    at = URI(at).resolve(next).toString()
                }
                else -> {
                    c.disconnect()
                    throw HttpError(code)
                }
            }
        }
        throw IOException("too many redirects")
    }

    private class Connection(val c: HttpURLConnection) : AutoCloseable {
        val inputStream get() = c.inputStream
        val contentLengthLong get() = c.contentLengthLong
        override fun close() = c.disconnect()
    }

    companion object {
        const val MAX_HOPS = 5
        const val MAX_TEXT_BYTES = 512 * 1024
        const val MAX_APK_BYTES = 300L * 1024 * 1024

        /** Only GitHub, only https: anything else is refused before it's asked. */
        fun check(url: String) {
            val uri = runCatching { URI(url) }.getOrNull() ?: throw IOException("not an address")
            val host = uri.host?.lowercase() ?: throw IOException("not an address")
            if (uri.scheme != "https" || !allowed(host)) throw IOException("not GitHub")
        }

        fun allowed(host: String): Boolean =
            host == "github.com" || host == "api.github.com" || host == "githubusercontent.com" || host.endsWith(".githubusercontent.com")
    }
}

/** GitHub answered with [code] (a 403 or 429 is its rate limit for anonymous requests). */
internal class HttpError(val code: Int) : IOException("HTTP $code")

/** A file's SHA-256, as lower-case hex. */
internal fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
