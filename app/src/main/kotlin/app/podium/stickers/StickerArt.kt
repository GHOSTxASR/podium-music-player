package app.podium.stickers

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The pixels of a sticker (D-55): a cut-out from a picture and a mask, and the sticker made from it
 * — trimmed to the subject, a border that follows the subject's own outline (never a rectangle),
 * and a hint of a shadow. Plain Android bitmaps, no model, so it's testable anywhere.
 */
object StickerArt {
    /** A sticker's image is at most this on its longest side. */
    const val MAX_SIDE = 640

    /** The thickest border, as a fraction of the sticker's longest side (thickness 1). */
    private const val MAX_BORDER_FRACTION = 0.07f
    private const val MIN_BORDER_PX = 2f

    /**
     * The mask as the subject's alpha: [mask] holds one value per pixel of a [maskSize]×[maskSize]
     * grid (0 = background, 1 = subject); it's sampled up to [width]×[height] with a soft edge.
     */
    fun alphaFrom(mask: FloatArray, maskSize: Int, width: Int, height: Int): ByteArray {
        val alpha = ByteArray(width * height)
        for (y in 0 until height) {
            val my = (y + 0.5f) / height * maskSize - 0.5f
            for (x in 0 until width) {
                val mx = (x + 0.5f) / width * maskSize - 0.5f
                val v = bilinear(mask, maskSize, mx, my)
                // A short ramp around 0.5 keeps the edge clean but not jagged.
                val a = ((v - 0.35f) / 0.3f).coerceIn(0f, 1f)
                alpha[y * width + x] = (a * 255f).roundToInt().toByte()
            }
        }
        return alpha
    }

    private fun bilinear(m: FloatArray, size: Int, x: Float, y: Float): Float {
        val x0 = x.toInt().coerceIn(0, size - 1)
        val y0 = y.toInt().coerceIn(0, size - 1)
        val x1 = min(x0 + 1, size - 1)
        val y1 = min(y0 + 1, size - 1)
        val fx = (x - x0).coerceIn(0f, 1f)
        val fy = (y - y0).coerceIn(0f, 1f)
        val top = m[y0 * size + x0] * (1 - fx) + m[y0 * size + x1] * fx
        val bottom = m[y1 * size + x0] * (1 - fx) + m[y1 * size + x1] * fx
        return top * (1 - fy) + bottom * fy
    }

