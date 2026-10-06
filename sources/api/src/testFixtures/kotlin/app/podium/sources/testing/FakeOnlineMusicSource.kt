package app.podium.sources.testing

import app.podium.core.common.Outcome
import app.podium.core.common.PodiumError
import app.podium.core.model.AlbumId
import app.podium.core.model.ArtistId
import app.podium.core.model.PlaybackRoute
import app.podium.core.model.PlaylistId
import app.podium.core.model.SourceId
import app.podium.core.model.SourceRef
import app.podium.core.model.Track
import app.podium.core.model.TrackId
import app.podium.sources.api.AlbumDetail
import app.podium.sources.api.AlbumSummary
import app.podium.sources.api.ArtistDetail
import app.podium.sources.api.ArtistSummary
import app.podium.sources.api.Basis
import app.podium.sources.api.Capability
import app.podium.sources.api.CatalogFacet
import app.podium.sources.api.DiscoveryFacet
import app.podium.sources.api.FacetResolution
import app.podium.sources.api.HealthOutcome
import app.podium.sources.api.MissReason
import app.podium.sources.api.MusicEnvironment
import app.podium.sources.api.MusicSource
import app.podium.sources.api.PlayableMedia
import app.podium.sources.api.PlaybackFacet
import app.podium.sources.api.PlaybackTarget
import app.podium.sources.api.PlaylistDetail
import app.podium.sources.api.PlaylistSummary
import app.podium.sources.api.Purpose
import app.podium.sources.api.QualityRequest
import app.podium.sources.api.RecommendationFacet
import app.podium.sources.api.SearchQuery
import app.podium.sources.api.SearchResults
import app.podium.sources.api.Shelf
import app.podium.sources.api.ShelfPage
import app.podium.sources.api.SourceCapabilities
import app.podium.sources.api.SourceDescriptor
import app.podium.sources.api.matching.TrackNormalizer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A deterministic ONLINE source for tests (D-35) — never registered by any app build. It has a
 * catalogue, an artist, a playlist, shelves, genres and recommendations, and every call can be
 * scripted to answer, miss, fail, rate-limit or hang, so multi-source behaviour (aggregation,
 * grouping, routing, fallback, health) is provable without a second real provider.
 */
