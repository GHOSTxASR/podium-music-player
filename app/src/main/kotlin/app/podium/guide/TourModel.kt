package app.podium.guide

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.podium.core.designsystem.shell.ShellPalette
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

// The model, in its own units: a 200 × 380 slab — the display above, the Wheel below.
internal const val MW = 200f
internal const val MH = 380f
private const val BODY_R = 28f
private val SCREEN = Rect(14f, 14f, 186f, 214f)
private const val SCREEN_R = 12f
private const val ROWS_TOP = 48f
private const val ROW_H = 26f
private val MINI = Rect(22f, 186f, 178f, 208f)
internal val WHEEL = Offset(100f, 296f)
internal const val WHEEL_R = 68f
internal const val CENTER_R = 23f
internal const val LABEL_R = 47f
private val MODEL_CENTER = Offset(MW / 2, MH / 2)

/** Degrees of the Wheel per row of the menu, and how far a try must turn to count. */
private const val DEG_PER_ROW = 50f
internal const val TURN_TO_PASS = 150f

/** How long the listener's own try stays on the model before the demonstration comes back. */
private const val HOLD_S = 3.5f
private const val FLY_S = 0.9f

/** Where the stickers land on the model (index 1 star, 2 heart, 3 bolt). */
private val STICK_AT = mapOf(1 to Offset(56f, 112f), 2 to Offset(146f, 98f), 3 to Offset(126f, 162f))
private val STICK_ROT = mapOf(1 to -12f, 2 to 10f, 3 to -4f)
private const val STICK_SIZE = 38f
private val STICKER_COLORS = mapOf(1 to Color(0xFFFFD23F), 2 to Color(0xFFFF6B9A), 3 to Color(0xFF4DA3FF))

/** A point on a circle round the Wheel's centre ([deg] clockwise from the right). */
internal fun onRing(deg: Float, r: Float): Offset {
    val a = deg * PI.toFloat() / 180f
    return Offset(WHEEL.x + r * cos(a), WHEEL.y + r * sin(a))
}

