package app.podium.core.designsystem.tonal

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import app.podium.core.designsystem.shell.BootTimeline
import app.podium.core.designsystem.shell.reflectionTurn
import app.podium.core.designsystem.theme.CarbonTonalColors
import app.podium.core.designsystem.theme.DisplayColors
import app.podium.core.designsystem.theme.srgbToOklab
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Carbon's adaptive colour (D-101): the palette, the atmosphere, the Wheel's light, the boot's reveal. */
class TonalTest {

    private fun cover(vararg argb: Int): IntArray = IntArray(32 * 32) { argb[it % argb.size] }

    private fun hueOf(c: Color): Double {
        val (_, a, b) = srgbToOklab(c.toArgbInt())
        return Math.toDegrees(atan2(b, a)).let { if (it < 0) it + 360 else it }
    }

    private fun chromaOf(c: Color): Double {
        val (_, a, b) = srgbToOklab(c.toArgbInt())
        return hypot(a, b)
    }

    private fun Color.toArgbInt(): Int =
        (0xFF shl 24) or ((red * 255).toInt() shl 16) or ((green * 255).toInt() shl 8) or (blue * 255).toInt()

    @Test
    fun `a black and white cover keeps Carbon neutral`() {
        assertEquals(TonePalette.Neutral, TonePalette.fromPixels(cover(0xFF000000.toInt(), 0xFF808080.toInt(), 0xFFFFFFFF.toInt())))
        assertNull(TonePalette.fromPixels(cover(0xFF3A3A3A.toInt())).hue)
    }

    @Test
    fun `a coloured cover gives its own hue, toned down`() {
        val red = TonePalette.fromPixels(cover(0xFFD32F2F.toInt(), 0xFF101010.toInt()))
        assertNotNull(red.hue)
        assertTrue(abs(hueOf(red.accent) - hueOf(Color(0xFFD32F2F))) < 12, "the accent keeps the cover's hue")
        assertTrue(chromaOf(red.accent) <= 0.115, "never loud: ${chromaOf(red.accent)}")
        val blue = TonePalette.fromPixels(cover(0xFF1565C0.toInt()))
        assertTrue(abs(hueOf(blue.accent) - hueOf(Color(0xFF1565C0))) < 15, "a blue cover gives a blue accent")
    }

    @Test
    fun `the strongest family of colour wins, not the most pixels of grey`() {
        // Mostly grey, a quarter green: green.
        val px = IntArray(32 * 32) { if (it % 4 == 0) 0xFF2E9E4F.toInt() else 0xFF777777.toInt() }
        val green = TonePalette.fromPixels(px)
        assertTrue(abs(hueOf(green.accent) - hueOf(Color(0xFF2E9E4F))) < 15)
    }

    @Test
    fun `every palette keeps Carbon's text and glyphs readable`() {
        val ink = CarbonTonalColors.labelPrimary.luminance()
        val quiet = CarbonTonalColors.labelSecondary.luminance()
        for (hue in 0 until 360 step 15) {
            val p = TonePalette.toned(hue.toDouble(), 0.2)
            for (surface in listOf(p.surfaceLow, p.surface, p.surfaceHigh, p.surfaceHighest)) {
                assertTrue(DisplayColors.contrast(ink, surface.luminance()) >= 10f, "primary text on a surface, hue $hue")
                assertTrue(DisplayColors.contrast(quiet, surface.luminance()) >= 5f, "secondary text on a surface, hue $hue")
                assertTrue(DisplayColors.contrast(p.accent.luminance(), surface.luminance()) >= 4.5f, "accent glyphs on a surface, hue $hue")
            }
            assertTrue(DisplayColors.contrast(p.onAccent.luminance(), p.accent.luminance()) >= 4.5f, "the play key's glyph, hue $hue")
            assertTrue(DisplayColors.contrast(p.accent.luminance(), p.accentContainer.luminance()) >= 3f, "a toggled action's glyph, hue $hue")
        }
    }

    @Test
    fun `the atmosphere stays dark whatever the cover`() {
        for (argb in intArrayOf(0xFFFFFFFF.toInt(), 0xFFFFEB3B.toInt(), 0xFF00E5FF.toInt(), 0xFF000000.toInt(), 0xFFE91E63.toInt())) {
            val field = AtmosphereField.render(cover(argb, 0xFF202020.toInt()), 32, 32, 48)
            for (p in field) {
                val (l, a, b) = srgbToOklab(p)
                assertTrue(l <= AtmosphereField.CEILING + 0.01, "too bright: L $l for ${Integer.toHexString(argb)}")
                assertTrue(l >= AtmosphereField.FLOOR - 0.02, "below the floor: L $l")
                assertTrue(hypot(a, b) <= AtmosphereField.CHROMA_PER_L * AtmosphereField.CEILING + 0.01, "too vivid")
                assertEquals(0xFF, p ushr 24, "opaque")
            }
        }
    }

    @Test
    fun `the atmosphere is defocused past recognition`() {
        // A hard black and white split becomes a soft ramp: neighbours never jump.
        val split = IntArray(32 * 32) { if (it % 32 < 16) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        val field = AtmosphereField.render(split, 32, 32, 96)
        for (y in 0 until 96) for (x in 1 until 96) {
            val step = abs(((field[y * 96 + x] shr 8) and 0xFF) - ((field[y * 96 + x - 1] shr 8) and 0xFF))
            assertTrue(step <= 3, "a hard edge survived at $x,$y ($step)")
        }
    }

    @Test
    fun `the Wheel's reflection rests still and turns gently with the tilt`() {
        assertEquals(0f, reflectionTurn(null))
        assertEquals(0f, reflectionTurn(Offset.Zero), 0.001f)
        val right = reflectionTurn(Offset(1f, 0f))
        val left = reflectionTurn(Offset(-1f, 0f))
        assertTrue(right * left < 0, "tilting the other way turns it the other way")
        for (x in -10..10) for (y in -10..10) {
            assertTrue(abs(reflectionTurn(Offset(x / 10f, y / 10f))) <= 50f, "never more than 50°")
        }
    }

    @Test
    fun `the boot reveals the menu only during the dive, steadily`() {
        assertEquals(0f, BootTimeline.revealAt(0f))
        assertEquals(0f, BootTimeline.revealAt(BootTimeline.DIVE_START - 1f))
        assertEquals(1f, BootTimeline.revealAt(BootTimeline.END), 0.001f)
        var last = 0f
        var t = BootTimeline.DIVE_START
        while (t <= BootTimeline.END) {
            val r = BootTimeline.revealAt(t)
            assertTrue(r >= last, "the reveal never goes back ($t)")
            last = r
            t += 10f
        }
    }
}
