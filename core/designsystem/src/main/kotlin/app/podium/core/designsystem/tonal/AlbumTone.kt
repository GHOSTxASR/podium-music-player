package app.podium.core.designsystem.tonal

import android.graphics.Bitmap
import androidx.compose.animation.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.podium.core.designsystem.theme.PodiumMotion
import app.podium.core.designsystem.theme.PodiumTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.WeakHashMap

/**
 * The playing album's tone on Carbon (D-101), animated: when the cover changes, every role moves to
 * the new [TonePalette] together. The colours are snapshot state, so read them while drawing
 * (`drawBehind`, `Canvas`, `graphicsLayer`): a change of album then redraws only what uses them and
 * recomposes nothing.
 */
@Stable
class AlbumTone(initial: TonePalette = TonePalette.Neutral) {
    private val accentA = Animatable(initial.accent)
    private val onAccentA = Animatable(initial.onAccent)
    private val accentContainerA = Animatable(initial.accentContainer)
    private val onAccentContainerA = Animatable(initial.onAccentContainer)
    private val surfaceLowA = Animatable(initial.surfaceLow)
    private val surfaceA = Animatable(initial.surface)
    private val surfaceHighA = Animatable(initial.surfaceHigh)
    private val surfaceHighestA = Animatable(initial.surfaceHighest)

    val accent: Color get() = accentA.value
    val onAccent: Color get() = onAccentA.value
    val accentContainer: Color get() = accentContainerA.value
    val onAccentContainer: Color get() = onAccentContainerA.value
    val surfaceLow: Color get() = surfaceLowA.value
    val surface: Color get() = surfaceA.value
    val surfaceHigh: Color get() = surfaceHighA.value
    val surfaceHighest: Color get() = surfaceHighestA.value

    /** The palette it is showing, or moving to. */
    var palette: TonePalette by mutableStateOf(initial)
        private set

    private val all get() = listOf(accentA, onAccentA, accentContainerA, onAccentContainerA, surfaceLowA, surfaceA, surfaceHighA, surfaceHighestA)

    private fun TonePalette.roles() = listOf(accent, onAccent, accentContainer, onAccentContainer, surfaceLow, surface, surfaceHigh, surfaceHighest)

    /** Every role to [next] together on [spec] (Oklab, so a hue change passes through no grey). */
    suspend fun moveTo(next: TonePalette, spec: AnimationSpec<Color>) {
        palette = next
        coroutineScope { all.zip(next.roles()).forEach { (animatable, target) -> launch { animatable.animateTo(target, spec) } } }
    }

    suspend fun snapTo(next: TonePalette) {
        palette = next
        all.zip(next.roles()).forEach { (animatable, target) -> animatable.snapTo(target) }
    }
}

/** The playing album's tone, provided by the app (Carbon only; elsewhere it stays neutral and unused). */
val LocalAlbumTone = staticCompositionLocalOf { AlbumTone() }

/** A palette change takes this long: a little slower than a skip's cover travel, so colour follows the cover. */
const val TONE_MILLIS = 700

/**
 * The tone for [palette] (null: there is no cover — neutral). It starts on the palette it's first
 * given and moves to every later one.
 */
@Composable
fun rememberAlbumTone(palette: TonePalette?): AlbumTone {
    val reduced = PodiumTheme.motion.reduced
    val tone = remember { AlbumTone(palette ?: TonePalette.Neutral) }
    LaunchedEffect(palette) {
        val next = palette ?: TonePalette.Neutral
        if (next != tone.palette) tone.moveTo(next, tween(if (reduced) 200 else TONE_MILLIS, easing = PodiumMotion.Standard))
    }
    return tone
}

/**
 * What the display takes from one cover: its palette, Carbon's atmosphere field, and Glass's
 * brighter fields for a dark and a light display (D-103).
 */
class CoverAnalysis(val palette: TonePalette, val field: ImageBitmap, val glassDark: ImageBitmap, val glassLight: ImageBitmap) {
    fun glassField(dark: Boolean): ImageBitmap = if (dark) glassDark else glassLight
}

/**
 * The analysis of the playing cover, worked out off the main thread (a few milliseconds) and kept
 * while the bitmap lives, so a cover seen again (a skip back) is instant. While the next cover is
 * still loading the last one's stays ([artwork] null, [settled] false): a skip never dips through
 * neutral. Null only once there's definitely no artwork ([settled] with no bitmap).
 */
@Composable
fun rememberCoverAnalysis(artwork: ImageBitmap?, settled: Boolean): CoverAnalysis? {
    var shown by remember { mutableStateOf(artwork?.let { Covers.cached(it.asAndroidBitmap()) }) }
    LaunchedEffect(artwork, settled) {
        when {
            artwork != null -> {
                val bitmap = artwork.asAndroidBitmap()
                shown = Covers.cached(bitmap) ?: withContext(Dispatchers.Default) { Covers.analyse(bitmap) }
            }
            settled -> shown = null
        }
    }
    return shown
}

private object Covers {
    private val cache = WeakHashMap<Bitmap, CoverAnalysis>()

    fun cached(bitmap: Bitmap): CoverAnalysis? = synchronized(cache) { cache[bitmap] }

    fun analyse(bitmap: Bitmap): CoverAnalysis {
        cached(bitmap)?.let { return it }
        val side = SAMPLE
        val small = downscale(bitmap, side)
        val pixels = IntArray(side * side)
        small.getPixels(pixels, 0, side, 0, 0, side, side)
        if (small !== bitmap) small.recycle()
        val size = AtmosphereField.SIZE
        fun field(tone: AtmosphereField.Tone) =
            Bitmap.createBitmap(AtmosphereField.render(pixels, side, side, size, tone), size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
        val analysis = CoverAnalysis(
            TonePalette.fromPixels(pixels),
            field(AtmosphereField.Carbon),
            field(AtmosphereField.GlassDark),
            field(AtmosphereField.GlassLight),
        )
        synchronized(cache) { cache[bitmap] = analysis }
        return analysis
    }

    /** Halving steps down to [side]: every source pixel counts (a single bilinear step skips most of them). */
    private fun downscale(source: Bitmap, side: Int): Bitmap {
        var current = source
        while (current.width > side * 2 && current.height > side * 2) {
            val next = Bitmap.createScaledBitmap(current, current.width / 2, current.height / 2, true)
            if (current !== source) current.recycle()
            current = next
        }
        val last = Bitmap.createScaledBitmap(current, side, side, true)
        if (current !== source && current !== last) current.recycle()
        return last
    }

    private const val SAMPLE = 32
}