private fun ease(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

/** In over [a0]..[a1], out over [b0]..[b1]. */
private fun fade(u: Float, a0: Float, a1: Float, b0: Float, b1: Float) = min(ease((u - a0) / (a1 - a0)), 1f - ease((u - b0) / (b1 - b0)))

private fun wrap(deg: Float): Float {
    var d = deg % 360f
    if (d > 180f) d -= 360f
    if (d < -180f) d += 360f
    return d
}

/** The model's colours: the listener's own finish (Glass, which has no body colour, shows graphite). */
internal class ModelInk(val bodyTop: Color, val bodyBottom: Color, val ring: Color, val ringRim: Color, val center: Color, val legend: Color) {
    val display = Color(0xFF0C0D10)

    companion object {
        fun of(p: ShellPalette): ModelInk =
            if (p.isGlass || p.bodyTop == Color.Unspecified || p.ring == Color.Unspecified) {
                ModelInk(Color(0xFF3B3E45), Color(0xFF222428), Color(0xFF2B2D32), Color(0xFF4C4F57), Color(0xFF33353B), Color(0xFFB8BBC3))
            } else {
                ModelInk(p.bodyTop, p.bodyBottom, p.ring, p.ringRim, p.center, p.legend)
            }
    }
}

/** The model's words, measured once: model units for what's on the model, dp for the page beside it. */
internal class ModelLabels(private val measurer: TextMeasurer, private val density: Density, base: TextStyle) {
    private val plain = base.copy(color = Color.White, shadow = null, lineHeight = TextUnit.Unspecified)
    private fun unit(text: String, size: Float, weight: FontWeight = FontWeight.Normal): TextLayoutResult =
        measurer.measure(text, plain.copy(fontSize = with(density) { size.toSp() }, fontWeight = weight))

    private fun page(text: String, size: Int, weight: FontWeight = FontWeight.Normal): TextLayoutResult =
        measurer.measure(text, plain.copy(fontSize = size.sp, fontWeight = weight))

    val podium = unit("Podium", 11f, FontWeight.SemiBold)
    val musicTitle = unit("Music", 11f, FontWeight.SemiBold)
    val home = listOf("Music", "Online", "Shuffle songs", "Now Playing", "Settings").map { unit(it, 11.5f) }
    val music = listOf("Songs", "Albums", "Artists", "Playlists", "Search").map { unit(it, 11.5f) }
    val menu = unit("MENU", 7.5f, FontWeight.SemiBold)
    val stickers = page("Stickers", 17, FontWeight.SemiBold)
    val help = page("Help", 14)
    val turnOff = page("Turn off Podium", 14)
}

/** Where the camera looks (model units), how close, how far the model sits aside, and the spotlight. */
internal data class Shot(
    val fx: Float, val fy: Float, val zoom: Float, val shift: Float,
    val spotX: Float, val spotY: Float, val spotR: Float, val spotA: Float,
) {
    companion object {
        fun of(step: GuideStep): Shot = when (step) {
            GuideStep.WELCOME -> Shot(100f, 190f, 1f, 0f, 100f, 190f, 160f, 0f)
            GuideStep.TURN -> Shot(100f, 224f, 1.22f, 0f, WHEEL.x, WHEEL.y, 84f, 1f)
            GuideStep.CENTER -> Shot(100f, 258f, 1.45f, 0f, WHEEL.x, WHEEL.y, 31f, 1f)
            GuideStep.MENU -> Shot(100f, 250f, 1.45f, 0f, WHEEL.x, WHEEL.y - LABEL_R, 22f, 1f)
            GuideStep.PLAY -> Shot(100f, 262f, 1.45f, 0f, WHEEL.x, WHEEL.y + LABEL_R, 22f, 1f)
            GuideStep.PINCH -> Shot(100f, 190f, 0.96f, 0f, 100f, 190f, 160f, 0f)
            GuideStep.STICKERS -> Shot(100f, 190f, 0.6f, -0.24f, 100f, 190f, 160f, 0f)
        }
    }
}

/** The camera, moving slowly from shot to shot — the close-up is the point. */
internal class Camera {
    val fx = Animatable(100f)
    val fy = Animatable(190f)
    val zoom = Animatable(1f)
    val shift = Animatable(0f)
    val spotX = Animatable(100f)
    val spotY = Animatable(190f)
    val spotR = Animatable(160f)
    val spotA = Animatable(0f)

    suspend fun moveTo(s: Shot, reduced: Boolean) = coroutineScope {
        val spec = spring<Float>(dampingRatio = 0.92f, stiffness = 60f)
        listOf(fx to s.fx, fy to s.fy, zoom to s.zoom, shift to s.shift, spotX to s.spotX, spotY to s.spotY, spotR to s.spotR, spotA to s.spotA)
            .forEach { (a, v) -> launch { if (reduced) a.snapTo(v) else a.animateTo(v, spec) } }
    }
}

/** The stage and the model's place on it this frame: model → stage is `m × scale + (ox, oy)`. */
internal class StageGeometry {
    var scale = 1f
        private set
    var ox = 0f
        private set
    var oy = 0f
        private set
    var card = Rect.Zero
        private set
    var tiles: List<Rect> = emptyList()
        private set
    var cardAlpha = 0f
        private set

    fun update(scope: DrawScope, camera: Camera, scene: Scene) = with(scope) {
        val w = size.width
        val h = size.height
        val base = min(w * 0.7f / MW, h * 0.9f / MH)
        scale = base * camera.zoom.value
        ox = w / 2 + camera.shift.value * w - camera.fx.value * scale
        oy = h / 2 - camera.fy.value * scale + scene.bob * scale
        cardAlpha = (-camera.shift.value / 0.24f).coerceIn(0f, 1f)
        val pad = 12.dp.toPx()
        // The page beside the model: in from the right as the camera moves aside.
        val slide = (1f - cardAlpha) * 40.dp.toPx()
        card = Rect(w * 0.5f + slide, h * 0.1f, w - 4.dp.toPx() + slide, h * 0.9f)
        val tile = (card.width - pad * 3) / 2
        val top = card.top + pad + 30.dp.toPx()
        tiles = List(4) { i ->
            val col = i % 2
            val row = i / 2
            val left = card.left + pad + col * (tile + pad)
            val t = top + row * (tile + pad)
            Rect(left, t, left + tile, t + tile)
        }
    }

    fun toScreen(m: Offset) = Offset(m.x * scale + ox, m.y * scale + oy)

    fun toModel(p: Offset) = Offset((p.x - ox) / scale, (p.y - oy) / scale)

    /** The angle round the Wheel under [p], or null when [p] isn't on the Wheel. */
    fun angleAt(p: Offset): Float? {
        val v = toModel(p) - WHEEL
        val d = v.getDistance()
        return if (d in (CENTER_R - 6f)..(WHEEL_R + 22f)) Math.toDegrees(atan2(v.y, v.x).toDouble()).toFloat() else null
    }

    fun isCenter(m: Offset) = (m - WHEEL).getDistance() <= CENTER_R + 8f

    /** On the Wheel's ring, within 42° of [deg] (−90 Menu, 90 play/pause). */
    fun isOnWheelAt(m: Offset, deg: Float): Boolean {
        val v = m - WHEEL
        val d = v.getDistance()
        if (d < CENTER_R + 2f || d > WHEEL_R + 14f) return false
        return abs(wrap(Math.toDegrees(atan2(v.y, v.x).toDouble()).toFloat() - deg)) <= 42f
    }

    fun tileAt(p: Offset): Int = if (cardAlpha < 0.5f) -1 else tiles.indexOfFirst { it.contains(p) }
}

internal class Finger(val x: Float, val y: Float, val alpha: Float, val press: Float = 0f, val tile: Int = -1, val approach: Float = 0f)
internal class Ripple(val x: Float, val y: Float, val t: Float)
internal class Fly(val index: Int, val t: Float)

/** Everything the model shows in one frame. */
internal class Scene(
    val lit: Float = 0f,
    val page: Float = 0f,
    val playing: Boolean = false,
    val progress: Float = 0.3f,
    val note: Float = -1f,
    val pinch: Float = 0f,
    val centerDown: Boolean = false,
    val fingers: List<Finger> = emptyList(),
    val ripple: Ripple? = null,
    val fly: Fly? = null,
    val placed: List<Int> = emptyList(),
    val placedAlpha: Float = 1f,
    val bob: Float = 0f,
    val tilePressed: Int = -1,
)

/** The demonstrations: each checkpoint's short loop, as a function of the loop's time [u] (seconds). */
internal object Demo {
    fun period(step: GuideStep): Float = when (step) {
        GuideStep.WELCOME -> 8f
        GuideStep.TURN -> 3.4f
        GuideStep.CENTER, GuideStep.MENU, GuideStep.PLAY -> 3.2f
        GuideStep.PINCH -> 3.8f
        GuideStep.STICKERS -> 4.2f
    }

    /** With reduced motion: one telling frame, held. */
    fun stillFrame(step: GuideStep): Float = when (step) {
        GuideStep.WELCOME -> 0f
        GuideStep.TURN -> 1.6f
        GuideStep.CENTER, GuideStep.MENU, GuideStep.PLAY -> 1.6f
        GuideStep.PINCH -> 2.4f
        GuideStep.STICKERS -> 2.6f
    }

    fun scene(step: GuideStep, u: Float): Scene = when (step) {
        GuideStep.WELCOME -> Scene(lit = 2f + 2f * sin(u * 0.8f), bob = sin(u * 0.785f) * 3f)
        GuideStep.TURN -> {
            val sweep = ease((u - 0.3f) / 2.2f)
            val pos = onRing(-160f + 220f * sweep, LABEL_R)
            Scene(lit = 4f * sweep * (1f - ease((u - 2.8f) / 0.6f)), fingers = listOf(Finger(pos.x, pos.y, fade(u, 0f, 0.3f, 2.5f, 2.8f))))
        }
        GuideStep.CENTER -> press(u, from = Offset(152f, 366f), to = WHEEL).let { (finger, ripple, pressed) ->
            Scene(
                page = ease((u - 0.75f) / 0.5f) * (1f - ease((u - 2.6f) / 0.4f)),
                centerDown = pressed,
                fingers = listOf(finger),
                ripple = ripple,
            )
        }
        GuideStep.MENU -> press(u, from = Offset(152f, 214f), to = onRing(-90f, LABEL_R)).let { (finger, ripple, _) ->
            Scene(page = (1f - ease((u - 0.75f) / 0.5f) + ease((u - 2.6f) / 0.4f)).coerceIn(0f, 1f), fingers = listOf(finger), ripple = ripple)
        }
        GuideStep.PLAY -> press(u, from = Offset(152f, 392f), to = onRing(90f, LABEL_R)).let { (finger, ripple, _) ->
            val playing = u in 0.75f..2.6f
            Scene(
                playing = playing,
                progress = 0.3f + 0.05f * (u - 0.75f).coerceIn(0f, 1.85f),
                note = if (u in 0.8f..2f) (u - 0.8f) / 1.2f else -1f,
                fingers = listOf(finger),
                ripple = ripple,
            )
        }
        GuideStep.PINCH -> {
            val t = ease((u - 0.4f) / 1.5f)
            val a = fade(u, 0f, 0.3f, 2.4f, 2.7f)
            val f1 = lerp(Offset(28f, 70f), Offset(84f, 168f), t)
            val f2 = lerp(Offset(172f, 330f), Offset(116f, 212f), t)
            Scene(pinch = t * (1f - ease((u - 3f) / 0.6f)), fingers = listOf(Finger(f1.x, f1.y, a), Finger(f2.x, f2.y, a)))
        }
        GuideStep.STICKERS -> Scene(
            fingers = listOf(Finger(0f, 0f, fade(u, 0f, 0.2f, 1.2f, 1.5f), press = if (u in 0.8f..1f) 1f else 0f, tile = 2, approach = ease((u - 0.2f) / 0.6f))),
            fly = if (u in 1f..2f) Fly(2, u - 1f) else null,
            placed = if (u >= 2f) listOf(2) else emptyList(),
            placedAlpha = 1f - ease((u - 3.6f) / 0.4f),
            tilePressed = if (u in 0.8f..1.05f) 2 else -1,
        )
    }

    /** A finger travelling [from] → [to], pressing at 0.6–0.85 s, then leaving. */
    private fun press(u: Float, from: Offset, to: Offset): Triple<Finger, Ripple?, Boolean> {
        val pos = lerp(from, to, ease(u / 0.6f))
        val pressed = u in 0.6f..0.85f
        val ripple = if (u in 0.7f..1.3f) Ripple(to.x, to.y, (u - 0.7f) / 0.6f) else null
        return Triple(Finger(pos.x, pos.y, fade(u, 0f, 0.2f, 2.2f, 2.5f), if (pressed) 1f else 0f), ripple, pressed)
    }
}

/** The listener's own tries on the model, for one checkpoint. Times are the stage clock's seconds. */
internal class TourUser(step: GuideStep) {
    var down = false
    var done = false
    private var until = -1f
    var sweep = 0f
    private var pageFrom = if (step == GuideStep.MENU) 1f else 0f
    private var pageTo = pageFrom
    private var pageStart = -10f
    private var playing = false
    private var playAt = -10f
    private var noteAt = -10f
    var pinch = 0f
        private set
    private var pinchFrom = 0f
    private var pinchTarget = 0f
    private var pinchAt = -10f
    private var releasing = false
    private var pressAt = -10f
    private var pressAtPoint = Offset.Zero
    private val placed = mutableListOf<Int>()
    private var flyIndex = -1
    private var flyAt = -10f

    fun active(now: Float) = down || now < until

    fun touched(now: Float) {
        until = now + HOLD_S
    }

    fun press(at: Offset, now: Float) {
        pressAtPoint = at
        pressAt = now
        touched(now)
    }

    private fun pageAt(now: Float) = pageFrom + (pageTo - pageFrom) * ease((now - pageStart) / 0.45f)

    fun slidePage(to: Float, now: Float) {
        pageFrom = pageAt(now)
        pageTo = to
        pageStart = now
    }

    fun togglePlay(now: Float) {
        playing = !playing
        if (playing) {
            playAt = now
            noteAt = now
        }
    }

    fun pinchTo(p: Float) {
        releasing = false
        pinch = p
    }

    /** Fingers lifted: a pinch that counted stays outside; one that didn't springs back. */
    fun releasePinch(now: Float) {
        pinchFrom = pinch
        pinchTarget = if (done) 1f else 0f
        pinchAt = now
        releasing = true
    }

    fun fly(index: Int, now: Float) {
        if (flyIndex >= 0 && flyIndex !in placed) placed += flyIndex
        flyIndex = index
        flyAt = now
        touched(now)
    }

    fun scene(step: GuideStep, now: Float): Scene {
        val sincePress = now - pressAt
        val sinceFly = now - flyAt
        val flying = flyIndex >= 0 && sinceFly < FLY_S
        val landed = if (flyIndex >= 0 && !flying && flyIndex !in placed) placed + flyIndex else placed.toList()
        return Scene(
            lit = if (step == GuideStep.TURN) (abs(sweep) / DEG_PER_ROW).coerceIn(0f, 4f) else 0f,
            page = pageAt(now),
            playing = playing,
            progress = if (playing) 0.3f + 0.05f * (now - playAt) else 0.3f,
            note = (now - noteAt).let { if (it in 0f..1.2f) it / 1.2f else -1f },
            pinch = if (releasing) pinchFrom + (pinchTarget - pinchFrom) * ease((now - pinchAt) / 0.5f) else pinch,
            centerDown = step == GuideStep.CENTER && sincePress in 0f..0.18f,
            ripple = if (sincePress in 0f..0.6f) Ripple(pressAtPoint.x, pressAtPoint.y, sincePress / 0.6f) else null,
            fly = if (flying) Fly(flyIndex, sinceFly / FLY_S) else null,
            placed = landed,
        )
    }
}

/**
 * The listener trying it on the model: around the Wheel (Turn), the center, Menu or play/pause
 * (a press on that part), two fingers together (Pinch), a sticker on the page (Stickers).
 */
internal suspend fun PointerInputScope.tourGestures(step: GuideStep, user: TourUser, geo: StageGeometry, now: () -> Float, pass: () -> Unit) {
    fun done() {
        if (!user.done) {
            user.done = true
            pass()
        }
    }
    awaitEachGesture {
        val down = awaitFirstDown()
        try {
            when (step) {
                GuideStep.TURN -> {
                    var prev = geo.angleAt(down.position)
                    user.down = true
                    user.touched(now())
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val a = geo.angleAt(change.position)
                        if (a != null && prev != null) {
                            user.sweep += wrap(a - prev)
                            if (abs(user.sweep) >= TURN_TO_PASS) done()
                        }
                        prev = a
                        change.consume()
                        user.touched(now())
                    }
                }
                GuideStep.CENTER, GuideStep.MENU, GuideStep.PLAY -> {
                    val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                    val m = geo.toModel(up.position)
                    val hit = when (step) {
                        GuideStep.CENTER -> geo.isCenter(m)
                        GuideStep.MENU -> geo.isOnWheelAt(m, -90f)
                        else -> geo.isOnWheelAt(m, 90f)
                    }
                    if (hit) {
                        user.press(if (step == GuideStep.CENTER) WHEEL else onRing(if (step == GuideStep.MENU) -90f else 90f, LABEL_R), now())
                        when (step) {
                            GuideStep.CENTER -> user.slidePage(1f, now())
                            GuideStep.MENU -> user.slidePage(0f, now())
                            else -> user.togglePlay(now())
                        }
                        done()
                    }
                }
                GuideStep.PINCH -> {
                    var start = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            user.down = true
                            val d = (pressed[0].position - pressed[1].position).getDistance()
                            if (start == 0f) start = d
                            user.pinchTo(((start - d) / (start * 0.55f)).coerceIn(0f, 1f))
                            if (user.pinch > 0.6f) done()
                            event.changes.forEach { it.consume() }
                            user.touched(now())
                        }
                    }
                    if (user.down) user.releasePinch(now())
                }
                GuideStep.STICKERS -> {
                    val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                    val tile = geo.tileAt(up.position)
                    if (tile >= 1) {
                        user.fly(tile, now())
                        done()
                    }
                }
                GuideStep.WELCOME -> Unit
            }
        } finally {
            if (user.down) user.touched(now())
            user.down = false
        }
    }
}

