package com.openminis.app.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.URI
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * [T-android-web-search-engines] Shared plumbing for the public HTML search adapters.
 *
 * The requirement asks the agent's web search to aggregate the mainstream engines ("谷歌百度必应")
 * behind one tool. Adding an engine must therefore be cheap and, more importantly, must never let a
 * single engine break the aggregate: every step below reports a failure as a value the caller can
 * record, and never throws.
 *
 * @see PublicSearchAdapter for the contract each engine implements.
 * @see HtmlSearchAdapter for the HTTP/response/limit skeleton shared by Google, Bing and Baidu.
 */

/**
 * Hard ceiling on how many bytes of a search page an adapter will buffer.
 *
 * 4 MB, not 1 MB: a real `www.baidu.com/s` response measured 1 055 019 bytes (inline SSR JSON and
 * card metadata), so a 1 MB ceiling silently disabled that engine entirely. The cap exists only to
 * stop a pathological/hostile body, and a search page at a few MB is normal, so it must not sit at
 * the edge of what the engines actually return.
 */
internal const val SEARCH_RESPONSE_BYTE_LIMIT = 4_000_000L

/**
 * Desktop-page user agent. All three engines serve a degraded or challenge page to unknown clients,
 * so the adapters have to look like the browser the user themselves would use.
 */
internal const val SEARCH_BROWSER_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/126.0.0.0 Mobile Safari/537.36"

/** Default client used by the engine adapters; redirects stay disabled so a challenge page is visible as such. */
internal fun defaultSearchHttpClient(dns: Dns): OkHttpClient = OkHttpClient.Builder()
    .dns(dns)
    .followRedirects(false)
    .followSslRedirects(false)
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .build()

/** Re-checks every resolved address so a public search host cannot be redirected to a private target. */
internal object BoundedSearchDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = InetAddress.getAllByName(hostname).toList()
        WebAccessPolicy.validateResolvedAddresses(hostname, addresses).getOrThrow()
        return addresses
    }
}

/**
 * Reads at most [limit] bytes from [body], or returns null when the response is larger.
 *
 * Deliberately not `source().readByteArray(n)`: that okio call throws [java.io.EOFException] as soon
 * as the body is shorter than `n`, which turns every ordinary (sub-megabyte) search page into an
 * opaque adapter failure.
 */
internal fun readSearchBodyBounded(body: ResponseBody, limit: Long = SEARCH_RESPONSE_BYTE_LIMIT): ByteArray? {
    val collected = ByteArrayOutputStream()
    val chunk = ByteArray(16 * 1024)
    body.byteStream().use { input ->
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            if (collected.size().toLong() + read > limit) return null
            collected.write(chunk, 0, read)
        }
    }
    return collected.toByteArray()
}

/** Decodes the named and numeric entities that appear in engine result markup. */
internal fun decodeHtmlEntities(value: String): String {
    if (!value.contains('&')) return value
    return Regex("&(#[xX][0-9a-fA-F]{1,6}|#[0-9]{1,7}|[a-zA-Z][a-zA-Z0-9]{1,9});").replace(value) { match ->
        val body = match.groupValues[1]
        val codePoint = when {
            body.startsWith("#x", ignoreCase = true) -> body.drop(2).toIntOrNull(16)
            body.startsWith("#") -> body.drop(1).toIntOrNull()
            else -> when (body.lowercase()) {
                "amp" -> '&'.code
                "lt" -> '<'.code
                "gt" -> '>'.code
                "quot" -> '"'.code
                "apos" -> '\''.code
                "nbsp" -> ' '.code
                "ensp", "emsp", "thinsp" -> ' '.code
                "middot" -> 0x00B7
                "hellip" -> 0x2026
                "mdash" -> 0x2014
                "ndash" -> 0x2013
                "ldquo" -> 0x201C
                "rdquo" -> 0x201D
                "lsquo" -> 0x2018
                "rsquo" -> 0x2019
                else -> null
            }
        }
        if (codePoint == null || codePoint !in 0x20..0x10FFFF) match.value else String(Character.toChars(codePoint))
    }
}

