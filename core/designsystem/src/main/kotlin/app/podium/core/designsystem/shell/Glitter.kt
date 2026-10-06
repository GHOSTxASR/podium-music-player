package app.podium.core.designsystem.shell

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.ComposeShader
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.podium.core.designsystem.theme.PodiumTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Glitter in the body's material (D-41, D-44, PODIUM_CUSTOMIZATION.md §6): fine reflective flakes
 * embedded in the plastic or metal, the way metal-flake paint looks up close. Held still, the
 * flakes glow softly where the light falls; tilting the phone moves that light the other way (tilt
 * right, the glow slides left) and turns different flakes towards it, so they catch the light and
 * let it go as they would in your hand. Never stars, never sparkles on top.
 *
 * Off by default; off draws nothing at all, so the body is exactly as before.
 */
@Immutable
data class Glitter(
    val enabled: Boolean = false,
    /** 0–1: how many of the flakes catch the light (the rest read as darker specks). */
    val amount: Float = DEFAULT_AMOUNT,
    /** 0–1: how many flakes per area. */
    val density: Float = DEFAULT_DENSITY,
    /** 0–1: flake size, about 0.4 dp to 1.4 dp. */
    val size: Float = DEFAULT_SIZE,
    /** 0–1: how strongly the flakes show. */
    val opacity: Float = DEFAULT_OPACITY,
    /** 0–1: how strongly the flakes glow where the light falls (0: evenly, as if under flat light). */
    val glow: Float = DEFAULT_GLOW,
    /** The light follows the phone's tilt (motion sensors); off, it stays where it is. */
    val tilt: Boolean = true,
) {
    companion object {
        const val DEFAULT_AMOUNT = 0.4f
        const val DEFAULT_DENSITY = 0.35f
        const val DEFAULT_SIZE = 0.3f
        const val DEFAULT_OPACITY = 0.5f
        const val DEFAULT_GLOW = 0.6f
    }
}

/**
 * Where the flakes sit in one square tile — pure and seeded, so the body's pattern never changes
 * between launches or devices of the same density.
 */
internal object GlitterField {

    data class Flake(val x: Float, val y: Float, val size: Float, val angle: Float, val brightness: Float, val lit: Boolean)

    /** Pixels of tile per flake at full density (fewer flakes at lower density). */
    private const val AREA_PER_FLAKE_AT_FULL = 36f

    /** Lit flakes face one of this many ways; each group catches the light at its own tilt. */
    const val FACINGS = 3

    fun flakes(tilePx: Int, density: Float, amount: Float, flakePx: Float, seed: Int = 41): List<Flake> {
        val random = Random(seed)
        val d = density.coerceIn(0f, 1f)
        // Even the lowest setting keeps a few flakes; the curve keeps low settings gentle.
        val count = (tilePx * tilePx / AREA_PER_FLAKE_AT_FULL * (0.04f + 0.96f * d * d)).roundToInt()
        val litShare = amount.coerceIn(0f, 1f)
        return List(count) {
            val r = random.nextFloat()
            Flake(
                x = random.nextFloat() * tilePx,
                y = random.nextFloat() * tilePx,
                size = flakePx * (0.7f + 0.6f * random.nextFloat()),
                angle = random.nextFloat() * 90f,
                // Most flakes face away from the light; a few face it fully.
                brightness = 0.35f + 0.65f * r * r,
                lit = random.nextFloat() < litShare,
            )
        }
    }

    /** Which way a flake faces, from its angle: one of [FACINGS] groups. */
    fun facing(flake: Flake): Int = (flake.angle / 90f * FACINGS).toInt().coerceIn(0, FACINGS - 1)

    fun flakePx(size: Float, pxPerDp: Float): Float = (0.4f + 1.0f * size.coerceIn(0f, 1f)) * pxPerDp

    /**
     * How brightly the flakes facing [group] catch the light at [tilt] (0–1). The groups face a third
     * of a turn apart, so as the phone tilts one brightens while another fades — never all at once.
     */
    fun catch(group: Int, tilt: Offset): Float {
        val phase = 2 * PI * group / FACINGS
        val angle = 3.2 * tilt.x + 2.4 * tilt.y
        return (0.5 + 0.5 * cos(angle - phase)).toFloat()
    }
}

/**
 * The phone's tilt from the gravity sensor, as the glitter needs it (pure, so it is tested without a
 * phone). [offset] is −1…1 each way, in screen directions:
 *
 * - **x** from the roll, absolute: level is 0; tilting the right edge down gives a negative x, so the
 *   light moves left — away from the lowered side, as a reflection does.
 * - **y** from the pitch, against the angle the phone is usually held at (it adapts over a few
 *   seconds, so however you hold it, holding it there is "straight"): tilting the top away moves
 *   the light down.
 *
 * Gravity readings are smoothed lightly (accelerometer readings, which include hand shake, more).
 */
class TiltFilter(private val raw: Boolean = false) {
    private var sx = 0f
    private var sy = 0f
    private var baseline = Float.NaN
    private var lastNs = 0L

