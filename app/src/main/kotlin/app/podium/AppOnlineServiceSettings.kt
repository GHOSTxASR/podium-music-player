package app.podium

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import app.podium.feature.settings.OnlineServiceSettings
import app.podium.feature.settings.OnlineServiceState
import app.podium.feature.settings.ServiceAccount
import app.podium.feature.settings.ServiceApp
import app.podium.feature.settings.ServiceChannel
import app.podium.core.common.Outcome
import app.podium.core.common.PodiumError
import app.podium.core.model.PlaybackRoute
import app.podium.player.api.RemoteAccess
import app.podium.player.remote.MediaSessionRemotePlayback
import app.podium.sources.api.AccountIdentity
import app.podium.sources.api.AuthState
import app.podium.sources.api.Basis
import app.podium.sources.api.MusicEnvironment
import app.podium.sources.api.MusicSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Settings ▸ the online service, over the registry (whether it's on, its account), the remote
 * playback (the app that plays its songs) and the device settings (hand-off behaviour).
 */
class AppOnlineServiceSettings(private val graph: AppGraph) : OnlineServiceSettings {

    private val context: Context get() = graph.context
    private fun service(): MusicSource? = graph.registry.registered(MusicEnvironment.ONLINE).firstOrNull()

    /** A channel switch under way, or why the last one didn't happen (D-86). */
    private data class Switch(val to: String? = null, val problem: String? = null)

    private val switch = MutableStateFlow(Switch())

    private val identities: StateFlow<List<AccountIdentity>>? get() = service()?.auth?.identities

    override val state: StateFlow<OnlineServiceState?> = combine(
        graph.registry.connectedSources,
        graph.remotePlayback.access,
        graph.deviceSettings.stayInPodium,
        identities ?: flowOf(emptyList()),
        switch,
    ) { sources, access, stay, channels, sw ->
        val s = sources.firstOrNull { it.descriptor.environment == MusicEnvironment.ONLINE } ?: return@combine null
        val signedIn = s.authenticationState is AuthState.SignedIn
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
            channels = if (signedIn) channels.map { ServiceChannel(it.id, it.name, it.detail, it.selected) } else emptyList(),
            switchingTo = sw.to,
            channelProblem = sw.problem,
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

    override fun refreshChannels() {
        val auth = service()?.auth ?: return
        if (auth.state.value !is AuthState.SignedIn) return
        graph.appScope.launch { auth.refreshIdentities() }
    }

    override fun selectChannel(id: String) {
        val auth = service()?.auth ?: return
        if (switch.value.to != null) return
        val name = auth.identities.value.firstOrNull { it.id == id }?.name ?: return
        val stayingOn = auth.identities.value.firstOrNull { it.selected }?.name
        switch.value = Switch(to = id)
        graph.appScope.launch {
            val result = auth.selectIdentity(id)
            switch.value = Switch(problem = (result as? Outcome.Failure)?.error?.let { channelProblem(it, name, stayingOn) })
        }
    }

    /** Why a switch to [name] didn't happen, and what to do next. */
    private fun channelProblem(error: PodiumError, name: String, stayingOn: String?): String = when (error) {
        PodiumError.Offline, is PodiumError.Network -> "Couldn't reach the service to switch to $name. Check the connection, then try again."
        is PodiumError.Unsupported -> "$name didn't answer as itself, so Podium stayed on ${stayingOn ?: "the current channel"}. Try again later."
        is PodiumError.RateLimited -> "The service asked Podium to wait. Try $name again in a minute."
        else -> "Couldn't switch to $name. Try again in a moment."
    }

    private fun start(intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            // No store or settings page for it on this phone: nothing to open.
        }
    }
}
