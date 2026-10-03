package app.podium.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.podium.core.designsystem.glass.GlassMaterial
import app.podium.core.designsystem.glass.glass
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.interaction.FocusListState
import app.podium.core.interaction.InputTargetEffect
import app.podium.core.interaction.PodiumInput
import app.podium.core.interaction.WheelButton
import app.podium.core.interaction.WheelContext
import app.podium.core.interaction.rememberPodiumHaptics

@Immutable
data class MenuAction(val label: String, val enabled: Boolean = true, val onSelect: () -> Unit)

@Immutable
data class MenuSpec(val title: String?, val actions: List<MenuAction>)

/**
 * A context menu (design-system.md §6.9): glass, in the functional layer, driven by the Wheel like
 * any list. Menu dismisses; Center chooses.
 */
@Composable
fun GlassMenu(
    spec: MenuSpec,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** Keeps the panel clear of the Wheel, which stays live (and above the scrim) while a menu is open. */
    panelPadding: PaddingValues = PaddingValues(0.dp),
) {
    val focus = remember(spec) { FocusListState() }
    val haptics = rememberPodiumHaptics()
    InputTargetEffect(WheelContext.OVERLAY) { input ->
        when (input) {
            is PodiumInput.Rotate -> {
                if (!focus.moveBy(input.detents)) haptics.boundary()
                true
            }
            is PodiumInput.Press -> {
                when (input.button) {
                    WheelButton.CENTER -> spec.actions.getOrNull(focus.focusedIndex)?.takeIf { it.enabled }?.let {
                        onDismiss()
                        it.onSelect()
                    }
                    WheelButton.MENU -> onDismiss()
                    else -> return@InputTargetEffect false
                }
                true
            }
            else -> input is PodiumInput.LongPress || input is PodiumInput.Release
        }
    }
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = if (PodiumTheme.colors.isDark) 0.35f else 0.18f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .padding(panelPadding),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(horizontal = 32.dp)
                .widthIn(max = 360.dp)
                .fillMaxWidth()
                .glass(GlassMaterial.Regular, RoundedCornerShape(if (PodiumTheme.colors.isIndustrial) 3.dp else 22.dp))
                .padding(vertical = 8.dp),
        ) {
            spec.title?.let { SectionHeader(it, Modifier.padding(bottom = 0.dp)) }
            FocusList(
                items = spec.actions,
                state = focus,
                key = { it.label },
                contentPadding = PaddingValues(0.dp),
                onActivate = { index ->
                    spec.actions[index].takeIf { it.enabled }?.let {
                        onDismiss()
                        it.onSelect()
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(max = 52.dp * spec.actions.size),
                paper = false,
            ) { action, _, focused ->
                MenuRow(action.label, focused, showChevron = false, enabled = action.enabled)
            }
        }
    }
}