/** Strips tags, decodes entities and collapses whitespace so a snippet or title is safe to store. */
internal fun stripHtmlMarkup(value: String): String = decodeHtmlEntities(value.replace(Regex("(?is)<[^>]*>"), " "))
    .replace(Regex("[\\s\\u00a0\\u3000]+"), " ")
    .trim()

/**
 * Turns an engine result `href` into an absolute, policy-checked URL.
 *
 * Engines mix absolute links, site-relative links (`/link?url=…`) and fragment-only navigation;
 * anything that is not a usable HTTP(S) target is dropped instead of being stored as a broken result.
 */
internal fun absolutizeSearchUrl(href: String, baseUrl: String): String? {
    val cleaned = decodeHtmlEntities(href).trim().replace(" ", "%20")
    if (cleaned.isEmpty() || cleaned.startsWith("#")) return null
    if (cleaned.startsWith("javascript:", true) || cleaned.startsWith("data:", true)) return null
    val resolved = runCatching { URI(baseUrl).resolve(cleaned) }.getOrNull() ?: return null
    return WebAccessPolicy.validateUrl(resolved.toString()).getOrNull()?.toString()
}

/** Decodes unpadded base64url, the encoding Bing uses for the `u=` parameter of its redirect links. */
internal fun decodeBase64UrlOrNull(value: String): String? {
    val normalized = value.trim().replace('-', '+').replace('_', '/')
    if (normalized.isEmpty()) return null
    val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
    return runCatching { String(Base64.getDecoder().decode(padded), Charsets.UTF_8) }.getOrNull()
}

/**
 * Identifies the response as *another* search engine's page, or returns null.
 *
 * Needed because the per-engine selectors overlap: measured against real captured responses, a Baidu
 * result page contains `<h3><a href=…>` inside a `<div class="… c-container …">`, which matches the
 * Google adapter's generic "heading-inside-anchor" pattern — Google parsing a Baidu page produced eight
 * `www.baidu.com/link?url=…` entries. Since the aggregate cannot tell a wrong-engine answer from a right
 * one, the engine that receives a foreign page has to reject it.
 *
 * The signals are deliberately competitor-specific (Bing's `b_algo`, Baidu's `bdstatic.com`/`cosc-`,
 * Google's shell) rather than a positive fingerprint of the receiving engine, so an unfamiliar variant
 * of the *right* engine still parses instead of being rejected for failing to look familiar.
 */
internal fun foreignSearchPageMarker(html: String, ownEngine: String): String? = when {
    ownEngine != "bing" && (html.contains("b_algo") || html.contains("id=\"b_results\"")) ->
        "response is a Bing result page, not $ownEngine"
    ownEngine != "baidu" &&
        (html.contains("bdstatic.com") || html.contains("cosc-title") || html.contains("baidu.com/link?")) ->
        "response is a Baidu result page, not $ownEngine"
    ownEngine != "google" && html.contains("emsg=SG_REL") ->
        "response is a Google JS-only shell page, not $ownEngine"
    else -> null
}

/**
 * Reads the snippet that belongs to the result whose title ends at [from], searching [from] to [until].
 *
 * Engines put the snippet in one of several containers and the container sits somewhere after the
 * title, so the *nearest* candidate wins across all [patterns] — taking the first pattern that matches
 * anything would attach the next result's snippet to this one. The caller supplies [until] as the start
 * of the next result (capped by a per-engine maximum gap), because the offset is markup-dependent:
 * measured on a real Baidu page the snippet begins 2 030–2 958 characters after `</h3>`.
 */
internal fun snippetNearestTo(html: String, from: Int, until: Int, patterns: List<Regex>): String? {
    if (from >= until || from >= html.length) return null
    val tail = html.substring(from, minOf(until, html.length))
    var best: String? = null
    var bestStart = Int.MAX_VALUE
    for (pattern in patterns) {
        val match = pattern.find(tail) ?: continue
        if (match.range.first >= bestStart) continue
        val text = stripHtmlMarkup(match.groupValues[1]).take(1_000)
        if (text.isBlank()) continue
        bestStart = match.range.first
        best = text
    }
    return best
}

