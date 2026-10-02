package app.podium.core.interaction

import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * Haptic tokens (design-system.md §9) mapped to documented system constants. Always played through
 * [View.performHapticFeedback], which honours the system touch-feedback setting (D-07).
 */
class PodiumHaptics(private val view: View, var enabled: Boolean = true) {
    private var lastDetentAt = 0L

    /** Focus moved one item / album. Rate-limited so fast spins don't buzz. */
    fun detent() {
        val now = SystemClock.uptimeMillis()
        if (now - lastDetentAt < MIN_DETENT_INTERVAL_MS) return
        lastDetentAt = now
        perform(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK)
    }

    /** Volume or scrub step. */
    fun step() = perform(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK)

    /** Hit the start/end of a list, or volume 0/max. */
    fun boundary() = perform(
        if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE else HapticFeedbackConstants.CONTEXT_CLICK,
    )

    /** A wheel button went down. */
    fun press() = perform(HapticFeedbackConstants.VIRTUAL_KEY)

    fun longPress() = perform(HapticFeedbackConstants.LONG_PRESS)

    fun confirm() = perform(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)

    fun reject() = perform(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)

    private fun perform(constant: Int) {
        if (enabled) view.performHapticFeedback(constant)
    }

    private companion object {
        const val MIN_DETENT_INTERVAL_MS = 16L
    }
}

@Composable
fun rememberPodiumHaptics(): PodiumHaptics {
    val view = LocalView.current
    return remember(view) { PodiumHaptics(view) }
}
