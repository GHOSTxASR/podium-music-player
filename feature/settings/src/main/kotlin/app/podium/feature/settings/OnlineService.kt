package app.podium.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.podium.core.designsystem.component.FocusList
import app.podium.core.designsystem.component.ListInputEffect
import app.podium.core.designsystem.component.LocalOverlayHost
import app.podium.core.designsystem.component.LocalScreenInsets
import app.podium.core.designsystem.component.MenuAction
import app.podium.core.designsystem.component.MenuPreview
import app.podium.core.designsystem.component.MenuRow
import app.podium.core.designsystem.component.MenuSpec
import app.podium.core.designsystem.component.MessageState
import app.podium.core.designsystem.symbol.PodiumSymbol
import app.podium.core.interaction.rememberFocusListState
import app.podium.core.interaction.rememberPodiumHaptics
import kotlinx.coroutines.flow.StateFlow

/**
 * The online music service as Settings shows it (YOUTUBE_MUSIC_ARCHITECTURE.md §10). Settings is the
 * one place the service is named; browsing never is (D-35). Everything here is generic: an account,
 * the app that plays its songs, and how a hand-off to that app behaves.
 */
data class OnlineServiceState(
    val name: String,
    val enabled: Boolean,
    /** "Unofficial — may stop working" for a service reached without an official API (D-19). */
    val basisNote: String?,
    val account: ServiceAccount,
    /** The app that plays the service's songs; null when they play in Podium itself. */
    val app: ServiceApp?,
    /** Bring Podium back to the front after handing a song to the app. */
    val stayInPodium: Boolean,
    /** The account's channels (D-86), when it has more than its own. */
    val channels: List<ServiceChannel> = emptyList(),
    /** The channel being switched to, while that happens. */
    val switchingTo: String? = null,
    /** Why the last switch didn't happen, and what to do (shown in the channel list). */
    val channelProblem: String? = null,
    /** The account's channels are being asked for. */
    val channelsLoading: Boolean = false,
    /** Why the channels couldn't be listed, and what to do. */
    val channelListProblem: String? = null,
)

/** One channel of the signed-in account (D-86). */
data class ServiceChannel(val id: String, val name: String, val detail: String?, val selected: Boolean)

sealed interface ServiceAccount {
    data object NotOffered : ServiceAccount
    data object SignedOut : ServiceAccount
    data object SigningIn : ServiceAccount
    data class SignedIn(val name: String) : ServiceAccount
    data object Expired : ServiceAccount
}

/** The app that plays the service's songs (the service's own official app). */
data class ServiceApp(
    val name: String,
    val installed: Boolean,
    /** The listener allowed Podium to see and control that app's playback. */
    val controlAllowed: Boolean,
)

interface OnlineServiceSettings {
    val state: StateFlow<OnlineServiceState?>
    fun setEnabled(enabled: Boolean)
    fun signIn()

    /** Sign out: that account's things are removed from this phone; local music is untouched. */
    fun signOut()
    fun installApp()
    fun openApp()

    /** Where the listener allows media control (the system's notification-access page). */
    fun allowControl()

    /** Podium's App info, where Android lets a sideloaded app use restricted settings. */
    fun openAppInfo()
    fun setStayInPodium(enabled: Boolean)

    /** Ask the service for the account's channels (the Settings screen opening). */
    fun refreshChannels() {}

    /** Act as channel [id] from now on (D-86). */
    fun selectChannel(id: String) {}
}

/** What the Settings row says about the service. */
fun onlineServiceSummary(state: OnlineServiceState?): String? = when {
    state == null -> null
    !state.enabled -> "Off"
    state.account is ServiceAccount.SignedIn -> state.account.name
    state.account is ServiceAccount.Expired -> "Sign in again"
    else -> "On"
}

private enum class ServiceRow { Enabled, Account, Channel, App, Control, AfterChoosing, Basis }

