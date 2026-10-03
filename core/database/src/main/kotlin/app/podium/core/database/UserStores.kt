package app.podium.core.database

import androidx.room3.withWriteTransaction
import app.podium.core.model.TrackId
import app.podium.player.api.FavoritesRepository
import app.podium.player.api.QueueOrigin
import app.podium.player.api.QueueState
import app.podium.player.api.QueueStore
import app.podium.player.api.RepeatMode
import app.podium.player.api.SavedQueue
import app.podium.player.api.SavedQueueItem
import app.podium.player.api.toSaved
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Favorites in the database (`liked_track`). The set is held in memory for instant toggles and
 * written through; it's loaded once at start.
 */
class DatabaseFavorites(
    private val db: PodiumDatabase,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) : FavoritesRepository {

    private val dao = db.likes()
    private val _favorites = MutableStateFlow<Set<TrackId>>(emptySet())
    override val favorites: StateFlow<Set<TrackId>> = _favorites.asStateFlow()

    init {
        scope.launch {
            dao.likedIds().collect { ids -> _favorites.value = ids.mapNotNullTo(LinkedHashSet()) { runCatching { TrackId(it) }.getOrNull() } }
        }
    }

    override fun toggle(id: TrackId) {
        val liked = id !in _favorites.value
        _favorites.value = if (liked) _favorites.value + id else _favorites.value - id
        scope.launch {
            if (liked) dao.like(listOf(LikedTrackEntity(id.value, now()))) else dao.unlike(id.value)
        }
    }

    /** One-time import of favorites kept before the database existed. */
    suspend fun import(ids: Collection<TrackId>) {
        if (ids.isEmpty()) return
        val at = now()
        dao.like(ids.map { LikedTrackEntity(it.value, at) })
    }
}

/**
 * The saved queue (`queue_state`, `queue_item`). Songs in the queue are kept in `track` even if
 * they aren't (or stop being) in a library, so a restored queue always has its songs.
 */
class DatabaseQueueStore(
    private val db: PodiumDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) : QueueStore {

    private val dao = db.queue()
    private val library = db.library()

    override suspend fun load(): SavedQueue? {
        val state = dao.state() ?: return null
        val items = dao.items()
        if (items.isEmpty()) return null
        val tracks = items.map { it.trackId }.distinct().chunked(500).flatMap { library.tracks(it) }.associateBy { it.id }
        // A song that can't be found any more drops out; the current position follows its song.
        val restored = items.mapIndexedNotNull { i, item ->
            val track = tracks[item.trackId] ?: return@mapIndexedNotNull null
            i to SavedQueueItem(TrackMapping.toTrack(track), runCatching { QueueOrigin.valueOf(item.origin) }.getOrDefault(QueueOrigin.CONTEXT), item.originalOrdinal)
        }
        if (restored.isEmpty()) return null
        val current = restored.indexOfFirst { it.first >= state.currentIndex }.takeIf { it >= 0 } ?: restored.lastIndex
        val sameSong = restored[current].first == state.currentIndex
        return SavedQueue(
            items = restored.map { it.second },
            currentIndex = current,
            positionMs = if (sameSong) state.positionMs else 0L,
            repeatMode = runCatching { RepeatMode.valueOf(state.repeatMode) }.getOrDefault(RepeatMode.OFF),
            shuffleEnabled = state.shuffleEnabled,
            contextLabel = state.contextLabel,
        )
    }

    override suspend fun save(state: QueueState, positionMs: Long) {
        val saved = state.toSaved(positionMs)
        db.withWriteTransaction {
            dao.clearItems()
            if (saved == null) {
                dao.clearState()
                return@withWriteTransaction
            }
            val at = now()
            val tracks = saved.items.map { it.track }.distinctBy { it.id }
            library.insertSourcesIfMissing(tracks.map { it.source.sourceId.value }.distinct().map { SourceAccountEntity(it, it, lastSyncAt = null) })
            tracks.chunked(500).forEach { chunk -> library.insertTracksIfMissing(chunk.map { TrackMapping.toEntity(it, inLibrary = false, now = at) }) }
            dao.insertItems(saved.items.mapIndexed { i, item -> QueueItemEntity(i, item.originalOrder, item.track.id.value, item.origin.name) })
            dao.upsertState(
                QueueStateEntity(
                    currentIndex = saved.currentIndex,
                    positionMs = saved.positionMs,
                    repeatMode = saved.repeatMode.name,
                    shuffleEnabled = saved.shuffleEnabled,
                    contextLabel = saved.contextLabel,
                    updatedAt = at,
                ),
            )
        }
    }

    override suspend fun savePosition(currentIndex: Int, positionMs: Long) {
        dao.updatePosition(currentIndex, positionMs.coerceAtLeast(0), now())
    }
}
