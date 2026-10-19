package app.podium.sources.addon

import app.podium.core.common.Outcome
import app.podium.core.common.PodiumError
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.net.URLEncoder

/**
 * The addon protocol (D-91), BitChord-compatible: three GETs under the addon's address, each answered
 * with JSON —
 *
 * - `/manifest.json`: who the addon is, what it does (`search`, `stream`) and its settings;
 * - `/search?q=…`: songs, each with the addon's own id;
 * - `/stream/{id}`: where that song's audio is.
 *
 * The listener's settings travel with search and stream as query parameters. HTTP failures come back
 * as Podium's errors, never with the address (it can carry the listener's token).
 */
internal class AddonClient(private val base: String, private val transport: AddonTransport) {

    suspend fun manifest(): Outcome<AddonManifest> =
        call("$base/manifest.json") { AddonProtocol.manifest(it) }

    suspend fun search(query: String, settings: Map<String, String>): Outcome<List<AddonTrack>> =
        call("$base/search?" + query(listOf("q" to query) + settings.toList())) { AddonProtocol.search(it) }

    suspend fun stream(id: String, settings: Map<String, String>): Outcome<AddonStream> {
        val params = if (settings.isEmpty()) "" else "?" + query(settings.toList())
        return call("$base/stream/${segment(id)}$params") { bytes ->
            AddonProtocol.stream(bytes)?.let { s -> absolute(s.url)?.let { s.copy(url = it) } }
        }
    }

    /** A stream address relative to the addon (some answer "/files/…") made whole. */
    private fun absolute(url: String): String? =
        if ("://" in url) url else runCatching { java.net.URI("$base/").resolve(url).toString() }.getOrNull()

    private suspend fun <T> call(url: String, read: (ByteArray) -> T?): Outcome<T> {
        val response = try {
            transport.get(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return Outcome.Failure(PodiumError.Network(e.message))
        }
        return when (response.code) {
            in 200..299 -> read(response.body)?.let { Outcome.Success(it) } ?: Outcome.Failure(PodiumError.Unexpected("unreadable answer"))
            404, 410 -> Outcome.Failure(PodiumError.NotFound("addon item"))
            401, 403 -> Outcome.Failure(PodiumError.AuthRequired("addon refused"))
            429 -> Outcome.Failure(PodiumError.RateLimited(response.retryAfterSeconds?.times(1000)))
            else -> Outcome.Failure(PodiumError.Server(response.code))
        }
    }

    companion object {
        private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        fun query(params: List<Pair<String, String>>): String = params.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }

        fun segment(id: String): String = encode(id)
    }
}
