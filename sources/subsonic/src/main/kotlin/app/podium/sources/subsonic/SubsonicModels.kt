package app.podium.sources.subsonic

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/*
 * The parts of the (Open)Subsonic schema Podium reads. Every field is optional except ids: servers
 * differ, and OpenSubsonic additions (artists[], isrc, musicBrainzId, explicitStatus, bitDepth…)
 * are only present on servers that support them.
 */

@Serializable
internal data class ArtistRefDto(val id: String? = null, val name: String = "")

@Serializable
internal data class SongDto(
    val id: String,
    val title: String = "",
    val album: String? = null,
    val albumId: String? = null,
    val artist: String? = null,
    val artistId: String? = null,
    val displayArtist: String? = null,
    val artists: List<ArtistRefDto> = emptyList(),
    val displayAlbumArtist: String? = null,
    val albumArtists: List<ArtistRefDto> = emptyList(),
    val track: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val coverArt: String? = null,
    val duration: Int? = null,
    val bitRate: Int? = null,
    val bitDepth: Int? = null,
    val samplingRate: Int? = null,
    val channelCount: Int? = null,
    val suffix: String? = null,
    val contentType: String? = null,
    val isVideo: Boolean = false,
    val musicBrainzId: String? = null,
    val isrc: JsonElement? = null,
    val explicitStatus: String? = null,
)

@Serializable
internal data class AlbumDto(
    val id: String,
    val name: String = "",
    val artist: String? = null,
    val artistId: String? = null,
    val displayArtist: String? = null,
    val coverArt: String? = null,
    val songCount: Int? = null,
    val year: Int? = null,
    val song: List<SongDto> = emptyList(),
)

@Serializable
internal data class ArtistDto(
    val id: String,
    val name: String = "",
    val coverArt: String? = null,
    val albumCount: Int? = null,
    val album: List<AlbumDto> = emptyList(),
)

@Serializable
internal data class PlaylistDto(
    val id: String,
    val name: String = "",
    val owner: String? = null,
    val songCount: Int? = null,
    val coverArt: String? = null,
    val entry: List<SongDto> = emptyList(),
)

@Serializable
internal data class SearchResultDto(
    val artist: List<ArtistDto> = emptyList(),
    val album: List<AlbumDto> = emptyList(),
    val song: List<SongDto> = emptyList(),
)

@Serializable
internal data class SongListDto(val song: List<SongDto> = emptyList())

@Serializable
internal data class AlbumListDto(val album: List<AlbumDto> = emptyList())

@Serializable
internal data class PlaylistsDto(val playlist: List<PlaylistDto> = emptyList())

@Serializable
internal data class ArtistInfoDto(val similarArtist: List<ArtistDto> = emptyList())

@Serializable
internal data class GenreDto(val value: String = "", val songCount: Int = 0)

@Serializable
internal data class GenresDto(val genre: List<GenreDto> = emptyList())
