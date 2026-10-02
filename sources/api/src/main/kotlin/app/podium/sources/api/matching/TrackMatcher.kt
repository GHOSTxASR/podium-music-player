package app.podium.sources.api.matching

import app.podium.core.model.ArtistRole
import app.podium.core.model.Track
import app.podium.core.model.VariantNote
import kotlin.math.abs
import kotlin.math.max

/**
 * Confidence that two tracks are the same recording. Only [EXACT] is safe for automatic playback
 * fallback (D-17); everything else is for grouping, review, or nothing.
 */
enum class MatchTier { EXACT, STRONG, POSSIBLE, NO_MATCH }

/** One explainable reason behind a decision. */
sealed interface Evidence {
    val description: String

    data class IsrcEqual(val isrc: String) : Evidence {
        override val description = "Same ISRC ($isrc)"
    }

    data class IsrcConflict(val a: String, val b: String) : Evidence {
        override val description = "Different ISRCs ($a vs $b)"
    }

    data object MbidEqual : Evidence {
        override val description = "Same MusicBrainz recording"
    }

    data object MbidConflict : Evidence {
        override val description = "Different MusicBrainz recordings"
    }

    data object TitleIdentityEqual : Evidence {
        override val description = "Same title identity"
    }

    data class TitleIdentityDiffers(val a: String, val b: String) : Evidence {
        override val description = "Different titles ('$a' vs '$b')"
    }

    data class VersionDiffers(val a: String, val b: String) : Evidence {
        override val description = "Different versions ($a vs $b)"
    }

    data class VersionDetailDiffers(val a: String, val b: String) : Evidence {
        override val description = "Different takes of the same version ('$a' vs '$b')"
    }

    data class ExplicitnessConflict(val a: String, val b: String) : Evidence {
        override val description = "Explicit and clean editions differ ($a vs $b)"
    }

    data class ArtistOverlap(val names: Set<String>) : Evidence {
        override val description = "Shared artist: ${names.joinToString()}"
    }

    data object NoSharedArtist : Evidence {
        override val description = "No artist in common"
    }

    data object ArtistUnknown : Evidence {
        override val description = "Artist missing on one side"
    }

    data class FeaturedArtistsDiffer(val a: Set<String>, val b: Set<String>) : Evidence {
        override val description = "Different featured artists"
    }

    data object FeaturedArtistsPartial : Evidence {
        override val description = "Featured artists listed on one side only"
    }

    data class DurationDelta(val millis: Long) : Evidence {
        override val description = "Lengths differ by ${millis / 1000.0}s"
    }

    data object DurationUnknown : Evidence {
        override val description = "Length unknown on one side"
    }

    data object AlbumEqual : Evidence {
        override val description = "Same album"
    }

    data object AlbumDiffers : Evidence {
        override val description = "Different album"
    }

    data object RemasterOnOneSide : Evidence {
        override val description = "Remastered on one side only"
    }

    data class Ambiguous(val candidates: Int) : Evidence {
        override val description = "$candidates candidates are equally likely"
    }
}

data class MatchResult(
    val tier: MatchTier,
    val confidence: Float,
    val evidence: List<Evidence>,
) {
    /** The only tier Podium will use to play a different source's copy automatically. */
    val isSafeForFallback: Boolean get() = tier == MatchTier.EXACT

    fun explain(): String = "$tier: " + evidence.joinToString("; ") { it.description }
}

data class MatchPolicy(
    val exactDurationMillis: Long = 2_000,
    val strongDurationMillis: Long = 5_000,
    val strongDurationFraction: Double = 0.02,
    val maxDurationMillis: Long = 10_000,
)

/**
 * Decides whether two tracks are the same recording (MUSIC_SOURCE_ARCHITECTURE §9).
 *
 * Rule-based tiers; the confidence number only orders results within a tier. Duration is evidence,
 * never proof: no tier is ever reached on duration alone.
 */
class TrackMatcher(private val policy: MatchPolicy = MatchPolicy()) {