    var offset: Offset = Offset.Zero
        private set

    /** One reading in screen axes (x right, y up), any units; [timestampNs] as the sensor gives it. */
    fun update(x: Float, y: Float, z: Float, timestampNs: Long): Offset {
        val norm = sqrt(x * x + y * y + z * z)
        if (norm < 1e-3f) return offset
        val nx = x / norm
        val ny = y / norm
        val dt = if (lastNs == 0L) 0.0 else ((timestampNs - lastNs) / 1e9).coerceIn(0.0, 0.2)
        lastNs = timestampNs
        if (baseline.isNaN()) {
            sx = nx
            sy = ny
            baseline = ny
        } else {
            val smooth = (1 - exp(-dt / if (raw) RAW_SMOOTH_S else GRAVITY_SMOOTH_S)).toFloat()
            sx += (nx - sx) * smooth
            sy += (ny - sy) * smooth
            baseline += (sy - baseline) * (1 - exp(-dt / BASELINE_S)).toFloat()
        }
        offset = Offset((sx * ROLL_GAIN).coerceIn(-1f, 1f), (-(sy - baseline) * PITCH_GAIN).coerceIn(-1f, 1f))
        return offset
    }

    companion object {
        private const val GRAVITY_SMOOTH_S = 0.06
        private const val RAW_SMOOTH_S = 0.15
        private const val BASELINE_S = 4.0
        /** About 27° of roll reaches the edge. */
        private const val ROLL_GAIN = 2.2f
        private const val PITCH_GAIN = 2.5f

        /** Device-axis readings (x, y) into screen axes for a display [rotation] (Surface.ROTATION_*). */
        fun toScreen(x: Float, y: Float, rotation: Int): Pair<Float, Float> = when (rotation) {
            Surface.ROTATION_90 -> -y to x
            Surface.ROTATION_180 -> -x to -y
            Surface.ROTATION_270 -> y to -x
            else -> x to y
        }
    }
}

/**
 * The phone's tilt while [enabled] and Podium is in the foreground (null when not listening). The
 * gravity sensor (or the accelerometer) runs at game rate only between resume and pause, and the
 * state changes only when the tilt moves noticeably — a phone lying still redraws nothing.
 */
@Composable
fun rememberDeviceTilt(enabled: Boolean): State<Offset?> {
    val state = remember { mutableStateOf<Offset?>(null) }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val orientation = LocalConfiguration.current.orientation
    DisposableEffect(enabled, lifecycle, orientation) {
        val manager = context.getSystemService(SensorManager::class.java)
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (!enabled || manager == null || sensor == null) {
            state.value = null
            return@DisposableEffect onDispose { }
        }
        val filter = TiltFilter(raw = sensor.type == Sensor.TYPE_ACCELEROMETER)
        val rotation = displayRotation(context)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val (sx, sy) = TiltFilter.toScreen(event.values[0], event.values[1], rotation)
                val next = filter.update(sx, sy, event.values[2], event.timestamp)
                val current = state.value
                if (current == null || (next - current).getDistance() > TILT_EPSILON) state.value = next
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        var listening = false
        fun start() {
            if (!listening) listening = manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        }
        fun stop() {
            if (listening) manager.unregisterListener(listener)
            listening = false
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> start()
                Lifecycle.Event.ON_PAUSE -> stop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            stop()
        }
    }
    return state
}

@Suppress("DEPRECATION")
private fun displayRotation(context: Context): Int =
    runCatching { context.display?.rotation }.getOrNull()
        ?: context.getSystemService(WindowManager::class.java)?.defaultDisplay?.rotation
        ?: Surface.ROTATION_0

/** Below this change in tilt the glitter isn't redrawn. */
private const val TILT_EPSILON = 0.006f

/** White alpha tiles: one per facing group of lit flakes, and the dull flakes. */
private class GlitterTiles(val lit: List<Bitmap>, val dull: Bitmap)

private object GlitterTileCache {
    private const val TILE = 512
    private val cache = object : LinkedHashMap<String, GlitterTiles>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, GlitterTiles>?) = size > 2
    }

    @Synchronized
    fun tiles(glitter: Glitter, pxPerDp: Float): GlitterTiles {
        // Quantised, so dragging a level re-renders only when the result would differ.
        fun q(v: Float) = (v.coerceIn(0f, 1f) * 20).roundToInt()
        val key = "${q(glitter.density)}-${q(glitter.amount)}-${q(glitter.size)}-${(pxPerDp * 4).roundToInt()}"
        return cache.getOrPut(key) {
            val flakes = GlitterField.flakes(TILE, q(glitter.density) / 20f, q(glitter.amount) / 20f, GlitterField.flakePx(q(glitter.size) / 20f, pxPerDp))
            val lit = flakes.filter { it.lit }
            GlitterTiles(
                lit = List(GlitterField.FACINGS) { g -> render(lit.filter { GlitterField.facing(it) == g }) },
                dull = render(flakes.filter { !it.lit }),
            )
        }
    }

    private fun render(flakes: List<GlitterField.Flake>): Bitmap {
        val bitmap = Bitmap.createBitmap(TILE, TILE, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (f in flakes) {
            paint.color = android.graphics.Color.argb((f.brightness * 255).roundToInt(), 255, 255, 255)
            // Draw each flake at its place and wrapped across the tile's edges, so tiles join seamlessly.
            for (dx in intArrayOf(0, -TILE, TILE)) for (dy in intArrayOf(0, -TILE, TILE)) {
                val x = f.x + dx
                val y = f.y + dy
                if (x < -f.size || y < -f.size || x > TILE + f.size || y > TILE + f.size) continue
                canvas.save()
                canvas.rotate(f.angle, x, y)
                canvas.drawRect(x - f.size / 2, y - f.size / 2, x + f.size / 2, y + f.size / 2, paint)
                canvas.restore()
            }
        }
        return bitmap
    }
}

