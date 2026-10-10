package app.podium.sources.youtubemusic

import java.security.MessageDigest

/**
 * A signed-in YouTube Music web session: the cookies Google's own sign-in page left behind
 * (YOUTUBE_MUSIC_ARCHITECTURE.md §6). It's only ever held in memory here; at rest it lives sealed in
 * the app's Keystore-backed credential store. It never appears in logs, exceptions or `toString`.
 */
internal object SessionCookies {
    /** The session cookies accepted for authorization. At least one must be present for a usable session. */
    val AUTH_COOKIE_NAMES: Set<String> = setOf("SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID")
}

internal class WebSession private constructor(private val cookies: Map<String, String>) {

    /** The `Cookie` header for requests to the music origin. */
    val cookieHeader: String get() = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    /** The secret the per-request authorization is derived from; a session without one is unusable. */
    private val apisid: String? get() = cookies["SAPISID"] ?: cookies["__Secure-3PAPISID"] ?: cookies["__Secure-1PAPISID"]

    val isUsable: Boolean get() = apisid != null

    /**
     * The `Authorization` header the web player sends with each signed-in request: the time, and a
     * SHA-1 of the time, the session's API secret and the origin. Recomputed for every request.
     */
    fun authorization(nowSeconds: Long, origin: String): String? {
        val secret = apisid ?: return null
        val digest = MessageDigest.getInstance("SHA-1").digest("$nowSeconds $secret $origin".toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "SAPISIDHASH ${nowSeconds}_$hex"
    }

    override fun toString(): String = "WebSession(${cookies.size} cookies, usable=$isUsable)"

    companion object {
        /** Parse a `Cookie` header ("a=1; b=2"). Blank names and values are dropped. */
        fun parse(header: String?): WebSession? {
            if (header.isNullOrBlank()) return null
            val cookies = LinkedHashMap<String, String>()
            header.split(';').forEach { part ->
                val eq = part.indexOf('=')
                if (eq <= 0) return@forEach
                val name = part.substring(0, eq).trim()
                val value = part.substring(eq + 1).trim()
                if (name.isNotEmpty() && value.isNotEmpty() && name.none { it.isWhitespace() }) cookies[name] = value
            }
            return WebSession(cookies).takeIf { it.isUsable }
        }
    }
}