/**
 * The end of the window that belongs to the result at [index]: the next result's title, never further
 * than [maxGap] past [from] so that a page with a single result still has a bounded search.
 */
internal fun snippetWindowEnd(matchStarts: List<Int>, index: Int, from: Int, maxGap: Int): Int =
    (matchStarts.getOrNull(index + 1) ?: (from + maxGap)).coerceAtMost(from + maxGap)

/**
 * The HTTP/response/limit half of an HTML search engine.
 *
 * Subclasses own only the engine-specific concerns: the request URL, the marker that means "this page is
 * an anti-bot challenge", the markup parsing, and the language hint. Everything shared here exists once,
 * so a failure in one engine cannot leak into another and every engine reports failures the same way the
 * pre-existing DuckDuckGo adapter does. Two shared guards run before parsing: the anti-bot marker and
 * [foreignSearchPageMarker], because the engines' result markup overlaps enough that one engine would
 * otherwise happily parse another's page.
 *
 * A page that yields no parsable result is reported as [SearchAdapterResult.Failure] — never as an
 * empty success — so the aggregate can distinguish "engine answered, layout unknown" from "engine
 * answered with genuinely zero results, which these engines never do".
 */
abstract class HtmlSearchAdapter(
    final override val id: String,
    private val clientFactory: (Dns) -> OkHttpClient = ::defaultSearchHttpClient,
) : PublicSearchAdapter {

    /** Absolute request URL for [query]; [limit] is applied through `num`/`count`/`rn` where supported. */
    internal abstract fun searchUrl(query: String, limit: Int): String

    /** Engine-specific anti-bot marker found in [html], or null when the page looks like a result page. */
    internal abstract fun challengeMarker(html: String): String?

    /** Engine name this adapter receives responses for; used to reject another engine's page. */
    internal open val engineName: String = id.removeSuffix("-html")

    /** Extracts every result the markup exposes; an unrecognised layout yields an empty list. */
    internal abstract fun parseResults(html: String): List<SearchItem>

    /** Language hint sent with every request; engines localise results and challenges based on it. */
    internal open val acceptLanguage: String = "zh-CN,zh;q=0.9,en;q=0.8"

    override suspend fun search(query: String, limit: Int, timeoutMs: Long): SearchAdapterResult =
        withContext(Dispatchers.IO) {
            val boundedLimit = limit.coerceIn(1, 50)
            val boundedTimeout = timeoutMs.coerceIn(1_000L, 120_000L)
            val client = clientFactory(BoundedSearchDns).newBuilder()
                .callTimeout(boundedTimeout, TimeUnit.MILLISECONDS)
                .connectTimeout(boundedTimeout, TimeUnit.MILLISECONDS)
                .readTimeout(boundedTimeout, TimeUnit.MILLISECONDS)
                .build()
            val request = Request.Builder()
                .url(searchUrl(query, boundedLimit))
                .header("User-Agent", SEARCH_BROWSER_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", acceptLanguage)
                .get()
                .build()
            val response = runCatching { client.newCall(request).execute() }
                .getOrElse { return@withContext SearchAdapterResult.Failure("network: ${it.message}") }
            response.use {
                if (!it.isSuccessful) return@withContext SearchAdapterResult.Failure("HTTP ${it.code}")
                val body = it.body ?: return@withContext SearchAdapterResult.Failure("empty response")
                val bytes = readSearchBodyBounded(body)
                    ?: return@withContext SearchAdapterResult.Failure("response exceeds size limit")
                val html = bytes.toString(body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
                challengeMarker(html)?.let { marker -> return@withContext SearchAdapterResult.Failure(marker) }
                foreignSearchPageMarker(html, engineName)?.let { marker ->
                    return@withContext SearchAdapterResult.Failure(marker)
                }
                val parsed = parseResults(html).take(boundedLimit)
                if (parsed.isEmpty()) {
                    SearchAdapterResult.Failure("$id page layout not recognised or returned no results")
                } else {
                    SearchAdapterResult.Success(parsed)
                }
            }
        }
}
