package app.podium.core.designsystem.shell

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.podium.core.designsystem.R
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.interaction.InputTargetEffect
import app.podium.core.interaction.PodiumInput
import app.podium.core.interaction.WheelContext
import app.podium.core.interaction.rememberPodiumHaptics
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where the boot is (D-102), shared with the screen it reveals: the boot writes [time]; the screen
 * reads [reveal] while drawing (its layer's alpha, scale and focus) and never recomposes for it.
 */
@Stable
class BootReveal {
    /** Milliseconds into [BootTimeline]. */
    var time by mutableFloatStateOf(0f)
        internal set

    /** With reduced motion the mark crossfades to the menu instead of the dive: 0–1, or −1 while it doesn't. */
    internal var fade by mutableFloatStateOf(-1f)

    /** How far the menu has come into view: 0 hidden behind the mark, 1 all there is. */
    val reveal: Float get() = if (fade >= 0f) fade else BootTimeline.revealAt(time)

    /** Starts over (the device switched off and on again). */
    fun reset() {
        time = 0f
        fade = -1f
    }
}

/**
 * The boot's choreography (D-102), in milliseconds. A dot swells into a lens holding the P, drawn in
 * fine lines with a prism's fringes; the P resolves into solid silver, spins down to its edge, and
 * the whole wordmark unrolls from that sliver as from a turning cylinder; it settles, holds a beat,
 * and the camera dives through the O into the menu.
 */
internal object BootTimeline {
    const val DOT = 100f
    const val BUBBLE_LANDS = 560f
    const val BUBBLE_END = 650f
    const val HOLD_END = 1150f
    const val RESOLVE_END = 1500f
    const val SPIN_END = 1780f
    const val UNROLL_END = 2380f
    const val SETTLE_END = 2620f
    const val DIVE_START = 2900f
    const val END = 3550f

    fun phase(t: Float, from: Float, to: Float): Float = ((t - from) / (to - from)).coerceIn(0f, 1f)

    /** The dive's progress, eased in and out: the camera gathers speed into the O and lands softly. */
    fun dive(t: Float): Float {
        val v = phase(t, DIVE_START, END)
        val a = v.pow(2.2f)
        val b = (1f - v).pow(2.2f)
        return if (a + b == 0f) 0f else a / (a + b)
    }

    fun revealAt(t: Float): Float = if (t < DIVE_START) 0f else dive(t)
}

/**
 * Power-on (D-26, D-102). Podium's mark reveals itself — the P in a lens of fine lines, the
 * wordmark unrolling from it — and the camera dives through the O into the menu, which [reveal]
 * brings forward behind it. The startup chord plays as the lens opens ([onChime]); each beat lands
 * with a haptic. About 3.5 s; any Wheel press skips to a quick dive. With reduced motion the
 * wordmark appears still and crossfades to the menu.
 *
 * Drawn on one canvas from one clock (the frames are a function of time alone), in front of the
 * live menu: before the dive the canvas is opaque; during it only what's outside the O's counter is
 * black. Touches never reach the menu until it's there.
 */
@Composable
fun BootScreen(reveal: BootReveal, onChime: () -> Unit, onFinished: () -> Unit, modifier: Modifier = Modifier) {
    val reduced = PodiumTheme.motion.reduced
    val haptics = rememberPodiumHaptics()
    val chime by rememberUpdatedState(onChime)
    val finished by rememberUpdatedState(onFinished)
    var skip by remember { mutableStateOf(false) }

    InputTargetEffect(WheelContext.LIST_FOCUS) { input ->
        if (input is PodiumInput.Press || input is PodiumInput.Rotate) skip = true
        true
    }

    LaunchedEffect(skip) {
        reveal.reset()
        if (skip) {
            // Skipped: straight into a quick dive from wherever the mark is.
            if (reduced) reveal.fade = 1f else animate(BootTimeline.DIVE_START, BootTimeline.END, animationSpec = tween(SKIP_DIVE_MS, easing = LinearEasing)) { v, _ -> reveal.time = v }
            finished()
            return@LaunchedEffect
        }
        if (reduced) {
            chime()
            reveal.time = BootTimeline.SETTLE_END
            delay(REDUCED_HOLD_MS)
            animate(0f, 1f, animationSpec = tween(200)) { v, _ -> reveal.fade = v }
            finished()
            return@LaunchedEffect
        }
        animate(0f, BootTimeline.END, animationSpec = tween(BootTimeline.END.roundToInt(), easing = LinearEasing)) { v, _ -> reveal.time = v }
        finished()
    }

    // The beats: the chord as the lens opens, a touch at each landing.
    LaunchedEffect(Unit) {
        if (reduced) return@LaunchedEffect
        var last = -1f
        snapshotFlow { reveal.time }.collect { t ->
            fun crossed(at: Float) = last < at && t >= at
            if (!skip) {
                if (crossed(BootTimeline.DOT)) {
                    chime()
                    haptics.tick()
                }
                if (crossed(BootTimeline.BUBBLE_LANDS)) haptics.press()
                if (crossed(BootTimeline.SPIN_END)) haptics.tick()
                if (crossed(BootTimeline.UNROLL_END)) haptics.press()
            }
            if (crossed(BootTimeline.END - 1f)) haptics.confirm()
            last = t
        }
    }

    BootMark(
        time = { reveal.time },
        fade = { reveal.fade },
        modifier = modifier
            .fillMaxSize()
            .semantics { contentDescription = "Starting Podium" }
            // Nothing beneath is touched while Podium starts.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            },
    )
}

/**
 * The boot's frames: [time] milliseconds into [BootTimeline] (read while drawing). [fade] (0–1, or
 * negative) is reduced motion's crossfade to the menu.
 */
@Composable
internal fun BootMark(time: () -> Float, modifier: Modifier = Modifier, fade: () -> Float = { -1f }) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val typeface = remember(context) { runCatching { context.resources.getFont(R.font.archivo_wordmark) }.getOrNull() ?: Typeface.DEFAULT_BOLD }
    val flakes = remember(density.density) { wordmarkFlakes(density.density) }
    var art by remember { mutableStateOf<MarkArt?>(null) }
    Canvas(modifier) {
        val current = art?.takeIf { it.width == size.width && it.height == size.height } ?: MarkArt.build(typeface, size.width, size.height, flakes).also { art = it }
        val f = fade()
        if (f >= 0f) {
            drawRect(Color.Black, alpha = 1f - f)
            drawFlatMark(current, alpha = 1f - f)
            return@Canvas
        }
        drawBootFrame(time(), current)
    }
}

