package app.podium

import android.content.Context
import androidx.core.content.edit
import app.podium.core.model.SourceId
import app.podium.feature.settings.OnlineSourceRow
import app.podium.feature.settings.OnlineSourceSettings
import app.podium.sources.api.AuthState
import app.podium.sources.api.ConfiguredSources
import app.podium.sources.api.ConnectResult
import app.podium.sources.api.SetupForm
import app.podium.sources.api.SetupProblem
import app.podium.sources.api.SignInResult
import app.podium.sources.api.SourceHealthMonitor
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
import kotlinx.coroutines.launch

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
    private val configured: ConfiguredSources,
    private val health: SourceHealthMonitor,
    private val scope: CoroutineScope,
) : OnlineSourceSettings {

    override val sources: StateFlow<List<OnlineSourceRow>> = registry.connectedSources
        .map { all -> all.filter { it.descriptor.environment == MusicEnvironment.ONLINE }.map(::row) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    override fun setEnabled(id: String, enabled: Boolean) = settings.setEnabled(SourceId(id), enabled)

    override fun move(id: String, toIndex: Int) =
        settings.move(SourceId(id), toIndex, registry.registered(MusicEnvironment.ONLINE).map { it.descriptor.id })

    override val addable: List<SetupForm> get() = configured.forms

    override fun form(key: String): SetupForm? {
        configured.forms.firstOrNull { it.kind == key }?.let { return it }
        val id = key.removePrefix(SIGN_IN).takeIf { key.startsWith(SIGN_IN) } ?: return null
        val source = registry.get(SourceId(id)) ?: return null
        val fields = source.auth?.signInFields?.takeIf { it.isNotEmpty() } ?: return null
        return SetupForm(kind = key, title = "Sign in to ${source.descriptor.displayName}", fields = fields, submitLabel = "Sign in")
    }

    override suspend fun submit(form: SetupForm, values: Map<String, String>): SetupProblem? {
        if (form.kind.startsWith(SIGN_IN)) {
            val id = SourceId(form.kind.removePrefix(SIGN_IN))
            val auth = registry.get(id)?.auth ?: return SetupProblem.NOT_SUPPORTED
            return when (val r = auth.signIn(values)) {
                SignInResult.SignedIn -> {
                    health.reset(id) // a refused sign-in left it rejected; that's over now
                    null
                }
                is SignInResult.Refused -> r.problem
            }
        }
        return when (val r = configured.add(form.kind, values)) {
            is ConnectResult.Connected -> null
            is ConnectResult.Refused -> r.problem
        }
    }

    override fun signOut(id: String) {
        val source = registry.get(SourceId(id)) ?: return
        scope.launch { source.auth?.signOut() }
    }

    override fun remove(id: String) = configured.remove(SourceId(id))

    private fun row(s: ConnectedSource) = OnlineSourceRow(
        id = s.sourceId.value,
        name = s.descriptor.displayName,
        enabled = s.enabled,
        note = when {
            s.authenticationState is AuthState.Rejected || s.health is SourceHealth.AuthRejected -> "Sign in again"
            s.health is SourceHealth.Unreachable -> "Can't reach it right now"
            s.health is SourceHealth.RateLimited -> "Busy, back soon"
            else -> null
        },
        removable = configured.isConfigured(s.sourceId),
        signedIn = when (s.authenticationState) {
            AuthState.NotRequired -> null
            is AuthState.SignedIn, AuthState.SigningIn -> s.health !is SourceHealth.AuthRejected
            else -> false
        }.takeIf { registry.get(s.sourceId)?.auth?.signInFields?.isNotEmpty() == true },
    )

    private companion object {
        val SIGN_IN = OnlineSourceSettings.signInKey("")
    }
}
