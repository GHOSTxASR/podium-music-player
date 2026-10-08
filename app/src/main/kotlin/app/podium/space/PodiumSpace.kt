package app.podium.space

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.util.VelocityTracker1D
import androidx.compose.runtime.CompositionLocalProvider
import app.podium.core.designsystem.component.LocalSeveralFingers
import app.podium.core.designsystem.theme.PodiumMotion
import app.podium.core.designsystem.theme.PodiumTheme
import kotlinx.coroutines.CoroutineStart
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * Where the object is (D-54, PHYSICAL_SPACE.md §3). Only these; nothing else can be true at once.
 */
enum class SpacePhase { NORMAL, ENTERING, PHYSICAL, STICKER_EDITING, RETURNING }

/** Which of the two the space shows large: the settings page (the default) or the Podium. */
enum class SpaceFocus { SETTINGS, PODIUM }

/**
 * The flat Podium and the object it becomes. [depth] runs 0 (flat, the app as always) … 1 (in its
 * space); [zoom] 0 (the settings page large, the Podium small beside it) … 1 (the Podium large, the
 * page small in its corner); [edit] 0 … 1 (nearer and square on, for placing stickers). The pinch
 * drives [depth] directly; everything else animates. Never persisted: a restart is flat, and every
 * visit to the space opens on the settings page.
 */
@Stable
class PodiumSpaceState(private val scope: CoroutineScope) {
    var phase by mutableStateOf(SpacePhase.NORMAL)
        private set
    val depth = Animatable(0f)
    val zoom = Animatable(0f)
    val edit = Animatable(0f)

    var focus by mutableStateOf(SpaceFocus.SETTINGS)
        private set

    /** Reduced motion (kept current by [PodiumSpace]): the object settles with a short fade of motion. */
    var reduced = false

    /** Fingers on the device right now, as the pinch watcher last saw them. */
    internal var fingers = 0

    val isOut: Boolean get() = phase != SpacePhase.NORMAL

    /**
     * PERSONAL (D-65): the fingers are moving the object, and [value] is where they put it (0 flat …
     * 1 in its space). Set on the frame the fingers move — no animation between the hand and the
     * object. Catching it mid-settle takes it from where it is.
     */
    fun follow(value: Float) {
        if (phase == SpacePhase.STICKER_EDITING) return
        if (phase == SpacePhase.NORMAL) {
            // Every visit opens with the settings page large (D-56).
            focus = SpaceFocus.SETTINGS
            scope.launch(start = CoroutineStart.UNDISPATCHED) { zoom.snapTo(0f) }
        }
        phase = SpacePhase.ENTERING
        scope.launch(start = CoroutineStart.UNDISPATCHED) { depth.snapTo(value.coerceIn(0f, 1f)) }
    }

    /**
     * The fingers let go while moving at [velocity] (depth per second): the object carries on to where
     * it was heading — into the space, or back to being the flat Podium — keeping that speed.
     * [startedOut]: the gesture began with the object in its space (a spread), so it stays out unless
     * it was brought most of the way back.
     */
    fun release(velocity: Float = 0f, startedOut: Boolean = false) {
        if (phase != SpacePhase.ENTERING) return
        val heading = depth.value + velocity * PROJECTION_SECONDS
        val stayOut = heading > if (startedOut) RETURN_THRESHOLD else ENTER_THRESHOLD
        if (stayOut) enter(velocity) else leave(velocity)
    }

    fun enter(velocity: Float = 0f) {
        if (phase == SpacePhase.NORMAL || phase == SpacePhase.RETURNING) {
            focus = SpaceFocus.SETTINGS
            scope.launch { zoom.snapTo(0f) }
        }
        phase = SpacePhase.ENTERING
        scope.launch {
            depth.animateTo(1f, if (reduced) tween(REDUCED_MILLIS) else PodiumMotion.settleIn(), initialVelocity = velocity)
            if (phase == SpacePhase.ENTERING) phase = SpacePhase.PHYSICAL
        }
    }

