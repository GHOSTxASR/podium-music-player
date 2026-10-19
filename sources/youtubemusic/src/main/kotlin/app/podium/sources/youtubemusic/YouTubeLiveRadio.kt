package app.podium.sources.youtubemusic

import app.podium.core.common.Outcome
import app.podium.core.common.PodiumError
import app.podium.core.model.MediaKind
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.UnknownHostException

/*
 * Live radio (D-90): YouTube's music streams that are live now — 24/7 lofi, jazz, chill and the
 * like — found with YouTube's own search, filtered to live, one search per genre. A station plays
 * through the same resolver as a song, as the stream's audio-only HLS rendition ([HlsAudio]).
 */

/** The genres offered, and what each searches for. */
internal object LiveGenres {
    val ALL: List<Pair<String, String>> = listOf(
        "Lofi" to "lofi radio",
        "Chill" to "chill music radio live",
        "Jazz" to "jazz radio live",
        "Hip-hop" to "hip hop radio live",
        "Pop" to "pop music radio live",
        "Rock" to "rock music radio live",
        "Electronic" to "electronic music radio live",
        "Synthwave" to "synthwave radio",
        "Classical" to "classical music radio live",
        "Ambient" to "ambient music radio live",
        "Bollywood" to "bollywood songs radio live",
        "K-pop" to "kpop radio live",
        "Sleep" to "sleep music radio live",
    )

    fun queryOf(genre: String): String? = ALL.firstOrNull { it.first == genre }?.second
}

/**
 * YouTube's search (the video site's web client, which can filter to live), signed out: stations
 * are public, and nothing of the listener's account goes with these. The client version is the
 * page's own when a stale one is refused.
 */
internal class YouTubeLiveSearch(
    private val transport: HttpTransport,
    private val userAgent: () -> String,
    private val region: () -> String = { "US" },
    private val language: String = "en",
) {
    @Volatile private var version: String = FALLBACK_VERSION
    @Volatile private var learned = false

    suspend fun search(query: String): Outcome<JsonObject> {
        val first = attempt(query)
        if (first is Outcome.Failure && first.error == STALE && !learned && learnVersion()) return attempt(query)
        return if (first is Outcome.Failure && first.error == STALE) Outcome.Failure(PodiumError.Server(400)) else first
    }

    private suspend fun attempt(query: String): Outcome<JsonObject> {
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "WEB")
                    put("clientVersion", version)
                    put("hl", language)
                    put("gl", region())
                }
            }
            put("query", query)
            put("params", LIVE_FILTER)
        }
        val headers = mapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json",
            "Accept-Language" to language,
            "User-Agent" to userAgent(),
            "Origin" to ORIGIN,
            "X-Youtube-Client-Name" to "1",
            "X-Youtube-Client-Version" to version,
        )
        val response = try {
            transport.send("POST", "$ORIGIN/youtubei/v1/search?prettyPrint=false", headers, body.toString().toByteArray(Charsets.UTF_8))
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return Outcome.Failure(if (e is UnknownHostException || e.message == "UnknownHostException") PodiumError.Offline else PodiumError.Network())
        }
        return when (response.code) {
            in 200..299 -> runCatching { Json.parseToJsonElement(response.body.toString(Charsets.UTF_8)) as JsonObject }
                .fold({ Outcome.Success(it) }, { Outcome.Failure(PodiumError.Server(null)) })
            400 -> Outcome.Failure(STALE)
            429 -> Outcome.Failure(PodiumError.RateLimited(response.retryAfterSeconds?.times(1000)))
            else -> Outcome.Failure(PodiumError.Server(response.code))
        }
    }

    /** The video site's current client version, from its page. */
    private suspend fun learnVersion(): Boolean {
        learned = true
        val page = try {
            transport.send("GET", "$ORIGIN/", mapOf("User-Agent" to userAgent(), "Accept-Language" to language), null)
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            return false
        }
        if (page.code !in 200..299) return false
        val (v, _) = PageConfigParser.parse(page.body.toString(Charsets.UTF_8)) ?: return false
        version = v
        return true
    }

    companion object {
        const val ORIGIN = "https://www.youtube.com"

        /** Search's "Live" filter. */
        const val LIVE_FILTER = "EgJAAQ%3D%3D"

        /** A web client version the site accepted; replaced by the page's own when refused. */
        const val FALLBACK_VERSION = "2.20251001.00.00"

        private val STALE = PodiumError.Server(-400)
    }
}

/** Live streams in a search answer, as stations. */
internal object LiveParser {