// --- Drawing -----------------------------------------------------------------------------------

private val stars = Random(57).let { r -> List(34) { floatArrayOf(r.nextFloat(), r.nextFloat(), 0.5f + r.nextFloat(), r.nextFloat() * 6.28f) } }

/** The whole stage for one frame: stars, the model, the spotlight, the page, a flying sticker, fingers. */
internal fun DrawScope.drawStage(geo: StageGeometry, camera: Camera, ink: ModelInk, labels: ModelLabels, scene: Scene, clock: Float) {
    val space = max(scene.pinch, geo.cardAlpha)
    if (space > 0.01f) {
        for (s in stars) {
            val twinkle = 0.6f + 0.4f * sin(clock * 0.7f + s[3])
            drawCircle(Color.White.copy(alpha = 0.5f * space * twinkle), s[2] * 1.dp.toPx(), Offset(s[0] * size.width, s[1] * size.height))
        }
    }
    val k = 1f - 0.38f * scene.pinch
    withTransform({
        translate(geo.ox, geo.oy)
        scale(geo.scale, geo.scale, pivot = Offset.Zero)
        scale(k, k, pivot = MODEL_CENTER)
    }) {
        drawModel(ink, labels, scene)
    }
    // The spotlight: everything but the part in question dims, a fine ring round it.
    val spotA = camera.spotA.value
    if (spotA > 0.01f) {
        val c = geo.toScreen(Offset(camera.spotX.value, camera.spotY.value))
        val r = camera.spotR.value * geo.scale
        drawRect(
            Brush.radialGradient(
                0f to Color.Transparent,
                0.62f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.5f * spotA),
                center = c,
                radius = r / 0.62f,
            ),
        )
        drawCircle(Color.White.copy(alpha = 0.22f * spotA), r, c, style = Stroke(1.5.dp.toPx()))
    }
    if (geo.cardAlpha > 0.01f) drawPage(geo, labels, scene)
    scene.fly?.let { fly ->
        val from = geo.tiles.getOrNull(fly.index)?.center ?: return@let
        val target = STICK_AT.getValue(fly.index)
        val to = geo.toScreen(MODEL_CENTER + (target - MODEL_CENTER) * k)
        val t = ease(fly.t)
        // An arc up and over onto the Podium.
        val lift = Offset(0f, -90.dp.toPx())
        val mid = lerp(from, to, 0.5f) + lift
        val p = lerp(lerp(from, mid, t), lerp(mid, to, t), t)
        val startSize = geo.tiles[fly.index].width * 0.56f
        val endSize = STICK_SIZE * geo.scale * k
        drawSticker(fly.index, p, startSize + (endSize - startSize) * t, STICK_ROT.getValue(fly.index) * t, 1f)
    }
    for (f in scene.fingers) {
        if (f.alpha <= 0.01f) continue
        val p = if (f.tile >= 0) {
            val tile = geo.tiles.getOrNull(f.tile) ?: continue
            lerp(Offset(tile.center.x + 30.dp.toPx(), geo.card.bottom + 30.dp.toPx()), tile.center, f.approach)
        } else {
            geo.toScreen(Offset(f.x, f.y))
        }
        drawFinger(p, f.alpha, f.press)
    }
}