/** What the boot draws with, for one canvas size: the mark's outlines, laid out, and the wordmark as a picture. */
internal class MarkArt private constructor(
    val width: Float,
    val height: Float,
    /** "PODIUM", the P alone, and the O's counter, in the wordmark's own coordinates (baseline at 0). */
    val word: Path,
    val p: Path,
    val counter: Path,
    val wordBounds: Rect,
    val pBounds: Rect,
    val counterBounds: Rect,
    /** The wordmark, silver with its flakes, exactly as drawn flat: what the cylinder unrolls. */
    val picture: ImageBitmap,
    /** Its flakes, for the flat wordmark. */
    val flakes: ImageBitmap,
) {
    /** Where the flat wordmark's own coordinates map to the canvas (it is drawn centred, 1:1). */
    val origin: Offset get() = Offset(width / 2f - wordBounds.center.x, height / 2f - wordBounds.center.y)

    /** The lens's side: a little over two fifths of the shorter side. */
    val lens: Float get() = min(width, height) * 0.42f

    companion object {
        fun build(typeface: Typeface, width: Float, height: Float, flakes: ImageBitmap): MarkArt {
            // Three quarters of the width; on a wide, short screen, held to about a third of its height.
            val markWidth = min(width * 0.76f, height * 1.8f)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.typeface = typeface
                textSize = markWidth / WordmarkEms
                letterSpacing = -0.01f
            }
            val word = android.graphics.Path().also { paint.getTextPath("PODIUM", 0, 6, 0f, 0f, it) }
            val p = android.graphics.Path().also { paint.getTextPath("P", 0, 1, 0f, 0f, it) }
            val oAt = paint.measureText("PO") - paint.measureText("O")
            val o = android.graphics.Path().also { paint.getTextPath("O", 0, 1, oAt, 0f, it) }
            val counter = innermost(o)
            val wb = bounds(word)
            val picture = picture(word, wb, flakes)
            return MarkArt(
                width, height,
                word.asComposePath(), p.asComposePath(), counter.asComposePath(),
                wb, bounds(p), bounds(counter), picture, flakes,
            )
        }

        private fun bounds(path: android.graphics.Path): Rect {
            val r = RectF()
            path.computeBounds(r, true)
            return Rect(r.left, r.top, r.right, r.bottom)
        }

        /** The smallest closed contour of a glyph: its counter. */
        private fun innermost(glyph: android.graphics.Path): android.graphics.Path {
            val measure = PathMeasure(glyph, false)
            var best: android.graphics.Path? = null
            var bestArea = Float.MAX_VALUE
            do {
                val contour = android.graphics.Path()
                if (!measure.getSegment(0f, measure.length, contour, true)) continue
                contour.close()
                val r = RectF()
                contour.computeBounds(r, true)
                val area = r.width() * r.height()
                if (area > 1f && area < bestArea) {
                    bestArea = area
                    best = contour
                }
            } while (measure.nextContour())
            return best ?: glyph
        }

        /** The wordmark drawn into a bitmap of its own size: silver, white where the light falls, with its flakes. */
        private fun picture(word: android.graphics.Path, wb: Rect, flakes: ImageBitmap): ImageBitmap {
            val pad = 2
            val w = (wb.width + pad * 2).roundToInt().coerceAtLeast(1)
            val h = (wb.height + pad * 2).roundToInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            canvas.translate(pad - wb.left, pad - wb.top)
            val silver = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, wb.top, 0f, wb.bottom,
                    SilverStops.map { it.second.toArgbInt() }.toIntArray(),
                    SilverStops.map { it.first }.toFloatArray(),
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawPath(word, silver)
            canvas.save()
            canvas.clipPath(word)
            val sparkle = Paint().apply {
                shader = BitmapShader(flakes.asAndroidBitmapCompat(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).also { s ->
                    s.setLocalMatrix(Matrix().apply { setTranslate(wb.left, wb.top) })
                }
            }
            canvas.drawRect(wb.left, wb.top, wb.right, wb.bottom, sparkle)
            canvas.restore()
            return bitmap.asImageBitmap()
        }
    }
}

