package app.podium.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
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
)

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
}

/** What the Settings row says about the service. */
fun onlineServiceSummary(state: OnlineServiceState?): String? = when {
    state == null -> null
    !state.enabled -> "Off"
    state.account is ServiceAccount.SignedIn -> state.account.name
    state.account is ServiceAccount.Expired -> "Sign in again"
    else -> "On"
}

private enum class ServiceRow { Enabled, Account, App, Control, AfterChoosing, Basis }

/**
 * Settings ▸ the online service: on/off, the account, the app that plays, media control, and what
 * happens after choosing a song. Center does the obvious thing; hold Center on the account to switch
 * or sign out.
 */
@Composable
fun OnlineServiceScreen(settings: OnlineServiceSettings) {
    val current by settings.state.collectAsStateWithLifecycle()
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
