package app.podium.sources.api.matching

import app.podium.core.model.Explicitness
import app.podium.core.model.VariantNote
import app.podium.core.model.VersionTag

/**
 * Classifies one title segment (bracketed or dash-suffixed text) as a version marker, part of the
 * song's identity, or packaging. Vocabulary derived from MusicBrainz recording-disambiguation and
 * release secondary-type conventions plus Podium's own corpus (D-23, ADR-014).
 */
object VersionLexicon {

    sealed interface Classification {
        data class Version(
            val tags: Set<VersionTag>,
            val variants: Set<VariantNote>,
            val explicitness: Explicitness?,
        ) : Classification

        /** Part of the name, e.g. "Part 2". */
        data object Identity : Classification

        /** Context only: soundtrack attribution, "Official Audio", a year, an edition note. */
        data object Packaging : Classification
    }

    fun classify(segment: String): Classification {
        val text = TrackNormalizer.identityKey(segment)
        if (text.isEmpty()) return Classification.Packaging
        val words = text.split(' ').toSet()
        fun has(vararg w: String) = w.all { it in words }
        fun hasAny(vararg w: String) = w.any { it in words }

        if (IDENTITY_PATTERN.matches(text)) return Classification.Identity

        val tags = mutableSetOf<VersionTag>()
        val variants = mutableSetOf<VariantNote>()
        var explicitness: Explicitness? = null

        // Explicitness markers ("Explicit", "Clean Version").
        if ("explicit" in words) explicitness = Explicitness.EXPLICIT
        if ("clean" in words) explicitness = Explicitness.CLEAN

        // Live performances. "Unplugged" sessions are live and acoustic.
        if (hasAny("live", "concert", "unplugged")) tags += VersionTag.LIVE
        if (hasAny("acoustic", "unplugged", "stripped")) tags += VersionTag.ACOUSTIC

        // Mixes. "Original mix", "radio mix", "extended mix", "album mix" are not remixes.
        val mixQualifiers = setOf("original", "radio", "extended", "album", "single", "clean", "explicit")
        if (hasAny("remix", "remixed", "rmx", "rework", "reworked", "refix", "bootleg", "flip", "vip", "dub", "mixed")) {
            tags += VersionTag.REMIX
        } else if ("mix" in words && words.none { it in mixQualifiers }) {
            tags += VersionTag.REMIX
        }

        if (hasAny("instrumental", "inst", "instr")) tags += VersionTag.INSTRUMENTAL
        if ("karaoke" in words || has("backing", "track") || has("minus", "one")) tags += VersionTag.KARAOKE
        if (hasAny("acapella", "acappella") || has("a", "cappella") || has("vocals", "only") || has("vocal", "only")) {
            tags += VersionTag.A_CAPPELLA
        }
        if (hasAny("cover", "covered")) tags += VersionTag.COVER
        if ("demo" in words) tags += VersionTag.DEMO
        if (hasAny("rerecorded", "rerecording") || has("re", "recorded") || has("re", "recording") ||
            POSSESSIVE_VERSION.containsMatchIn(segment.lowercase())
        ) {
            tags += VersionTag.RE_RECORDING
        }
        if (has("sped", "up") || has("speed", "up") || "spedup" in words) tags += VersionTag.SPED_UP
        if ("slowed" in words) tags += VersionTag.SLOWED
        if ("reverb" in words) tags += VersionTag.REVERB
        if ("nightcore" in words) tags += VersionTag.NIGHTCORE
        if ("extended" in words) tags += VersionTag.EXTENDED
        if ("radio" in words && hasAny("edit", "version", "mix", "cut")) tags += VersionTag.RADIO_EDIT
        if ("single" in words && hasAny("version", "edit", "mix")) tags += VersionTag.SINGLE_EDIT
        if (text == "edit" || has("short", "version")) tags += VersionTag.SINGLE_EDIT
        if ("mono" in words) tags += VersionTag.MONO
        if (hasAny("orchestral", "symphonic", "orchestra")) tags += VersionTag.ORCHESTRAL
        if ("medley" in words) tags += VersionTag.MEDLEY
        if ("mashup" in words || has("mash", "up")) tags += VersionTag.MASHUP
        if ("reprise" in words) tags += VersionTag.REPRISE
        if ("video" in words && !hasAny("lyric", "lyrics", "visualizer", "visualiser")) {
            tags += VersionTag.MUSIC_VIDEO_AUDIO
        }

        // Compatible variants of the same recording.
        if (words.any { it.startsWith("remaster") }) variants += VariantNote.REMASTER
        if (has("original", "mix")) variants += VariantNote.ORIGINAL_MIX
        if ((has("album", "version") || has("lp", "version") || has("original", "version")) &&
            VersionTag.RE_RECORDING !in tags
        ) {
            variants += VariantNote.ALBUM_VERSION
        }

        return if (tags.isEmpty() && variants.isEmpty() && explicitness == null) {
            Classification.Packaging
        } else {
            Classification.Version(tags, variants, explicitness)
        }
    }

    /** "part 2", "pt 2", "vol 3", "no 9", "chapter 1", "movement iv", "ii" — the name continues. */
    private val IDENTITY_PATTERN =
        Regex("""^(part|vol|volume|no|number|chapter|movement|mvt|act)\s+([0-9]+|[ivxlc]+)$|^([0-9]{1,2}|[ivx]{1,4})$""")

    /** Re-recordings branded with the artist's name: "(Taylor's Version)". */
    private val POSSESSIVE_VERSION = Regex("""\b\w+'s version\b""")
}