private fun Color.toArgbInt(): Int =
    android.graphics.Color.argb((alpha * 255).roundToInt(), (red * 255).roundToInt(), (green * 255).roundToInt(), (blue * 255).roundToInt())

private fun ImageBitmap.asAndroidBitmapCompat(): Bitmap = asAndroidBitmap()

/** One frame of the boot at [t] ms. */
internal fun DrawScope.drawBootFrame(t: Float, art: MarkArt) {
    when {
        t < BootTimeline.RESOLVE_END -> {
            drawRect(Color.Black)
            drawLens(t, art)
        }
        t < BootTimeline.SPIN_END -> {
            drawRect(Color.Black)
            drawSpin(t, art)
        }
        t < BootTimeline.UNROLL_END -> {
            drawRect(Color.Black)
            drawUnroll(t, art)
        }
        t < BootTimeline.DIVE_START -> {
            drawRect(Color.Black)
            val settle = BootTimeline.phase(t, BootTimeline.UNROLL_END, BootTimeline.SETTLE_END)
            drawGlow(art, settle)
            drawFlatMark(art, alpha = 1f, scale = 1f + 0.015f * (1f - easeOut(settle)))
        }
        else -> drawDive(t, art)
    }
}

// --- The lens: a dot swells into a squircle holding the P in fine lines -------------------------

