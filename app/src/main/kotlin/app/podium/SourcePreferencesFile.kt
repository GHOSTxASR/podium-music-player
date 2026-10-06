package app.podium

import android.content.Context
import androidx.core.content.edit
import app.podium.core.model.SourceId
import app.podium.sources.api.SourcePreferences
import app.podium.sources.api.SourcePreferencesStore

/**
 * Which sources are on (D-35), in a small private SharedPreferences file — a few
 * values, written synchronously so a choice survives the process dying right after it's made.
 */
class SharedPrefsSourcePreferences(context: Context) : SourcePreferencesStore {

    private val prefs = context.getSharedPreferences("sources", Context.MODE_PRIVATE)

    override fun load(): SourcePreferences = SourcePreferences(
        enabled = prefs.getStringSet(KEY_ON, emptySet()).orEmpty().associate { SourceId(it) to true } +
            prefs.getStringSet(KEY_OFF, emptySet()).orEmpty().associate { SourceId(it) to false },
        priority = prefs.getString(KEY_PRIORITY, null)?.split('\n')?.filter { it.isNotBlank() }?.map(::SourceId).orEmpty(),
    )

    override fun save(preferences: SourcePreferences) {
        prefs.edit(commit = true) {
            putStringSet(KEY_ON, preferences.enabled.filterValues { it }.keys.map { it.value }.toSet())
            putStringSet(KEY_OFF, preferences.enabled.filterValues { !it }.keys.map { it.value }.toSet())
            putString(KEY_PRIORITY, preferences.priority.joinToString("\n") { it.value })
        }
    }

    private companion object {
        const val KEY_ON = "enabled"
        const val KEY_OFF = "disabled"
        const val KEY_PRIORITY = "priority"
    }
}
