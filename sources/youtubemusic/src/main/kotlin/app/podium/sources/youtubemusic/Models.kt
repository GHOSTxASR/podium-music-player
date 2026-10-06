package app.podium.sources.youtubemusic

import app.podium.core.model.MediaKind

/*
 * What the catalogue's answers contain, before they become Podium's provider-neutral models
 * (YouTubeMusicMapper). Ids here are the provider's own and never leave this module raw.
 */

internal data class Thumb(val url: String, val width: Int)

/** A name, with the provider's browse id when it links somewhere. */
internal data class YtmRef(val name: String, val id: String? = null)

internal sealed interface YtmItem

/** Something playable: a song, a music video, another video, or an episode ([kind]). */
internal data class YtmSong(
    val videoId: String,
    val title: String,
    val artists: List<YtmRef>,
    val album: YtmRef? = null,
    val durationMs: Long? = null,
    val thumbnails: List<Thumb> = emptyList(),
    val explicit: Boolean = false,
    val kind: MediaKind = MediaKind.SONG,
    /** The entry's id within a playlist (needed to remove it there). */
    val setVideoId: String? = null,
    val playable: Boolean = true,
    val trackNumber: Int? = null,
    val year: Int? = null,
) : YtmItem

internal data class YtmAlbum(
    val browseId: String,
    val title: String,
    val artists: List<YtmRef>,
    val year: Int? = null,
    val thumbnails: List<Thumb> = emptyList(),
    val explicit: Boolean = false,
    /** "Album", "Single", "EP" — as the catalogue labels it. */
    val type: String? = null,
) : YtmItem

internal data class YtmArtist(
    val browseId: String,
    val name: String,
    val thumbnails: List<Thumb> = emptyList(),
) : YtmItem

internal data class YtmPlaylist(
    /** Without the "VL" browse prefix. */
    val playlistId: String,
    val title: String,
    val author: String? = null,
    val trackCount: Int? = null,
    val thumbnails: List<Thumb> = emptyList(),
) : YtmItem

/** A titled row of items (home, an artist's albums…). */
internal data class YtmShelf(val title: String, val items: List<YtmItem>, val moreBrowseId: String? = null)

/** An album or playlist page. */
internal data class YtmCollection(
    val title: String,
    val subtitle: String?,
    val artists: List<YtmRef>,
    val year: Int?,
    val thumbnails: List<Thumb>,
    val tracks: List<YtmSong>,
    /** The playlist that plays this collection in order (an album's playlist id). */
    val playlistId: String?,
    val trackCount: Int?,
    val continuation: YouTubeMusicClient.Continuation?,
    val isAlbum: Boolean,
)

internal data class YtmArtistPage(
    val name: String,
    val thumbnails: List<Thumb>,
    val songs: List<YtmSong>,
    /** A playlist holding all their songs, when the page links one. */
    val songsPlaylistId: String?,
    val albums: List<YtmAlbum>,
    val singles: List<YtmAlbum>,
    val videos: List<YtmSong>,
    val playlists: List<YtmPlaylist>,
    val related: List<YtmArtist>,
    /** The playlist id of the artist's radio. */
    val radioPlaylistId: String?,
    val shufflePlaylistId: String?,
)

/** A page of a list that continues: what's here and how to get more. */
internal data class YtmPage<T>(val items: List<T>, val continuation: YouTubeMusicClient.Continuation?)

internal data class YtmAccount(val name: String, val handle: String?, val photo: List<Thumb>)

/** A song in the account's history, under the catalogue's own label for when ("Today"). */
internal data class YtmHistoryItem(val song: YtmSong, val period: String?)
