package app.podium.sources.audius

import app.podium.core.common.Outcome
import app.podium.core.common.PodiumError
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.UnknownHostException

/*
 * Audius' public REST API (https://api.audius.co/v1, verified against docs.audius.co and the live
 * Swagger definition on 2026-10-03). Read access is anonymous: requests identify the app with
 * `app_name`; an API key is only needed for signing users in and acting on their behalf.
 */

@Serializable
internal data class UserDto(
    val id: String,
    val name: String = "",
    val handle: String = "",
    @Serializable(with = ArtworkMapSerializer::class) val profile_picture: Map<String, String>? = null,
    val follower_count: Int? = null,
    val track_count: Int? = null,
    val album_count: Int? = null,
    val playlist_count: Int? = null,
    val is_verified: Boolean = false,
)

@Serializable
internal data class AccessDto(val stream: Boolean = true, val download: Boolean = false)

@Serializable
internal data class AlbumBacklinkDto(val playlist_id: Long? = null, val playlist_name: String? = null, val permalink: String? = null)

@Serializable
internal data class TrackDto(
    val id: String,
    val title: String = "",
    val duration: Long? = null,
    val genre: String? = null,
    val mood: String? = null,
    val release_date: String? = null,
    val user: UserDto? = null,
    @Serializable(with = ArtworkMapSerializer::class) val artwork: Map<String, String>? = null,
    val permalink: String? = null,
    val is_streamable: Boolean = true,
    val stream_conditions: JsonElement? = null,
    val access: AccessDto? = null,
    val isrc: String? = null,
    val album_backlink: AlbumBacklinkDto? = null,
    val is_downloadable: Boolean = false,
    val download_conditions: JsonElement? = null,
    val play_count: Long? = null,
    val favorite_count: Long? = null,
)

@Serializable
internal data class PlaylistDto(
    val id: String,
    val playlist_name: String = "",
    val is_album: Boolean = false,
    @Serializable(with = ArtworkMapSerializer::class) val artwork: Map<String, String>? = null,
    val user: UserDto? = null,
    val track_count: Int? = null,
    val release_date: String? = null,
)

@Serializable
internal data class GenreDto(val name: String, val count: Long = 0)

@Serializable
internal data class SearchDto(
    val tracks: List<TrackDto> = emptyList(),
    val users: List<UserDto> = emptyList(),
    val playlists: List<PlaylistDto> = emptyList(),
    val albums: List<PlaylistDto> = emptyList(),
)

/** Artwork maps carry "150x150"-style keys with URLs, plus a "mirrors" list we don't need: keep the strings only. */
internal object ArtworkMapSerializer : KSerializer<Map<String, String>?> {
    private val delegate = JsonElement.serializer()
    override val descriptor = delegate.descriptor
    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: Map<String, String>?) =
        delegate.serialize(encoder, JsonObject(value.orEmpty().mapValues { JsonPrimitive(it.value) }))

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): Map<String, String>? {
        val element = delegate.deserialize(decoder) as? JsonObject ?: return null
        return element.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.let { k to it } }.toMap()
    }
}

/**
 * The calls Podium makes, each returning a typed [Outcome]. Network and HTTP failures are
 * classified (offline, rate limited, not found, server) so the UI can explain them.
 */
