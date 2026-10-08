package app.podium.poc

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.util.concurrent.TimeUnit

class PocDownloader(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) : Downloader() {

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
        private val INSTANCE by lazy { PocDownloader() }
        fun getInstance(): PocDownloader = INSTANCE
    }

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val requestBody = if (dataToSend != null) {
            dataToSend.toRequestBody(null)
        } else if (httpMethod.equals("POST", ignoreCase = true)) {
            ByteArray(0).toRequestBody(null)
        } else {
            null
        }

        val okRequestBuilder = okhttp3.Request.Builder()
            .method(httpMethod, requestBody)
            .url(url)
            .addHeader("User-Agent", USER_AGENT)

        headers?.forEach { (headerName, headerValues) ->
            okRequestBuilder.removeHeader(headerName)
            headerValues.forEach { headerValue ->
                okRequestBuilder.addHeader(headerName, headerValue)
            }
        }

        client.newCall(okRequestBuilder.build()).execute().use { response ->
            if (response.code == 429) {
                throw ReCaptchaException("reCaptcha Challenge requested (429)", url)
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
