package app.podium.sources.api

import app.podium.core.common.Outcome
import app.podium.core.model.AlbumId
import app.podium.core.model.ArtistId
import app.podium.core.model.ArtworkRef
import app.podium.core.model.SourceRef
import app.podium.core.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Searching and looking up a source's catalogue. */
interface CatalogFacet {
    suspend fun search(query: SearchQuery): Outcome<SearchResults>
    suspend fun track(ref: SourceRef): Outcome<Track>
    suspend fun album(id: AlbumId): Outcome<AlbumDetail>
    suspend fun artist(id: ArtistId): Outcome<ArtistDetail>
}

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

interface RecommendationFacet {
    suspend fun related(seeds: List<Track>, limit: Int): Outcome<List<Track>>
}

interface DownloadFacet {
    fun permission(track: Track): DownloadPermission
}

interface AuthFacet {
    val state: StateFlow<AuthState>
}

sealed interface AuthState {
    data object NotRequired : AuthState
    data object SignedOut : AuthState
    data object SigningIn : AuthState
    data class SignedIn(val accountName: String) : AuthState
    data object Expired : AuthState
    data class Rejected(val reason: String) : AuthState
}

data class SearchQuery(val text: String, val limit: Int = 25)

data class SearchResults(
    val tracks: List<Track> = emptyList(),
    val albums: List<AlbumSummary> = emptyList(),
    val artists: List<ArtistSummary> = emptyList(),
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

data class ArtistDetail(val summary: ArtistSummary, val albums: List<AlbumSummary>, val tracks: List<Track>)
