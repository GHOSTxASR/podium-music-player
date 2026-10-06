package app.podium.sources.api

import app.podium.core.model.SourceId
import java.util.UUID

/*
 * Sources the listener adds — a music server, an account — rather than ones every build has (D-37).
 * Provider-neutral: a source kind describes its own setup form; Settings renders it without knowing
 * what it is; the kind's factory checks the answers and builds the source. What a configured source
 * is (its profile) is kept in an ordinary store; its secrets only ever in a [CredentialStore].
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

/** A kind of source the listener can add, as Settings offers it ("Add a music server"). */
data class SetupForm(val kind: String, val title: String, val fields: List<SetupField>, val submitLabel: String = "Connect") {
    companion object {
        /** Added to the values when the listener confirmed an unencrypted address on their own network (D-09). */
        const val ALLOW_CLEARTEXT = "podium.allowCleartext"
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

/** What a configured source is — never its secrets. */
data class SourceProfile(
    val id: SourceId,
    val kind: String,
    val displayName: String,
    /** Non-secret settings the source needs (e.g. an address, a user name). */
    val settings: Map<String, String> = emptyMap(),
)

/** Where profiles survive restarts. */
interface SourceProfileStore {
    fun load(): List<SourceProfile>
    fun save(profiles: List<SourceProfile>)
}

class InMemorySourceProfileStore(private var profiles: List<SourceProfile> = emptyList()) : SourceProfileStore {
    override fun load(): List<SourceProfile> = profiles

    override fun save(profiles: List<SourceProfile>) {
        this.profiles = profiles
    }
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

/** What a factory says after checking a setup form. */
sealed interface ConnectResult {
    data class Connected(
        val displayName: String,
        val settings: Map<String, String>,
        val secrets: Map<String, String>,
    ) : ConnectResult {
        override fun toString() = "Connected(displayName=$displayName, settings=$settings, secrets=${secrets.keys} hidden)"
    }

    data class Refused(val problem: SetupProblem) : ConnectResult
}

/** Builds one kind of configurable source. Registered by the app; nothing else knows the kinds. */
interface SourceFactory {
    val form: SetupForm

    /** Check what the listener entered with the source itself (e.g. a server ping) — no side effects. */
    suspend fun connect(values: Map<String, String>): ConnectResult

    /** The source for a stored profile; it reads its secrets from [credentials] when it needs them. */
    fun create(profile: SourceProfile, credentials: CredentialStore): MusicSource
}

/**
 * The listener's configured sources (D-37): restored at startup, added from Settings, removed. Each
 * becomes an ordinary source in the [SourceRegistry] — searched, played, prioritised like any other.
 */
class ConfiguredSources(
    private val registry: SourceRegistry,
    private val settings: SourceSettings,
    private val profiles: SourceProfileStore,
    private val credentials: CredentialStore,
    factories: List<SourceFactory>,
    private val newId: (kind: String) -> SourceId = { kind -> SourceId("$kind-${UUID.randomUUID().toString().take(8)}") },
) {
    private val factories = factories.associateBy { it.form.kind }

    /** The kinds the listener can add. */
    val forms: List<SetupForm> get() = factories.values.map { it.form }

    /** Register every stored source (call before [SourceSettings.apply]). Unknown kinds are skipped. */
    @Synchronized
    fun restore() {
        profiles.load().forEach { profile ->
            val factory = factories[profile.kind] ?: return@forEach
            if (registry.get(profile.id) == null) registry.register(factory.create(profile, credentials))
        }
    }

    fun isConfigured(id: SourceId): Boolean = profiles.load().any { it.id == id }

    /** Check [values] with the source, then keep and register it. */
    suspend fun add(kind: String, values: Map<String, String>): ConnectResult {
        val factory = factories[kind] ?: return ConnectResult.Refused(SetupProblem.NOT_SUPPORTED)
        val result = factory.connect(values)
        if (result !is ConnectResult.Connected) return result
        synchronized(this) {
            val profile = SourceProfile(newId(kind), kind, result.displayName, result.settings)
            credentials.write(profile.id, result.secrets)
            profiles.save(profiles.load() + profile)
            registry.register(factory.create(profile, credentials))
            settings.apply()
        }
        return result
    }

    /** Forget a configured source entirely: unregistered, its secrets deleted, its choices cleared. */
    @Synchronized
    fun remove(id: SourceId) {
        if (!isConfigured(id)) return
        registry.unregister(id)
        credentials.delete(id)
        profiles.save(profiles.load().filterNot { it.id == id })
        settings.forget(id)
    }
}
