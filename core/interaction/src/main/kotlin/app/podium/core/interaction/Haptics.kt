package app.podium.core.interaction

import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/** The Wheel's audible click (D-32): a tick per detent, a firmer click per press. */
interface Clicker {
    fun tick()
    fun press()

    companion object {
        val Silent = object : Clicker {
            override fun tick() = Unit
            override fun press() = Unit
        }
    }
}

/** How the device answers touch, from Settings: haptics, and the click sound. */
@Immutable
data class Feedback(val haptics: Boolean = true, val clicker: Clicker = Clicker.Silent)

val LocalFeedback = compositionLocalOf { Feedback() }

/**
 * Haptic tokens (design-system.md §9) mapped to documented system constants, paired with the click
 * sound when it's on. Haptics always go through [View.performHapticFeedback], which honours the
 * system touch-feedback setting (D-07); the Settings switch can turn them off on top of that.
 */
class PodiumHaptics(private val view: View, private val feedback: Feedback = Feedback()) {
    private var lastDetentAt = 0L

    /** Focus moved one item / album. Rate-limited so fast spins don't buzz. */
    fun detent() {
        val now = SystemClock.uptimeMillis()
        if (now - lastDetentAt < MIN_DETENT_INTERVAL_MS) return
        lastDetentAt = now
        perform(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK)
        feedback.clicker.tick()
    }

    /** Volume or scrub step. */
    fun step() {
        perform(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK)
        feedback.clicker.tick()
    }

    /** Hit the start/end of a list, or volume 0/max. */
    fun boundary() = perform(
        if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE else HapticFeedbackConstants.CONTEXT_CLICK,
    )

    /** A wheel button went down. */
    fun press() {
        perform(HapticFeedbackConstants.VIRTUAL_KEY)
        feedback.clicker.press()
    }

    fun longPress() = perform(HapticFeedbackConstants.LONG_PRESS)

    fun confirm() = perform(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)

    fun reject() = perform(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)

    /** A light tick that isn't a Wheel detent (e.g. a line of the boot self-test landing). */
    fun tick() = perform(HapticFeedbackConstants.CLOCK_TICK)

    private fun perform(constant: Int) {
        if (feedback.haptics) view.performHapticFeedback(constant)
    }

    private companion object {
        const val MIN_DETENT_INTERVAL_MS = 16L
    }
}

@Composable
fun rememberPodiumHaptics(): PodiumHaptics {
    val view = LocalView.current
    val feedback = LocalFeedback.current
    return remember(view, feedback) { PodiumHaptics(view, feedback) }
}
