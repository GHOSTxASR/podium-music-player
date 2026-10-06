package app.podium.sources.subsonic

import app.podium.core.common.ManualClock
import app.podium.core.model.SourceId
import app.podium.core.model.Track
import app.podium.sources.api.FacetResolution
import app.podium.sources.api.InMemoryCredentialStore
import app.podium.sources.api.MissReason
import app.podium.sources.api.MusicEnvironment
import app.podium.sources.api.ResolutionPath
import app.podium.sources.api.SearchQuery
import app.podium.sources.api.SourceHealth
import app.podium.sources.api.SourceHealthMonitor
import app.podium.sources.api.SourceProfile
import app.podium.sources.api.SourceRegistry
import app.podium.sources.api.aggregate.MultiSourceCatalog
import app.podium.sources.api.aggregate.SourceFanOut
import app.podium.sources.api.aggregate.TrackGrouper
import app.podium.sources.api.matching.MatchTier
import app.podium.sources.api.matching.TrackMatcher
import app.podium.sources.api.resolve.InMemoryEquivalenceStore
import app.podium.sources.api.resolve.ResolveOutcome
import app.podium.sources.api.resolve.ResolveRequest
import app.podium.sources.api.resolve.StreamResolver
import app.podium.sources.testing.FakeMusicSource
import app.podium.sources.testing.FakeOnlineMusicSource
import app.podium.sources.testing.track
import app.podium.player.api.RecommendationRequest
import app.podium.player.api.SourceRecommendationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A music server next to another online source and the local library (D-35..D-37): one search,
 * one song per recording, priority, fallback within ONLINE only, health, autoplay.
 */
class SubsonicMultiSourceTest {
    private val clock = ManualClock(1_000_000)
    private val health = SourceHealthMonitor(clock)
    private val registry = SourceRegistry(health)
    private val equivalence = InMemoryEquivalenceStore()
    private val server = FakeSubsonicServer()
    private val serverId = SourceId("opensubsonic-home")

    private val home = SubsonicMusicSource(
        SourceProfile(serverId, SubsonicSourceFactory.KIND, "Navidrome (home)", mapOf("address" to "https://home.lan", "username" to server.user)),
        InMemoryCredentialStore().apply { write(serverId, mapOf("password" to server.password)) },
        server,
    )

    /** Another online source with the same recording (same ISRC), a studio take only, and its own songs. */
    private val other = FakeOnlineMusicSource("other").apply {
        track("Song Shared", artist = "Band", key = "shared", durationSec = 201.0, isrc = "US-ABC-26-00001")
        track("Song Live", artist = "Band", key = "studio")
        track("Song Gamma", artist = "Band", key = "gamma")
    }

    private val libraryFile = track("Song Shared", "Band", source = "phone", key = "file", isrc = "USABC2600001")
    private val library = FakeMusicSource("phone", listOf(libraryFile))

    init {
        registry.register(library)
        registry.register(home)
        registry.register(other)
    }