    /** Return to Podium: the object comes forward, the space recedes, the flat app is back. */
    fun leave(velocity: Float = 0f) {
        phase = SpacePhase.RETURNING
        scope.launch {
            launch { edit.animateTo(0f, if (reduced) tween(REDUCED_MILLIS) else PodiumMotion.settleBack()) }
            depth.animateTo(0f, if (reduced) tween(REDUCED_MILLIS) else PodiumMotion.settleBack(), initialVelocity = velocity)
            if (phase == SpacePhase.RETURNING) phase = SpacePhase.NORMAL
        }
    }

    /** The Podium large, the settings page small in its corner. */
    fun focusPodium() = focusOn(SpaceFocus.PODIUM)

    /** The settings page large again. */
    fun focusSettings() = focusOn(SpaceFocus.SETTINGS)

    private fun focusOn(f: SpaceFocus) {
        if (phase != SpacePhase.PHYSICAL && phase != SpacePhase.ENTERING) return
        focus = f
        scope.launch { zoom.animateTo(if (f == SpaceFocus.PODIUM) 1f else 0f, if (reduced) tween(REDUCED_MILLIS) else PodiumMotion.rearrange()) }
    }

    fun startEditing() {
        if (phase != SpacePhase.PHYSICAL) return
        phase = SpacePhase.STICKER_EDITING
        scope.launch { edit.animateTo(1f, if (reduced) tween(REDUCED_MILLIS) else PodiumMotion.rearrange()) }
    }

    fun stopEditing() {
        if (phase != SpacePhase.STICKER_EDITING) return
        phase = SpacePhase.PHYSICAL
        scope.launch { edit.animateTo(0f, if (reduced) tween(REDUCED_MILLIS) else PodiumMotion.rearrange()) }
    }

    companion object {
        /** A pinch from the flat Podium heading past this goes into the space. */
        const val ENTER_THRESHOLD = 0.3f

        /** A spread from the space heading below this comes back to the flat Podium. */
        const val RETURN_THRESHOLD = 0.7f

        /** How far ahead the release looks along the fingers' motion. */
        const val PROJECTION_SECONDS = 0.12f

        private const val REDUCED_MILLIS = 160
    }
}

/** The space, for anything that opens it without the gesture (Settings ▸ Podium body). */
val LocalPodiumSpace = androidx.compose.runtime.staticCompositionLocalOf<PodiumSpaceState?> { null }

@Composable
fun rememberPodiumSpaceState(): PodiumSpaceState {
    val scope = rememberCoroutineScope()
    return remember(scope) { PodiumSpaceState(scope) }
}

/**
 * The window: the object (the whole Podium, [podium]) and, while it's out, the space around it and
 * the [panel]. [stickers] are drawn on the object, in its coordinates, so they move with it.
 * [edgeColor] is the slab's edge (the body's own colour, darker); [spaceTint] the space's darkness.
 */