private fun DrawScope.drawLens(t: Float, art: MarkArt) {
    val c = Offset(size.width / 2f, size.height / 2f)
    val grow = BootTimeline.phase(t, BootTimeline.DOT, BootTimeline.BUBBLE_END)
    val dot = BootTimeline.phase(t, 0f, BootTimeline.DOT)
    val resolve = BootTimeline.phase(t, BootTimeline.HOLD_END, BootTimeline.RESOLVE_END)
    // Out of nothing: a faint dot, then a swell that overshoots and settles.
    val scale = if (t < BootTimeline.DOT) 0.04f * dot else 0.04f + 0.96f * backOut(grow)
    // Held: it breathes, wider then taller, and the breathing dies away.
    val held = BootTimeline.phase(t, BootTimeline.BUBBLE_END, BootTimeline.RESOLVE_END)
    val wobble = if (t > BootTimeline.BUBBLE_END) 0.028f * sin(held * 2f * PI.toFloat() * 2.2f) * exp(-held * 3.2f) else 0f
    val shrink = 1f - 0.08f * easeInOut(resolve)
    val side = art.lens * scale * shrink
    val sx = side * (1f + wobble)
    val sy = side * (1f - wobble)
    // A circle that squares into the icon's squircle as it grows.
    val square = easeInOut(BootTimeline.phase(t, 380f, BootTimeline.BUBBLE_END))
    val corner = min(sx, sy) * (0.5f - 0.2f * square)
    val lensAlpha = 1f - easeInOut(BootTimeline.phase(t, 1250f, BootTimeline.RESOLVE_END))
    val topLeft = Offset(c.x - sx / 2f, c.y - sy / 2f)
    val lensSize = Size(sx, sy)
    val radius = CornerRadius(corner)

    // Echoes of the rim, left behind as it lands.
    val echo = BootTimeline.phase(t, BootTimeline.BUBBLE_END, 980f)
    if (t > BootTimeline.BUBBLE_END && echo < 1f) {
        for (k in 1..2) {
            val e = 1f + 0.05f * k * (0.4f + echo)
            drawRoundRect(
                Iridescent(c),
                Offset(c.x - sx * e / 2f, c.y - sy * e / 2f),
                Size(sx * e, sy * e),
                CornerRadius(corner * e),
                alpha = (0.16f / k) * (1f - echo) * lensAlpha,
                style = Stroke(1.5.dp.toPx()),
            )
        }
    }
    // The lens: nearly black glass, lit faintly from above, with the P inside in lines.
    drawRoundRect(Color(0xFF07080A), topLeft, lensSize, radius, alpha = lensAlpha)
    drawRoundRect(
        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.07f), Color.Transparent), startY = topLeft.y, endY = topLeft.y + sy * 0.6f),
        topLeft, lensSize, radius, alpha = lensAlpha,
    )
    val lensPath = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(topLeft, lensSize), radius)) }
    // The P: as tall as a little over half the lens.
    val pScale = side * 0.56f / art.pBounds.height
    val pPath = Path().apply {
        addPath(art.p)
        transform(androidx.compose.ui.graphics.Matrix().apply {
            translate(c.x, c.y)
            scale(pScale, pScale)
            translate(-art.pBounds.center.x, -art.pBounds.center.y)
        })
    }
    clipPath(lensPath) {
        drawLinedP(pPath, t, resolve, art, c, pScale)
    }
    // The rim: a thin iridescent edge, and its soft glow inside.
    val rimShift = (t / 2400f) * 360f
    drawRoundRect(Iridescent(c, rimShift), topLeft, lensSize, radius, alpha = 0.85f * lensAlpha, style = Stroke(2.dp.toPx()))
    drawRoundRect(Iridescent(c, rimShift), topLeft + Offset(3f, 3f), Size(sx - 6f, sy - 6f), CornerRadius(max(corner - 3f, 0f)), alpha = 0.14f * lensAlpha, style = Stroke(6.dp.toPx()))
}