/** The model itself, in model units. */
private fun DrawScope.drawModel(ink: ModelInk, labels: ModelLabels, scene: Scene) {
    val p = scene.pinch
    val body = Size(MW, MH)
    val corner = CornerRadius(BODY_R)
    if (p > 0.001f) {
        // Out in its space: a slab, with an edge and a shadow.
        drawRoundRect(Color.Black.copy(alpha = 0.5f * p), topLeft = Offset(12f * p, 18f * p), size = body, cornerRadius = corner)
        for (i in 6 downTo 1) {
            val f = i / 6f
            drawRoundRect(androidx.compose.ui.graphics.lerp(ink.bodyBottom, Color.Black, 0.3f + 0.3f * f), topLeft = Offset(7f * p * f, 9f * p * f), size = body, cornerRadius = corner)
        }
    }
    drawRoundRect(Brush.verticalGradient(listOf(ink.bodyTop, ink.bodyBottom), startY = 0f, endY = MH), size = body, cornerRadius = corner)
    drawRoundRect(Color.White.copy(alpha = 0.1f), size = body, cornerRadius = corner, style = Stroke(1f))
    drawRoundRect(ink.display, topLeft = SCREEN.topLeft, size = SCREEN.size, cornerRadius = CornerRadius(SCREEN_R))
    clipRect(SCREEN.left, SCREEN.top, SCREEN.right, SCREEN.bottom) { drawScreen(labels, scene) }
    drawRoundRect(Color.White.copy(alpha = 0.07f), topLeft = SCREEN.topLeft, size = SCREEN.size, cornerRadius = CornerRadius(SCREEN_R), style = Stroke(1f))
    drawWheel(ink, labels, scene)
    for (i in scene.placed) drawSticker(i, STICK_AT.getValue(i), STICK_SIZE, STICK_ROT.getValue(i), scene.placedAlpha)
}

