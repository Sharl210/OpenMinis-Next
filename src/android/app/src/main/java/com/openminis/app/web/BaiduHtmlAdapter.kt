package com.openminis.app.web

import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.URI
import java.net.URLEncoder

/**
 * [T-android-web-search-engines] Baidu search, parsed from the plain server-rendered result page.
 *
 * Verified against a real `www.baidu.com/s` response (1.03 MB, `wd=kotlin coroutines`):
 * titles sit in `<h3 class="cosc-title cos-link t …">`, whose anchor `href` is *never* the
 * destination — it is a `www.baidu.com/link?url=…` redirect whose target is obfuscated server-side.
 * The real URL is present in the same result block in three places, tried in this order:
 *
 *  1. `mu="…"` on the enclosing `<div class="result c-container …">` — the most reliable, it covers
 *     every genuine result and is how Baidu itself labels the card;
 *  2. `data-url="…"` on the TTS button inside the block;
 *  3. the `url` field of the embedded `data-feedback` JSON.
 *
 * Using the redirect anyway would hand the caller a tracking link instead of the result, so a
 * redirect that yields no real target is dropped rather than stored.
 *
 * Baidu also injects ad/recommendation cards into the same container markup, labelled with
 * `mu="http://nourl.ubs.baidu.com/…"` and `mu="http://28608.recommend_list.baidu.com"`; those hosts
 * are rejected, so a card without a real destination never becomes a "result". On the captured page
 * those ad cards also carry no `<h3>`, so this is a second line of defence rather than the only one.
 *
 * Verified selectors: result container, `h3` title, `mu`/`data-url`/`data-feedback` targets and the
 * `summary-text_*` snippet container. The anti-bot marker is *not* verified against a live challenge
 * page (the captured page was a normal result page) — it stays as a best-effort guard.
 */
