package app.podium.sources.api

/** What a source can do. Absent facet ⇒ [CapabilityStatus.UNAVAILABLE]. */
enum class Capability {
    SEARCH,
    BROWSE,
    LIBRARY,
    LIKES,
    PLAYLISTS,
    DIRECT_STREAM,
    REMOTE_PLAYBACK,
    EMBEDDED_PLAYBACK,
    DOWNLOADS,
    LYRICS,
    RECOMMENDATIONS,
    ARTWORK,
    AUTHENTICATION,

    /** An online account's own library (liked songs, playlists, albums, artists). Not LIBRARY: that one is synced to the device. */
    ACCOUNT_LIBRARY,

    /** The account's own listening history, kept by the provider. */
    HISTORY,
}

/**
 * The state of one capability. Ordered from "usable" to "not offered".
 * A provider is never treated as supporting a capability because the UI would like it to.
 */
enum class CapabilityStatus {
    AVAILABLE,

    /** Works, with limits (quota, server feature missing). Still usable. */
    DEGRADED,

    /** Needs an Android runtime permission the user hasn't granted (e.g. music access). */
    REQUIRES_PERMISSION,
    REQUIRES_SIGN_IN,
    REQUIRES_SUBSCRIPTION,
    REQUIRES_PROVIDER_APP,

    /** Turned off by Podium build policy or the user. */
    DISABLED,

    /** The provider doesn't offer it. */
    UNAVAILABLE;

    val isUsable: Boolean get() = this == AVAILABLE || this == DEGRADED

    /** The user can do something to make it usable (grant, sign in, subscribe, install). */
    val isActionable: Boolean
        get() = this == REQUIRES_PERMISSION || this == REQUIRES_SIGN_IN ||
            this == REQUIRES_SUBSCRIPTION || this == REQUIRES_PROVIDER_APP
}

data class CapabilityState(
    val status: CapabilityStatus,
    /** User-facing explanation when not AVAILABLE, in sentence case. */
    val note: String? = null,
    /** What the user can do about it. Rendered generically; the UI never knows the provider. */
    val action: CapabilityAction? = null,
) {
    companion object {
        val Available = CapabilityState(CapabilityStatus.AVAILABLE)
        val Unavailable = CapabilityState(CapabilityStatus.UNAVAILABLE)
    }
}

/** The effective capability set of one source at one moment. */
data class SourceCapabilities(val states: Map<Capability, CapabilityState>) {
    operator fun get(capability: Capability): CapabilityState = states[capability] ?: CapabilityState.Unavailable

    fun isUsable(capability: Capability): Boolean = get(capability).status.isUsable

    /**
     * Combine with another layer of restrictions (runtime probe, account state, policy). For each
     * capability the more restrictive status wins: a restriction can only narrow, never widen.
     */
    fun restrictedBy(other: SourceCapabilities): SourceCapabilities {
        val keys = states.keys + other.states.keys
        return SourceCapabilities(
            keys.associateWith { key ->
                val mine = this[key]
                val theirs = other.states[key] ?: return@associateWith mine
                if (theirs.status.ordinal > mine.status.ordinal) theirs else mine
            },
        )
    }

    /** Apply a policy that disables some capabilities regardless of what the provider offers. */
    fun disable(capabilities: Set<Capability>, note: String): SourceCapabilities =
        SourceCapabilities(
            states + capabilities.filter { this[it].status != CapabilityStatus.UNAVAILABLE }
                .associateWith { CapabilityState(CapabilityStatus.DISABLED, note) },
        )

    companion object {
        val None = SourceCapabilities(emptyMap())

        fun of(vararg pairs: Pair<Capability, CapabilityState>) = SourceCapabilities(pairs.toMap())

        fun available(vararg capabilities: Capability) =
            SourceCapabilities(capabilities.associateWith { CapabilityState.Available })
    }
}

/** A user action that can make a capability usable. */
sealed interface CapabilityAction {
    /** An Android runtime permission (e.g. music access). */
    data class RequestPermission(val permission: String, val label: String) : CapabilityAction
    data class SignIn(val label: String) : CapabilityAction
    data class InstallApp(val packageName: String, val label: String) : CapabilityAction
}

/** Per-track download permission (a source may allow downloads but not for this track). */
sealed interface DownloadPermission {
    data class Allowed(val note: String? = null) : DownloadPermission
    data class NotPermitted(val reason: String) : DownloadPermission
    data object NotApplicable : DownloadPermission
}
