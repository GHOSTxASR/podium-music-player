package app.podium.sources.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * A folder that holds music on a source whose library comes from storage (D-32). [path] is
 * relative to the storage root, slash-separated and slash-terminated ("Music/Albums/").
 */
data class MusicFolder(val path: String, val songCount: Int)

/**
 * Which folders a source's library is read from. A folder is read when the closest choice at or
 * above it is an inclusion; with no choice above it, it's read only if nothing is included
 * explicitly. So: nothing chosen reads everything; including "Music/" reads only Music; including
 * "Music/" and excluding "Music/Podcasts/" reads Music without its podcasts.
 */
data class FolderSelection(val included: Set<String> = emptySet(), val excluded: Set<String> = emptySet()) {

    fun includes(path: String): Boolean {
        val p = normalize(path)
        val inc = included.filter { p.startsWith(it) }.maxOfOrNull { it.length } ?: -1
        val exc = excluded.filter { p.startsWith(it) }.maxOfOrNull { it.length } ?: -1
        return when {
            inc < 0 && exc < 0 -> included.isEmpty()
            else -> inc > exc
        }
    }

    /** True when some folder strictly inside [path] is chosen differently from [path] itself. */
    fun isMixedBelow(path: String): Boolean {
        val p = normalize(path)
        val mine = includes(p)
        return (included + excluded).any { it != p && it.startsWith(p) && includes(it) != mine }
    }

    /** Flip [path] (and everything inside it), keeping the rest of the choice as it was. */
    fun toggled(path: String): FolderSelection {
        val p = normalize(path)
        val wanted = !includes(p)
        val inc = included.filterNot { it.startsWith(p) }.toMutableSet()
        val exc = excluded.filterNot { it.startsWith(p) }.toMutableSet()
        val cleared = FolderSelection(inc, exc)
        if (cleared.includes(p) == wanted) return cleared
        // Including the first folder narrows "everything" to just it: say so explicitly.
        if (wanted) inc += p else exc += p
        return FolderSelection(inc, exc)
    }

    companion object {
        /** Read every folder. */
        val Everything = FolderSelection()

        /**
         * The starting choice: everything except where apps keep their own audio (messengers'
         * voice notes and shared clips, call and voice recordings).
         */
        val Default = FolderSelection(excluded = setOf("Android/media/", "WhatsApp/", "Telegram/", "Recordings/"))

        fun normalize(path: String): String = path.trim('/').let { if (it.isEmpty()) "" else "$it/" }
    }
}

/** A source whose library is read from folders the listener can choose. */
interface FolderFacet {
    /** Every folder that holds music, chosen or not, with its own song count. */
    fun folders(): Flow<List<MusicFolder>>

    val selection: StateFlow<FolderSelection>

    fun select(selection: FolderSelection)
}
