package app.podium.sources.audius

import app.podium.core.common.Outcome
import app.podium.core.common.PodiumError
import app.podium.core.model.AlbumId
import app.podium.core.model.ArtistId
import app.podium.core.model.ArtworkRef
import app.podium.core.model.Availability
import app.podium.core.model.AudioQuality
import app.podium.core.model.Codec
import app.podium.core.model.PlaybackRoute
import app.podium.core.model.PlaylistId
import app.podium.core.model.SourceRef
import app.podium.core.model.Track
import app.podium.core.model.TrackId
import app.podium.sources.api.AlbumDetail
import app.podium.sources.api.ArtistDetail
import app.podium.sources.api.ArtistSummary
import app.podium.sources.api.ArtworkFacet
import app.podium.sources.api.ArtworkPayload
import app.podium.sources.api.Attribution
import app.podium.sources.api.Basis
import app.podium.sources.api.Capability
import app.podium.sources.api.CapabilityState
import app.podium.sources.api.CapabilityStatus
import app.podium.sources.api.CatalogFacet
import app.podium.sources.api.DiscoveryFacet
import app.podium.sources.api.FacetResolution
import app.podium.sources.api.MissReason
import app.podium.sources.api.MusicSource
import app.podium.sources.api.PlayableMedia
import app.podium.sources.api.PlaybackFacet
import app.podium.sources.api.PlaybackTarget
import app.podium.sources.api.PlaylistDetail
import app.podium.sources.api.Purpose
import app.podium.sources.api.QualityRequest
import app.podium.sources.api.RecommendationFacet
import app.podium.sources.api.SearchKind
import app.podium.sources.api.SearchQuery
import app.podium.sources.api.SearchResults
import app.podium.sources.api.Shelf
import app.podium.sources.api.ShelfPage
import app.podium.sources.api.SourceCapabilities
import app.podium.sources.api.SourceDescriptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Audius (D-34): the first online source — a public catalogue with direct MP3 streams that the
 * official API offers to third-party apps. Anonymous: search, browsing, artists, playlists,
 * recommendations and streaming. Signing in (Audius favourites and playlists on the account) needs
 * an Audius API key the user registers; until then those capabilities are reported unavailable
 * and Podium keeps online likes and playlists on the device instead.
 *
 * Gated tracks (pay-, follow- or token-gated) are marked unavailable and never resolved: Podium
 * doesn't unlock anything.
 */
