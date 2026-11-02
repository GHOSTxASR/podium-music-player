package app.podium.core.designsystem.component

import androidx.compose.animation.core.Animatable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.podium.core.designsystem.glass.GlassMaterial
import app.podium.core.designsystem.glass.glass
import app.podium.core.designsystem.theme.PodiumMotion
import app.podium.core.designsystem.theme.PodiumColors
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.designsystem.tonal.CarbonRadius
import app.podium.core.designsystem.tonal.ContinuousShape
import app.podium.core.designsystem.tonal.canDefocus
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
    // TACTILE (D-65): the menu arrives under the press that asked for it — a quick settle from a
    // touch smaller, interactive from its first frame. It leaves at once: nothing lingers taking input.
    val reduced = PodiumTheme.motion.reduced
    val appear = remember(spec) { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(spec) {
        haptics.menuOpen()
        appear.animateTo(1f, PodiumMotion.pop())
    }
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
            .graphicsLayer { alpha = appear.value.coerceIn(0f, 1f) }
            .background(Color.Black.copy(alpha = scrimAlpha(PodiumTheme.colors)))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .padding(panelPadding),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(horizontal = 32.dp)
                .widthIn(max = 360.dp)
                .fillMaxWidth()
                .graphicsLayer {
                    val s = 0.96f + 0.04f * appear.value
                    scaleX = s
                    scaleY = s
                }
                .glass(GlassMaterial.Regular, sheetShape(PodiumTheme.colors))
                // With the lens's own 3 dp inset, the lens sits 8 dp from the sheet's top and
                // bottom, as from its sides; its corners are then concentric with the sheet's.
                .padding(vertical = 5.dp),
        ) {
            spec.title?.let { SectionHeader(it, Modifier.padding(bottom = 0.dp)) }
            FocusList(
                items = spec.actions.indices.toList(),
                state = focus,
                // By place, not label: two choices may read the same (two playlists of one name).
                key = { it },
                contentPadding = PaddingValues(0.dp),
                onActivate = { index ->
                    spec.actions[index].takeIf { it.enabled }?.let {
                        onDismiss()
                        it.onSelect()
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(max = 52.dp * spec.actions.size),
                paper = false,
            ) { index, _, focused ->
                val action = spec.actions[index]
                MenuRow(action.label, focused, showChevron = false, enabled = action.enabled)
            }
        }
    }
}

/**
 * Behind a menu or notice the display dims. On Carbon the display behind also goes out of focus
 * ([app.podium.core.designsystem.tonal.defocus]), so the dim can stay light; where it can't blur, a
 * deeper dim keeps the solid panel clear of what's behind it.
 */
internal fun scrimAlpha(colors: PodiumColors): Float = when {
    colors.isTonal -> if (canDefocus) 0.3f else 0.5f
    colors.isDark -> 0.35f
    else -> 0.18f
}

/** A menu's or notice's shape: squared on the matte instrument, continuous elsewhere (D-103). */
internal fun sheetShape(colors: PodiumColors): Shape =
    if (colors.isIndustrial) RoundedCornerShape(sheetCorner(colors)) else ContinuousShape(sheetCorner(colors))

/** A menu's or notice's corners: squared on the matte instrument, Carbon's sheet radius (D-101), round on Glass. */
internal fun sheetCorner(colors: PodiumColors): Dp = when {
    colors.isIndustrial -> 3.dp
    colors.isTonal -> CarbonRadius.sheet
    else -> 22.dp
}
