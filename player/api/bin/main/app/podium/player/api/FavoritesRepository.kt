package app.podium.player.api

import app.podium.core.model.TrackId
import kotlinx.coroutines.flow.StateFlow

/** Songs the listener has marked as favorites, by source-qualified identity. */
interface FavoritesRepository {
    val favorites: StateFlow<Set<TrackId>>
    fun toggle(id: TrackId)
}
