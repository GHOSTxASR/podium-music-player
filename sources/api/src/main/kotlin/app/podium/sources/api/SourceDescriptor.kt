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
    /** Which of Podium's two worlds this source belongs to (D-34): music you own, or music online. */
    val environment: MusicEnvironment = if (basis == Basis.LOCAL_DEVICE) MusicEnvironment.LOCAL else MusicEnvironment.ONLINE,
)

/**
 * Podium's two music environments (D-34). They stay separate everywhere — libraries, likes,
 * playlists, history — and the UI says which one a song belongs to; nothing silently crosses over.
 */
enum class MusicEnvironment { LOCAL, ONLINE }

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
