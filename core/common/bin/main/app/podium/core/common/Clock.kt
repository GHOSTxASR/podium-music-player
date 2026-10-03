package app.podium.core.common

/** Injectable time source so time-based logic (circuit breakers, expiry) is deterministic in tests. */
fun interface Clock {
    fun nowMillis(): Long

    companion object {
        val System: Clock = Clock { java.lang.System.currentTimeMillis() }
    }
}

/** A clock tests can advance manually. */
class ManualClock(private var now: Long = 0L) : Clock {
    override fun nowMillis(): Long = now

    fun advanceBy(millis: Long) {
        require(millis >= 0)
        now += millis
    }

    fun set(millis: Long) {
        now = millis
    }
}