    private fun TestScope.catalog() = MultiSourceCatalog(
        registry, MusicEnvironment.ONLINE,
        SourceFanOut(health, 5_000, CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))),
        TrackGrouper(TrackMatcher(), equivalence::isRejected), equivalence,
    )

    private val resolver = StreamResolver(registry, health, TrackMatcher(), clock, equivalence = equivalence)

    private suspend fun TestScope.search(text: String) = catalog().search(SearchQuery(text)).last()

    @Test
    fun `one search covers the server and the other source, each song once`() = runTest {
        val result = search("song")
        val titles = result.results.tracks.map { it.title }
        assertEquals(titles.distinct(), titles)
        assertTrue(listOf("Song Shared", "Song Alpha", "Song Gamma").all { it in titles })
        val shared = result.groups.single { it.track.title == "Song Shared" }
        assertEquals(serverId, shared.track.source.sourceId, "the server comes first by registration order")
        assertEquals(listOf(SourceId("other")), shared.alternates.map { it.source.sourceId })
        assertEquals(MatchTier.EXACT, shared.decisions.values.single().tier, "same ISRC: the same recording")
        assertTrue(result.results.tracks.none { it.source.sourceId == library.sourceId }, "the library never answers an online search")
    }

    @Test
    fun `a live take and a studio take are two songs, whatever the provider`() = runTest {
        val result = search("live")
        assertEquals(2, result.results.tracks.size)
        assertTrue(result.groups.all { it.alternates.isEmpty() })
    }

    @Test
    fun `priority decides which copy a row shows, never what is visible`() = runTest {
        registry.setPriority(listOf(SourceId("other"), serverId))
        val result = search("song")
        assertEquals(SourceId("other"), result.groups.single { it.track.title == "Song Shared" }.track.source.sourceId)
        assertTrue("Song Alpha" in result.results.tracks.map { it.title }, "the server's own songs stay visible")
    }

    @Test
    fun `a song gone from the server plays the other source's exact copy and the server stays healthy`() = runTest {
        val shared = search("shared").groups.single().track
        server.missingSongs += "s1"
        val outcome = assertIs<ResolveOutcome.Resolved>(resolver.resolve(ResolveRequest(shared)))
        assertEquals(SourceId("other"), outcome.selection.servedBy)
        assertEquals(ResolutionPath.EXACT_FALLBACK, outcome.selection.path)
        assertEquals(SourceHealth.Healthy, health.health(serverId), "a miss isn't a failure")
    }

    @Test
    fun `a server that's down counts against its health and the other copy plays`() = runTest {
        val shared = search("shared").groups.single().track
        server.unreachable = true
        val outcome = assertIs<ResolveOutcome.Resolved>(resolver.resolve(ResolveRequest(shared)))
        assertEquals(SourceId("other"), outcome.selection.servedBy)
        assertIs<SourceHealth.Degraded>(health.health(serverId))
    }

    @Test
    fun `a song chosen online never becomes the library's file, even with the same ISRC`() = runTest {
        val shared = search("shared").groups.single().track
        server.missingSongs += "s1"
        registry.setEnabled(SourceId("other"), false)
        assertIs<ResolveOutcome.Miss>(resolver.resolve(ResolveRequest(shared)))
        assertEquals(0, library.resolveCalls)
        assertEquals(0, library.searchCalls)
    }

    @Test
    fun `a song chosen from the library never becomes the server's stream`() = runTest {
        val file = libraryFile
        library.outcomes[file.id] = FacetResolution.Miss(MissReason.NOT_FOUND)
        assertIs<ResolveOutcome.Miss>(resolver.resolve(ResolveRequest(file)))
        assertTrue(server.methods.none { it == "search3" || it == "getSong" })
    }

    @Test
    fun `turning the server off removes it from search, and on again brings it back`() = runTest {
        registry.setEnabled(serverId, false)
        assertTrue(search("song").results.tracks.none { it.source.sourceId == serverId })
        registry.setEnabled(serverId, true)
        assertTrue(search("song").results.tracks.any { it.source.sourceId == serverId })
    }

    @Test
    fun `autoplay asks every online source about the songs it holds, never the library`() = runTest {
        val shared = search("shared").groups.single()
        val engine = SourceRecommendationEngine(registry, equivalence::exactEquivalents)
        val picks = engine.recommend(RecommendationRequest(listOf(shared.track), setOf(shared.track.id), 10))
        assertTrue(picks.any { it.source.sourceId == serverId }, "the server's similar songs")
        assertTrue(picks.any { it.source.sourceId == SourceId("other") }, "the other source, asked about its own copy")
        assertTrue(picks.none { it.source.sourceId == library.sourceId })
        assertEquals(listOf("shared"), other.recommendSeeds.single().map { it.providerKey })
    }

    @Test
    fun `a broken source doesn't stop the others' suggestions`() = runTest {
        val shared = search("shared").groups.single()
        server.httpStatus = 500
        val picks = SourceRecommendationEngine(registry, equivalence::exactEquivalents)
            .recommend(RecommendationRequest(listOf(shared.track), setOf(shared.track.id), 10))
        assertTrue(picks.isNotEmpty())
        assertTrue(picks.all { it.source.sourceId == SourceId("other") })
    }

    @Test
    fun `a close but unproven match from the server is never grouped or remembered`() = runTest {
        other.track("Song Alpha", artist = "Band", key = "alpha-long", durationSec = 205.0) // 5 s longer, no ids: STRONG at best
        val result = search("alpha")
        assertEquals(2, result.results.tracks.size)
        val serverAlpha: Track = result.results.tracks.first { it.source.sourceId == serverId }
        assertTrue(equivalence.exactEquivalents(serverAlpha).isEmpty())
    }
}
