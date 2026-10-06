package app.podium.sources.youtubemusic

import app.podium.core.model.MediaKind
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Turns the catalogue's page answers into [YtmItem]s. Written against the structure of the music
 * web client's answers as of 2026-10 (YOUTUBE_MUSIC_IMPLEMENTATION_NOTES.md §3), tolerant by design:
 * items are recognised by what they link to and how they play rather than by where they sit, both the
 * older and the newer page layouts are read, and anything unrecognisable is skipped, never guessed.
 */
internal object YouTubeMusicParser {

    /** What a list holds when its rows don't say (a typed search, an album's tracks). */
    enum class Hint { NONE, SONG, VIDEO, ALBUM, ARTIST, PLAYLIST, EPISODE }

    // --- Single items --------------------------------------------------------------------------------

    /** Any list or grid entry, whatever its renderer. */
    fun item(entry: JsonElement, hint: Hint = Hint.NONE): YtmItem? {
        val (name, r) = entry.renderer() ?: return null
        return when (name) {
            "musicResponsiveListItemRenderer" -> responsiveItem(r, hint)
            "musicTwoRowItemRenderer" -> twoRowItem(r)
            "playlistPanelVideoRenderer" -> panelItem(r)
            "playlistPanelVideoWrapperRenderer" -> r.at("primaryRenderer")?.let { item(it, hint) }
            else -> null
        }
    }

    fun responsiveItem(r: JsonObject, hint: Hint = Hint.NONE): YtmItem? {
        val columns = r.at("flexColumns").arr.map { it.at("musicResponsiveListItemFlexColumnRenderer", "text") }
        val titleRuns = columns.getOrNull(0).runs()
        val title = titleRuns.joinToString("") { it.runText() }.trim().takeIf { it.isNotEmpty() } ?: return null
        val detail = columns.drop(1).flatMapIndexed { i, c -> (if (i > 0) listOf(SEPARATOR_RUN) else emptyList()) + c.runs() }
        val fixed = r.at("fixedColumns").arr.mapNotNull { it.at("musicResponsiveListItemFixedColumnRenderer", "text").text() }
        val thumbs = thumbnails(r.at("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails"))
        val videoId = r.at("playlistItemData", "videoId").str
            ?: r.at("overlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer", "playNavigationEndpoint", "watchEndpoint", "videoId").str
            ?: titleRuns.firstNotNullOfOrNull { it.at("navigationEndpoint", "watchEndpoint", "videoId").str }
        val label = detail.firstOrNull()?.runText()?.trim()?.takeIf { it in TYPE_LABELS }
        val explicit = isExplicit(r)
        if (videoId != null) {
            val kind = kindOf(r.findFirst("musicVideoType").str, label, hint)
            val durationText = fixed.firstOrNull { DURATION.matches(it.trim()) } ?: detail.firstOrNull { DURATION.matches(it.runText().trim()) }?.runText()
            return YtmSong(
                videoId = videoId,
                title = title,
                artists = artistsIn(detail, label),
                album = detail.firstNotNullOfOrNull { run -> pageRef(run, ALBUM_PAGE) },
                durationMs = durationText?.let(::parseDuration),
                thumbnails = thumbs,
                explicit = explicit,
                kind = kind,
                setVideoId = r.at("playlistItemData", "playlistSetVideoId").str,
                playable = r["musicItemRendererDisplayPolicy"].str != GREYED_OUT,
                trackNumber = r.at("index").text()?.trim()?.toIntOrNull(),
            )
        }
        val browse = r.at("navigationEndpoint", "browseEndpoint") ?: titleRuns.firstNotNullOfOrNull { it.at("navigationEndpoint", "browseEndpoint") }
        val browseId = browse.at("browseId").str ?: return null
        val page = browse.at("browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig", "pageType").str
        return pageItem(browseId, page, title, detail, label, thumbs, explicit, hint)
    }

