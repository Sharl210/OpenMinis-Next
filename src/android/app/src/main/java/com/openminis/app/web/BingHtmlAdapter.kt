package com.openminis.app.web

import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * [T-android-web-search-engines] Bing search, parsed from the plain server-rendered result page.
 *
 * Bing keeps its result list in `<li class="b_algo">` blocks and its titles in `<h2><a>`; because the
 * outer list nests other lists, the parser anchors on the heading rather than on the `<li>` boundary
 * (a lazy `</li>` match would truncate the block). Bing also rewrites an increasing share of its
 * outbound links through `bing.com/ck/a?…&u=a1<base64url>`, which is decoded here so the stored URL is
 * the destination rather than a tracking redirect.
 *
 * Known-unverified: which of these selectors the live page still uses. See the adapter report — the
 * fixture under `app/src/test/resources/search/` is a hand-written approximation, not a captured page.
 */
class BingHtmlAdapter(
    clientFactory: (Dns) -> OkHttpClient = ::defaultSearchHttpClient,
) : HtmlSearchAdapter("bing-html", clientFactory) {

    internal override fun searchUrl(query: String, limit: Int): String {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val count = limit.coerceIn(1, 50)
        return "https://www.bing.com/search?q=$encoded&count=$count&mkt=zh-CN&setlang=zh-hans"
    }

    internal override fun challengeMarker(html: String): String? = when {
        html.contains("Verify you are human", ignoreCase = true) -> "bing anti-bot challenge"
        html.contains("bing.com/challenge", ignoreCase = true) -> "bing anti-bot challenge"
        else -> null
    }

    internal override fun parseResults(html: String): List<SearchItem> {
        val matches = renderings.firstNotNullOfOrNull { pattern ->
            pattern.findAll(html).toList().takeIf { it.isNotEmpty() }
        }.orEmpty()
        val starts = matches.map { it.range.first }
        return matches.mapIndexedNotNull { index, match ->
            val titleEnd = match.range.last + 1
            val until = snippetWindowEnd(starts, index, titleEnd, MAX_SNIPPET_GAP)
            val target = unwrapRedirect(match.groupValues[1])
            val url = absolutizeSearchUrl(target, BING_ORIGIN) ?: return@mapIndexedNotNull null
            if (isBingInternal(url)) return@mapIndexedNotNull null
            val title = stripHtmlMarkup(match.groupValues[2]).take(300)
            if (title.isBlank()) return@mapIndexedNotNull null
            SearchItem(title, url, snippetNearestTo(html, titleEnd, until, snippetPatterns), id)
        }.distinctBy { it.url }.toList()
    }

    /** Decodes `ck/a?…&u=a1<base64url>` tracking links back into their destination. */
    private fun unwrapRedirect(href: String): String {
        val decoded = decodeHtmlEntities(href).trim()
        if (!decoded.contains("/ck/a?")) return decoded
        val query = decoded.substringAfter('?', "")
        val raw = Regex("(?:^|&)u=([^&]+)").find(query)?.groupValues?.get(1)
        if (raw != null) {
            listOf(raw, raw.removePrefix("a1"), raw.removePrefix("a0"))
                .firstNotNullOfOrNull { decodeBase64UrlOrNull(it)?.takeIf { text -> text.startsWith("http", true) } }
                ?.let { return it }
            val percentDecoded = runCatching { URLDecoder.decode(raw, Charsets.UTF_8.name()) }.getOrNull()
            if (percentDecoded != null && percentDecoded.startsWith("http", ignoreCase = true)) return percentDecoded
        }
        return decoded
    }

    private fun isBingInternal(url: String): Boolean {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return true
        val host = uri.host?.lowercase() ?: return true
        if (host != "www.bing.com" && host != "bing.com" && host != "cn.bing.com") return false
        val path = uri.path.orEmpty()
        return path.startsWith("/ck/a") || path.startsWith("/search") || path.startsWith("/account")
    }

    private companion object {
        const val BING_ORIGIN = "https://www.bing.com/"
        const val MAX_SNIPPET_GAP = 4_000

        val renderings = listOf(
            Regex("(?is)<h2[^>]*>\\s*<a\\s[^>]*href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>"),
            Regex("(?is)<a\\s[^>]*class=[\"'][^\"']*\\btilk\\b[^\"']*[\"'][^>]*href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>"),
            Regex("(?is)<a\\s[^>]*href=[\"']([^\"']+)[\"'][^>]*class=[\"'][^\"']*\\btilk\\b[^\"']*[\"'][^>]*>(.*?)</a>"),
        )

        val snippetPatterns = listOf(
            Regex("(?is)<p[^>]*class=[\"'][^\"']*b_lineclamp[^\"']*[\"'][^>]*>(.*?)</p>"),
            Regex("(?is)<div[^>]*class=[\"'][^\"']*b_caption[^\"']*[\"'][^>]*>.*?<p[^>]*>(.*?)</p>"),
            Regex("(?is)<div[^>]*class=[\"'][^\"']*b_snippet[^\"']*[\"'][^>]*>(.*?)</div>"),
        )
    }
}
