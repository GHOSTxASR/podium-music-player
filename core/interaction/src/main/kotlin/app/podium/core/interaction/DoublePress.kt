package app.podium.core.interaction

/**
 * Tells a double press of one button from two single ones (D-68). Each press is reported as it
 * happens, so the button's own action never waits: [press] returns true when this press comes
 * within [windowMs] of the previous one. A third quick press starts afresh, it isn't a second double.
 */
class DoublePress(private val windowMs: Long = WINDOW_MS) {
    private var last = NONE

    fun press(nowMs: Long): Boolean {
        val double = last != NONE && nowMs - last in 0..windowMs
        last = if (double) NONE else nowMs
        return double
    }

    companion object {
        /** Android's own double-tap timeout. */
        const val WINDOW_MS = 300L
        private const val NONE = Long.MIN_VALUE
    }
}
