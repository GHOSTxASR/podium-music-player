package app.podium.sources.youtubemusic

import app.podium.core.common.Outcome
import app.podium.core.common.PodiumError
import app.podium.core.common.map
import app.podium.core.model.AlbumId
import app.podium.core.model.AlbumRef
import app.podium.core.model.ArtistId
import app.podium.core.model.ArtworkRef
import app.podium.core.model.MediaKind
import app.podium.core.model.PlaybackRoute
import app.podium.core.model.PlaylistId
import app.podium.core.model.SourceId
import app.podium.core.model.SourceRef
import app.podium.core.model.Track
import app.podium.core.model.TrackId
import app.podium.sources.api.AccountIdentity
import app.podium.sources.api.AccountLibraryFacet
import app.podium.sources.api.AlbumDetail
import app.podium.sources.api.AlbumSummary
import app.podium.sources.api.ArtistDetail
import app.podium.sources.api.ArtistSummary
import app.podium.sources.api.ArtworkFacet
import app.podium.sources.api.ArtworkPayload
import app.podium.sources.api.Attribution
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
import app.podium.sources.api.HistoryEntry
import app.podium.sources.api.LiveRadioFacet
import app.podium.sources.api.MissReason
import app.podium.sources.api.MusicEnvironment
import app.podium.sources.api.MusicSource
import app.podium.sources.api.PlaybackFacet
import app.podium.sources.api.PlaybackTarget
import app.podium.sources.api.PlaylistDetail
import app.podium.sources.api.PlaylistSummary
import app.podium.sources.api.Purpose
import app.podium.sources.api.QualityRequest
import app.podium.sources.api.RecommendationFacet
import app.podium.sources.api.SearchKind
import app.podium.sources.api.SearchQuery
import app.podium.sources.api.SearchResults
import app.podium.sources.api.SetupProblem
import app.podium.sources.api.Shelf
import app.podium.sources.api.ShelfPage
import app.podium.sources.api.SignInResult
import app.podium.sources.api.SourceCapabilities
import app.podium.sources.api.SourceDescriptor
import app.podium.sources.api.WebSignIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * YouTube Music as Podium's ONLINE source (YOUTUBE_MUSIC_ARCHITECTURE.md). One module, every
 * provider detail inside it:
 *
 * - **Catalogue** (search, home, albums, artists, playlists, radio) from the music web client's
 *   browsing endpoints — `Basis.UNOFFICIAL_API` (D-19, D-20 Y1 approved 2026-10-06).
 * - **Account** (liked songs, library, history, likes): the listener's own web session, signed in on
 *   Google's page, sealed at rest by the app's [CredentialStore] (§6).
 * - **Playback** is Podium's own (D-48): a song resolves to a [PlaybackTarget.DirectStream] through
 *   [YouTubeMusicStreamResolver], without the listener's session, and plays in Media3 like local
 *   music — queue, background playback, notification and lock-screen controls included.
 *
 * Nothing outside this module knows any of that: the rest of Podium sees facets and capabilities.
 */
