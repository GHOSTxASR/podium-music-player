package app.podium.player.api

import app.podium.core.model.QueueUid
import app.podium.core.model.Track
import app.podium.sources.api.resolve.FallbackPolicy
import app.podium.sources.api.resolve.Selection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

/**
 * The single writer of the queue (ADR-006). Pure Kotlin: every mutation returns the [QueueOp]s the
 * playback engine must apply so the player's playlist mirrors this state exactly.
 *
 * Not thread-safe by design: the playback service calls it from one thread (the application looper).
 */
class QueueManager(
    private val newUid: () -> QueueUid = UidFactory()::next,
    private val keepUserQueueOnNewContext: Boolean = true,
) {
    private val _state = MutableStateFlow(QueueState())
    val state: StateFlow<QueueState> = _state.asStateFlow()

    private var shuffleSeed: Long = 0L

    private val s: QueueState get() = _state.value

    fun item(uid: QueueUid): QueueItem? = s.items.firstOrNull { it.uid == uid }
    fun indexOf(uid: QueueUid): Int = s.items.indexOfFirst { it.uid == uid }

    /** Start playing a context (album, playlist, list) from [startIndex]. */
    fun setContext(
        tracks: List<Track>,
        startIndex: Int,
        context: PlayContext?,
        shuffle: Boolean = false,
        seed: Long = Random.nextLong(),
        fallbackPolicy: FallbackPolicy = FallbackPolicy.EXACT_ONLY,
    ): List<QueueOp> {
        require(tracks.isNotEmpty()) { "A context needs at least one track" }
        require(startIndex in tracks.indices) { "startIndex out of range" }
        val keptUser = if (keepUserQueueOnNewContext) {
            s.upNext.filter { it.origin == QueueOrigin.PLAY_NEXT || it.origin == QueueOrigin.USER_QUEUED }
        } else emptyList()

        val contextItems = tracks.mapIndexed { i, t ->
            QueueItem(newUid(), t, QueueOrigin.CONTEXT, fallbackPolicy = fallbackPolicy, originalOrder = i)
        }
        val items: List<QueueItem>
        val current: Int
        if (shuffle) {
            shuffleSeed = seed
            val start = contextItems[startIndex]
            val rest = shuffled(contextItems.filterIndexed { i, _ -> i != startIndex }, seed)
            items = listOf(start) + keptUser + rest
            current = 0
        } else {
            items = contextItems.subList(0, startIndex + 1) + keptUser + contextItems.drop(startIndex + 1)
            current = startIndex
        }
        _state.value = s.copy(items = items, currentIndex = current, shuffleEnabled = shuffle, context = context)
        checkInvariants()
        return listOf(QueueOp.ReplaceAll(items, current))
    }

    /** Insert right after the current item ("Play Next"); newest first. */
    fun playNext(tracks: List<Track>): List<QueueOp> {
        if (tracks.isEmpty()) return emptyList()
        if (s.isEmpty) return setContext(tracks, 0, null)
        val newItems = tracks.map { QueueItem(newUid(), it, QueueOrigin.PLAY_NEXT) }
        return insert(s.currentIndex + 1, newItems)
    }

    /** Append after the user's queued items, before the rest of the context ("Add to Queue"). */
    fun addToQueue(tracks: List<Track>): List<QueueOp> {
        if (tracks.isEmpty()) return emptyList()
        if (s.isEmpty) return setContext(tracks, 0, null)
        val newItems = tracks.map { QueueItem(newUid(), it, QueueOrigin.USER_QUEUED) }
        var index = s.currentIndex + 1
        while (index < s.items.size &&
            (s.items[index].origin == QueueOrigin.PLAY_NEXT || s.items[index].origin == QueueOrigin.USER_QUEUED)
        ) index++
        return insert(index, newItems)
    }

    private fun insert(index: Int, newItems: List<QueueItem>): List<QueueOp> {
        val items = s.items.toMutableList().apply { addAll(index, newItems) }
        val current = if (index <= s.currentIndex) s.currentIndex + newItems.size else s.currentIndex
        _state.value = s.copy(items = items, currentIndex = current)
        checkInvariants()
        return listOf(QueueOp.Insert(index, newItems))
    }

    /** Move an upcoming item. Its origin joins the section it lands in, keeping section order. */
    fun move(uid: QueueUid, toIndex: Int): List<QueueOp> {
        val from = indexOf(uid)
        // Only upcoming items move; history and the current item are fixed.
        if (from <= s.currentIndex || s.currentIndex + 1 > s.items.lastIndex) return emptyList()
        val target = toIndex.coerceIn(s.currentIndex + 1, s.items.lastIndex)
        if (from == target) return emptyList()
        val items = s.items.toMutableList()
        val moved = items.removeAt(from)
        items.add(target, moved)
        val before = items.getOrNull(target - 1)?.takeIf { target - 1 > s.currentIndex }
        val after = items.getOrNull(target + 1)
        val newOrigin = before?.origin ?: after?.origin ?: moved.origin
        items[target] = moved.copy(origin = newOrigin)
        _state.value = s.copy(items = items)
        checkInvariants()
        return listOf(QueueOp.Move(from, target))
    }

    /** Remove items. Removing the current item makes the next one current (the engine skips). */
    fun remove(uids: Set<QueueUid>): List<QueueOp> {
        val ops = mutableListOf<QueueOp>()
        var items = s.items
        var current = s.currentIndex
        for (uid in uids) {
            val index = items.indexOfFirst { it.uid == uid }
            if (index < 0) continue
            items = items.toMutableList().apply { removeAt(index) }
            if (index < current) current-- else if (index == current && current >= items.size) current = items.lastIndex
            ops += QueueOp.Remove(index, 1)
        }
        _state.value = s.copy(items = items, currentIndex = if (items.isEmpty()) -1 else current)
        checkInvariants()
        return ops
    }

    /** Remove everything after the current item. */
    fun clearUpcoming(): List<QueueOp> {
        val count = s.upNext.size
        if (count == 0) return emptyList()
        val from = s.currentIndex + 1
        _state.value = s.copy(items = s.items.subList(0, from))
        checkInvariants()
        return listOf(QueueOp.Remove(from, count))
    }

    /** Jump to an item; items in between stay in the list as history (iPod behaviour). */
    fun skipTo(uid: QueueUid): List<QueueOp> {
        val index = indexOf(uid)
        if (index < 0) return emptyList()
        _state.value = s.copy(currentIndex = index)
        return listOf(QueueOp.SeekTo(index))
    }

    /** The player advanced or was moved (auto-advance, next/previous, system controls). */
    fun onCurrentChanged(uid: QueueUid) {
        val index = indexOf(uid)
        if (index >= 0 && index != s.currentIndex) _state.value = s.copy(currentIndex = index)
    }

    fun setRepeat(mode: RepeatMode) {
        _state.value = s.copy(repeatMode = mode)
    }

    /**
     * Shuffle is a visible reorder of upcoming CONTEXT items (user-queued items stay first, in
     * order). Turning it off restores the original context order for items not yet played.
     */
    fun setShuffle(enabled: Boolean, seed: Long = Random.nextLong()): List<QueueOp> {
        if (enabled == s.shuffleEnabled) return emptyList()
        val from = s.currentIndex + 1
        val upcoming = s.upNext
        val user = upcoming.filter { it.origin == QueueOrigin.PLAY_NEXT || it.origin == QueueOrigin.USER_QUEUED }
        val context = upcoming.filter { it.origin == QueueOrigin.CONTEXT }
        val auto = upcoming.filter { it.origin == QueueOrigin.AUTOPLAY }
        val reordered = if (enabled) {
            shuffleSeed = seed
            shuffled(context, seed)
        } else {
            context.sortedBy { it.originalOrder }
        }
        val newUpcoming = user + reordered + auto
        _state.value = s.copy(items = s.items.subList(0, from) + newUpcoming, shuffleEnabled = enabled)
        checkInvariants()
        return if (newUpcoming.isEmpty()) emptyList() else listOf(QueueOp.ReplaceRange(from, s.items.size, newUpcoming))
    }

    /** Record the resolved selection for an item: the pin that prevents re-resolution. */
    fun pin(uid: QueueUid, selection: Selection) {
        val index = indexOf(uid)
        if (index < 0) return
        val items = s.items.toMutableList()
        items[index] = items[index].copy(selection = selection)
        _state.value = s.copy(items = items)
    }

    /** Drop a pin (e.g. the pinned URL expired before playback started). */
    fun unpin(uid: QueueUid) {
        val index = indexOf(uid)
        if (index < 0 || s.items[index].selection == null) return
        val items = s.items.toMutableList()
        items[index] = items[index].copy(selection = null)
        _state.value = s.copy(items = items)
    }

    /** Fisher–Yates with a seed, then spread same-artist neighbours apart when possible. */
    private fun shuffled(items: List<QueueItem>, seed: Long): List<QueueItem> {
        val out = items.toMutableList()
        val random = Random(seed)
        for (i in out.lastIndex downTo 1) {
            val j = random.nextInt(i + 1)
            val tmp = out[i]; out[i] = out[j]; out[j] = tmp
        }
        for (i in 1 until out.size) {
            if (out[i].track.artistDisplay != out[i - 1].track.artistDisplay) continue
            val swapWith = (i + 1 until minOf(out.size, i + 6)).firstOrNull {
                out[it].track.artistDisplay != out[i - 1].track.artistDisplay
            } ?: continue
            val tmp = out[i]; out[i] = out[swapWith]; out[swapWith] = tmp
        }
        return out
    }

    /**
     * Invariants (queue-and-autoplay.md §2), asserted after every mutation.
     * Section order is a property of *insertion* (Play Next, Add to Queue land before the context),
     * not a global invariant: going back into history legitimately makes played context items
     * upcoming again, ahead of user-queued items. Up Next is shown as contiguous runs in play order.
     */
    fun checkInvariants() {
        val st = s
        check(st.items.isEmpty() == (st.currentIndex == -1)) { "current index must exist iff queue is non-empty" }
        if (st.items.isNotEmpty()) check(st.currentIndex in st.items.indices) { "current index out of range" }
        check(st.items.map { it.uid }.toSet().size == st.items.size) { "queue uids must be unique" }
    }

    private class UidFactory {
        private var n = 0L
        fun next(): QueueUid = QueueUid("q${++n}")
    }
}
