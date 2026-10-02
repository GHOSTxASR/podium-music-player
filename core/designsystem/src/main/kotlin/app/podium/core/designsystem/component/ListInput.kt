package app.podium.core.designsystem.component

import androidx.compose.runtime.Composable
import app.podium.core.interaction.FocusListState
import app.podium.core.interaction.InputTargetEffect
import app.podium.core.interaction.PodiumInput
import app.podium.core.interaction.WheelButton
import app.podium.core.interaction.WheelContext
import app.podium.core.interaction.rememberPodiumHaptics

/**
 * The standard list behaviour (interaction-model.md §4, ListFocus): rotate moves focus with a
 * boundary haptic at the ends, Center activates, long-press Center opens the context menu.
 * Menu and transport buttons fall through to the global handlers.
 */
@Composable
fun ListInputEffect(
    focus: FocusListState,
    onActivate: (Int) -> Unit,
    onLongPress: ((Int) -> Unit)? = null,
) {
    val haptics = rememberPodiumHaptics()
    InputTargetEffect(WheelContext.LIST_FOCUS) { input ->
        when (input) {
            is PodiumInput.Rotate -> {
                if (!focus.moveBy(input.detents)) haptics.boundary()
                true
            }
            is PodiumInput.Press -> if (input.button == WheelButton.CENTER) {
                if (focus.itemCount > 0) onActivate(focus.focusedIndex)
                true
            } else false
            is PodiumInput.LongPress -> if (input.button == WheelButton.CENTER && onLongPress != null) {
                if (focus.itemCount > 0) onLongPress(focus.focusedIndex)
                true
            } else false
            is PodiumInput.Release -> false
        }
    }
}