/** The P in fine horizontal lines through a prism: its colours part at the edges, then close up as it resolves into silver. */
private fun DrawScope.drawLinedP(pPath: Path, t: Float, resolve: Float, art: MarkArt, c: Offset, pScale: Float) {
    val pitch = 3.4.dp.toPx()
    // The lines fill in as it resolves; their gaps close.
    val duty = 0.52f + 0.48f * easeInOut(BootTimeline.phase(t, BootTimeline.HOLD_END, 1350f))
    val drift = (t / 1000f) * pitch * 2.2f
    val split = 2.6.dp.toPx() * (0.35f + 0.65f * (1f - BootTimeline.phase(t, BootTimeline.DOT, BootTimeline.BUBBLE_END))) *
        (1f - BootTimeline.phase(t, BootTimeline.HOLD_END, 1300f))
    val shimmer = sin(t / 260f) * 0.5f
    fun lines(tint: Color) = Brush.verticalGradient(
        0f to tint,
        duty to tint,
        duty to Color.Transparent,
        1f to Color.Transparent,
        startY = drift,
        endY = drift + pitch,
        tileMode = TileMode.Repeated,
    )
    val bright = 0.93f
    val channels = listOf(
        Color(bright, 0f, 0f) to -split * (1f + shimmer * 0.3f),
        Color(0f, bright, 0f) to 0f,
        Color(0f, 0f, bright) to split * (1f - shimmer * 0.3f),
    )
    for ((tint, dx) in channels) {
        translate(left = dx) {
            clipPath(pPath) { drawRect(lines(tint), topLeft = Offset(-dx, 0f), size = size, blendMode = BlendMode.Plus) }
        }
    }
    // Resolving: solid silver comes up over the lines.
    val solid = easeInOut(BootTimeline.phase(t, 1250f, 1450f))
    if (solid > 0f) {
        drawPath(pPath, Brush.verticalGradient(*SilverStops, startY = c.y - art.pBounds.height * pScale / 2f, endY = c.y + art.pBounds.height * pScale / 2f), alpha = solid)
    }
}

// --- The spin: the solid P turns down to its edge ----------------------------------------------

private fun DrawScope.drawSpin(t: Float, art: MarkArt) {
    val c = Offset(size.width / 2f, size.height / 2f)
    val x = BootTimeline.phase(t, BootTimeline.RESOLVE_END, BootTimeline.SPIN_END)
    val turn = 2.5f * PI.toFloat() * easeIn(x)
    val facing = cos(turn)
    val side = art.lens * 0.92f * (1f - 0.55f * easeIn(x))
    val pScale = side * 0.56f / art.pBounds.height
    val silver = Brush.verticalGradient(*SilverStops, startY = art.pBounds.top, endY = art.pBounds.bottom)
    withTransform({
        translate(c.x, c.y)
        scale(pScale * facing, pScale, pivot = Offset.Zero)
        translate(-art.pBounds.center.x, -art.pBounds.center.y)
    }) {
        // Its back is the same silver in shadow.
        drawPath(art.p, silver)
        if (facing < 0f) drawPath(art.p, Color.Black.copy(alpha = 0.4f))
    }
}

// --- The unroll: the wordmark comes off a turning cylinder -------------------------------------

private fun DrawScope.drawUnroll(t: Float, art: MarkArt) {
    val u = BootTimeline.phase(t, BootTimeline.SPIN_END, BootTimeline.UNROLL_END)
    // Opens steadily, then slows into flat: never a snap open.
    val e = 1f - (1f - u).pow(2.2f)
    val w = art.wordBounds.width
    // From a sliver (the P on its edge) to nearly flat: the radius grows geometrically.
    val r0 = 3.dp.toPx()
    val r1 = w / (2f * 0.08f)
    val radius = r0 * (r1 / r0).pow(e)
    // The cylinder is still turning as it opens: the word comes round to face front.
    val turn = (1f - e) * 1.4f
    // The prism parts the colours at the turning edges, less and less as it flattens.
    val fringe = 0.06f * (1f - e).pow(1.5f)
    val c = Offset(size.width / 2f, size.height / 2f)
    if (fringe > 0.002f) {
        drawCylinder(art, c, radius * (1f - fringe), turn, Color(1f, 0f, 0f), BlendMode.Plus)
        drawCylinder(art, c, radius, turn, Color(0f, 1f, 0f), BlendMode.Plus)
        drawCylinder(art, c, radius * (1f + fringe), turn, Color(0f, 0f, 1f), BlendMode.Plus)
    } else {
        drawCylinder(art, c, radius, turn, null, BlendMode.SrcOver)
    }
}

