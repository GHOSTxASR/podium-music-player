package app.podium.sources.youtubemusic

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The network, as the stream walk needs it (D-97): the player asked as an app, and a stream checked
 * before the player gets it. Tests replace it with scripted answers.
 */
internal interface StreamHttp {
    /** One request to YouTube's own API. */
    suspend fun post(url: String, headers: Map<String, String>, body: ByteArray): HttpResponse

    /**
     * The status of one range of a stream (`0-`, open-ended as the player asks, or `from-to`), with
     * two bytes read at most: whether it really serves audio there.
     */
    suspend fun probe(url: String, headers: Map<String, String>, range: String): Int
}

/**
 * OkHttp, cancellable for real: a caller that gives up cancels the call. Only https, only to
 * YouTube's API and its video servers; answers are capped in size. Neither addresses (a stream
 * address is the listener's own) nor exception messages leave this class.
 */
internal class OkHttpStreamHttp(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
) : StreamHttp {

    override suspend fun post(url: String, headers: Map<String, String>, body: ByteArray): HttpResponse {
        val request = Request.Builder().url(checked(url)).headers(headers.toHeaders()).post(body.toRequestBody(JSON)).build()
        return execute(request, MAX_ANSWER_BYTES)
    }

    override suspend fun probe(url: String, headers: Map<String, String>, range: String): Int {
        val request = Request.Builder().url(checked(url)).headers((headers + ("Range" to "bytes=$range")).toHeaders()).get().build()
        return execute(request, PROBE_BYTES).code
    }

    private fun checked(url: String): String {
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull()
        val allowed = url.startsWith("https://") && host != null && ALLOWED_HOST_SUFFIXES.any { host == it || host.endsWith(".$it") }
        if (!allowed) throw IOException("refused")
        return url
    }

    private suspend fun execute(request: Request, cap: Int): HttpResponse = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // Never pass the original message on: it can contain the address.
                cont.resumeWithException(IOException(e.javaClass.simpleName))
            }

            override fun onResponse(call: Call, response: Response) {
                val answer = runCatching { response.use { read(it, cap) } }
                answer.fold(cont::resume) { cont.resumeWithException(IOException(it.javaClass.simpleName)) }
            }
        })
    }

    private fun read(response: Response, cap: Int): HttpResponse {
        val body = response.body
        val bytes = body?.byteStream()?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (out.size() < cap) {
                val n = input.read(buffer, 0, minOf(buffer.size, cap - out.size()))
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            if (out.size() >= cap && cap == MAX_ANSWER_BYTES) throw IOException("answer too large")
            out.toByteArray()
        } ?: ByteArray(0)
        return HttpResponse(response.code, bytes, response.header("Retry-After")?.trim()?.toLongOrNull())
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        const val MAX_ANSWER_BYTES = 4 * 1024 * 1024
        /** A check reads this much and closes, even from an open-ended range. */
        const val PROBE_BYTES = 2

        /** YouTube's API, and the servers its streams come from. */
        val ALLOWED_HOST_SUFFIXES = listOf("youtube.com", "googlevideo.com")
    }
}
