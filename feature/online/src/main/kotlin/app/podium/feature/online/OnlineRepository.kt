package app.podium.feature.online

import app.podium.core.common.Outcome
import app.podium.core.model.ArtistId
import app.podium.core.model.PlaylistId
import app.podium.core.model.Track
import app.podium.core.model.TrackId
import app.podium.sources.api.ArtistDetail
import app.podium.sources.api.ArtistSummary
import app.podium.sources.api.PlaylistDetail
import app.podium.sources.api.SearchResults
import app.podium.sources.api.Shelf
import app.podium.sources.api.ShelfPage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The online source the ONLINE section shows (D-34), by what it can do — never by who it is.
 * [name] is only for copy ("Couldn't reach …").
 */
data class OnlineSource(
    val name: String,
    val canSearch: Boolean,
    val canBrowse: Boolean,
    val canRecommend: Boolean,
    /** A note from the source when it can't be reached or used. */
    val note: String? = null,
)

/** One of the listener's online playlists (kept on the device until a source can hold them). */
data class OnlinePlaylist(val id: String, val name: String, val trackCount: Int)

data class OnlinePlaylistEntry(val entryId: Long, val track: Track)

data class OnlinePlaylistContents(val playlist: OnlinePlaylist, val entries: List<OnlinePlaylistEntry>)

/**
 * Everything the ONLINE screens read and write. Catalogue calls go to the online source; likes,
 * playlists and history are ONLINE's own and never touch the local library (D-34).
 */
interface OnlineRepository {
    /** The connected online source, or null when there's none. */
    val source: StateFlow<OnlineSource?>

    suspend fun shelves(): Outcome<List<Shelf>>
    suspend fun shelf(id: String, offset: Int, limit: Int): Outcome<ShelfPage>
    suspend fun genres(): Outcome<List<String>>
    suspend fun genre(name: String, offset: Int, limit: Int): Outcome<List<Track>>
    suspend fun search(text: String, offset: Int, limit: Int): Outcome<SearchResults>
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

    val recentlyPlayed: Flow<List<Track>>
    fun clearHistory()
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
}
