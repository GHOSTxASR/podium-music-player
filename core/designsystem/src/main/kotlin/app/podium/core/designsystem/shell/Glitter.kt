package app.podium.core.designsystem.shell

import android.graphics.Bitmap
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Shader
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import app.podium.core.designsystem.theme.PodiumTheme
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Glitter in the body's material (D-41, PODIUM_CUSTOMIZATION.md §6): fine reflective flakes
 * embedded in the plastic or metal, the way metal-flake paint looks up close — most flakes dull,
 * a few catching the light. Never stars, never sparkles on top.
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
    /** A very slow glint moving across the body, as if the device were tilted in the light. */
    val animated: Boolean = false,
) {
    companion object {
        const val DEFAULT_AMOUNT = 0.4f
        const val DEFAULT_DENSITY = 0.35f
        const val DEFAULT_SIZE = 0.3f
        const val DEFAULT_OPACITY = 0.5f
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

    fun flakePx(size: Float, pxPerDp: Float): Float = (0.4f + 1.0f * size.coerceIn(0f, 1f)) * pxPerDp
}

/** Two white alpha tiles (lit flakes, dull flakes), tinted with the body's colours when drawn. */
private class GlitterTiles(val lit: Bitmap, val dull: Bitmap)

private object GlitterTileCache {
    private const val TILE = 512
    private val cache = object : LinkedHashMap<String, GlitterTiles>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, GlitterTiles>?) = size > 3
    }

    @Synchronized
    fun tiles(glitter: Glitter, pxPerDp: Float): GlitterTiles {
        // Quantised, so dragging a level re-renders only when the result would differ.
        fun q(v: Float) = (v.coerceIn(0f, 1f) * 20).roundToInt()
        val key = "${q(glitter.density)}-${q(glitter.amount)}-${q(glitter.size)}-${(pxPerDp * 4).roundToInt()}"
        return cache.getOrPut(key) {
            val flakes = GlitterField.flakes(TILE, q(glitter.density) / 20f, q(glitter.amount) / 20f, GlitterField.flakePx(q(glitter.size) / 20f, pxPerDp))
            GlitterTiles(render(flakes.filter { it.lit }), render(flakes.filter { !it.lit }))
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
 * Overlays the body's glitter. The tiles are rendered once per setting and repeated; drawing is two
 * tiled rects (and, when animated, a third with a moving glint, refreshed about 12 times a second and
 * only while the device is on screen). Clip before this modifier to keep it inside a shape.
 */
@Composable
fun Modifier.glitter(glitter: Glitter, light: Color, dark: Color): Modifier {
    if (!glitter.enabled || glitter.opacity <= 0f) return this
    val pxPerDp = LocalDensity.current.density
    val tiles = remember(glitter.density, glitter.amount, glitter.size, pxPerDp) { GlitterTileCache.tiles(glitter, pxPerDp) }
    val animate = glitter.animated && !PodiumTheme.motion.reduced
    var phase by remember { mutableFloatStateOf(0f) }
    if (animate) {
        LaunchedEffect(Unit) {
            val start = withFrameMillis { it }
            while (true) {
                delay(GLINT_TICK_MS)
                // Suspends while no frames are drawn (screen off, app in the background).
                withFrameMillis { now -> phase = ((now - start) % GLINT_PERIOD_MS) / GLINT_PERIOD_MS.toFloat() }
            }
        }
    }
    val opacity = glitter.opacity.coerceIn(0f, 1f)
    return drawWithCache {
        val lit = ShaderBrush(ImageShader(tiles.lit.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
        val dull = ShaderBrush(ImageShader(tiles.dull.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
        val litFilter = ColorFilter.tint(light, BlendMode.SrcIn)
        val dullFilter = ColorFilter.tint(dark, BlendMode.SrcIn)
        onDrawWithContent {
            drawContent()
            drawRect(dull, alpha = opacity * 0.55f, colorFilter = dullFilter)
            drawRect(lit, alpha = opacity * (if (animate) 0.7f else 0.85f), colorFilter = litFilter)
            if (animate) drawRect(glint(tiles.lit, phase), alpha = opacity * 0.6f, colorFilter = litFilter)
        }
    }
}

/** The lit flakes seen through a soft diagonal band of light at [phase] (0–1) of its sweep. */
private fun glint(lit: Bitmap, phase: Float): ShaderBrush = object : ShaderBrush() {
    override fun createShader(size: Size): Shader {
        val angle = PI / 5
        val span = size.width + size.height
        val centre = -span * 0.25f + span * 1.5f * phase
        val dx = cos(angle).toFloat()
        val dy = sin(angle).toFloat()
        val half = span * 0.18f
        val band = LinearGradient(
            (centre - half) * dx, (centre - half) * dy, (centre + half) * dx, (centre + half) * dy,
            intArrayOf(0, Color.White.toArgb(), 0),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        val flakes = android.graphics.BitmapShader(lit, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        return ComposeShader(flakes, band, PorterDuff.Mode.DST_IN)
    }
}

private const val GLINT_TICK_MS = 80L
private const val GLINT_PERIOD_MS = 14_000L
