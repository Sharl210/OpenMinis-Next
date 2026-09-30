package com.openminis.app.web

import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * [T-android-web-search-engines] Google search, parsed from the plain server-rendered result page.
 *
 * `google.com/search` returns markup that changes often and that Google actively defends, so the
 * parser is built to be tolerant in one direction only: any anchor whose visible title sits in an
 * `<h3>` is treated as a result, several snippet layouts are tried in order, and everything that is
 * not a real outbound HTTP(S) target (Google's own navigation, `#` links, `/url?q=` wrappers pointing
 * at Google itself) is dropped. When the layout is beyond recognition the adapter reports a failure
 * with a reason instead of silently returning nothing.
 *
 * Reality check from a real captured response: Google currently answers `google.com/search` from a
 * non-JS client with a ~92 KB JS-only shell (no `<h3>`, no `id="rso"`, `emsg=SG_REL`) regardless of
 * URL form (`&gbv=1`, `&udm=14`, `ncr`, full browser headers). So this adapter's parser is *not*
 * exercised by the live engine today; [challengeMarker] reports that shell explicitly, which is the
 * handler behaviour a caller should see. Which of the selectors below the live server-rendered page
 * would use is therefore still unverified — the fixture for the parser is a hand-written
 * approximation, while `google-shell.html` is a trimmed copy of the real response.
 */
class GoogleHtmlAdapter(
    clientFactory: (Dns) -> OkHttpClient = ::defaultSearchHttpClient,
) : HtmlSearchAdapter("google-html", clientFactory) {

    internal override fun searchUrl(query: String, limit: Int): String {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val count = limit.coerceIn(1, 50)
        return "https://www.google.com/search?q=$encoded&num=$count&hl=zh-CN&pws=0"
    }

    internal override fun challengeMarker(html: String): String? = when {
        html.contains("/sorry/index", ignoreCase = true) -> "google anti-bot interstitial (/sorry/index)"
        html.contains("unusual traffic", ignoreCase = true) -> "google anti-bot interstitial (unusual traffic)"
        html.contains("consent.google.com", ignoreCase = true) -> "google consent interstitial"
        // Verified against a real google.com/search response: the page has no <h3>, no id="rso" and no
        // id="search", and ends with an "If you're having trouble accessing Google Search … emsg=SG_REL"
        // notice. Google serves this JS-only shell whenever it will not hand results to a client that
        // cannot run scripts, and it does NOT use /sorry/index for it. Reporting the honest reason keeps
        // the next reader from hunting for a selector fix that can never work.
        html.contains("emsg=SG_REL", ignoreCase = true) ->
            "google served a JS-only shell page (no server-rendered results; emsg=SG_REL)"
        html.contains("id=\"yvlrue\"", ignoreCase = true) &&
            html.contains("having trouble accessing Google Search", ignoreCase = true) ->
            "google served a JS-only shell page (no server-rendered results)"
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
            val rawHref = match.groupValues[1]
            val target = unwrapRedirect(rawHref)
            if (target == null) return@mapIndexedNotNull null
            if (isGoogleInternal(target)) return@mapIndexedNotNull null
            val url = absolutizeSearchUrl(target, GOOGLE_ORIGIN) ?: return@mapIndexedNotNull null
            val title = stripHtmlMarkup(match.groupValues[2]).take(300)
            if (title.isBlank()) return@mapIndexedNotNull null
            SearchItem(title, url, snippetNearestTo(html, titleEnd, until, snippetPatterns), id)
        }.distinctBy { it.url }.toList()
    }

    /** Unwraps `google.com/url?q=<target>` style redirects and passes everything else through. */
    private fun unwrapRedirect(href: String): String? {
        val decoded = decodeHtmlEntities(href).trim()
        if (decoded.isEmpty()) return null
        if (decoded.startsWith("http", ignoreCase = true)) return decoded
        if (!decoded.contains("/url?")) return decoded
        val query = decoded.substringAfter('?', "")
        for (key in listOf("q", "url")) {
            val raw = Regex("(?:^|&)$key=([^&]*)").find(query)?.groupValues?.get(1) ?: continue
            val candidate = runCatching { URLDecoder.decode(raw, Charsets.UTF_8.name()) }.getOrNull() ?: raw
            if (candidate.startsWith("http", ignoreCase = true)) return candidate
        }
        return null
    }

    /** Google's own navigation arrives as `/search?…`, `/preferences`, federation hosts, and so on. */
    private fun isGoogleInternal(target: String): Boolean {
        val resolved = runCatching { java.net.URI(GOOGLE_ORIGIN).resolve(decodeHtmlEntities(target).replace(" ", "%20")) }
            .getOrNull() ?: return true
        val host = resolved.host?.lowercase() ?: return true
        if (host in INTERNAL_HOSTS) return true
        // `endsWith("google.com")` would also swallow `evilgoogle.com`, so match the domain boundary.
        val isGoogleHost = host == "google.com" || host.endsWith(".google.com") ||
            host.endsWith(".googleusercontent.com")
        if (!isGoogleHost) return false
        val path = resolved.path.orEmpty()
        return INTERNAL_PATHS.any { path.equals(it, ignoreCase = true) || path.startsWith("$it/") }
    }

    private companion object {
        const val GOOGLE_ORIGIN = "https://www.google.com/"
        const val MAX_SNIPPET_GAP = 4_000

        val INTERNAL_HOSTS = setOf(
            "accounts.google.com", "policies.google.com", "ogs.google.com",
            "www.gstatic.com", "encrypted-tbn0.gstatic.com",
        )
        val INTERNAL_PATHS = listOf(
            "/search", "/url", "/preferences", "/setprefs", "/advanced_search", "/webhp", "/save",
        )

        /**
         * Result anchors: the title is always an `<h3>` and the anchor opens before it on the modern
         * layout, while the older layout nests the anchor inside the heading.
         */
        val renderings = listOf(
            Regex(
                "(?is)<a\\s[^>]*href=[\"']([^\"']+)[\"'][^>]*>" +
                    "(?:(?!</a>).){0,1000}?<h3[^>]*>(.*?)</h3>",
            ),
            Regex(
                "(?is)<h3[^>]*>\\s*<a\\s[^>]*href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>",
            ),
        )

        val snippetPatterns = listOf(
            Regex(
                "(?is)<div[^>]*class=[\"'][^\"']*(?:VwiC3b|IsZvec|LY7yXd|aCOpRe|yDYNvb)[^\"']*[\"'][^>]*>(.*?)</div>",
            ),
            Regex(
                "(?is)<span[^>]*class=[\"'][^\"']*(?:VwiC3b|aCOpRe|st)\\b[^\"']*[\"'][^>]*>(.*?)</span>",
            ),
            Regex("(?is)<div[^>]*class=[\"'][^\"']*MjjYud[^\"']*[\"'][^>]*>.*?<div[^>]*>(.*?)</div>"),
        )
    }
}
