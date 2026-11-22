package vn.tramtruyen.reader.engine

import java.net.URI

/** One URL policy shared by UI and networking. Cleartext HTTP is disabled app-wide. */
object UrlPolicy {
    fun normalizeHttps(raw: String): String? {
        val value = raw.trim()
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.host.isNullOrBlank() || uri.userInfo != null) return null
        return uri.toASCIIString()
    }

    fun isAllowed(raw: String): Boolean = normalizeHttps(raw) != null
}