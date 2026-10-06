package app.podium.sources.subsonic

import kotlinx.coroutines.delay
import java.net.URI
import java.net.URLDecoder

/**
 * A scripted (Open)Subsonic server for tests: it checks the token authentication exactly as a real
 * server does (md5(password + salt)), answers the methods Podium uses from a small library, and
 * can be told to fail, hang or garble its answers. No network involved.
 */
internal class FakeSubsonicServer(
    val user: String = "listener",
    var password: String = "test-password-1",
    val type: String = "navidrome",
) : HttpTransport {

    /** Every request's query, decoded (for "no password on the wire" checks). */
    val requests = mutableListOf<Map<String, String>>()
    val methods get() = requests.map { it["method"].orEmpty() }

    var httpStatus: Int? = null
    var garbled = false
    var hangMillis = 0L
    var unreachable = false
    val missingSongs = mutableSetOf<String>()

    val songs = mutableListOf(
        song("s1", "Song Shared", "Band", "al1", "Record", isrc = """["USABC2600001"]""", mbid = "mb-1", suffix = "flac", bitDepth = 24, samplingRate = 96000),
        song("s2", "Song Alpha", "Band", "al1", "Record"),
        song("s3", "Song Live (Live)", "Band", "al2", "Live Record", explicit = "explicit"),
    )

    override suspend fun get(url: String): HttpResponse {
        if (unreachable) throw java.net.ConnectException("refused")
        if (hangMillis > 0) delay(hangMillis)
        val uri = URI(url)
        val method = uri.path.substringAfterLast('/')
        val q = uri.rawQuery.orEmpty().split('&').filter { '=' in it }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        requests += q + ("method" to method)
        httpStatus?.let { return HttpResponse(it, ByteArray(0)) }
        if (!uri.path.startsWith("/rest/")) return HttpResponse(404, ByteArray(0))
        if (garbled) return json("this is not json")
        val authed = q["u"] == user && q["t"] == SubsonicApi.md5(password + q["s"].orEmpty())
        if (!authed) return error(40, "Wrong username or password")
        return when (method) {
            "ping" -> ok("")
            "search3" -> {
                val words = q["query"].orEmpty().lowercase().split(' ').filter { it.isNotBlank() }
                val hits = songs.filter { s -> words.all { w -> s.contains("\"title\":\"") && s.lowercase().contains(w) } }
                val n = q["songCount"]?.toInt() ?: 20
                val off = q["songOffset"]?.toInt() ?: 0
                val albums = if ((q["albumCount"]?.toInt() ?: 0) > 0) """[{"id":"al1","name":"Record","artist":"Band","artistId":"ar1","coverArt":"al-al1","songCount":2,"year":2024}]""" else "[]"
                val artists = if ((q["artistCount"]?.toInt() ?: 0) > 0) """[{"id":"ar1","name":"Band","coverArt":"ar-ar1","albumCount":2}]""" else "[]"
                ok(""""searchResult3":{"song":[${hits.drop(off).take(n).joinToString(",")}],"album":$albums,"artist":$artists}""")
            }
            "getSong" -> {
                val id = q["id"].orEmpty()
                val s = songs.firstOrNull { it.contains("\"id\":\"$id\"") }
                if (s == null || id in missingSongs) error(70, "Song not found") else ok(""""song":$s""")
            }
            "getAlbum" -> when (q["id"]) {
                "al1" -> ok(""""album":{"id":"al1","name":"Record","artist":"Band","artistId":"ar1","coverArt":"al-al1","songCount":2,"year":2024,"song":[${songs[0]},${songs[1]}]}""")
                else -> error(70, "Album not found")
            }
            "getArtist" -> if (q["id"] == "ar1") ok(""""artist":{"id":"ar1","name":"Band","albumCount":1,"album":[{"id":"al1","name":"Record","artist":"Band","songCount":2}]}""") else error(70, "Artist not found")
            "getTopSongs" -> ok(""""topSongs":{}""") // no metadata agent: no top songs
            "getPlaylists" -> ok(""""playlists":{"playlist":[{"id":"p1","name":"Road trip","owner":"listener","songCount":1}]}""")
            "getPlaylist" -> if (q["id"] == "p1") ok(""""playlist":{"id":"p1","name":"Road trip","owner":"listener","songCount":1,"entry":[${songs[1]}]}""") else error(70, "Playlist not found")
            "getAlbumList2" -> ok(""""albumList2":{"album":[{"id":"al1","name":"Record","artist":"Band","songCount":2}]}""")
            "getRandomSongs" -> ok(""""randomSongs":{"song":[${songs[1]}]}""")
            "getSimilarSongs" -> ok(""""similarSongs":{"song":[${songs[1]},${songs[0]}]}""")
            "getSimilarSongs2" -> ok(""""similarSongs2":{"song":[${songs[1]}]}""")
            "getArtistInfo2" -> ok(""""artistInfo2":{"similarArtist":[{"id":"ar2","name":"Friends"}]}""")
            "getGenres" -> ok(""""genres":{"genre":[{"value":"Rock","songCount":3},{"value":"Jazz","songCount":9}]}""")
            "getSongsByGenre" -> ok(""""songsByGenre":{"song":[${songs[1]}]}""")
            "getCoverArt" -> if (q["id"] == "al-al1") HttpResponse(200, byteArrayOf(1, 2, 3), "image/png") else error(70, "Not found")
            else -> error(0, "Unknown method")
        }
    }

    private fun ok(body: String) = json(
        """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"$type","serverVersion":"0.53.0","openSubsonic":true${if (body.isEmpty()) "" else ",$body"}}}""",
    )

    private fun error(code: Int, message: String) =
        json("""{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":$code,"message":"$message"}}}""")

    private fun json(text: String) = HttpResponse(200, text.toByteArray(), "application/json")

    companion object {
        fun song(
            id: String,
            title: String,
            artist: String,
            albumId: String,
            album: String,
            seconds: Int = 200,
            isrc: String? = null,
            mbid: String? = null,
            explicit: String? = null,
            suffix: String = "mp3",
            bitDepth: Int? = null,
            samplingRate: Int? = null,
        ) = buildString {
            append("""{"id":"$id","title":"$title","artist":"$artist","artistId":"ar1","album":"$album","albumId":"$albumId",""")
            append(""""artists":[{"id":"ar1","name":"$artist"}],"coverArt":"al-$albumId","duration":$seconds,"track":1,"discNumber":1,"year":2024,""")
            append(""""suffix":"$suffix","contentType":"${if (suffix == "flac") "audio/flac" else "audio/mpeg"}","bitRate":${if (suffix == "flac") 2300 else 320}""")
            isrc?.let { append(""","isrc":$it""") }
            mbid?.let { append(""","musicBrainzId":"$it"""") }
            explicit?.let { append(""","explicitStatus":"$it"""") }
            bitDepth?.let { append(""","bitDepth":$it""") }
            samplingRate?.let { append(""","samplingRate":$it""") }
            append("}")
        }
    }
}
