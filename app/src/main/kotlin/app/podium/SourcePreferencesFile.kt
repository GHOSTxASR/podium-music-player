package app.podium

import android.content.Context
import androidx.core.content.edit
import app.podium.core.model.SourceId
import app.podium.feature.settings.OnlineSourceRow
import app.podium.feature.settings.OnlineSourceSettings
import app.podium.sources.api.AuthState
import app.podium.sources.api.ConnectedSource
import app.podium.sources.api.MusicEnvironment
import app.podium.sources.api.SourceHealth
import app.podium.sources.api.SourcePreferences
import app.podium.sources.api.SourcePreferencesStore
import app.podium.sources.api.SourceRegistry
import app.podium.sources.api.SourceSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Which sources are on and their order (D-35), in a small private SharedPreferences file — a few
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

/**
 * Settings ▸ Online sources over the registry (the one list of sources) and [SourceSettings] (the
 * one way choices change and persist). Generic: any online source the build registers shows up.
 */
class RegistryOnlineSourceSettings(
    private val registry: SourceRegistry,
    private val settings: SourceSettings,
    scope: CoroutineScope,
) : OnlineSourceSettings {

    override val sources: StateFlow<List<OnlineSourceRow>> = registry.connectedSources
        .map { all -> all.filter { it.descriptor.environment == MusicEnvironment.ONLINE }.map(::row) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    override fun setEnabled(id: String, enabled: Boolean) = settings.setEnabled(SourceId(id), enabled)

    override fun move(id: String, toIndex: Int) =
        settings.move(SourceId(id), toIndex, registry.registered(MusicEnvironment.ONLINE).map { it.descriptor.id })

    private fun row(s: ConnectedSource) = OnlineSourceRow(
        id = s.sourceId.value,
        name = s.descriptor.displayName,
        enabled = s.enabled,
        note = when {
            s.authenticationState is AuthState.Rejected || s.health is SourceHealth.AuthRejected -> "Sign in again"
            s.authenticationState == AuthState.SignedOut -> "Needs sign-in"
            s.health is SourceHealth.Unreachable -> "Can't reach it right now"
            s.health is SourceHealth.RateLimited -> "Busy, back soon"
            else -> null
        },
    )
}
