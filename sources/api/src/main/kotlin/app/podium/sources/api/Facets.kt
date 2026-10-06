package app.podium.sources.api

import app.podium.core.common.Outcome
import app.podium.core.model.AlbumId
import app.podium.core.model.ArtistId
import app.podium.core.model.ArtworkRef
import app.podium.core.common.PodiumError
import app.podium.core.model.PlaylistId
import app.podium.core.model.SourceRef
import app.podium.core.model.Track
import app.podium.core.model.TrackId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Searching and looking up a source's catalogue. */
interface CatalogFacet {
    suspend fun search(query: SearchQuery): Outcome<SearchResults>
    suspend fun track(ref: SourceRef): Outcome<Track>
    suspend fun album(id: AlbumId): Outcome<AlbumDetail>
    suspend fun artist(id: ArtistId): Outcome<ArtistDetail>

    /** A playlist and its songs, for sources that have playlists. */
    suspend fun playlist(id: PlaylistId): Outcome<PlaylistDetail> = Outcome.Failure(PodiumError.NotFound("playlist"))
}

/**
 * The lists a source curates for browsing (D-34): charts, new and underground music, popular
 * playlists, genres. Each shelf comes with its first page; [shelf] pages further on request.
 */
interface DiscoveryFacet {
    suspend fun shelves(): Outcome<List<Shelf>>
    suspend fun shelf(id: String, offset: Int, limit: Int): Outcome<ShelfPage>

    /** Genres to explore, most popular first. */
    suspend fun genres(): Outcome<List<String>>

    /** Music in one genre, as the source ranks it. */
    suspend fun genre(name: String, offset: Int, limit: Int): Outcome<List<Track>>
}

/** One curated list. A shelf holds songs, playlists (albums are playlists with isAlbum) or artists. */
data class Shelf(
    val id: String,
    val title: String,
    val tracks: List<Track> = emptyList(),
    val playlists: List<PlaylistSummary> = emptyList(),
    val artists: List<ArtistSummary> = emptyList(),
)

data class ShelfPage(
    val tracks: List<Track> = emptyList(),
    val playlists: List<PlaylistSummary> = emptyList(),
    val artists: List<ArtistSummary> = emptyList(),
)

/** The user's own collection at a source (enumerable). */
interface LibraryFacet {
    /** Emits the full library whenever it changes. Sources decide how to observe changes. */
    fun tracks(): Flow<List<Track>>
    fun albums(): Flow<List<AlbumSummary>>
    fun artists(): Flow<List<ArtistSummary>>
}

/** Turns one of this source's tracks into a playback target. */
interface PlaybackFacet {
    val routes: Set<app.podium.core.model.PlaybackRoute>
    suspend fun resolve(track: Track, quality: QualityRequest, purpose: Purpose): FacetResolution

    /**
     * For a source whose music plays in another app (a [PlaybackTarget.RemoteProvider]): the
     * provider key of this source's track that the other app's media session reports as
     * [sessionMediaId], or null when it isn't recognisable. Lets Podium mirror what plays there
     * without guessing from titles.
     */
    fun remoteTrackKey(sessionMediaId: String): String? = null

    /**
     * Hand a whole context (an album, a playlist, a radio) to the other app, starting at [start].
     * Null when the source can only hand over single songs.
     */
    fun remoteContext(context: RemoteContext, start: Track?): PlaybackTarget.RemoteProvider? = null
}

/** A context an app that plays a source's music can start by itself (a playlist, an album, a radio). */
sealed interface RemoteContext {
    data class Collection(val id: PlaylistId) : RemoteContext
    data class Radio(val seed: Track) : RemoteContext
    data class ArtistRadio(val artist: ArtistId) : RemoteContext
}

/** Artwork bytes or a local file for an [ArtworkRef] owned by this source. */
interface ArtworkFacet {
    suspend fun load(ref: ArtworkRef, sizePx: Int): ArtworkPayload?
}

sealed interface ArtworkPayload {
    data class LocalFile(val path: String) : ArtworkPayload
    data class Bytes(val bytes: ByteArray, val mimeType: String) : ArtworkPayload {
        override fun equals(other: Any?) = other is Bytes && other.mimeType == mimeType && other.bytes.contentEquals(bytes)
        override fun hashCode() = 31 * bytes.contentHashCode() + mimeType.hashCode()
    }
}

/** Contracts below are declared now so capabilities are explicit; implementations come later. */
interface LyricsFacet {
    suspend fun lyrics(track: Track): Outcome<String?>
}

/**
 * What the source itself recommends (D-34): songs like some songs, an artist's radio, related
 * artists. Podium never invents recommendations when a source provides them.
 */
interface RecommendationFacet {
    /** Songs in the spirit of [seeds] (most relevant first), never any in [exclude]. */
    suspend fun related(seeds: List<Track>, limit: Int, exclude: Set<TrackId> = emptySet()): Outcome<List<Track>>

    /** An artist's radio: their music mixed with related artists'. */
    suspend fun artistRadio(artist: ArtistId, limit: Int, exclude: Set<TrackId> = emptySet()): Outcome<List<Track>>

    suspend fun relatedArtists(artist: ArtistId, limit: Int): Outcome<List<ArtistSummary>>
}

interface DownloadFacet {
    fun permission(track: Track): DownloadPermission
}

/**
 * Signing in to a source (D-37), whatever the source is: what it asks for, its state, and signing
 * in and out. Secrets go to the source's [CredentialStore] and nowhere else; the UI only ever sees
 * [AuthState] and the field descriptions.
 */
