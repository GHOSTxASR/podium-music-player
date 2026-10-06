package app.podium.core.lyrics

import app.podium.sources.api.matching.ArtistNames
import app.podium.sources.api.matching.TrackNormalizer
import kotlin.math.abs

/**
 * Whether a provider's lyrics belong to the song playing (LYRICS_ARCHITECTURE.md §3). Conservative by
 * design: no lyrics beat another recording's lyrics.
 *
 * - The titles' identity must be the same (Podium's normaliser: "Song (Remastered 2011)" is "Song").
 * - Version markers must agree both ways: a live, remix, acoustic, instrumental or sped-up version
 *   never takes the studio song's words, and the reverse.
 * - The artists must share a name (any credited name, compared in normalised form).
 * - When both lengths are known they must be within [DURATION_TOLERANCE_MS]; unknown on either side
 *   skips that check only.
 */
object LyricsMatcher {
    const val DURATION_TOLERANCE_MS = 3_000L

    data class Candidate(val title: String, val artist: String, val album: String?, val durationMs: Long?)

    fun matches(request: LyricsRequest, candidate: Candidate): Boolean {
        val want = TrackNormalizer.analyze(request.title, request.artist)
        val have = TrackNormalizer.analyze(candidate.title, candidate.artist)
        if (want.version.identityKey.isEmpty() || want.version.identityKey != have.version.identityKey) return false
        if (want.version.tags != have.version.tags) return false
        val artistsWanted = ArtistNames.comparableForms(request.artist)
        val artistsFound = ArtistNames.comparableForms(candidate.artist)
        if (artistsWanted.isEmpty() || artistsWanted.none { it in artistsFound }) return false
        val a = request.durationMs
        val b = candidate.durationMs
        if (a != null && a > 0 && b != null && b > 0 && abs(a - b) > DURATION_TOLERANCE_MS) return false
        return true
    }

    /** The best of several candidates: a match, preferring the closest length, then the same album. */
    fun <T> best(request: LyricsRequest, candidates: List<T>, describe: (T) -> Candidate): T? =
        candidates
            .filter { matches(request, describe(it)) }
            .minWithOrNull(
                compareBy<T> { c -> request.durationMs?.let { d -> describe(c).durationMs?.let { abs(it - d) } } ?: Long.MAX_VALUE }
                    .thenBy { c -> if (request.album != null && describe(c).album?.equals(request.album, ignoreCase = true) == true) 0 else 1 },
            )
}
