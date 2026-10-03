package app.podium.core.designsystem.artwork

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.podium.core.designsystem.glass.GlassTier
import app.podium.core.designsystem.glass.LocalGlassTier
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.designsystem.theme.oklchToColor
import app.podium.core.designsystem.type.PodiumText
import kotlin.math.roundToInt

/** Loads artwork by URI (provided by the app; resolves `podium-art://` through the sources). */
fun interface ArtworkLoader {
    suspend fun load(uri: String, sizePx: Int): ImageBitmap?

    /** A memory-cache hit, synchronously: cached artwork is on screen in the first frame. */
    fun peek(uri: String, sizePx: Int): ImageBitmap? = null
}

val LocalArtworkLoader = staticCompositionLocalOf<ArtworkLoader> { ArtworkLoader { _, _ -> null } }

/** Size buckets keep the image cache small and hit rates high (performance.md §3). */
fun artworkBucketPx(sizePx: Int): Int = BUCKETS.firstOrNull { it >= sizePx } ?: BUCKETS.last()

private val BUCKETS = listOf(96, 192, 512, 1024)

/**
 * Artwork for one URI. [settled] is false only while a load is in flight, so callers can tell
 * "still loading" (keep the placeholder quiet) from "there is no artwork" (show the fallback).
 */
@Immutable
data class ArtworkState(
    val bitmap: ImageBitmap?,
    val settled: Boolean,
    val fromCache: Boolean,
    /** A smaller cached copy of the same artwork, shown at once while [bitmap] loads. */
    val placeholder: ImageBitmap? = null,
)

@Composable
fun rememberArtworkState(uri: String?, size: Dp): ArtworkState {
    val loader = LocalArtworkLoader.current
    val px = artworkBucketPx(with(LocalDensity.current) { size.toPx().roundToInt() })
    return key(uri, px) {
        val cached = remember { uri?.let { loader.peek(it, px) } }
        val smaller = remember {
            if (cached != null || uri == null) null else BUCKETS.filter { it < px }.reversed().firstNotNullOfOrNull { loader.peek(uri, it) }
        }
        val initial = ArtworkState(cached, settled = cached != null || uri == null, fromCache = cached != null, placeholder = smaller)
        val state by produceState(initial) {
            if (cached == null && uri != null) value = ArtworkState(loader.load(uri, px), settled = true, fromCache = false, placeholder = smaller)
        }
        state
    }
}

@Composable
fun rememberArtwork(uri: String?, size: Dp): ImageBitmap? = rememberArtworkState(uri, size).bitmap

/** Radius proportional to size, capped (design-system.md §4): small thumbnails stay crisp. */
fun artworkRadius(size: Dp): Dp = (size * 0.045f).coerceIn(4.dp, 14.dp)

/**
 * Square album artwork for rows and the mini player. Loading shows a quiet surface; the image
 * fades in over it (cached art appears at once); missing artwork gets [ArtworkFallback].
 */
@Composable
fun ArtworkImage(
    uri: String?,
    size: Dp,
    fallbackText: String,
    modifier: Modifier = Modifier,
) {
    val colors = PodiumTheme.colors
    val shape = RoundedCornerShape(artworkRadius(size))
    val art = rememberArtworkState(uri, size)
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(colors.canvasRaised)
            .artworkRim(shape),
        contentAlignment = Alignment.Center,
    ) {
        ArtworkLayers(art, fallbackText)
    }
}

/** Placeholder (a smaller cached copy) or fallback, with the real image fading in over it. */
@Composable
private fun ArtworkLayers(art: ArtworkState, fallbackText: String) {
    if (art.bitmap == null && art.settled) ArtworkFallback(fallbackText, Modifier.fillMaxSize())
    if (art.bitmap == null) {
        art.placeholder?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
    art.bitmap?.let { FadeInImage(it, art.fromCache || art.placeholder != null, ContentScale.Crop, Modifier.fillMaxSize()) }
}

@Composable
private fun FadeInImage(bitmap: ImageBitmap, immediate: Boolean, scale: ContentScale, modifier: Modifier) {
    val reduced = PodiumTheme.motion.reduced
    val alpha = remember(bitmap) { Animatable(if (immediate || reduced) 1f else 0f) }
    LaunchedEffect(bitmap) { if (alpha.value < 1f) alpha.animateTo(1f, tween(220)) }
    Image(bitmap, contentDescription = null, contentScale = scale, modifier = modifier.graphicsLayer { this.alpha = alpha.value })
}

/**
 * Podium's cover for music without artwork: two close tones chosen from the title (so the same
 * song always gets the same cover), Podium's ring, and the initial. Quiet enough to sit among real
 * covers, distinct enough to tell songs apart — never a generic music-note placeholder.
 */
@Composable
fun ArtworkFallback(seed: String, modifier: Modifier = Modifier) {
    val dark = PodiumTheme.colors.isDark
    val hue = remember(seed) { ((seed.hashCode() and 0x7fffffff) % 360).toDouble() }
    val top = remember(hue, dark) { oklchToColor(if (dark) 0.40 else 0.86, 0.06, hue) }
    val bottom = remember(hue, dark) { oklchToColor(if (dark) 0.27 else 0.76, 0.07, hue + 24) }
    val ink = if (dark) Color.White else Color.Black
    Box(modifier.background(Brush.linearGradient(listOf(top, bottom))), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension * 0.30f
            drawCircle(ink.copy(alpha = 0.16f), radius = r, style = Stroke(width = size.minDimension * 0.035f))
        }
        val initial = seed.trim().firstOrNull()?.uppercase() ?: ""
        if (initial.isNotEmpty()) {
            val style = PodiumTheme.type.title
            BoxWithFraction { minDp ->
                PodiumText(initial, style, ink.copy(alpha = 0.55f), fontSize = style.fontSize * (minDp / 44f).coerceIn(0.9f, 4f))
            }
        }
    }
}

