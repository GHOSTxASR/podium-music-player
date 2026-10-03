package app.podium.feature.library

import app.podium.core.model.AlbumId
import app.podium.core.model.ArtistId
import app.podium.core.model.Track
import app.podium.sources.api.CapabilityAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull

/** The library as screens see it: merged across sources, provider-neutral. */
sealed interface LibraryState {
    data object Loading : LibraryState
    data class Ready(val tracks: List<Track>) : LibraryState
}

/** A source that needs the user before it can contribute (e.g. a permission). Rendered generically. */
data class SourceAction(val sourceName: String, val note: String, val action: CapabilityAction)

/**
 * The library for screens. Albums and artists default to being derived from [songs] in memory
 * ([LibraryIndex]); the app's repository answers them from the library database (D-31).
 * Album and artist streams emit nothing until the library has loaded.
 */
interface LibraryRepository {
    val songs: StateFlow<LibraryState>
    val pendingActions: StateFlow<List<SourceAction>>

    /** Pictures of artists, from sources that have them (never album covers standing in). */
    val artistArtwork: StateFlow<Map<ArtistId, String>> get() = NoArtistArtwork

    fun albums(): Flow<List<LibraryAlbum>> = loaded().map { LibraryIndex.albums(it) }

    fun album(id: AlbumId): Flow<AlbumDetail?> = loaded().map { LibraryIndex.album(it, id) }

    fun artists(): Flow<List<LibraryArtist>> = combine(loaded(), artistArtwork) { tracks, images -> LibraryIndex.artists(tracks, images) }

    fun artist(id: ArtistId): Flow<ArtistDetail?> = combine(loaded(), artistArtwork) { tracks, images -> LibraryIndex.artist(tracks, id, images) }

    private fun loaded(): Flow<List<Track>> = songs.mapNotNull { (it as? LibraryState.Ready)?.tracks }
}

private val NoArtistArtwork: StateFlow<Map<ArtistId, String>> = MutableStateFlow(emptyMap())