    fun twoRowItem(r: JsonObject): YtmItem? {
        val titleRuns = r.at("title").runs()
        val title = titleRuns.joinToString("") { it.runText() }.trim().takeIf { it.isNotEmpty() } ?: return null
        val detail = r.at("subtitle").runs()
        val thumbs = thumbnails(r.at("thumbnailRenderer", "musicThumbnailRenderer", "thumbnail", "thumbnails"))
        val endpoint = r.at("navigationEndpoint") ?: titleRuns.firstOrNull()?.at("navigationEndpoint")
        val label = detail.firstOrNull()?.runText()?.trim()?.takeIf { it in TYPE_LABELS }
        val explicit = isExplicit(r)
        endpoint.at("watchEndpoint", "videoId").str?.let { videoId ->
            return YtmSong(
                videoId = videoId,
                title = title,
                artists = artistsIn(detail, label),
                album = detail.firstNotNullOfOrNull { pageRef(it, ALBUM_PAGE) },
                thumbnails = thumbs,
                explicit = explicit,
                kind = kindOf(endpoint.findFirst("musicVideoType").str, label, Hint.NONE),
            )
        }
        val browse = endpoint.at("browseEndpoint") ?: return null
        val browseId = browse.at("browseId").str ?: return null
        val page = browse.at("browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig", "pageType").str
        return pageItem(browseId, page, title, detail, label, thumbs, explicit, Hint.NONE)
    }

    /** A row of the watch-next list (a radio, a queue). */
    fun panelItem(r: JsonObject): YtmSong? {
        val videoId = r["videoId"].str ?: r.at("navigationEndpoint", "watchEndpoint", "videoId").str ?: return null
        val title = r.at("title").text()?.trim() ?: return null
        val byline = r.at("longBylineText").runs().ifEmpty { r.at("shortBylineText").runs() }
        return YtmSong(
            videoId = videoId,
            title = title,
            artists = artistsIn(byline, null),
            album = byline.firstNotNullOfOrNull { pageRef(it, ALBUM_PAGE) },
            durationMs = r.at("lengthText").text()?.let(::parseDuration),
            thumbnails = thumbnails(r.at("thumbnail", "thumbnails")),
            explicit = isExplicit(r),
            kind = kindOf(r.findFirst("musicVideoType").str, null, Hint.NONE),
            year = byline.firstNotNullOfOrNull { YEAR.matchEntire(it.runText().trim())?.value?.toIntOrNull() },
        )
    }

    private fun pageItem(
        browseId: String,
        page: String?,
        title: String,
        detail: List<JsonObject>,
        label: String?,
        thumbs: List<Thumb>,
        explicit: Boolean,
        hint: Hint,
    ): YtmItem? {
        val kind = when {
            page == ALBUM_PAGE -> Hint.ALBUM
            page == ARTIST_PAGE || page == LIBRARY_ARTIST_PAGE -> Hint.ARTIST
            page == PLAYLIST_PAGE -> Hint.PLAYLIST
            page != null -> return null // profiles, podcasts, episodes' pages: not music to browse here
            browseId.startsWith("MPRE") -> Hint.ALBUM
            browseId.startsWith("UC") || browseId.startsWith("MPLAUC") -> Hint.ARTIST
            browseId.startsWith("VL") -> Hint.PLAYLIST
            else -> hint
        }
        return when (kind) {
            Hint.ALBUM -> YtmAlbum(
                browseId = browseId,
                title = title,
                artists = artistsIn(detail, label),
                year = detail.firstNotNullOfOrNull { YEAR.matchEntire(it.runText().trim())?.value?.toIntOrNull() },
                thumbnails = thumbs,
                explicit = explicit,
                type = label?.takeIf { it in ALBUM_LABELS },
            )
            Hint.ARTIST -> YtmArtist(browseId.removePrefix("MPLA"), title, thumbs)
            Hint.PLAYLIST -> YtmPlaylist(
                playlistId = browseId.removePrefix("VL"),
                title = title,
                author = plainParts(detail, label).firstOrNull { COUNT.find(it) == null && !it.endsWith(" views") },
                trackCount = detail.firstNotNullOfOrNull { run -> COUNT.find(run.runText())?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() },
                thumbnails = thumbs,
            )
            else -> null
        }
    }

    // --- Search ------------------------------------------------------------------------------------------

    /** A search answer: what was found, the filters the answer offers (label → params), and paging. */
    data class SearchPage(val items: List<YtmItem>, val filters: Map<String, String>, val continuation: YouTubeMusicClient.Continuation?, val noResults: Boolean)

