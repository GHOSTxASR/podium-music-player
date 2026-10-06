package app.podium.core.designsystem.theme

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.podium.core.designsystem.shell.DeviceAppearance
import app.podium.core.designsystem.shell.FinishPreset
import app.podium.core.designsystem.shell.Glitter
import app.podium.core.designsystem.shell.GlitterField
import app.podium.core.designsystem.shell.palette
import app.podium.core.designsystem.type.DefaultType
import app.podium.core.designsystem.type.DisplayFont
import app.podium.core.designsystem.type.Inter
import app.podium.core.designsystem.type.TypographyPreset
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The display's customization (D-41): readable whatever is chosen, and the body's glitter. */
@RunWith(AndroidJUnit4::class)
class VirtualDisplayTest {

    private fun contrastOf(a: Color, b: Color) = DisplayColors.contrast(a.luminance(), b.luminance())

    @Test
    fun `a custom display reads on any colour the listener can choose`() {
        // A sweep of the whole sRGB cube: primary and secondary text clear 4.5:1, tertiary 3:1.
        for (r in 0..255 step 17) for (g in 0..255 step 17) for (b in 0..255 step 17) {
            val argb = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            val colors = DisplayColors.colors(DisplayTheme.CUSTOM, darkSystem = true, VirtualDisplay(solidArgb = argb), image = null)
            assertTrue(contrastOf(colors.labelPrimary, colors.canvas) >= 4.5f, "primary on #%06X".format(argb and 0xFFFFFF))
            assertTrue(contrastOf(colors.labelSecondary, colors.canvas) >= 4.5f, "secondary on #%06X".format(argb and 0xFFFFFF))
            assertTrue(contrastOf(colors.labelTertiary, colors.canvas) >= 3f, "tertiary on #%06X".format(argb and 0xFFFFFF))
            assertTrue(colors.isIndustrial, "Custom is the matte instrument")
        }
    }

    @Test
    fun `light backgrounds take dark ink and dark ones light ink`() {
        val paper = DisplayColors.colors(DisplayTheme.CUSTOM, true, VirtualDisplay(solidArgb = 0xFFF2E6C8.toInt()), null)
        val slate = DisplayColors.colors(DisplayTheme.CUSTOM, true, VirtualDisplay(solidArgb = 0xFF22313A.toInt()), null)
        assertTrue(paper.labelPrimary.luminance() < 0.1f && !paper.isDark)
        assertTrue(slate.labelPrimary.luminance() > 0.8f && slate.isDark)
        // Glass on a solid colour picks light or dark glass the same way.
        val glass = DisplayColors.colors(DisplayTheme.GLASS, true, VirtualDisplay(background = DisplayBackground.SOLID, solidArgb = 0xFFF2E6C8.toInt()), null)
        assertEquals(LightColors.labelPrimary, glass.labelPrimary)
        assertEquals(Color(0xFFF2E6C8), glass.canvas)
    }

    @Test
    fun `carbon and bone keep their own display whatever background was chosen`() {
        val chosen = VirtualDisplay(background = DisplayBackground.SOLID, solidArgb = 0xFFFF0000.toInt())
        assertEquals(CarbonColors, DisplayColors.colors(DisplayTheme.CARBON, true, chosen, null))
        assertEquals(BoneColors, DisplayColors.colors(DisplayTheme.BONE, true, chosen, null))
        assertNull(DisplayColors.surface(DisplayTheme.CARBON, chosen, null))
        // And Glass with no background is exactly as before.
        assertEquals(DarkColors, DisplayColors.colors(DisplayTheme.GLASS, true, VirtualDisplay(), null))
        assertEquals(LightColors, DisplayColors.colors(DisplayTheme.GLASS, false, VirtualDisplay(), null))
    }

