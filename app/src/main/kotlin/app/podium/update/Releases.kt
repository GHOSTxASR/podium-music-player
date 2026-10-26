package app.podium.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** A version as Podium names its releases: "0.1.4", tagged `v0.1.4` (D-99). */
internal data class Version(val parts: List<Int>, val text: String) : Comparable<Version> {
    override fun compareTo(other: Version): Int {
        for (i in 0 until maxOf(parts.size, other.parts.size)) {
            val c = (parts.getOrNull(i) ?: 0).compareTo(other.parts.getOrNull(i) ?: 0)
            if (c != 0) return c
        }
        return 0
    }

    override fun toString() = text

    companion object {
        /** "v0.1.4", "0.1.4" or "0.1.4-test" (what follows `-` or `+` doesn't count); null when unreadable. */
        fun of(raw: String): Version? {
            val core = raw.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
            val parts = core.split('.').map { it.toIntOrNull() ?: return null }
            if (parts.isEmpty() || parts.size > 4) return null
            return Version(parts, core)
        }
    }
}

/** The app in a release: where it downloads from, its size, and its SHA-256 when GitHub gives one. */
internal data class ReleaseApk(val url: String, val size: Long, val sha256: String?)

/** The newest published release, as GitHub's API describes it. */
internal data class Release(
    val version: Version,
    val tag: String,
    val notes: String,
    val pageUrl: String,
    val apk: ReleaseApk?,
)

internal object Releases {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The API's `releases/latest` answer; null for a draft, a pre-release, or what can't be read. */
    fun parse(text: String): Release? {
        val o = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        if (o.bool("draft") == true || o.bool("prerelease") == true) return null
        val tag = o.str("tag_name") ?: return null
        val version = Version.of(tag) ?: return null
        val page = o.str("html_url")?.takeIf { it.startsWith("https://github.com/") } ?: return null
        val assets = (o["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        // The release's app is `podium.apk` (deployment.md §7); any one APK otherwise.
        val asset = assets.firstOrNull { it.str("name") == APK_NAME } ?: assets.firstOrNull { it.str("name")?.endsWith(".apk") == true }
        val apk = asset?.let { a ->
            val url = a.str("browser_download_url")?.takeIf { it.startsWith("https://github.com/") } ?: return@let null
            ReleaseApk(
                url = url,
                size = (a["size"] as? JsonPrimitive)?.longOrNull ?: 0L,
                sha256 = a.str("digest")?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase()
                    ?.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } },
            )
        }
        return Release(version, tag, o.str("body").orEmpty(), page, apk)
    }

    const val APK_NAME = "podium.apk"

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
}

/**
 * A release's notes in short (D-99), for the update notice: the one-line summary the notes open with
 * (`**Podium 0.1.4** adds …`), then the headline of each change, What's new first, at most [max].
 * Markdown is taken out (bold, code, links); nothing is shown that the notes don't say.
 */
internal object ReleaseNotes {
    fun short(notes: String, max: Int = 4): List<String> {
        val paragraphs = notes.replace("\r", "").split(Regex("\n\\s*\n")).map { p -> p.lines().joinToString(" ") { it.trim() }.trim() }
        val summary = paragraphs.firstOrNull { it.startsWith("**Podium") }?.let(::plain)?.takeIf { it.isNotBlank() }
        val sections = LinkedHashMap<String, MutableList<String>>()
        var section = ""
        var bullet: StringBuilder? = null
        fun close() {
            bullet?.let { sections.getOrPut(section) { mutableListOf() } += it.toString() }
            bullet = null
        }
        notes.replace("\r", "").lines().forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.startsWith("#") -> {
                    close()
                    section = line.trimStart('#').trim().lowercase()
                }
                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
                    close()
                    bullet = StringBuilder(line.trimStart().drop(2).trim())
                }
                line.isBlank() -> close()
                bullet != null && raw.startsWith(" ") -> bullet!!.append(' ').append(line.trim())
                else -> close()
            }
        }
        close()
        // What's new, then what was fixed, then the rest.
        val order = sections.keys.sortedBy { k ->
            when {
                "new" in k || "added" in k -> 0
                "fix" in k -> 1
                else -> 2
            }
        }
        val headlines = order.flatMap { sections[it].orEmpty() }.map(::headline).filter { it.isNotBlank() }.distinct()
        return listOfNotNull(summary) + headlines.take(max)
    }

    /** "**Full screen:** Settings ▸ …" → "Full screen"; otherwise the first sentence, kept short. */
    private fun headline(bullet: String): String {
        Regex("^\\*\\*(.+?)\\*\\*").find(bullet)?.let { return plain(it.groupValues[1]).trimEnd(':', '.', ' ') }
        val text = plain(bullet)
        val sentence = text.substringBefore(". ").substringBefore(": ").trimEnd('.', ' ')
        return if (sentence.length <= MAX_HEADLINE) sentence else sentence.take(MAX_HEADLINE - 1).trimEnd() + "…"
    }

    /** Markdown out: links to their text, bold and code marks dropped, spaces folded. */
    fun plain(s: String): String = s
        .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
        .replace("**", "")
        .replace("__", "")
        .replace("`", "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private const val MAX_HEADLINE = 60
}
