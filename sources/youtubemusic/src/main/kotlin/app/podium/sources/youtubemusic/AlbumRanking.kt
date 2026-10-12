package app.podium.sources.youtubemusic

/**
 * The order albums found by a search are shown in (D-74). The catalogue mixes full albums with EPs
 * and singles (a single is often the song's own one-track release, or its video's), so:
 *
 * 1. An album titled exactly as searched leads.
 * 2. Then full albums, then EPs, then singles, then anything the catalogue didn't label.
 * 3. Otherwise the catalogue's own order.
 */
internal object AlbumRanking {

    fun rank(query: String, albums: List<YtmAlbum>): List<YtmAlbum> {
        val wanted = key(query)
        return albums.withIndex()
            .sortedWith(
                compareBy<IndexedValue<YtmAlbum>>(
                    { if (wanted.isNotEmpty() && key(it.value.title) == wanted) 0 else 1 },
                    { typeRank(it.value.type) },
                    { it.index },
                ),
            )
            .map { it.value }
    }

    private fun typeRank(type: String?): Int = when (type) {
        "Album" -> 0
        "EP" -> 1
        "Single" -> 2
        else -> 3
    }

    private fun key(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
}
