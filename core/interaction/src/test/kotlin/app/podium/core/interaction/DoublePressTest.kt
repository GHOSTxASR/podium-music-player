package app.podium.core.interaction

import kotlin.test.Test
import kotlin.test.assertEquals

/** Double ⏯ opens Now Playing (D-68): only two presses close together count, once. */
class DoublePressTest {

    private fun DoublePress.pressesAt(vararg times: Long) = times.map { press(it) }

    @Test
    fun `two presses within the window are a double press`() {
        assertEquals(listOf(false, true), DoublePress().pressesAt(1_000, 1_000 + DoublePress.WINDOW_MS))
    }

    @Test
    fun `presses further apart are single presses`() {
        assertEquals(listOf(false, false, false), DoublePress().pressesAt(1_000, 1_301, 1_700))
    }

    @Test
    fun `a third quick press starts afresh`() {
        assertEquals(listOf(false, true, false, true), DoublePress().pressesAt(1_000, 1_150, 1_300, 1_450))
    }

    @Test
    fun `the first press of all is never a double`() {
        assertEquals(listOf(false), DoublePress().pressesAt(0))
    }
}
