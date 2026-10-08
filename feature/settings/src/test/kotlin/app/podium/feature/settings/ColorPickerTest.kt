package app.podium.feature.settings

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The colour picker's model (D-51): the whole palette by hue, plus saturation and brightness. */
class ColorPickerTest {

    @Test
    fun `colours survive the trip through hue, saturation and brightness`() {
        for (argb in listOf(0xFF2F6F5E.toInt(), 0xFF22313A.toInt(), 0xFFFF0000.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF7F7F7F.toInt())) {
            assertEquals(argb, Hsv.of(argb).argb, "%08X".format(argb))
        }
    }

    @Test
    fun `turning the hue goes all the way round, even from a greyish colour`() {
        var hsv = Hsv.of(0xFF22313A.toInt()) // the default slate: low saturation
        val seen = HashSet<Int>()
        repeat(90) {
            hsv = hsv.turned(ColorChannel.HUE, 1)
            seen += (hsv.hue / 60f).toInt()
        }
        assertEquals((0..5).toSet(), seen, "every sixth of the palette is passed through")
        assertTrue(kotlin.math.abs(hsv.hue - Hsv.of(0xFF22313A.toInt()).hue) < 0.5f, "a full turn comes back round")
    }

    @Test
    fun `saturation and brightness stop at their ends`() {
        val red = Hsv.of(0xFFFF0000.toInt())
        assertEquals(0xFF808080.toInt() and 0xFFFFFFFF.toInt(), red.with(ColorChannel.SATURATION, 0f).copy(brightness = 0.5f).argb)
        assertEquals(1f, red.turned(ColorChannel.SATURATION, 10).saturation)
        assertEquals(0f, red.turned(ColorChannel.BRIGHTNESS, -100).brightness)
        assertEquals(0xFF000000.toInt(), red.turned(ColorChannel.BRIGHTNESS, -100).argb)
    }
}