    /** [source] with [alpha] as its transparency, trimmed to the subject (null when nothing is left). */
    fun cutout(source: Bitmap, alpha: ByteArray): Bitmap? {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        var left = w
        var top = h
        var right = -1
        var bottom = -1
        for (y in 0 until h) for (x in 0 until w) {
            val a = alpha[y * w + x].toInt() and 0xFF
            val i = y * w + x
            pixels[i] = (a shl 24) or (pixels[i] and 0x00FFFFFF)
            if (a > 24) {
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        if (right < left || bottom < top) return null
        val full = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        val trimmed = Bitmap.createBitmap(full, left, top, right - left + 1, bottom - top + 1)
        return fit(trimmed, MAX_SIDE)
    }

    /**
     * The finished sticker: [cutout] on a border of [border]'s colour that follows its outline at
     * [thickness] (0–1), with a hint of a shadow beneath. Padded so nothing is clipped.
     */
    fun compose(cutout: Bitmap, border: StickerBorder, thickness: Float): Bitmap {
        val longest = max(cutout.width, cutout.height)
        val radius = if (border == StickerBorder.NONE) 0f else max(MIN_BORDER_PX, longest * MAX_BORDER_FRACTION * thickness.coerceIn(0.05f, 1f))
        val shadow = max(2f, longest * 0.012f)
        val pad = (radius + shadow * 3).roundToInt() + 2
        val w = cutout.width + pad * 2
        val h = cutout.height + pad * 2
        val alpha = ByteArray(w * h)
        val src = IntArray(cutout.width * cutout.height)
        cutout.getPixels(src, 0, cutout.width, 0, 0, cutout.width, cutout.height)
        for (y in 0 until cutout.height) for (x in 0 until cutout.width) {
            alpha[(y + pad) * w + (x + pad)] = (src[y * cutout.width + x] ushr 24).toByte()
        }
        val outline = if (radius > 0f) dilate(alpha, w, h, radius) else alpha
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        // The shadow: the outline, soft and faint, a little below.
        val shape = maskBitmap(outline, w, h, Color.BLACK)
        canvas.drawBitmap(shape.extractAlpha(), 0f, shadow, Paint().apply {
            color = Color.argb(70, 0, 0, 0)
            maskFilter = BlurMaskFilter(shadow * 1.5f, BlurMaskFilter.Blur.NORMAL)
            isAntiAlias = true
        })
        if (radius > 0f) {
            canvas.drawBitmap(maskBitmap(outline, w, h, if (border == StickerBorder.WHITE) Color.WHITE else Color.BLACK), 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        canvas.drawBitmap(cutout, pad.toFloat(), pad.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /**
     * The outline grown by [radius] px — each pixel takes its distance to the subject (a two-pass
     * chamfer distance, close to Euclidean) — with a one-pixel soft edge.
     */
    internal fun dilate(alpha: ByteArray, w: Int, h: Int, radius: Float): ByteArray {
        val inf = Float.MAX_VALUE / 4
        val d = FloatArray(w * h) { if ((alpha[it].toInt() and 0xFF) >= 128) 0f else inf }
        val a = 1f
        val b = sqrt(2f)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            var v = d[i]
            if (x > 0) v = min(v, d[i - 1] + a)
            if (y > 0) {
                v = min(v, d[i - w] + a)
                if (x > 0) v = min(v, d[i - w - 1] + b)
                if (x < w - 1) v = min(v, d[i - w + 1] + b)
            }
            d[i] = v
        }
        for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) {
            val i = y * w + x
            var v = d[i]
            if (x < w - 1) v = min(v, d[i + 1] + a)
            if (y < h - 1) {
                v = min(v, d[i + w] + a)
                if (x < w - 1) v = min(v, d[i + w + 1] + b)
                if (x > 0) v = min(v, d[i + w - 1] + b)
            }
            d[i] = v
        }
        return ByteArray(w * h) { i ->
            val inside = (radius - d[i] + 0.5f).coerceIn(0f, 1f)
            val own = (alpha[i].toInt() and 0xFF) / 255f
            (max(inside, own) * 255f).roundToInt().toByte()
        }
    }

    private fun maskBitmap(alpha: ByteArray, w: Int, h: Int, color: Int): Bitmap {
        val rgb = color and 0x00FFFFFF
        val pixels = IntArray(w * h) { ((alpha[it].toInt() and 0xFF) shl 24) or rgb }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    /** [bitmap] no larger than [maxSide] on its longest side. */
    fun fit(bitmap: Bitmap, maxSide: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxSide) return bitmap
        val f = maxSide / longest.toFloat()
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * f).roundToInt().coerceAtLeast(1), (bitmap.height * f).roundToInt().coerceAtLeast(1), true)
    }

    /**
     * A brush stroke on the mask while refining: a soft disc at ([x],[y]) (fractions of the picture)
     * that adds to the subject or erases from it. The mask is square but the picture needn't be, so
     * the disc's radius is given per axis ([rx], [ry], fractions of the picture's width and height)
     * to stay round on the picture. Returns false when nothing changed.
     */
    fun brush(mask: FloatArray, size: Int, x: Float, y: Float, rx: Float, ry: Float, add: Boolean): Boolean {
        val cx = x * size
        val cy = y * size
        val ax = (rx * size).coerceAtLeast(0.5f)
        val ay = (ry * size).coerceAtLeast(0.5f)
        val x0 = (cx - ax).toInt().coerceAtLeast(0)
        val x1 = (cx + ax).toInt().coerceAtMost(size - 1)
        val y0 = (cy - ay).toInt().coerceAtLeast(0)
        val y1 = (cy + ay).toInt().coerceAtMost(size - 1)
        var changed = false
        for (py in y0..y1) for (px in x0..x1) {
            val dx = (px + 0.5f - cx) / ax
            val dy = (py + 0.5f - cy) / ay
            val dist = sqrt(dx * dx + dy * dy)
            if (dist > 1f) continue
            // Full strength inside two thirds of the radius, a soft falloff to the rim.
            val strength = ((1f - dist) / 0.35f).coerceIn(0f, 1f)
            val i = py * size + px
            val v = if (add) max(mask[i], strength) else min(mask[i], 1f - strength)
            if (v != mask[i]) {
                mask[i] = v
                changed = true
            }
        }
        return changed
    }
}