@Composable
private fun BoxWithFraction(content: @Composable (minDp: Float) -> Unit) {
    androidx.compose.foundation.layout.BoxWithConstraints(contentAlignment = Alignment.Center) {
        content(minOf(maxWidth.value, maxHeight.value))
    }
}

/**
 * The Now Playing artwork: as large as [maxWidth] × [maxHeight] allows while keeping the image's
 * own proportions — square covers stay square, portrait and landscape art aren't cropped into a
 * square (extremes beyond 1:2 are cropped to it). The frame animates between proportions; the
 * caller reserves the full box, so the layout around it never shifts.
 */
@Composable
fun FittedArtwork(
    uri: String?,
    fallbackText: String,
    maxWidth: Dp,
    maxHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val colors = PodiumTheme.colors
    val art = rememberArtworkState(uri, maxOf(maxWidth, maxHeight))
    val aspect = (art.bitmap ?: art.placeholder)?.let { (it.width.toFloat() / it.height).coerceIn(0.5f, 2f) } ?: 1f
    val w: Dp
    val h: Dp
    if (aspect >= 1f) {
        w = minOf(maxWidth, maxHeight * aspect)
        h = w / aspect
    } else {
        h = minOf(maxHeight, maxWidth / aspect)
        w = h * aspect
    }
    val width by animateDpAsState(w, spring(stiffness = 500f, dampingRatio = 0.9f), label = "artWidth")
    val height by animateDpAsState(h, spring(stiffness = 500f, dampingRatio = 0.9f), label = "artHeight")
    val shape = RoundedCornerShape(artworkRadius(minOf(width, height)) + 2.dp)
    Box(
        modifier
            .size(width, height)
            .shadow(if (colors.isDark) 22.dp else 14.dp, shape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(colors.canvasRaised)
            .artworkRim(shape),
        contentAlignment = Alignment.Center,
    ) {
        ArtworkLayers(art, fallbackText)
    }
}

/** A hairline rim drawn over the artwork, so dark covers still have an edge on a dark screen. */
@Composable
private fun Modifier.artworkRim(shape: androidx.compose.ui.graphics.Shape): Modifier {
    val color = PodiumTheme.colors.labelPrimary.copy(alpha = if (PodiumTheme.colors.isDark) 0.10f else 0.08f)
    return drawWithContent {
        drawContent()
        val outline = shape.createOutline(size, layoutDirection, this)
        drawOutline(outline, color, style = Stroke(width = 1.dp.toPx()))
    }
}

/**
 * Background extension (design-system.md §5.6): the artwork, heavily blurred, extends into the
 * whole canvas behind Now Playing and fades out at the top and bottom, so the art's colour reaches
 * the edges with no hard boundary and glass controls have real content to refract. A scrim keyed
 * to the artwork's brightness keeps text legible over very bright (or, in light mode, very dark)
 * covers. Pass a small bitmap; it is blurred beyond recognition anyway. Not drawn on the Solid tier.
 */
@Composable
fun BackgroundExtension(bitmap: ImageBitmap?, modifier: Modifier = Modifier) {
    if (bitmap == null || LocalGlassTier.current == GlassTier.Solid) return
    val colors = PodiumTheme.colors
    val luminance = remember(bitmap) { averageLuminance(bitmap) }
    // How much the cover fights the text: bright art in dark mode, dark art in light mode.
    val risk = if (colors.isDark) luminance else 1f - luminance
    val alpha = if (colors.isDark) 0.46f else 0.30f
    Box(modifier) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(alpha = alpha, compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.55f),
                            0.25f to Color.Black,
                            0.62f to Color.Black,
                            1f to Color.Transparent,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                }
                .blur(72.dp),
        )
        Box(Modifier.fillMaxSize().background(colors.canvas.copy(alpha = (0.10f + 0.45f * risk).coerceIn(0f, 0.6f))))
    }
}

/** Mean relative luminance (0–1) of an 8 × 8 downscale: cheap, once per cover. */
private fun averageLuminance(bitmap: ImageBitmap): Float {
    val small = android.graphics.Bitmap.createScaledBitmap(bitmap.asAndroidBitmap(), 8, 8, true)
    val pixels = IntArray(64)
    small.getPixels(pixels, 0, 8, 0, 0, 8, 8)
    if (small != bitmap.asAndroidBitmap()) small.recycle()
    return pixels.map { p ->
        val r = ((p shr 16) and 0xFF) / 255f
        val g = ((p shr 8) and 0xFF) / 255f
        val b = (p and 0xFF) / 255f
        0.2126f * r + 0.7152f * g + 0.0722f * b
    }.average().toFloat()
}