private val RowGrey = Color(0xFF8E9099)

/** The model's display: Home (or Music, after Center), the lit band, a small now-playing bar. */
private fun DrawScope.drawScreen(labels: ModelLabels, scene: Scene) {
    val home = -scene.page * SCREEN.width
    val music = (1f - scene.page) * SCREEN.width
    fun centred(t: TextLayoutResult, x: Float, y: Float, color: Color) = drawText(t, color, Offset(x - t.size.width / 2f, y - t.size.height / 2f))
    centred(labels.podium, 100f + home, 28f, Color.White)
    centred(labels.musicTitle, 100f + music, 28f, Color.White)
    drawLine(Color.White.copy(alpha = 0.08f), Offset(SCREEN.left + 10f, 40f), Offset(SCREEN.right - 10f, 40f), 0.8f)
    fun rows(texts: List<TextLayoutResult>, dx: Float, lit: Float) {
        drawRoundRect(Color.White.copy(alpha = 0.12f), topLeft = Offset(22f + dx, ROWS_TOP + lit * ROW_H + 1f), size = Size(156f, ROW_H - 2f), cornerRadius = CornerRadius(7f))
        texts.forEachIndexed { i, t ->
            val on = abs(i - lit) < 0.5f
            drawText(t, if (on) Color.White else RowGrey, Offset(32f + dx, ROWS_TOP + i * ROW_H + (ROW_H - t.size.height) / 2f))
        }
    }
    rows(labels.home, home, scene.lit)
    rows(labels.music, music, 0f)
    // Now playing: a glyph, a title's shape, the progress.
    drawRoundRect(Color.White.copy(alpha = 0.07f), topLeft = MINI.topLeft, size = MINI.size, cornerRadius = CornerRadius(11f))
    val g = Offset(MINI.left + 12f, MINI.center.y)
    if (scene.playing) {
        drawRect(Color.White, topLeft = Offset(g.x - 3.5f, g.y - 4f), size = Size(2.2f, 8f))
        drawRect(Color.White, topLeft = Offset(g.x + 1.3f, g.y - 4f), size = Size(2.2f, 8f))
    } else {
        drawPath(Path().apply { moveTo(g.x - 3f, g.y - 4.5f); lineTo(g.x + 4f, g.y); lineTo(g.x - 3f, g.y + 4.5f); close() }, Color.White)
    }
    drawLine(Color.White.copy(alpha = 0.5f), Offset(46f, 193f), Offset(118f, 193f), 3f, StrokeCap.Round)
    drawLine(Color.White.copy(alpha = 0.28f), Offset(46f, 199.5f), Offset(94f, 199.5f), 2.4f, StrokeCap.Round)
    val end = 46f + 124f * scene.progress.coerceIn(0f, 1f)
    drawLine(Color.White.copy(alpha = 0.14f), Offset(46f, 204.5f), Offset(170f, 204.5f), 1.4f, StrokeCap.Round)
    drawLine(Color.White.copy(alpha = 0.7f), Offset(46f, 204.5f), Offset(end, 204.5f), 1.4f, StrokeCap.Round)
    if (scene.note >= 0f) drawNote(Offset(156f, 178f - 46f * scene.note), 1f - scene.note)
}

