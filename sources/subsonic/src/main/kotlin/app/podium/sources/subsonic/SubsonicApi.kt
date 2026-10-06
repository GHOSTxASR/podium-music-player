package app.podium.sources.subsonic

import app.podium.core.common.Outcome
import app.podium.core.common.PodiumError
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/** A user name and password for one server. Never logged; held only as long as a request needs it. */
internal data class ServerCredentials(val username: String, val password: String) {
    override fun toString() = "ServerCredentials(username=$username, password=***)"
}

/** What a server says about itself on `ping`. */
internal data class ServerIdentity(val type: String?, val serverVersion: String?, val openSubsonic: Boolean)

/**
 * The OpenSubsonic / Subsonic REST API (https://opensubsonic.netlify.app/docs/) for one server.
 *
 * Authentication is the protocol's token scheme: every request carries the user name, a fresh
 * random salt and md5(password + salt) — never the password itself. The JSON envelope's
 * `status`/`error` is classified into Podium's errors: "not found" (70) is a miss, wrong or
 * unsupported credentials (40, 41, 42, 44) are auth problems, anything else a server failure.
 */
internal class SubsonicApi(
    private val base: String,
    private val transport: HttpTransport,
    private val credentials: () -> ServerCredentials?,
    private val random: SecureRandom = SecureRandom(),
) {
    /** A request URL with fresh authentication, for calls and for the player (stream URLs). */
    fun url(method: String, params: List<Pair<String, String?>> = emptyList(), with: ServerCredentials? = credentials()): String? {
        val auth = with ?: return null
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes).toHex()
        val token = md5(auth.password + salt)
        val all = listOf("u" to auth.username, "t" to token, "s" to salt, "v" to API_VERSION, "c" to CLIENT, "f" to "json") +
            params.mapNotNull { (k, v) -> v?.let { k to it } }
        return "$base/rest/$method?" + all.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
    }

    suspend fun ping(with: ServerCredentials? = null): Outcome<ServerIdentity> =
        call("ping", with = with) { root ->
            ServerIdentity(
                type = root["type"]?.jsonPrimitive?.contentOrNull,
                serverVersion = root["serverVersion"]?.jsonPrimitive?.contentOrNull,
                openSubsonic = root["openSubsonic"]?.jsonPrimitive?.contentOrNull == "true",
            )
        }

    suspend fun search(query: String, songCount: Int, songOffset: Int, albumCount: Int, artistCount: Int) =
        call(
            "search3",
            "query" to query, "songCount" to "$songCount", "songOffset" to "$songOffset",
            "albumCount" to "$albumCount", "albumOffset" to "0", "artistCount" to "$artistCount", "artistOffset" to "0",
        ) { it.child("searchResult3", SearchResultDto.serializer()) ?: SearchResultDto() }

    suspend fun song(id: String) = call("getSong", "id" to id) { it.child("song", SongDto.serializer()) ?: throw MissingAnswer() }

    suspend fun album(id: String) = call("getAlbum", "id" to id) { it.child("album", AlbumDto.serializer()) ?: throw MissingAnswer() }

    suspend fun artist(id: String) = call("getArtist", "id" to id) { it.child("artist", ArtistDto.serializer()) ?: throw MissingAnswer() }

    suspend fun topSongs(artistName: String, count: Int) =
        call("getTopSongs", "artist" to artistName, "count" to "$count") { it.child("topSongs", SongListDto.serializer())?.song.orEmpty() }

    suspend fun playlists() = call("getPlaylists") { it.child("playlists", PlaylistsDto.serializer())?.playlist.orEmpty() }

    suspend fun playlist(id: String) = call("getPlaylist", "id" to id) { it.child("playlist", PlaylistDto.serializer()) ?: throw MissingAnswer() }

    suspend fun albumList(type: String, size: Int, offset: Int) =
        call("getAlbumList2", "type" to type, "size" to "$size", "offset" to "$offset") { it.child("albumList2", AlbumListDto.serializer())?.album.orEmpty() }

    suspend fun randomSongs(size: Int) = call("getRandomSongs", "size" to "$size") { it.child("randomSongs", SongListDto.serializer())?.song.orEmpty() }

    suspend fun similarSongs(id: String, count: Int) =
        call("getSimilarSongs", "id" to id, "count" to "$count") { it.child("similarSongs", SongListDto.serializer())?.song.orEmpty() }

    suspend fun similarSongs2(artistId: String, count: Int) =
        call("getSimilarSongs2", "id" to artistId, "count" to "$count") { it.child("similarSongs2", SongListDto.serializer())?.song.orEmpty() }

    suspend fun artistInfo(artistId: String, count: Int) =
        call("getArtistInfo2", "id" to artistId, "count" to "$count") { it.child("artistInfo2", ArtistInfoDto.serializer())?.similarArtist.orEmpty() }

    suspend fun genres() = call("getGenres") { it.child("genres", GenresDto.serializer())?.genre.orEmpty() }

    suspend fun songsByGenre(genre: String, count: Int, offset: Int) =
        call("getSongsByGenre", "genre" to genre, "count" to "$count", "offset" to "$offset") { it.child("songsByGenre", SongListDto.serializer())?.song.orEmpty() }

    /** Cover art bytes (the server picks the format). */
    suspend fun coverArt(id: String, size: Int): Outcome<HttpResponse> {
        val url = url("getCoverArt", listOf("id" to id, "size" to "$size")) ?: return Outcome.Failure(PodiumError.AuthRequired())
        val response = fetch(url).let { (it as? Outcome.Success)?.value ?: return it as Outcome.Failure }
        if (response.code !in 200..299) return Outcome.Failure(httpError(response.code))
        // A server answers an unknown id with a JSON error, not an image.
        if (response.contentType?.contains("json") == true) return Outcome.Failure(PodiumError.NotFound("cover art"))
        return Outcome.Success(response)
    }

    private suspend fun <T> call(
        method: String,
        vararg params: Pair<String, String?>,
        with: ServerCredentials? = null,
        extract: (JsonObject) -> T,
    ): Outcome<T> {
        val url = url(method, params.toList(), with ?: credentials()) ?: return Outcome.Failure(PodiumError.AuthRequired("signed out"))
        val response = fetch(url).let { (it as? Outcome.Success)?.value ?: return it as Outcome.Failure }
        if (response.code !in 200..299) return Outcome.Failure(httpError(response.code))
        val root = try {
            json.parseToJsonElement(response.body.decodeToString()).jsonObject["subsonic-response"]?.jsonObject
        } catch (e: Exception) {
            null
        } ?: return Outcome.Failure(PodiumError.Unexpected("unreadable response"))
        if (root["status"]?.jsonPrimitive?.contentOrNull != "ok") {
            val error = root["error"]?.jsonObject
            return Outcome.Failure(protocolError(error?.get("code")?.jsonPrimitive?.intOrNull))
        }
        return try {
            Outcome.Success(extract(root))
        } catch (e: MissingAnswer) {
            Outcome.Failure(PodiumError.NotFound(method))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The shape wasn't what the protocol promises: a malformed answer is a failure, not a miss.
            Outcome.Failure(PodiumError.Unexpected("malformed $method"))
        }
    }

    private suspend fun fetch(url: String): Outcome<HttpResponse> = try {
        Outcome.Success(transport.get(url))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Never the message: it can contain the URL, and the URL carries the token.
        Outcome.Failure(PodiumError.Network(e.javaClass.simpleName))
    }

    private fun <T> JsonObject.child(name: String, serializer: KSerializer<T>): T? =
        get(name)?.let { json.decodeFromJsonElement(serializer, it) }

    /** The envelope said ok but lacked the requested element: nothing there. */
    private class MissingAnswer : Exception()

    companion object {
        const val API_VERSION = "1.16.1"
        const val CLIENT = "Podium"
        private const val SALT_BYTES = 8

        internal val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

        /** The protocol's error codes, in Podium's terms. */
        fun protocolError(code: Int?): PodiumError = when (code) {
            70 -> PodiumError.NotFound("data")
            40, 41, 44 -> PodiumError.AuthRequired("credentials refused")
            42, 43 -> PodiumError.AuthRequired("authentication not supported")
            50 -> PodiumError.AuthRequired("not authorised")
            20, 30 -> PodiumError.PolicyDisabled("protocol version")
            else -> PodiumError.Server(null)
        }

        fun httpError(code: Int): PodiumError = when (code) {
            401, 403 -> PodiumError.AuthRequired("HTTP $code")
            404 -> PodiumError.NotFound("HTTP 404")
            429 -> PodiumError.RateLimited()
            else -> PodiumError.Server(code)
        }

        fun md5(s: String): String = MessageDigest.getInstance("MD5").digest(s.toByteArray()).toHex()

        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

        private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}

/** ISRC can be a string or (OpenSubsonic) a list of strings. */
internal fun JsonElement?.firstText(): String? = when (this) {
    null -> null
    is kotlinx.serialization.json.JsonPrimitive -> contentOrNull
    is kotlinx.serialization.json.JsonArray -> firstOrNull()?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
    else -> null
}
