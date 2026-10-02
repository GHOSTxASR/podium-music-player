package app.podium.core.interaction

/** The Wheel's five buttons. */
enum class WheelButton { MENU, NEXT, PLAY_PAUSE, PREVIOUS, CENTER }

/**
 * Semantic input (interaction-model.md §2). The Wheel, keyboard, D-pad, rotary encoders and mouse
 * wheels all produce these; screens never see raw gestures.
 */
sealed interface PodiumInput {
    /** Positive = clockwise = "down"/"more". [velocity] in detents per second. */
    data class Rotate(val detents: Int, val velocity: Float = 0f) : PodiumInput
    data class Press(val button: WheelButton) : PodiumInput
    data class LongPress(val button: WheelButton) : PodiumInput
    data class Release(val button: WheelButton) : PodiumInput
}

/** What the wheel's rotation means on the current screen (interaction-model.md §4). */
enum class WheelContext { LIST_FOCUS, VOLUME, SCRUB, QUEUE, OVERLAY }