private fun DrawScope.drawNote(at: Offset, alpha: Float) {
    val c = Color.White.copy(alpha = alpha)
    drawCircle(c, 3.4f, at)
    drawLine(c, Offset(at.x + 3f, at.y), Offset(at.x + 3f, at.y - 12f), 1.3f)
    drawLine(c, Offset(at.x + 3f, at.y - 12f), Offset(at.x + 8f, at.y - 9f), 1.3f, StrokeCap.Round)
}

private fun DrawScope.drawWheel(ink: ModelInk, labels: ModelLabels, scene: Scene) {
    drawCircle(ink.ring, WHEEL_R, WHEEL)
    drawCircle(ink.ringRim, WHEEL_R, WHEEL, style = Stroke(1.2f))
    val menu = labels.menu
    drawText(menu, ink.legend, Offset(WHEEL.x - menu.size.width / 2f, WHEEL.y - LABEL_R - menu.size.height / 2f))
    skipGlyph(Offset(WHEEL.x + LABEL_R, WHEEL.y), ink.legend, back = false)
    skipGlyph(Offset(WHEEL.x - LABEL_R, WHEEL.y), ink.legend, back = true)
    val pp = Offset(WHEEL.x, WHEEL.y + LABEL_R)
    drawPath(Path().apply { moveTo(pp.x - 7f, pp.y - 4.5f); lineTo(pp.x - 1f, pp.y); lineTo(pp.x - 7f, pp.y + 4.5f); close() }, ink.legend)
    drawRect(ink.legend, topLeft = Offset(pp.x + 1.5f, pp.y - 4.5f), size = Size(1.8f, 9f))
    drawRect(ink.legend, topLeft = Offset(pp.x + 4.6f, pp.y - 4.5f), size = Size(1.8f, 9f))
    drawCircle(if (scene.centerDown) androidx.compose.ui.graphics.lerp(ink.center, Color.Black, 0.3f) else ink.center, CENTER_R, WHEEL)
    drawCircle(ink.ringRim, CENTER_R, WHEEL, style = Stroke(1f))
    scene.ripple?.let { r ->
        drawCircle(Color.White.copy(alpha = 0.5f * (1f - r.t)), 8f + 24f * r.t, Offset(r.x, r.y), style = Stroke(1.6f))
    }
}

