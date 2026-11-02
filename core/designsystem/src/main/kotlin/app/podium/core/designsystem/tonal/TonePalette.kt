package app.podium.core.designsystem.tonal

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import app.podium.core.designsystem.theme.oklchInGamut
import app.podium.core.designsystem.theme.srgbToOklab
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * Carbon's adaptive colours for one cover (D-101): one toned-down accent from the artwork's most
 * telling hue, and surfaces tinted faintly toward it, all dark. Pure data, computed once per cover
 * off the main thread. [Neutral] is Carbon with no cover, or with a cover that has no colour to
 * speak of (black-and-white photography stays black and white).
 *
 * The roles follow a dark tonal scheme: the accent is light (OKLCH L 0.80) and never loud (chroma at
 * most 0.11), its container dark; surfaces climb from L 0.185 to 0.30 with a tint of the hue far
 * below anything that reads as colour. Text is never taken from here: it stays Carbon's neutral ink.
 */
@Immutable
data class TonePalette(
    /** The one strong colour: progress, the play key, indicators, active glyphs. */
    val accent: Color,
    /** Glyphs on [accent]. */
    val onAccent: Color,
    /** A toggled action's backing. */
    val accentContainer: Color,
    /** Glyphs on [accentContainer]. */
    val onAccentContainer: Color,
    /** Grouped controls sit on this: the action row, the header's back key. */
    val surfaceLow: Color,
    /** Floating surfaces: the mini player, menus, notices. */
    val surface: Color,
    /** Keys and the focus band. */
    val surfaceHigh: Color,
    /** A key under the finger; the Wheel's focused action. */
    val surfaceHighest: Color,
    /** The cover's hue in degrees, or null for the neutral palette. */
    val hue: Float?,
) {
    companion object {
        /** Carbon without a cover: graphite surfaces and a platinum accent. */
        val Neutral = TonePalette(
            accent = Color(0xFFE6E3DC),
            onAccent = Color(0xFF141416),
            accentContainer = Color(0xFF34332F),
            onAccentContainer = Color(0xFFF3F1EC),
            surfaceLow = Color(0xFF141416),
            surface = Color(0xFF1A1A1D),
            surfaceHigh = Color(0xFF232327),
            surfaceHighest = Color(0xFF2D2D32),
            hue = null,
        )

        /** Hue bins of 10°. */
        private const val BINS = 36

        /** Below this chroma a pixel is grey: it says nothing about the cover's colour. */
        private const val MIN_CHROMA = 0.035

        /** Mean colour weight per pixel under which a cover counts as colourless. */
        private const val NEUTRAL_BELOW = 0.003

        /**
         * The palette for a cover's pixels (ARGB, any order; a 32 × 32 downscale is plenty). The
         * seed is the hue family (a bin and its neighbours) carrying the most colour, where vivid,
         * mid-light pixels count most and near-black and near-white ones not at all.
         */
        fun fromPixels(argb: IntArray): TonePalette {
            val weight = DoubleArray(BINS)
            val sumA = DoubleArray(BINS)
            val sumB = DoubleArray(BINS)
            val sumC = DoubleArray(BINS)
            var counted = 0
            for (p in argb) {
                if ((p ushr 24) < 128) continue
                counted++
                val (l, a, b) = srgbToOklab(p)
                if (l < 0.18 || l > 0.96) continue
                val c = hypot(a, b)
                if (c < MIN_CHROMA) continue
                val deg = Math.toDegrees(atan2(b, a)).let { if (it < 0) it + 360 else it }
                val bin = (deg / (360.0 / BINS)).toInt().coerceIn(0, BINS - 1)
                val w = c * (1.0 - min(abs(l - 0.62), 0.5))
                weight[bin] += w
                sumA[bin] += a * w
                sumB[bin] += b * w
                sumC[bin] += c * w
            }
            if (counted == 0 || weight.sum() / counted < NEUTRAL_BELOW) return Neutral
            var best = 0
            var bestScore = -1.0
            for (i in 0 until BINS) {
                val score = weight[(i + BINS - 1) % BINS] * 0.5 + weight[i] + weight[(i + 1) % BINS] * 0.5
                if (score > bestScore) {
                    bestScore = score
                    best = i
                }
            }
            var a = 0.0
            var b = 0.0
            var c = 0.0
            var w = 0.0
            for (d in -1..1) {
                val i = (best + d + BINS) % BINS
                a += sumA[i]
                b += sumB[i]
                c += sumC[i]
                w += weight[i]
            }
            val hue = Math.toDegrees(atan2(b, a)).let { if (it < 0) it + 360 else it }
            return toned(hue, c / w)
        }

        /** The dark scheme for a seed [hue] (degrees) of [chroma]: toned down, never loud. */
        fun toned(hue: Double, chroma: Double): TonePalette {
            val c = (chroma * 0.85).coerceIn(0.05, 0.11)
            val tint = min(chroma * 0.16, 0.014)
            return TonePalette(
                accent = oklchInGamut(0.80, c, hue),
                onAccent = oklchInGamut(0.23, min(c * 0.4, 0.04), hue),
                accentContainer = oklchInGamut(0.33, min(c * 0.6, 0.065), hue),
                onAccentContainer = oklchInGamut(0.91, min(c * 0.35, 0.04), hue),
                surfaceLow = oklchInGamut(0.185, tint * 0.8, hue),
                surface = oklchInGamut(0.215, tint, hue),
                surfaceHigh = oklchInGamut(0.255, tint, hue),
                surfaceHighest = oklchInGamut(0.30, tint, hue),
                hue = hue.toFloat(),
            )
        }
    }
}