class AudiusMusicSource internal constructor(
    private val api: AudiusApi,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : MusicSource {

    constructor(userAgent: String, onRequest: () -> Unit = {}) : this(AudiusApi(UrlConnectionTransport(userAgent), onRequest = onRequest))

    /** Debug-only: rehearse losing the network without touching the phone's settings. */
    var simulateOffline: Boolean
        get() = api.offline
        set(value) {
            api.offline = value
        }

    override val descriptor = SourceDescriptor(
        id = AudiusMapper.SOURCE,
        displayName = "Audius",
        providerName = "Audius",
        basis = Basis.OFFICIAL_API,
        attribution = Attribution("Music from Audius", linkUrl = "https://audius.co"),
        termsUrl = "https://audius.co/legal/terms-of-use",
        cheapResolve = true,
    )

    private val _capabilities = MutableStateFlow(
        SourceCapabilities(
            mapOf(
                Capability.SEARCH to CapabilityState.Available,
                Capability.BROWSE to CapabilityState.Available,
                Capability.DIRECT_STREAM to CapabilityState.Available,
                Capability.RECOMMENDATIONS to CapabilityState.Available,
                Capability.ARTWORK to CapabilityState.Available,
                Capability.AUTHENTICATION to CapabilityState(CapabilityStatus.UNAVAILABLE, "Signing in to Audius needs an Audius app key"),
                Capability.LIKES to CapabilityState(CapabilityStatus.UNAVAILABLE, "Saved on this device until you sign in"),
                Capability.PLAYLISTS to CapabilityState(CapabilityStatus.UNAVAILABLE, "Saved on this device until you sign in"),
                Capability.DOWNLOADS to CapabilityState.Unavailable,
            ),
        ),
    )
    override val capabilities: StateFlow<SourceCapabilities> = _capabilities

    private suspend fun <T> io(block: () -> Outcome<T>): Outcome<T> = withContext(io) { block() }

    private fun List<TrackDto>.tracks(): List<Track> = map(AudiusMapper::track)

    /** Only songs that can actually be streamed, for lists Podium plays from. */
    private fun List<Track>.playable() = filter { it.availability == Availability.Playable }

    override val catalog: CatalogFacet = object : CatalogFacet {
        override suspend fun search(query: SearchQuery): Outcome<SearchResults> {
            if (query.text.isBlank()) return Outcome.Success(SearchResults.Empty)
            return io { api.search(query.text.trim(), query.offset, query.limit) }.map { r ->
                SearchResults(
                    tracks = if (SearchKind.TRACKS in query.kinds) r.tracks.tracks() else emptyList(),
                    artists = if (SearchKind.ARTISTS in query.kinds) r.users.map(AudiusMapper::artist) else emptyList(),
                    albums = if (SearchKind.ALBUMS in query.kinds) r.albums.map(AudiusMapper::album) else emptyList(),
                    playlists = if (SearchKind.PLAYLISTS in query.kinds) r.playlists.map(AudiusMapper::playlist) else emptyList(),
                )
            }
        }

        override suspend fun track(ref: SourceRef): Outcome<Track> = io { api.track(ref.providerKey) }.map(AudiusMapper::track)

        override suspend fun album(id: AlbumId): Outcome<AlbumDetail> {
            val key = id.value.substringAfter('|')
            return playlistParts(key) { summary, tracks -> AlbumDetail(AudiusMapper.album(summary), tracks) }
        }

        override suspend fun playlist(id: PlaylistId): Outcome<PlaylistDetail> =
            playlistParts(id.providerKey) { summary, tracks -> PlaylistDetail(AudiusMapper.playlist(summary), tracks) }

        override suspend fun artist(id: ArtistId): Outcome<ArtistDetail> = coroutineScope {
            val key = id.value.substringAfter('|')
            val user = async(io) { api.user(key) }
            val tracks = async(io) { api.userTracks(key, 0, 20) }
            val albums = async(io) { api.userAlbums(key, 20) }
            val playlists = async(io) { api.userPlaylists(key, 20) }
            when (val u = user.await()) {
                is Outcome.Failure -> u
                is Outcome.Success -> Outcome.Success(
                    ArtistDetail(
                        summary = AudiusMapper.artist(u.value),
                        tracks = tracks.await().getOrDefault(emptyList()).tracks(),
                        albums = albums.await().getOrDefault(emptyList()).map(AudiusMapper::album),
                        playlists = playlists.await().getOrDefault(emptyList()).filterNot { it.is_album }.map(AudiusMapper::playlist),
                    ),
                )
            }
        }
    }

    private suspend fun <T> playlistParts(key: String, build: (PlaylistDto, List<Track>) -> T): Outcome<T> = coroutineScope {
        val head = async(io) { api.playlist(key) }
        val tracks = async(io) { api.playlistTracks(key) }
        when (val h = head.await()) {
            is Outcome.Failure -> h
            is Outcome.Success -> {
                val summary = h.value.firstOrNull() ?: return@coroutineScope Outcome.Failure(PodiumError.NotFound("playlist"))
                tracks.await().map { build(summary, it.tracks()) }
            }
        }
    }

    override val discovery: DiscoveryFacet = object : DiscoveryFacet {
        override suspend fun shelves(): Outcome<List<Shelf>> = coroutineScope {
            val week = async(io) { api.trending("week", 0, SHELF) }
            val underground = async(io) { api.underground(0, SHELF) }
            val playlists = async(io) { api.trendingPlaylists(0, SHELF) }
            val month = async(io) { api.trending("month", 0, SHELF) }
            val parts = listOf(
                week.await().map { Shelf(TRENDING_WEEK, "Trending this week", tracks = it.tracks().playable()) },
                underground.await().map { Shelf(UNDERGROUND, "Underground", tracks = it.tracks().playable()) },
                playlists.await().map { Shelf(PLAYLISTS, "Popular playlists", playlists = it.map(AudiusMapper::playlist)) },
                month.await().map { Shelf(TRENDING_MONTH, "Trending this month", tracks = it.tracks().playable()) },
            )
            val shelves = parts.filterIsInstance<Outcome.Success<Shelf>>().map { it.value }.filter { it.tracks.isNotEmpty() || it.playlists.isNotEmpty() }
            if (shelves.isEmpty()) parts.filterIsInstance<Outcome.Failure>().firstOrNull() ?: Outcome.Success(emptyList())
            else Outcome.Success(shelves)
        }

        override suspend fun shelf(id: String, offset: Int, limit: Int): Outcome<ShelfPage> = when (id) {
            TRENDING_WEEK -> io { api.trending("week", offset, limit) }.map { ShelfPage(tracks = it.tracks().playable()) }
            TRENDING_MONTH -> io { api.trending("month", offset, limit) }.map { ShelfPage(tracks = it.tracks().playable()) }
            UNDERGROUND -> io { api.underground(offset, limit) }.map { ShelfPage(tracks = it.tracks().playable()) }
            PLAYLISTS -> io { api.trendingPlaylists(offset, limit) }.map { ShelfPage(playlists = it.map(AudiusMapper::playlist)) }
            else -> Outcome.Failure(PodiumError.NotFound("shelf $id"))
        }

        override suspend fun genres(): Outcome<List<String>> = io { api.genres(20) }.map { list -> list.map { it.name } }

        override suspend fun genre(name: String, offset: Int, limit: Int): Outcome<List<Track>> =
            io { api.trending("month", offset, limit, genre = name) }.map { it.tracks().playable() }
    }

    override val recommendations: RecommendationFacet = object : RecommendationFacet {
        override suspend fun related(seeds: List<Track>, limit: Int, exclude: Set<TrackId>): Outcome<List<Track>> {
            val genre = seeds.mapNotNull { AudiusMapper.privateOf(it)?.genre }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            val leaveOut = (exclude + seeds.map { it.id }).filter { it.sourceId == AudiusMapper.SOURCE }.map { it.providerKey }.takeLast(MAX_EXCLUDED)
            val recommended = io { api.recommended(genre, leaveOut, limit) }
            val first = (recommended as? Outcome.Success)?.value?.tracks()?.playable().orEmpty()
            if (first.size >= limit || genre == null) {
                return if (first.isEmpty() && recommended is Outcome.Failure) recommended else Outcome.Success(first.filterOut(exclude))
            }
            // Topped up from what's trending in the same genre, still from Audius' own ranking.
            val more = io { api.trending("month", 0, limit * 2, genre = genre) }.getOrDefault(emptyList()).tracks().playable()
            return Outcome.Success((first + more).distinctBy { it.id }.filterOut(exclude + seeds.map { it.id }).take(limit))
        }

        override suspend fun artistRadio(artist: ArtistId, limit: Int, exclude: Set<TrackId>): Outcome<List<Track>> = coroutineScope {
            val key = artist.value.substringAfter('|')
            val own = async(io) { api.userTracks(key, 0, 12) }
            val related = io { api.relatedUsers(key, 6) }.getOrDefault(emptyList())
            val theirs = related.map { user -> async(io) { api.userTracks(user.id, 0, 4).getOrDefault(emptyList()).tracks().playable() } }.awaitAll()
            val mine = when (val o = own.await()) {
                is Outcome.Success -> o.value.tracks().playable()
                is Outcome.Failure -> if (theirs.all { it.isEmpty() }) return@coroutineScope o else emptyList()
            }
            // Interleave: the artist, someone related, the artist again… so it's their radio, not a playlist.
            val pool = theirs.flatten().toMutableList()
            val mixed = buildList {
                mine.forEach { t ->
                    add(t)
                    pool.removeFirstOrNull()?.let(::add)
                }
                addAll(pool)
            }
            Outcome.Success(mixed.distinctBy { it.id }.filterOut(exclude).take(limit))
        }

        override suspend fun relatedArtists(artist: ArtistId, limit: Int): Outcome<List<ArtistSummary>> =
            io { api.relatedUsers(artist.value.substringAfter('|'), limit) }.map { list -> list.map(AudiusMapper::artist) }
    }

    override val playback: PlaybackFacet = object : PlaybackFacet {
        override val routes = setOf(PlaybackRoute.DIRECT)

        override suspend fun resolve(track: Track, quality: QualityRequest, purpose: Purpose): FacetResolution {
            if (track.source.sourceId != AudiusMapper.SOURCE) return FacetResolution.Miss(MissReason.NOT_FOUND)
            if (track.availability is Availability.Unavailable) return FacetResolution.Miss(MissReason.NOT_PERMITTED)
            if (api.offline) return FacetResolution.Miss(MissReason.SOURCE_UNAVAILABLE)
            // The API's stream endpoint redirects to a content node; the player follows it each time.
            return FacetResolution.Resolved(
                PlaybackTarget.DirectStream(
                    track.id,
                    PlayableMedia(
                        uri = api.streamUrl(track.source.providerKey),
                        mimeType = "audio/mpeg",
                        claimedQuality = AudioQuality(codec = Codec.MP3),
                        durationMs = track.durationMs,
                        sourceId = AudiusMapper.SOURCE,
                        cacheKey = "${track.id.value}|stream",
                    ),
                ),
            )
        }
    }

    override val artwork: ArtworkFacet = object : ArtworkFacet {
        override suspend fun load(ref: ArtworkRef, sizePx: Int): ArtworkPayload? {
            if (ref.sourceId != AudiusMapper.SOURCE || !ref.key.startsWith("https://")) return null
            return when (val r = io { api.bytes(AudiusMapper.sizedArtwork(ref.key, sizePx)) }) {
                is Outcome.Success -> ArtworkPayload.Bytes(r.value, "image/jpeg")
                is Outcome.Failure -> null
            }
        }
    }

    private fun List<Track>.filterOut(exclude: Set<TrackId>) = filter { it.id !in exclude }

    companion object {
        const val TRENDING_WEEK = "trending-week"
        const val TRENDING_MONTH = "trending-month"
        const val UNDERGROUND = "underground"
        const val PLAYLISTS = "playlists"
        private const val SHELF = 20
        private const val MAX_EXCLUDED = 60
    }
}

private inline fun <T, R> Outcome<T>.map(f: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Success -> Outcome.Success(f(value))
    is Outcome.Failure -> this
}

private fun <T> Outcome<T>.getOrDefault(default: T): T = (this as? Outcome.Success)?.value ?: default
