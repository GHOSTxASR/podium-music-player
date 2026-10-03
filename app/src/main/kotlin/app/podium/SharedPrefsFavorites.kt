package app.podium

import android.content.Context
import androidx.core.content.edit
import app.podium.core.model.TrackId
import app.podium.feature.nowplaying.FavoritesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Favorites by source-qualified identity, in a small private SharedPreferences file. Interim
 * storage until the library database lands with S4 (D-28), which will migrate this set.
 */
class SharedPrefsFavorites(context: Context) : FavoritesRepository {

    private val prefs = context.getSharedPreferences("favorites", Context.MODE_PRIVATE)
    private val _favorites = MutableStateFlow(
        prefs.getStringSet(KEY, emptySet()).orEmpty().mapNotNull { runCatching { TrackId(it) }.getOrNull() }.toSet(),
    )
    override val favorites: StateFlow<Set<TrackId>> = _favorites.asStateFlow()

    override fun toggle(id: TrackId) {
        val next = if (id in _favorites.value) _favorites.value - id else _favorites.value + id
        _favorites.value = next
        prefs.edit { putStringSet(KEY, next.map { it.value }.toSet()) }
    }

    private companion object {
        const val KEY = "track_ids"
    }
}