@Composable
fun PodiumSpace(
    state: PodiumSpaceState,
    edgeColor: Color,
    spaceTint: Color,
    modifier: Modifier = Modifier,
    stickers: @Composable BoxScope.() -> Unit = {},
    stickerEditor: @Composable BoxScope.() -> Unit = {},
    panel: @Composable BoxScope.() -> Unit = {},
    podium: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    val pinchThreshold = with(density) { PINCH_DISTANCE.toPx() }
    val reduced = PodiumTheme.motion.reduced
    SideEffect { state.reduced = reduced }
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .pointerInput(state) { pinchGestures(state, pinchThreshold) },
    ) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        // Animation values are read only where they're drawn: the app inside never recomposes for them.
        val out by remember(state) { derivedStateOf { state.depth.value > 0.001f } }

        if (out) Space(state, spaceTint, still = reduced)

        // The object: one layer, so the face, its edge and its stickers move as one.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val pose = Pose.at(state.depth.value, state.zoom.value, state.edit.value)
                    scaleX = pose.scale
                    scaleY = pose.scale
                    translationX = pose.offsetX * w
                    translationY = pose.offsetY * h
                    rotationY = pose.rotationY
                    rotationX = pose.rotationX
                    cameraDistance = CAMERA_DISTANCE * density.density
                },
        ) {
            if (out) {
                // The slab's edge and its shadow, behind the face, in the face's own coordinates.
                Canvas(Modifier.fillMaxSize()) {
                    val p = state.depth.value
                    val r = CORNER.toPx() * p
                    val thick = Offset(EDGE_X.toPx(), EDGE_Y.toPx()) * p
                    // A soft contact shadow under the slab.
                    drawRoundRect(
                        Color.Black.copy(alpha = 0.55f * p),
                        topLeft = Offset(thick.x * 2.2f, thick.y * 3.2f),
                        size = size,
                        cornerRadius = CornerRadius(r * 1.2f),
                    )
                    // The edge: stacked steps of the body's colour, darkening away from the face.
                    val steps = 8
                    for (i in steps downTo 1) {
                        val f = i / steps.toFloat()
                        drawRoundRect(
                            lerp(edgeColor, Color.Black, 0.25f + 0.35f * f),
                            topLeft = thick * f,
                            size = size,
                            cornerRadius = CornerRadius(r),
                        )
                    }
                }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val p = state.depth.value
                        if (p > 0.001f) {
                            shape = RoundedCornerShape(CORNER * p)
                            clip = true
                        }
                    }
                    .drawWithContent {
                        drawContent()
                        val p = state.depth.value
                        if (p > 0.001f) {
                            // Light from the upper left: the far corner a touch darker, a fine rim.
                            drawRect(
                                Brush.linearGradient(
                                    0f to Color.White.copy(alpha = 0.06f * p),
                                    0.5f to Color.Transparent,
                                    1f to Color.Black.copy(alpha = 0.22f * p),
                                    start = Offset.Zero,
                                    end = Offset(size.width, size.height),
                                ),
                            )
                            val r = CORNER.toPx() * p
                            drawRoundRect(
                                Color.White.copy(alpha = 0.14f * p),
                                size = size,
                                cornerRadius = CornerRadius(r),
                                style = Stroke(width = 1.5.dp.toPx()),
                            )
                        }
                    },
            ) {
                val severalFingers = remember(state) { { state.fingers >= 2 } }
                CompositionLocalProvider(LocalSeveralFingers provides severalFingers) { podium() }
                stickers()
                if (state.phase == SpacePhase.STICKER_EDITING) stickerEditor()
            }
            // While out, the object itself isn't operated: a tap brings it back (or, editing, the
            // editor above takes the touches).
            if (out && state.phase != SpacePhase.STICKER_EDITING) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(state) {
                            // Small beside the page, a tap brings the Podium close; close, a tap goes back to using it.
                            detectTapGestures {
                                if (state.phase != SpacePhase.PHYSICAL) return@detectTapGestures
                                if (state.focus == SpaceFocus.SETTINGS) state.focusPodium() else state.leave()
                            }
                        }
                        .semantics {
                            contentDescription = "Your Podium"
                            onClick("Look at your Podium, or return to it when it's close") {
                                if (state.focus == SpaceFocus.SETTINGS) state.focusPodium() else state.leave()
                                true
                            }
                        },
                )
            }
        }

        if (out) panel()
    }
}

/** Where the object sits for a given depth and editing amount (fractions of the window). */
internal data class Pose(val scale: Float, val offsetX: Float, val offsetY: Float, val rotationY: Float, val rotationX: Float) {
    companion object {
        /**
         * Flat → in the space (front-first: a slight turn and tilt, never more than ~11°): small at
         * the left beside the settings page ([zoom] 0) or large ([zoom] 1) → editing.
         */
        fun at(depthIn: Float, zoom: Float, edit: Float): Pose {
            // A settle may carry a little past its end (it kept the hand's speed): further into the
            // space is fine, but never larger than the flat Podium.
            val depth = depthIn.coerceIn(0f, 1.08f)
            val besidePage = Pose(scale = 0.32f, offsetX = -0.3f, offsetY = -0.04f, rotationY = 11f, rotationX = 5f)
            val close = Pose(scale = 0.6f, offsetX = -0.11f, offsetY = -0.05f, rotationY = 10f, rotationX = 4f)
            val editing = Pose(scale = 0.74f, offsetX = 0f, offsetY = -0.08f, rotationY = 2f, rotationX = 1.5f)
            val flat = Pose(1f, 0f, 0f, 0f, 0f)
            val out = flat.towards(besidePage.towards(close, zoom), depth)
            return out.towards(editing, edit * depth)
        }
    }

