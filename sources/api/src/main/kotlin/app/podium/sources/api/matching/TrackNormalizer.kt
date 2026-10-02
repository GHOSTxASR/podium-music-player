package app.podium.sources.api.matching

import app.podium.core.model.ArtistCredit
import app.podium.core.model.ArtistRole
import app.podium.core.model.Explicitness
import app.podium.core.model.Track
import app.podium.core.model.VariantNote
import app.podium.core.model.VersionInfo
import app.podium.core.model.VersionTag
import java.text.Normalizer
import java.util.Locale

/**
 * Splits a title into Identity / Version / Packaging (MUSIC_SOURCE_ARCHITECTURE §9.2).
 *
 * Never "strip all brackets and compare": bracketed and dash-suffixed segments are *classified*.
 * A leading bracket is identity ("(I Can't Get No) Satisfaction"); part numbers are identity;
 * version markers change identity; packaging is only context.
 *
 * Independent Podium design (ADR-014): the vocabulary follows MusicBrainz's published recording
 * disambiguation and release-type conventions, not any other client's code.
 */
object TrackNormalizer {

    data class TitleAnalysis(
        val version: VersionInfo,
        /** The cleaned title without packaging/version segments, original casing, for search queries. */
        val baseTitle: String,
        val featuredArtists: List<String>,
        val explicitnessHint: Explicitness?,
    )

    /** Fill in [Track.version], featured credits and explicitness hints derived from the title. */
    fun normalize(track: Track): Track {
        if (track.version != VersionInfo.Unknown) return track
        val analysis = analyze(track.title, track.artistDisplay)
        val existing = track.artists.map { ArtistNames.normalize(it.name) }.toSet()
        val featured = analysis.featuredArtists
            .filter { ArtistNames.normalize(it) !in existing }
            .map { ArtistCredit(it, ArtistRole.FEATURED) }
        val explicitness = when {
            track.explicitness != Explicitness.UNKNOWN -> track.explicitness
            analysis.explicitnessHint != null -> analysis.explicitnessHint
            else -> track.explicitness
        }
        return track.copy(
            version = analysis.version,
            artists = track.artists + featured,
            explicitness = explicitness,
        )
    }

