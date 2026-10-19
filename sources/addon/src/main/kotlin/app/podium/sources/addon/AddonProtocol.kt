package app.podium.sources.addon

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** What an addon says about itself: its `manifest.json` (D-91). */
data class AddonManifest(
    val id: String,
    val name: String,
    val version: String? = null,
    val description: String? = null,
    val resources: Set<String> = setOf(RESOURCE_SEARCH, RESOURCE_STREAM),
    val settings: List<AddonSetting> = emptyList(),
    /** Whether the addon lets its streams be saved for offline listening. Podium only streams from addons. */
    val allowsDownloads: Boolean = false,
) {
    val canSearch: Boolean get() = RESOURCE_SEARCH in resources
    val canStream: Boolean get() = RESOURCE_STREAM in resources

    /** Every setting at the value the listener chose, or its default when that isn't one it takes. */
    fun settle(values: Map<String, String>): Map<String, String> =
        settings.mapNotNull { s -> s.accept(values[s.key])?.let { s.key to it } }.toMap()

    companion object {
        const val RESOURCE_SEARCH = "search"
        const val RESOURCE_STREAM = "stream"
    }
}

/** One setting an addon declares; the listener's value goes with every request as a query parameter. */
data class AddonSetting(
    val key: String,
    val label: String,
    val type: Type,
    val default: String? = null,
    val options: List<Option> = emptyList(),
) {
    enum class Type { SELECT, TOGGLE, TEXT }

    data class Option(val label: String, val value: String)

    /** [value] when this setting takes it, else the default (for a choice, the first option). */
    fun accept(value: String?): String? = when (type) {
        Type.SELECT -> value?.takeIf { v -> options.any { it.value == v } }
            ?: default?.takeIf { d -> options.any { it.value == d } }
            ?: options.firstOrNull()?.value
        Type.TOGGLE -> value?.takeIf { it == "true" || it == "false" } ?: default?.takeIf { it == "true" || it == "false" } ?: "false"
        Type.TEXT -> value?.takeIf { it.length <= MAX_TEXT } ?: default
    }

    /** What the listener sees for [value]: an option's label, On/Off, or the text itself. */
    fun describe(value: String?): String = when (type) {
        Type.SELECT -> options.firstOrNull { it.value == value }?.label ?: value.orEmpty()
        Type.TOGGLE -> if (value == "true") "On" else "Off"
        Type.TEXT -> value.orEmpty()
    }

    companion object {
        const val MAX_TEXT = 256
    }
}

/** A song in an addon's search answer. */
internal data class AddonTrack(
    val id: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
    val isrc: String?,
    val explicit: Boolean?,
)

/** An addon's answer for one song's stream. */
internal data class AddonStream(
    val url: String,
    val format: String?,
    val quality: String?,
    val mimeType: String?,
    val encrypted: Boolean,
    val headers: Map<String, String>,
    val expiresAtMillis: Long?,
)

/**
 * The addon protocol's answers, read leniently (D-91). Addons are written by anyone: every answer
 * is untrusted input whose shape varies. Nothing here throws; what can't be read is null, and only
 * what the protocol needs is kept.
 */
internal object AddonProtocol {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun parse(bytes: ByteArray): JsonElement? = runCatching { json.parseToJsonElement(bytes.decodeToString()) }.getOrNull()

    fun manifest(bytes: ByteArray): AddonManifest? {
        val o = parse(bytes) as? JsonObject ?: return null
        val id = o["id"].text()?.take(MAX_NAME)
        val name = o["name"].text()?.take(MAX_NAME)
        if (id == null && name == null) return null
        val resources = (o["resources"] as? JsonArray)?.mapNotNull { r ->
            (r.text() ?: (r as? JsonObject)?.let { it["name"].text() ?: it["type"].text() })?.lowercase()
        }?.toSet()
        val settings = (o["settings"] as? JsonArray).orEmpty().mapNotNull(::setting).distinctBy { it.key }.take(MAX_SETTINGS)
        return AddonManifest(
            id = id ?: name!!,
            name = name ?: id!!,
            version = o["version"].text()?.take(MAX_NAME),
            description = o["description"].text()?.take(MAX_DESCRIPTION),
            resources = resources ?: setOf(AddonManifest.RESOURCE_SEARCH, AddonManifest.RESOURCE_STREAM),
            settings = settings,
            allowsDownloads = o["allowDownloads"].flag() == true,
        )
    }

