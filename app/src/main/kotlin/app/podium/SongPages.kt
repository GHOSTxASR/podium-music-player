package app.podium

import app.podium.core.common.Outcome
import app.podium.core.model.AlbumId
import app.podium.core.model.ArtistCredit
import app.podium.core.model.ArtistId
import app.podium.core.model.Track
import app.podium.core.model.TrackId
import app.podium.player.api.TrackCatalog
import app.podium.sources.api.CatalogFacet
import app.podium.sources.api.SearchKind
import app.podium.sources.api.SearchQuery
import app.podium.sources.api.SourceRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.ConcurrentHashMap

/**
 * A song with its album's and artists' pages, for Now Playing's Go to album and Go to artist
 * (D-79, D-80). Some of a source's lists name a song's album and artists without linking their
 * pages; the song's own details on its source carry them. So a song shown without them is looked
 * up there, and what it fills in is kept. Provider-neutral: it asks the song's own source, through
 * the registry.
 *
 * - A lookup runs in [scope], so it finishes even when the screen that asked has gone, and two
 *   screens asking at once share it.
 * - What a lookup found is kept here, so it holds when a list later puts the song in the catalog
 *   again without its pages.
 * - A lookup that failed (no network, say) is tried again the next time the song is asked for.
 * - Pages the details still leave out are found by name on the same source: the artist named
 *   exactly so, the album of that exact title by the song's artist. Nothing close is taken.
 */
class SongPages(
    private val catalog: TrackCatalog,
    private val registry: SourceRegistry,
    private val scope: CoroutineScope,
) {
    private val found = ConcurrentHashMap<TrackId, Track>()
    private val pending = HashMap<TrackId, Deferred<Track?>>()

    suspend fun of(id: TrackId): Track? {
        val known = (catalog.get(id) as? Outcome.Success)?.value ?: return null
        if (hasPages(known)) return known
        val lookup = synchronized(pending) {
            found[id]?.let { return withPages(known, it) }
            pending.getOrPut(id) { scope.async(start = CoroutineStart.LAZY) { lookUp(known) } }
        }
        lookup.start()
        val filled = try {
            lookup.await()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive() // the caller gave up: so does this call, not the lookup
            null
        }
        return filled?.let { withPages(known, it) } ?: known
    }

    /** [known] with the pages its source links; null when they couldn't be asked for. */
    private suspend fun lookUp(known: Track): Track? {
        try {
            val source = registry.get(known.id.sourceId)?.catalog ?: return null
            val asking = Asking(source)
            val details = source.track(known.source)
            if (details is Outcome.Failure) asking.failed = true
            var filled = withPages(known, (details as? Outcome.Success)?.value?.takeIf { it.id == known.id })
            if (!hasPages(filled)) filled = asking.byName(filled)
            // Kept unless something failed before anything was found: then it's asked again next time.
            if (!asking.failed || filled != known) synchronized(pending) { found[known.id] = filled }
            if (filled != known) catalog.remember(listOf(filled))
            return filled
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        } finally {
            synchronized(pending) { pending.remove(known.id) }
        }
    }

    /** Pages found by name on [source], exact names only. */
    private class Asking(private val source: CatalogFacet) {
        var failed = false

        suspend fun byName(track: Track): Track {
            val artists = track.artists.flatMap { credit -> if (credit.id != null) listOf(credit) else linked(credit) }
            val album = track.album?.let { album ->
                if (album.id != null || album.title.isBlank()) album
                else albumNamed(album.title, album.albumArtist ?: artists.firstOrNull()?.name)?.let { album.copy(id = it) } ?: album
            }
            return track.copy(artists = artists, album = album)
        }

        /** The credit with its artist's page; a line naming several, as one credit each when every one is found. */
        private suspend fun linked(credit: ArtistCredit): List<ArtistCredit> {
            artistNamed(credit.name)?.let { return listOf(credit.copy(id = it)) }
            val names = credit.name.split(JOINERS).map(String::trim).filter(String::isNotEmpty)
            if (names.size < 2) return listOf(credit)
            val ids = names.map { artistNamed(it) ?: return listOf(credit) }
            return names.zip(ids) { name, id -> ArtistCredit(name, credit.role, id) }
        }

        private suspend fun artistNamed(name: String): ArtistId? =
            search(name, SearchKind.ARTISTS)?.artists?.firstOrNull { sameName(it.name, name) }?.id

        private suspend fun albumNamed(title: String, artist: String?): AlbumId? =
            search(listOfNotNull(title, artist).joinToString(" "), SearchKind.ALBUMS)?.albums?.firstOrNull {
                sameName(it.title, title) && (artist == null || key(it.artistDisplay).contains(key(artist)))
            }?.id

        private suspend fun search(text: String, kind: SearchKind) =
            when (val r = source.search(SearchQuery(text, limit = SEARCH_LIMIT, kinds = setOf(kind)))) {
                is Outcome.Success -> r.value
                is Outcome.Failure -> null.also { failed = true }
            }
    }

    internal companion object {
        private const val SEARCH_LIMIT = 10

        /** How one line joins several artists: "A & B", "A, B", "A x B", "A feat. B". */
        private val JOINERS = Regex(" & |, | x | and | feat\\.? | ft\\.? | with ", RegexOption.IGNORE_CASE)

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

        private fun sameName(a: String, b: String) = key(a).isNotEmpty() && key(a) == key(b)

        private fun key(name: String) = name.lowercase().filter { it.isLetterOrDigit() }
    }
}
