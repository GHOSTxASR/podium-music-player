package app.podium.sources.addon

import app.podium.core.common.Outcome
import app.podium.core.common.PodiumError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
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
 * The listener's settings travel with search and stream as query parameters. A query that came with
 * the address itself (a token, say) goes with every request, the manifest's too, ahead of them (D-98).
 * HTTP failures come back as Podium's errors, never with the address (it can carry the listener's
 * token).
 *
 * Asking politely and getting an answer (D-96):
 * - **Paced.** Requests start at least [SPACING_MS] apart, in the order they were made.
 * - **Hedged.** Some addons now and then leave one request hanging while the same request sent again
 *   answers at once. A request with no answer after [HEDGE_AFTER_MS] is sent once more and the first
 *   answer wins; one that fails at once (a dropped connection, a 502/503/504) is sent once more
 *   straight away.
 * - **Held after "too many requests".** After a 429 nothing is sent until its `Retry-After` (or
 *   [DEFAULT_HOLD_MS]); asks in the meantime get the same answer without touching the network.
 */
internal class AddonClient(
    private val base: String,
    private val transport: AddonTransport,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val gate = Mutex()
    private var lastStart: Long? = null

    /** The address without its query, and the query as it was typed (sent with every request). */
    private val root = base.substringBefore('?')
    private val fixed = base.substringAfter('?', "")

    @Volatile private var heldUntil = 0L

    suspend fun manifest(): Outcome<AddonManifest> =
        call(at("/manifest.json", emptyList())) { AddonProtocol.manifest(it) }

    suspend fun search(query: String, settings: Map<String, String>): Outcome<List<AddonTrack>> =
        call(at("/search", listOf("q" to query) + settings.toList())) { AddonProtocol.search(it) }

    suspend fun stream(id: String, settings: Map<String, String>): Outcome<AddonStream> =
        call(at("/stream/${segment(id)}", settings.toList())) { bytes ->
            AddonProtocol.stream(bytes)?.let { s -> absolute(s.url)?.let { s.copy(url = it) } }
        }

    /** [path] under the addon, with the address's own query first, then [params]. */
    private fun at(path: String, params: List<Pair<String, String>>): String {
        val q = listOfNotNull(fixed.takeIf { it.isNotEmpty() }, query(params).takeIf { params.isNotEmpty() }).joinToString("&")
        return if (q.isEmpty()) "$root$path" else "$root$path?$q"
    }

    /** A stream address relative to the addon (some answer "/files/…") made whole. */
    private fun absolute(url: String): String? =
        if ("://" in url) url else runCatching { java.net.URI("$root/").resolve(url).toString() }.getOrNull()

    private suspend fun <T> call(url: String, read: (ByteArray) -> T?): Outcome<T> {
        held()?.let { return Outcome.Failure(it) }
        val response = try {
            hedged(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return Outcome.Failure(PodiumError.Network(e.message))
        }
        return when (response.code) {
            in 200..299 -> read(response.body)?.let { Outcome.Success(it) } ?: Outcome.Failure(PodiumError.Unexpected("unreadable answer"))
            404, 410 -> Outcome.Failure(PodiumError.NotFound("addon item"))
            401, 403 -> Outcome.Failure(PodiumError.AuthRequired("addon refused"))
            429 -> {
                val hold = response.retryAfterSeconds?.takeIf { it > 0 }?.times(1000)?.coerceAtMost(MAX_HOLD_MS) ?: DEFAULT_HOLD_MS
                heldUntil = now() + hold
                Outcome.Failure(PodiumError.RateLimited(hold))
            }
            else -> Outcome.Failure(PodiumError.Server(response.code))
        }
    }

    /** "Too many requests" for as long as the addon asked Podium to wait, or null when it may be asked. */
    private fun held(): PodiumError.RateLimited? {
        val left = heldUntil - now()
        return if (left > 0) PodiumError.RateLimited(left) else null
    }

    /** One answer for [url]: the first of at most two identical requests (see the class notes). */
    private suspend fun hedged(url: String): HttpResponse = coroutineScope {
        val first = attempt(url)
        val early = withTimeoutOrNull(HEDGE_AFTER_MS) { first.await() }
        if (early != null && early.settles()) return@coroutineScope early.getOrThrow()
        val pending = listOfNotNull(first.takeIf { early == null }, attempt(url)).toMutableList()
        var last = early
        while (pending.isNotEmpty()) {
            val (done, result) = select { pending.forEach { d -> d.onAwait { d to it } } }
            pending -= done
            last = result
            if (result.settles()) break
        }
        pending.forEach { it.cancel() }
        last!!.getOrThrow()
    }

    private fun CoroutineScope.attempt(url: String): Deferred<Result<HttpResponse>> = async {
        if (held() != null) return@async Result.success(HttpResponse(429, ByteArray(0), (heldUntil - now()).coerceAtLeast(1000) / 1000))
        paced()
        try {
            Result.success(transport.get(url))
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    /** An answer worth keeping: anything but a dropped connection or a gateway that gave up. */
    private fun Result<HttpResponse>.settles(): Boolean = getOrNull()?.let { it.code !in RETRYABLE } ?: false

    /** Waits for this request's turn: starts are [SPACING_MS] apart, first come first served. */
    private suspend fun paced() = gate.withLock {
        lastStart?.let { previous ->
            val wait = previous + SPACING_MS - now()
            if (wait > 0) delay(wait.coerceAtMost(SPACING_MS))
        }
        lastStart = now()
    }

    companion object {
        /** The least time between two requests to one addon. */
        const val SPACING_MS = 200L

        /** An addon answering normally answers well inside this; past it the request is sent again. */
        const val HEDGE_AFTER_MS = 2_500L

        /** How long "too many requests" holds when the addon doesn't say. */
        const val DEFAULT_HOLD_MS = 30_000L

        /** The longest an addon's `Retry-After` is taken at its word. */
        const val MAX_HOLD_MS = 10 * 60_000L

        private val RETRYABLE = setOf(502, 503, 504)

        private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        fun query(params: List<Pair<String, String>>): String = params.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }

        fun segment(id: String): String = encode(id)
    }
}
