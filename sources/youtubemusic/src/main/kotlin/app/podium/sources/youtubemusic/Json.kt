package app.podium.sources.youtubemusic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * Safe navigation over the catalogue's answers. Every answer is untrusted input whose shape changes
 * without notice: nothing here throws, a missing step is null, and parsers decide what a missing
 * piece means (YOUTUBE_MUSIC_ARCHITECTURE.md §5, "parsers fail soft, never crash").
 */

/** Walk [path]: a String is an object key, an Int an array index. */
internal fun JsonElement?.at(vararg path: Any): JsonElement? {
    var current: JsonElement? = this
    for (step in path) {
        current = when (step) {
            is String -> (current as? JsonObject)?.get(step)
            is Int -> (current as? JsonArray)?.getOrNull(step)
            else -> null
        } ?: return null
    }
    return current
}

internal val JsonElement?.obj: JsonObject? get() = this as? JsonObject

internal val JsonElement?.arr: List<JsonElement> get() = (this as? JsonArray).orEmpty()

internal val JsonElement?.str: String? get() = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

internal val JsonElement?.bool: Boolean? get() = (this as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

/** The single key of a one-key wrapper object ({"fooRenderer": {...}}) and its value. */
internal fun JsonElement?.renderer(): Pair<String, JsonObject>? {
    val o = this as? JsonObject ?: return null
    val (key, value) = o.entries.firstOrNull() ?: return null
    return key to (value as? JsonObject ?: return null)
}

/** The text runs of a formatted string ({"runs": [...]}) — or one run made of {"simpleText": ...}. */
internal fun JsonElement?.runs(): List<JsonObject> {
    val o = this as? JsonObject ?: return emptyList()
    o["runs"]?.let { r -> return r.arr.mapNotNull { it as? JsonObject } }
    val simple = o["simpleText"].str ?: return emptyList()
    return listOf(JsonObject(mapOf("text" to JsonPrimitive(simple))))
}

/** A formatted string as plain text, or null when it has none. */
internal fun JsonElement?.text(): String? = runs().joinToString("") { it["text"].str.orEmpty() }.takeIf { it.isNotBlank() }

internal fun JsonObject.runText(): String = this["text"].str.orEmpty()

/**
 * Every value stored under [key] anywhere below this element, depth first, in document order.
 * Used where the catalogue's nesting varies between pages and versions; [limit] bounds the walk.
 */
internal fun JsonElement?.findAll(key: String, limit: Int = Int.MAX_VALUE): List<JsonElement> {
    val found = ArrayList<JsonElement>()
    fun walk(e: JsonElement?, depth: Int) {
        if (found.size >= limit || depth > MAX_DEPTH) return
        when (e) {
            is JsonObject -> for ((k, v) in e) {
                if (k == key) found += v
                if (found.size >= limit) return
                walk(v, depth + 1)
            }
            is JsonArray -> for (v in e) walk(v, depth + 1)
            else -> Unit
        }
    }
    walk(this, 0)
    return found
}

internal fun JsonElement?.findFirst(key: String): JsonElement? = findAll(key, 1).firstOrNull()

private const val MAX_DEPTH = 64