    fun match(aIn: Track, bIn: Track): MatchResult {
        val a = TrackNormalizer.normalize(aIn)
        val b = TrackNormalizer.normalize(bIn)
        val evidence = mutableListOf<Evidence>()
        var ceiling = MatchTier.EXACT

        fun cap(tier: MatchTier) {
            if (tier.ordinal > ceiling.ordinal) ceiling = tier
        }

        fun noMatch(vararg reasons: Evidence) = MatchResult(MatchTier.NO_MATCH, 0f, evidence + reasons)

        // --- Identifier evidence -------------------------------------------------------------
        val isrcA = a.identifiers.normalizedIsrc
        val isrcB = b.identifiers.normalizedIsrc
        val mbidA = a.identifiers.musicBrainzRecordingId?.lowercase()
        val mbidB = b.identifiers.musicBrainzRecordingId?.lowercase()
        val identifierEqual = when {
            isrcA != null && isrcA == isrcB -> {
                evidence += Evidence.IsrcEqual(isrcA); true
            }
            mbidA != null && mbidA == mbidB -> {
                evidence += Evidence.MbidEqual; true
            }
            else -> false
        }
        if (!identifierEqual) {
            if (isrcA != null && isrcB != null && isrcA != isrcB) {
                evidence += Evidence.IsrcConflict(isrcA, isrcB)
                cap(MatchTier.POSSIBLE)
            }
            if (mbidA != null && mbidB != null && mbidA != mbidB) {
                evidence += Evidence.MbidConflict
                cap(MatchTier.POSSIBLE)
            }
        }

        // --- Explicit vs clean: never interchangeable ---------------------------------------
        if (a.explicitness.conflictsWith(b.explicitness)) {
            return noMatch(Evidence.ExplicitnessConflict(a.explicitness.name, b.explicitness.name))
        }

        // --- Title identity -------------------------------------------------------------------
        val va = a.version
        val vb = b.version
        if (va.identityKey == vb.identityKey) {
            evidence += Evidence.TitleIdentityEqual
        } else if (identifierEqual) {
            // Localized or re-spelled titles with the same recording id: plausible, not exact.
            evidence += Evidence.TitleIdentityDiffers(va.identityKey, vb.identityKey)
            cap(MatchTier.STRONG)
        } else {
            return noMatch(Evidence.TitleIdentityDiffers(va.identityKey, vb.identityKey))
        }

        // --- Version: live/remix/sped-up/... must agree in both directions -------------------
        if (va.tags != vb.tags) {
            val reason = Evidence.VersionDiffers(describe(va.tags), describe(vb.tags))
            if (!identifierEqual) return noMatch(reason)
            evidence += reason
            cap(MatchTier.STRONG) // metadata disagrees with the identifier: never auto-substitute
        } else if (va.tags.isNotEmpty() && va.versionDetail != vb.versionDetail) {
            evidence += Evidence.VersionDetailDiffers(va.versionDetail, vb.versionDetail)
            cap(if (identifierEqual) MatchTier.STRONG else MatchTier.POSSIBLE)
        }
        val remasterA = VariantNote.REMASTER in va.variants
        val remasterB = VariantNote.REMASTER in vb.variants
        if (remasterA != remasterB && !identifierEqual) {
            evidence += Evidence.RemasterOnOneSide
            cap(MatchTier.STRONG)
        }

        // --- Artists ---------------------------------------------------------------------------
        val primaryA = artistForms(a, featured = false)
        val primaryB = artistForms(b, featured = false)
        if (primaryA.isEmpty() || primaryB.isEmpty()) {
            evidence += Evidence.ArtistUnknown
            cap(if (identifierEqual) MatchTier.STRONG else MatchTier.POSSIBLE)
        } else {
            val shared = primaryA intersect primaryB
            if (shared.isEmpty()) {
                if (!identifierEqual) return noMatch(Evidence.NoSharedArtist)
                evidence += Evidence.NoSharedArtist
                cap(MatchTier.STRONG)
            } else {
                evidence += Evidence.ArtistOverlap(shared)
            }
        }
        val featA = artistForms(a, featured = true)
        val featB = artistForms(b, featured = true)
        when {
            featA.isNotEmpty() && featB.isNotEmpty() && (featA intersect featB).isEmpty() -> {
                val reason = Evidence.FeaturedArtistsDiffer(featA, featB)
                if (!identifierEqual) return noMatch(reason)
                evidence += reason
                cap(MatchTier.STRONG)
            }
            featA.isEmpty() != featB.isEmpty() && !identifierEqual -> {
                evidence += Evidence.FeaturedArtistsPartial
                cap(MatchTier.STRONG)
            }
        }

        // --- Duration: evidence only -----------------------------------------------------------
        val da = a.durationMs
        val db = b.durationMs
        if (da == null || db == null) {
            evidence += Evidence.DurationUnknown
            if (!identifierEqual) cap(MatchTier.POSSIBLE)
        } else {
            val delta = abs(da - db)
            evidence += Evidence.DurationDelta(delta)
            val strongWindow = max(policy.strongDurationMillis, (max(da, db) * policy.strongDurationFraction).toLong())
            when {
                delta > policy.maxDurationMillis -> {
                    if (!identifierEqual) return noMatch()
                    cap(MatchTier.STRONG) // same id, very different length: a data problem
                }
                delta > strongWindow -> cap(if (identifierEqual) MatchTier.STRONG else MatchTier.POSSIBLE)
                delta > policy.exactDurationMillis -> if (!identifierEqual) cap(MatchTier.STRONG)
            }
        }

        // --- Album -------------------------------------------------------------------------------
        val albumA = a.album?.title?.let(::albumKey)
        val albumB = b.album?.title?.let(::albumKey)
        if (albumA != null && albumB != null) {
            if (albumA == albumB) {
                evidence += Evidence.AlbumEqual
            } else {
                evidence += Evidence.AlbumDiffers
                if (!identifierEqual) cap(MatchTier.STRONG)
            }
        }

        val tier = ceiling
        return MatchResult(tier, confidence(tier, evidence), evidence)
    }