    fun analyze(rawTitle: String, artistDisplay: String = ""): TitleAnalysis {
        val title = clean(rawTitle)
        val tags = mutableSetOf<VersionTag>()
        val variants = mutableSetOf<VariantNote>()
        val packaging = mutableListOf<String>()
        val featured = mutableListOf<String>()
        var explicitness: Explicitness? = null

        // 1. Leading bracket = identity: "(I Can't Get No) Satisfaction".
        var remaining = title
        var leadingIdentity = ""
        LEADING_BRACKET.find(remaining)?.let { m ->
            leadingIdentity = m.groupValues[1]
            remaining = remaining.substring(m.range.last + 1).trim()
        }

        // 2. Pull out bracketed segments anywhere after the start.
        val segments = mutableListOf<String>()
        var guard = 0
        while (guard++ < 8) {
            val m = BRACKETED.find(remaining) ?: break
            segments += m.groupValues[1].trim()
            remaining = (remaining.substring(0, m.range.first) + " " + remaining.substring(m.range.last + 1)).trim()
        }

        // 3. Dash-suffixed segments: "Song - Live at Wembley", "Artist - Song" upload shape.
        val dashParts = remaining.split(DASH_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
        var base = dashParts.firstOrNull().orEmpty()
        var tail = dashParts.drop(1)
        if (tail.isNotEmpty() && artistDisplay.isNotBlank() &&
            ArtistNames.normalize(base) == ArtistNames.normalize(artistDisplay)
        ) {
            base = tail.first()
            tail = tail.drop(1)
        }

        // 4. "feat." inside the base title.
        FEATURING_INLINE.find(base)?.let { m ->
            featured += ArtistNames.split(m.groupValues[2])
            base = base.substring(0, m.range.first).trim()
        }

        val identityExtra = mutableListOf<String>()
        val versionDetails = mutableListOf<String>()
        fun classify(segment: String, fromDash: Boolean) {
            val seg = segment.trim()
            if (seg.isEmpty()) return
            FEATURING_SEGMENT.matchEntire(seg)?.let {
                featured += ArtistNames.split(it.groupValues[2])
                return
            }
            when (val c = VersionLexicon.classify(seg)) {
                is VersionLexicon.Classification.Version -> {
                    tags += c.tags
                    variants += c.variants
                    c.explicitness?.let { explicitness = it }
                    if (c.tags.isNotEmpty()) versionDetails += identityKey(seg)
                }
                is VersionLexicon.Classification.Identity -> identityExtra += seg
                VersionLexicon.Classification.Packaging ->
                    // An unrecognised dash suffix is more likely part of the name than packaging.
                    if (fromDash) identityExtra += seg else packaging += seg
            }
        }
        segments.forEach { classify(it, fromDash = false) }
        tail.forEach { classify(it, fromDash = true) }

        val identityText = listOf(leadingIdentity, base, identityExtra.joinToString(" "))
            .filter { it.isNotBlank() }
            .joinToString(" ")
        val displayBase = listOf(leadingIdentity.takeIf { it.isNotBlank() }?.let { "($it)" }, base)
            .filterNotNull().joinToString(" ").trim()

        return TitleAnalysis(
            version = VersionInfo(
                identityKey = identityKey(identityText),
                tags = tags,
                variants = variants,
                versionDetail = versionDetails.sorted().joinToString(" | "),
                packaging = packaging,
            ),
            baseTitle = displayBase.ifBlank { title },
            featuredArtists = featured.distinct(),
            explicitnessHint = explicitness,
        )
    }

    /**
     * Comparison key for the identity part of a title: case-folded, Latin diacritics folded (other
     * scripts untouched — removing combining marks would destroy e.g. Devanagari), punctuation
     * removed, "&" → "and", "pt." → "part".
     */
    fun identityKey(text: String): String {
        val folded = foldLatinDiacritics(clean(text)).lowercase(Locale.ROOT)
            .replace("&", " and ")
        val words = folded.split(NON_WORD).filter { it.isNotEmpty() }.map { word ->
            when (word) {
                "pt" -> "part"
                else -> word
            }
        }
        return words.joinToString(" ")
    }

    /** NFKC, unified quotes/dashes, collapsed whitespace. */
    fun clean(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .replace(QUOTES_SINGLE, "'")
        .replace(QUOTES_DOUBLE, "\"")
        .replace(DASHES, "-")
        .replace(WHITESPACE, " ")
        .trim()

    internal fun foldLatinDiacritics(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        val out = StringBuilder(decomposed.length)
        var lastBaseIsLatin = false
        for (ch in decomposed) {
            if (Character.getType(ch) == Character.NON_SPACING_MARK.toInt()) {
                if (!lastBaseIsLatin) out.append(ch)
            } else {
                out.append(ch)
                lastBaseIsLatin = Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.LATIN
            }
        }
        return Normalizer.normalize(out, Normalizer.Form.NFC)
    }

    private val LEADING_BRACKET = Regex("""^[(\[]([^()\[\]]+)[)\]]\s*(?=\S)""")
    private val BRACKETED = Regex("""[(\[{]([^()\[\]{}]*)[)\]}]""")
    private val DASH_SEPARATOR = Regex("""\s+-\s+""")
    private val FEATURING_INLINE = Regex("""\s(feat\.?|ft\.?|featuring)\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val FEATURING_SEGMENT = Regex("""^(feat\.?|ft\.?|featuring|with)\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val QUOTES_SINGLE = Regex("[‘’‚‛′´`]")
    private val QUOTES_DOUBLE = Regex("[“”„‟″]")
    private val DASHES = Regex("[‐‑‒–—―−]")
    private val WHITESPACE = Regex("""\s+""")
    private val NON_WORD = Regex("""[^\p{L}\p{M}\p{N}]+""")
}

/** Artist-name comparison helpers. */
object ArtistNames {
    /**
     * Comparable form of one artist name. Does not split: "Simon & Garfunkel" stays one name.
     */
    fun normalize(name: String): String {
        val folded = TrackNormalizer.foldLatinDiacritics(TrackNormalizer.clean(name)).lowercase(Locale.ROOT)
            .replace("&", " and ")
            .replace(PUNCT, " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return folded.removePrefix("the ").trim()
    }

    /** Split a display credit like "A, B & C feat. D" into names. Used alongside the full string. */
    fun split(display: String): List<String> = display.split(SEPARATORS)
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    /**
     * Every comparable form of a credit: the full normalized string and each split name (names of
     * at least two characters). Comparing sets of these protects band names that contain "&" or
     * "and" from being broken into false single-word overlaps.
     */
    fun comparableForms(display: String): Set<String> {
        val full = normalize(display)
        val parts = split(display).map(::normalize).filter { it.length >= 2 }
        return (parts + full).filter { it.isNotBlank() }.toSet()
    }

    private val PUNCT = Regex("""[^\p{L}\p{M}\p{N}\s]""")
    private val SEPARATORS = Regex(
        """\s*(?:,|;|/|\s&\s|\sx\s|\s×\s|\svs\.?\s|\sfeat\.?\s|\sft\.?\s|\sfeaturing\s|\swith\s)\s*""",
        RegexOption.IGNORE_CASE,
    )
}