/**
 * Settings ▸ the online service: on/off, the account (and its channel, when it has several), the
 * app that plays, media control, and what happens after choosing a song. Center does the obvious
 * thing; hold Center on the account to switch or sign out.
 */
@Composable
fun OnlineServiceScreen(settings: OnlineServiceSettings, onChannels: () -> Unit = {}) {
    val current by settings.state.collectAsStateWithLifecycle()
    val signedIn = current?.account is ServiceAccount.SignedIn
    LaunchedEffect(signedIn) { if (signedIn) settings.refreshChannels() }
    val haptics = rememberPodiumHaptics()
    val overlay = LocalOverlayHost.current
    val state = current
    if (state == null) {
        val insets = LocalScreenInsets.current
        Box(Modifier.fillMaxSize().padding(top = insets.top, bottom = insets.bottom), contentAlignment = Alignment.Center) {
            MessageState(PodiumSymbol.Offline, "No online music", "This version of Podium has no online music service.")
        }
        return
    }
    val rows = ServiceRow.entries.filter {
        when (it) {
            ServiceRow.Account -> state.account != ServiceAccount.NotOffered
            // Always there when signed in (issue #4): an account's channels can't all be seen from here.
            ServiceRow.Channel -> state.account is ServiceAccount.SignedIn
            ServiceRow.App, ServiceRow.AfterChoosing -> state.app != null
            ServiceRow.Control -> state.app?.installed == true
            ServiceRow.Basis -> state.basisNote != null
            else -> true
        }
    }
    val accountMenu: () -> Unit = {
        when (val a = state.account) {
            is ServiceAccount.SignedIn -> overlay.show(
                MenuSpec(
                    a.name,
                    listOf(
                        MenuAction("Switch channel") { onChannels() },
                        MenuAction("Switch account") { settings.signOut(); settings.signIn() },
                        MenuAction("Sign out") {
                            overlay.show(
                                MenuSpec(
                                    "Sign out of ${state.name}?",
                                    listOf(
                                        MenuAction("Sign out") { settings.signOut() },
                                        MenuAction("Stay signed in") {},
                                    ),
                                ),
                            )
                        },
                    ),
                ),
            )
            ServiceAccount.SigningIn -> haptics.reject()
            else -> settings.signIn()
        }
    }
    val activate: (Int) -> Unit = { i ->
        when (rows.getOrNull(i)) {
            ServiceRow.Enabled -> {
                haptics.confirm()
                settings.setEnabled(!state.enabled)
            }
            ServiceRow.Account -> if (!state.enabled) haptics.reject() else accountMenu()
            ServiceRow.Channel -> if (!state.enabled) haptics.reject() else onChannels()
            ServiceRow.App -> if (state.app?.installed == true) settings.openApp() else settings.installApp()
            ServiceRow.Control -> if (state.app?.controlAllowed == true) haptics.reject() else settings.allowControl()
            ServiceRow.AfterChoosing -> {
                haptics.confirm()
                settings.setStayInPodium(!state.stayInPodium)
            }
            ServiceRow.Basis, null -> haptics.reject()
        }
    }
    val longPress: (Int) -> Unit = { i ->
        when (rows.getOrNull(i)) {
            ServiceRow.Account -> accountMenu()
            ServiceRow.Control -> overlay.show(
                MenuSpec(
                    "Media controls",
                    listOf(
                        MenuAction("Open the setting") { settings.allowControl() },
                        // Android 13+ guards this setting for apps not installed from a store.
                        MenuAction("If it's restricted: App info, then allow restricted settings") { settings.openAppInfo() },
                    ),
                ),
            )
            else -> haptics.reject()
        }
    }
    val focus = rememberFocusListState("online-service")
    ListInputEffect(focus, onActivate = activate, onLongPress = longPress)
    FocusList(
        items = rows,
        state = focus,
        key = { it },
        contentPadding = LocalScreenInsets.current.listPadding(),
        onActivate = activate,
        onLongPress = longPress,
        preview = { MenuPreview.Instrument },
        modifier = Modifier.fillMaxSize(),
    ) { row, _, focused ->
        when (row) {
            ServiceRow.Enabled -> MenuRow("Online music", focused, value = if (state.enabled) "On" else "Off", showChevron = false)
            ServiceRow.Account -> MenuRow(
                "Account",
                focused,
                value = when (val a = state.account) {
                    is ServiceAccount.SignedIn -> a.name
                    ServiceAccount.SigningIn -> "Signing in"
                    ServiceAccount.Expired -> "Sign in again"
                    else -> "Sign in"
                },
                enabled = state.enabled,
            )
            ServiceRow.Channel -> MenuRow(
                "Channel",
                focused,
                value = when {
                    state.switchingTo != null -> "Switching"
                    else -> state.channels.firstOrNull { it.selected }?.name ?: "Choose"
                },
                enabled = state.enabled,
            )
            ServiceRow.App -> MenuRow(state.app?.name.orEmpty(), focused, value = if (state.app?.installed == true) "Installed" else "Install")
            ServiceRow.Control -> MenuRow(
                "Media controls",
                focused,
                value = if (state.app?.controlAllowed == true) "Allowed" else "Allow",
                showChevron = state.app?.controlAllowed != true,
            )
            ServiceRow.AfterChoosing -> MenuRow(
                "After choosing a song",
                focused,
                value = if (state.stayInPodium) "Stay here" else "Show ${state.app?.name.orEmpty()}",
                showChevron = false,
            )
            ServiceRow.Basis -> MenuRow(state.basisNote.orEmpty(), focused, enabled = false, showChevron = false)
        }
    }
}