    /**
     * The best candidate for [target], or null. If several candidates reach EXACT but disagree
     * with each other (different album or length), the result is downgraded: ambiguity is never
     * resolved by guessing.
     */
    fun best(target: Track, candidates: List<Track>): Pair<Track, MatchResult>? {
        val scored = candidates
            .map { it to match(target, it) }
            .filter { it.second.tier != MatchTier.NO_MATCH }
            .sortedWith(compareBy<Pair<Track, MatchResult>> { it.second.tier.ordinal }.thenByDescending { it.second.confidence })
        val top = scored.firstOrNull() ?: return null
        if (top.second.tier == MatchTier.EXACT) {
            val exact = scored.filter { it.second.tier == MatchTier.EXACT }
            val distinct = exact.distinctBy { (t, _) ->
                (t.album?.title?.let(::albumKey) ?: "") + "|" + ((t.durationMs ?: 0) / policy.exactDurationMillis)
            }
            if (distinct.size > 1) {
                val downgraded = top.second.copy(
                    tier = MatchTier.POSSIBLE,
                    evidence = top.second.evidence + Evidence.Ambiguous(distinct.size),
                )
                return top.first to downgraded
            }
        }
        return top
    }

    private fun artistForms(track: Track, featured: Boolean): Set<String> {
        val credits = track.artists.filter { (it.role == ArtistRole.FEATURED) == featured }
        val forms = credits.flatMap { ArtistNames.comparableForms(it.name) }.toMutableSet()
        if (!featured && credits.isEmpty() && track.artistDisplay.isNotBlank()) {
            forms += ArtistNames.comparableForms(track.artistDisplay)
        }
        return forms
    }

    private fun albumKey(title: String): String = TrackNormalizer.analyze(title).version.identityKey

    private fun describe(tags: Set<*>) = if (tags.isEmpty()) "original" else tags.joinToString("+") { it.toString().lowercase() }

    private fun confidence(tier: MatchTier, evidence: List<Evidence>): Float {
        var c = when (tier) {
            MatchTier.EXACT -> 0.95f
            MatchTier.STRONG -> 0.8f
            MatchTier.POSSIBLE -> 0.5f
            MatchTier.NO_MATCH -> 0f
        }
        for (e in evidence) {
            c += when (e) {
                is Evidence.IsrcEqual, Evidence.MbidEqual -> 0.04f
                Evidence.AlbumEqual -> 0.01f
                is Evidence.DurationDelta -> if (e.millis <= 1_000) 0.005f else 0f
                else -> 0f
            }
        }
        return c.coerceAtMost(1f)
    }
}

/** Builds catalogue search queries for finding a track elsewhere: packaging removed. */
object MatchQueryBuilder {
    fun queries(track: Track): List<String> {
        val analysis = TrackNormalizer.analyze(track.title, track.artistDisplay)
        val versionWords = analysis.version.versionDetail.replace(" | ", " ")
        val title = listOf(analysis.baseTitle, versionWords).filter { it.isNotBlank() }.joinToString(" ")
        val primary = track.artists.firstOrNull { it.role == ArtistRole.PRIMARY }?.name ?: track.artistDisplay
        return listOfNotNull(
            "$title $primary".trim().takeIf { primary.isNotBlank() },
            title.takeIf { it.isNotBlank() },
        ).distinct()
    }
}
