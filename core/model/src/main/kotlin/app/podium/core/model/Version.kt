package app.podium.core.model

/**
 * Markers that make a recording a *different take*. Two tracks whose tag sets differ are never the
 * same recording, in either direction ("Song (Live)" is not "Song", and "Song" is not "Song (Live)").
 * Derived by `TrackNormalizer` from titles and provider metadata — never provider-specific.
 */
enum class VersionTag {
    LIVE,
    REMIX,
    ACOUSTIC,
    INSTRUMENTAL,
    KARAOKE,
    A_CAPPELLA,
    COVER,
    DEMO,
    RE_RECORDING,
    SPED_UP,
    SLOWED,
    REVERB,
    NIGHTCORE,
    EXTENDED,
    RADIO_EDIT,
    SINGLE_EDIT,
    MONO,
    ORCHESTRAL,
    MEDLEY,
    MASHUP,
    REPRISE,
    MUSIC_VIDEO_AUDIO,
}

/**
 * Notes that describe the *same* recording in different clothing. They never veto a match, but some
 * (a remaster) cap how confident the matcher may be without identifier evidence.
 */
enum class VariantNote {
    REMASTER,
    ORIGINAL_MIX,
    ALBUM_VERSION,
}

/**
 * The version/identity analysis of a title.
 *
 * - [identityKey]: the words that name the song (alphanumeric, case-folded). Must agree for a match.
 * - [tags]: identity-changing version markers. Must agree for a match.
 * - [variants]: compatible variants. Never a veto.
 * - [versionDetail]: normalized text of the identity-changing segments ("daft punk remix",
 *   "live at wembley 1986"). Two remixes by different remixers share the REMIX tag but not this.
 * - [packaging]: context such as a soundtrack attribution or "Official Audio". Tie-break only.
 */
data class VersionInfo(
    val identityKey: String,
    val tags: Set<VersionTag> = emptySet(),
    val variants: Set<VariantNote> = emptySet(),
    val versionDetail: String = "",
    val packaging: List<String> = emptyList(),
) {
    companion object {
        val Unknown = VersionInfo(identityKey = "")
    }
}
