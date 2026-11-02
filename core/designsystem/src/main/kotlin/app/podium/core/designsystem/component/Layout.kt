package app.podium.core.designsystem.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Where the functional layer sits over a screen: content scrolls beneath the title bar (top) and
 * beneath the mini player + Wheel (bottom). Lists pad by these so every row can reach the
 * readable region (design-system.md §1.1). Both already hold the edge margin (D-103), so a list
 * starts right below the title bar's housings and ends the same distance above the mini player.
 */
@Immutable
data class ScreenInsets(val top: Dp = 0.dp, val bottom: Dp = 0.dp) {
    fun listPadding(extraTop: Dp = 0.dp, extraBottom: Dp = 0.dp) = PaddingValues(top = top + extraTop, bottom = bottom + extraBottom)
}

val LocalScreenInsets = staticCompositionLocalOf { ScreenInsets() }

/** Overlays (menus) live in the functional layer, above the captured content (ADR-007 §2). */
@Stable
class OverlayHost {
    var menu by mutableStateOf<MenuSpec?>(null)
        private set

    fun show(spec: MenuSpec) {
        menu = spec
    }

    fun dismiss() {
        menu = null
    }
}

val LocalOverlayHost = staticCompositionLocalOf { OverlayHost() }

/**
 * A moment that takes the whole display (D-104: Now Playing's vinyl): drawn over everything on it,
 * the title bar included, while what lies behind goes out of focus by [depth] (0–1, read while
 * drawing; the moment sets it as it opens and closes, so the blur follows its own choreography).
 * One at a time; the screen that opens it closes it.
 */
@Stable
class ImmersiveHost {
    var content by mutableStateOf<(@Composable () -> Unit)?>(null)
        private set

    /** How far out of focus the display behind has gone, 0–1. */
    var depth by mutableFloatStateOf(0f)

    /** What the system's back does while it shows (the shell asks; the moment decides). */
    var onBack: () -> Unit = {}
        private set

    fun show(onBack: () -> Unit, content: @Composable () -> Unit) {
        this.onBack = onBack
        this.content = content
    }

    fun close() {
        content = null
        onBack = {}
        depth = 0f
    }
}

val LocalImmersiveHost = staticCompositionLocalOf { ImmersiveHost() }
