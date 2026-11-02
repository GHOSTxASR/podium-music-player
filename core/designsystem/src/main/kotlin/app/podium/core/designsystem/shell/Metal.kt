package app.podium.core.designsystem.shell

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Carbon's hardware (D-101): blackened steel. The body is brushed — fine horizontal grain that
 * catches the light unevenly — and the Wheel is spun: fine concentric machining, its reflection two
 * soft lobes along the diagonal from the light (top left), as a turned metal disc shows under a
 * studio softbox. Graphite and near-black throughout; the light is neutral, never gold.
 *
 * The textures are worked out once (the Wheel's on a background thread, at its own size) and drawn
 * as bitmaps: nothing per pixel happens while drawing.
 */
internal object Machining {

    /**
     * A disc of concentric machining [size] px across, as ARGB: light and dark grooves around zero
     * (white or black at an alpha), one value per pixel's radius so every ring is continuous, and a
     * slow irregularity around the circle — the reason turning the Wheel shows: a real turned
     * surface is never perfectly even. Outside the disc it's transparent.
     */
    fun spun(size: Int, seed: Int = 26): IntArray {
        val random = Random(seed)
        val half = size / 2f
        val radii = ceil(half * 1.5f).toInt() + 2
        // Grooves: white noise across the radius, softened a little so the finest rings are a
        // pixel or two, with a few broader bands where the tool's pressure changed.
        val raw = FloatArray(radii) { random.nextFloat() * 2f - 1f }
        val grooves = FloatArray(radii) { r ->
            val a = raw[(r - 1).coerceAtLeast(0)]
            val b = raw[r]
            val c = raw[(r + 1).coerceAtMost(radii - 1)]
            (a * 0.25f + b * 0.5f + c * 0.25f) * 1.6f
        }
        val bands = FloatArray(radii)
        var drift = 0f
        for (r in 0 until radii) {
            drift = drift * 0.96f + (random.nextFloat() * 2f - 1f) * 0.12f
            bands[r] = drift
        }
        // Around the circle: a few slow harmonics, so turning the disc moves something you can see.
        val harmonics = 4
        val amps = FloatArray(harmonics) { 0.5f / (it + 1) * (0.6f + random.nextFloat() * 0.4f) }
        val phases = FloatArray(harmonics) { random.nextFloat() * 2f * PI.toFloat() }
        val out = IntArray(size * size)
        for (y in 0 until size) {
            val dy = y + 0.5f - half
            for (x in 0 until size) {
                val dx = x + 0.5f - half
                val r = sqrt(dx * dx + dy * dy)
                if (r > half + 1f) continue
                val r0 = floor(r).toInt().coerceIn(0, radii - 2)
                val t = r - r0
                val groove = grooves[r0] * (1 - t) + grooves[r0 + 1] * t
                val band = bands[r0] * (1 - t) + bands[r0 + 1] * t
                val theta = atan2(dy, dx)
                var around = 0f
                for (k in 0 until harmonics) around += amps[k] * sin((k + 1) * theta + phases[k] + r * 0.004f * (k + 1))
                val v = (groove * 0.85f + band * 0.15f) * (0.8f + 0.4f * around)
                val edge = (half + 1f - r).coerceIn(0f, 1f)
                val alpha = (abs(v) * 64f * edge).roundToInt().coerceIn(0, 255)
                out[y * size + x] = if (v >= 0f) (alpha shl 24) or 0xFFFFFF else alpha shl 24
            }
        }
        return out
    }

    /**
     * A tile of brushed metal [width] × [height] px: white noise drawn out along x into fine streaks
     * (wrapping, so the tile repeats seamlessly), with a little variation from row to row, as ARGB
     * light and dark around zero.
     */
    fun brushed(width: Int, height: Int, seed: Int = 101): IntArray {
        val random = Random(seed)
        val streak = 36
        val out = IntArray(width * height)
        val row = FloatArray(width)
        val smooth = FloatArray(width)
        for (y in 0 until height) {
            for (x in 0 until width) row[x] = random.nextFloat() * 2f - 1f
            // A long box blur along the row, wrapping: streaks.
            var sum = 0f
            for (i in -streak..streak) sum += row[Math.floorMod(i, width)]
            for (x in 0 until width) {
                smooth[x] = sum / (2 * streak + 1)
                sum += row[Math.floorMod(x + streak + 1, width)] - row[Math.floorMod(x - streak, width)]
            }
            val rowTone = (random.nextFloat() * 2f - 1f) * 0.35f
            for (x in 0 until width) {
                // The blur leaves the streaks faint: lift them back to a full range.
                val v = (smooth[x] * 7f + rowTone).coerceIn(-1.5f, 1.5f)
                val alpha = (abs(v) / 1.5f * 255f).roundToInt().coerceIn(0, 255)
                out[y * width + x] = if (v >= 0f) (alpha shl 24) or 0xFFFFFF else alpha shl 24
            }
        }
        return out
    }
}

/** The body's brushed tile: 384 px of streaks, repeated (seeded, so it never changes between launches). */
internal val BrushedTile: ImageBitmap by lazy {
    Bitmap.createBitmap(Machining.brushed(BRUSHED_W, BRUSHED_H), BRUSHED_W, BRUSHED_H, Bitmap.Config.ARGB_8888).asImageBitmap()
}

private const val BRUSHED_W = 384
private const val BRUSHED_H = 256

private val spunCache = HashMap<Int, ImageBitmap>()

/**
 * The Wheel's machining at [sizePx] across: worked out on a background thread the first time (a few
 * tens of milliseconds) and kept, so the Wheel draws its plain metal for a frame or two and then
 * its grain. Null until it's ready.
 */
@Composable
internal fun rememberSpunMetal(sizePx: Int): ImageBitmap? {
    var texture by remember(sizePx) { mutableStateOf(synchronized(spunCache) { spunCache[sizePx] }) }
    LaunchedEffect(sizePx) {
        if (texture != null || sizePx <= 0) return@LaunchedEffect
        texture = withContext(Dispatchers.Default) {
            val bitmap = Bitmap.createBitmap(Machining.spun(sizePx), sizePx, sizePx, Bitmap.Config.ARGB_8888).asImageBitmap()
            synchronized(spunCache) { spunCache[sizePx] = bitmap }
            bitmap
        }
    }
    return texture
}

/**
 * A spun-metal disc filling the draw area: [base], its machining ([texture], turned by [angle]
 * degrees with the Wheel's band), the reflection's two lobes, and a bevel at its rim — lit at the
 * top left, in shadow at the bottom right ([raised]; false for a recess, which is lit the other way).
 * The reflection and the bevel's light belong to the room, not the metal: they don't turn with the
 * band, but [light] (degrees, from [reflectionTurn]) moves them as the phone tilts.
 */
internal fun DrawScope.drawSpunDisc(
    base: Color,
    texture: ImageBitmap?,
    angle: Float,
    sheen: Float,
    textureAlpha: Float,
    raised: Boolean = true,
    light: Float = 0f,
) {
    val radius = size.minDimension / 2f
    drawCircle(base, radius)
    texture?.let { tex ->
        rotate(angle) {
            drawImage(
                tex,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(tex.width, tex.height),
                dstOffset = IntOffset(((size.width - radius * 2) / 2f).roundToInt(), ((size.height - radius * 2) / 2f).roundToInt()),
                dstSize = IntSize((radius * 2).roundToInt(), (radius * 2).roundToInt()),
                alpha = textureAlpha,
                filterQuality = FilterQuality.Low,
            )
        }
    }
    rotate(light) {
        // The reflection of a soft light at the top left: a faint brightening along the diagonal
        // through it, a weaker one opposite, the other diagonal dark. Restrained: it's steel, not chrome.
        drawCircle(
            Brush.sweepGradient(
                0f to Color.White.copy(alpha = 0.02f * sheen),
                0.125f to Color.White.copy(alpha = 0.065f * sheen),
                0.25f to Color.White.copy(alpha = 0.008f * sheen),
                0.40f to Color.Transparent,
                0.625f to Color.White.copy(alpha = 0.13f * sheen),
                0.80f to Color.Transparent,
                0.92f to Color.White.copy(alpha = 0.008f * sheen),
                1f to Color.White.copy(alpha = 0.02f * sheen),
                center = center,
            ),
            radius,
        )
        // The rim's bevel: light where it faces the light, shadow where it faces away.
        val lit = Color.White.copy(alpha = 0.22f)
        val shade = Color.Black.copy(alpha = 0.5f)
        drawCircle(
            Brush.linearGradient(
                listOf(if (raised) lit else shade, Color.Transparent, if (raised) shade else lit),
                start = Offset(center.x - radius, center.y - radius),
                end = Offset(center.x + radius, center.y + radius),
            ),
            radius - 0.75.dp.toPx(),
            style = Stroke(width = 1.5.dp.toPx()),
        )
    }
}

/**
 * How far the Wheel's reflection turns, in degrees, for the phone's [tilt] (from
 * [rememberDeviceTilt]; null: level, or no sensor). The light stays in the room while the phone
 * moves, so its reflection on the spun metal swings round the centre the way the tilt points it —
 * gently, never more than [MaxReflectionTurn] either way.
 */
internal fun reflectionTurn(tilt: Offset?): Float {
    if (tilt == null) return 0f
    val rest = Math.toDegrees(atan2(LightAt.y, LightAt.x).toDouble())
    val now = Math.toDegrees(atan2(LightAt.y + tilt.y * TiltReach, LightAt.x + tilt.x * TiltReach).toDouble())
    var turn = now - rest
    while (turn > 180) turn -= 360
    while (turn < -180) turn += 360
    return turn.toFloat().coerceIn(-MaxReflectionTurn, MaxReflectionTurn)
}

/** Where the light sits, seen from the Wheel's centre: up and to the left (screen axes). */
private val LightAt = Offset(-0.65f, -0.76f)

/** How far a full tilt moves the light. */
private const val TiltReach = 0.75f

private const val MaxReflectionTurn = 50f

/**
 * A matte metal coat (the Wheel's centre on Carbon): no machining and no reflection — the light only
 * spreads softly across it, a little brighter toward the top left — set into a recess whose edge
 * shades its upper left and whose lower lip catches the light. Its fine, even grain comes from
 * `Modifier.grain` over it ([MatteCoatGrain]).
 */
internal fun DrawScope.drawMatteCoat(base: Color) {
    val radius = size.minDimension / 2f
    drawCircle(base, radius)
    drawCircle(
        Brush.linearGradient(
            listOf(Color.White.copy(alpha = 0.045f), Color.Transparent, Color.Black.copy(alpha = 0.16f)),
            start = Offset(center.x - radius, center.y - radius),
            end = Offset(center.x + radius, center.y + radius),
        ),
        radius,
    )
    // The recess: the ring's edge shades the upper left, the lip below catches the light.
    drawCircle(
        Brush.linearGradient(
            listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent, Color.White.copy(alpha = 0.14f)),
            start = Offset(center.x - radius, center.y - radius),
            end = Offset(center.x + radius, center.y + radius),
        ),
        radius - 1.dp.toPx(),
        style = Stroke(width = 2.dp.toPx()),
    )
}

