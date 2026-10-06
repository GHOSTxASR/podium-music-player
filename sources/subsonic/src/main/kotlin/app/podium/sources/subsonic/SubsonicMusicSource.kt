package app.podium.sources.subsonic

import app.podium.core.common.Outcome
import app.podium.core.common.PodiumError
import app.podium.core.model.AlbumId
import app.podium.core.model.ArtistId
import app.podium.core.model.ArtworkRef
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
import app.podium.sources.api.AuthFacet
import app.podium.sources.api.AuthState
import app.podium.sources.api.Basis
import app.podium.sources.api.Capability
import app.podium.sources.api.CapabilityAction
import app.podium.sources.api.CapabilityState
import app.podium.sources.api.CapabilityStatus
import app.podium.sources.api.CatalogFacet
import app.podium.sources.api.CredentialStore
import app.podium.sources.api.DiscoveryFacet
import app.podium.sources.api.FacetResolution
import app.podium.sources.api.HealthOutcome
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
import app.podium.sources.api.SetupField
import app.podium.sources.api.SetupProblem
import app.podium.sources.api.Shelf
import app.podium.sources.api.ShelfPage
import app.podium.sources.api.SignInResult
import app.podium.sources.api.SourceCapabilities
import app.podium.sources.api.SourceDescriptor
import app.podium.sources.api.SourceProfile
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A music server the listener added that speaks (Open)Subsonic — Navidrome, Gonic, Airsonic-Advanced,
 * Ampache, LMS… (D-37). The listener's own music, streamed with their own credentials through the
 * server's official API: search, artists, albums, playlists, genres, the server's similar-songs
 * (when it has the data), artwork and direct streams. It is an ONLINE source like any other; nothing
 * outside this module knows it is a "Subsonic" server.
 *
 * Signed out (credentials removed or refused), every capability reports REQUIRES_SIGN_IN and the
 * catalogue answers nothing; the source stays configured until it's removed.
 */