    fun search(json: JsonObject): SearchPage {
        val tabs = json.at("contents", "tabbedSearchResultsRenderer", "tabs").arr
        val section = tabs.firstNotNullOfOrNull { it.at("tabRenderer", "content", "sectionListRenderer") }
            ?: json.at("contents", "sectionListRenderer")
        val filters = section.at("header", "chipCloudRenderer", "chips").arr.mapNotNull { chip ->
            val c = chip.at("chipCloudChipRenderer") ?: return@mapNotNull null
            val params = c.at("navigationEndpoint", "searchEndpoint", "params").str ?: return@mapNotNull null
            val label = c.at("uniqueId").str ?: c.at("text").text() ?: return@mapNotNull null
            label.trim().lowercase() to params
        }.toMap()
        val items = ArrayList<YtmItem>()
        var continuation: YouTubeMusicClient.Continuation? = null
        var noResults = false
        for (block in section.at("contents").arr) {
            val (name, r) = block.renderer() ?: continue
            when (name) {
                "musicCardShelfRenderer" -> {
                    cardItem(r)?.let(items::add)
                    r.at("contents").arr.mapNotNullTo(items) { item(it) }
                }
                "musicShelfRenderer" -> {
                    val hint = hintForTitle(r.at("title").text())
                    r.at("contents").arr.mapNotNullTo(items) { item(it, hint) }
                    continuation = continuation ?: continuationOf(r)
                }
                "itemSectionRenderer" -> {
                    val contents = r.at("contents").arr
                    if (contents.any { it.renderer()?.first == "messageRenderer" }) noResults = true
                    contents.mapNotNullTo(items) { item(it) }
                    continuation = continuation ?: continuationOf(r)
                }
            }
        }
        return SearchPage(items.distinctBy(::identity), filters, continuation, noResults && items.isEmpty())
    }

    /** More of a typed search. */
    fun searchContinuation(json: JsonObject, hint: Hint): YtmPage<YtmItem> {
        val (entries, continuation) = continuationEntries(json)
        return YtmPage(entries.mapNotNull { item(it, hint) }, continuation)
    }

    /** The top result card: an artist, album, song or playlist with its own layout. */
    private fun cardItem(r: JsonObject): YtmItem? {
        val titleRun = r.at("title").runs().firstOrNull() ?: return null
        val title = titleRun.runText().trim().takeIf { it.isNotEmpty() } ?: return null
        val detail = r.at("subtitle").runs()
        val label = detail.firstOrNull()?.runText()?.trim()?.takeIf { it in TYPE_LABELS }
        val thumbs = thumbnails(r.at("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails"))
        val endpoint = titleRun.at("navigationEndpoint") ?: r.at("onTap")
        endpoint.at("watchEndpoint", "videoId").str?.let { videoId ->
            return YtmSong(
                videoId = videoId,
                title = title,
                artists = artistsIn(detail, label),
                album = detail.firstNotNullOfOrNull { pageRef(it, ALBUM_PAGE) },
                durationMs = detail.firstOrNull { DURATION.matches(it.runText().trim()) }?.runText()?.let(::parseDuration),
                thumbnails = thumbs,
                explicit = isExplicit(r),
                kind = kindOf(endpoint.findFirst("musicVideoType").str, label, Hint.NONE),
            )
        }
        val browse = endpoint.at("browseEndpoint") ?: return null
        val browseId = browse.at("browseId").str ?: return null
        val page = browse.at("browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig", "pageType").str
        return pageItem(browseId, page, title, detail, label, thumbs, isExplicit(r), Hint.NONE)
    }

    // --- Home and other shelf pages -------------------------------------------------------------------

    fun shelves(json: JsonObject): YtmPage<YtmShelf> {
        val (blocks, continuation) = sectionBlocks(json)
        return YtmPage(blocks.mapNotNull(::shelf).filter { it.items.isNotEmpty() }, continuation)
    }