/**
 * Settings ▸ the online service ▸ Channel (D-86): the signed-in account's channels, the one Podium
 * acts as checked. Choosing another switches to it — its library, likes and history are its own;
 * the check moves only once the service has answered as that channel.
 */
@Composable
fun ServiceChannelScreen(settings: OnlineServiceSettings) {
    val current by settings.state.collectAsStateWithLifecycle()
    val haptics = rememberPodiumHaptics()
    LaunchedEffect(Unit) { settings.refreshChannels() }
    val state = current
    val channels = state?.channels.orEmpty()
    val problem = state?.channelProblem ?: state?.channelListProblem ?: when {
        state?.channelsLoading == true && channels.isEmpty() -> "Asking for this account's channels…"
        state?.channelsLoading != true && channels.size == 1 ->
            "Only ${channels.single().name} came back for this account. If your channel is missing, check on YouTube that it belongs to this Google account, then come back."
        else -> null
    }
    val focus = rememberFocusListState("online-channel")
    val activate: (Int) -> Unit = { i ->
        val channel = channels.getOrNull(i)
        when {
            channel == null || state?.switchingTo != null -> haptics.reject()
            channel.selected && problem == null -> haptics.reject()
            else -> {
                haptics.confirm()
                settings.selectChannel(channel.id)
            }
        }
    }
    ListInputEffect(focus, onActivate = activate)
    FocusList(
        items = channels.map { it.id } + listOfNotNull(problem?.let { PROBLEM_ROW }),
        state = focus,
        key = { it },
        contentPadding = LocalScreenInsets.current.listPadding(),
        onActivate = activate,
        preview = { MenuPreview.Instrument },
        modifier = Modifier.fillMaxSize(),
    ) { id, _, focused ->
        val channel = channels.firstOrNull { it.id == id }
        if (channel == null) {
            MenuRow(problem.orEmpty(), focused, enabled = false, showChevron = false)
        } else {
            MenuRow(
                channel.name,
                focused,
                value = if (state?.switchingTo == channel.id) "Switching" else channel.detail,
                selected = channel.selected,
                showChevron = false,
            )
        }
    }
}

private const val PROBLEM_ROW = "\u0000problem"
