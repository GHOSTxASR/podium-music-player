package app.podium.feature.library

import app.podium.core.model.ArtistId
import app.podium.core.model.Track
import app.podium.sources.api.CapabilityAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The library as screens see it: merged across sources, provider-neutral. */
sealed interface LibraryState {
    data object Loading : LibraryState
    data class Ready(val tracks: List<Track>) : LibraryState
}

/** A source that needs the user before it can contribute (e.g. a permission). Rendered generically. */
data class SourceAction(val sourceName: String, val note: String, val action: CapabilityAction)

interface LibraryRepository {
    val songs: StateFlow<LibraryState>
    val pendingActions: StateFlow<List<SourceAction>>

    /** Pictures of artists, from sources that have them (never album covers standing in). */
    val artistArtwork: StateFlow<Map<ArtistId, String>> get() = NoArtistArtwork
}

private val NoArtistArtwork: StateFlow<Map<ArtistId, String>> = MutableStateFlow(emptyMap())
