package app.podium.sources.youtubemusic

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Production OkHttp downloader for NewPipeExtractor requests in YouTube Music.
 * Handles timeouts, header mapping, body encoding, and propagates rate limits / challenges.
 */
internal class YouTubeMusicDownloader(
    private val userAgent: () -> String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) : Downloader() {

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val requestBody = when {
            dataToSend != null -> dataToSend.toRequestBody(null)
            httpMethod.equals("POST", ignoreCase = true) -> ByteArray(0).toRequestBody(null)
            else -> null
        }

        val okRequestBuilder = okhttp3.Request.Builder()
            .method(httpMethod, requestBody)
            .url(url)
            .addHeader("User-Agent", userAgent())

        headers?.forEach { (headerName, headerValues) ->
            okRequestBuilder.removeHeader(headerName)
            headerValues.forEach { headerValue ->
                okRequestBuilder.addHeader(headerName, headerValue)
            }
        }

        client.newCall(okRequestBuilder.build()).execute().use { response ->
            if (response.code == 429) {
                throw ReCaptchaException("reCaptcha challenge / rate limit requested (429)", url)
            }

            val bodyString = response.body?.string()
            val latestUrl = response.request.url.toString()

            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                bodyString,
                latestUrl
            )
        }
    }
}