class YouTubeMusicSource internal constructor(
    private val credentials: CredentialStore,
    transport: HttpTransport,
    userAgent: () -> String,
    region: () -> String,
    private val now: () -> Long,
    streamResolver: YouTubeMusicStreamResolver? = null,
    streamMemory: StreamWalkMemory = StreamWalkMemory.None,
    scriptEngine: ScriptEngine? = null,
    scriptCache: PlayerScriptCache? = null,
) : MusicSource {

    /**
     * [userAgent] is the device's own web browser user agent (the same one Google's sign-in page
     * saw); [region] the listener's country; [streamMemory] keeps the app that last served a stream
     * across restarts; [scriptEngine] and [scriptCache] run and keep YouTube's player script for the
     * backup apps that need it (D-98).
     */
    constructor(
        credentials: CredentialStore,
        userAgent: () -> String,
        region: () -> String = { "US" },
        streamMemory: StreamWalkMemory = StreamWalkMemory.None,
        scriptEngine: ScriptEngine? = null,
        scriptCache: PlayerScriptCache? = null,
    ) : this(
        credentials, UrlConnectionTransport(), userAgent, region, System::currentTimeMillis,
        streamMemory = streamMemory, scriptEngine = scriptEngine, scriptCache = scriptCache,
    )

    private val id = SOURCE_ID
    private val mapper = YouTubeMusicMapper(id)
    private val client = YouTubeMusicClient(transport, userAgent, ::currentSession, now, region = region, onBehalfOf = ::currentPageId)
    private val cursors = CursorCache()
    private val liveSearch = YouTubeLiveSearch(transport, userAgent, region)
    private val resolver = streamResolver ?: YouTubeMusicStreamResolver(
        sourceId = id,
        downloader = YouTubeMusicDownloader(userAgent),
        region = region,
        now = now,
        memory = streamMemory,
        scriptEngine = scriptEngine,
        scriptCache = scriptCache,
    )

    @Volatile private var session: WebSession? = credentials.read(id)?.get(KEY_SESSION)?.let(WebSession::parse)

    /** The channel acted as (D-86): its page id, or null for the account's own. Kept with the session. */
    @Volatile private var pageId: String? = credentials.read(id)?.get(KEY_PAGE_ID)

    /** A session (and channel) being checked — signing in, or switching channel — before it is kept. */
    private class Pending(val session: WebSession, val pageId: String?)

    @Volatile private var pending: Pending? = null

    private fun currentSession(): WebSession? = pending?.session ?: session

    private fun currentPageId(): String? = pending.let { if (it != null) it.pageId else pageId }

    /** The account's channels, once asked (D-86). */
    private val _identities = MutableStateFlow<List<AccountIdentity>>(emptyList())
    private val switching = Mutex()

    override val descriptor = SourceDescriptor(
        id = id,
        displayName = "YouTube Music",
        providerName = "YouTube Music",
        basis = Basis.UNOFFICIAL_API,
        attribution = Attribution("YouTube Music", linkUrl = YouTubeMusicClient.ORIGIN),
        termsUrl = "https://www.youtube.com/t/terms",
        environment = MusicEnvironment.ONLINE,
    )

    private val _auth = MutableStateFlow<AuthState>(
        credentials.read(id)?.let { stored ->
            val s = stored[KEY_SESSION]?.let(WebSession::parse)
            if (s == null) AuthState.SignedOut else AuthState.SignedIn(stored[KEY_NAME] ?: "YouTube Music", stored[KEY_ACCOUNT] ?: accountKeyOf(stored[KEY_NAME].orEmpty()))
        } ?: AuthState.SignedOut,
    )

    private val _capabilities = MutableStateFlow(capabilitiesFor(_auth.value))
    override val capabilities: StateFlow<SourceCapabilities> = _capabilities.asStateFlow()

    private fun setAuth(state: AuthState) {
        if (state != _auth.value) cursors.clear()
        _auth.value = state
        _capabilities.value = capabilitiesFor(state)
    }

    private fun capabilitiesFor(state: AuthState): SourceCapabilities {
        val signedIn = state is AuthState.SignedIn
        val signIn = CapabilityState(
            CapabilityStatus.REQUIRES_SIGN_IN,
            if (state is AuthState.Expired) "Your YouTube Music sign-in expired" else "Sign in to YouTube Music",
            CapabilityAction.SignIn("Sign in"),
        )
        fun account(available: CapabilityState) = if (signedIn) available else signIn
        val catalogue = if (signedIn) CapabilityState.Available else CapabilityState(CapabilityStatus.DEGRADED, "Sign in for the full catalogue")
        return SourceCapabilities(
            mapOf(
                Capability.SEARCH to catalogue,
                Capability.BROWSE to catalogue,
                Capability.RECOMMENDATIONS to CapabilityState.Available,
                Capability.ARTWORK to CapabilityState.Available,
                Capability.AUTHENTICATION to CapabilityState.Available,
                Capability.ACCOUNT_LIBRARY to account(CapabilityState.Available),
                Capability.LIKES to account(CapabilityState.Available),
                Capability.PLAYLISTS to account(CapabilityState(CapabilityStatus.DEGRADED, "Playlists can be played, not edited")),
                Capability.HISTORY to account(CapabilityState.Available),
                Capability.LIVE_RADIO to CapabilityState.Available,
                Capability.DIRECT_STREAM to CapabilityState.Available,
                Capability.DOWNLOADS to CapabilityState(CapabilityStatus.UNAVAILABLE, "Not permitted"),
            ),
        )
    }

    /** Every account call goes through here: a refused session is an expired one, and says so. */
    private fun <T> Outcome<T>.watch(): Outcome<T> {
        if (this is Outcome.Failure && error is PodiumError.AuthExpired && pending == null && _auth.value is AuthState.SignedIn) {
            session = null
            pageId = null
            _identities.value = emptyList()
            credentials.delete(id)
            setAuth(AuthState.Expired)
        }
        return this
    }

    private fun requireSession(): PodiumError? = when {
        session != null -> null
        _auth.value is AuthState.Expired -> PodiumError.AuthExpired()
        else -> PodiumError.AuthRequired()
    }

    // --- Signing in ------------------------------------------------------------------------------------

    override val auth: AuthFacet = object : AuthFacet {
        override val state: StateFlow<AuthState> = _auth.asStateFlow()

        override val webSignIn = WebSignIn(
            title = "Sign in to YouTube Music",
            startUrl = "https://accounts.google.com/ServiceLogin?service=youtube&passive=true&continue=https%3A%2F%2Fmusic.youtube.com%2F",
            cookieOrigin = YouTubeMusicClient.ORIGIN,
            doneWhenCookies = SessionCookies.AUTH_COOKIE_NAMES,
            doneUrlPrefix = YouTubeMusicClient.ORIGIN,
            allowedHostSuffixes = listOf("google.com", "youtube.com", "gstatic.com", "googleusercontent.com", "ggpht.com", "ytimg.com", "googleapis.com"),
        )

        /**
         * Checks the page's session against the account menu, then keeps it sealed. Whatever ends
         * the check short of signing in — a refusal, a failure to save, or the caller giving up
         * (the sign-in screen closed) — puts the account back as it was, so it is never left
         * "signing in" with nothing running.
         */
        override suspend fun signIn(values: Map<String, String>): SignInResult {
            val candidate = WebSession.parse(values[WebSignIn.SESSION_KEY]) ?: return SignInResult.Refused(SetupProblem.WRONG_CREDENTIALS)
            val before = _auth.value
            _auth.value = AuthState.SigningIn
            var signedIn = false
            try {
                pending = Pending(candidate, pageId = null)
                val result = try {
                    client.accountMenu()
                } finally {
                    pending = null
                }
                val account = (result as? Outcome.Success)?.value?.let(YouTubeMusicParser::account)
                    ?: return SignInResult.Refused(
                        when ((result as? Outcome.Failure)?.error) {
                            is PodiumError.Network, PodiumError.Offline, is PodiumError.Server, is PodiumError.RateLimited -> SetupProblem.UNREACHABLE
                            else -> SetupProblem.WRONG_CREDENTIALS
                        },
                    )
                val key = accountKeyOf(account.handle ?: account.name)
                try {
                    credentials.write(id, mapOf(KEY_SESSION to candidate.cookieHeader, KEY_NAME to account.name, KEY_ACCOUNT to key))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The Keystore couldn't seal it: nothing is kept, and the listener can try again.
                    return SignInResult.Refused(SetupProblem.UNKNOWN)
                }
                session = candidate
                pageId = null
                _identities.value = emptyList()
                setAuth(AuthState.SignedIn(account.name, key))
                signedIn = true
                return SignInResult.SignedIn
            } finally {
                if (!signedIn) settle(before)
            }
        }

        /** After a sign-in that didn't happen: the account as it was, unless something else moved it on meanwhile. */
        private fun settle(before: AuthState) {
            if (_auth.value != AuthState.SigningIn) return // e.g. signed out while the check ran
            setAuth(
                when {
                    before is AuthState.SignedIn && session != null -> before
                    before is AuthState.Expired -> AuthState.Expired
                    else -> AuthState.SignedOut
                },
            )
        }

        override suspend fun signOut() {
            session = null
            pageId = null
            _identities.value = emptyList()
            credentials.delete(id)
            setAuth(AuthState.SignedOut)
        }

        // --- Channels (D-86) ---------------------------------------------------------------------------

        override val identities: StateFlow<List<AccountIdentity>> = _identities.asStateFlow()

        /**
         * The account's channels, from its channel switcher; the one acted as is selected. The
         * service lists them in two places and some accounts only in one (issue #4), so when the
         * first lists fewer than two, the music page's own switcher is asked too and both are
         * merged. A refusal here says nothing about the session (it isn't [watch]ed): the channels
         * just aren't offered.
         */
        override suspend fun refreshIdentities(): Outcome<List<AccountIdentity>> {
            requireSession()?.let { return Outcome.Failure(it) }
            val listed = client.accountsList()
            val fromList = (listed as? Outcome.Success)?.value?.let(YouTubeMusicParser::channels).orEmpty()
            val switcher = if (fromList.size >= 2) null else client.accountSwitcher()
            val fromSwitcher = (switcher as? Outcome.Success)?.value?.let(YouTubeMusicParser::channels).orEmpty()
            if (listed is Outcome.Failure && (switcher == null || switcher is Outcome.Failure)) return listed
            val channels = (fromList + fromSwitcher).distinctBy { it.pageId.orEmpty() }
            return Outcome.Success(identitiesOf(channels, pageId).also { _identities.value = it })
        }

        /**
         * Act as another channel: the account menu is asked as that channel first, and only a
         * channel that answers as itself is kept — the account's name and key become that channel's,
         * so its library, likes and history are its own. Anything short of that leaves things as
         * they were.
         */
        override suspend fun selectIdentity(id: String): Outcome<Unit> = switching.withLock {
            val signedIn = session ?: return Outcome.Failure(requireSession() ?: PodiumError.AuthRequired())
            val target = _identities.value.firstOrNull { it.id == id } ?: return Outcome.Failure(PodiumError.NotFound("channel"))
            val page = id.takeUnless { it == OWN_CHANNEL }
            if (target.selected && page == pageId) return Outcome.Success(Unit)
            val result = try {
                pending = Pending(signedIn, page)
                client.accountMenu()
            } finally {
                pending = null
            }
            if (result is Outcome.Failure) return result
            val account = YouTubeMusicParser.account((result as Outcome.Success).value)
                ?: return Outcome.Failure(PodiumError.Unexpected("no account in the answer"))
            // The catalogue answers as the account's own channel when it won't act as another: that's not a switch.
            val answeredAs = account.handle?.equals(target.detail, ignoreCase = true) == true || account.name == target.name
            if (!answeredAs) return Outcome.Failure(PodiumError.Unsupported("acting as that channel"))
            val key = if (page == null) accountKeyOf(account.handle ?: account.name) else accountKeyOf(account.handle ?: "page:$page")
            try {
                credentials.write(
                    this@YouTubeMusicSource.id,
                    buildMap {
                        put(KEY_SESSION, signedIn.cookieHeader)
                        put(KEY_NAME, account.name)
                        put(KEY_ACCOUNT, key)
                        page?.let { put(KEY_PAGE_ID, it) }
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return Outcome.Failure(PodiumError.Unexpected("couldn't keep the channel"))
            }
            pageId = page
            _identities.value = _identities.value.map { it.copy(selected = it.id == id) }
            setAuth(AuthState.SignedIn(account.name, key))
            Outcome.Success(Unit)
        }
    }

    // --- Live radio (D-90) ------------------------------------------------------------------------------

    override val liveRadio: LiveRadioFacet = object : LiveRadioFacet {
        override fun genres(): List<String> = LiveGenres.ALL.map { it.first }

        override suspend fun stations(genre: String): Outcome<List<Track>> {
            val query = LiveGenres.queryOf(genre) ?: return Outcome.Failure(PodiumError.NotFound("genre"))
            return liveSearch.search(query).map { json -> LiveParser.stations(json).map { mapper.track(it) } }
        }
    }

    // --- Catalogue -------------------------------------------------------------------------------------

    override val catalog: CatalogFacet = object : CatalogFacet {
        override suspend fun search(query: SearchQuery): Outcome<SearchResults> {
            val text = query.text.trim()
            if (text.isEmpty()) return Outcome.Success(SearchResults.Empty)
            if (query.offset > 0 || query.kinds == setOf(SearchKind.TRACKS)) {
                return songSearch(text).range(query.offset, query.limit).map { songs ->
                    SearchResults(tracks = songs.map { mapper.track(it) })
                }
            }
            val all = client.search(text).watch()
            if (all is Outcome.Failure) return all
            val page = YouTubeMusicParser.search((all as Outcome.Success).value)
            lastFilters[text] = page.filters
            val items = page.items
            // Searching an artist's name means their albums: the albums-only search answers with
            // anything titled like them (tributes, covers), so their own page's albums come first.
            val artist = items.filterIsInstance<YtmArtist>().firstOrNull { sameName(it.name, text) }
            // The songs and the albums come from the typed searches, when the catalogue offers them
            // (the mixed answer holds only a few of each); all at once.
            val (songs, typedAlbums, artistAlbums) = coroutineScope {
                val songs = async { if (SearchKind.TRACKS in query.kinds) songSearch(text).range(0, query.limit).let { it as? Outcome.Success }?.value else null }
                val wantAlbums = SearchKind.ALBUMS in query.kinds
                val own = async {
                    if (wantAlbums && artist != null) (artistPage(artist.browseId) as? Outcome.Success)?.value?.let { it.albums + it.singles } else null
                }
                val albums = async {
                    if (wantAlbums && artist == null) albumSearch(text).range(0, ALBUM_RESULTS).let { it as? Outcome.Success }?.value else null
                }
                Triple(songs.await(), albums.await(), own.await())
            }
            // The mixed answer's albums are its best guesses: they lead, the typed search fills in.
            val albums = if (!artistAlbums.isNullOrEmpty()) {
                (artistAlbums + items.filterIsInstance<YtmAlbum>().filter { a -> a.artists.any { sameName(it.name, text) } })
                    .distinctBy { it.browseId }.take(ALBUM_RESULTS)
            } else {
                AlbumRanking.rank(text, (items.filterIsInstance<YtmAlbum>() + typedAlbums.orEmpty()).distinctBy { it.browseId })
            }
            val playable = items.filterIsInstance<YtmSong>()
            return Outcome.Success(
                SearchResults(
                    tracks = if (SearchKind.TRACKS in query.kinds) {
                        (songs ?: playable.filter { it.kind == MediaKind.SONG }).take(query.limit).map { mapper.track(it) }
                    } else emptyList(),
                    videos = if (SearchKind.VIDEOS in query.kinds) {
                        playable.filter { it.kind == MediaKind.MUSIC_VIDEO || it.kind == MediaKind.VIDEO }.map { mapper.track(it) }
                    } else emptyList(),
                    albums = if (SearchKind.ALBUMS in query.kinds) albums.map(mapper::album) else emptyList(),
                    artists = if (SearchKind.ARTISTS in query.kinds) items.filterIsInstance<YtmArtist>().map(mapper::artist) else emptyList(),
                    playlists = if (SearchKind.PLAYLISTS in query.kinds) items.filterIsInstance<YtmPlaylist>().map(mapper::playlist) else emptyList(),
                ),
            )
        }

        override suspend fun track(ref: SourceRef): Outcome<Track> {
            val videoId = ref.providerKey
            return client.next(videoId, null).watch().map { json ->
                YouTubeMusicParser.watchSong(json, videoId)
            }.flatMap { song -> song?.let { Outcome.Success(mapper.track(it)) } ?: Outcome.Failure(PodiumError.NotFound("song")) }
        }

        override suspend fun album(id: AlbumId): Outcome<AlbumDetail> =
            albumPage(id.value.substringAfter(TrackId.SEPARATOR)).map { (summary, tracks) -> AlbumDetail(summary, tracks) }

        override suspend fun artist(id: ArtistId): Outcome<ArtistDetail> =
            artistPage(id.value.substringAfter(TrackId.SEPARATOR)).map { page ->
                ArtistDetail(
                    summary = ArtistSummary(id, page.name, mapper.artwork(page.thumbnails)),
                    albums = (page.albums + page.singles).map(mapper::album),
                    tracks = page.songs.map { mapper.track(it) },
                    playlists = page.playlists.map(mapper::playlist),
                )
            }

        override suspend fun playlist(id: PlaylistId): Outcome<PlaylistDetail> {
            val key = id.providerKey
            if (YouTubeMusicMapper.isAlbumBrowseId(key)) {
                return albumPage(key).map { (album, tracks) ->
                    PlaylistDetail(
                        PlaylistSummary(id, album.title, album.artistDisplay, album.artwork, tracks.size, isAlbum = true, year = album.year),
                        tracks,
                    )
                }
            }
            return playlistPage(key, MAX_PLAYLIST_TRACKS)
        }
    }

    private val lastFilters = ConcurrentHashMap<String, Map<String, String>>()

    private fun songSearch(text: String): Cursor<YtmSong> = cursors.get("search:songs:$text") {
        Cursor(
            first = {
                val params = SearchFilter.SONGS.fromAnswer(lastFilters[text].orEmpty()) ?: SearchFilter.SONGS.encoded()
                client.search(text, params).watch().map { json ->
                    val page = YouTubeMusicParser.search(json)
                    YtmPage(page.items.filterIsInstance<YtmSong>().filter { it.kind == MediaKind.SONG }, page.continuation)
                }
            },
            more = { token ->
                client.search(text, continuation = token).watch().map { json ->
                    val page = YouTubeMusicParser.searchContinuation(json, YouTubeMusicParser.Hint.SONG)
                    YtmPage(page.items.filterIsInstance<YtmSong>().filter { it.kind == MediaKind.SONG }, page.continuation)
                }
            },
            key = { it.videoId },
        )
    }

    /** The same name, whatever the case, spacing or punctuation ("the weeknd" is "The Weeknd"). */
    private fun sameName(a: String, b: String): Boolean {
        fun key(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        return key(a).isNotEmpty() && key(a) == key(b)
    }

    private fun albumSearch(text: String): Cursor<YtmAlbum> = cursors.get("search:albums:$text") {
        Cursor(
            first = {
                val params = SearchFilter.ALBUMS.fromAnswer(lastFilters[text].orEmpty()) ?: SearchFilter.ALBUMS.encoded()
                client.search(text, params).watch().map { json ->
                    val page = YouTubeMusicParser.search(json)
                    YtmPage(page.items.filterIsInstance<YtmAlbum>(), page.continuation)
                }
            },
            more = { token ->
                client.search(text, continuation = token).watch().map { json ->
                    YtmPage(YouTubeMusicParser.searchContinuation(json, YouTubeMusicParser.Hint.ALBUM).items.filterIsInstance<YtmAlbum>(), null)
                }
            },
            key = { it.browseId },
        )
    }

    /** An album's page: its summary and its songs (which inherit the album's artists and art). */
    private suspend fun albumPage(browseId: String): Outcome<Pair<AlbumSummary, List<Track>>> =
        client.browse(browseId).watch().flatMap { json ->
            val page = YouTubeMusicParser.collection(json, albumPage = true) ?: return@flatMap Outcome.Failure(PodiumError.NotFound("album"))
            val summary = AlbumSummary(
                id = AlbumId.of(id, browseId),
                title = page.title,
                artistDisplay = page.artists.joinToString(", ") { it.name },
                artwork = mapper.artwork(page.thumbnails),
                year = page.year,
                trackCount = page.tracks.size,
            )
            val context = YouTubeMusicMapper.AlbumContext(AlbumRef(page.title, AlbumId.of(id, browseId), summary.artistDisplay), page.artists, page.thumbnails, page.year)
            Outcome.Success(summary to page.tracks.map { mapper.track(it, context) })
        }

    private suspend fun playlistPage(playlistId: String, maxTracks: Int): Outcome<PlaylistDetail> {
        val first = client.browse("VL$playlistId").watch()
        if (first is Outcome.Failure) return first
        val page = YouTubeMusicParser.collection((first as Outcome.Success).value, albumPage = false)
            ?: return Outcome.Failure(PodiumError.NotFound("playlist"))
        val songs = ArrayList(page.tracks)
        var token = page.continuation
        var pages = 0
        while (token != null && songs.size < maxTracks && pages < MAX_PLAYLIST_PAGES) {
            val more = client.browse("VL$playlistId", continuation = token).watch()
            val next = (more as? Outcome.Success)?.value?.let(YouTubeMusicParser::songsContinuation) ?: break
            if (next.items.isEmpty()) break
            songs += next.items
            token = next.continuation?.takeIf { it != token }
            pages++
        }
        val tracks = songs.distinctBy { it.setVideoId ?: it.videoId }.map { mapper.track(it) }
        return Outcome.Success(
            PlaylistDetail(
                PlaylistSummary(
                    id = PlaylistId.of(id, playlistId),
                    title = page.title,
                    ownerName = page.artists.joinToString(", ") { it.name },
                    artwork = mapper.artwork(page.thumbnails),
                    trackCount = page.trackCount ?: tracks.size,
                ),
                tracks,
            ),
        )
    }

    private val artistPages = ConcurrentHashMap<String, Pair<Long, YtmArtistPage>>()

    private suspend fun artistPage(browseId: String): Outcome<YtmArtistPage> {
        artistPages[browseId]?.takeIf { now() - it.first < PAGE_CACHE_MS }?.let { return Outcome.Success(it.second) }
        return client.browse(browseId).watch().flatMap { json ->
            YouTubeMusicParser.artist(json)?.let { page ->
                artistPages[browseId] = now() to page
                Outcome.Success(page)
            } ?: Outcome.Failure(PodiumError.NotFound("artist"))
        }
    }

    // --- Home ----------------------------------------------------------------------------------------------

    private val shelfContents = ConcurrentHashMap<String, ShelfPage>()

    override val discovery: DiscoveryFacet = object : DiscoveryFacet {
        override suspend fun shelves(): Outcome<List<Shelf>> {
            val first = client.browse(HOME).watch()
            if (first is Outcome.Failure) return first
            val pages = ArrayList<YtmShelf>()
            var page = YouTubeMusicParser.shelves((first as Outcome.Success).value)
            pages += page.items
            var more = 0
            while (page.continuation != null && more < HOME_PAGES) {
                val next = client.browse(HOME, continuation = page.continuation).watch()
                page = (next as? Outcome.Success)?.value?.let(YouTubeMusicParser::shelves) ?: break
                pages += page.items
                more++
            }
            val shelves = pages.mapIndexed { index, s ->
                val shelfId = "home:$index:${s.title.hashCode().toUInt().toString(16)}"
                val songs = s.items.filterIsInstance<YtmSong>().filter { it.kind != MediaKind.EPISODE }.map { mapper.track(it) }
                val playlists = s.items.mapNotNull {
                    when (it) {
                        is YtmPlaylist -> mapper.playlist(it)
                        is YtmAlbum -> mapper.albumAsPlaylist(it)
                        else -> null
                    }
                }
                val artists = s.items.filterIsInstance<YtmArtist>().map(mapper::artist)
                shelfContents[shelfId] = ShelfPage(songs, playlists, artists)
                Shelf(shelfId, s.title, songs, playlists, artists)
            }.filter { it.tracks.isNotEmpty() || it.playlists.isNotEmpty() || it.artists.isNotEmpty() }
            return Outcome.Success(shelves)
        }

        override suspend fun shelf(id: String, offset: Int, limit: Int): Outcome<ShelfPage> {
            val page = shelfContents[id] ?: return Outcome.Failure(PodiumError.NotFound("shelf"))
            return Outcome.Success(
                ShelfPage(
                    page.tracks.drop(offset).take(limit),
                    page.playlists.drop((offset - page.tracks.size).coerceAtLeast(0)).take(limit),
                    if (offset == 0) page.artists else emptyList(),
                ),
            )
        }

        // Moods and genres are playlists of playlists here, not songs: not offered (§4.3).
        override suspend fun genres(): Outcome<List<String>> = Outcome.Success(emptyList())

        override suspend fun genre(name: String, offset: Int, limit: Int): Outcome<List<Track>> =
            Outcome.Failure(PodiumError.Unsupported("genres"))
    }

    // --- Radio -----------------------------------------------------------------------------------------------

    override val recommendations: RecommendationFacet = object : RecommendationFacet {
        override suspend fun related(seeds: List<Track>, limit: Int, exclude: Set<TrackId>): Outcome<List<Track>> {
            val seed = seeds.firstOrNull { it.source.sourceId == id } ?: return Outcome.Success(emptyList())
            val videoId = seed.source.providerKey
            return radio(videoId, "RDAMVM$videoId", limit, exclude + seeds.map { it.id })
        }

        override suspend fun artistRadio(artist: ArtistId, limit: Int, exclude: Set<TrackId>): Outcome<List<Track>> =
            artistPage(artist.value.substringAfter(TrackId.SEPARATOR)).flatMap { page ->
                val list = page.radioPlaylistId ?: page.shufflePlaylistId
                val mix = list?.let { radio(page.radioVideoId, it, limit, exclude, page.radioParams.takeIf { _ -> list == page.radioPlaylistId }) }
                if (mix is Outcome.Success && mix.value.isNotEmpty()) return@flatMap mix
                // No mix on the page (or it came back empty): a radio from their best-known song.
                val top = page.songs.firstOrNull { TrackId.of(id, it.videoId) !in exclude } ?: page.songs.firstOrNull()
                    ?: return@flatMap mix ?: Outcome.Failure(PodiumError.Unsupported("artist radio"))
                radio(top.videoId, "RDAMVM${top.videoId}", limit, exclude).map { found ->
                    (listOf(mapper.track(top)) + found).distinctBy { it.id }.take(limit)
                }
            }

        override suspend fun relatedArtists(artist: ArtistId, limit: Int): Outcome<List<ArtistSummary>> =
            artistPage(artist.value.substringAfter(TrackId.SEPARATOR)).map { page -> page.related.take(limit).map(mapper::artist) }
    }

    private suspend fun radio(videoId: String?, playlistId: String, limit: Int, exclude: Set<TrackId>, params: String? = null): Outcome<List<Track>> {
        val found = LinkedHashMap<String, YtmSong>()
        var page = when (val r = client.next(videoId, playlistId, params).watch()) {
            is Outcome.Failure -> return r
            is Outcome.Success -> YouTubeMusicParser.next(r.value)
        }
        var rounds = 0
        while (true) {
            page.items.filter { it.kind != MediaKind.EPISODE && TrackId.of(id, it.videoId) !in exclude }.forEach { found.putIfAbsent(it.videoId, it) }
            val token = page.continuation
            if (found.size >= limit || token == null || rounds >= RADIO_PAGES) break
            page = (client.next(videoId, playlistId, params, continuation = token).watch() as? Outcome.Success)?.value
                ?.let(YouTubeMusicParser::nextContinuation) ?: break
            rounds++
        }
        return Outcome.Success(found.values.take(limit).map { mapper.track(it) })
    }

    // --- Playback: in Podium, through Media3 -------------------------------------------------------------

    override val playback: PlaybackFacet = object : PlaybackFacet {
        override val routes = setOf(PlaybackRoute.DIRECT)

        /** The first extraction of a run fetches and reads the player; give it room before it counts as a failure. */
        override val resolveTimeoutMillis: Long = RESOLVE_TIMEOUT_MS

        override suspend fun resolve(track: Track, quality: QualityRequest, purpose: Purpose): FacetResolution {
            if (track.source.sourceId != id) return FacetResolution.Miss(MissReason.NO_SOURCE)
            if (purpose == Purpose.DOWNLOAD) return FacetResolution.Miss(MissReason.NOT_PERMITTED)
            return resolver.resolve(track, quality)
        }

        override fun forgetStream(track: Track) = resolver.forget(track)
    }

    // --- Artwork -----------------------------------------------------------------------------------------------

    override val artwork: ArtworkFacet = object : ArtworkFacet {
        override suspend fun load(ref: ArtworkRef, sizePx: Int): ArtworkPayload? {
            if (ref.sourceId != id) return null
            val bytes = client.image(YouTubeMusicMapper.sizedArtworkUrl(ref.key, sizePx)) ?: return null
            return ArtworkPayload.Bytes(bytes, mimeOf(bytes))
        }
    }

    // --- The account's library ---------------------------------------------------------------------------------

    override val accountLibrary: AccountLibraryFacet = object : AccountLibraryFacet {
        override suspend fun likedSongs(offset: Int, limit: Int): Outcome<List<Track>> {
            requireSession()?.let { return Outcome.Failure(it) }
            return cursors.get<YtmSong>("liked") {
                Cursor(
                    first = { client.browse("VL$LIKED_PLAYLIST").watch().map { json -> YouTubeMusicParser.collection(json, false).let { YtmPage(it?.tracks.orEmpty(), it?.continuation) } } },
                    more = { token -> client.browse("VL$LIKED_PLAYLIST", continuation = token).watch().map(YouTubeMusicParser::songsContinuation) },
                    key = { it.videoId },
                )
            }.range(offset, limit).map { songs -> songs.map { mapper.track(it) } }
        }

        override suspend fun playlists(offset: Int, limit: Int): Outcome<List<PlaylistSummary>> =
            libraryList("FEmusic_liked_playlists", offset, limit) { (it as? YtmPlaylist)?.takeIf { p -> p.playlistId != LIKED_PLAYLIST }?.let(mapper::playlist) }

        override suspend fun albums(offset: Int, limit: Int): Outcome<List<AlbumSummary>> =
            libraryList("FEmusic_liked_albums", offset, limit) { (it as? YtmAlbum)?.let(mapper::album) }

        override suspend fun artists(offset: Int, limit: Int): Outcome<List<ArtistSummary>> =
            libraryList("FEmusic_library_corpus_track_artists", offset, limit) { (it as? YtmArtist)?.let(mapper::artist) }

        override suspend fun history(offset: Int, limit: Int): Outcome<List<HistoryEntry>> {
            requireSession()?.let { return Outcome.Failure(it) }
            return client.browse("FEmusic_history").watch().map { json ->
                YouTubeMusicParser.history(json).drop(offset).take(limit).map { HistoryEntry(mapper.track(it.song), it.period) }
            }
        }

        override val canWriteLikes: Boolean get() = session != null

        override suspend fun setLiked(track: Track, liked: Boolean): Outcome<Unit> {
            requireSession()?.let { return Outcome.Failure(it) }
            if (track.source.sourceId != id) return Outcome.Failure(PodiumError.NotFound("song"))
            val result = client.like(track.source.providerKey, liked).watch().map { }
            if (result is Outcome.Success) cursors.clear() // the liked list changed
            return result
        }
    }

    private suspend fun <T> libraryList(browseId: String, offset: Int, limit: Int, map: (YtmItem) -> T?): Outcome<List<T>> {
        requireSession()?.let { return Outcome.Failure(it) }
        return cursors.get<YtmItem>("library:$browseId") {
            Cursor(
                first = { client.browse(browseId).watch().map(YouTubeMusicParser::library) },
                more = { token -> client.browse(browseId, continuation = token).watch().map(YouTubeMusicParser::libraryContinuation) },
                key = YouTubeMusicParser::identity,
            )
        }.range(offset, limit).map { items -> items.mapNotNull(map) }
    }

    companion object {
        val SOURCE_ID = SourceId("ytmusic")

        /** The official app that plays YouTube Music for Podium (§8). */
        /** The official app; Podium no longer hands songs to it, but the generic remote-playback wiring names it. */
        const val PROVIDER_APP_PACKAGE = "com.google.android.apps.youtube.music"

        /** Room for a cold first extraction (fetching and reading the player) on a slow network. */
        internal const val RESOLVE_TIMEOUT_MS = 20_000L

        private const val KEY_SESSION = "session"
        private const val KEY_NAME = "account-name"
        private const val KEY_ACCOUNT = "account-key"
        private const val KEY_PAGE_ID = "page-id"

        /** The identity id of the account's own channel, which has no page id. */
        internal const val OWN_CHANNEL = "own"
        private const val HOME = "FEmusic_home"

        /** Albums a search shows: a screenful, not the whole discography. */
        private const val ALBUM_RESULTS = 12
        private const val LIKED_PLAYLIST = "LM"
        private const val HOME_PAGES = 2
        private const val RADIO_PAGES = 3
        private const val MAX_PLAYLIST_TRACKS = 1_000
        private const val MAX_PLAYLIST_PAGES = 12
        private const val PAGE_CACHE_MS = 10 * 60 * 1000L

        /**
         * The channels as identities (D-86). The one acted as is selected: the channel with [pageId],
         * or — acting as the account itself — the one the switcher marks, else the one without a page id.
         */
        internal fun identitiesOf(channels: List<YtmChannel>, pageId: String?): List<AccountIdentity> {
            val chosen = when {
                pageId != null -> channels.firstOrNull { it.pageId == pageId }
                else -> channels.firstOrNull { it.selected } ?: channels.firstOrNull { it.pageId == null }
            }
            return channels.map { c ->
                AccountIdentity(
                    id = c.pageId ?: OWN_CHANNEL,
                    name = c.name,
                    detail = c.handle,
                    artworkUrl = c.photo.maxByOrNull { it.width }?.url,
                    selected = c === chosen,
                )
            }
        }

        /** An opaque key for an account's cached data: never the name itself, never anything secret. */
        fun accountKeyOf(identity: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest("podium-ytmusic:$identity".toByteArray(Charsets.UTF_8))
            return "ytm-" + digest.take(8).joinToString("") { "%02x".format(it) }
        }

        private fun mimeOf(bytes: ByteArray): String = when {
            bytes.size > 3 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "image/png"
            bytes.size > 11 && bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() -> "image/webp"
            else -> "image/jpeg"
        }
    }
}

internal inline fun <T, R> Outcome<T>.flatMap(transform: (T) -> Outcome<R>): Outcome<R> = when (this) {
    is Outcome.Success -> transform(value)
    is Outcome.Failure -> this
}
