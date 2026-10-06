package app.podium.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import app.podium.sources.api.SetupForm
import app.podium.sources.api.SetupProblem
import kotlinx.coroutines.flow.StateFlow

/**
 * One online source as Settings shows it (D-35, D-37): whatever the source is, the same few facts —
 * its name, whether it's on, whether it's signed in, and, when something needs the listener, a short
 * note. Settings is the one place a source is named; music browsing never is.
 */
data class OnlineSourceRow(
    val id: String,
    val name: String,
    val enabled: Boolean,
    /** Why it can't help right now, in sentence case ("Can't reach it right now"); null when fine. */
    val note: String? = null,
    /** The listener added it (a server): it can be removed. */
    val removable: Boolean = false,
    /** Null: it doesn't sign in. True: signed in. False: needs signing in. */
    val signedIn: Boolean? = null,
)

/** The online sources this build has, in the order Podium prefers them, and the listener's choices. */
interface OnlineSourceSettings {
    val sources: StateFlow<List<OnlineSourceRow>>
    fun setEnabled(id: String, enabled: Boolean)

    /** Move a source to [toIndex] in the preferred order (only matters when there's more than one). */
    fun move(id: String, toIndex: Int)

    /** Kinds of source the listener can add ("Add a music server"), each with its own form. */
    val addable: List<SetupForm> get() = emptyList()

    /** The form behind [key]: an add form (its kind) or a source's sign-in form ([signInKey]). */
    fun form(key: String): SetupForm? = null

    /** Send a form. Null when it worked; otherwise what went wrong, for the form to explain. */
    suspend fun submit(form: SetupForm, values: Map<String, String>): SetupProblem? = SetupProblem.NOT_SUPPORTED

    fun signOut(id: String) {}

    /** Forget a source the listener added: its secrets, its choices, the source itself. */
    fun remove(id: String) {}

    companion object {
        fun signInKey(sourceId: String) = "signin|$sourceId"
    }
}

/** What the "Online sources" row says: how many are on, without naming any. */
fun onlineSourcesSummary(rows: List<OnlineSourceRow>): String? {
    if (rows.isEmpty()) return null
    val on = rows.count { it.enabled }
    return when (on) {
        0 -> "Off"
        rows.size -> "On"
        else -> "$on of ${rows.size} on"
    }
}

private sealed interface SourcesRow {
    data class Source(val row: OnlineSourceRow) : SourcesRow
    data class Add(val form: SetupForm) : SourcesRow
}

/**
 * Settings ▸ Online sources: every online source, then a row per kind the listener can add. Center
 * turns a source on or off (or, signed out, asks to sign in); hold Center for its other actions —
 * move up/down, sign in or out, remove. Searching, playing and recommending never ask which.
 */
@Composable
fun OnlineSourcesScreen(settings: OnlineSourceSettings, onOpenForm: (String) -> Unit) {
    val sources by settings.sources.collectAsStateWithLifecycle()
    val haptics = rememberPodiumHaptics()
    val overlay = LocalOverlayHost.current
    val rows = remember(sources, settings.addable) { sources.map(SourcesRow::Source) + settings.addable.map(SourcesRow::Add) }
    val focus = rememberFocusListState("online-sources")
    val activate: (Int) -> Unit = { i ->
        when (val r = rows.getOrNull(i)) {
            is SourcesRow.Source -> if (r.row.signedIn == false) {
                onOpenForm(OnlineSourceSettings.signInKey(r.row.id))
            } else {
                haptics.confirm()
                settings.setEnabled(r.row.id, !r.row.enabled)
            }
            is SourcesRow.Add -> onOpenForm(r.form.kind)
            null -> Unit
        }
    }
    val longPress: (Int) -> Unit = { i ->
        val r = rows.getOrNull(i) as? SourcesRow.Source
        val position = sources.indexOfFirst { it.id == r?.row?.id }
        val actions = if (r == null) emptyList() else buildList {
            if (position > 0) add(MenuAction("Move up") { settings.move(r.row.id, position - 1) })
            if (position in 0 until sources.lastIndex) add(MenuAction("Move down") { settings.move(r.row.id, position + 1) })
            when (r.row.signedIn) {
                true -> add(MenuAction("Sign out") { settings.signOut(r.row.id) })
                false -> add(MenuAction("Sign in") { onOpenForm(OnlineSourceSettings.signInKey(r.row.id)) })
                null -> Unit
            }
            if (r.row.removable) add(MenuAction("Remove") { settings.remove(r.row.id) })
        }
        if (actions.isEmpty()) haptics.reject() else overlay.show(MenuSpec(r!!.row.name, actions))
    }
    ListInputEffect(focus, onActivate = activate, onLongPress = longPress)
    if (rows.isEmpty()) {
        val insets = LocalScreenInsets.current
        Box(Modifier.fillMaxSize().padding(top = insets.top, bottom = insets.bottom), contentAlignment = Alignment.Center) {
            MessageState(PodiumSymbol.Offline, "No online sources", "This version of Podium has none to turn on.")
        }
        return
    }
    FocusList(
        items = rows,
        state = focus,
        key = {
            when (it) {
                is SourcesRow.Source -> "source:${it.row.id}"
                is SourcesRow.Add -> "add:${it.form.kind}"
            }
        },
        contentPadding = LocalScreenInsets.current.listPadding(),
        onActivate = activate,
        onLongPress = longPress,
        preview = { MenuPreview.Instrument },
        modifier = Modifier.fillMaxSize(),
    ) { row, _, focused ->
        when (row) {
            is SourcesRow.Source -> MenuRow(
                row.row.name,
                focused,
                value = when {
                    !row.row.enabled -> "Off"
                    row.row.signedIn == false -> "Sign in"
                    else -> row.row.note ?: "On"
                },
                showChevron = row.row.signedIn == false,
            )
            is SourcesRow.Add -> MenuRow(row.form.title, focused)
        }
    }
}