class BaiduHtmlAdapter(
    clientFactory: (Dns) -> OkHttpClient = ::defaultSearchHttpClient,
) : HtmlSearchAdapter("baidu-html", clientFactory) {

    internal override fun searchUrl(query: String, limit: Int): String {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val count = limit.coerceIn(1, 50)
        return "https://www.baidu.com/s?wd=$encoded&rn=$count&ie=utf-8"
    }

    internal override fun challengeMarker(html: String): String? = when {
        html.contains("百度安全验证", ignoreCase = true) -> "baidu anti-bot verification page"
        html.contains("wappass.baidu.com", ignoreCase = true) -> "baidu anti-bot verification page"
        html.contains("security-verify", ignoreCase = true) -> "baidu anti-bot verification page"
        else -> null
    }

    internal override fun parseResults(html: String): List<SearchItem> {
        val containers = resultContainers(html)
        val matches = renderings.firstNotNullOfOrNull { pattern ->
            pattern.findAll(html).toList().takeIf { it.isNotEmpty() }
        }.orEmpty()
        val starts = matches.map { it.range.first }
        return matches.mapIndexedNotNull { index, match ->
            val titleEnd = match.range.last + 1
            val until = snippetWindowEnd(starts, index, titleEnd, MAX_SNIPPET_GAP)
            val block = html.substring(titleEnd, minOf(html.length, until))
            val url = realResultUrl(match.groupValues[1], enclosingMu(containers, match.range.first), block)
                ?: return@mapIndexedNotNull null
            val title = stripHtmlMarkup(match.groupValues[2]).take(300)
            if (title.isBlank()) return@mapIndexedNotNull null
            SearchItem(title, url, snippetNearestTo(html, titleEnd, until, snippetPatterns), id)
        }.distinctBy { it.url }.toList()
    }

    /** Position and `mu` of every `<div class="result c-container …">` card, in document order. */
    private fun resultContainers(html: String): List<Pair<Int, String?>> = CONTAINER
        .findAll(html)
        .map { match -> match.range.first to ATTR_MU.find(match.value)?.groupValues?.get(1)?.let(::decodeHtmlEntities) }
        .toList()

    /** `mu` of the card this title belongs to, i.e. the last container that opens before it. */
    private fun enclosingMu(containers: List<Pair<Int, String?>>, titleIndex: Int): String? =
        containers.lastOrNull { it.first < titleIndex }?.second

    /**
     * Resolves a title to the real destination, preferring Baidu's own card metadata over its
     * opaque redirect. Returns null when only a redirect (`/link?url=…`) or an internal/ad target is
     * available, so the caller never advertises a tracking link as a search result.
     */
    private fun realResultUrl(href: String, containerMu: String?, block: String): String? {
        val candidates = listOfNotNull(containerMu, dataUrlIn(block), dataFeedbackUrlIn(block))
        for (candidate in candidates) {
            val url = absolutizeSearchUrl(candidate, BAIDU_ORIGIN) ?: continue
            if (isBaiduOwnedHost(url)) continue
            return url
        }
        // No real destination was exposed; fall back to the redirect only if it is not Baidu's own.
        val fallback = absolutizeSearchUrl(href, BAIDU_ORIGIN) ?: return null
        return if (isBaiduOwnedHost(fallback)) null else fallback
    }

    /** TTS buttons carry the destination in `data-url`. */
    private fun dataUrlIn(block: String): String? = ATTR_DATA_URL.find(block)?.groupValues?.get(1)

    /** The feedback widget embeds a JSON blob whose `url` field is the destination. */
    private fun dataFeedbackUrlIn(block: String): String? = FEEDBACK_URL.find(block)?.groupValues?.get(1)

    /**
     * Baidu's own hosts (including the ad/recommendation cards `nourl.ubs.baidu.com` and
     * `*.recommend_list.baidu.com`) are not search results.
     */
    private fun isBaiduOwnedHost(url: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return true
        return host == "baidu.com" || host.endsWith(".baidu.com")
    }

    private companion object {
        const val BAIDU_ORIGIN = "https://www.baidu.com/"
        // Measured on the captured page: the summary span starts 2 030–2 958 characters after the
        // title's </h3>, while consecutive titles are ~10 000 characters apart.
        const val MAX_SNIPPET_GAP = 8_000

        val CONTAINER = Regex("(?is)<div[^>]*class=[\"'][^\"']*\\bresult\\b[^\"']*\\bc-container\\b[^\"']*[\"'][^>]*>")
        val ATTR_MU = Regex("(?is)\\bmu=[\"']([^\"']+)[\"']")
        val ATTR_DATA_URL = Regex("(?is)\\bdata-url=[\"'](https?://[^\"'\\s]+)[\"']")
        val FEEDBACK_URL = Regex("(?is)&quot;url&quot;:&quot;(https?[^&\"]+)&quot;")

        val renderings = listOf(
            Regex(
                "(?is)<h3[^>]*class=[\"'][^\"']*\\bt\\b[^\"']*[\"'][^>]*>\\s*" +
                    "<a\\s[^>]*href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>",
            ),
            Regex("(?is)<h3[^>]*>\\s*<a\\s[^>]*href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>"),
            Regex(
                "(?is)<a\\s[^>]*class=[\"'][^\"']*\\bc-title\\b[^\"']*[\"'][^>]*" +
                    "href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>",
            ),
        )

        val snippetPatterns = listOf(
            // current layout: <span class="… summary-text_15QGa">…</span>
            Regex("(?is)<span[^>]*class=[\"'][^\"']*\\bsummary-text_[^\"']*[\"'][^>]*>(.*?)</span>"),
            Regex("(?is)<div[^>]*class=[\"'][^\"']*\\bc-abstract\\b[^\"']*[\"'][^>]*>(.*?)</div>"),
            Regex("(?is)<div[^>]*class=[\"'][^\"']*content-right_[^\"']*[\"'][^>]*>(.*?)</div>"),
            Regex("(?is)<span[^>]*class=[\"'][^\"']*content-right_[^\"']*[\"'][^>]*>(.*?)</span>"),
            Regex("(?is)<div[^>]*class=[\"'][^\"']*\\bc-span-last\\b[^\"']*[\"'][^>]*>(.*?)</div>"),
        )
    }
}