    private fun shelf(block: JsonElement): YtmShelf? {
        val (name, r) = block.renderer() ?: return null
        val title: String
        val entries: List<JsonElement>
        val more: String?
        when (name) {
            "musicCarouselShelfRenderer", "musicImmersiveCarouselShelfRenderer" -> {
                val header = r.at("header").renderer()?.second
                title = header.at("title").text() ?: return null
                entries = r.at("contents").arr
                more = header.at("moreContentButton", "buttonRenderer", "navigationEndpoint", "browseEndpoint", "browseId").str
                    ?: header.at("title").runs().firstNotNullOfOrNull { it.at("navigationEndpoint", "browseEndpoint", "browseId").str }
            }
            "musicShelfRenderer" -> {
                title = r.at("title").text() ?: return null
                entries = r.at("contents").arr
                more = r.at("bottomEndpoint", "browseEndpoint", "browseId").str
            }
            "gridRenderer" -> {
                title = r.at("header", "gridHeaderRenderer", "title").text() ?: return null
                entries = r.at("items").arr
                more = null
            }
            else -> return null
        }
        val hint = hintForTitle(title)
        return YtmShelf(title.trim(), entries.mapNotNull { item(it, hint) }.distinctBy(::identity), more)
    }

    // --- Albums and playlists ---------------------------------------------------------------------------

    fun collection(json: JsonObject, albumPage: Boolean): YtmCollection? {
        val header = json.findFirst("musicResponsiveHeaderRenderer")
            ?: json.findFirst("musicDetailHeaderRenderer")
            ?: json.at("header").renderer()?.second
        val title = header.at("title").text()?.trim()
        val shelf = json.findFirst("musicPlaylistShelfRenderer") ?: json.findFirst("musicShelfRenderer")
        if (title == null && shelf == null) return null
        val subtitle = header.at("subtitle").runs()
        val strapline = header.at("straplineTextOne").runs()
        val linkedArtists = (strapline + subtitle).mapNotNull { pageRef(it, ARTIST_PAGE) ?: pageRef(it, USER_CHANNEL_PAGE) }
        val artists = linkedArtists.ifEmpty {
            strapline.map { it.runText().trim() }.filter { it.isNotEmpty() && it != SEPARATOR }.map { YtmRef(it) }
        }
        val year = subtitle.firstNotNullOfOrNull { YEAR.matchEntire(it.runText().trim())?.value?.toIntOrNull() }
        val thumbs = thumbnails(header.at("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails"))
            .ifEmpty { thumbnails(header.at("thumbnail", "croppedSquareThumbnailRenderer", "thumbnail", "thumbnails")) }
        val counts = (header.at("secondSubtitle").runs() + subtitle).firstNotNullOfOrNull { run ->
            COUNT.find(run.runText())?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
        }
        val hint = if (albumPage) Hint.SONG else Hint.NONE
        val tracks = shelf.at("contents").arr.mapNotNull { item(it, hint) as? YtmSong }
        val playlistId = shelf.at("playlistId").str
            ?: json.findAll("watchEndpoint", 40).firstNotNullOfOrNull { it.at("playlistId").str }
            ?: json.findAll("watchPlaylistEndpoint", 10).firstNotNullOfOrNull { it.at("playlistId").str }
            ?: json.at("microformat", "microformatDataRenderer", "urlCanonical").str?.substringAfter("list=", "")?.substringBefore('&')?.takeIf { it.isNotEmpty() }
        return YtmCollection(
            title = title ?: "",
            subtitle = subtitle.joinToString("") { it.runText() }.takeIf { it.isNotBlank() },
            artists = artists,
            year = year,
            thumbnails = thumbs,
            tracks = tracks,
            playlistId = playlistId,
            trackCount = counts,
            continuation = shelf?.let { continuationOf(it) },
            isAlbum = albumPage,
        )
    }

    fun songsContinuation(json: JsonObject): YtmPage<YtmSong> {
        val (entries, continuation) = continuationEntries(json)
        return YtmPage(entries.mapNotNull { item(it, Hint.SONG) as? YtmSong }, continuation)
    }

    // --- Artists ------------------------------------------------------------------------------------------

