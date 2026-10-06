package app.podium.core.lyrics

/*
 * Lyrics, provider-independent (LYRICS_ARCHITECTURE.md §2). A provider supplies words; Podium decides
 * how they follow the music. Synced lyrics carry the moment each line is sung; plain lyrics never
 * pretend to.
 */

/**
 * One line of synced lyrics: sung from [startMs]. An empty [text] is a gap between lines.
 * [endMs] is when the line ends, when the provider says (otherwise the next line's start bounds it);
 * [words] are the provider's own word times (enhanced LRC), empty when it only times whole lines.
 */
data class LyricsLine(
    val startMs: Long,
    val text: String,
    val endMs: Long? = null,
    val words: List<LyricsWord> = emptyList(),
)

/** A word of a line, sung from [startMs] — only from a provider that times words. */
data class LyricsWord(val startMs: Long, val text: String)

sealed interface Lyrics {
    /** Lines with times, in order. Never empty. */
    data class Synced(val lines: List<LyricsLine>) : Lyrics {
        init {
            require(lines.isNotEmpty()) { "synced lyrics need lines" }
        }
    }

    /** Words without times: shown as they are, moved through by the listener. Never empty. */
    data class Plain(val lines: List<String>) : Lyrics {
        init {
            require(lines.isNotEmpty()) { "plain lyrics need lines" }
        }
    }

    /** The provider knows the song has no words. */
    data object Instrumental : Lyrics
}

/** What Podium knows about the song whose lyrics it wants — metadata only, never a provider's id. */
data class LyricsRequest(
    /** Stable identity of the song for caching (a source-qualified track id). */
    val key: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
)

/** The answer the screen shows. */
sealed interface LyricsResult {
    data class Found(val lyrics: Lyrics, val provider: LyricsAttribution) : LyricsResult

    /** Nothing confident enough: better no lyrics than another song's. */
    data object NotFound : LyricsResult

    /** The listener hasn't agreed yet to send song details to the lyrics service (D-11). */
    data object NeedsConsent : LyricsResult

    data object Offline : LyricsResult

    /** The service failed, timed out or answered nonsense; trying again later may work. */
    data object Failed : LyricsResult

    data object RateLimited : LyricsResult
}

/** Who the words came from, for the small credit in Settings and on the lyrics screen's details. */
data class LyricsAttribution(val name: String, val url: String)
