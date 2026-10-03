package app.podium.sources.audius

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** One HTTP response: status and body bytes. */
class HttpResponse(val code: Int, val body: ByteArray, val headers: Map<String, String> = emptyMap())

/** The network, as the Audius client needs it. Tests replace it with recorded responses. */
fun interface HttpTransport {
    @Throws(IOException::class)
    fun get(url: String): HttpResponse
}

/** Plain HttpURLConnection: no extra dependency, and the same stack Media3's data source uses. */
class UrlConnectionTransport(private val userAgent: String) : HttpTransport {
    override fun get(url: String): HttpResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Accept", "application/json, image/*")
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { it.readBytes() } ?: ByteArray(0)
            val headers = connection.headerFields.filterKeys { it != null }.mapValues { it.value.joinToString(",") }.mapKeys { it.key.lowercase() }
            return HttpResponse(code, body, headers)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 15_000
    }
}
