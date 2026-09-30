package com.openminis.app.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** A public search provider. Implementations parse their own public response shape. */
interface PublicSearchAdapter {
    val id: String
    suspend fun search(query: String, limit: Int, timeoutMs: Long): SearchAdapterResult
}

class PublicSearchService(private val adapters: List<PublicSearchAdapter>) {
    suspend fun search(query: String, options: SearchOptions = SearchOptions()): SearchResult {
        val bounded = options.normalized()
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) return SearchResult(normalizedQuery, emptyList(), listOf(SearchFailure("service", "query is blank")))
        if (adapters.isEmpty()) return SearchResult(
            normalizedQuery,
            emptyList(),
            listOf(SearchFailure("service", "public search adapter not configured")),
        )
        val failures = mutableListOf<SearchFailure>()
        val perAdapter = adapters.map { adapter ->
            when (val response = runCatching { adapter.search(normalizedQuery, bounded.maxResults, bounded.timeoutMs) }
                .getOrElse { SearchAdapterResult.Failure(it.message ?: "adapter failed") }) {
                is SearchAdapterResult.Success -> response.items
                is SearchAdapterResult.Failure -> {
                    failures += SearchFailure(adapter.id, response.detail)
                    emptyList()
                }
            }
        }
        // [T-android-web-search-engines] Round-robin the engines instead of concatenating them.
        // Concatenating in adapter order meant the first engine to answer with a full page filled the
        // whole budget: measured with the app's default maxResults=10 and the engines' real result counts
        // (bing 10, baidu 8), the "aggregate" returned bing=10 and nothing from any other engine — i.e.
        // registering more engines silently REPLACED the previous one instead of adding to it. Interleaving
        // keeps each engine's own ranking order while guaranteeing every engine that has anything to say is
        // represented, which is what an aggregate is for.
        val results = interleaveByEngine(perAdapter)
            .distinctBy { it.url }
            .take(bounded.maxResults)
        return SearchResult(normalizedQuery, results, failures)
    }

    /** Takes the n-th item of every engine in turn, preserving each engine's internal order. */
    internal fun interleaveByEngine(perAdapter: List<List<SearchItem>>): List<SearchItem> {
        val merged = ArrayList<SearchItem>(perAdapter.sumOf { it.size })
        val longest = perAdapter.maxOfOrNull { it.size } ?: 0
        for (rank in 0 until longest) {
            for (items in perAdapter) {
                items.getOrNull(rank)?.let(merged::add)
            }
        }
        return merged
    }
}