class SubsonicMusicSource internal constructor(
    private val profile: SourceProfile,
    private val credentials: CredentialStore,
    transport: HttpTransport,
) : MusicSource {

    constructor(profile: SourceProfile, credentials: CredentialStore, userAgent: String) :
        this(profile, credentials, UrlConnectionTransport(userAgent))

    private val username = profile.settings[KEY_USERNAME].orEmpty()
    private val mapper = SubsonicMapper(profile.id)
    private val api = SubsonicApi(profile.settings[KEY_ADDRESS].orEmpty(), transport, ::stored)

    private fun stored(): ServerCredentials? =
        credentials.read(profile.id)?.get(KEY_PASSWORD)?.let { ServerCredentials(username, it) }

    override val descriptor = SourceDescriptor(
        id = profile.id,
        displayName = profile.displayName,
        providerName = "OpenSubsonic",
        basis = Basis.USER_SERVER,
        cheapResolve = true,
    )

    private val _auth = MutableStateFlow<AuthState>(if (stored() != null) AuthState.SignedIn(username) else AuthState.SignedOut)
    private val _capabilities = MutableStateFlow(capabilitiesFor(_auth.value))
    override val capabilities: StateFlow<SourceCapabilities> = _capabilities.asStateFlow()

    private fun setAuth(state: AuthState) {
        _auth.value = state
        _capabilities.value = capabilitiesFor(state)
    }

    private fun capabilitiesFor(state: AuthState): SourceCapabilities {
        val usable = state is AuthState.SignedIn
        val signIn = CapabilityState(CapabilityStatus.REQUIRES_SIGN_IN, "Sign in to this music server", CapabilityAction.SignIn("Sign in"))
        fun gated(available: CapabilityState) = if (usable) available else signIn
        return SourceCapabilities(
            mapOf(
                Capability.SEARCH to gated(CapabilityState.Available),
                Capability.BROWSE to gated(CapabilityState.Available),
                Capability.DIRECT_STREAM to gated(CapabilityState.Available),
                Capability.ARTWORK to gated(CapabilityState.Available),
                // Similar songs and artists come from the server's metadata agents, which may be off.
                Capability.RECOMMENDATIONS to gated(CapabilityState(CapabilityStatus.DEGRADED, "Depends on what the server knows")),
                Capability.AUTHENTICATION to CapabilityState.Available,
                Capability.LIKES to CapabilityState(CapabilityStatus.UNAVAILABLE, "Saved on this device"),
                Capability.PLAYLISTS to CapabilityState(CapabilityStatus.UNAVAILABLE, "The server's playlists can be played, not edited"),
                Capability.DOWNLOADS to CapabilityState(CapabilityStatus.UNAVAILABLE, "Downloads come later"),
            ),
        )
    }

    /** A call answered "wrong credentials": the source is signed in no more until the listener acts. */
    private fun <T> Outcome<T>.watchAuth(): Outcome<T> {
        if (this is Outcome.Failure && error is PodiumError.AuthRequired && _auth.value is AuthState.SignedIn) {
            setAuth(AuthState.Rejected("The server refused the sign-in"))
        }
        return this
    }

    private fun <T, R> Outcome<T>.map(f: (T) -> R): Outcome<R> = when (this) {
        is Outcome.Success -> Outcome.Success(f(value))
        is Outcome.Failure -> this
    }

    // --- Signing in -----------------------------------------------------------------------------------

    override val auth: AuthFacet = object : AuthFacet {
        override val state: StateFlow<AuthState> = _auth.asStateFlow()

        override val signInFields = listOf(SetupField(KEY_PASSWORD, "Password", kind = SetupField.Kind.SECRET))

        override suspend fun signIn(values: Map<String, String>): SignInResult {
            val password = values[KEY_PASSWORD]?.takeIf { it.isNotEmpty() } ?: return SignInResult.Refused(SetupProblem.MISSING_FIELD)
            setAuth(AuthState.SigningIn)
            return when (val r = api.ping(ServerCredentials(username, password))) {
                is Outcome.Success -> {
                    credentials.write(profile.id, mapOf(KEY_PASSWORD to password))
                    setAuth(AuthState.SignedIn(username))
                    SignInResult.SignedIn
                }
                is Outcome.Failure -> {
                    setAuth(if (stored() != null) AuthState.Rejected("The server refused the sign-in") else AuthState.SignedOut)
                    SignInResult.Refused(problemOf(r.error))
                }
            }
        }

        override suspend fun signOut() {
            credentials.delete(profile.id)
            setAuth(AuthState.SignedOut)
        }
    }

    // --- Catalogue ------------------------------------------------------------------------------------

    override val catalog: CatalogFacet = object : CatalogFacet {
        override suspend fun search(query: SearchQuery): Outcome<SearchResults> {
            if (query.text.isBlank()) return Outcome.Success(SearchResults.Empty)
            val firstPage = query.offset == 0
            return api.search(
                query.text.trim(),
                songCount = if (SearchKind.TRACKS in query.kinds) query.limit else 0,
                songOffset = query.offset,
                albumCount = if (firstPage && SearchKind.ALBUMS in query.kinds) query.limit else 0,
                artistCount = if (firstPage && SearchKind.ARTISTS in query.kinds) query.limit else 0,
            ).watchAuth().map { r ->
                SearchResults(
                    tracks = r.song.map(mapper::track),
                    albums = r.album.map(mapper::album),
                    artists = r.artist.map(mapper::artist),
                )
            }
        }

        override suspend fun track(ref: SourceRef): Outcome<Track> = api.song(ref.providerKey).watchAuth().map(mapper::track)

        override suspend fun album(id: AlbumId): Outcome<AlbumDetail> =
            api.album(id.value.substringAfter('|').removePrefix(SubsonicMapper.ALBUM)).watchAuth()
                .map { a -> AlbumDetail(mapper.album(a), a.song.map(mapper::track)) }

        override suspend fun artist(id: ArtistId): Outcome<ArtistDetail> = coroutineScope {
            val artist = when (val r = api.artist(id.value.substringAfter('|')).watchAuth()) {
                is Outcome.Failure -> return@coroutineScope r
                is Outcome.Success -> r.value
            }
            // The server's popular songs for the artist need its metadata agents; without them, the
            // first album's songs stand in so the page always has music.
            val top = (api.topSongs(artist.name, ARTIST_SONGS) as? Outcome.Success)?.value.orEmpty()
            val songs = top.ifEmpty {
                artist.album.firstOrNull()?.let { (api.album(it.id) as? Outcome.Success)?.value?.song }.orEmpty()
            }
            Outcome.Success(
                ArtistDetail(
                    summary = mapper.artist(artist).copy(trackCount = null),
                    albums = artist.album.map(mapper::album),
                    tracks = songs.map(mapper::track),
                ),
            )
        }

        override suspend fun playlist(id: PlaylistId): Outcome<PlaylistDetail> {
            val key = id.providerKey
            return when {
                key.startsWith(SubsonicMapper.ALBUM) -> api.album(key.removePrefix(SubsonicMapper.ALBUM)).watchAuth()
                    .map { a -> PlaylistDetail(mapper.albumAsPlaylist(a), a.song.map(mapper::track)) }
                key.startsWith(SubsonicMapper.PLAYLIST) -> api.playlist(key.removePrefix(SubsonicMapper.PLAYLIST)).watchAuth()
                    .map { p -> PlaylistDetail(mapper.playlist(p), p.entry.map(mapper::track)) }
                else -> Outcome.Failure(PodiumError.NotFound("playlist"))
            }
        }
    }

    // --- Discovery ------------------------------------------------------------------------------------

    override val discovery: DiscoveryFacet = object : DiscoveryFacet {
        override suspend fun shelves(): Outcome<List<Shelf>> = coroutineScope {
            val newest = async { api.albumList("newest", SHELF, 0) }
            val random = async { api.randomSongs(SHELF) }
            val frequent = async { api.albumList("frequent", SHELF, 0) }
            val lists = async { api.playlists() }
            val parts = listOf(
                newest.await().map { Shelf(NEWEST, "Recently added", playlists = it.map(mapper::albumAsPlaylist)) },
                random.await().map { Shelf(RANDOM, "Random picks", tracks = it.map(mapper::track)) },
                frequent.await().map { Shelf(FREQUENT, "Most played", playlists = it.map(mapper::albumAsPlaylist)) },
                lists.await().map { Shelf(PLAYLISTS, "Server playlists", playlists = it.map(mapper::playlist)) },
            ).map { it.watchAuth() }
            val shelves = parts.filterIsInstance<Outcome.Success<Shelf>>().map { it.value }.filter { it.tracks.isNotEmpty() || it.playlists.isNotEmpty() }
            if (shelves.isEmpty()) parts.filterIsInstance<Outcome.Failure>().firstOrNull() ?: Outcome.Success(emptyList())
            else Outcome.Success(shelves)
        }

        override suspend fun shelf(id: String, offset: Int, limit: Int): Outcome<ShelfPage> = when (id) {
            NEWEST -> api.albumList("newest", limit, offset).watchAuth().map { ShelfPage(playlists = it.map(mapper::albumAsPlaylist)) }
            FREQUENT -> api.albumList("frequent", limit, offset).watchAuth().map { ShelfPage(playlists = it.map(mapper::albumAsPlaylist)) }
            // Random has no pages; each page is a fresh draw (the screen drops repeats).
            RANDOM -> api.randomSongs(limit).watchAuth().map { ShelfPage(tracks = it.map(mapper::track)) }
            PLAYLISTS -> if (offset > 0) Outcome.Success(ShelfPage()) else api.playlists().watchAuth().map { ShelfPage(playlists = it.map(mapper::playlist)) }
            else -> Outcome.Failure(PodiumError.NotFound("shelf $id"))
        }

        override suspend fun genres(): Outcome<List<String>> =
            api.genres().watchAuth().map { list -> list.filter { it.value.isNotBlank() }.sortedByDescending { it.songCount }.map { it.value } }

        override suspend fun genre(name: String, offset: Int, limit: Int): Outcome<List<Track>> =
            api.songsByGenre(name, limit, offset).watchAuth().map { it.map(mapper::track) }
    }

    // --- Recommendations (from the server's own similarity data) --------------------------------------

    override val recommendations: RecommendationFacet = object : RecommendationFacet {
        override suspend fun related(seeds: List<Track>, limit: Int, exclude: Set<TrackId>): Outcome<List<Track>> {
            val seed = seeds.lastOrNull { it.source.sourceId == profile.id } ?: return Outcome.Success(emptyList())
            val leaveOut = exclude + seeds.map { it.id }
            return api.similarSongs(seed.source.providerKey, limit + leaveOut.size).watchAuth()
                .map { songs -> songs.map(mapper::track).filter { it.id !in leaveOut }.take(limit) }
        }

        override suspend fun artistRadio(artist: ArtistId, limit: Int, exclude: Set<TrackId>): Outcome<List<Track>> =
            api.similarSongs2(artist.value.substringAfter('|'), limit + exclude.size).watchAuth()
                .map { songs -> songs.map(mapper::track).filter { it.id !in exclude }.take(limit) }

        override suspend fun relatedArtists(artist: ArtistId, limit: Int): Outcome<List<ArtistSummary>> =
            api.artistInfo(artist.value.substringAfter('|'), limit).watchAuth().map { it.map(mapper::artist) }
    }

    // --- Artwork --------------------------------------------------------------------------------------

    override val artwork: ArtworkFacet = object : ArtworkFacet {
        override suspend fun load(ref: ArtworkRef, sizePx: Int): ArtworkPayload? {
            if (ref.sourceId != profile.id) return null
            return when (val r = api.coverArt(ref.key, sizePx)) {
                is Outcome.Success -> ArtworkPayload.Bytes(r.value.body, r.value.contentType?.substringBefore(';') ?: "image/jpeg")
                is Outcome.Failure -> null
            }
        }
    }

    // --- Playback -------------------------------------------------------------------------------------

    override val playback: PlaybackFacet = object : PlaybackFacet {
        override val routes = setOf(PlaybackRoute.DIRECT)

        /**
         * Ask the server about the song first: gone (70) is a miss — the server works, it just doesn't
         * have it — while a refused sign-in or a dead server is a failure. Then a stream URL with
         * fresh authentication; the player fetches it directly.
         */
        override suspend fun resolve(track: Track, quality: QualityRequest, purpose: Purpose): FacetResolution {
            if (track.source.sourceId != profile.id) return FacetResolution.Miss(MissReason.NOT_FOUND)
            val song = when (val r = api.song(track.source.providerKey).watchAuth()) {
                is Outcome.Success -> r.value
                is Outcome.Failure -> return failureOf(r.error)
            }
            if (song.isVideo) return FacetResolution.Miss(MissReason.NOT_STREAMABLE)
            val maxKbps = (quality as? QualityRequest.Capped)?.maxKbps
            val url = api.url("stream", listOfNotNull("id" to song.id, maxKbps?.let { "maxBitRate" to "$it" }))
                ?: return FacetResolution.Failed(HealthOutcome.AUTH_FAILURE, "signed out")
            return FacetResolution.Resolved(
                PlaybackTarget.DirectStream(
                    track.id,
                    PlayableMedia(
                        uri = url,
                        mimeType = if (maxKbps == null) song.contentType else null, // a transcode may change it
                        claimedQuality = if (maxKbps == null) mapper.quality(song) else null,
                        durationMs = song.duration?.takeIf { it > 0 }?.let { it * 1000L },
                        sourceId = profile.id,
                        cacheKey = "${track.id.value}|${maxKbps ?: "original"}",
                    ),
                ),
            )
        }
    }

    private fun failureOf(error: PodiumError): FacetResolution = when (error) {
        is PodiumError.NotFound -> FacetResolution.Miss(MissReason.NOT_FOUND)
        is PodiumError.AuthRequired -> FacetResolution.Failed(HealthOutcome.AUTH_FAILURE, "sign-in refused")
        is PodiumError.RateLimited -> FacetResolution.Failed(HealthOutcome.RATE_LIMIT, retryAfterMillis = error.retryAfterMillis)
        is PodiumError.Server -> FacetResolution.Failed(HealthOutcome.SERVER_ERROR)
        is PodiumError.Network, PodiumError.Offline -> FacetResolution.Failed(HealthOutcome.NETWORK_FAILURE)
        else -> FacetResolution.Failed(HealthOutcome.UNKNOWN)
    }

    companion object {
        const val KEY_ADDRESS = "address"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"

        const val NEWEST = "newest"
        const val RANDOM = "random"
        const val FREQUENT = "frequent"
        const val PLAYLISTS = "playlists"
        private const val SHELF = 20
        private const val ARTIST_SONGS = 20

        internal fun problemOf(error: PodiumError): SetupProblem = when (error) {
            is PodiumError.AuthRequired -> if (error.detail == "authentication not supported") SetupProblem.NOT_SUPPORTED else SetupProblem.WRONG_CREDENTIALS
            is PodiumError.PolicyDisabled -> SetupProblem.NOT_SUPPORTED
            is PodiumError.Network, PodiumError.Offline -> SetupProblem.UNREACHABLE
            is PodiumError.NotFound -> SetupProblem.NOT_SUPPORTED // no /rest API at that address
            else -> SetupProblem.UNKNOWN
        }
    }
}