    /**
     * Every video marked live now, once each, the most listened to first (the site's order among
     * equals). Its channel is its "artist"; it has no length.
     */
    fun stations(json: JsonObject): List<YtmSong> {
        val found = LinkedHashMap<String, Pair<YtmSong, Long>>()
        for (v in json.findAll("videoRenderer")) {
            val id = v.at("videoId").str ?: continue
            if (!isLiveNow(v)) continue
            val title = v.at("title").text()?.trim() ?: continue
            val channel = (v.at("ownerText").text() ?: v.at("longBylineText").text() ?: v.at("shortBylineText").text())?.trim()
            val thumbs = v.at("thumbnail", "thumbnails").arr.mapNotNull { t ->
                val url = t.at("url").str ?: return@mapNotNull null
                Thumb(if (url.startsWith("//")) "https:$url" else url, (t.at("width") as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0)
            }
            val song = YtmSong(
                videoId = id,
                title = title,
                artists = listOfNotNull(channel?.let { YtmRef(it) }),
                thumbnails = thumbs,
                kind = MediaKind.LIVE,
            )
            found.putIfAbsent(id, song to (listeners(v) ?: -1L))
        }
        return found.values.withIndex()
            .sortedWith(compareByDescending<IndexedValue<Pair<YtmSong, Long>>> { it.value.second }.thenBy { it.index })
            .map { it.value.first }
    }

    private fun isLiveNow(v: kotlinx.serialization.json.JsonElement): Boolean =
        v.at("badges").arr.any { it.at("metadataBadgeRenderer", "style").str == "BADGE_STYLE_TYPE_LIVE_NOW" } ||
            v.at("thumbnailOverlays").arr.any { it.at("thumbnailOverlayTimeStatusRenderer", "style").str == "LIVE" }

    /** "1,234 watching", "12K watching" → how many listen now. */
    internal fun listeners(v: kotlinx.serialization.json.JsonElement): Long? {
        val text = v.at("viewCountText").text() ?: return null
        val m = Regex("""([0-9][0-9.,]*)\s*([KkMm]?)""").find(text) ?: return null
        val number = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        return (number * when (m.groupValues[2].lowercase()) { "k" -> 1_000; "m" -> 1_000_000; else -> 1 }).toLong()
    }
}

/**
 * A live stream's HLS master playlist → the stream to play: its audio-only rendition — the AAC-LC
 * one (128 kbps) when there is one, as songs get — so no video is ever fetched. Only when a
 * stream offers no audio rendition, its smallest variant.
 */
internal object HlsAudio {
    data class Pick(val uri: String, val audioOnly: Boolean, val itag: Int?)

    fun pick(master: String): Pick? {
        val lines = master.lines().map { it.trim() }
        val renditions = lines.filter { it.startsWith("#EXT-X-MEDIA:") && attribute(it, "TYPE") == "AUDIO" }
            .mapNotNull { line -> attribute(line, "URI")?.let { it to attribute(line, "GROUP-ID") } }
        val variants = lines.withIndex().filter { it.value.startsWith("#EXT-X-STREAM-INF:") }.mapNotNull { (i, inf) ->
            val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return@mapNotNull null
            Variant(uri, attribute(inf, "AUDIO"), attribute(inf, "CODECS").orEmpty(), attribute(inf, "BANDWIDTH")?.toLongOrNull() ?: Long.MAX_VALUE)
        }
        if (renditions.isNotEmpty()) {
            val lcGroups = variants.filter { "mp4a.40.2" in it.codecs }.mapNotNull { it.audioGroup }.toSet()
            val (uri, _) = renditions.firstOrNull { it.second in lcGroups }
                ?: renditions.firstOrNull { itagOf(it.first) == 140 }
                ?: renditions.first()
            return Pick(uri, audioOnly = true, itag = itagOf(uri))
        }
        val smallest = variants.minByOrNull { it.bandwidth } ?: return null
        return Pick(smallest.uri, audioOnly = false, itag = null)
    }

    private data class Variant(val uri: String, val audioGroup: String?, val codecs: String, val bandwidth: Long)

    /** An attribute of an HLS tag line: quoted or bare. */
    internal fun attribute(line: String, key: String): String? {
        val m = Regex("""(?:^|[:,])$key=(?:"([^"]*)"|([^,]*))""").find(line) ?: return null
        return m.groupValues[1].ifEmpty { m.groupValues[2] }.ifEmpty { null }
    }

    /** The audio format underneath ("…gir%3Dyes%3Bitag%3D140…"): 140 is AAC-LC 128 kbps, 139 HE-AAC 48. */
    internal fun itagOf(uri: String): Int? = Regex("""itag%3D(\d+)""").find(uri)?.groupValues?.get(1)?.toIntOrNull()

    /** When the stream's address stops working ("/expire/<seconds>/"), in epoch millis. */
    fun expiresAtMillis(uri: String): Long? = Regex("""/expire/(\d+)/""").find(uri)?.groupValues?.get(1)?.toLongOrNull()?.times(1000)
}
