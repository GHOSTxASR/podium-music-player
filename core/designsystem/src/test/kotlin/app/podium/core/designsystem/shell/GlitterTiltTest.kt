package app.podium.core.designsystem.shell

import android.view.Surface
import androidx.compose.ui.geometry.Offset
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The glitter's light follows the phone's tilt (D-44). Readings are gravity in screen axes. */
class GlitterTiltTest {

    private val g = 9.81f

    /** A reading for a phone held at [pitchDeg] back from upright and rolled [rollDeg] (right edge down). */
    private fun reading(pitchDeg: Double, rollDeg: Double): Triple<Float, Float, Float> {
        val p = Math.toRadians(pitchDeg)
        val r = Math.toRadians(rollDeg)
        // Upright reads +y; leaning back moves gravity into +z; rolling right moves it into -x.
        val x = (-sin(r) * cos(p)).toFloat() * g
        val y = (cos(r) * cos(p)).toFloat() * g
        val z = (sin(p)).toFloat() * g
        return Triple(x, y, z)
    }

    private fun TiltFilter.hold(pitchDeg: Double, rollDeg: Double, seconds: Double, start: Long = 0L): Offset {
        val (x, y, z) = reading(pitchDeg, rollDeg)
        var t = start
        var last = Offset.Zero
        repeat((seconds * 50).toInt()) {
            t += 20_000_000L
            last = update(x, y, z, t)
        }
        return last
    }

    @Test
    fun `held level, the light sits in the middle`() {
        val o = TiltFilter().hold(pitchDeg = 40.0, rollDeg = 0.0, seconds = 1.0)
        assertEquals(0f, o.x, 0.01f)
        assertEquals(0f, o.y, 0.01f)
    }

    @Test
    fun `tilting right moves the light left, tilting left moves it right`() {
        val right = TiltFilter().apply { hold(40.0, 0.0, 1.0) }.hold(40.0, 12.0, 1.0, start = 1_000_000_000L)
        assertTrue(right.x < -0.2f, "right tilt: $right")
        val left = TiltFilter().apply { hold(40.0, 0.0, 1.0) }.hold(40.0, -12.0, 1.0, start = 1_000_000_000L)
        assertTrue(left.x > 0.2f, "left tilt: $left")
        // About 27° of roll reaches the edge, and never beyond.
        assertEquals(-1f, TiltFilter().hold(40.0, 45.0, 1.0).x, 0.001f)
    }

    @Test
    fun `tilting the top away moves the light down, and holding still brings it back`() {
        val filter = TiltFilter()
        filter.hold(35.0, 0.0, 2.0)
        val away = filter.hold(60.0, 0.0, 0.5, start = 2_000_000_000L)
        assertTrue(away.y > 0.2f, "top away: $away")
        // However the phone is held, holding it there becomes "straight" within seconds.
        val settled = filter.hold(60.0, 0.0, 20.0, start = 2_500_000_000L)
        assertEquals(0f, settled.y, 0.05f)
    }

    @Test
    fun `accelerometer readings are smoothed more than gravity readings`() {
        val raw = TiltFilter(raw = true).apply { hold(40.0, 0.0, 1.0) }
        val gravity = TiltFilter().apply { hold(40.0, 0.0, 1.0) }
        val (x, y, z) = reading(40.0, 15.0)
        val oneRaw = raw.update(x, y, z, 1_020_000_000L)
        val oneGravity = gravity.update(x, y, z, 1_020_000_000L)
        assertTrue(kotlin.math.abs(oneRaw.x) < kotlin.math.abs(oneGravity.x))
    }

    @Test
    fun `device axes become screen axes in every rotation`() {
        assertEquals(1f to 2f, TiltFilter.toScreen(1f, 2f, Surface.ROTATION_0))
        assertEquals(-2f to 1f, TiltFilter.toScreen(1f, 2f, Surface.ROTATION_90))
        assertEquals(-1f to -2f, TiltFilter.toScreen(1f, 2f, Surface.ROTATION_180))
        assertEquals(2f to -1f, TiltFilter.toScreen(1f, 2f, Surface.ROTATION_270))
    }

    @Test
    fun `flakes facing different ways catch the light at different tilts`() {
        val level = (0 until GlitterField.FACINGS).map { GlitterField.catch(it, Offset.Zero) }
        val tilted = (0 until GlitterField.FACINGS).map { GlitterField.catch(it, Offset(-0.5f, 0f)) }
        assertTrue(level.all { it in 0f..1f } && tilted.all { it in 0f..1f })
        // Some group brightens while another fades: the glitter shifts, it doesn't just dim.
        val changes = level.zip(tilted) { a, b -> b - a }
        assertTrue(changes.any { it > 0.1f } && changes.any { it < -0.1f }, "$changes")
    }
}