    private fun towards(o: Pose, t: Float) = Pose(
        scale + (o.scale - scale) * t,
        offsetX + (o.offsetX - offsetX) * t,
        offsetY + (o.offsetY - offsetY) * t,
        rotationY + (o.rotationY - rotationY) * t,
        rotationX + (o.rotationX - rotationX) * t,
    )
}

/**
 * PERSONAL (D-65): two fingers pinching in on the flat Podium push the object back into its space,
 * and two fingers spreading on it in its space bring it back — the object follows the fingers both
 * ways, from wherever it is, and the release carries on with their speed. One finger is never
 * touched: the gesture is only claimed (consumed, in the Initial pass) once it's clearly a pinch,
 * and it is measured from that moment, so the object never jumps to catch up with fingers that had
 * already moved.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.pinchGestures(state: PodiumSpaceState, threshold: Float) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var start = 0f
        var claimed = false
        var startedOut = false
        var claimDistance = 0f
        var claimDepth = 0f
        var value = 0f
        val velocity = VelocityTracker1D(isDataDifferential = false)
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.filter { it.pressed }
            state.fingers = pressed.size
            // The lift is a sample too: fingers that stopped before letting go carry no speed.
            if (claimed && pressed.size < 2) velocity.addDataPoint(event.changes.first().uptimeMillis, value)
            if (pressed.isEmpty()) break
            if (pressed.size < 2) {
                if (claimed) break
                start = 0f
                continue
            }
            if (state.phase == SpacePhase.STICKER_EDITING) continue // the editor's own two fingers
            val d = hypot(pressed[0].position.x - pressed[1].position.x, pressed[0].position.y - pressed[1].position.y)
            if (start == 0f) start = d
            if (!claimed) {
                val out = state.phase == SpacePhase.PHYSICAL
                val recognised = if (out) d - start > threshold && d > start * 1.3f else start - d > threshold && d < start * 0.78f
                if (recognised) {
                    claimed = true
                    startedOut = out
                    claimDistance = d
                    claimDepth = state.depth.value
                }
            }
            if (claimed) {
                // Closing the fingers by half their distance takes the object all the way into its
                // space; opening them by 60 % brings it all the way back — and either way, reversing
                // the fingers reverses the object.
                val span = claimDistance * if (startedOut) SPREAD_SPAN else PINCH_SPAN
                value = (claimDepth + (claimDistance - d) / span).coerceIn(0f, 1f)
                state.follow(value)
                velocity.addDataPoint(event.changes.first().uptimeMillis, value)
                event.changes.forEach { it.consume() }
            }
        }
        state.fingers = 0
        if (claimed) state.release(velocity.calculateVelocity(), startedOut)
    }
}

/** Fingers closing by this fraction of their distance (when claimed) take the object all the way in. */
private const val PINCH_SPAN = 0.5f

/** Fingers opening by this fraction of their distance bring it all the way back. */
private const val SPREAD_SPAN = 0.6f

/**
 * The space: dark, nearly empty — a few faint stars drifting very slowly, and four glints that
 * brighten and fade over many seconds. They are there to give the space depth, not to be watched:
 * as the object moves back the nearer specks gather in a touch more than the far ones, and as it
 * comes close they slide a little the other way (parallax). Procedural, one layer, nothing decoded
 * and nothing allocated per frame. [still]: reduced motion — no drift, no twinkle.
 */
