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
 * One online source as Settings shows it (D-35): whatever the source is, the same few facts — its
 * name, whether it's on, and, when something needs the listener, a short note. Settings is the one
 * place a source is named; music browsing never is.
 */
data class OnlineSourceRow(
    val id: String,
    val name: String,
    val enabled: Boolean,
    /** Why it can't help right now, in sentence case ("Can't reach it right now"); null when fine. */
    val note: String? = null,
)

/** The online sources this build has, in the order Podium prefers them, and the listener's choices. */
interface OnlineSourceSettings {
    val sources: StateFlow<List<OnlineSourceRow>>
    fun setEnabled(id: String, enabled: Boolean)

    /** Move a source to [toIndex] in the preferred order (only matters when there's more than one). */
    fun move(id: String, toIndex: Int)
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

/**
 * Settings ▸ Online sources: every online source this build has. Center turns one on or off;
 * with more than one, hold Center to move it up or down — the order Podium picks from when it finds
 * the same song in several places. Searching, playing and recommending never ask the listener which.
 */
@Composable
fun OnlineSourcesScreen(settings: OnlineSourceSettings) {
    val rows by settings.sources.collectAsStateWithLifecycle()
    val haptics = rememberPodiumHaptics()
    val overlay = LocalOverlayHost.current
    val focus = rememberFocusListState("online-sources")
    val activate: (Int) -> Unit = { i ->
        rows.getOrNull(i)?.let { row ->
            haptics.confirm()
            settings.setEnabled(row.id, !row.enabled)
        }
    }
    val longPress: (Int) -> Unit = { i ->
        val row = rows.getOrNull(i)
        if (row != null && rows.size > 1) {
            overlay.show(
                MenuSpec(
                    row.name,
                    buildList {
                        if (i > 0) add(MenuAction("Move up") { settings.move(row.id, i - 1) })
                        if (i < rows.lastIndex) add(MenuAction("Move down") { settings.move(row.id, i + 1) })
                    },
                ),
            )
        } else {
            haptics.reject()
        }
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
        key = { it.id },
        contentPadding = LocalScreenInsets.current.listPadding(),
        onActivate = activate,
        onLongPress = longPress,
        preview = { MenuPreview.Instrument },
        modifier = Modifier.fillMaxSize(),
    ) { row, _, focused ->
        MenuRow(row.name, focused, value = if (!row.enabled) "Off" else row.note ?: "On", showChevron = false)
    }
}
