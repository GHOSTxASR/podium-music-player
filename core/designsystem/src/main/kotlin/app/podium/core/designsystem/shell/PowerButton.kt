package app.podium.core.designsystem.shell

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.podium.core.designsystem.glass.GlassMaterial
import app.podium.core.designsystem.glass.glass
import app.podium.core.designsystem.symbol.PodiumSymbol
import app.podium.core.designsystem.symbol.Symbol
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.interaction.rememberPodiumHaptics

/**
 * The device's power button: small, set into the body beside the Wheel. Glass on the Glass finish
 * (it is a control), otherwise a solid button in the Wheel's colour. The glyph (from the same
 * symbol font as every other Podium icon) dims when off.
 */
@Composable
fun PowerButton(on: Boolean, palette: ShellPalette, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val colors = PodiumTheme.colors
    val haptics = rememberPodiumHaptics()
    val currentOnToggle by rememberUpdatedState(onToggle)
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, PodiumTheme.motion.press(), label = "powerScale")
    val glyphBase = if (palette.isGlass) colors.labelSecondary else palette.legend
    val glyph by animateColorAsState(if (on) glyphBase else glyphBase.copy(alpha = 0.45f), label = "powerGlyph")
    val face = if (palette.isGlass) {
        Modifier.glass(GlassMaterial.Control, CircleShape, pressed = pressed)
    } else {
        Modifier
            .shadow(3.dp, CircleShape, clip = false)
            .clip(CircleShape)
            .background(
                Brush.verticalGradient(
                    listOf(lerp(palette.ring, Color.White, 0.10f), lerp(palette.ring, Color.Black, 0.08f)),
                ),
            )
            .grain { palette.grain }
            .border(1.dp, palette.ringRim, CircleShape)
    }
    // A 48dp target around a 36dp button.
    Box(
        modifier
            .size(48.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        haptics.press()
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = {
                        haptics.confirm()
                        currentOnToggle()
                    },
                )
            }
            .semantics {
                role = Role.Button
                contentDescription = "Power"
                stateDescription = if (on) "On" else "Off"
                onClick { currentOnToggle(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .then(face),
            contentAlignment = Alignment.Center,
        ) {
            Symbol(PodiumSymbol.Power, glyph, size = 18.dp, weight = 600)
        }
    }
}