/** The matte coat's grain: fine and even, like bead blasting, never a pattern. */
internal const val MatteCoatGrain = 0.34f

/** A recessed seam around a part set into the body: a dark groove with the lip below it catching light. */
internal fun DrawScope.drawSeam(centre: Offset, radius: Float) {
    drawCircle(Color.Black.copy(alpha = 0.55f), radius + 1.dp.toPx(), centre, style = Stroke(width = 2.dp.toPx()))
    drawCircle(
        Brush.linearGradient(
            listOf(Color.Transparent, Color.White.copy(alpha = 0.10f)),
            start = Offset(centre.x - radius, centre.y - radius),
            end = Offset(centre.x + radius, centre.y + radius),
        ),
        radius + 2.5.dp.toPx(),
        centre,
        style = Stroke(width = 1.dp.toPx()),
    )
}

/** The body's own light on Carbon: brushed grain, soft edge highlights and a restrained reflection. */
internal fun DrawScope.drawBrushedBody(top: Color, bottom: Color, brushed: Brush) {
    drawRect(Brush.verticalGradient(listOf(top, bottom)))
    drawRect(brushed, alpha = 0.10f)
    // One broad, soft reflection from the top left, and a fainter one lower down.
    drawRect(
        Brush.linearGradient(
            0f to Color.White.copy(alpha = 0.055f),
            0.28f to Color.Transparent,
            0.62f to Color.White.copy(alpha = 0.018f),
            0.85f to Color.Transparent,
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        ),
    )
    // The chamfers where the body curves away: a catch of light along the top and the sides, the
    // underside falling into shadow.
    val side = 16.dp.toPx()
    drawRect(Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.06f), Color.Transparent), endX = side), size = Size(side, size.height))
    drawRect(
        Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.035f)), startX = size.width - side, endX = size.width),
        topLeft = Offset(size.width - side, 0f),
        size = Size(side, size.height),
    )
    drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.07f), Color.Transparent), endY = 10.dp.toPx()), size = Size(size.width, 10.dp.toPx()))
    val foot = 40.dp.toPx()
    drawRect(
        Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.32f)), startY = size.height - foot, endY = size.height),
        topLeft = Offset(0f, size.height - foot),
        size = Size(size.width, foot),
    )
}
