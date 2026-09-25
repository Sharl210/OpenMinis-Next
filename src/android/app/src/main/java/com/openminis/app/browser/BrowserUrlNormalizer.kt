package com.openminis.app.browser

import java.net.URI

/** Canonical URL identity for browser history and bookmark operations. */
object BrowserUrlNormalizer {
    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.startsWith("about:", ignoreCase = true)) {
            return "about:" + trimmed.substringAfter(':')
        }

        val candidate = when {
            trimmed.startsWith("//") -> "https:$trimmed"
            SCHEME_WITH_AUTHORITY.matches(trimmed) -> trimmed
            else -> "https://$trimmed"
        }
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return trimmed
        val host = uri.host ?: return trimmed
        val scheme = uri.scheme ?: return trimmed
        val authority = uri.rawAuthority ?: return trimmed
        val userInfo = authority.substringBeforeLast('@', missingDelimiterValue = "")
            .takeIf { '@' in authority }
            ?.let { "$it@" }
            .orEmpty()
        val normalizedHost = host.lowercase()
        val normalizedAuthority = buildString {
            append(userInfo)
            append(normalizedHost)
            if (uri.port >= 0) append(':').append(uri.port)
        }
        val path = uri.rawPath.orEmpty().trimEnd('/')
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        val fragment = uri.rawFragment?.let { "#$it" }.orEmpty()
        return "${scheme.lowercase()}://$normalizedAuthority$path$query$fragment"
    }

    private val SCHEME_WITH_AUTHORITY = Regex("^[A-Za-z][A-Za-z0-9+.-]*://.+")
}