private fun DrawScope.skipGlyph(c: Offset, color: Color, back: Boolean) {
    val d = if (back) -1f else 1f
    drawPath(Path().apply { moveTo(c.x - 4f * d, c.y - 4.5f); lineTo(c.x + 2f * d, c.y); lineTo(c.x - 4f * d, c.y + 4.5f); close() }, color)
    drawRect(color, topLeft = Offset(if (back) c.x - 3.8f else c.x + 2.2f, c.y - 4.5f), size = Size(1.6f, 9f))
}

/** The page beside the model (Stickers): its title, the gallery's tiles, Help and Turn off. */
private fun DrawScope.drawPage(geo: StageGeometry, labels: ModelLabels, scene: Scene) {
    val a = geo.cardAlpha
    val card = geo.card
    val r = CornerRadius(18.dp.toPx())
    drawRoundRect(Color(0xFF1C1C1F).copy(alpha = a), card.topLeft, card.size, r)
    drawRoundRect(Color.White.copy(alpha = 0.08f * a), card.topLeft, card.size, r, style = Stroke(1.dp.toPx()))
    val pad = 12.dp.toPx()
    drawText(labels.stickers, Color.White.copy(alpha = a), Offset(card.left + pad, card.top + pad))
    val tileR = CornerRadius(12.dp.toPx())
    geo.tiles.forEachIndexed { i, tile ->
        if (i == 0) {
            drawRoundRect(
                Color(0xFF6E7078).copy(alpha = a), tile.topLeft, tile.size, tileR,
                style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
            )
            val arm = tile.width * 0.14f
            val w = 2.dp.toPx()
            drawLine(Color.White.copy(alpha = a), tile.center - Offset(arm, 0f), tile.center + Offset(arm, 0f), w, StrokeCap.Round)
            drawLine(Color.White.copy(alpha = a), tile.center - Offset(0f, arm), tile.center + Offset(0f, arm), w, StrokeCap.Round)
        } else {
            drawRoundRect(Color(0xFF0E0E10).copy(alpha = a), tile.topLeft, tile.size, tileR)
            drawRoundRect(Color.White.copy(alpha = 0.08f * a), tile.topLeft, tile.size, tileR, style = Stroke(1.dp.toPx()))
            val flying = scene.fly?.index == i
            drawSticker(i, tile.center, tile.width * 0.56f, 0f, if (flying) 0.35f * a else a)
            if (scene.tilePressed == i) drawRoundRect(Color.White.copy(alpha = 0.1f * a), tile.topLeft, tile.size, tileR)
        }
    }
    val below = geo.tiles.last().bottom + pad * 1.5f
    drawText(labels.help, Color.White.copy(alpha = 0.85f * a), Offset(card.left + pad, below))
    drawText(labels.turnOff, Color(0xFFFF6961).copy(alpha = a), Offset(card.left + pad, below + labels.help.size.height + pad))
}