/**
 * Overlays the body's glitter: the dull specks, then each facing group of lit flakes seen through a
 * soft light centred where the tilt puts it, each group as bright as it catches the light, the
 * best-facing flakes flaring under the light, and a faint glaze there. Tiles are rendered once per setting and repeated, so a frame is five
 * tiled draws plus one flare, and frames happen only when the tilt moves. Clip before this modifier to keep it
 * inside a shape. [tiltOverride] fixes the tilt (previews and tests, which have no motion sensors).
 */
@Composable
fun Modifier.glitter(glitter: Glitter, light: Color, dark: Color, tiltOverride: Offset? = null): Modifier {
    if (!glitter.enabled || glitter.opacity <= 0f) return this
    val pxPerDp = LocalDensity.current.density
    val tiles = remember(glitter.density, glitter.amount, glitter.size, pxPerDp) { GlitterTileCache.tiles(glitter, pxPerDp) }
    val reduced = PodiumTheme.motion.reduced
    val sensed by rememberDeviceTilt(enabled = tiltOverride == null && glitter.tilt && !reduced && glitter.glow > 0f)
    val tilt = tiltOverride ?: sensed
    val opacity = glitter.opacity.coerceIn(0f, 1f)
    val glow = glitter.glow.coerceIn(0f, 1f)
    return drawWithCache {
        val dull = ShaderBrush(ImageShader(tiles.dull.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
        val litFilter = ColorFilter.tint(light, BlendMode.SrcIn)
        val dullFilter = ColorFilter.tint(dark, BlendMode.SrcIn)
        onDrawWithContent {
            drawContent()
            drawRect(dull, alpha = opacity * 0.55f, colorFilter = dullFilter)
            val t = tilt ?: Offset.Zero
            val centre = Offset(size.width * (0.5f + 0.45f * t.x), size.height * (0.42f + 0.35f * t.y))
            val radius = maxOf(size.width, size.height) * 0.7f
            var best = 0
            var bestCatch = -1f
            for (g in tiles.lit.indices) {
                val catch = GlitterField.catch(g, t)
                if (catch > bestCatch) {
                    bestCatch = catch
                    best = g
                }
                // No glow: every flake evenly, as under flat light. Full glow: bright under the light,
                // faint away from it, each group as bright as it faces the light.
                val alpha = opacity * (1f - glow + glow * (0.15f + 1.1f * catch))
                drawRect(lit(tiles.lit[g], centre, radius, edge = 1f - 0.8f * glow), alpha = alpha.coerceIn(0f, 1f), colorFilter = litFilter)
            }
            if (glow > 0f) {
                // The flakes facing the light flare where it falls: added light, not paint.
                drawRect(
                    lit(tiles.lit[best], centre, radius * 0.55f, edge = 0f),
                    alpha = (glow * 0.75f * bestCatch * bestCatch * (0.5f + opacity)).coerceIn(0f, 1f),
                    colorFilter = ColorFilter.tint(sparkOf(light), BlendMode.SrcIn),
                    blendMode = BlendMode.Plus,
                )
                // The glaze: a breath of light on the material itself where the light falls.
                drawRect(
                    Brush.radialGradient(listOf(light.copy(alpha = 0.1f * glow), Color.Transparent), center = centre, radius = radius * 0.6f),
                )
            }
        }
    }
}

/** A flake's flare: its own colour, most of the way to white. */
private fun sparkOf(light: Color): Color = Color(
    red = light.red + (1f - light.red) * 0.6f,
    green = light.green + (1f - light.green) * 0.6f,
    blue = light.blue + (1f - light.blue) * 0.6f,
)

/** A group's flakes seen through the light: full at [centre], down to [edge] at [radius]. */
private fun lit(tile: Bitmap, centre: Offset, radius: Float, edge: Float): ShaderBrush = object : ShaderBrush() {
    override fun createShader(size: Size): Shader {
        val mask = RadialGradient(
            centre.x, centre.y, radius.coerceAtLeast(1f),
            intArrayOf(Color.White.toArgb(), Color.White.copy(alpha = edge.coerceIn(0f, 1f)).toArgb()),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        return ComposeShader(BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT), mask, PorterDuff.Mode.DST_IN)
    }
}
