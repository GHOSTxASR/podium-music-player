package app.podium.feature.online

import app.podium.core.common.Outcome
import app.podium.core.model.ArtistId
import app.podium.core.model.PlaylistId
import app.podium.core.model.Track
import app.podium.core.model.TrackId
import app.podium.core.common.PodiumError
import app.podium.sources.api.AlbumSummary
import app.podium.sources.api.ArtistDetail
import app.podium.sources.api.HistoryEntry
import app.podium.sources.api.PlaylistSummary
import app.podium.sources.api.ArtistSummary
import app.podium.sources.api.PlaylistDetail
import app.podium.sources.api.SearchResults
import app.podium.sources.api.Shelf
import app.podium.sources.api.ShelfPage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * What ONLINE can do right now, across every enabled online source (D-34, D-35) — by what they can
 * do, never by who they are, and never how many there are.
 */
data class OnlineStatus(
    val canSearch: Boolean,
    val canBrowse: Boolean,
    val canRecommend: Boolean,
    /** The account's own library (liked songs, playlists, albums, artists) can be read. */
    val canLibrary: Boolean = false,
    /** The account's own listening history can be read. */
    val canHistory: Boolean = false,
    /** Genres to explore are offered. */
    val canExplore: Boolean = true,
    /** The online account, when the music service has accounts. */
    val account: OnlineAccount? = null,
)

/** The listener's online account as browsing shows it — never which service it is (D-35). */
data class OnlineAccount(val state: AccountState, val name: String? = null) {
    val signedIn: Boolean get() = state == AccountState.SIGNED_IN
}

enum class AccountState { SIGNED_OUT, SIGNING_IN, SIGNED_IN, EXPIRED }

/** One of the listener's online playlists (kept on the device until a source can hold them). */
data class OnlinePlaylist(val id: String, val name: String, val trackCount: Int)

data class OnlinePlaylistEntry(val entryId: Long, val track: Track)

data class OnlinePlaylistContents(val playlist: OnlinePlaylist, val entries: List<OnlinePlaylistEntry>)

/**
 * Everything the ONLINE screens read and write. Catalogue calls go to every enabled online source at
 * once and come back as one answer, each song once (D-35); anything that belongs to one source (an
 * artist, an album, a playlist, a shelf) carries its source in its id and goes back to that source.
 * Likes, playlists and history are ONLINE's own and never touch the local library (D-34).
 */
interface OnlineRepository {
    /** What ONLINE can do, or null when no online source is on. */
    val status: StateFlow<OnlineStatus?>

    /** Whether a radio can start from [track] (its source — or one with the same recording — recommends). */
    fun canStartRadio(track: Track): Boolean

    /** Whether [artist]'s source can name related artists and play the artist's radio. */
    fun canRelate(artist: ArtistId): Boolean

    suspend fun shelves(): Outcome<List<Shelf>>
    suspend fun shelf(id: String, offset: Int, limit: Int): Outcome<ShelfPage>
    suspend fun genres(): Outcome<List<String>>
    suspend fun genre(name: String, offset: Int, limit: Int): Outcome<List<Track>>

    /**
     * Search every online source. Emits as sources answer — what's known so far, then the final
     * answer — so a slow source never keeps the others' results off the screen. A failure is
     * emitted only when no source could answer.
     */
    fun search(text: String, offset: Int, limit: Int): Flow<Outcome<SearchResults>>
    suspend fun artist(id: ArtistId): Outcome<ArtistDetail>
    suspend fun relatedArtists(id: ArtistId): Outcome<List<ArtistSummary>>
    suspend fun collection(id: PlaylistId): Outcome<PlaylistDetail>

    val likedIds: StateFlow<Set<TrackId>>
    val likedTracks: Flow<List<Track>>
    fun setLiked(track: Track, liked: Boolean)

    val playlists: Flow<List<OnlinePlaylist>>
    fun playlist(id: String): Flow<OnlinePlaylistContents?>
    suspend fun createPlaylist(name: String, tracks: List<Track>): String
    fun renamePlaylist(id: String, name: String)
    fun deletePlaylist(id: String)
    fun addToPlaylist(id: String, tracks: List<Track>)
    fun removeFromPlaylist(id: String, entryId: Long)
    fun movePlaylistEntry(id: String, entryId: Long, toIndex: Int)

    /** What Podium itself played online (its own record, not the account's), newest first. */
    val recentlyPlayed: Flow<List<Track>>
    fun clearHistory()

    // --- The account's own library (YOUTUBE_MUSIC_ARCHITECTURE §7) -----------------------------------

    suspend fun libraryPlaylists(offset: Int, limit: Int): Outcome<List<PlaylistSummary>> = Outcome.Failure(PodiumError.Unsupported("library"))
    suspend fun libraryAlbums(offset: Int, limit: Int): Outcome<List<AlbumSummary>> = Outcome.Failure(PodiumError.Unsupported("library"))
    suspend fun libraryArtists(offset: Int, limit: Int): Outcome<List<ArtistSummary>> = Outcome.Failure(PodiumError.Unsupported("library"))

    /** What the account itself recorded, newest first. */
    suspend fun accountHistory(offset: Int, limit: Int): Outcome<List<HistoryEntry>> = Outcome.Failure(PodiumError.Unsupported("history"))

    /** Fetch the account's likes again (after signing in, or when Liked songs opens). */
    fun refreshLibrary() {}
}

/** What the ONLINE screens ask the player and the system to do. */
interface OnlineActions {
    fun play(tracks: List<Track>, index: Int, label: String)
    fun shuffle(tracks: List<Track>, label: String)
    fun playNext(track: Track)
    fun addToQueue(track: Track)
    fun startRadio(track: Track)
    fun startArtistRadio(artist: ArtistSummary)
    fun startGenreRadio(genre: String)

    /** Share the song's page at its source (only offered when it has one). */
    fun share(track: Track)

    /** The song playing now, if it's an online one (radio "from what's playing"). */
    val nowPlaying: StateFlow<Track?>

    /**
     * Play [tracks] from an album or playlist the listener opened ([collection]); whoever plays it
     * may play the collection as a whole.
     */
    fun playCollection(tracks: List<Track>, index: Int, label: String, collection: app.podium.core.model.PlaylistId, shuffle: Boolean = false) =
        if (shuffle) shuffle(tracks, label) else play(tracks, index, label)

    /** Sign in to the online account (opens the service's own sign-in page). */
    fun signIn() {}
}