    private fun setting(e: JsonElement): AddonSetting? {
        val o = e as? JsonObject ?: return null
        val key = o["key"].text() ?: o["id"].text() ?: o["name"].text() ?: return null
        if (!SETTING_KEY.matches(key)) return null
        val type = when (o["type"].text()?.lowercase()) {
            "select", "dropdown", "enum", "choice", "radio" -> AddonSetting.Type.SELECT
            "boolean", "bool", "toggle", "switch", "checkbox" -> AddonSetting.Type.TOGGLE
            "text", "string", "input", "password" -> AddonSetting.Type.TEXT
            else -> return null
        }
        val options = (o["options"] as? JsonArray).orEmpty().mapNotNull { opt ->
            when (opt) {
                is JsonPrimitive -> opt.contentOrNull?.let { AddonSetting.Option(it, it) }
                is JsonObject -> {
                    val value = opt["value"].text() ?: return@mapNotNull null
                    AddonSetting.Option(opt["label"].text() ?: opt["name"].text() ?: value, value)
                }
                else -> null
            }
        }.distinctBy { it.value }
        if (type == AddonSetting.Type.SELECT && options.isEmpty()) return null
        val label = o["label"].text() ?: o["title"].text() ?: o["name"].text()?.takeIf { it != key } ?: humanize(key)
        return AddonSetting(key, label.take(MAX_NAME), type, o["default"].text(), options)
    }

    /** "audio_quality" → "Audio quality". */
    private fun humanize(key: String): String =
        key.replace('_', ' ').replace('-', ' ').replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase().replaceFirstChar { it.uppercase() }

    fun search(bytes: ByteArray): List<AddonTrack>? {
        val root = parse(bytes) ?: return null
        val items = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["tracks"] ?: root["results"] ?: root["items"]) as? JsonArray ?: return null
            else -> return null
        }
        return items.mapNotNull(::track)
    }

    private fun track(e: JsonElement): AddonTrack? {
        val o = e as? JsonObject ?: return null
        val id = o["id"].text()?.takeIf { it.length <= MAX_ID } ?: return null
        val title = (o["title"] ?: o["name"]).text() ?: return null
        val artist = o["artist"].text()
            ?: (o["artist"] as? JsonObject)?.get("name").text()
            ?: (o["artists"] as? JsonArray)?.mapNotNull { it.text() ?: (it as? JsonObject)?.get("name").text() }
                ?.takeIf { it.isNotEmpty() }?.joinToString(", ")
        val album = o["album"].text() ?: (o["album"] as? JsonObject)?.let { it["title"].text() ?: it["name"].text() }
        val durationMs = o["durationMs"].number()?.toLong()
            ?: o["duration"].number()?.let { if (it > MAX_SECONDS) it.toLong() else (it * 1000).toLong() }
            ?: o["duration"].text()?.let(::clock)
        return AddonTrack(
            id = id,
            title = title.take(MAX_NAME),
            artist = artist?.take(MAX_NAME),
            album = album?.take(MAX_NAME),
            durationMs = durationMs?.takeIf { it > 0 },
            isrc = o["isrc"].text(),
            explicit = o["explicit"].flag(),
        )
    }

    /** "3:21" or "1:02:03" → milliseconds. */
    private fun clock(text: String): Long? {
        val parts = text.split(':').map { it.trim().toLongOrNull() ?: return null }
        if (parts.isEmpty() || parts.size > 3) return null
        return parts.fold(0L) { acc, n -> acc * 60 + n } * 1000
    }

    fun stream(bytes: ByteArray): AddonStream? {
        val o = parse(bytes) as? JsonObject ?: return null
        val url = (o["url"] ?: o["streamUrl"] ?: o["stream"]).text() ?: return null
        val headers = (o["headers"] as? JsonObject)?.entries
            ?.mapNotNull { (k, v) -> v.text()?.let { k to it } }
            ?.filter { (k, v) -> HEADER_NAME.matches(k) && '\n' !in v && '\r' !in v }
            ?.take(MAX_HEADERS)?.toMap().orEmpty()
        val expires = (o["expiresAt"] ?: o["expires"])?.number()?.toLong()?.let { if (it < 100_000_000_000L) it * 1000 else it }
        return AddonStream(
            url = url,
            format = (o["format"] ?: o["manifest"] ?: o["type"]).text()?.lowercase(),
            quality = o["quality"].text()?.take(MAX_NAME),
            mimeType = (o["mimeType"] ?: o["mime"]).text(),
            encrypted = o["encrypted"].flag() == true || o["drm"].flag() == true,
            headers = headers,
            expiresAtMillis = expires,
        )
    }

    private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonElement?.number(): Double? = (this as? JsonPrimitive)?.let { p ->
        if (p.isString) p.content.trim().toDoubleOrNull() else p.longOrNull?.toDouble() ?: p.doubleOrNull
    }?.takeIf { it.isFinite() && it >= 0 }

    private fun JsonElement?.flag(): Boolean? = (this as? JsonPrimitive)?.let { p ->
        p.booleanOrNull ?: p.longOrNull?.let { it != 0L } ?: p.contentOrNull?.lowercase()?.toBooleanStrictOrNull()
    }

    private val SETTING_KEY = Regex("[A-Za-z0-9_.-]{1,64}")
    private val HEADER_NAME = Regex("[A-Za-z0-9-]{1,64}")
    private const val MAX_NAME = 300
    private const val MAX_DESCRIPTION = 1_000
    private const val MAX_ID = 1_000
    private const val MAX_SETTINGS = 20
    private const val MAX_HEADERS = 10

    /** A "duration" larger than ten hours of seconds is taken to be milliseconds. */
    private const val MAX_SECONDS = 36_000.0
}
