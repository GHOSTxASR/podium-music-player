package app.podium.core.interaction

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** Tunable feel parameters (interaction-model.md §3). Values are starting points for device tuning. */
data class WheelTuning(
    val detentDegrees: Float = 15f,
    val rotationSlopDegrees: Float = 8f,
    val reverseHysteresisDegrees: Float = 7.5f,
    /** Acceleration tiers: (minimum detents/s, multiplier). Applied only when the context allows. */
    val accelerationTiers: List<Pair<Float, Int>> = listOf(30f to 8, 16f to 4, 8f to 2),
    val velocityWindowMillis: Long = 120,
)

/**
 * Pure state machine turning pointer positions on the Wheel into [PodiumInput]s.
 *
 * A touch that starts on the ring and travels more than the slop angle becomes a rotation (no button
 * fires); otherwise its release presses the zone it started on. The center only presses.
 * Long-press is driven by the caller's timer via [onLongPressTimeout].
 */
class WheelGestureTracker(private val tuning: WheelTuning = WheelTuning()) {

    enum class Region { RING, CENTER, OUTSIDE }

    private var region = Region.OUTSIDE
    private var startZone: WheelButton? = null
    private var lastAngle = 0.0
    private var travelled = 0.0
    private var accumulator = 0.0
    private var rotating = false
    private var longPressed = false
    private var lastDirection = 0
    private val detentTimes = ArrayDeque<Long>()

    /** True once the current touch has become a rotation. */
    val isRotating: Boolean get() = rotating

    /** The button visually pressed right now (null while rotating or idle). */
    val pressedButton: WheelButton? get() = if (rotating) null else startZone

    /**
     * @param x,y pointer position; [cx],[cy] wheel center; [innerRadius] center-button radius;
     *   [outerRadius] wheel radius. All in the same units.
     * @return the button under the finger, or null if the touch isn't on the wheel.
     */
    fun onDown(x: Float, y: Float, cx: Float, cy: Float, innerRadius: Float, outerRadius: Float): WheelButton? {
        reset()
        val r = hypot((x - cx).toDouble(), (y - cy).toDouble())
        region = when {
            r <= innerRadius -> Region.CENTER
            r <= outerRadius -> Region.RING
            else -> Region.OUTSIDE
        }
        if (region == Region.OUTSIDE) return null
        lastAngle = angleOf(x, y, cx, cy)
        startZone = if (region == Region.CENTER) WheelButton.CENTER else zoneAt(lastAngle)
        return startZone
    }

    /** Pointer moved. Returns any rotation input produced (at most one coalesced Rotate). */
    fun onMove(x: Float, y: Float, cx: Float, cy: Float, timeMillis: Long, accelerate: Boolean): PodiumInput.Rotate? {
        if (region != Region.RING || longPressed) return null
        val angle = angleOf(x, y, cx, cy)
        var delta = angle - lastAngle
        if (delta > 180) delta -= 360
        if (delta < -180) delta += 360
        lastAngle = angle
        travelled += delta
        if (!rotating) {
            if (abs(travelled) < tuning.rotationSlopDegrees) return null
            rotating = true
            accumulator = travelled
        } else {
            accumulator += delta
        }
        // Direction changes need half a detent before counting, so a wobbling finger doesn't jitter.
        val direction = if (accumulator > 0) 1 else -1
        if (lastDirection != 0 && direction != lastDirection && abs(accumulator) < tuning.reverseHysteresisDegrees) return null

        var detents = 0
        while (abs(accumulator) >= tuning.detentDegrees) {
            val step = if (accumulator > 0) 1 else -1
            detents += step
            accumulator -= step * tuning.detentDegrees
        }
        if (detents == 0) return null
        lastDirection = if (detents > 0) 1 else -1
        repeat(abs(detents)) { detentTimes.addLast(timeMillis) }
        while (detentTimes.isNotEmpty() && timeMillis - detentTimes.first() > tuning.velocityWindowMillis) detentTimes.removeFirst()
        val velocity = detentVelocity()
        val multiplier = if (accelerate) tuning.accelerationTiers.firstOrNull { velocity >= it.first }?.second ?: 1 else 1
        return PodiumInput.Rotate(detents * multiplier, velocity)
    }

    /** Pointer lifted. Returns the press for a tap, or the release that ends a long press. */
    fun onUp(): PodiumInput? {
        val zone = startZone
        val result = when {
            zone == null -> null
            longPressed -> PodiumInput.Release(zone)
            rotating -> null
            else -> PodiumInput.Press(zone)
        }
        reset()
        return result
    }

    /** The long-press timeout elapsed while the pointer is still down. */
    fun onLongPressTimeout(): PodiumInput.LongPress? {
        val zone = startZone ?: return null
        if (rotating || longPressed) return null
        longPressed = true
        return PodiumInput.LongPress(zone)
    }

    fun onCancel() = reset()

    /**
     * Detents per second across the recent window, measured over the time the detents actually
     * span. A lone detent has no measurable rate (0), so slow, deliberate clicks never accelerate.
     */
    private fun detentVelocity(): Float {
        if (detentTimes.size < 2) return 0f
        val spanMillis = (detentTimes.last() - detentTimes.first()).coerceAtLeast(MIN_SPAN_MILLIS)
        return (detentTimes.size - 1) * 1000f / spanMillis
    }

    private fun reset() {
        region = Region.OUTSIDE
        startZone = null
        travelled = 0.0
        accumulator = 0.0
        rotating = false
        longPressed = false
        lastDirection = 0
        detentTimes.clear()
    }

    companion object {
        /** About one 60 Hz frame: detents reported in the same event count as arriving this far apart. */
        private const val MIN_SPAN_MILLIS = 16L

        /** Clockwise angle from 12 o'clock, 0–360. Screen y grows downwards. */
        fun angleOf(x: Float, y: Float, cx: Float, cy: Float): Double {
            val radians = atan2((x - cx).toDouble(), (cy - y).toDouble())
            val degrees = radians * 180 / PI
            return if (degrees < 0) degrees + 360 else degrees
        }

        /** MENU at the top, NEXT right, PLAY_PAUSE bottom, PREVIOUS left — each a 90° sector. */
        fun zoneAt(angle: Double): WheelButton = when {
            angle >= 315 || angle < 45 -> WheelButton.MENU
            angle < 135 -> WheelButton.NEXT
            angle < 225 -> WheelButton.PLAY_PAUSE
            else -> WheelButton.PREVIOUS
        }
    }
}
