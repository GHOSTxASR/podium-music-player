package app.podium.stickers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import kotlin.math.max
import kotlin.math.roundToInt

/** The picture a sticker is made from, read upright and no larger than needed (D-55). */
object StickerPictures {
    /** The working picture's longest side: plenty for a [StickerArt.MAX_SIDE] sticker, light to cut. */
    const val WORKING_SIDE = 1280

    /** [uri] decoded upright, at most [maxSide] on its longest side; null when it can't be read. Blocking. */
    fun decode(context: Context, uri: Uri, maxSide: Int = WORKING_SIDE): Bitmap? = try {
        val resolver = context.contentResolver
        // With inJustDecodeBounds the decoder answers null and only fills in the size; that null is no failure.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val probe = resolver.openInputStream(uri) ?: return null
        probe.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val longest = max(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest / (sample * 2) >= maxSide) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        val orientation = runCatching {
            resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        StickerArt.fit(upright(decoded, orientation), maxSide)
    } catch (e: Exception) {
        Log.i("PodiumStickers", "picture unreadable (${e.javaClass.simpleName})")
        null
    }

    private fun upright(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        val turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (turned !== bitmap) bitmap.recycle()
        return turned
    }

    /** How much of the picture the mask keeps (0–1), to tell "found nothing" from a subject. */
    fun coverage(mask: FloatArray): Float = mask.count { it >= 0.5f } / mask.size.toFloat()

    /**
     * The mask as a darkening of everything that isn't the subject: one ARGB pixel per mask cell,
     * black at [strength] where the mask is 0, clear where it's 1. Drawn over the picture, the
     * subject stays lit.
     */
    fun shade(mask: FloatArray, pixels: IntArray, strength: Float = 0.72f) {
        for (i in mask.indices) {
            val a = ((1f - mask[i].coerceIn(0f, 1f)) * strength * 255f).roundToInt()
            pixels[i] = a shl 24
        }
    }
}
