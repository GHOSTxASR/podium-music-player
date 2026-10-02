package app.podium.core.interaction

import kotlin.test.Test
import kotlin.test.assertEquals

class WheelAccelerationTest {

    @Test
    fun `short lists never skip items, however fast the spin`() {
        // Found on device: in a 10-song list a quick spin jumped the highlight past songs.
        listOf(0f, 10f, 30f, 100f).forEach { assertEquals(1, WheelAcceleration.multiplier(it, itemCount = 10)) }
        assertEquals(1, WheelAcceleration.multiplier(100f, itemCount = WheelAcceleration.MIN_ITEMS - 1))
    }

    @Test
    fun `long lists accelerate only beyond a deliberate scroll`() {
        val n = 500
        assertEquals(1, WheelAcceleration.multiplier(0f, n))
        assertEquals(1, WheelAcceleration.multiplier(15f, n)) // brisk but deliberate: still one item per click
        assertEquals(2, WheelAcceleration.multiplier(22f, n))
        assertEquals(4, WheelAcceleration.multiplier(36f, n))
        assertEquals(4, WheelAcceleration.multiplier(200f, n)) // capped
    }
}