internal class AudiusApi(
    private val transport: HttpTransport,
    private val appName: String = APP_NAME,
    private val base: String = BASE_URL,
    /** Called before every request (the disk light). */
    private val onRequest: () -> Unit = {},
) {
    /** A debug switch to rehearse losing the network without touching the phone's settings. */
    @Volatile var offline: Boolean = false

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }

    fun streamUrl(trackId: String): String = "$base/tracks/${enc(trackId)}/stream?app_name=${enc(appName)}"

    fun trending(time: String, offset: Int, limit: Int, genre: String? = null) =
        list("/tracks/trending", TrackDto.serializer(), "time" to time, "offset" to "$offset", "limit" to "$limit", "genre" to genre)

    fun underground(offset: Int, limit: Int) = list("/tracks/trending/underground", TrackDto.serializer(), "offset" to "$offset", "limit" to "$limit")

    fun recommended(genre: String?, exclude: Collection<String>, limit: Int) =
        list("/tracks/recommended", TrackDto.serializer(), listOf("genre" to genre, "limit" to "$limit") + exclude.map { "exclusion_list" to it })

    fun trendingPlaylists(offset: Int, limit: Int) = list("/playlists/trending", PlaylistDto.serializer(), "offset" to "$offset", "limit" to "$limit")

    fun genres(limit: Int) = list("/genres/popular", GenreDto.serializer(), "limit" to "$limit")

    fun search(query: String, offset: Int, limit: Int) =
        one("/search/full", SearchDto.serializer(), "query" to query, "kind" to "all", "offset" to "$offset", "limit" to "$limit")

    fun track(id: String) = one("/tracks/${enc(id)}", TrackDto.serializer())

    fun tracks(ids: Collection<String>) = list("/tracks", TrackDto.serializer(), ids.map { "id" to it })

    fun user(id: String) = one("/users/${enc(id)}", UserDto.serializer())

    fun userTracks(id: String, offset: Int, limit: Int) =
        list("/users/${enc(id)}/tracks", TrackDto.serializer(), "sort" to "plays", "offset" to "$offset", "limit" to "$limit")

    fun userAlbums(id: String, limit: Int) = list("/users/${enc(id)}/albums", PlaylistDto.serializer(), "limit" to "$limit")

    fun userPlaylists(id: String, limit: Int) = list("/users/${enc(id)}/playlists", PlaylistDto.serializer(), "limit" to "$limit")

    fun relatedUsers(id: String, limit: Int) = list("/users/${enc(id)}/related", UserDto.serializer(), "limit" to "$limit")

    /** Playlists come back as a one-element list. */
    fun playlist(id: String) = list("/playlists/${enc(id)}", PlaylistDto.serializer())

    fun playlistTracks(id: String) = list("/playlists/${enc(id)}/tracks", TrackDto.serializer())

    /** Raw bytes (artwork). */
    fun bytes(url: String): Outcome<ByteArray> = request(url) { it }

    // --- plumbing ----------------------------------------------------------------------------------

    private fun <T> list(path: String, item: KSerializer<T>, vararg params: Pair<String, String?>): Outcome<List<T>> = list(path, item, params.toList())

    private fun <T> list(path: String, item: KSerializer<T>, params: List<Pair<String, String?>>): Outcome<List<T>> =
        data(path, params) { json.decodeFromJsonElement(ListSerializer(item), it) }

    private fun <T> one(path: String, item: KSerializer<T>, vararg params: Pair<String, String?>): Outcome<T> =
        data(path, params.toList()) { json.decodeFromJsonElement(item, it) }

    private fun <T> data(path: String, params: List<Pair<String, String?>>, decode: (JsonElement) -> T): Outcome<T> {
        val query = (params.filter { it.second != null } + ("app_name" to appName)).joinToString("&") { (k, v) -> "${enc(k)}=${enc(v!!)}" }
        return request("$base$path?$query") { body ->
            val root = json.parseToJsonElement(body.decodeToString()) as? JsonObject ?: throw IOException("Unexpected response")
            decode(root["data"] ?: throw IOException("No data"))
        }
    }

    private fun <T> request(url: String, parse: (ByteArray) -> T): Outcome<T> {
        if (offline) return Outcome.Failure(PodiumError.Offline)
        onRequest()
        val response = try {
            transport.get(url)
        } catch (e: UnknownHostException) {
            return Outcome.Failure(PodiumError.Offline)
        } catch (e: SocketTimeoutException) {
            return Outcome.Failure(PodiumError.Network("Timed out"))
        } catch (e: IOException) {
            return Outcome.Failure(PodiumError.Network(e.message))
        }
        return when (response.code) {
            in 200..299 -> try {
                Outcome.Success(parse(response.body))
            } catch (e: Exception) {
                Outcome.Failure(PodiumError.Unexpected("Couldn't read the response: ${e.message}"))
            }
            404 -> Outcome.Failure(PodiumError.NotFound(url.substringAfter(base).substringBefore('?')))
            429 -> Outcome.Failure(PodiumError.RateLimited(response.headers["retry-after"]?.toLongOrNull()?.times(1000)))
            401, 403 -> Outcome.Failure(PodiumError.AuthRequired("HTTP ${response.code}"))
            else -> Outcome.Failure(PodiumError.Server(response.code))
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    companion object {
        const val BASE_URL = "https://api.audius.co/v1"
        const val APP_NAME = "Podium"
    }
}
