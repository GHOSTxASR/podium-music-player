package app.podium.core.designsystem.shell

import androidx.compose.ui.graphics.toArgb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceAppearanceTest {

    @Test
    fun `hex codes parse with or without the hash and in any case`() {
        assertEquals(0xFF7A1F3D.toInt(), parseHex("#7A1F3D"))
        assertEquals(0xFF7A1F3D.toInt(), parseHex("7a1f3d"))
        assertEquals("#7A1F3D", formatHex(0xFF7A1F3D.toInt()))
    }

    @Test
    fun `anything but six hex digits is rejected`() {
        listOf("", "#", "7A1F3", "7A1F3D0", "GG0000", "#12 456").forEach { assertNull(parseHex(it), it) }
    }

    @Test
    fun `light finishes take dark legends and dark ones light legends`() {
        val silver = DeviceAppearance(FinishPreset.SILVER).palette(darkTheme = true)
        val burgundy = DeviceAppearance(FinishPreset.BURGUNDY).palette(darkTheme = true)
        assertTrue(silver.isLight)
        assertFalse(burgundy.isLight)
        assertTrue(luminance(silver.legend.toArgb()) < luminance(silver.ring.toArgb()))
        assertTrue(luminance(burgundy.legend.toArgb()) > luminance(burgundy.ring.toArgb()))
    }

    @Test
    fun `the body is lit from the top`() {
        DeviceAppearance.entriesForTest().forEach { appearance ->
            val p = appearance.palette(darkTheme = true)
            assertTrue(luminance(p.bodyTop.toArgb()) > luminance(p.bodyBottom.toArgb()), appearance.preset.name)
        }
    }

    @Test
    fun `glass has no solid colours and custom uses the custom colour`() {
        assertTrue(DeviceAppearance(FinishPreset.GLASS).palette(darkTheme = true).isGlass)
        assertNull(DeviceAppearance(FinishPreset.GLASS).baseArgb)
        assertEquals(0xFF123456.toInt(), DeviceAppearance(FinishPreset.CUSTOM, customArgb = 0xFF123456.toInt()).baseArgb)
    }

    @Test
    fun `turning the hue a full circle comes back to the same colour`() {
        // Found while testing on device: re-reading each clipped step drifted #2F6F5E to #3C725C.
        var walk = HueWalk.of(0xFF2F6F5E.toInt())
        repeat(60) { walk = walk.turned(6.0) }
        val argb = walk.argb
        val (r0, g0, b0) = rgb(0xFF2F6F5E.toInt())
        val (r1, g1, b1) = rgb(argb)
        assertTrue(kotlin.math.abs(r0 - r1) <= 2 && kotlin.math.abs(g0 - g1) <= 2 && kotlin.math.abs(b0 - b1) <= 2, formatHex(argb))
    }

    private fun DeviceAppearance.Companion.entriesForTest() =
        FinishPreset.entries.filter { it != FinishPreset.GLASS }.map { DeviceAppearance(it) }

    private fun rgb(argb: Int) = Triple((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)

    private fun luminance(argb: Int): Double = rgb(argb).let { (r, g, b) -> 0.2126 * r + 0.7152 * g + 0.0722 * b }
}
