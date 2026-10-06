package app.podium.sources.youtubemusic

import java.util.Base64

/**
 * Typed search ("only songs", "only albums"). The catalogue's own answer lists its filters with the
 * parameters that select them; those are always preferred ([fromAnswer]). When an answer offers
 * none, the filter is encoded the same way: a small protocol-buffer message naming one result type.
 */
internal enum class SearchFilter(val labels: Set<String>, private val field: Int) {
    SONGS(setOf("songs"), 1),
    VIDEOS(setOf("videos"), 2),
    ALBUMS(setOf("albums"), 3),
    ARTISTS(setOf("artists"), 4),
    FEATURED_PLAYLISTS(setOf("featured playlists"), 7),
    COMMUNITY_PLAYLISTS(setOf("community playlists", "playlists"), 8);

    /** The parameters selecting this filter: { 2: { 17: { <field>: 1 } } }, base64, as the page sends it. */
    fun encoded(): String {
        val inner = byteArrayOf((field shl 3).toByte(), 1)
        val wrapped = byteArrayOf(0x8a.toByte(), 0x01, inner.size.toByte()) + inner
        val message = byteArrayOf(0x12, wrapped.size.toByte()) + wrapped
        return Base64.getEncoder().encodeToString(message).replace("=", "%3D")
    }

    fun fromAnswer(filters: Map<String, String>): String? = labels.firstNotNullOfOrNull { filters[it] }
}
