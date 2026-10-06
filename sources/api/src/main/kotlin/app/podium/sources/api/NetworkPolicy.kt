package app.podium.sources.api

import java.net.URI

/**
 * Which server addresses Podium will talk to (D-09). The platform allows cleartext, because a
 * network-security-config can't name IP ranges; this is the real gate: `https` anywhere, `http`
 * only to a host on the listener's own network (private, link-local or loopback addresses, and
 * `.local` / `.lan` / `.home.arpa` names) and only after the listener has been warned.
 */
object NetworkPolicy {

    sealed interface Verdict {
        /** A normalized base address (scheme, host, port, path; no query, no trailing slash). */
        val base: String?

        data class Secure(override val base: String) : Verdict
        data class LocalCleartext(override val base: String) : Verdict
        data class Refused(val problem: SetupProblem) : Verdict {
            override val base: String? get() = null
        }
    }

    fun check(address: String): Verdict {
        val trimmed = address.trim()
        if (trimmed.isEmpty()) return Verdict.Refused(SetupProblem.MISSING_FIELD)
        // An address typed without a scheme is assumed to be https.
        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return Verdict.Refused(SetupProblem.BAD_ADDRESS)
        val host = uri.host?.lowercase() ?: return Verdict.Refused(SetupProblem.BAD_ADDRESS)
        if (uri.userInfo != null || uri.query != null || uri.fragment != null) return Verdict.Refused(SetupProblem.BAD_ADDRESS)
        val base = buildString {
            append(uri.scheme.lowercase()).append("://")
            append(if (':' in host && !host.startsWith('[')) "[$host]" else host)
            if (uri.port != -1) append(':').append(uri.port)
            append(uri.rawPath.orEmpty().trimEnd('/'))
        }
        return when (uri.scheme.lowercase()) {
            "https" -> Verdict.Secure(base)
            "http" -> if (isLocal(host)) Verdict.LocalCleartext(base) else Verdict.Refused(SetupProblem.INSECURE_ADDRESS)
            else -> Verdict.Refused(SetupProblem.BAD_ADDRESS)
        }
    }

    /**
     * Whether Podium may open [url]: never unencrypted to a host off the listener's own network.
     * Checked on every request a source makes and every stream it hands the player, since the
     * platform's own cleartext block is off app-wide. Other schemes (files, content) aren't traffic.
     */
    fun permits(url: String): Boolean {
        val scheme = url.substringBefore("://", missingDelimiterValue = "")
        if (!scheme.equals("http", ignoreCase = true)) return true
        val authority = url.substring(scheme.length + 3).substringBefore('/').substringBefore('?').substringBefore('#')
        val hostAndPort = authority.substringAfterLast('@')
        val host = if (hostAndPort.startsWith('[')) hostAndPort.substringBefore(']') + "]" else hostAndPort.substringBefore(':')
        return host.isNotEmpty() && isLocal(host)
    }

    /** A host on the listener's own network (by address or local name), never resolved via DNS here. */
    fun isLocal(host: String): Boolean {
        val h = host.lowercase().removePrefix("[").removeSuffix("]")
        if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa")) return true
        ipv4(h)?.let { (a, b) ->
            return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
        }
        if (':' in h) {
            return h == "::1" || h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd")
        }
        return false
    }

    private fun ipv4(h: String): Pair<Int, Int>? {
        val parts = h.split('.')
        if (parts.size != 4) return null
        val numbers = parts.map { it.toIntOrNull()?.takeIf { n -> n in 0..255 } ?: return null }
        return numbers[0] to numbers[1]
    }
}