/** Public DuckDuckGo HTML endpoint; challenge or format changes fail explicitly. */
class DuckDuckGoHtmlAdapter(
    private val clientFactory: (Dns) -> OkHttpClient = { dns ->
        OkHttpClient.Builder()
            .dns(dns)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    },
) : PublicSearchAdapter {
    override val id: String = "duckduckgo-html"

    override suspend fun search(query: String, limit: Int, timeoutMs: Long): SearchAdapterResult = withContext(Dispatchers.IO) {
        val boundedLimit = limit.coerceIn(1, 50)
        val boundedTimeout = timeoutMs.coerceIn(1_000L, 120_000L)
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val url = "https://html.duckduckgo.com/html/?q=$encoded"
        val client = clientFactory(BoundedSearchDns).newBuilder()
            .callTimeout(boundedTimeout, TimeUnit.MILLISECONDS)
            .connectTimeout(boundedTimeout, TimeUnit.MILLISECONDS)
            .readTimeout(boundedTimeout, TimeUnit.MILLISECONDS)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (compatible; OpenMinisNext/1.0)")
            .header("Accept", "text/html")
            .get()
            .build()
        val response = runCatching { client.newCall(request).execute() }
            .getOrElse { return@withContext SearchAdapterResult.Failure("network: ${it.message}") }
        response.use {
            if (!it.isSuccessful) return@withContext SearchAdapterResult.Failure("HTTP ${it.code}")
            val body = it.body ?: return@withContext SearchAdapterResult.Failure("empty response")
            if ((it.header("Content-Length")?.toLongOrNull() ?: 0L) > MAX_RESPONSE_BYTES) {
                return@withContext SearchAdapterResult.Failure("response exceeds size limit")
            }
            // The body must be read without okio's `readByteArray(n)`, which throws EOFException as soon
            // as the page is shorter than n — i.e. for every ordinary DuckDuckGo response.
            val bytes = readSearchBodyBounded(body, MAX_RESPONSE_BYTES)
                ?: return@withContext SearchAdapterResult.Failure("response exceeds size limit")
            val html = bytes.toString(body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
            if (html.contains("anomaly-modal", ignoreCase = true) || html.contains("captcha", ignoreCase = true)) {
                return@withContext SearchAdapterResult.Failure("search source returned an anti-bot challenge")
            }
            val parsed = parseResults(html).take(boundedLimit)
            if (parsed.isEmpty()) SearchAdapterResult.Failure("search response format changed or returned no parseable results")
            else SearchAdapterResult.Success(parsed)
        }
    }

    private fun parseResults(html: String): List<SearchItem> {
        val results = Regex("(?is)<div[^>]*class=[\"'][^\"']*result[^\"']*[\"'][^>]*>(.*?)(?=<div[^>]*class=[\"'][^\"']*result|\\z)")
            .findAll(html)
        return results.mapNotNull { match ->
            val block = match.groupValues[1]
            val anchor = Regex("(?is)<a[^>]*class=[\"'][^\"']*result__a[^\"']*[\"'][^>]*href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>")
                .find(block) ?: return@mapNotNull null
            val rawUrl = decodeHtml(anchor.groupValues[1]).let { value ->
                Regex("uddg=([^&]+)").find(value)?.groupValues?.get(1)?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() } ?: value
            }
            val safeUrl = WebAccessPolicy.validateUrl(rawUrl).getOrNull()?.toString() ?: return@mapNotNull null
            val title = stripMarkup(anchor.groupValues[2]).take(300)
            if (title.isBlank()) return@mapNotNull null
            val snippet = Regex("(?is)<(?:a|div)[^>]*class=[\"'][^\"']*result__snippet[^\"']*[\"'][^>]*>(.*?)</(?:a|div)>")
                .find(block)?.groupValues?.get(1)?.let(::stripMarkup)?.take(1_000)
            SearchItem(title, safeUrl, snippet, id)
        }.distinctBy { it.url }.toList()
    }

    private fun stripMarkup(value: String): String = decodeHtml(value.replace(Regex("(?is)<[^>]+>"), " "))
        .replace(Regex("\\s+"), " ").trim()

    private fun decodeHtml(value: String): String = value
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">")

    private object BoundedSearchDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val addresses = InetAddress.getAllByName(hostname).toList()
            WebAccessPolicy.validateResolvedAddresses(hostname, addresses).getOrThrow()
            return addresses
        }
    }

    companion object { private const val MAX_RESPONSE_BYTES = 1_000_000L }
}

data class SearchOptions(val maxResults: Int = 10, val timeoutMs: Long = 20_000L) {
    fun normalized() = copy(maxResults = maxResults.coerceIn(1, 50), timeoutMs = timeoutMs.coerceIn(1_000L, 120_000L))
}

/**
 * [T-android-web-search-settings] The options actually used for one search call.
 *
 * The requirement lets the user configure how many results the search tool may
 * return and how long it may take ("它能返回最大多少条内容,然后超时时间是多少") —
 * so the configured value is a CEILING, not merely a default. The model may ask
 * for fewer results or a shorter timeout, but must never exceed what the user
 * allowed.
 *
 * This lived inline in the tool executor, where it could not be tested at all and
 * was therefore only ever read, never asserted. Kept pure so the ceiling
 * contract, the fallback-to-user-setting behaviour and the interaction with
 * [SearchOptions.normalized]'s bounds are all checkable on the JVM.
 *
 * @param requestedMaxResults what the model asked for; null = "use the setting".
 * @param requestedTimeoutMs same, for the timeout.
 */
fun effectiveSearchOptions(
    requestedMaxResults: Int?,
    requestedTimeoutMs: Long?,
    userMaxResults: Int,
    userTimeoutMs: Long,
): SearchOptions = SearchOptions(
    maxResults = (requestedMaxResults ?: userMaxResults).coerceAtMost(userMaxResults),
    timeoutMs = (requestedTimeoutMs ?: userTimeoutMs).coerceAtMost(userTimeoutMs),
).normalized()

data class SearchItem(val title: String, val url: String, val snippet: String? = null, val source: String)
data class SearchFailure(val source: String, val detail: String)
data class SearchResult(val query: String, val items: List<SearchItem>, val failures: List<SearchFailure>)

sealed interface SearchAdapterResult {
    data class Success(val items: List<SearchItem>) : SearchAdapterResult
    data class Failure(val detail: String) : SearchAdapterResult
}
