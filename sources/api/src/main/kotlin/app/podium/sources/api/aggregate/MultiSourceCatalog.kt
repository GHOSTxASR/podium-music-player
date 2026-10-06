package app.podium.sources.api.aggregate

import app.podium.core.common.Outcome
import app.podium.core.common.PodiumError
import app.podium.core.model.AlbumId
import app.podium.core.model.ArtistId
import app.podium.core.model.PlaylistId
import app.podium.core.model.ScopedKey
import app.podium.core.model.SourceId
import app.podium.core.model.Track
import app.podium.core.model.TrackId
import app.podium.sources.api.AlbumDetail
import app.podium.sources.api.ArtistDetail
import app.podium.sources.api.ArtistSummary
import app.podium.sources.api.Capability
import app.podium.sources.api.MusicEnvironment
import app.podium.sources.api.MusicSource
import app.podium.sources.api.PlaylistDetail
import app.podium.sources.api.SearchQuery
import app.podium.sources.api.SearchResults
import app.podium.sources.api.Shelf
import app.podium.sources.api.ShelfPage
import app.podium.sources.api.SourceRegistry
import app.podium.sources.api.resolve.EquivalenceStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** A search across several sources, as far as it has got (D-35). */
data class CatalogSearch(
    /** One song per recording (each group's shown copy), then artists, albums and playlists. */
    val results: SearchResults,
    /** The songs with every copy of each, for the resolver and for tests. */
    val groups: List<TrackGroup>,
    /** Sources still to answer; 0 when this is the final word. */
    val pending: Int,
    /** Set only when the search is over and no source could answer: why. */
    val error: PodiumError? = null,
) {
    val complete: Boolean get() = pending == 0
}

/**
 * One catalogue over every enabled source of an environment (D-35). Screens ask for music; this
 * asks the sources — all at once, in the listener's priority order, within each source's
 * capabilities and health — and answers with one list per question: each recording once (EXACT
 * matches grouped, every copy kept), never a source name.
 *
 * Anything source-local — an artist, an album, a playlist, a shelf — carries its source
 * (`<sourceId>|<key>`) and is routed back to exactly that source, never to "the current one".
 * Nothing here knows which sources exist: they come from the [SourceRegistry].
 */
