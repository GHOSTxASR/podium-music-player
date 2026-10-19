package app.podium.core.lyrics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** What a provider found for one request (the repository turns it into a [LyricsResult]). */
sealed interface ProviderAnswer {
    data class Found(val lyrics: Lyrics) : ProviderAnswer
    data object NotFound : ProviderAnswer
    data object Offline : ProviderAnswer
    /** The service asked Podium to slow down; nothing more is asked of it for [retryInMs]. */
    data class RateLimited(val retryInMs: Long) : ProviderAnswer
    data object Failed : ProviderAnswer
}

/** Somewhere lyrics come from. Replaceable: nothing outside the lyrics layer knows which one it is. */
interface LyricsProvider {
    /** Stable id, part of every cache key. */
    val id: String
    val attribution: LyricsAttribution
    suspend fun lookup(request: LyricsRequest): ProviderAnswer
}

/** An HTTP GET, as the provider needs it. Tests answer with scripted responses. */
fun interface LyricsHttp {
    /** Returns the status and body; throws IOException when the network fails. */
    suspend fun get(url: String): Pair<Int, String>

    /** The status, the body and how long the service asks to wait (its `Retry-After`, in seconds), when it says. */
    suspend fun reply(url: String): Reply = get(url).let { (code, body) -> Reply(code, body, null) }

    data class Reply(val code: Int, val body: String, val retryAfterSeconds: Long?)
}

/**
 * LRCLIB (lrclib.net): a free, open, community-built lyrics database with synced (LRC) and plain
 * lyrics, no key and no account (LYRICS_ARCHITECTURE.md §1). Podium asks by the song's metadata —
 * title, artist, album, length — never by any music service's id:
 *
 * 1. `GET /api/get` with all four (the service matches the length within a couple of seconds);
 * 2. if that finds nothing, `GET /api/search` by title and artist, and Podium's own [LyricsMatcher]
 *    picks a candidate only when it's confidently the same recording.
 *
 * Synced lyrics are preferred (with the line end times and any word times of LRCLIB's `lyricsfile`);
 * plain lyrics are used as plain; "instrumental" is believed.
 *
 * **Asking politely (D-84).** The service limits how often it is asked: it answers 429 with how long
 * to wait (`Retry-After`). Podium waits that long — or, without it, 30 s, doubling with each further
 * refusal up to 10 min — and asks nothing at all meanwhile: every lookup in the wait is answered
 * [ProviderAnswer.RateLimited] at once, with the time left. Its own requests are at least
 * [MIN_INTERVAL_MS] apart, so skipping through songs can't send a burst. An answer the service does
 * give ends the backing off.
 */
