package app.podium.sources.youtubemusic

import app.podium.core.common.Outcome
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Offsets over a list the catalogue pages by continuation tokens. Podium's screens ask for "the next
 * 25 after the first 50"; the catalogue only knows "what comes after this token". A cursor keeps the
 * items seen so far and the token for more, and fetches only what a request needs.
 */
internal class Cursor<T>(
    private val first: suspend () -> Outcome<YtmPage<T>>,
    private val more: suspend (YouTubeMusicClient.Continuation) -> Outcome<YtmPage<T>>,
    private val key: (T) -> Any = { it as Any },
) {
    private val lock = Mutex()
    private val items = ArrayList<T>()
    private val seen = HashSet<Any>()
    private var next: YouTubeMusicClient.Continuation? = null
    private var started = false

    val exhausted: Boolean get() = started && next == null

    suspend fun range(offset: Int, limit: Int, maxPages: Int = MAX_PAGES_PER_REQUEST): Outcome<List<T>> = lock.withLock {
        if (!started) {
            when (val r = first()) {
                is Outcome.Failure -> return@withLock r
                is Outcome.Success -> {
                    add(r.value.items)
                    next = r.value.continuation
                    started = true
                }
            }
        }
        var pages = 0
        while (items.size < offset + limit && pages < maxPages) {
            val token = next ?: break
            when (val r = more(token)) {
                is Outcome.Failure -> if (items.size > offset) break else return@withLock r
                is Outcome.Success -> {
                    val added = add(r.value.items)
                    // A page that adds nothing new (or repeats its own token) ends the list.
                    next = r.value.continuation?.takeIf { added > 0 && it != token }
                }
            }
            pages++
        }
        Outcome.Success(items.drop(offset).take(limit))
    }

    private fun add(page: List<T>): Int {
        var added = 0
        for (item in page) if (seen.add(key(item))) {
            items += item
            added++
        }
        return added
    }

    companion object {
        const val MAX_PAGES_PER_REQUEST = 12
    }
}

/** A small least-recently-used map of cursors (one per list being read), cleared when the account changes. */
internal class CursorCache(private val capacity: Int = 24) {
    private val map = object : LinkedHashMap<String, Cursor<*>>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cursor<*>>?) = size > capacity
    }

    @Suppress("UNCHECKED_CAST")
    @Synchronized
    fun <T> get(key: String, create: () -> Cursor<T>): Cursor<T> = map.getOrPut(key) { create() } as Cursor<T>

    @Synchronized
    fun clear() = map.clear()
}
