package app.podium.sources.api.aggregate

import app.podium.core.common.Outcome
import app.podium.core.common.PodiumError
import app.podium.core.model.SourceId
import app.podium.sources.api.HealthOutcome
import app.podium.sources.api.MusicSource
import app.podium.sources.api.SourceHealth
import app.podium.sources.api.SourceHealthMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** One source's answer to a question asked of several (D-35): a value, or why there's none. */
sealed interface SourceAnswer<out T> {
    val source: SourceId

    data class Answered<T>(override val source: SourceId, val value: T) : SourceAnswer<T>
    data class Failed(override val source: SourceId, val error: PodiumError) : SourceAnswer<Nothing>
    data class TimedOut(override val source: SourceId) : SourceAnswer<Nothing>
}

/**
 * Asks several sources the same question at once (D-35).
 *
 * - **Concurrent and bounded.** Each source has [timeoutMillis]. A slow, broken or uncooperative
 *   source never holds up the others or the caller: the calls run in [work], detached from the
 *   caller, so a source stuck in blocking I/O is abandoned (and cancelled) rather than waited for.
 * - **Cancellable.** Cancelling the collector cancels every outstanding call; answers to a stale
 *   question are never delivered.
 * - **Health-aware.** Sources whose breaker is open, that are rate-limited or rejected are skipped —
 *   unless every candidate's breaker is open, when they're asked anyway rather than leave the
 *   listener with nothing. Every answer is recorded: an answer or a miss is healthy (a miss means
 *   "works, doesn't have it"); network and server failures and timeouts count; being offline
 *   counts against no source.
 */
class SourceFanOut(
    private val health: SourceHealthMonitor,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val work: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    /** Emits each source's answer as it arrives (not in priority order). */
    fun <T> ask(sources: List<MusicSource>, call: suspend (MusicSource) -> Outcome<T>): Flow<SourceAnswer<T>> = channelFlow {
        for (source in admitted(sources)) {
            val id = source.descriptor.id
            val pending = work.async { call(source) }
            launch {
                try {
                    val answer: SourceAnswer<T> = try {
                        when (val outcome = withTimeoutOrNull(timeoutMillis) { pending.await() }) {
                            null -> SourceAnswer.TimedOut(id)
                            is Outcome.Success -> SourceAnswer.Answered(id, outcome.value)
                            is Outcome.Failure -> SourceAnswer.Failed(id, outcome.error)
                        }
                    } catch (e: CancellationException) {
                        if (!isActive) throw e // the question was withdrawn
                        SourceAnswer.Failed(id, PodiumError.Unexpected("cancelled"))
                    } catch (t: Throwable) {
                        SourceAnswer.Failed(id, PodiumError.Unexpected(t.javaClass.simpleName))
                    }
                    record(source, answer)
                    send(answer)
                } finally {
                    pending.cancel()
                }
            }
        }
    }

    /** Every admitted source's answer, in the order [sources] were given (priority order). */
    suspend fun <T> askAll(sources: List<MusicSource>, call: suspend (MusicSource) -> Outcome<T>): List<SourceAnswer<T>> {
        val answers = ask(sources, call).toList().associateBy { it.source }
        return sources.mapNotNull { answers[it.descriptor.id] }
    }

    /** One source, with the same timeout and health rules. */
    suspend fun <T> askOne(source: MusicSource, call: suspend (MusicSource) -> Outcome<T>): Outcome<T> =
        when (val answer = askAll(listOf(source), call).firstOrNull()) {
            is SourceAnswer.Answered -> Outcome.Success(answer.value)
            is SourceAnswer.Failed -> Outcome.Failure(answer.error)
            is SourceAnswer.TimedOut -> Outcome.Failure(PodiumError.Network("Timed out"))
            null -> Outcome.Failure(unavailable(source.descriptor.id))
        }

    /** Why a source wasn't asked: what the listener should be told instead of an answer. */
    fun unavailable(id: SourceId): PodiumError = when (val h = health.health(id)) {
        is SourceHealth.RateLimited -> PodiumError.RateLimited()
        is SourceHealth.AuthRejected -> PodiumError.AuthRequired(h.detail)
        is SourceHealth.Disabled -> PodiumError.PolicyDisabled()
        else -> PodiumError.Network("Not reachable right now")
    }

    /** The sources worth asking now, by health (see the class comment). */
    fun admitted(sources: List<MusicSource>): List<MusicSource> {
        val open = sources.filter { health.canAttempt(it.descriptor.id) }
        if (open.isNotEmpty()) return open
        // Every breaker open: ask anyway (a breaker saves time, it must never blank the screen).
        return sources.filter { health.health(it.descriptor.id) is SourceHealth.Unreachable }
    }

    private fun record(source: MusicSource, answer: SourceAnswer<*>) {
        val id = source.descriptor.id
        when (answer) {
            is SourceAnswer.Answered -> health.record(id, HealthOutcome.SUCCESS)
            is SourceAnswer.TimedOut -> health.record(id, HealthOutcome.NETWORK_FAILURE)
            is SourceAnswer.Failed -> outcomeOf(answer.error, signsIn = source.auth != null)
                ?.let { health.record(id, it, (answer.error as? PodiumError.RateLimited)?.retryAfterMillis) }
        }
    }

    companion object {
        /** Long enough for a slow mobile network, short enough that nobody waits on a dead source. */
        const val DEFAULT_TIMEOUT_MILLIS = 8_000L

        /**
         * What a failed call says about the source itself. Null: nothing (the phone is offline, or
         * Podium's own policy). "Not authorised" only means rejected credentials for a source that
         * signs in; for one that doesn't there's nothing to re-enter, so it's a server problem the
         * breaker recovers from.
         */
        fun outcomeOf(error: PodiumError, signsIn: Boolean): HealthOutcome? = when (error) {
            PodiumError.Offline -> null
            is PodiumError.PolicyDisabled -> null
            is PodiumError.NotFound -> HealthOutcome.MISS
            is PodiumError.Network -> HealthOutcome.NETWORK_FAILURE
            is PodiumError.Server -> HealthOutcome.SERVER_ERROR
            is PodiumError.RateLimited -> HealthOutcome.RATE_LIMIT
            is PodiumError.AuthRequired -> if (signsIn) HealthOutcome.AUTH_FAILURE else HealthOutcome.SERVER_ERROR
            is PodiumError.InvalidMedia, is PodiumError.UnsupportedFormat -> HealthOutcome.INVALID_MEDIA
            else -> HealthOutcome.UNKNOWN
        }
    }
}
