package app.podium.sources.api

import app.podium.core.model.SourceId

/**
 * What a source is, in provider-neutral terms. [providerName] is for display only — no code may
 * branch on it (MUSIC_SOURCE_ARCHITECTURE §0).
 */
data class SourceDescriptor(
    val id: SourceId,
    val displayName: String,
    val providerName: String,
    val basis: Basis,
    val attribution: Attribution? = null,
    val termsUrl: String? = null,
    /** True when resolving is cheap enough to do speculatively (prefetch next item). */
    val cheapResolve: Boolean = false,
)

/** Where a source's access comes from (ADR-013, D-19). Drives labels and build policy. */
enum class Basis {
    LOCAL_DEVICE,
    USER_SERVER,
    OFFICIAL_API,
    UNOFFICIAL_API,
}

/** Marks/link-back a provider requires next to its content; rendered generically by the UI. */
data class Attribution(
    val text: String,
    val linkUrl: String? = null,
    val logoKey: String? = null,
)
