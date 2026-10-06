package app.podium

import app.podium.core.database.LibraryStore
import app.podium.core.database.OnlineLibraryStore
import app.podium.core.model.SourceId
import app.podium.core.model.Track
import app.podium.core.model.TrackId
import app.podium.player.api.FavoritesRepository
import app.podium.player.api.PlayIntent
import app.podium.player.api.PlaybackController
import app.podium.player.api.TrackCatalog
import app.podium.sources.api.MusicEnvironment
import app.podium.sources.api.SourceRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Which environment a song belongs to, by its source (D-34). */
class Environments(private val registry: SourceRegistry) {
    fun of(id: TrackId): MusicEnvironment = registry.get(id.sourceId)?.descriptor?.environment ?: MusicEnvironment.LOCAL
    fun isOnline(id: TrackId) = of(id) == MusicEnvironment.ONLINE
}

/**
 * The heart on Now Playing (D-34): liking a local song changes local favorites; liking an online
 * song changes ONLINE's liked songs. Neither ever touches the other.
 */
class EnvironmentFavorites(
    private val local: FavoritesRepository,
    private val online: AppOnlineRepository,
    private val environments: Environments,
    private val lookup: suspend (TrackId) -> Track?,
    private val scope: CoroutineScope,
) : FavoritesRepository {

    override val favorites: StateFlow<Set<TrackId>> = combine(local.favorites, online.likedIds) { a, b -> a + b }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    override fun toggle(id: TrackId) {
        if (!environments.isOnline(id)) {
            local.toggle(id)
            return
        }
        scope.launch {
            val track = lookup(id) ?: return@launch
            online.setLiked(track, id !in online.likedIds.value)
        }
    }
}

/**
 * Online listening history (D-34, D-35): records each online song heard — the song the listener
 * chose, with its own source; when it started; how long it played; and, if another source's EXACT
 * copy served it, which source — in ONLINE's history. Local songs are never recorded here.
 */
class OnlineHistoryRecorder(
    private val controller: PlaybackController,
    private val store: OnlineLibraryStore,
    private val environments: Environments,
    private val catalog: TrackCatalog,
    private val library: LibraryStore,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var current: TrackId? = null
    private var servedBy: SourceId? = null
    private var startedAt = 0L
    private var heard = 0L
    private var lastPosition = 0L

    fun start() {
        scope.launch {
            controller.snapshot.collect { snapshot ->
                val id = snapshot.item?.trackId
                if (id != current) {
                    finish()
                    current = id?.takeIf { environments.isOnline(it) }
                    servedBy = null
                    startedAt = now()
                    heard = 0L
                    lastPosition = 0L
                }
                // Who serves it is known once it has been resolved; the last word wins.
                if (id == current) snapshot.item?.servedBy?.let { servedBy = it }
            }
        }
        // Count time actually heard: position advances while playing (seeks don't count).
        scope.launch {
            while (isActive) {
                delay(TICK_MS)
                if (current == null || controller.snapshot.value.intent != PlayIntent.PLAY) continue
                val position = controller.positionMs()
                val step = position - lastPosition
                if (step in 1..(TICK_MS * 3)) heard += step
                lastPosition = position
            }
        }
    }

    private fun finish() {
        val id = current ?: return
        val played = heard
        val at = startedAt
        val server = servedBy
        if (played < MIN_LISTEN_MS) return
        scope.launch {
            val track = catalog.cached(id) ?: library.track(id) ?: return@launch
            store.record(track, at, played, servedBy = server)
        }
    }

    private companion object {
        const val TICK_MS = 2_000L
        const val MIN_LISTEN_MS = 5_000L
    }
}
