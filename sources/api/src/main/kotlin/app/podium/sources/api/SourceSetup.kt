package app.podium.sources.api

import app.podium.core.model.SourceId

/*
 * Signing in and keeping secrets (D-37). Configured, listener-added sources (O11) were retired with
 * the move to one online service (D-38); what remains is provider-neutral: the fields a sign-in can
 * ask for, the problems it can report, and the only place secrets may live.
 */

/** One thing a setup or sign-in form asks for. */
data class SetupField(
    val key: String,
    val label: String,
    val hint: String = "",
    val kind: Kind = Kind.TEXT,
) {
    enum class Kind {
        TEXT,
        URL,

        /** Never shown back, never logged, stored only in the [CredentialStore]. */
        SECRET,
    }
}

/** Why setting up or signing in didn't work — Settings words it; nothing provider-specific leaks. */
enum class SetupProblem {
    MISSING_FIELD,
    BAD_ADDRESS,

    /** Unencrypted (http) to a host that isn't on the listener's own network (D-09). */
    INSECURE_ADDRESS,

    /** Unencrypted (http) to a host on the listener's own network: allowed once they confirm (D-09). */
    NEEDS_CLEARTEXT_CONSENT,
    UNREACHABLE,
    WRONG_CREDENTIALS,
    NOT_SUPPORTED,
    UNKNOWN,
}

/**
 * Secrets for configured sources, by source (D-37). Implementations keep them encrypted at rest
 * (the app: Android Keystore); nothing else in Podium may hold them. Values are never logged.
 */
interface CredentialStore {
    fun read(source: SourceId): Map<String, String>?
    fun write(source: SourceId, values: Map<String, String>)
    fun delete(source: SourceId)
}

class InMemoryCredentialStore : CredentialStore {
    private val values = mutableMapOf<SourceId, Map<String, String>>()

    @Synchronized override fun read(source: SourceId) = values[source]

    @Synchronized override fun write(source: SourceId, values: Map<String, String>) {
        this.values[source] = values.toMap()
    }

    @Synchronized override fun delete(source: SourceId) {
        values.remove(source)
    }
}
