package app.podium.core.common

/** Result of an operation that can fail with a classified [PodiumError]. */
sealed interface Outcome<out T> {
    data class Success<out T>(val value: T) : Outcome<T>
    data class Failure(val error: PodiumError) : Outcome<Nothing>

    companion object {
        fun <T> success(value: T): Outcome<T> = Success(value)
        fun failure(error: PodiumError): Outcome<Nothing> = Failure(error)
    }
}

inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Success -> Outcome.Success(transform(value))
    is Outcome.Failure -> this
}

fun <T> Outcome<T>.getOrNull(): T? = (this as? Outcome.Success)?.value

/**
 * Classified failures (architecture.md §9). Raw exception text never reaches the UI; each case maps
 * to user copy in one place.
 */
sealed interface PodiumError {
    data object Offline : PodiumError
    data class Network(val detail: String? = null) : PodiumError
    data class Server(val httpStatus: Int? = null) : PodiumError
    data class AuthRequired(val detail: String? = null) : PodiumError

    /** A sign-in that worked before and doesn't any more: the listener signs in again. */
    data class AuthExpired(val detail: String? = null) : PodiumError
    data class RateLimited(val retryAfterMillis: Long? = null) : PodiumError
    data class NotFound(val what: String) : PodiumError
    data class InvalidMedia(val detail: String? = null) : PodiumError
    data class UnsupportedFormat(val codec: String? = null) : PodiumError
    data class PolicyDisabled(val detail: String? = null) : PodiumError
    data class PermissionRequired(val permission: String) : PodiumError
    data class Unexpected(val detail: String? = null) : PodiumError

    /** The source doesn't offer this at all (not a failure, never retried). */
    data class Unsupported(val what: String) : PodiumError

    /** It exists, but can't be played here (region, rights, needs another app). */
    data class NotPlayable(val reason: String) : PodiumError

    /** Something only the listener can do: install an app, grant access, accept terms. */
    data class UserActionRequired(val action: String) : PodiumError
}

/**
 * Worth trying again later without the listener doing anything: the network, a busy or failing
 * server, a rate limit. Misses, refusals and things the source doesn't offer are permanent.
 */
val PodiumError.isTransient: Boolean
    get() = this is PodiumError.Offline || this is PodiumError.Network || this is PodiumError.Server ||
        this is PodiumError.RateLimited || this is PodiumError.Unexpected
