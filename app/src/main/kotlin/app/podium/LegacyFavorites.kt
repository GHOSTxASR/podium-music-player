package app.podium

import android.content.Context
import app.podium.core.database.DatabaseFavorites
import app.podium.core.model.TrackId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Before the library database (D-31), favorites lived in a small SharedPreferences file. Move them
 * into the database once, then delete the file. Safe to run on every start: with no file it does
 * nothing, and an interrupted move is simply repeated (imports ignore duplicates).
 */
internal object LegacyFavorites {
    private const val FILE = "favorites"
    private const val KEY = "track_ids"

    fun moveInto(context: Context, favorites: DatabaseFavorites, scope: CoroutineScope) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY)) return
        val ids = prefs.getStringSet(KEY, emptySet()).orEmpty().mapNotNull { runCatching { TrackId(it) }.getOrNull() }
        scope.launch {
            favorites.import(ids)
            context.deleteSharedPreferences(FILE)
        }
    }
}
