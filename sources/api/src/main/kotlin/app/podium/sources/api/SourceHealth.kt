package app.podium.sources.api

import app.podium.core.common.Clock
import app.podium.core.model.SourceId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The classification of one interaction with a source. */
enum class HealthOutcome {
    SUCCESS,

    /** The source answered but doesn't have / won't serve this item. Never damages health. */
    MISS,
    NETWORK_FAILURE,
    AUTH_FAILURE,
    RATE_LIMIT,
    SERVER_ERROR,

    /** The media itself was bad (corrupt, unsupported). Per-item, not an infrastructure problem. */
    INVALID_MEDIA,
    POLICY_DISABLED,
    UNKNOWN;

    /** Failures that indicate the source's infrastructure may be down. */
    val isInfrastructureFailure: Boolean
        get() = this == NETWORK_FAILURE || this == SERVER_ERROR || this == UNKNOWN
}

sealed interface SourceHealth {
    val isAttemptAllowed: Boolean

    data object Healthy : SourceHealth {
        override val isAttemptAllowed = true
    }

    /** Some recent infrastructure failures, below the breaker threshold. */
    data class Degraded(val consecutiveFailures: Int) : SourceHealth {
        override val isAttemptAllowed = true
    }

    /** Circuit open: skip this source until [retryAtMillis]; then one probe attempt is allowed. */
    data class Unreachable(val retryAtMillis: Long, val openCount: Int) : SourceHealth {
        override val isAttemptAllowed = false
    }

    /** One probe attempt is allowed after an open circuit expires. */
    data class Probing(val openCount: Int) : SourceHealth {
        override val isAttemptAllowed = true
    }

    /** Credentials refused. No retries until the user acts (re-sign-in clears it). */
    data class AuthRejected(val detail: String?) : SourceHealth {
        override val isAttemptAllowed = false
    }

    data class RateLimited(val untilMillis: Long) : SourceHealth {
        override val isAttemptAllowed = false
    }

    data object Disabled : SourceHealth {
        override val isAttemptAllowed = false
    }
}

data class HealthPolicy(
    val failureThreshold: Int = 3,
    /** Open-circuit durations for the 1st, 2nd, 3rd+ consecutive openings. */
    val backoffMillis: List<Long> = listOf(30_000L, 120_000L, 600_000L),
    val defaultRateLimitMillis: Long = 60_000L,
)

/**
 * Per-source health with a circuit breaker (MUSIC_SOURCE_ARCHITECTURE §7).
 * - A MISS proves the source is reachable: it clears the failure streak and never counts against it.
 * - INVALID_MEDIA is per-item and does not affect the breaker.
 * - N consecutive infrastructure failures open the circuit with escalating backoff; after it expires
 *   a single probe is allowed (half-open); success closes it, failure re-opens it longer.
 */
class SourceHealthMonitor(
    private val clock: Clock,
    private val policy: HealthPolicy = HealthPolicy(),
) {
    private data class Entry(
        val consecutiveFailures: Int = 0,
        val openCount: Int = 0,
        val state: SourceHealth = SourceHealth.Healthy,
    )

    private val entries = MutableStateFlow<Map<SourceId, Entry>>(emptyMap())

    private val _states = MutableStateFlow<Map<SourceId, SourceHealth>>(emptyMap())
    val states: StateFlow<Map<SourceId, SourceHealth>> = _states.asStateFlow()

    /** Current health, applying time-based transitions (open → probing, rate limit expiry). */
    fun health(source: SourceId): SourceHealth {
        val entry = entries.value[source] ?: return SourceHealth.Healthy
        val now = clock.nowMillis()
        return when (val s = entry.state) {
            is SourceHealth.Unreachable -> if (now >= s.retryAtMillis) SourceHealth.Probing(s.openCount) else s
            is SourceHealth.RateLimited -> if (now >= s.untilMillis) SourceHealth.Healthy else s
            else -> s
        }
    }

    fun canAttempt(source: SourceId): Boolean = health(source).isAttemptAllowed

    fun record(source: SourceId, outcome: HealthOutcome, retryAfterMillis: Long? = null) {
        val now = clock.nowMillis()
        entries.update { all ->
            val current = all[source] ?: Entry()
            val wasProbing = health(source) is SourceHealth.Probing
            val next = when {
                outcome == HealthOutcome.SUCCESS || outcome == HealthOutcome.MISS ->
                    Entry(consecutiveFailures = 0, openCount = 0, state = SourceHealth.Healthy)

                outcome == HealthOutcome.INVALID_MEDIA -> current.copy(state = health(source))

                outcome == HealthOutcome.AUTH_FAILURE ->
                    current.copy(state = SourceHealth.AuthRejected(null))

                outcome == HealthOutcome.RATE_LIMIT ->
                    current.copy(
                        state = SourceHealth.RateLimited(now + (retryAfterMillis ?: policy.defaultRateLimitMillis)),
                    )

                outcome == HealthOutcome.POLICY_DISABLED -> current.copy(state = SourceHealth.Disabled)

                outcome.isInfrastructureFailure -> {
                    val failures = current.consecutiveFailures + 1
                    if (wasProbing || failures >= policy.failureThreshold) {
                        val opens = current.openCount + 1
                        val backoff = policy.backoffMillis[(opens - 1).coerceAtMost(policy.backoffMillis.lastIndex)]
                        Entry(failures, opens, SourceHealth.Unreachable(now + backoff, opens))
                    } else {
                        current.copy(consecutiveFailures = failures, state = SourceHealth.Degraded(failures))
                    }
                }

                else -> current
            }
            all + (source to next)
        }
        publish()
    }

    /** User re-authenticated or re-enabled the source. */
    fun reset(source: SourceId) {
        entries.update { it - source }
        publish()
    }

    private fun publish() {
        _states.value = entries.value.keys.associateWith { health(it) }
    }
}