/** A sticker: its shape in its colour, on a white border that follows the shape. */
private fun DrawScope.drawSticker(index: Int, center: Offset, size: Float, rotation: Float, alpha: Float) {
    if (alpha <= 0.01f) return
    val path = stickerPath(index, center, size)
    rotate(rotation, center) {
        drawPath(path, Color.Black.copy(alpha = 0.25f * alpha), style = Stroke(size * 0.2f, join = StrokeJoin.Round))
        drawPath(path, Color.White.copy(alpha = alpha), style = Stroke(size * 0.16f, join = StrokeJoin.Round))
        drawPath(path, STICKER_COLORS.getValue(index).copy(alpha = alpha))
    }
}

private fun stickerPath(index: Int, c: Offset, s: Float): Path = Path().apply {
    when (index) {
        1 -> { // a star
            for (i in 0 until 10) {
                val r = if (i % 2 == 0) s * 0.5f else s * 0.22f
                val a = (-90f + i * 36f) * PI.toFloat() / 180f
                val p = Offset(c.x + r * cos(a), c.y + r * sin(a))
                if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
            }
            close()
        }
        2 -> { // a heart
            moveTo(c.x, c.y + s * 0.42f)
            cubicTo(c.x - s * 0.62f, c.y + s * 0.02f, c.x - s * 0.48f, c.y - s * 0.5f, c.x, c.y - s * 0.18f)
            cubicTo(c.x + s * 0.48f, c.y - s * 0.5f, c.x + s * 0.62f, c.y + s * 0.02f, c.x, c.y + s * 0.42f)
            close()
        }
        else -> { // a bolt
            val pts = listOf(0.1f to -0.5f, -0.3f to 0.05f, -0.02f to 0.05f, -0.14f to 0.5f, 0.32f to -0.1f, 0.04f to -0.1f)
            pts.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(c.x + x * s, c.y + y * s) else lineTo(c.x + x * s, c.y + y * s) }
            close()
        }
    }
}

/** A fingertip: a soft disc, a bright rim, a little smaller while pressing. */
private fun DrawScope.drawFinger(p: Offset, alpha: Float, press: Float) {
    val r = 17.dp.toPx() * (1f - 0.12f * press)
    drawCircle(Color.Black.copy(alpha = 0.25f * alpha), r + 2.dp.toPx(), p + Offset(0f, 2.dp.toPx()))
    drawCircle(Color.White.copy(alpha = (0.28f + 0.22f * press) * alpha), r, p)
    drawCircle(Color.White.copy(alpha = 0.9f * alpha), r, p, style = Stroke(2.dp.toPx()))
}