class MultiSourceCatalog(
    private val registry: SourceRegistry,
    private val environment: MusicEnvironment,
    private val fanOut: SourceFanOut,
    private val grouper: TrackGrouper,
    private val equivalence: EquivalenceStore,
) {
    private val pages = Pages()

    /** Which sources were last seen to have each genre (lower-cased name), for routing a genre. */
    @Volatile private var genreHolders: Map<String, Set<SourceId>> = emptyMap()

    /** Enabled sources of this environment that can do [capability] now, in priority order. */
    fun sources(capability: Capability): List<MusicSource> =
        registry.ordered(environment).filter { it.capabilities.value.isUsable(capability) }

    /** Whether radio can start from [track]: its source, or one holding the same recording, recommends. */
    fun recommends(track: Track): Boolean {
        val holders = (listOf(track) + equivalence.exactEquivalents(track)).map { it.source.sourceId }.toSet()
        return sources(Capability.RECOMMENDATIONS).any { it.descriptor.id in holders && it.recommendations != null }
    }

    /** Whether [artist]'s own source can name related artists and play their radio. */
    fun recommends(artist: ArtistId): Boolean =
        sources(Capability.RECOMMENDATIONS).any { it.descriptor.id == artist.sourceId && it.recommendations != null }

    // --- Search ------------------------------------------------------------------------------------------

    /**
     * Search every source that can. Emits a merged view each time a source answers (the most
     * preferred sources' songs first, so the order is the same however the answers race) and ends
     * with the complete view. A source that fails or runs out of time just adds nothing.
     * [SearchQuery.offset] > 0 continues the last search with the same words ("More songs").
     */
    fun search(query: SearchQuery): Flow<CatalogSearch> = flow {
        val sources = sources(Capability.SEARCH).filter { it.catalog != null }
        if (sources.isEmpty()) {
            emit(CatalogSearch(SearchResults.Empty, emptyList(), pending = 0, error = NO_SOURCE))
            return@flow
        }
        val key = "search|${query.kinds.sorted()}|${query.limit}|${query.text.trim().lowercase()}"
        val session = pages.open(key, query.offset)
        val asked = fanOut.admitted(sources)
        if (asked.isEmpty()) {
            emit(CatalogSearch(SearchResults.Empty, emptyList(), pending = 0, error = fanOut.unavailable(sources.first().descriptor.id)))
            return@flow
        }
        val answers = mutableMapOf<SourceId, SourceAnswer<SearchResults>>()
        fanOut.ask(asked) { s -> s.catalog!!.search(query.copy(offset = session.offsetOf(s.descriptor.id))) }
            .collect { answer ->
                answers[answer.source] = answer
                emit(view(asked, answers, session, pending = asked.size - answers.size))
            }
        val ok = asked.mapNotNull { answers[it.descriptor.id] as? SourceAnswer.Answered }
        pages.advance(session, ok.associate { it.source to it.value.tracks.size }, merge(ok, session).first)
    }

    private fun view(
        asked: List<MusicSource>,
        answers: Map<SourceId, SourceAnswer<SearchResults>>,
        session: Pages.Session,
        pending: Int,
    ): CatalogSearch {
        val ok = asked.mapNotNull { answers[it.descriptor.id] as? SourceAnswer.Answered }
        val (rows, fresh) = merge(ok, session)
        rows.forEach(equivalence::remember)
        val results = SearchResults(
            tracks = fresh.map { it.track },
            artists = interleave(ok.map { it.value.artists }).distinctBy { it.id },
            albums = interleave(ok.map { it.value.albums }).distinctBy { it.id },
            playlists = interleave(ok.map { it.value.playlists }).distinctBy { it.id },
        )
        val error = if (pending == 0 && ok.isEmpty()) errorOf(asked.mapNotNull { answers[it.descriptor.id] }) else null
        return CatalogSearch(results, fresh, pending, error)
    }

    /** All rows so far (earlier pages first) and the ones this page adds. */
    private fun merge(ok: List<SourceAnswer.Answered<SearchResults>>, session: Pages.Session): Pair<List<TrackGroup>, List<TrackGroup>> {
        val rows = grouper.group(ok.map { TrackGrouper.Ranked(it.source, it.value.tracks) }, session.rows)
        return rows to rows.drop(session.rows.size)
    }

    // --- Things that belong to one source ----------------------------------------------------------------

    suspend fun artist(id: ArtistId): Outcome<ArtistDetail> =
        route(id.sourceId) { s -> s.catalog?.artist(id) }

    suspend fun album(id: AlbumId): Outcome<AlbumDetail> =
        route(id.sourceId) { s -> s.catalog?.album(id) }

    /** A playlist — or an album a source presents as one — from the source it belongs to. */
    suspend fun playlist(id: PlaylistId): Outcome<PlaylistDetail> =
        route(id.sourceId) { s -> s.catalog?.playlist(id) }

    suspend fun relatedArtists(id: ArtistId, limit: Int): Outcome<List<ArtistSummary>> {
        if (!recommends(id)) return Outcome.Success(emptyList())
        return route(id.sourceId) { s -> s.recommendations?.relatedArtists(id, limit) }
    }

    private suspend fun <T> route(source: SourceId, call: suspend (MusicSource) -> Outcome<T>?): Outcome<T> {
        val s = registry.get(source)?.takeIf { it.descriptor.environment == environment }
            ?: return Outcome.Failure(PodiumError.NotFound("source"))
        if (!registry.isEnabled(source)) return Outcome.Failure(PodiumError.PolicyDisabled("source turned off"))
        return fanOut.askOne(s) { call(it) ?: Outcome.Failure(PodiumError.NotFound("not offered")) }
    }

    // --- Discovery ---------------------------------------------------------------------------------------

    /**
     * Every source's shelves. Shelves with the same name ("Trending this week") from several sources
     * become one, their songs merged like search results; ids name every source a shelf came from.
     */
    suspend fun shelves(): Outcome<List<Shelf>> {
        val sources = discoverySources()
        if (sources.isEmpty()) return Outcome.Failure(NO_SOURCE)
        val answers = fanOut.askAll(sources) { it.discovery!!.shelves() }
        val ok = answers.filterIsInstance<SourceAnswer.Answered<List<Shelf>>>()
        if (ok.isEmpty()) return Outcome.Failure(errorOf(answers).takeUnless { answers.isEmpty() } ?: fanOut.unavailable(sources.first().descriptor.id))
        val byTitle = linkedMapOf<String, MutableList<Pair<SourceId, Shelf>>>()
        ok.forEach { a -> a.value.forEach { shelf -> byTitle.getOrPut(shelf.title.trim().lowercase()) { mutableListOf() } += a.source to shelf } }
        return Outcome.Success(
            byTitle.values.map { members ->
                val rows = grouper.group(members.map { (source, shelf) -> TrackGrouper.Ranked(source, shelf.tracks) })
                rows.forEach(equivalence::remember)
                Shelf(
                    id = ShelfIds.encode(members.map { (source, shelf) -> ScopedKey.of(source, shelf.id) }),
                    title = members.first().second.title,
                    tracks = rows.map { it.track },
                    playlists = interleave(members.map { it.second.playlists }).distinctBy { it.id },
                )
            },
        )
    }

    /** More of a shelf from [shelves], from every source it came from. */
    suspend fun shelf(id: String, offset: Int, limit: Int): Outcome<ShelfPage> {
        val members = ShelfIds.decode(id)
        val sources = discoverySources().filter { s -> members.any { it.sourceId == s.descriptor.id } }
        if (sources.isEmpty()) return Outcome.Failure(NO_SOURCE)
        val keyOf = members.associate { it.sourceId to it.key }
        val session = pages.open("shelf|$limit|$id", offset)
        repeat(MAX_EMPTY_PAGES) {
            val answers = fanOut.askAll(sources) { s -> s.discovery!!.shelf(keyOf.getValue(s.descriptor.id), session.offsetOf(s.descriptor.id), limit) }
            val ok = answers.filterIsInstance<SourceAnswer.Answered<ShelfPage>>()
            if (ok.isEmpty()) return Outcome.Failure(errorOf(answers))
            val rows = grouper.group(ok.map { TrackGrouper.Ranked(it.source, it.value.tracks) }, session.rows)
            val fresh = rows.drop(session.rows.size)
            rows.forEach(equivalence::remember)
            pages.advance(session, ok.associate { it.source to it.value.tracks.size + it.value.playlists.size }, rows)
            val playlists = interleave(ok.map { it.value.playlists }).distinctBy { it.id }
            if (fresh.isNotEmpty() || playlists.isNotEmpty() || ok.all { it.value.tracks.isEmpty() }) {
                return Outcome.Success(ShelfPage(fresh.map { it.track }, playlists))
            }
        }
        return Outcome.Success(ShelfPage())
    }

    /** Genres from every source, most popular first per source, each name once. */
    suspend fun genres(): Outcome<List<String>> {
        val sources = discoverySources()
        if (sources.isEmpty()) return Outcome.Failure(NO_SOURCE)
        val answers = fanOut.askAll(sources) { it.discovery!!.genres() }
        val ok = answers.filterIsInstance<SourceAnswer.Answered<List<String>>>()
        if (ok.isEmpty()) return Outcome.Failure(errorOf(answers).takeUnless { answers.isEmpty() } ?: fanOut.unavailable(sources.first().descriptor.id))
        val holders = mutableMapOf<String, MutableSet<SourceId>>()
        ok.forEach { a -> a.value.forEach { holders.getOrPut(it.trim().lowercase()) { mutableSetOf() } += a.source } }
        genreHolders = holders
        return Outcome.Success(interleave(ok.map { it.value }).distinctBy { it.trim().lowercase() })
    }

    /** Music in a genre from the sources that have it (all of them, if not known yet). */
    suspend fun genre(name: String, offset: Int, limit: Int): Outcome<List<Track>> {
        val all = discoverySources()
        val holders = genreHolders[name.trim().lowercase()]
        val sources = if (holders == null) all else all.filter { it.descriptor.id in holders }.ifEmpty { all }
        if (sources.isEmpty()) return Outcome.Failure(NO_SOURCE)
        val session = pages.open("genre|$limit|${name.trim().lowercase()}", offset)
        repeat(MAX_EMPTY_PAGES) {
            val answers = fanOut.askAll(sources) { s -> s.discovery!!.genre(name, session.offsetOf(s.descriptor.id), limit) }
            val ok = answers.filterIsInstance<SourceAnswer.Answered<List<Track>>>()
            if (ok.isEmpty()) return Outcome.Failure(errorOf(answers))
            val rows = grouper.group(ok.map { TrackGrouper.Ranked(it.source, it.value) }, session.rows)
            val fresh = rows.drop(session.rows.size)
            rows.forEach(equivalence::remember)
            pages.advance(session, ok.associate { it.source to it.value.size }, rows)
            if (fresh.isNotEmpty() || ok.all { it.value.isEmpty() }) return Outcome.Success(fresh.map { it.track })
        }
        return Outcome.Success(emptyList())
    }

    private fun discoverySources() = sources(Capability.BROWSE).filter { it.discovery != null }

    // --- Helpers -----------------------------------------------------------------------------------------

    /** The most telling reason nothing came back: offline beats busy beats anything else. */
    private fun errorOf(answers: List<SourceAnswer<*>>): PodiumError {
        val errors = answers.map {
            when (it) {
                is SourceAnswer.Failed -> it.error
                is SourceAnswer.TimedOut -> PodiumError.Network("Timed out")
                is SourceAnswer.Answered -> null
            }
        }.filterNotNull()
        return errors.firstOrNull { it == PodiumError.Offline }
            ?: errors.firstOrNull { it is PodiumError.RateLimited }
            ?: errors.firstOrNull()
            ?: NO_SOURCE
    }

    private fun <T> interleave(lists: List<List<T>>): List<T> {
        val deepest = lists.maxOfOrNull { it.size } ?: 0
        return (0 until deepest).flatMap { i -> lists.mapNotNull { it.getOrNull(i) } }
    }

    /**
     * Paging through a merged list: each source has its own place in its own list, and rows already
     * shown stay put (a later copy of a shown song joins its row instead of appearing again). Bounded.
     */
    private class Pages(private val capacity: Int = 24) {
        /** [start]: where every source begins — 0, or the caller's offset for a list reopened mid-way. */
        class Session(private val start: Int) {
            private val offsets = mutableMapOf<SourceId, Int>()
            @Volatile var rows: List<TrackGroup> = emptyList()

            /** Where [source] continues in its own list. */
            @Synchronized fun offsetOf(source: SourceId): Int = offsets[source] ?: start

            @Synchronized fun advance(returned: Map<SourceId, Int>, all: List<TrackGroup>) {
                returned.forEach { (source, count) -> offsets[source] = offsetOf(source) + count }
                rows = all
            }
        }

        private val sessions = LinkedHashMap<String, Session>(16, 0.75f, true)

        /** Offset 0 starts afresh; later pages continue the session (or start mid-way if it's gone). */
        @Synchronized
        fun open(key: String, offset: Int): Session {
            val existing = sessions[key]
            if (offset > 0 && existing != null) return existing
            val fresh = Session(start = offset)
            sessions[key] = fresh
            while (sessions.size > capacity) sessions.remove(sessions.keys.first())
            return fresh
        }

        fun advance(session: Session, returned: Map<SourceId, Int>, rows: List<TrackGroup>) = session.advance(returned, rows)
    }

    private companion object {
        val NO_SOURCE = PodiumError.NotFound("online source")

        /** Pages whose songs all turned out to be copies of songs already shown, before giving up. */
        const val MAX_EMPTY_PAGES = 3
    }
}

/** Shelf ids that name every source a merged shelf came from. */
internal object ShelfIds {
    private const val SEP = '\u001E'

    fun encode(keys: List<ScopedKey>): String = keys.joinToString("$SEP") { it.value }

    fun decode(id: String): List<ScopedKey> = id.split(SEP).filter { TrackId.SEPARATOR in it }.map(::ScopedKey)
}
