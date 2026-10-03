package app.podium.core.model

/**
 * How the library groups songs into artists and albums (D-29, D-31). Provider-neutral and
 * source-qualified: songs without album metadata are grouped under an "Unknown album" per artist
 * and source, never merged across sources by title.
 */
object LibraryGrouping {

    /** The artist a song is filed under: its first credit, or its display name within its source. */
    fun artistIdOf(track: Track): ArtistId =
        track.artists.firstOrNull()?.id ?: ArtistId.of(track.source.sourceId, track.artistDisplay.trim().lowercase())

    /** The album a song is filed under. */
    fun albumIdOf(track: Track): AlbumId =
        track.album?.id ?: AlbumId.of(track.source.sourceId, "unknown/" + artistIdOf(track).value)

    /** The name an artist is filed under. */
    fun artistNameOf(track: Track): String = track.artists.firstOrNull()?.name ?: track.artistDisplay

    /**
     * A sort key: case- and accent-folded, with a leading "The"/"A"/"An" and punctuation dropped, so
     * "The Beatles" files under B and "Éclair" next to "Eclair". Collation-free so SQL can order by it.
     */
    fun sortKey(text: String): String {
        val folded = java.text.Normalizer.normalize(text.trim(), java.text.Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
        val words = folded.replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        val stripped = words.replaceFirst(Regex("^(the|a|an) (?=\\S)"), "")
        return stripped.ifEmpty { words }
    }
}
