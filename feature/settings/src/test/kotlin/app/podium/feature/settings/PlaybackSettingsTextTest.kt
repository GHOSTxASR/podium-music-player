package app.podium.feature.settings

import org.junit.Test
import kotlin.test.assertEquals

/** How the equaliser's bands and gains, and crossfade lengths, read in Settings (D-76, D-77). */
class PlaybackSettingsTextTest {
    @Test
    fun `bands, gains and crossfade lengths read plainly`() {
        assertEquals("31 Hz", bandLabel(0))
        assertEquals("1 kHz", bandLabel(5))
        assertEquals("16 kHz", bandLabel(9))
        assertEquals("+3 dB", gainLabel(3f))
        assertEquals("0 dB", gainLabel(0f))
        assertEquals("−4 dB", gainLabel(-4f))
        assertEquals("6 seconds", crossfadeLabel(6))
    }
}
