package app.podium.sources.api.resolve

import app.podium.core.common.ManualClock
import app.podium.core.model.Explicitness
import app.podium.core.model.Track
import app.podium.sources.api.FacetResolution
import app.podium.sources.api.HealthOutcome
import app.podium.sources.api.MissReason
import app.podium.sources.api.PlayableMedia
import app.podium.sources.api.ResolutionPath
import app.podium.sources.api.SourceHealth
import app.podium.sources.api.SourceHealthMonitor
import app.podium.sources.api.SourceRegistry
import app.podium.sources.api.matching.MatchTier
import app.podium.sources.api.matching.TrackMatcher
import app.podium.sources.testing.FakeMusicSource
import app.podium.sources.testing.track
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StreamResolverTest {
    private val clock = ManualClock(10_000_000)
    private val health = SourceHealthMonitor(clock)
    private val registry = SourceRegistry(health)

    private fun resolver(owned: List<OwnedCopyLocator> = emptyList()) =
        StreamResolver(registry, health, TrackMatcher(), clock, owned)

    private val song = track("Song", "Artist", source = "primary", album = "Album", durationSec = 200.0)

    private fun primary(vararg tracks: Track, expiresAfter: Long? = null) =
        FakeMusicSource("primary", tracks.toList(), expiresAfterMillis = expiresAfter, clock = { clock.nowMillis() })
            .also { registry.register(it) }

    private fun mirror(vararg tracks: Track) = FakeMusicSource("mirror", tracks.toList()).also { registry.register(it) }

    @Test
    fun `resolves from the track's own source`() = runTest {
        val p = primary(song)
        val outcome = assertIs<ResolveOutcome.Resolved>(resolver().resolve(ResolveRequest(song)))
        assertEquals(ResolutionPath.OWN_SOURCE, outcome.selection.path)
        assertEquals(p.sourceId, outcome.selection.servedBy)
        assertEquals(1, p.resolveCalls)
    }

    @Test
    fun `a valid pin is reused without asking any source`() = runTest {
        val p = primary(song)
        val r = resolver()
        val first = assertIs<ResolveOutcome.Resolved>(r.resolve(ResolveRequest(song)))
        val second = assertIs<ResolveOutcome.Resolved>(r.resolve(ResolveRequest(song, pinned = first.selection)))
        assertTrue(second.reused)
        assertEquals(first.selection, second.selection)
        assertEquals(1, p.resolveCalls)
    }

    @Test
    fun `an expired pin is resolved again`() = runTest {
        val p = primary(song, expiresAfter = 120_000)
        val r = resolver()
        val first = assertIs<ResolveOutcome.Resolved>(r.resolve(ResolveRequest(song)))
        clock.advanceBy(61_000) // inside the 60 s safety margin of the 120 s expiry
        val second = assertIs<ResolveOutcome.Resolved>(r.resolve(ResolveRequest(song, pinned = first.selection)))
        assertEquals(false, second.reused)
        assertEquals(2, p.resolveCalls)
    }

    @Test
    fun `an owned copy is preferred to streaming`() = runTest {
        val p = primary(song)
        val ownedMedia = PlayableMedia("file:///owned.flac", sourceId = song.source.sourceId, cacheKey = "owned")
        val outcome = assertIs<ResolveOutcome.Resolved>(
            resolver(listOf(OwnedCopyLocator { if (it.id == song.id) song to ownedMedia else null })).resolve(ResolveRequest(song)),
        )
        assertEquals(ResolutionPath.OWNED_COPY, outcome.selection.path)
        assertEquals(0, p.resolveCalls)
    }

    @Test
    fun `falls back to an EXACT copy on another source when the own source misses`() = runTest {
        val p = primary(song)
        p.outcomes[song.id] = FacetResolution.Miss(MissReason.NOT_FOUND)
        val copy = track("Song", "Artist", source = "mirror", album = "Album", durationSec = 200.5)
        val m = mirror(copy)
        val outcome = assertIs<ResolveOutcome.Resolved>(resolver().resolve(ResolveRequest(song)))
        assertEquals(ResolutionPath.EXACT_FALLBACK, outcome.selection.path)
        assertEquals(m.sourceId, outcome.selection.servedBy)
        assertEquals(copy.id, outcome.selection.servedTrack.id)
        assertEquals(MatchTier.EXACT, outcome.selection.match?.tier)
    }

    private suspend fun fallbackMisses(candidate: Track, original: Track = song) {
        val p = primary(original)
        p.outcomes[original.id] = FacetResolution.Miss(MissReason.NOT_FOUND)
        val m = mirror(candidate)
        val outcome = resolver().resolve(ResolveRequest(original))
        assertIs<ResolveOutcome.Miss>(outcome, "must not fall back to '${candidate.title}': $outcome")
        assertEquals(0, m.resolveCalls, "the non-matching copy must never be asked for a stream")
    }

    @Test
    fun `fallback never switches live and studio`() = runTest {
        fallbackMisses(track("Song (Live)", "Artist", source = "mirror", album = "Album", durationSec = 200.0))
    }

    @Test
    fun `fallback never switches remix and original`() = runTest {
        fallbackMisses(track("Song (Club Remix)", "Artist", source = "mirror", album = "Album", durationSec = 200.0))
    }

    @Test
    fun `fallback never switches explicit and clean`() = runTest {
        val explicit = track("Song", "Artist", source = "primary", album = "Album", explicitness = Explicitness.EXPLICIT)
        fallbackMisses(
            track("Song", "Artist", source = "mirror", album = "Album", explicitness = Explicitness.CLEAN),
            original = explicit,
        )
    }

    @Test
    fun `fallback never plays another artist's song of the same name`() = runTest {
        fallbackMisses(track("Song", "Someone Else", source = "mirror", album = "Album", durationSec = 200.0))
    }

    @Test
    fun `fallback refuses anything below EXACT`() = runTest {
        // Same song on a different album: STRONG, not EXACT.
        fallbackMisses(track("Song", "Artist", source = "mirror", album = "Greatest Hits", durationSec = 200.0))
    }

    @Test
    fun `no fallback when the item's policy forbids it`() = runTest {
        val p = primary(song)
        p.outcomes[song.id] = FacetResolution.Miss(MissReason.NOT_FOUND)
        val m = mirror(track("Song", "Artist", source = "mirror", album = "Album", durationSec = 200.0))
        assertIs<ResolveOutcome.Miss>(resolver().resolve(ResolveRequest(song, fallbackPolicy = FallbackPolicy.NONE)))
        assertEquals(0, m.searchCalls)
    }

    @Test
    fun `misses keep the source healthy, failures are recorded`() = runTest {
        val p = primary(song)
        p.outcomes[song.id] = FacetResolution.Miss(MissReason.NOT_FOUND)
        repeat(5) { resolver().resolve(ResolveRequest(song, fallbackPolicy = FallbackPolicy.NONE)) }
        assertEquals(SourceHealth.Healthy, health.health(p.sourceId))

        p.outcomes[song.id] = FacetResolution.Failed(HealthOutcome.NETWORK_FAILURE)
        val failed = assertIs<ResolveOutcome.Failed>(resolver().resolve(ResolveRequest(song, fallbackPolicy = FallbackPolicy.NONE)))
        assertEquals(HealthOutcome.NETWORK_FAILURE, failed.outcome)
        assertIs<SourceHealth.Degraded>(health.health(p.sourceId))
    }

    @Test
    fun `an open circuit skips the source and the exact copy plays instead`() = runTest {
        val p = primary(song)
        val copy = track("Song", "Artist", source = "mirror", album = "Album", durationSec = 200.0)
        mirror(copy)
        repeat(3) { health.record(p.sourceId, HealthOutcome.SERVER_ERROR) }
        val outcome = assertIs<ResolveOutcome.Resolved>(resolver().resolve(ResolveRequest(song)))
        assertEquals(ResolutionPath.EXACT_FALLBACK, outcome.selection.path)
        assertEquals(0, p.resolveCalls)
    }

    @Test
    fun `refreshing a playing item never switches source`() = runTest {
        val p = primary(song)
        val r = resolver()
        val pinned = assertIs<ResolveOutcome.Resolved>(r.resolve(ResolveRequest(song))).selection
        val m = mirror(track("Song", "Artist", source = "mirror", album = "Album", durationSec = 200.0))
        p.outcomes[song.id] = FacetResolution.Failed(HealthOutcome.AUTH_FAILURE)
        val outcome = r.resolve(ResolveRequest(song, pinned = pinned, refreshPinnedSourceOnly = true))
        assertIs<ResolveOutcome.Failed>(outcome)
        assertEquals(0, m.resolveCalls)
        assertEquals(0, m.searchCalls)
    }

    @Test
    fun `fallback follows user priority`() = runTest {
        val p = primary(song)
        p.outcomes[song.id] = FacetResolution.Miss(MissReason.NOT_FOUND)
        val first = FakeMusicSource("first", listOf(track("Song", "Artist", source = "first", album = "Album")))
        val second = FakeMusicSource("second", listOf(track("Song", "Artist", source = "second", album = "Album")))
        registry.register(first)
        registry.register(second)
        registry.setPriority(listOf(second.sourceId, first.sourceId))
        val outcome = assertIs<ResolveOutcome.Resolved>(resolver().resolve(ResolveRequest(song)))
        assertEquals(second.sourceId, outcome.selection.servedBy)
    }

    @Test
    fun `disabled sources are never used`() = runTest {
        val p = primary(song)
        p.outcomes[song.id] = FacetResolution.Miss(MissReason.NOT_FOUND)
        val m = FakeMusicSource("mirror", listOf(track("Song", "Artist", source = "mirror", album = "Album")))
        registry.register(m, enabled = false)
        assertIs<ResolveOutcome.Miss>(resolver().resolve(ResolveRequest(song)))
        assertEquals(0, m.searchCalls)
    }

    @Test
    fun `concurrent requests for the same track share one resolution`() = runTest {
        val p = primary(song)
        p.resolveDelayMillis = 100
        val r = resolver()
        val results = List(5) { async { r.resolve(ResolveRequest(song)) } }.awaitAll()
        assertTrue(results.all { it is ResolveOutcome.Resolved })
        assertEquals(1, p.resolveCalls)
    }
}