    fun artist(json: JsonObject): YtmArtistPage? {
        val header = json.findFirst("musicImmersiveHeaderRenderer")
            ?: json.findFirst("musicVisualHeaderRenderer")
            ?: json.findFirst("musicResponsiveHeaderRenderer")
            ?: json.at("header").renderer()?.second
        val name = header.at("title").text()?.trim() ?: return null
        val thumbs = thumbnails(header.at("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails"))
            .ifEmpty { thumbnails(header.at("foregroundThumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails")) }
        val (blocks, _) = sectionBlocks(json)
        var songs: List<YtmSong> = emptyList()
        var songsPlaylist: String? = null
        val albums = ArrayList<YtmAlbum>()
        val singles = ArrayList<YtmAlbum>()
        val videos = ArrayList<YtmSong>()
        val playlists = ArrayList<YtmPlaylist>()
        val related = ArrayList<YtmArtist>()
        val carouselSongs = ArrayList<YtmSong>()
        for (block in blocks) {
            val (kind, r) = block.renderer() ?: continue
            if (kind == "musicShelfRenderer" && songs.isEmpty()) {
                songs = r.at("contents").arr.mapNotNull { item(it, Hint.SONG) as? YtmSong }
                songsPlaylist = (r.at("bottomEndpoint", "browseEndpoint", "browseId").str
                    ?: r.at("title").runs().firstNotNullOfOrNull { it.at("navigationEndpoint", "browseEndpoint", "browseId").str })
                    ?.takeIf { it.startsWith("VL") }?.removePrefix("VL")
                continue
            }
            val s = shelf(block) ?: continue
            val lower = s.title.lowercase()
            for (i in s.items) when (i) {
                is YtmAlbum -> if ("single" in lower || i.type == "Single" || i.type == "EP") singles += i else albums += i
                is YtmSong -> if (i.kind == MediaKind.SONG) carouselSongs += i else videos += i
                is YtmArtist -> related += i
                is YtmPlaylist -> playlists += i
            }
        }
        if (songs.isEmpty()) songs = carouselSongs
        val radio = header.at("startRadioButton", "buttonRenderer", "navigationEndpoint", "watchPlaylistEndpoint", "playlistId").str
            ?: header.findAll("watchPlaylistEndpoint", 4).mapNotNull { it.at("playlistId").str }.firstOrNull { it.startsWith("RD") }
        val shuffle = header.at("playButton", "buttonRenderer", "navigationEndpoint", "watchPlaylistEndpoint", "playlistId").str
        return YtmArtistPage(name, thumbs, songs, songsPlaylist, albums, singles, videos.filter { it.kind != MediaKind.EPISODE }, playlists, related, radio, shuffle)
    }

    // --- The watch-next list (radio, a song's details) ------------------------------------------------

    fun next(json: JsonObject): YtmPage<YtmSong> {
        val panel = json.findFirst("playlistPanelRenderer")
        val songs = panel.at("contents").arr.mapNotNull { item(it) as? YtmSong }
        return YtmPage(songs, panel?.let(::continuationOf))
    }

    fun nextContinuation(json: JsonObject): YtmPage<YtmSong> {
        val (entries, continuation) = continuationEntries(json)
        return YtmPage(entries.mapNotNull { item(it) as? YtmSong }, continuation)
    }

    // --- The account's library ---------------------------------------------------------------------------

    /** A library tab: a grid of playlists or albums, or a list of artists or songs. */
    fun library(json: JsonObject): YtmPage<YtmItem> {
        val grid = json.findFirst("gridRenderer")
        if (grid != null) return YtmPage(grid.at("items").arr.mapNotNull { item(it) }, continuationOf(grid))
        val shelf = json.findFirst("musicShelfRenderer") ?: json.findFirst("musicPlaylistShelfRenderer")
        return YtmPage(shelf.at("contents").arr.mapNotNull { item(it) }, shelf?.let(::continuationOf))
    }

    fun libraryContinuation(json: JsonObject): YtmPage<YtmItem> {
        val (entries, continuation) = continuationEntries(json)
        return YtmPage(entries.mapNotNull { item(it) }, continuation)
    }

    fun history(json: JsonObject): List<YtmHistoryItem> {
        val (blocks, _) = sectionBlocks(json)
        return blocks.flatMap { block ->
            val (name, r) = block.renderer() ?: return@flatMap emptyList()
            if (name != "musicShelfRenderer") return@flatMap emptyList()
            val period = r.at("title").text()?.trim()
            r.at("contents").arr.mapNotNull { (item(it, Hint.SONG) as? YtmSong)?.let { s -> YtmHistoryItem(s, period) } }
        }
    }

