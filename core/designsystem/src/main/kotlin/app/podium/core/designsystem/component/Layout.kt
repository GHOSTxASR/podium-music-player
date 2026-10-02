package app.podium.core.designsystem.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Where the functional layer sits over a screen: content scrolls beneath the title bar (top) and
 * beneath the mini player + Wheel (bottom). Lists pad by these so every row can reach the
 * readable region (design-system.md §1.1).
 */
@Immutable
data class ScreenInsets(val top: Dp = 0.dp, val bottom: Dp = 0.dp) {
    fun listPadding(extraTop: Dp = 8.dp, extraBottom: Dp = 8.dp) = PaddingValues(top = top + extraTop, bottom = bottom + extraBottom)
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