class FakeOnlineMusicSource(
    id: String,
    initial: List<Track> = emptyList(),
    capabilities: SourceCapabilities = SourceCapabilities.available(
        Capability.SEARCH, Capability.BROWSE, Capability.DIRECT_STREAM, Capability.RECOMMENDATIONS, Capability.ARTWORK,
    ),
    val artistName: String = "Fake Artist $id",
) : MusicSource {

    /** How a call answers. */
    sealed interface Behavior {
        data object Answer : Behavior
        data object Miss : Behavior
        data class Fail(val error: PodiumError) : Behavior
        /** Never answers within any sensible timeout (and ignores nothing: it's a plain delay). */
        data class Hang(val millis: Long = 60_000) : Behavior
        /** Answers, but only after [millis]. */
        data class Slow(val millis: Long) : Behavior

        /** Throws, like an adapter choking on a response it can't read. */
        data object Malformed : Behavior
    }

    val sourceId = SourceId(id)
    private val catalogTracks = LinkedHashMap<TrackId, Track>().apply { initial.forEach { put(it.id, it) } }

    var searchBehavior: Behavior = Behavior.Answer
    var detailBehavior: Behavior = Behavior.Answer
    var resolveBehavior: Behavior = Behavior.Answer
    var recommendBehavior: Behavior = Behavior.Answer

    var searchCalls = 0
        private set
    var resolveCalls = 0
        private set
    var genreCalls = 0
        private set
    val searchOffsets = mutableListOf<Int>()
    val recommendSeeds = mutableListOf<List<TrackId>>()

    /** Ids this source refuses to stream ("not streamable here"), though it lists them. */
    val unstreamable = mutableSetOf<TrackId>()

    override val descriptor = SourceDescriptor(
        sourceId,
        displayName = "Fake $id",
        providerName = "Fake",
        basis = Basis.OFFICIAL_API,
        environment = MusicEnvironment.ONLINE,
    )

    private val _capabilities = MutableStateFlow(capabilities)
    override val capabilities: StateFlow<SourceCapabilities> = _capabilities

    /** Take a capability away (as a provider, a policy or an account might). */
    fun withdraw(capability: Capability) {
        _capabilities.value = SourceCapabilities(_capabilities.value.states - capability)
    }

    val tracks: List<Track> get() = catalogTracks.values.toList()

    val artistId: ArtistId get() = ArtistId.of(sourceId, "artist")
    val playlistId: PlaylistId get() = PlaylistId.of(sourceId, "mix")

    fun track(title: String, artist: String = artistName, key: String = title.lowercase().replace(' ', '-'), durationSec: Double = 200.0, isrc: String? = null): Track =
        Track(
            id = TrackId.of(sourceId, key),
            source = SourceRef(sourceId, key),
            title = title,
            artists = listOf(app.podium.core.model.ArtistCredit(artist, id = ArtistId.of(sourceId, "artist"))),
            artistDisplay = artist,
            durationMs = (durationSec * 1000).toLong(),
            identifiers = app.podium.core.model.RecordingIdentifiers(isrc = isrc),
            routes = setOf(PlaybackRoute.DIRECT),
        ).also { catalogTracks[it.id] = it }

    /** Put [track] in the catalogue, replacing any with the same id. */
    fun put(track: Track) {
        require(track.source.sourceId == sourceId)
        catalogTracks[track.id] = track
    }

    private suspend fun <T> behave(behavior: Behavior, miss: () -> Outcome<T>, answer: () -> Outcome<T>): Outcome<T> = when (behavior) {
        Behavior.Answer -> answer()
        Behavior.Miss -> miss()
        is Behavior.Fail -> Outcome.Failure(behavior.error)
        is Behavior.Hang -> {
            delay(behavior.millis)
            answer()
        }
        Behavior.Malformed -> throw IllegalStateException("malformed answer")
        is Behavior.Slow -> {
            delay(behavior.millis)
            answer()
        }
    }

    private fun matching(text: String): List<Track> {
        val words = TrackNormalizer.identityKey(text).split(' ').filter { it.isNotEmpty() }
        return catalogTracks.values.filter { t ->
            val hay = TrackNormalizer.identityKey("${t.title} ${t.artistDisplay}").split(' ')
            words.all { it in hay }
        }
    }

    override val catalog: CatalogFacet = object : CatalogFacet {
        override suspend fun search(query: SearchQuery): Outcome<SearchResults> {
            searchCalls++
            searchOffsets += query.offset
            return behave(searchBehavior, { Outcome.Success(SearchResults.Empty) }) {
                val hits = matching(query.text).drop(query.offset).take(query.limit)
                val artists = if (artistName.lowercase().contains(query.text.trim().lowercase())) {
                    listOf(ArtistSummary(artistId, artistName))
                } else emptyList()
                Outcome.Success(SearchResults(tracks = hits, artists = artists))
            }
        }

        override suspend fun track(ref: SourceRef): Outcome<Track> =
            catalogTracks[TrackId.of(ref.sourceId, ref.providerKey)]?.let { Outcome.Success(it) } ?: Outcome.Failure(PodiumError.NotFound(ref.providerKey))

        override suspend fun album(id: AlbumId): Outcome<AlbumDetail> = behave(detailBehavior, { Outcome.Failure(PodiumError.NotFound("album")) }) {
            Outcome.Success(AlbumDetail(AlbumSummary(id, "Album of ${sourceId.value}", artistName), tracks))
        }

        override suspend fun artist(id: ArtistId): Outcome<ArtistDetail> = behave(detailBehavior, { Outcome.Failure(PodiumError.NotFound("artist")) }) {
            if (id.sourceId != sourceId) Outcome.Failure(PodiumError.NotFound("artist")) // never answers for another source's id
            else Outcome.Success(ArtistDetail(ArtistSummary(id, artistName), albums = emptyList(), tracks = tracks))
        }

        override suspend fun playlist(id: PlaylistId): Outcome<PlaylistDetail> = behave(detailBehavior, { Outcome.Failure(PodiumError.NotFound("playlist")) }) {
            if (id.sourceId != sourceId) Outcome.Failure(PodiumError.NotFound("playlist"))
            else Outcome.Success(PlaylistDetail(PlaylistSummary(id, "Mix of ${sourceId.value}", artistName), tracks))
        }
    }

    override val discovery: DiscoveryFacet = object : DiscoveryFacet {
        override suspend fun shelves(): Outcome<List<Shelf>> = behave(searchBehavior, { Outcome.Success(emptyList()) }) {
            Outcome.Success(listOf(Shelf("trending", "Trending", tracks = tracks.take(SHELF_PAGE)), Shelf("own-${sourceId.value}", "Only on ${sourceId.value}", tracks = tracks.takeLast(1))))
        }

        override suspend fun shelf(id: String, offset: Int, limit: Int): Outcome<ShelfPage> = behave(searchBehavior, { Outcome.Success(ShelfPage()) }) {
            when (id) {
                "trending" -> Outcome.Success(ShelfPage(tracks = tracks.drop(offset).take(limit)))
                "own-${sourceId.value}" -> Outcome.Success(ShelfPage(tracks = tracks.takeLast(1).drop(offset)))
                else -> Outcome.Failure(PodiumError.NotFound("shelf $id"))
            }
        }

        override suspend fun genres(): Outcome<List<String>> = behave(searchBehavior, { Outcome.Success(emptyList()) }) {
            Outcome.Success(listOf("Electronic", "Pop ${sourceId.value}"))
        }

        override suspend fun genre(name: String, offset: Int, limit: Int): Outcome<List<Track>> {
            genreCalls++
            return behave(searchBehavior, { Outcome.Success(emptyList()) }) { Outcome.Success(tracks.drop(offset).take(limit)) }
        }
    }

    override val recommendations: RecommendationFacet = object : RecommendationFacet {
        override suspend fun related(seeds: List<Track>, limit: Int, exclude: Set<TrackId>): Outcome<List<Track>> {
            recommendSeeds += seeds.map { it.id }
            return behave(recommendBehavior, { Outcome.Success(emptyList()) }) {
                Outcome.Success(tracks.filter { it.id !in exclude && seeds.none { s -> s.id == it.id } }.take(limit))
            }
        }

        override suspend fun artistRadio(artist: ArtistId, limit: Int, exclude: Set<TrackId>): Outcome<List<Track>> =
            behave(recommendBehavior, { Outcome.Success(emptyList()) }) { Outcome.Success(tracks.filter { it.id !in exclude }.take(limit)) }

        override suspend fun relatedArtists(artist: ArtistId, limit: Int): Outcome<List<ArtistSummary>> =
            behave(recommendBehavior, { Outcome.Success(emptyList()) }) { Outcome.Success(listOf(ArtistSummary(ArtistId.of(sourceId, "friend"), "Friend of ${sourceId.value}"))) }
    }

    override val playback: PlaybackFacet = object : PlaybackFacet {
        override val routes = setOf(PlaybackRoute.DIRECT)

        override suspend fun resolve(track: Track, quality: QualityRequest, purpose: Purpose): FacetResolution {
            resolveCalls++
            return when (val b = resolveBehavior) {
                Behavior.Miss -> FacetResolution.Miss(MissReason.NOT_FOUND)
                Behavior.Malformed -> throw IllegalStateException("malformed answer")
                is Behavior.Fail -> FacetResolution.Failed(
                    when (b.error) {
                        is PodiumError.RateLimited -> HealthOutcome.RATE_LIMIT
                        is PodiumError.Server -> HealthOutcome.SERVER_ERROR
                        else -> HealthOutcome.NETWORK_FAILURE
                    },
                )
                is Behavior.Hang -> {
                    delay(b.millis)
                    FacetResolution.Failed(HealthOutcome.NETWORK_FAILURE)
                }
                else -> {
                    if (b is Behavior.Slow) delay(b.millis)
                    when {
                        track.id !in catalogTracks -> FacetResolution.Miss(MissReason.NOT_FOUND)
                        track.id in unstreamable -> FacetResolution.Miss(MissReason.NOT_STREAMABLE)
                        else -> FacetResolution.Resolved(
                            PlaybackTarget.DirectStream(
                                track.id,
                                PlayableMedia(uri = "fake://${sourceId.value}/${track.id.providerKey}", sourceId = sourceId, cacheKey = "${track.id.value}|max"),
                            ),
                        )
                    }
                }
            }
        }
    }

    companion object {
        const val SHELF_PAGE = 2
    }
}