    fun account(json: JsonObject): YtmAccount? {
        val header = json.findFirst("activeAccountHeaderRenderer") ?: return null
        val name = header.at("accountName").text()?.trim() ?: return null
        return YtmAccount(name, header.at("channelHandle").text()?.trim(), thumbnails(header.at("accountPhoto", "thumbnails")))
    }

    // --- Shared pieces ----------------------------------------------------------------------------------

    /** The blocks of a page's main section list, and how to load more of them. */
    private fun sectionBlocks(json: JsonObject): Pair<List<JsonElement>, YouTubeMusicClient.Continuation?> {
        json.at("continuationContents", "sectionListContinuation")?.let { c ->
            return c.at("contents").arr to continuationOf(c)
        }
        val section = json.at("contents", "singleColumnBrowseResultsRenderer", "tabs").arr
            .firstNotNullOfOrNull { it.at("tabRenderer", "content", "sectionListRenderer") }
            ?: json.at("contents", "twoColumnBrowseResultsRenderer", "secondaryContents", "sectionListRenderer")
            ?: json.findFirst("sectionListRenderer")
        return section.at("contents").arr to section?.let(::continuationOf)
    }

    /** The entries of a continuation answer, in either of the catalogue's two styles. */
    private fun continuationEntries(json: JsonObject): Pair<List<JsonElement>, YouTubeMusicClient.Continuation?> {
        json.at("continuationContents")?.renderer()?.second?.let { c ->
            val entries = c.at("contents").arr.ifEmpty { c.at("items").arr }
            return entries to continuationOf(c)
        }
        val appended = json.at("onResponseReceivedActions").arr.flatMap { it.at("appendContinuationItemsAction", "continuationItems").arr }
        return appended to trailingContinuation(appended)
    }

    /** How to continue a list: the older `continuations` field, or a trailing continuation item. */
    fun continuationOf(list: JsonElement): YouTubeMusicClient.Continuation? {
        list.at("continuations").arr.firstNotNullOfOrNull { c ->
            (c.at("nextContinuationData") ?: c.at("nextRadioContinuationData"))?.at("continuation").str
        }?.let { return YouTubeMusicClient.Continuation(it, inBody = false) }
        return trailingContinuation(list.at("contents").arr.ifEmpty { list.at("items").arr })
    }

    private fun trailingContinuation(entries: List<JsonElement>): YouTubeMusicClient.Continuation? =
        entries.lastOrNull()?.at("continuationItemRenderer", "continuationEndpoint", "continuationCommand", "token").str
            ?.let { YouTubeMusicClient.Continuation(it, inBody = true) }

    fun thumbnails(e: JsonElement?): List<Thumb> =
        e.arr.mapNotNull { t ->
            val url = t.at("url").str?.takeIf { it.startsWith("https://") } ?: return@mapNotNull null
            Thumb(url, t.at("width")?.toString()?.toIntOrNull() ?: 0)
        }.sortedBy { it.width }

    /** The artists named in a detail line: linked artist runs, else the plain words before the album. */
    private fun artistsIn(runs: List<JsonObject>, label: String?): List<YtmRef> {
        val linked = runs.mapNotNull { pageRef(it, ARTIST_PAGE) ?: pageRef(it, USER_CHANNEL_PAGE) }
        if (linked.isNotEmpty()) return linked
        return plainParts(runs, label).firstOrNull()
            ?.takeUnless { DURATION.matches(it) || YEAR.matches(it) || it.endsWith(" views") || it.endsWith(" plays") }
            ?.let { listOf(YtmRef(it)) }
            .orEmpty()
    }

    /** The detail line split at its separators, without the type label. */
    private fun plainParts(runs: List<JsonObject>, label: String?): List<String> {
        val parts = ArrayList<String>()
        val current = StringBuilder()
        for (run in runs) {
            val text = run.runText()
            if (text.trim() == SEPARATOR.trim()) {
                if (current.isNotBlank()) parts += current.toString().trim()
                current.clear()
            } else current.append(text)
        }
        if (current.isNotBlank()) parts += current.toString().trim()
        return if (label != null && parts.firstOrNull() == label) parts.drop(1) else parts
    }

