package app.podium.core.interaction

/**
 * When fast spins move focus by more than one item per detent (interaction-model.md §3).
 * Only long lists accelerate — in a short list every item must be reachable one click at a
 * time — and only for spins well beyond a deliberate scroll (≈ 1 revolution per second).
 */
object WheelAcceleration {
    const val MIN_ITEMS = 50

    /** (minimum detents per second, multiplier), fastest first. */
    private val tiers = listOf(36f to 4, 22f to 2)

    fun multiplier(velocity: Float, itemCount: Int): Int =
        if (itemCount < MIN_ITEMS) 1 else tiers.firstOrNull { velocity >= it.first }?.second ?: 1
}