/** The wordmark on a cylinder of [radius] turned by [turn] radians, seen face on: drawn in thin upright strips. */
private fun DrawScope.drawCylinder(art: MarkArt, c: Offset, radius: Float, turn: Float, tint: Color?, blend: BlendMode) {
    val picture = art.picture
    val strips = CylinderStrips
    val stripW = picture.width.toFloat() / strips
    val half = picture.width / 2f
    val filter = tint?.let { ColorFilter.tint(it, BlendMode.Modulate) }
    val top = (c.y - picture.height / 2f).roundToInt()
    for (i in 0 until strips) {
        val x0 = i * stripW - half
        val x1 = x0 + stripW
        val a0 = x0 / radius - turn
        val a1 = x1 / radius - turn
        if (a0 < -HALF_PI || a1 > HALF_PI) continue
        val sx0 = c.x + radius * sin(a0)
        val sx1 = c.x + radius * sin(a1)
        val width = sx1 - sx0
        if (width < 0.05f) continue
        val shade = cos((a0 + a1) / 2f).coerceAtLeast(0f).pow(0.7f)
        drawImage(
            picture,
            srcOffset = IntOffset((i * stripW).roundToInt(), 0),
            srcSize = IntSize(((i + 1) * stripW).roundToInt() - (i * stripW).roundToInt(), picture.height),
            dstOffset = IntOffset(sx0.roundToInt(), top),
            dstSize = IntSize(max(1, (sx1 + 0.5f).roundToInt() - sx0.roundToInt()), picture.height),
            alpha = shade,
            colorFilter = filter,
            blendMode = blend,
            filterQuality = FilterQuality.Low,
        )
    }
}

// --- Flat: the wordmark at rest, and the dive through its O ------------------------------------

private fun DrawScope.drawGlow(art: MarkArt, amount: Float) {
    if (amount <= 0f) return
    drawCircle(
        Brush.radialGradient(
            listOf(Color.White.copy(alpha = 0.075f * amount), Color.Transparent),
            center = center,
            radius = art.wordBounds.width * 0.62f,
        ),
        radius = art.wordBounds.width * 0.62f,
        center = center,
    )
}

/** The wordmark as it rests: silver, with its flakes, centred. */
internal fun DrawScope.drawFlatMark(art: MarkArt, alpha: Float, scale: Float = 1f) {
    val origin = art.origin
    withTransform({
        scale(scale, scale, pivot = center)
        translate(origin.x, origin.y)
    }) {
        drawMarkPath(art, alpha, flakes = 1f)
    }
}

private fun DrawScope.drawMarkPath(art: MarkArt, alpha: Float, flakes: Float) {
    val wb = art.wordBounds
    drawPath(art.word, Brush.verticalGradient(*SilverStops, startY = wb.top, endY = wb.bottom), alpha = alpha)
    if (flakes > 0f) {
        clipPath(art.word) {
            drawRect(FlakeBrush(art.flakes, Offset(wb.left, wb.top)), topLeft = wb.topLeft, size = wb.size, alpha = alpha * flakes)
        }
    }
}

/** The camera dives into the O: the mark grows about its counter, and through the counter, the menu. */
private fun DrawScope.drawDive(t: Float, art: MarkArt) {
    val e = BootTimeline.dive(t)
    val origin = art.origin
    val cb = art.counterBounds
    val counterAt = Offset(cb.center.x + origin.x, cb.center.y + origin.y)
    // Deep enough that the counter covers the display's corners.
    val a = cb.width / 2f
    val b = cb.height / 2f
    val zMax = 1.12f * sqrt((size.width / 2f / a).pow(2) + (size.height / 2f / b).pow(2))
    val z = exp(ln(zMax) * e)
    val centre = Offset(size.width / 2f, size.height / 2f)
    val at = Offset(counterAt.x + (centre.x - counterAt.x) * easeInOut(e), counterAt.y + (centre.y - counterAt.y) * easeInOut(e))
    val matrix = androidx.compose.ui.graphics.Matrix().apply {
        translate(at.x, at.y)
        scale(z, z)
        translate(-cb.center.x, -cb.center.y)
    }
    // Black everywhere but through the counter.
    val hole = Path().apply {
        addPath(art.counter)
        transform(matrix)
    }
    clipPath(hole, ClipOp.Difference) { drawRect(Color.Black) }
    // The letters, flying past; their flakes fade as they grow.
    withTransform({ transform(matrix) }) {
        drawMarkPath(art, alpha = 1f, flakes = 1f - BootTimeline.phase(e, 0f, 0.35f))
    }
}

