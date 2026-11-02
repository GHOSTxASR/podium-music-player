package app.podium.core.designsystem.component

import android.view.ViewConfiguration
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import app.podium.core.designsystem.shell.ShellPalette
import app.podium.core.designsystem.shell.MatteCoatGrain
import app.podium.core.designsystem.shell.drawMatteCoat
import app.podium.core.designsystem.shell.drawSeam
import app.podium.core.designsystem.shell.drawSpunDisc
import app.podium.core.designsystem.shell.reflectionTurn
import app.podium.core.designsystem.shell.rememberDeviceTilt
import app.podium.core.designsystem.shell.rememberSpunMetal
import app.podium.core.designsystem.shell.grain
import app.podium.core.designsystem.symbol.PodiumSymbol
import app.podium.core.designsystem.symbol.Symbol
import app.podium.core.designsystem.theme.PodiumMotion
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.interaction.PodiumInput
import app.podium.core.interaction.WheelButton
import app.podium.core.interaction.WheelGestureTracker
import app.podium.core.interaction.rememberPodiumHaptics
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

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
    // A held centre starts its rising pressure this far into the hold (D-105).
    val holdBuildAt = remember(longPressTimeout) { (longPressTimeout * 0.4f).toLong() }
    val tracker = remember { WheelGestureTracker() }
    val severalFingers = LocalSeveralFingers.current
    val currentOnInput by rememberUpdatedState(onInput)

    var pressed by remember { mutableStateOf<WheelButton?>(null) }
    var finger by remember { mutableStateOf<Offset?>(null) }
    val scope = rememberCoroutineScope()

    val centerFraction = 0.38f
    val centerSize = diameter * centerFraction
    // TACTILE (D-65): each part goes down on the frame the finger lands and comes back up with a
    // whisper of spring. Driven from the touch handler and read only while drawing.
    val keys = remember { WheelButton.entries.associateWith { KeyTravel(if (it == WheelButton.CENTER) 0.955f else 0.9f) } }
    val travelling = remember { arrayOfNulls<WheelButton>(1) }
    fun pressVisually(button: WheelButton?) {
        if (button == travelling[0]) return
        travelling[0]?.let { keys.getValue(it).release(scope) }
        button?.let { keys.getValue(it).press(scope) }
        travelling[0] = button
    }

    // MECHANICAL (D-65): the knurled band turns with the finger, 1:1, while it turns the Wheel, and
    // clicks into the nearest detent when the finger lifts. Nothing here recomposes the Wheel.
    val band = remember { Animatable(0f) }
    // Read by the touch handler, which outlives a change of finish. Carbon's spun metal turns too (D-101).
    val knurled by rememberUpdatedState(solid?.matte == true || solid?.metal == true)
    val metal = solid?.metal == true
    val machining = if (metal) rememberSpunMetal(with(LocalDensity.current) { diameter.roundToPx() }) else null
    // The reflection on the metal follows the phone's tilt, as it would on the real thing (D-101).
    val tilt = if (metal) rememberDeviceTilt(enabled = !PodiumTheme.motion.reduced) else null

    fun emit(input: PodiumInput) {
        when (input) {
            // The detent goes to whoever turns with it first; if they answered it themselves (the
            // end of a list, a volume step), theirs is the feel — otherwise the Wheel's own click,
            // by how fast it turns (D-105).
            is PodiumInput.Rotate -> {
                haptics.releaseHold()
                val mark = haptics.mark()
                currentOnInput(input)
                if (!haptics.askedSince(mark)) haptics.detent(input.velocity)
                return
            }
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
                    pressVisually(zone)
                    pressed = zone
                    finger = down.position
                    // The centre is a firm key; the quarters lighter (D-105).
                    if (zone == WheelButton.CENTER) haptics.centerPress() else haptics.press()
                    val bandAtDown = band.value
                    var turned = false
                    var waitLongPress = true
                    // Held, the centre builds a rising pressure toward its long press.
                    var building = false
                    while (true) {
                        val wait = when {
                            zone != WheelButton.CENTER -> longPressTimeout
                            !building -> holdBuildAt
                            else -> longPressTimeout - holdBuildAt
                        }
                        val event = if (waitLongPress) {
                            withTimeoutOrNull(wait) { awaitPointerEvent() }
                        } else {
                            awaitPointerEvent()
                        }
                        if (event == null) {
                            if (zone == WheelButton.CENTER && !building) {
                                building = true
                                if (!severalFingers()) haptics.holdBuild()
                                continue
                            }
                            waitLongPress = false
                            // A second finger down means a pinch may be starting: no hold (D-65).
                            if (!severalFingers()) tracker.onLongPressTimeout()?.let(::emit) else haptics.releaseHold()
                            continue
                        }
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        // Something above took the gesture (the two-finger pinch into Podium's
                        // space, D-54): the Wheel lets go rather than turn under it.
                        if (change.isConsumed) break
                        if (!change.pressed) {
                            haptics.releaseHold()
                            tracker.onUp()?.let(::emit)
                            break
                        }
                        finger = change.position
                        tracker.onMove(change.position.x, change.position.y, cx, cy, change.uptimeMillis)
                            ?.let {
                                waitLongPress = false
                                emit(it)
                            }
                        if (knurled && tracker.isRotating) {
                            turned = true
                            val angle = bandAtDown + tracker.travelledDegrees
                            scope.launch(start = CoroutineStart.UNDISPATCHED) { band.snapTo(angle) }
                        }
                        pressed = tracker.pressedButton
                        pressVisually(tracker.pressedButton)
                        change.consume()
                    }
                    tracker.onCancel()
                    // Whatever the gesture became, it's over: no hold left building (D-105).
                    haptics.releaseHold()
                    pressVisually(null)
                    pressed = null
                    finger = null
                    if (turned) {
                        // Into the nearest detent: a small click into place, never a coast.
                        val rest = (band.value / DetentDegrees).roundToInt() * DetentDegrees
                        scope.launch(start = CoroutineStart.UNDISPATCHED) { band.animateTo(rest, PodiumMotion.detent()) }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (solid == null) {
            // The ring: one piece of glass.
            Box(Modifier.fillMaxSize().glass(GlassMaterial.Regular, CircleShape))
        } else if (solid.metal) {
            // Carbon (D-101): blackened, spun steel set into the body. Its machining turns with the
            // finger under a light that stays put; a seam around it, a shadow beneath it.
            Canvas(
                Modifier
                    .fillMaxSize()
                    .shadow(7.dp, CircleShape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
                    // Cached as one picture between turns and tilts: the display moving above it
                    // doesn't redraw the metal (D-65).
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
            ) {
                drawSpunDisc(solid.ring, machining, angle = band.value, sheen = 1f, textureAlpha = 0.45f, light = reflectionTurn(tilt?.value))
            }
        } else {
            // A matte, slightly concave ring in the finish's wheel colour, with the body's grain.
            Box(
                Modifier
                    .fillMaxSize()
                    .then(if (solid.matte) Modifier else Modifier.shadow(4.dp, CircleShape, clip = false))
                    .clip(CircleShape)
                    .background(
                        if (solid.matte) {
                            Brush.verticalGradient(listOf(solid.ring, solid.ring))
                        } else {
                            Brush.verticalGradient(listOf(lerp(solid.ring, Color.Black, 0.07f), lerp(solid.ring, Color.White, 0.06f)))
                        },
                    )
                    .grain { solid.grain }
                    .border(1.dp, solid.ringRim, CircleShape),
            )
        }

        // Pressed zone, finger specular, and the gap that makes the center a separate piece.
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f
            val inner = radius * centerFraction
            if (solid?.matte == true) {
                // Knurling: fine radial ticks around the outer band; a longer one at each detent.
                val ticks = (360f / (DetentDegrees / 2f)).toInt()
                for (t in 0 until ticks) {
                    val a = Math.toRadians((t * DetentDegrees / 2f + band.value).toDouble())
                    val long = t % 2 == 0
                    val r0 = radius * (if (long) 0.885f else 0.915f)
                    val r1 = radius * 0.965f
                    val dir = Offset(kotlin.math.sin(a).toFloat(), -kotlin.math.cos(a).toFloat())
                    drawLine(
                        color = solid.legend.copy(alpha = if (long) 0.22f else 0.12f),
                        start = center + dir * r0,
                        end = center + dir * r1,
                        strokeWidth = (if (long) 1.4f else 1f).dp.toPx(),
                    )
                }
                // The band's inner edge: a machined step.
                drawCircle(solid.ringRim, radius = radius * 0.86f, style = Stroke(width = 1.dp.toPx()))
            }
            if (metal) {
                // The seam the Wheel sits in, just outside it (drawn here, outside the cached ring's bounds).
                drawSeam(center, radius)
                // The well the centre sits in: its wall in shadow at the top left, its lip lit below.
                drawCircle(
                    Brush.linearGradient(
                        listOf(Color.Black.copy(alpha = 0.7f), Color.Black.copy(alpha = 0.25f), Color.White.copy(alpha = 0.12f)),
                        start = Offset(center.x - inner, center.y - inner),
                        end = Offset(center.x + inner, center.y + inner),
                    ),
                    radius = inner + 2.dp.toPx(),
                    style = Stroke(width = 4.dp.toPx()),
                )
            }
            pressed?.takeIf { it != WheelButton.CENTER }?.let { zone ->
                val middle = when (zone) {
                    WheelButton.MENU -> -90f
                    WheelButton.NEXT -> 0f
                    WheelButton.PLAY_PAUSE -> 90f
                    else -> 180f
                }
                // The pressed quarter darkens and its edges blend into the ring (D-103): full over the
                // zone's middle, fading out across its borders instead of stopping on a hard line.
                val shade = solid?.pressShade ?: Color.Black.copy(alpha = if (colors.isDark) 0.16f else 0.08f)
                rotate(middle - 180f) {
                    drawCircle(
                        Brush.sweepGradient(
                            0f to Color.Transparent,
                            (180f - PressFeatherOuter) / 360f to Color.Transparent,
                            (180f - PressFeatherInner) / 360f to shade,
                            (180f + PressFeatherInner) / 360f to shade,
                            (180f + PressFeatherOuter) / 360f to Color.Transparent,
                            1f to Color.Transparent,
                            center = center,
                        ),
                        radius = radius,
                    )
                }
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
            if (!metal) drawCircle(
                color = solid?.ringRim?.copy(alpha = 0.22f) ?: colors.shadow.copy(alpha = if (colors.isDark) 0.35f else 0.10f),
                radius = inner + 1.5.dp.toPx(),
                style = Stroke(width = (if (solid == null) 3 else 2).dp.toPx()),
            )
        }

        // Legends on the ring's midline. MENU is the one all-caps string in Podium (hardware heritage).
        val legendRadius = (diameter.value / 2f + centerSize.value / 2f) / 2f
        val legendColor = solid?.legend ?: colors.labelSecondary
        // The legends have depth on every finish (D-103): light ones stand off the ring on a soft
        // shadow; dark ones are pressed into it, the lower edge of each cut catching the light.
        val engraved = with(LocalDensity.current) {
            if (legendColor.luminance() > 0.4f) {
                Shadow(Color.Black.copy(alpha = if (metal) 0.85f else 0.6f), Offset(0f, 1.25.dp.toPx()), 1.5.dp.toPx())
            } else {
                Shadow(Color.White.copy(alpha = 0.7f), Offset(0f, 1.dp.toPx()), 0.5.dp.toPx())
            }
        }
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
                        val s = keys.getValue(button).value
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
            // One line, as wide as the face sets it, centred on its key.
            BasicText("MENU", Modifier.wrapContentSize(unbounded = true), style = type.wheelLegend.copy(color = legendColor, shadow = engraved), softWrap = false, maxLines = 1)
        }
        Legend(90f, WheelButton.NEXT, "Next track") { Symbol(PodiumSymbol.Next, legendColor, size = 22.dp, weight = 700, filled = true, shadow = engraved) }
        Legend(180f, WheelButton.PLAY_PAUSE, if (isPlaying) "Pause" else "Play") {
            Row2 {
                Symbol(PodiumSymbol.Play, legendColor, size = 18.dp, weight = 700, filled = true, shadow = engraved)
                Symbol(PodiumSymbol.Pause, legendColor, size = 18.dp, weight = 700, filled = true, shadow = engraved)
            }
        }
        Legend(270f, WheelButton.PREVIOUS, "Previous track") { Symbol(PodiumSymbol.Previous, legendColor, size = 22.dp, weight = 700, filled = true, shadow = engraved) }

        // The center: a separate, smaller piece of glass.
        Box(
            Modifier
                .size(centerSize)
                .graphicsLayer {
                    val s = keys.getValue(WheelButton.CENTER).value
                    scaleX = s
                    scaleY = s
                }
                .then(
                    if (solid == null) {
                        Modifier.glass(GlassMaterial.Control, CircleShape, pressed = pressed == WheelButton.CENTER)
                    } else if (solid.metal) {
                        // Carbon (D-101): a separate part with its own finish — a matte, bead-blasted
                        // coat (no machining, no reflection), as on a phone's satin frame — set lower
                        // than the shining ring: shaded at its upper left by the ring's edge, its lower
                        // lip catching the light. Darker when pressed.
                        val down = pressed == WheelButton.CENTER
                        Modifier
                            .clip(CircleShape)
                            .drawBehind { drawMatteCoat(if (down) lerp(solid.center, Color.Black, 0.22f) else solid.center) }
                            .grain { MatteCoatGrain }
                    } else {
                        // A convex button in the body colour: lit from above, darker when pressed.
                        val face = if (pressed == WheelButton.CENTER) lerp(solid.center, Color.Black, 0.10f) else solid.center
                        Modifier
                            .then(if (solid.matte) Modifier else Modifier.shadow(2.dp, CircleShape, clip = false))
                            .clip(CircleShape)
                            .background(
                                if (solid.matte) {
                                    Brush.verticalGradient(listOf(face, face))
                                } else {
                                    Brush.verticalGradient(listOf(lerp(face, Color.White, 0.08f), lerp(face, Color.Black, 0.06f)))
                                },
                            )
                            .grain { solid.grain }
                            .border(1.dp, solid.ringRim, CircleShape)
                    },
                )
                .semantics {
                    role = Role.Button
                    contentDescription = "Select"
                    onClick { currentOnInput(PodiumInput.Press(WheelButton.CENTER)); true }
                },
        ) {
            if (solid?.matte == true) {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(solid.ringRim, radius = size.minDimension / 2f * 0.72f, style = Stroke(width = 1.dp.toPx()))
                }
            }
        }
    }
}

@Composable
private fun Row2(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) { content() }
}

/** The pressed quarter's shade: full within this many degrees of its middle… */
private const val PressFeatherInner = 26f

/** …fading to nothing by this many, a little past the quarter's border. */
private const val PressFeatherOuter = 58f

/** One wheel detent, in degrees (WheelTuning.detentDegrees): the knurling's pitch. */
private const val DetentDegrees = 18f
