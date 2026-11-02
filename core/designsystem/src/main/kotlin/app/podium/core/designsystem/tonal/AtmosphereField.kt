package app.podium.core.designsystem.tonal

import app.podium.core.designsystem.theme.linearChannelToSrgb
import app.podium.core.designsystem.theme.linearToOklab
import app.podium.core.designsystem.theme.oklabToLinear
import app.podium.core.designsystem.theme.srgbChannelToLinear
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The album's atmosphere on Carbon (D-101): the cover reduced to a small field of its colours,
 * darkened into Carbon's range and defocused far past recognition. Computed once per cover, off the
 * main thread, as ARGB pixels ready to be drawn enlarged behind the display's content — nothing is
 * blurred while drawing.
 *
 * Every pixel keeps its hue but is pressed into OKLab lightness [FLOOR]..[CEILING] (a white cover
 * becomes deep charcoal, never a bright screen), with chroma held under [CHROMA_PER_L] of its
 * lightness so colour reads as an undertone. Then the field is enlarged and blurred three times
 * over (close to a Gaussian about a tenth of its width), in linear light.
 */
object AtmosphereField {
    /** The field's side, in pixels: drawn enlarged, it has nothing finer than this to show. */
    const val SIZE = 96

    /** OKLab lightness Carbon's field keeps to: deep charcoal, never brighter than its keys. */
    const val FLOOR = 0.10
    const val CEILING = 0.30

    /** Chroma at most this share of the lightness (D-103: a little more colour than D-101's 0.24). */
    const val CHROMA_PER_L = 0.30

    /** How a field is toned: the lightness it's pressed into and how much colour it keeps. */
    class Tone(val floor: Double, val ceiling: Double, val chromaPerL: Double)

    /** Carbon (D-101): deep charcoal with the cover's colour as an undertone. */
    val Carbon = Tone(FLOOR, CEILING, CHROMA_PER_L)

    /** Glass on a dark display (D-103): brighter and more colourful, light for the glass to bend. */
    val GlassDark = Tone(0.16, 0.50, 0.38)

    /** Glass on a light display: a pale wash of the cover's colours. */
    val GlassLight = Tone(0.80, 0.95, 0.13)

    /** How far each of the three box passes reaches, as a share of [size]. */
    private const val BLUR = 0.09f

    /**
     * Renders the field for [source] ([width] × [height] ARGB pixels; a 32 × 32 downscale of the cover
     * is plenty) at [size] × [size], opaque.
     */
    fun render(source: IntArray, width: Int, height: Int, size: Int = SIZE, tone: Tone = Carbon): IntArray {
        require(width > 0 && height > 0 && source.size >= width * height)
        // Tone-mapped, in linear light.
        val r = FloatArray(width * height)
        val g = FloatArray(width * height)
        val b = FloatArray(width * height)
        for (i in 0 until width * height) {
            val p = source[i]
            val (l, a, bb) = linearToOklab(
                srgbChannelToLinear(((p shr 16) and 0xFF) / 255.0),
                srgbChannelToLinear(((p shr 8) and 0xFF) / 255.0),
                srgbChannelToLinear((p and 0xFF) / 255.0),
            )
            val lOut = tone.floor + (tone.ceiling - tone.floor) * l.coerceIn(0.0, 1.0).pow(1.15)
            val c = hypot(a, bb)
            val cOut = minOf(c, tone.chromaPerL * lOut)
            val k = if (c > 1e-6) cOut / c else 0.0
            val (lr, lg, lb) = oklabToLinear(lOut, a * k, bb * k)
            r[i] = lr.coerceIn(0.0, 1.0).toFloat()
            g[i] = lg.coerceIn(0.0, 1.0).toFloat()
            b[i] = lb.coerceIn(0.0, 1.0).toFloat()
        }
        val fr = enlarge(r, width, height, size)
        val fg = enlarge(g, width, height, size)
        val fb = enlarge(b, width, height, size)
        val radius = (size * BLUR).roundToInt().coerceAtLeast(1)
        for (channel in arrayOf(fr, fg, fb)) repeat(3) { boxBlur(channel, size, radius) }
        return IntArray(size * size) { i ->
            fun ch(v: Float) = (linearChannelToSrgb(v.toDouble()) * 255.0).roundToInt().coerceIn(0, 255)
            (0xFF shl 24) or (ch(fr[i]) shl 16) or (ch(fg[i]) shl 8) or ch(fb[i])
        }
    }

    /** Bilinear enlargement of one channel to [size] × [size], sampling pixel centres. */
    private fun enlarge(src: FloatArray, w: Int, h: Int, size: Int): FloatArray {
        val out = FloatArray(size * size)
        for (y in 0 until size) {
            val sy = ((y + 0.5f) * h / size - 0.5f).coerceIn(0f, (h - 1).toFloat())
            val y0 = sy.toInt()
            val y1 = minOf(y0 + 1, h - 1)
            val ty = sy - y0
            for (x in 0 until size) {
                val sx = ((x + 0.5f) * w / size - 0.5f).coerceIn(0f, (w - 1).toFloat())
                val x0 = sx.toInt()
                val x1 = minOf(x0 + 1, w - 1)
                val tx = sx - x0
                val top = src[y0 * w + x0] * (1 - tx) + src[y0 * w + x1] * tx
                val bottom = src[y1 * w + x0] * (1 - tx) + src[y1 * w + x1] * tx
                out[y * size + x] = top * (1 - ty) + bottom * ty
            }
        }
        return out
    }

    /** One horizontal and one vertical box pass of [radius], edges held. */
    private fun boxBlur(data: FloatArray, size: Int, radius: Int) {
        val line = FloatArray(size)
        val span = 2 * radius + 1
        for (pass in 0..1) {
            for (j in 0 until size) {
                fun at(i: Int): Float {
                    val k = i.coerceIn(0, size - 1)
                    return if (pass == 0) data[j * size + k] else data[k * size + j]
                }
                var sum = 0f
                for (i in -radius..radius) sum += at(i)
                for (i in 0 until size) {
                    line[i] = sum / span
                    sum += at(i + radius + 1) - at(i - radius)
                }
                for (i in 0 until size) {
                    if (pass == 0) data[j * size + i] = line[i] else data[i * size + j] = line[i]
                }
            }
        }
    }
}
