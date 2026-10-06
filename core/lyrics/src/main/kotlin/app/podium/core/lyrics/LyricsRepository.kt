package app.podium.core.lyrics

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * Lyrics for the screens (LYRICS_ARCHITECTURE.md §6): asks the [provider] only with the listener's
 * consent (D-11), remembers answers so a song's lyrics are fetched once, and never lets the UI near
 * the network.
 *
 * - Found lyrics are kept for [foundTtlMs]; "not found" for [missTtlMs] (the database grows); failures
 *   aren't kept at all.
 * - Keys are the provider plus a fingerprint of the song's identity and metadata, so different
 *   providers, songs, or edits never share an entry, and nothing secret is ever part of one.
 * - Asking twice at once makes one request.
 */
class LyricsRepository(
    private val provider: LyricsProvider,
    private val cache: LyricsCache,
    private val consent: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    private val foundTtlMs: Long = 30L * 24 * 60 * 60 * 1000,
    private val missTtlMs: Long = 3L * 24 * 60 * 60 * 1000,
) {
    val attribution: LyricsAttribution get() = provider.attribution

    private val lock = Mutex()
    private val inFlight = HashMap<String, CompletableDeferred<LyricsResult>>()

    suspend fun lyrics(request: LyricsRequest): LyricsResult {
        val key = keyOf(request)
        cache.read(key)?.let { entry ->
            if (now() - entry.storedAt < (if (entry.lyrics != null) foundTtlMs else missTtlMs)) {
                return entry.lyrics?.let { LyricsResult.Found(it, provider.attribution) } ?: LyricsResult.NotFound
            }
        }
        if (!consent()) return LyricsResult.NeedsConsent
        val (deferred, owner) = lock.withLock {
            inFlight[key]?.let { it to false } ?: CompletableDeferred<LyricsResult>().also { inFlight[key] = it }.let { it to true }
        }
        if (!owner) return deferred.await()
        val result = try {
            when (val answer = provider.lookup(request)) {
                is ProviderAnswer.Found -> {
                    cache.write(key, LyricsCache.Entry(now(), answer.lyrics))
                    LyricsResult.Found(answer.lyrics, provider.attribution)
                }
                ProviderAnswer.NotFound -> {
                    cache.write(key, LyricsCache.Entry(now(), null))
                    LyricsResult.NotFound
                }
                ProviderAnswer.Offline -> LyricsResult.Offline
                ProviderAnswer.RateLimited -> LyricsResult.RateLimited
                ProviderAnswer.Failed -> LyricsResult.Failed
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            lock.withLock { inFlight.remove(key) }
            deferred.cancel()
            throw e
        } catch (e: Exception) {
            LyricsResult.Failed
        }
        lock.withLock { inFlight.remove(key) }
        deferred.complete(result)
        return result
    }

    /** Forget every kept answer. */
    fun clear() = cache.clear()

    fun keyOf(request: LyricsRequest): String {
        val fingerprint = listOf(
            request.key,
            request.title.trim().lowercase(),
            request.artist.trim().lowercase(),
            request.album?.trim()?.lowercase().orEmpty(),
            ((request.durationMs ?: 0L) / 2_000).toString(),
        ).joinToString("\u001F")
        val digest = MessageDigest.getInstance("SHA-256").digest(fingerprint.toByteArray(Charsets.UTF_8))
        return provider.id + "-" + digest.take(16).joinToString("") { "%02x".format(it) }
    }
}

/** Where answers are kept between runs. */
interface LyricsCache {
    /** [lyrics] null means "the provider has none". */
    data class Entry(val storedAt: Long, val lyrics: Lyrics?)

    fun read(key: String): Entry?
    fun write(key: String, entry: Entry)
    fun clear()
}

class InMemoryLyricsCache : LyricsCache {
    private val map = LinkedHashMap<String, LyricsCache.Entry>()
    @Synchronized override fun read(key: String) = map[key]
    @Synchronized override fun write(key: String, entry: LyricsCache.Entry) {
        map[key] = entry
    }
    @Synchronized override fun clear() = map.clear()
}

/**
 * Answers kept as small JSON files in the app's cache directory (the system may clear it; that only
 * costs a request). Recently used entries also stay in memory. At most [maxEntries] files.
 */
class FileLyricsCache(private val directory: File, private val maxEntries: Int = 500) : LyricsCache {

    @Serializable
    private data class Stored(
        val storedAt: Long,
        val kind: String,
        val times: List<Long> = emptyList(),
        val lines: List<String> = emptyList(),
        /** Per line: its end time, or -1. Absent in entries written before word timing. */
        val ends: List<Long> = emptyList(),
        /** Per line: its words' start times (empty when the provider times whole lines only). */
        val wordTimes: List<List<Long>> = emptyList(),
        val wordTexts: List<List<String>> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val memory = object : LinkedHashMap<String, LyricsCache.Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LyricsCache.Entry>?) = size > 64
    }

    @Synchronized
    override fun read(key: String): LyricsCache.Entry? {
        memory[key]?.let { return it }
        val file = fileOf(key) ?: return null
        if (!file.isFile) return null
        val stored = runCatching { json.decodeFromString(Stored.serializer(), file.readText()) }.getOrNull() ?: run {
            file.delete()
            return null
        }
        val lyrics = when (stored.kind) {
            "synced" -> stored.lines.zip(stored.times).mapIndexed { i, (text, ms) ->
                val words = stored.wordTimes.getOrNull(i).orEmpty().zip(stored.wordTexts.getOrNull(i).orEmpty()) { t, w -> LyricsWord(t, w) }
                LyricsLine(ms, text, endMs = stored.ends.getOrNull(i)?.takeIf { it >= 0 }, words = words)
            }.takeIf { it.isNotEmpty() }?.let(Lyrics::Synced)
            "plain" -> stored.lines.takeIf { it.isNotEmpty() }?.let(Lyrics::Plain)
            "instrumental" -> Lyrics.Instrumental
            else -> null
        }
        return LyricsCache.Entry(stored.storedAt, lyrics).also { memory[key] = it }
    }

    @Synchronized
    override fun write(key: String, entry: LyricsCache.Entry) {
        memory[key] = entry
        val file = fileOf(key) ?: return
        val stored = when (val l = entry.lyrics) {
            is Lyrics.Synced -> Stored(
                entry.storedAt,
                "synced",
                l.lines.map { it.startMs },
                l.lines.map { it.text },
                ends = l.lines.map { it.endMs ?: -1L },
                wordTimes = l.lines.map { line -> line.words.map { it.startMs } },
                wordTexts = l.lines.map { line -> line.words.map { it.text } },
            )
            is Lyrics.Plain -> Stored(entry.storedAt, "plain", lines = l.lines)
            Lyrics.Instrumental -> Stored(entry.storedAt, "instrumental")
            null -> Stored(entry.storedAt, "none")
        }
        runCatching {
            directory.mkdirs()
            val partial = File(directory, file.name + ".partial")
            partial.writeText(json.encodeToString(Stored.serializer(), stored))
            partial.renameTo(file)
            trim()
        }
    }

    @Synchronized
    override fun clear() {
        memory.clear()
        directory.listFiles()?.forEach { it.delete() }
    }

    private fun trim() {
        val files = directory.listFiles { f -> f.name.endsWith(".json") } ?: return
        if (files.size <= maxEntries) return
        files.sortedBy { it.lastModified() }.take(files.size - maxEntries).forEach { it.delete() }
    }

    /** Keys are hex digests with a provider prefix; anything else is refused (no path tricks). */
    private fun fileOf(key: String): File? =
        key.takeIf { it.matches(Regex("[a-z0-9]{1,32}-[0-9a-f]{32}")) }?.let { File(directory, "$it.json") }
}
