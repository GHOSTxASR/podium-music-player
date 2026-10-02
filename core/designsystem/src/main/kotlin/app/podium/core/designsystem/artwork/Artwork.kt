package app.podium.core.designsystem.artwork

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.podium.core.designsystem.glass.GlassTier
import app.podium.core.designsystem.glass.LocalGlassTier
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.designsystem.type.PodiumText
import kotlin.math.roundToInt

/** Loads artwork by URI (provided by the app; resolves `podium-art://` through the sources). */
fun interface ArtworkLoader {
    suspend fun load(uri: String, sizePx: Int): ImageBitmap?
}

val LocalArtworkLoader = staticCompositionLocalOf<ArtworkLoader> { ArtworkLoader { _, _ -> null } }

/** Size buckets keep the image cache small and hit rates high (performance.md §3). */
fun artworkBucketPx(sizePx: Int): Int = listOf(96, 192, 512, 1024).firstOrNull { it >= sizePx } ?: 1024

@Composable
fun rememberArtwork(uri: String?, size: Dp): ImageBitmap? {
    val loader = LocalArtworkLoader.current
    val px = artworkBucketPx(with(LocalDensity.current) { size.toPx().roundToInt() })
    val bitmap by produceState<ImageBitmap?>(null, uri, px) {
        value = uri?.let { loader.load(it, px) }
    }
    return bitmap
}

/** Radius proportional to size, capped (design-system.md §4): small thumbnails stay crisp. */
fun artworkRadius(size: Dp): Dp = (size * 0.045f).coerceIn(4.dp, 14.dp)

/**
 * Album artwork. While loading or when absent, a monogram on a raised surface — never a generic
 * music-note placeholder.
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
    val bitmap = rememberArtwork(uri, size)
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(colors.canvasRaised),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            PodiumText(
                text = fallbackText.trim().take(1).uppercase(),
                style = PodiumTheme.type.title,
                color = colors.labelTertiary,
                fontSize = PodiumTheme.type.title.fontSize * (size.value / 44f).coerceIn(1f, 3f),
            )
        }
    }
}

/**
 * Background extension (design-system.md §5.6): the artwork, heavily blurred, extends into the
 * whole canvas behind Now Playing and fades out at the top and bottom, so the art's colour reaches
 * the edges with no hard boundary and the Wheel's glass has real content to refract.
 * Pass a small bitmap; it is blurred beyond recognition anyway. Not drawn on the Solid tier.
 */
@Composable
fun BackgroundExtension(bitmap: ImageBitmap?, modifier: Modifier = Modifier) {
    if (bitmap == null || LocalGlassTier.current == GlassTier.Solid) return
    val alpha = if (PodiumTheme.colors.isDark) 0.42f else 0.26f
    Image(
        bitmap = bitmap,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .graphicsLayer(alpha = alpha, compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.2f to Color.Black,
                        0.6f to Color.Black,
                        1f to Color.Transparent,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            }
            .blur(72.dp),
    )
}
