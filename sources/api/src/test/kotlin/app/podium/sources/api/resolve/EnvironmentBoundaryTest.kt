package app.podium.sources.api.resolve

import app.podium.core.common.ManualClock
import app.podium.core.model.SourceId
import app.podium.core.model.SourceRef
import app.podium.core.model.Track
import app.podium.core.model.TrackId
import app.podium.sources.api.FacetResolution
import app.podium.sources.api.MissReason
import app.podium.sources.api.PlayableMedia
import app.podium.sources.api.ResolutionPath
import app.podium.sources.api.SourceHealthMonitor
import app.podium.sources.api.SourceRegistry
import app.podium.sources.api.matching.MatchResult
import app.podium.sources.api.matching.MatchTier
import app.podium.sources.api.matching.TrackMatcher
import app.podium.sources.testing.FakeMusicSource
import app.podium.sources.testing.FakeOnlineMusicSource
import app.podium.sources.testing.track
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The environment is part of what the listener chose (D-36): a song picked online plays online, a
 * song picked from the library plays from the library. Fallback stays inside the environment.
 */
class EnvironmentBoundaryTest {
    private val clock = ManualClock(1_000_000)
    private val health = SourceHealthMonitor(clock)
    private val registry = SourceRegistry(health)
    private val equivalence = InMemoryEquivalenceStore()

    private fun resolver(owned: List<OwnedCopyLocator> = emptyList()) =
        StreamResolver(registry, health, TrackMatcher(), clock, owned, equivalence)

    /** The library: two local sources (e.g. this phone and, later, a home server). */
    private val phone = FakeMusicSource("phone").also { registry.register(it) }
    private val shelf = FakeMusicSource("shelf").also { registry.register(it) }

    /** ONLINE: two online sources. */
    private val a = FakeOnlineMusicSource("a").also { registry.register(it) }
    private val b = FakeOnlineMusicSource("b").also { registry.register(it) }

    private fun local(source: FakeMusicSource, key: String): Track =
        track("Song Shared", "Band", source = source.sourceId.value, key = key).also { source.add(it) }

    @Test
    fun `a song chosen online never silently becomes the local file`() = runTest {
        val online = a.track("Song Shared", artist = "Band")
        a.unstreamable += online.id
        local(phone, "file-1")
        val outcome = assertIs<ResolveOutcome.Miss>(resolver().resolve(ResolveRequest(online)))
        assertEquals(MissReason.NOT_STREAMABLE, outcome.reason)
        assertEquals(0, phone.searchCalls, "the library isn't even searched")
        assertEquals(0, phone.resolveCalls)
    }

    @Test
    fun `a song chosen online plays another online source's exact copy`() = runTest {
        val online = a.track("Song Shared", artist = "Band")
        a.unstreamable += online.id
        local(phone, "file-1")
        b.track("Song Shared", artist = "Band", durationSec = 201.0)
        val outcome = assertIs<ResolveOutcome.Resolved>(resolver().resolve(ResolveRequest(online)))
        assertEquals(b.sourceId, outcome.selection.servedBy)
    }

    @Test
    fun `a song chosen from the library never silently becomes an online stream`() = runTest {
        val file = local(phone, "file-1")
        phone.outcomes[file.id] = FacetResolution.Miss(MissReason.NOT_FOUND) // the file is gone
        a.track("Song Shared", artist = "Band")
        assertIs<ResolveOutcome.Miss>(resolver().resolve(ResolveRequest(file)))
        assertEquals(0, a.searchCalls)
        assertEquals(0, a.resolveCalls)
    }

    @Test
    fun `a song chosen from the library plays another library source's exact copy`() = runTest {
        val file = local(phone, "file-1")
        phone.outcomes[file.id] = FacetResolution.Miss(MissReason.NOT_FOUND)
        local(shelf, "file-2")
        val outcome = assertIs<ResolveOutcome.Resolved>(resolver().resolve(ResolveRequest(file)))
        assertEquals(shelf.sourceId, outcome.selection.servedBy)
        assertEquals(ResolutionPath.EXACT_FALLBACK, outcome.selection.path)
    }

    @Test
    fun `even a known exact pair never crosses environments`() = runTest {
        val online = a.track("Song Shared", artist = "Band")
        a.unstreamable += online.id
        val file = local(phone, "file-1")
        equivalence.put(online, file, MatchResult(MatchTier.EXACT, 0.95f, emptyList()))
        assertIs<ResolveOutcome.Miss>(resolver().resolve(ResolveRequest(online)))
        assertEquals(0, phone.resolveCalls)
    }

    @Test
    fun `an owned copy from the other environment is ignored, a download of the song itself is not`() = runTest {
        val online = a.track("Song Shared", artist = "Band")
        val file = local(phone, "file-1")
        val fileMedia = PlayableMedia("file:///music/song.flac", sourceId = phone.sourceId, cacheKey = "f")
        val fromLibrary = OwnedCopyLocator { file to fileMedia }
        val first = assertIs<ResolveOutcome.Resolved>(resolver(listOf(fromLibrary)).resolve(ResolveRequest(online)))
        assertEquals(ResolutionPath.OWN_SOURCE, first.selection.path, "the library file isn't used for an online pick")
        val download = PlayableMedia("file:///downloads/a.mp3", sourceId = a.sourceId, cacheKey = "d")
        val second = assertIs<ResolveOutcome.Resolved>(resolver(listOf(OwnedCopyLocator { online to download })).resolve(ResolveRequest(online)))
        assertEquals(ResolutionPath.OWNED_COPY, second.selection.path, "a download of the very song is still preferred")
    }

    @Test
    fun `a song from a source that's gone has no environment to fall back within`() = runTest {
        val orphan = Track(TrackId.of(SourceId("gone"), "1"), SourceRef(SourceId("gone"), "1"), "Song Shared", emptyList(), "Band", durationMs = 200_000)
        a.track("Song Shared", artist = "Band")
        assertIs<ResolveOutcome.Miss>(resolver().resolve(ResolveRequest(orphan)))
        assertEquals(0, a.searchCalls)
    }
}