    private fun pageRef(run: JsonObject, pageType: String): YtmRef? {
        val browse = run.at("navigationEndpoint", "browseEndpoint") ?: return null
        val type = browse.at("browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig", "pageType").str
        if (type != pageType) return null
        val name = run.runText().trim().takeIf { it.isNotEmpty() } ?: return null
        return YtmRef(name, browse.at("browseId").str)
    }

    private fun isExplicit(r: JsonObject): Boolean =
        (r.at("badges").arr + r.at("subtitleBadges").arr + r.at("subtitleBadge").arr).any {
            it.at("musicInlineBadgeRenderer", "icon", "iconType").str == "MUSIC_EXPLICIT_BADGE"
        }

    fun kindOf(videoType: String?, label: String?, hint: Hint): MediaKind = when (videoType) {
        "MUSIC_VIDEO_TYPE_ATV", "MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK" -> MediaKind.SONG
        "MUSIC_VIDEO_TYPE_OMV", "MUSIC_VIDEO_TYPE_OFFICIAL_SOURCE_MUSIC" -> MediaKind.MUSIC_VIDEO
        "MUSIC_VIDEO_TYPE_UGC" -> MediaKind.VIDEO
        "MUSIC_VIDEO_TYPE_PODCAST_EPISODE" -> MediaKind.EPISODE
        else -> when (label) {
            "Song" -> MediaKind.SONG
            "Video" -> MediaKind.VIDEO
            "Episode" -> MediaKind.EPISODE
            else -> when (hint) {
                Hint.VIDEO -> MediaKind.VIDEO
                Hint.EPISODE -> MediaKind.EPISODE
                else -> MediaKind.SONG
            }
        }
    }

    private fun hintForTitle(title: String?): Hint {
        val t = title?.lowercase() ?: return Hint.NONE
        return when {
            "song" in t -> Hint.SONG
            "video" in t -> Hint.VIDEO
            "album" in t || "single" in t -> Hint.ALBUM
            "artist" in t -> Hint.ARTIST
            "playlist" in t -> Hint.PLAYLIST
            "episode" in t -> Hint.EPISODE
            else -> Hint.NONE
        }
    }

    fun identity(item: YtmItem): String = when (item) {
        is YtmSong -> "s:${item.videoId}"
        is YtmAlbum -> "a:${item.browseId}"
        is YtmArtist -> "r:${item.browseId}"
        is YtmPlaylist -> "p:${item.playlistId}"
    }

    /** "3:45", "1:02:03" → milliseconds. */
    fun parseDuration(text: String): Long? {
        val parts = text.trim().split(':')
        if (parts.size !in 2..3) return null
        val numbers = parts.map { it.toIntOrNull() ?: return null }
        return numbers.fold(0L) { acc, n -> acc * 60 + n } * 1000
    }

    private const val SEPARATOR = " • "
    private val SEPARATOR_RUN = JsonObject(mapOf("text" to jsonString(SEPARATOR)))
    private val DURATION = Regex("\\d{1,2}(:\\d{2}){1,2}")
    private val YEAR = Regex("(19|20)\\d{2}")
    private val COUNT = Regex("([\\d,]+)\\s+(songs?|tracks?|episodes?)")
    private const val GREYED_OUT = "MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT"
    const val ALBUM_PAGE = "MUSIC_PAGE_TYPE_ALBUM"
    const val ARTIST_PAGE = "MUSIC_PAGE_TYPE_ARTIST"
    const val PLAYLIST_PAGE = "MUSIC_PAGE_TYPE_PLAYLIST"
    private const val USER_CHANNEL_PAGE = "MUSIC_PAGE_TYPE_USER_CHANNEL"
    private const val LIBRARY_ARTIST_PAGE = "MUSIC_PAGE_TYPE_LIBRARY_ARTIST"
    private val ALBUM_LABELS = setOf("Album", "Single", "EP")
    private val TYPE_LABELS = setOf("Song", "Video", "Episode", "Album", "Single", "EP", "Artist", "Playlist", "Podcast", "Profile", "Station")
}
