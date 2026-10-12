package app.podium

import app.podium.core.common.Outcome
import app.podium.core.model.Track
import app.podium.core.model.TrackId
import app.podium.player.api.TrackCatalog
import app.podium.sources.api.SourceRegistry
import java.util.concurrent.ConcurrentHashMap

/**
 * A song with its album's and artists' pages, for Now Playing's Go to album and Go to artist
 * (D-79). Some of a source's lists name a song's album and artists without linking their pages
 * (a shelf's tiles, a liked-songs row); the song's own details on its source carry them. So a
 * song shown without them is looked up there once, and what it fills in is remembered.
 * Provider-neutral: it asks the song's own source, through the registry.
 */
class SongPages(
    private val catalog: TrackCatalog,
    private val registry: SourceRegistry,
) {
    private val lookedUp = ConcurrentHashMap.newKeySet<TrackId>()

    suspend fun of(id: TrackId): Track? {
        val known = (catalog.get(id) as? Outcome.Success)?.value ?: return null
        if (hasPages(known) || !lookedUp.add(id)) return known
        val fresh = (registry.get(id.sourceId)?.catalog?.track(known.source) as? Outcome.Success)?.value
        val filled = withPages(known, fresh?.takeIf { it.id == known.id })
        if (filled != known) catalog.remember(listOf(filled))
        return filled
    }

    internal companion object {
        /** Every page there is to go to: the album's, and each artist's. */
        fun hasPages(track: Track): Boolean =
            track.album?.id != null && track.artists.isNotEmpty() && track.artists.all { it.id != null }

        /**
         * [known] with the pages [fresh] links and it doesn't: the album's, and each artist's,
         * matched by name. Credits that match none (one line naming two artists, say) give way to
         * [fresh]'s own credits. Nothing [known] already links is replaced.
         */
        fun withPages(known: Track, fresh: Track?): Track {
            if (fresh == null) return known
            val album = when {
                known.album?.id != null -> known.album
                fresh.album?.id != null -> known.album?.copy(id = fresh.album!!.id) ?: fresh.album
                else -> known.album
            }
            val linked = fresh.artists.filter { it.id != null }.associateBy { key(it.name) }
            var artists = known.artists.map { c -> if (c.id != null) c else linked[key(c.name)]?.let { c.copy(id = it.id) } ?: c }
            if (artists.none { it.id != null } && linked.isNotEmpty()) artists = fresh.artists
            return known.copy(album = album, artists = artists)
        }

        private fun key(name: String) = name.lowercase().filter { it.isLetterOrDigit() }
    }
}