/** The wordmark's flakes as a brush, laid from [origin] so they sit where the picture has them. */
private class FlakeBrush(private val tile: ImageBitmap, private val origin: Offset) : ShaderBrush() {
    override fun createShader(size: Size): Shader =
        BitmapShader(tile.asAndroidBitmapCompat(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
            setLocalMatrix(Matrix().apply { setTranslate(origin.x, origin.y) })
        }
}

/** A sweep of soft spectral colour for the lens's rim, turned by [shift] degrees. */
@Suppress("FunctionName")
private fun Iridescent(centre: Offset, shift: Float = 0f): Brush {
    val colours = listOf(
        Color(0xFF9FD8FF), Color(0xFF8C93FF), Color(0xFFC6A2FF), Color(0xFFFFB3E6),
        Color(0xFFFFE3A8), Color(0xFFB8FFE4), Color(0xFF9FD8FF),
    )
    val turn = ((shift % 360f) / 360f)
    val stops = colours.mapIndexed { i, colour -> ((i / (colours.size - 1f)) + turn) % 1f to colour }.sortedBy { it.first }
    val full = buildList {
        add(0f to stops.last().second)
        addAll(stops)
        add(1f to stops.first().second)
    }.distinctBy { it.first }
    return Brush.sweepGradient(*full.toTypedArray(), center = centre)
}

private fun backOut(x: Float): Float {
    val c1 = 1.25f
    val c3 = c1 + 1f
    val y = x - 1f
    return 1f + c3 * y * y * y + c1 * y * y
}

private fun easeIn(x: Float) = x * x * x
private fun easeOut(x: Float) = 1f - (1f - x).pow(3)
private fun easeInOut(x: Float) = x * x * (3f - 2f * x)

private const val HALF_PI = (PI / 2).toFloat()

/** How many upright strips the cylinder is drawn in. */
private const val CylinderStrips = 120

/** A skipped boot dives this fast. */
private const val SKIP_DIVE_MS = 260

/** With reduced motion, how long the still wordmark shows. */
private const val REDUCED_HOLD_MS = 900L

/** From the top of the line to the bottom: white where the light falls, then a soft silver. */
private val SilverStops = arrayOf(0.18f to Color(0xFFEFEFF2), 0.52f to Color(0xFFD3D3D9), 0.84f to Color(0xFFA3A3AD))

/** "PODIUM" is this many ems wide at the wordmark's letter spacing. */
private const val WordmarkEms = 5.31f

/**
 * One tile of the wordmark's flakes, from the body's seeded field (so it is the same every launch):
 * bright flakes and darker specks on transparent, each about a dp across.
 */
private fun wordmarkFlakes(pxPerDp: Float): ImageBitmap {
    val tile = (96 * pxPerDp).roundToInt().coerceIn(128, 512)
    val flakes = GlitterField.flakes(tile, density = 0.85f, amount = 0.55f, flakePx = GlitterField.flakePx(0.4f, pxPerDp), seed = 71)
    val bitmap = Bitmap.createBitmap(tile, tile, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    for (f in flakes) {
        paint.color = if (f.lit) {
            android.graphics.Color.argb((140 + 115 * f.brightness).roundToInt(), 255, 255, 255)
        } else {
            android.graphics.Color.argb((30 + 70 * f.brightness).roundToInt(), 24, 24, 30)
        }
        // Each flake at its place and wrapped across the edges, so the tiles join seamlessly.
        for (dx in intArrayOf(0, -tile, tile)) for (dy in intArrayOf(0, -tile, tile)) {
            val x = f.x + dx
            val y = f.y + dy
            if (x < -f.size || y < -f.size || x > tile + f.size || y > tile + f.size) continue
            canvas.save()
            canvas.rotate(f.angle, x, y)
            canvas.drawRect(x - f.size / 2, y - f.size / 2, x + f.size / 2, y + f.size / 2, paint)
            canvas.restore()
        }
    }
    return bitmap.asImageBitmap()
}
