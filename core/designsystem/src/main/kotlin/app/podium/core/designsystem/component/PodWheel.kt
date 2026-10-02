package app.podium.core.designsystem.component

import android.view.ViewConfiguration
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.podium.core.designsystem.glass.GlassMaterial
import app.podium.core.designsystem.glass.glass
import app.podium.core.designsystem.symbol.PodiumSymbol
import app.podium.core.designsystem.symbol.Symbol
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.interaction.PodiumInput
import app.podium.core.interaction.WheelButton
import app.podium.core.interaction.WheelGestureTracker
import app.podium.core.interaction.rememberPodiumHaptics
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.lerp
import app.podium.core.designsystem.shell.ShellPalette
import app.podium.core.designsystem.shell.grain

/** Wheel diameter for a window width (design-system.md §6.1): 66% of width, 232–312 dp. */
fun wheelDiameter(windowWidth: Dp): Dp = (windowWidth * 0.66f).coerceIn(232.dp, 312.dp)

/**
 * The Wheel: Podium's one bold element. Glass ring + glass center, static when idle, alive when
 * touched. Emits semantic [PodiumInput]s only; screens never see raw gestures.
 *
 * @param isPlaying for the play/pause button's accessibility state.
 */
@Composable
fun PodWheel(
    onInput: (PodiumInput) -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 280.dp,
    isPlaying: Boolean = false,
    /** A solid finish (D-26); null or a Glass palette draws the Liquid Glass wheel. */
    palette: ShellPalette? = null,
) {
    val solid = palette?.takeUnless { it.isGlass }
    val colors = PodiumTheme.colors
    val type = PodiumTheme.type
    val haptics = rememberPodiumHaptics()
    val longPressTimeout = remember { ViewConfiguration.getLongPressTimeout().toLong() }
    val tracker = remember { WheelGestureTracker() }
    val currentOnInput by rememberUpdatedState(onInput)

    var pressed by remember { mutableStateOf<WheelButton?>(null) }
    var finger by remember { mutableStateOf<Offset?>(null) }

    val centerFraction = 0.38f
    val centerSize = diameter * centerFraction
    val centerScale by animateFloatAsState(
        if (pressed == WheelButton.CENTER) 0.96f else 1f,
        PodiumTheme.motion.press(),
        label = "centerScale",
    )

    fun emit(input: PodiumInput) {
        when (input) {
            is PodiumInput.Rotate -> haptics.detent()
            is PodiumInput.LongPress -> haptics.longPress()
            else -> Unit
        }
        currentOnInput(input)
    }

    Box(
        modifier
            .size(diameter)
            .semantics {
                contentDescription = "Wheel"
                customActions = listOf(
                    CustomAccessibilityAction("Move up") { currentOnInput(PodiumInput.Rotate(-1)); true },
                    CustomAccessibilityAction("Move down") { currentOnInput(PodiumInput.Rotate(1)); true },
                    CustomAccessibilityAction("More options") { currentOnInput(PodiumInput.LongPress(WheelButton.CENTER)); true },
                    CustomAccessibilityAction("Go to Home") { currentOnInput(PodiumInput.LongPress(WheelButton.MENU)); true },
                )
            }
            .pointerInput(diameter) {
                val outer = size.width / 2f + 12.dp.toPx()
                val inner = size.width * centerFraction / 2f
                val cx = size.width / 2f
                val cy = size.height / 2f
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val zone = tracker.onDown(down.position.x, down.position.y, cx, cy, inner, outer) ?: return@awaitEachGesture
                    pressed = zone
                    finger = down.position
                    haptics.press()
                    var waitLongPress = true
                    while (true) {
                        val event = if (waitLongPress) {
                            withTimeoutOrNull(longPressTimeout) { awaitPointerEvent() }
                        } else {
                            awaitPointerEvent()
                        }
                        if (event == null) {
                            waitLongPress = false
                            tracker.onLongPressTimeout()?.let(::emit)
                            continue
                        }
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            tracker.onUp()?.let(::emit)
                            break
                        }
                        finger = change.position
                        tracker.onMove(change.position.x, change.position.y, cx, cy, change.uptimeMillis)
                            ?.let {
                                waitLongPress = false
                                emit(it)
                            }
                        pressed = tracker.pressedButton
                        change.consume()
                    }
                    tracker.onCancel()
                    pressed = null
                    finger = null
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (solid == null) {
            // The ring: one piece of glass.
            Box(Modifier.fillMaxSize().glass(GlassMaterial.Regular, CircleShape))
        } else {
            // A matte, slightly concave ring in the finish's wheel colour, with the body's grain.
            Box(
                Modifier
                    .fillMaxSize()
                    .shadow(4.dp, CircleShape, clip = false)
                    .clip(CircleShape)
                    .background(Brush.verticalGradient(listOf(lerp(solid.ring, Color.Black, 0.07f), lerp(solid.ring, Color.White, 0.06f))))
                    .grain { solid.grain }
                    .border(1.dp, solid.ringRim, CircleShape),
            )
        }

        // Pressed zone, finger specular, and the gap that makes the center a separate piece.
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f
            val inner = radius * centerFraction
            pressed?.takeIf { it != WheelButton.CENTER }?.let { zone ->
                val start = when (zone) {
                    WheelButton.MENU -> -135f
                    WheelButton.NEXT -> -45f
                    WheelButton.PLAY_PAUSE -> 45f
                    else -> 135f
                }
                drawArc(
                    color = solid?.pressShade ?: Color.Black.copy(alpha = if (colors.isDark) 0.16f else 0.08f),
                    startAngle = start,
                    sweepAngle = 90f,
                    useCenter = true,
                )
            }
            finger?.let { f ->
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color.White.copy(alpha = if (solid == null) 0.20f else 0.10f), Color.Transparent),
                        center = f,
                        radius = 48.dp.toPx(),
                    ),
                    radius = radius,
                )
            }
            // The gap that makes the center a separate piece; solid finishes get a softer seam.
            drawCircle(
                color = solid?.ringRim?.copy(alpha = 0.22f) ?: colors.shadow.copy(alpha = if (colors.isDark) 0.35f else 0.10f),
                radius = inner + 1.5.dp.toPx(),
                style = Stroke(width = (if (solid == null) 3 else 2).dp.toPx()),
            )
        }

        // Legends on the ring's midline. MENU is the one all-caps string in Podium (hardware heritage).
        val legendRadius = (diameter.value / 2f + centerSize.value / 2f) / 2f
        val legendColor = solid?.legend ?: colors.labelSecondary
        @Composable
        fun Legend(angleDeg: Float, button: WheelButton, label: String, content: @Composable () -> Unit) {
            val rad = Math.toRadians(angleDeg.toDouble())
            val dx = (legendRadius * sin(rad)).toFloat()
            val dy = (-legendRadius * cos(rad)).toFloat()
            Box(
                Modifier
                    .offset { IntOffset((dx * density).roundToInt(), (dy * density).roundToInt()) }
                    .size(48.dp)
                    .graphicsLayer {
                        val s = if (pressed == button) 0.9f else 1f
                        scaleX = s
                        scaleY = s
                    }
                    .semantics {
                        role = Role.Button
                        contentDescription = label
                        if (button == WheelButton.PLAY_PAUSE) stateDescription = if (isPlaying) "Playing" else "Paused"
                        onClick { currentOnInput(PodiumInput.Press(button)); true }
                    },
                contentAlignment = Alignment.Center,
            ) { content() }
        }
        Legend(0f, WheelButton.MENU, "Back") {
            BasicText("MENU", style = type.wheelLegend.copy(color = legendColor))
        }
        Legend(90f, WheelButton.NEXT, "Next track") { Symbol(PodiumSymbol.Next, legendColor, size = 22.dp, weight = 600, filled = true) }
        Legend(180f, WheelButton.PLAY_PAUSE, if (isPlaying) "Pause" else "Play") {
            Row2 { Symbol(PodiumSymbol.Play, legendColor, size = 18.dp, weight = 600, filled = true); Symbol(PodiumSymbol.Pause, legendColor, size = 18.dp, weight = 600, filled = true) }
        }
        Legend(270f, WheelButton.PREVIOUS, "Previous track") { Symbol(PodiumSymbol.Previous, legendColor, size = 22.dp, weight = 600, filled = true) }

        // The center: a separate, smaller piece of glass.
        Box(
            Modifier
                .size(centerSize)
                .graphicsLayer {
                    scaleX = centerScale
                    scaleY = centerScale
                }
                .then(
                    if (solid == null) {
                        Modifier.glass(GlassMaterial.Control, CircleShape, pressed = pressed == WheelButton.CENTER)
                    } else {
                        // A convex button in the body colour: lit from above, darker when pressed.
                        val face = if (pressed == WheelButton.CENTER) lerp(solid.center, Color.Black, 0.10f) else solid.center
                        Modifier
                            .shadow(2.dp, CircleShape, clip = false)
                            .clip(CircleShape)
                            .background(Brush.verticalGradient(listOf(lerp(face, Color.White, 0.08f), lerp(face, Color.Black, 0.06f))))
                            .grain { solid.grain }
                            .border(1.dp, solid.ringRim, CircleShape)
                    },
                )
                .semantics {
                    role = Role.Button
                    contentDescription = "Select"
                    onClick { currentOnInput(PodiumInput.Press(WheelButton.CENTER)); true }
                },
        )
    }
}

@Composable
private fun Row2(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) { content() }
}