class LrclibProvider(
    private val http: LyricsHttp,
    private val base: String = "https://lrclib.net",
    /** A monotonic clock in milliseconds (tests pass their own). */
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) : LyricsProvider {

    private val gate = Mutex()
    private var blockedUntil = Long.MIN_VALUE
    private var refusals = 0
    private var lastAsked = Long.MIN_VALUE

    override val id = "lrclib"
    override val attribution = LyricsAttribution("LRCLIB", "https://lrclib.net")

    @Serializable
    internal data class Record(
        val id: Long? = null,
        val trackName: String? = null,
        val artistName: String? = null,
        val albumName: String? = null,
        val duration: Double? = null,
        val instrumental: Boolean = false,
        val plainLyrics: String? = null,
        val syncedLyrics: String? = null,
        /** LRCLIB's structured form of the same lyrics: adds line end times, and words when word-synced. */
        val lyricsfile: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    override suspend fun lookup(request: LyricsRequest): ProviderAnswer {
        if (request.title.isBlank() || request.artist.isBlank()) return ProviderAnswer.NotFound
        val exact = fetch(getUrl(request)) { body -> listOf(json.decodeFromString(Record.serializer(), body)) }
        // The service's own exact match is checked against Podium's rules too. Synced words win at
        // once; an exact match with only plain words is kept while the search looks for the same
        // song with times (often another upload of it has them).
        var plainExact: Record? = null
        when (exact) {
            is Fetched.Records -> {
                val record = exact.records.singleOrNull()
                if (record != null && LyricsMatcher.matches(request, record.candidate())) {
                    if (record.instrumental || record.isSynced) return answer(record)
                    plainExact = record
                }
            }
            Fetched.NotFound -> Unit
            is Fetched.Problem -> return exact.answer
        }
        return when (val search = fetch(searchUrl(request)) { body -> json.decodeFromString(ListSerializer(Record.serializer()), body) }) {
            is Fetched.Records -> {
                val usable = search.records.filter { it.hasWords || it.instrumental }
                val best = LyricsMatcher.best(request, usable.filter { it.isSynced }) { it.candidate() }
                    ?: plainExact
                    ?: LyricsMatcher.best(request, usable) { it.candidate() }
                best?.let(::answer) ?: ProviderAnswer.NotFound
            }
            Fetched.NotFound -> plainExact?.let(::answer) ?: ProviderAnswer.NotFound
            is Fetched.Problem -> plainExact?.let(::answer) ?: search.answer
        }
    }

    private val Record.hasWords get() = !syncedLyrics.isNullOrBlank() || !plainLyrics.isNullOrBlank()

    /** Times the service has for this record: its LRC, or its structured file. */
    private val Record.isSynced get() = LrcParser.synced(syncedLyrics) != null || !lyricsfile.isNullOrBlank()

    private fun Record.candidate() = LyricsMatcher.Candidate(
        title = trackName.orEmpty(),
        artist = artistName.orEmpty(),
        album = albumName,
        durationMs = duration?.let { (it * 1000).toLong() },
    )

    private fun answer(record: Record): ProviderAnswer {
        if (record.instrumental) return ProviderAnswer.Found(Lyrics.Instrumental)
        val file = runCatching { LyricsFile.parse(record.lyricsfile) }.getOrDefault(emptyList())
        LrcParser.synced(record.syncedLyrics)?.let { return ProviderAnswer.Found(Lyrics.Synced(LyricsFile.enrich(it.lines, file))) }
        // No LRC, but the structured file has times: use it.
        file.takeIf { lines -> lines.any { it.text.isNotBlank() } }?.let { return ProviderAnswer.Found(Lyrics.Synced(it)) }
        LrcParser.plain(record.plainLyrics)?.let { return ProviderAnswer.Found(it) }
        return ProviderAnswer.NotFound
    }

    private sealed interface Fetched {
        data class Records(val records: List<Record>) : Fetched
        data object NotFound : Fetched
        data class Problem(val answer: ProviderAnswer) : Fetched
    }

    private suspend fun fetch(url: String, decode: (String) -> List<Record>): Fetched {
        val reply = gate.withLock {
            val t = now()
            if (t < blockedUntil) return Fetched.Problem(ProviderAnswer.RateLimited(blockedUntil - t))
            // At least MIN_INTERVAL_MS between requests (callers wait their turn here, in order).
            if (lastAsked != Long.MIN_VALUE) {
                val wait = lastAsked + MIN_INTERVAL_MS - t
                if (wait > 0) delay(wait)
            }
            lastAsked = now()
            val answer = try {
                http.reply(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: UnknownHostException) {
                return Fetched.Problem(ProviderAnswer.Offline)
            } catch (e: IOException) {
                return Fetched.Problem(if (e.message == "UnknownHostException") ProviderAnswer.Offline else ProviderAnswer.Failed)
            }
            if (answer.code == 429) {
                val asked = answer.retryAfterSeconds?.takeIf { it > 0 }?.times(1000)
                val backoff = minOf(FIRST_BACKOFF_MS shl minOf(refusals, 5), MAX_BACKOFF_MS)
                val wait = (asked ?: backoff).coerceIn(MIN_BACKOFF_MS, MAX_BACKOFF_MS)
                refusals++
                blockedUntil = now() + wait
                return Fetched.Problem(ProviderAnswer.RateLimited(wait))
            }
            refusals = 0
            answer
        }
        return when (reply.code) {
            in 200..299 -> runCatching { Fetched.Records(decode(reply.body)) }.getOrElse { Fetched.Problem(ProviderAnswer.Failed) }
            404 -> Fetched.NotFound
            else -> Fetched.Problem(ProviderAnswer.Failed)
        }
    }

    private fun getUrl(r: LyricsRequest) = buildString {
        append(base).append("/api/get?track_name=").append(enc(r.title)).append("&artist_name=").append(enc(r.artist))
        r.album?.takeIf { it.isNotBlank() }?.let { append("&album_name=").append(enc(it)) }
        r.durationMs?.takeIf { it > 0 }?.let { append("&duration=").append((it + 500) / 1000) }
    }

    private fun searchUrl(r: LyricsRequest) =
        "$base/api/search?track_name=${enc(r.title)}&artist_name=${enc(r.artist)}"

    private fun enc(s: String) = URLEncoder.encode(s.trim(), "UTF-8")

    companion object {
        /** Podium's own requests are at least this far apart. */
        const val MIN_INTERVAL_MS = 1_000L

        /** Without a `Retry-After`: 30 s, doubling with each refusal in a row, at most 10 min. */
        const val FIRST_BACKOFF_MS = 30_000L
        const val MAX_BACKOFF_MS = 10 * 60_000L

        /** However short a wait the service names, at least this. */
        const val MIN_BACKOFF_MS = 5_000L
    }
}

/**
 * HttpURLConnection for the lyrics service: https only, a clear user agent naming Podium (as the
 * service asks), cancellable, short timeouts, bounded answers.
 */
class UrlConnectionLyricsHttp(
    private val userAgent: String,
    private val executor: Executor = SharedExecutor,
) : LyricsHttp {
    override suspend fun get(url: String): Pair<Int, String> = reply(url).let { it.code to it.body }

    override suspend fun reply(url: String): LyricsHttp.Reply {
        if (!url.startsWith("https://")) throw IOException("refused")
        return suspendCancellableCoroutine { cont ->
            val connection = URI.create(url).toURL().openConnection() as HttpURLConnection
            cont.invokeOnCancellation { runCatching { connection.disconnect() } }
            executor.execute {
                cont.resumeWith(
                    runCatching {
                        try {
                            connection.connectTimeout = 8_000
                            connection.readTimeout = 10_000
                            connection.instanceFollowRedirects = false
                            connection.setRequestProperty("User-Agent", userAgent)
                            connection.setRequestProperty("Accept", "application/json")
                            val code = connection.responseCode
                            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                            val bytes = stream?.use { input ->
                                val out = java.io.ByteArrayOutputStream()
                                val buffer = ByteArray(8 * 1024)
                                while (out.size() < MAX_BYTES) {
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    out.write(buffer, 0, n)
                                }
                                out.toByteArray()
                            } ?: ByteArray(0)
                            LyricsHttp.Reply(code, bytes.toString(Charsets.UTF_8), retryAfterSeconds(connection.getHeaderField("Retry-After")))
                        } catch (e: UnknownHostException) {
                            throw e
                        } catch (e: IOException) {
                            throw IOException(e.javaClass.simpleName)
                        } finally {
                            connection.disconnect()
                        }
                    },
                )
            }
        }
    }

    private companion object {
        const val MAX_BYTES = 2 * 1024 * 1024

        /** `Retry-After` as seconds, or as an HTTP date (seconds from now), or null. */
        fun retryAfterSeconds(value: String?): Long? {
            val v = value?.trim().orEmpty()
            if (v.isEmpty()) return null
            v.toLongOrNull()?.let { return it.coerceAtLeast(0) }
            return runCatching {
                val at = java.time.ZonedDateTime.parse(v, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
                ((at - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            }.getOrNull()
        }
        val SharedExecutor: Executor = Executors.newCachedThreadPool { r -> Thread(r, "podium-lyrics-io").apply { isDaemon = true } }
    }
}
