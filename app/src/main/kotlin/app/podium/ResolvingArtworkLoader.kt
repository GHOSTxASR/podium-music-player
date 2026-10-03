package app.podium

import app.podium.core.common.DiskActivity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.podium.core.designsystem.artwork.ArtworkLoader
import app.podium.sources.api.ArtworkPayload
import app.podium.sources.api.ArtworkResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Artwork for the UI: resolved through the owning source (no provider URLs here), decoded at the
 * requested size bucket, and kept in a memory cache sized to ~1/8 of the heap (performance.md §3).
 */
class ResolvingArtworkLoader(private val resolver: ArtworkResolver) : ArtworkLoader {

    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    override fun peek(uri: String, sizePx: Int): ImageBitmap? = cache.get("$uri@$sizePx")?.asImageBitmap()

    override suspend fun load(uri: String, sizePx: Int): ImageBitmap? {
        DiskActivity.pulse()
        val key = "$uri@$sizePx"
        cache.get(key)?.let { return it.asImageBitmap() }
        val bitmap = withContext(Dispatchers.IO) {
            when (val payload = resolver.load(uri, sizePx)) {
                is ArtworkPayload.LocalFile -> decode(sizePx) { opts -> BitmapFactory.decodeFile(payload.path, opts) }
                is ArtworkPayload.Bytes -> decode(sizePx) { opts -> BitmapFactory.decodeByteArray(payload.bytes, 0, payload.bytes.size, opts) }
                null -> null
            }
        } ?: return null
        cache.put(key, bitmap)
        return bitmap.asImageBitmap()
    }

    private inline fun decode(sizePx: Int, block: (BitmapFactory.Options) -> Bitmap?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        block(bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= sizePx && bounds.outHeight / (sample * 2) >= sizePx) sample *= 2
        return block(BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
