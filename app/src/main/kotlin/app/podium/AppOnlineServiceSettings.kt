package app.podium

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import app.podium.feature.settings.OnlineServiceSettings
import app.podium.feature.settings.OnlineServiceState
import app.podium.feature.settings.ServiceAccount
import app.podium.feature.settings.ServiceApp
import app.podium.core.model.PlaybackRoute
import app.podium.player.api.RemoteAccess
import app.podium.player.remote.MediaSessionRemotePlayback
import app.podium.sources.api.AuthState
import app.podium.sources.api.Basis
import app.podium.sources.api.MusicEnvironment
import app.podium.sources.api.MusicSource
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Settings ▸ the online service, over the registry (whether it's on, its account), the remote
 * playback (the app that plays its songs) and the device settings (hand-off behaviour).
 */
class AppOnlineServiceSettings(private val graph: AppGraph) : OnlineServiceSettings {

    private val context: Context get() = graph.context
    private fun service(): MusicSource? = graph.registry.registered(MusicEnvironment.ONLINE).firstOrNull()

    override val state: StateFlow<OnlineServiceState?> = combine(
        graph.registry.connectedSources,
        graph.remotePlayback.access,
        graph.deviceSettings.stayInPodium,
    ) { sources, access, stay ->
        val s = sources.firstOrNull { it.descriptor.environment == MusicEnvironment.ONLINE } ?: return@combine null
        OnlineServiceState(
            name = s.descriptor.displayName,
            enabled = s.enabled,
            basisNote = if (s.descriptor.basis == Basis.UNOFFICIAL_API) "Unofficial — may stop working" else null,
            account = when (val a = s.authenticationState) {
                AuthState.NotRequired -> ServiceAccount.NotOffered
                AuthState.SignedOut, is AuthState.Rejected -> ServiceAccount.SignedOut
                AuthState.SigningIn -> ServiceAccount.SigningIn
                is AuthState.SignedIn -> ServiceAccount.SignedIn(a.accountName)
                AuthState.Expired -> ServiceAccount.Expired
            },
            // Only a service whose songs play in another app has an app to install or allow (by route).
            app = if (graph.registry.get(s.sourceId)?.playback?.routes?.contains(PlaybackRoute.DIRECT) != false) null else ServiceApp(
                name = s.descriptor.displayName,
                installed = access != RemoteAccess.NO_APP,
                controlAllowed = access == RemoteAccess.READY,
            ),
            stayInPodium = stay,
        )
    }.stateIn(graph.appScope, SharingStarted.Eagerly, null)

    override fun setEnabled(enabled: Boolean) {
        service()?.descriptor?.id?.let { graph.sourceSettings.setEnabled(it, enabled) }
    }

    override fun signIn() {
        val id = service()?.descriptor?.id ?: return
        start(WebSignInActivity.intent(context, id))
    }

    override fun signOut() {
        graph.appScope.launch { graph.online.signOut() }
    }

    override fun installApp() = start(MediaSessionRemotePlayback.installIntent(app.podium.sources.youtubemusic.YouTubeMusicSource.PROVIDER_APP_PACKAGE))

    override fun openApp() = graph.remotePlayback.openApp()

    override fun allowControl() = start(MediaSessionRemotePlayback.accessSettingsIntent(context))

    override fun openAppInfo() = start(MediaSessionRemotePlayback.appInfoIntent(context))

    override fun setStayInPodium(enabled: Boolean) = graph.deviceSettings.setStayInPodium(enabled)

    private fun start(intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            // No store or settings page for it on this phone: nothing to open.
        }
    }
}