interface AuthFacet {
    val state: StateFlow<AuthState>

    /** What signing in asks for (empty when the source signs in some other way, or not at all). */
    val signInFields: List<SetupField> get() = emptyList()

    /** Check [values] with the source and, if accepted, keep them for next time. */
    suspend fun signIn(values: Map<String, String>): SignInResult = SignInResult.Refused(SetupProblem.NOT_SUPPORTED)

    /** Forget the stored credentials. The source stays configured, and asks to sign in again. */
    suspend fun signOut() {}

    /** Set when signing in happens on the provider's web page instead of a form. */
    val webSignIn: WebSignIn? get() = null
}

/**
 * An online account's own library (YOUTUBE_MUSIC_ARCHITECTURE §7). Lists page by offset; a source
 * that pages by tokens keeps them itself. The account is the authority: Podium caches, never owns.
 */
interface AccountLibraryFacet {
    suspend fun likedSongs(offset: Int, limit: Int): Outcome<List<Track>>
    suspend fun playlists(offset: Int, limit: Int): Outcome<List<PlaylistSummary>>
    suspend fun albums(offset: Int, limit: Int): Outcome<List<AlbumSummary>>
    suspend fun artists(offset: Int, limit: Int): Outcome<List<ArtistSummary>>

    /** What the account itself recorded, newest first, with the provider's own grouping ("Today"). */
    suspend fun history(offset: Int, limit: Int): Outcome<List<HistoryEntry>> = Outcome.Failure(PodiumError.Unsupported("history"))

    /** Whether [setLiked] writes to the account. */
    val canWriteLikes: Boolean get() = false

    suspend fun setLiked(track: Track, liked: Boolean): Outcome<Unit> = Outcome.Failure(PodiumError.Unsupported("likes"))
}

/** One song in an account's own history. [period] is the provider's label for when ("Today"), if any. */
data class HistoryEntry(val track: Track, val period: String?)

/**
 * A sign-in that happens on the provider's own web page (D-37 amended, YOUTUBE_MUSIC_ARCHITECTURE §6):
 * Podium shows the page in a locked-down web view, never sees the password, and hands the session
 * the page leaves behind to [AuthFacet.signIn] under [SESSION_KEY]. Everything here is
 * provider-neutral data; the screen running it never knows which provider it is.
 */
data class WebSignIn(
    val title: String,
    val startUrl: String,
    /** Whose cookies make up the session, e.g. "https://example.com". */
    val cookieOrigin: String,
    /** Signed in once any of these cookies exists for [cookieOrigin] and the page is on [doneUrlPrefix]. */
    val doneWhenCookies: Set<String>,
    val doneUrlPrefix: String,
    /** The page may only go to these hosts (and their subdomains), over https. */
    val allowedHostSuffixes: List<String>,
) {
    companion object {
        const val SESSION_KEY = "web-session"
    }
}

sealed interface SignInResult {
    data object SignedIn : SignInResult
    data class Refused(val problem: SetupProblem) : SignInResult
}

sealed interface AuthState {
    data object NotRequired : AuthState
    data object SignedOut : AuthState
    data object SigningIn : AuthState
    /**
     * [accountKey] keys everything kept for this account (likes, playlists, history caches) so one
     * account's data never shows under another's; it is an opaque, non-secret value.
     */
    data class SignedIn(val accountName: String, val accountKey: String = accountName) : AuthState
    data object Expired : AuthState
    data class Rejected(val reason: String) : AuthState
}

/** What to search for; [offset] pages through results without loading the whole catalogue. */
data class SearchQuery(
    val text: String,
    val limit: Int = 25,
    val offset: Int = 0,
    val kinds: Set<SearchKind> = SearchKind.entries.toSet(),
)

enum class SearchKind { TRACKS, ARTISTS, ALBUMS, PLAYLISTS, VIDEOS }

data class SearchResults(
    val tracks: List<Track> = emptyList(),
    val albums: List<AlbumSummary> = emptyList(),
    val artists: List<ArtistSummary> = emptyList(),
    val playlists: List<PlaylistSummary> = emptyList(),
    /** Music videos and other videos, kept apart from songs (each says its [Track.kind]). */
    val videos: List<Track> = emptyList(),
) {
    companion object {
        val Empty = SearchResults()
    }
}

data class AlbumSummary(
    val id: AlbumId,
    val title: String,
    val artistDisplay: String,
    val artwork: ArtworkRef? = null,
    val year: Int? = null,
    val trackCount: Int? = null,
)

data class ArtistSummary(
    val id: ArtistId,
    val name: String,
    /** A picture of the artist, if the source has one. Never an album cover standing in for it. */
    val artwork: ArtworkRef? = null,
    val trackCount: Int? = null,
)

data class AlbumDetail(val summary: AlbumSummary, val tracks: List<Track>)

data class ArtistDetail(
    val summary: ArtistSummary,
    val albums: List<AlbumSummary>,
    val tracks: List<Track>,
    val playlists: List<PlaylistSummary> = emptyList(),
)

/** A playlist (or an album a source presents as a playlist). */
data class PlaylistSummary(
    val id: PlaylistId,
    val title: String,
    val ownerName: String,
    val artwork: ArtworkRef? = null,
    val trackCount: Int? = null,
    val isAlbum: Boolean = false,
    val year: Int? = null,
)

data class PlaylistDetail(val summary: PlaylistSummary, val tracks: List<Track>)