    @Test
    fun `a picture shows over its colour at the chosen opacity, and the ink follows what is drawn`() {
        val white = image(0xFFFFFFFF.toInt())
        val display = VirtualDisplay(background = DisplayBackground.IMAGE, solidArgb = 0xFF101010.toInt(), imageUri = "content://x", imageOpacity = 0.8f)
        val surface = DisplayColors.surface(DisplayTheme.CUSTOM, display, white)!!
        assertEquals(0.8f, surface.imageAlpha)
        // A bright picture at 80 % over near-black: what's drawn is light, so the ink is dark.
        assertTrue(DisplayColors.colors(DisplayTheme.CUSTOM, true, display, white).labelPrimary.luminance() < 0.1f)
        // High contrast lets the picture step back.
        val high = DisplayColors.surface(DisplayTheme.CUSTOM, display.copy(contrast = TextContrast.HIGH), white)!!
        assertEquals(0.8f * DisplayColors.HIGH_CONTRAST_IMAGE_FACTOR, high.imageAlpha, 0.0001f)
    }

    @Test
    fun `a picture that can no longer be read falls back to the solid colour`() {
        val display = VirtualDisplay(background = DisplayBackground.IMAGE, solidArgb = 0xFF22313A.toInt(), imageUri = "content://gone")
        val surface = DisplayColors.surface(DisplayTheme.GLASS, display, image = null)!!
        assertNull(surface.image)
        assertEquals(Color(0xFF22313A), surface.solid)
    }

    @Test
    fun `high contrast brings secondary text forward`() {
        val standard = DisplayColors.colors(DisplayTheme.BONE, true, VirtualDisplay(), null)
        val high = DisplayColors.colors(DisplayTheme.BONE, true, VirtualDisplay(contrast = TextContrast.HIGH), null)
        assertTrue(contrastOf(high.labelSecondary, high.canvas) > contrastOf(standard.labelSecondary, standard.canvas))
        assertEquals(standard.labelPrimary, high.labelPrimary)
    }

    @Test
    fun `every display font sets the whole scale, and strings it can't set fall back to Inter`() {
        assertSame(DefaultType, TypographyPreset.of(DisplayFont.CLASSIC).type)
        for (font in DisplayFont.entries) {
            val preset = TypographyPreset.of(font)
            // The Wheel's legend is on the body: always Classic.
            assertEquals(DefaultType.wheelLegend, preset.type.wheelLegend)
            assertNotEquals(FontFamily.Default, preset.familyFor("Coastline"))
        }
        // Space Grotesk has no Cyrillic, Pixelify Sans no Greek: whole strings go to Inter.
        assertEquals(Inter, TypographyPreset.of(DisplayFont.INDUSTRIAL).familyFor("Кино"))
        assertEquals(Inter, TypographyPreset.of(DisplayFont.PIXEL).familyFor("Ωmega"))
        assertNotEquals(Inter, TypographyPreset.of(DisplayFont.MONO).familyFor("Björk"))
        // Mono is set a little smaller, so rows keep their rhythm.
        assertTrue(TypographyPreset.of(DisplayFont.MONO).type.row.fontSize.value < DefaultType.row.fontSize.value)
    }

    @Test
    fun `glitter is off by default and seeded, so the body never changes between launches`() {
        assertFalse(DeviceAppearance().glitter.enabled)
        assertFalse(DeviceAppearance(FinishPreset.STEEL_GRAY).palette(true).glitter.enabled)
        val a = GlitterField.flakes(256, 0.5f, 0.4f, 2f)
        val b = GlitterField.flakes(256, 0.5f, 0.4f, 2f)
        assertEquals(a, b)
        assertTrue(GlitterField.flakes(256, 0.9f, 0.4f, 2f).size > GlitterField.flakes(256, 0.2f, 0.4f, 2f).size)
        // About the chosen share of flakes catches the light.
        val many = GlitterField.flakes(512, 1f, 0.3f, 2f)
        val share = many.count { it.lit } / many.size.toFloat()
        assertEquals(0.3f, share, 0.03f)
        // Flakes inherit the body: a light body's glitter has darker specks than its own colour.
        val silver = DeviceAppearance(FinishPreset.SILVER, glitter = Glitter(enabled = true)).palette(true)
        assertTrue(silver.glitterDark.luminance() < silver.bodyTop.luminance())
    }

    private fun image(argb: Int): DisplayImage {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(argb) }
        return DisplayImage(bitmap.asImageBitmap(), Color(argb).luminance())
    }
}