@Composable
private fun Space(state: PodiumSpaceState, tint: Color, still: Boolean) {
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(still) {
        if (still) return@LaunchedEffect
        val start = withFrameMillis { it } - now
        while (isActive) withFrameMillis { now = it - start }
    }
    val stars = remember { Starfield.generate() }
    Spacer(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = state.depth.value.coerceIn(0f, 1f) }
            .drawWithCache {
                val diagonal = hypot(size.width, size.height)
                // The faintest vignette: the edges of the space a little darker.
                val vignette = Brush.radialGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f)),
                    center = Offset(size.width * 0.4f, size.height * 0.55f),
                    radius = diagonal * 0.65f,
                )
                onDrawBehind {
                    drawRect(tint)
                    drawRect(vignette)
                    val t = now / 1000f
                    val depth = state.depth.value.coerceIn(0f, 1f)
                    val zoom = state.zoom.value
                    val middle = Offset(size.width / 2f, size.height / 2f)
                    for (s in stars.dust) {
                        // Drift: a few dp a second, wrapping; a slow shimmer.
                        val y = ((s.y * size.height + t * s.speed * density) % size.height + size.height) % size.height
                        val x = s.x * size.width + sin(t * 0.07f + s.phase) * 6f * density
                        // Parallax, by how near the speck is (its size): gathering in as the object
                        // recedes, sliding the other way as it comes close.
                        val near = (s.radius - 0.4f) / 0.9f
                        val gather = 1f - 0.05f * near * depth
                        val p = middle + (Offset(x, y) - middle) * gather + Offset(-PARALLAX_DP * density * near * zoom, 0f)
                        val twinkle = 0.75f + 0.25f * sin(t * s.twinkle + s.phase)
                        drawCircle(Color.White.copy(alpha = s.alpha * twinkle), radius = s.radius * density, center = p)
                    }
                    for (g in stars.glints) {
                        val pulse = ((sin(t * (2 * PI.toFloat() / g.period) + g.phase) + 1f) / 2f)
                        val a = 0.08f + 0.42f * pulse * pulse
                        val arm = g.size * density * (0.7f + 0.3f * pulse)
                        withTransform({
                            translate(g.x * size.width, g.y * size.height)
                            scale(arm, arm, Offset.Zero)
                        }) { drawPath(UnitGlint, Color.White.copy(alpha = a)) }
                    }
                }
            },
    )
}

/** A glint's four-pointed shape at unit size, scaled where it's drawn. */
private val UnitGlint = Path().apply {
    moveTo(0f, -1f); quadraticTo(0f, 0f, 1f, 0f); quadraticTo(0f, 0f, 0f, 1f)
    quadraticTo(0f, 0f, -1f, 0f); quadraticTo(0f, 0f, 0f, -1f); close()
}

/** How far the nearest specks slide as the object comes close. */
private const val PARALLAX_DP = 10f

/** The stars, fixed for a run: a few dozen points and four glints. */
private class Starfield(val dust: List<Dust>, val glints: List<Glint>) {
    class Dust(val x: Float, val y: Float, val radius: Float, val alpha: Float, val speed: Float, val twinkle: Float, val phase: Float)
    class Glint(val x: Float, val y: Float, val size: Float, val period: Float, val phase: Float)

    companion object {
        fun generate(seed: Int = 54): Starfield {
            val r = Random(seed)
            val dust = List(DUST_COUNT) {
                Dust(
                    x = r.nextFloat(), y = r.nextFloat(),
                    radius = 0.4f + r.nextFloat() * 0.9f,
                    alpha = 0.08f + r.nextFloat() * 0.3f,
                    speed = 1.5f + r.nextFloat() * 3f,
                    twinkle = 0.2f + r.nextFloat() * 0.6f,
                    phase = r.nextFloat() * 6.28f,
                )
            }
            val glints = List(GLINT_COUNT) {
                Glint(x = 0.08f + r.nextFloat() * 0.84f, y = 0.06f + r.nextFloat() * 0.88f, size = 3f + r.nextFloat() * 3f, period = 7f + r.nextFloat() * 6f, phase = r.nextFloat() * 6.28f)
            }
            return Starfield(dust, glints)
        }

        private const val DUST_COUNT = 42
        private const val GLINT_COUNT = 4
    }
}

private val PINCH_DISTANCE = 40.dp
private val CORNER = 40.dp
private val EDGE_X = 14.dp
private val EDGE_Y = 18.dp
private const val CAMERA_DISTANCE = 18f
