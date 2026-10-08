package app.podium.core.interaction

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WheelGestureTrackerTest {
    private val cx = 500f
    private val cy = 500f
    private val inner = 100f
    private val outer = 300f
    private val ringRadius = 200f

    /** Point on the ring at [degrees] clockwise from 12 o'clock. */
    private fun at(degrees: Double): Pair<Float, Float> {
        val r = degrees * PI / 180
        return (cx + ringRadius * sin(r)).toFloat() to (cy - ringRadius * cos(r)).toFloat()
    }

    private fun WheelGestureTracker.down(deg: Double) = at(deg).let { (x, y) -> onDown(x, y, cx, cy, inner, outer) }

    /** Sweep from [from] to [to] degrees in 1° steps, [msPerDegree] apart. Returns total detents. */
    private fun WheelGestureTracker.sweep(from: Double, to: Double, msPerDegree: Long = 10): Int {
        var total = 0
        var t = 0L
        val step = if (to > from) 1.0 else -1.0
        var d = from
        while ((step > 0 && d < to) || (step < 0 && d > to)) {
            d += step
            t += msPerDegree
            val (x, y) = at(d)
            onMove(x, y, cx, cy, t)?.let { total += it.detents }
        }
        return total
    }

    @Test
    fun `zones are the four quadrants and the center`() {
        val tracker = WheelGestureTracker()
        assertEquals(WheelButton.MENU, tracker.down(0.0))
        assertEquals(WheelButton.NEXT, tracker.down(90.0))
        assertEquals(WheelButton.PLAY_PAUSE, tracker.down(180.0))
        assertEquals(WheelButton.PREVIOUS, tracker.down(270.0))
        assertEquals(WheelButton.CENTER, tracker.onDown(cx, cy, cx, cy, inner, outer))
        assertNull(tracker.onDown(0f, 0f, cx, cy, inner, outer))
    }

    @Test
    fun `the travelled angle follows the finger across 12 o'clock, both ways`() {
        val tracker = WheelGestureTracker()
        tracker.down(340.0)
        tracker.sweep(340.0, 395.0)
        assertEquals(55f, tracker.travelledDegrees, 0.01f)
        tracker.sweep(395.0, 365.0)
        assertEquals(25f, tracker.travelledDegrees, 0.01f)
        tracker.onUp()
        assertEquals(0f, tracker.travelledDegrees, "a new touch starts from nothing")
    }

    @Test
    fun `a tap presses the zone it started on`() {
        val tracker = WheelGestureTracker()
        tracker.down(90.0)
        assertEquals(PodiumInput.Press(WheelButton.NEXT), tracker.onUp())
    }

    @Test
    fun `small movement within the slop is still a tap`() {
        val tracker = WheelGestureTracker()
        tracker.down(90.0)
        assertEquals(0, tracker.sweep(90.0, 96.0))
        assertEquals(PodiumInput.Press(WheelButton.NEXT), tracker.onUp())
    }

    @Test
    fun `rotation emits one detent per 18 degrees and cancels the press`() {
        val tracker = WheelGestureTracker()
        tracker.down(0.0)
        val detents = tracker.sweep(0.0, 91.0) // just past the boundary: real fingers never land exactly on it
        assertEquals(5, detents)
        assertTrue(tracker.isRotating)
        assertNull(tracker.onUp(), "a rotation must never fire the button it started on")
    }

    @Test
    fun `counter-clockwise rotation is negative and crosses 12 o'clock cleanly`() {
        val tracker = WheelGestureTracker()
        tracker.down(30.0)
        assertEquals(-5, tracker.sweep(30.0, -61.0))
    }

    @Test
    fun `the tracker reports raw detents however fast the spin`() {
        // Acceleration belongs to the list (WheelAcceleration), which knows its length.
        assertEquals(20, WheelGestureTracker().apply { down(0.0) }.sweep(0.0, 361.0, msPerDegree = 1))
    }

    @Test
    fun `fast spins report a high velocity, slow clicks none`() {
        var fast = 0f
        WheelGestureTracker().apply {
            down(0.0)
            var t = 0L
            for (d in 1..180) {
                t += 1
                val (x, y) = at(d.toDouble())
                onMove(x, y, cx, cy, t)?.let { fast = it.velocity }
            }
        }
        assertTrue(fast > 36f, "a 1000°/s spin should read as fast, got $fast")
        var slow = -1f
        WheelGestureTracker().apply {
            down(0.0)
            val (x, y) = at(19.0)
            onMove(x, y, cx, cy, 100)?.let { slow = it.velocity }
        }
        assertEquals(0f, slow)
    }

    @Test
    fun `wobble at a direction change does not jitter`() {
        val tracker = WheelGestureTracker()
        tracker.down(0.0)
        assertEquals(2, tracker.sweep(0.0, 38.0))
        // Back by 5° then forward again: under the hysteresis, so no extra detents either way.
        assertEquals(0, tracker.sweep(38.0, 33.0))
        assertEquals(0, tracker.sweep(33.0, 38.0))
    }

    @Test
    fun `long press fires once and ends with a release`() {
        val tracker = WheelGestureTracker()
        tracker.down(270.0)
        assertEquals(PodiumInput.LongPress(WheelButton.PREVIOUS), tracker.onLongPressTimeout())
        assertNull(tracker.onLongPressTimeout())
        assertIs<PodiumInput.Release>(tracker.onUp())
    }

    @Test
    fun `no long press once rotating`() {
        val tracker = WheelGestureTracker()
        tracker.down(0.0)
        tracker.sweep(0.0, 45.0)
        assertNull(tracker.onLongPressTimeout())
    }

    @Test
    fun `the center never rotates`() {
        val tracker = WheelGestureTracker()
        tracker.onDown(cx, cy, cx, cy, inner, outer)
        assertNull(tracker.onMove(cx + 50, cy, cx, cy, 10))
        assertEquals(PodiumInput.Press(WheelButton.CENTER), tracker.onUp())
    }
}
