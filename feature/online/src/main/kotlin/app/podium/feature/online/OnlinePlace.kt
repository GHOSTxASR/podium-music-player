package app.podium.feature.online

import app.podium.core.model.ArtistId
import app.podium.core.model.PlaylistId

/**
 * Places in the ONLINE section of the paper (D-34). The app keeps them in its back stack as
 * strings ([encode]/[decode]) so they survive process death like every other place.
 */
sealed interface OnlinePlace {
    val title: String

    data object Menu : OnlinePlace { override val title = "Online" }
    data object Home : OnlinePlace { override val title = "Home" }
    data object Explore : OnlinePlace { override val title = "Explore" }
    data object Search : OnlinePlace { override val title = "Search" }
    data object Liked : OnlinePlace { override val title = "Liked songs" }
    data object Playlists : OnlinePlace { override val title = "Playlists" }
    data object Radio : OnlinePlace { override val title = "Radio" }
    data object History : OnlinePlace { override val title = "History" }
    data object Recent : OnlinePlace { override val title = "Recently played" }

    data class Shelf(val id: String, override val title: String) : OnlinePlace
    data class Genre(val name: String) : OnlinePlace { override val title get() = name }
    data class Artist(val id: ArtistId, val name: String) : OnlinePlace { override val title get() = name }
    data class Collection(val id: PlaylistId, override val title: String, val isAlbum: Boolean) : OnlinePlace
    data class MyPlaylist(val id: String, val name: String) : OnlinePlace { override val title get() = name }

    /** Naming a playlist: a new one (with [tracks] to put in it), or renaming [playlistId]. */
    data class NamePlaylist(val playlistId: String? = null, val currentName: String = "") : OnlinePlace {
        override val title get() = if (playlistId == null) "New playlist" else "Rename"
    }

    companion object {
        private const val SEP = '\u001F'

        fun encode(place: OnlinePlace): String = when (place) {
            Menu -> "menu"
            Home -> "home"
            Explore -> "explore"
            Search -> "search"
            Liked -> "liked"
            Playlists -> "playlists"
            Radio -> "radio"
            History -> "history"
            Recent -> "recent"
            is Shelf -> listOf("shelf", place.id, place.title).joinToString("$SEP")
            is Genre -> listOf("genre", place.name).joinToString("$SEP")
            is Artist -> listOf("artist", place.id.value, place.name).joinToString("$SEP")
            is Collection -> listOf("collection", place.id.value, place.title, place.isAlbum.toString()).joinToString("$SEP")
            is MyPlaylist -> listOf("mine", place.id, place.name).joinToString("$SEP")
            is NamePlaylist -> listOf("name", place.playlistId.orEmpty(), place.currentName).joinToString("$SEP")
        }

        fun decode(text: String): OnlinePlace? {
            val p = text.split(SEP)
            return when (p[0]) {
                "menu" -> Menu
                "home" -> Home
                "explore" -> Explore
                "search" -> Search
                "liked" -> Liked
                "playlists" -> Playlists
                "radio" -> Radio
                "history" -> History
                "recent" -> Recent
                "shelf" -> p.getOrNull(2)?.let { Shelf(p[1], it) }
                "genre" -> p.getOrNull(1)?.let { Genre(it) }
                "artist" -> p.getOrNull(2)?.let { Artist(ArtistId(p[1]), it) }
                "collection" -> p.getOrNull(3)?.let { Collection(PlaylistId(p[1]), p[2], it.toBoolean()) }
                "mine" -> p.getOrNull(2)?.let { MyPlaylist(p[1], it) }
                "name" -> NamePlaylist(p.getOrNull(1)?.takeIf { it.isNotEmpty() }, p.getOrNull(2).orEmpty())
                else -> null
            }
        }
    }
}
