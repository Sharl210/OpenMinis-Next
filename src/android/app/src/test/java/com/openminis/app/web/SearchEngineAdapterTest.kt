package com.openminis.app.web

import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-web-search-engines] Offline contract tests for the Google, Bing and Baidu adapters.
 *
 * Nothing here touches the network. The HTTP path is driven through an injected OkHttp interceptor that
 * answers from a canned body, so the request URL, the body limit, the anti-bot/foreign-page guards, the
 * result limit and the failure reporting are all exercised without a socket.
 *
 * Fixtures come in two kinds and the distinction matters when reading a failure:
 *  - `*-real.html` are trimmed copies of real captured responses (see each file's header comment).
 *    They pin behaviour to what the engines actually returned on 2026-09-30.
 *  - `*-results.html` are hand-written approximations of a *successful* result page. Google's real
 *    response is a JS-only shell (see `google-shell.html`), so its success-page fixture is synthetic.
 */
class SearchEngineAdapterTest {

    // ---------------------------------------------------------------- parsing

    @Test
    fun `google parses titles snippets and unwraps url redirects`() {
        val items = GoogleHtmlAdapter().parseResults(fixture("google-results.html"))

        assertEquals(3, items.size)
        assertEquals("Kotlin 官方文档 - kotlinlang.org", items[0].title)
        assertEquals("https://kotlinlang.org/docs/home.html", items[0].url)
        assertTrue(items[0].snippet!!.contains("跨平台的静态类型编程语言"))
        assertEquals("google-html", items[0].source)

        assertEquals("https://developer.android.com/kotlin", items[1].url)
        assertTrue(items[1].snippet!!.contains("官方推荐的开发语言 & 工具链"))

        // percent-encoded /url?q= payload is decoded before the URL is stored
        assertEquals("https://example.org/kotlin-coroutines?ref=g", items[2].url)
        assertEquals("一篇协程教程。", items[2].snippet)
    }

    @Test
    fun `google drops its own navigation instead of reporting it as a result`() {
        val html = """
            <html><body>
            <a href="/search?q=related"><h3>相关搜索</h3></a>
            <a href="/preferences"><h3>搜索设置</h3></a>
            <a href="#"><h3>返回顶部</h3></a>
            <a href="javascript:void(0)"><h3>脚本链接</h3></a>
            <a href="https://accounts.google.com/signin"><h3>登录</h3></a>
            </body></html>
        """.trimIndent()
        assertTrue(GoogleHtmlAdapter().parseResults(html).isEmpty())
    }

    @Test
    fun `bing parses a real captured result page including ck tracking links`() {
        val items = BingHtmlAdapter().parseResults(fixture("bing-real.html"))

        assertEquals(3, items.size)
        assertEquals("https://www.sejuku.net/blog/92718", items[0].url)
        assertTrue(items[0].title.isNotBlank())
        assertTrue(items[0].snippet!!.isNotBlank())
        assertEquals("bing-html", items[0].source)
        // the u=a1<base64url> payload is decoded, so no bing.com/ck redirect survives
        assertTrue(items.none { it.url.contains("bing.com/ck") })
        assertTrue(items.all { it.url.startsWith("http") })
    }

    @Test
    fun `bing parses result headings and decodes ck tracking links`() {
        val items = BingHtmlAdapter().parseResults(fixture("bing-results.html"))

        assertEquals(3, items.size)
        assertEquals("IntelliJ IDEA & Kotlin IDE", items[0].title)
        assertEquals("https://jetbrains.com/idea/", items[0].url)
        assertTrue(items[0].snippet!!.contains("内置 Kotlin 支持"))
        assertEquals("bing-html", items[0].source)

        // bing.com/ck/a?…&u=a1<base64url> resolves to the destination, not to bing
        assertEquals("https://www.example.org/kotlin-guide", items[1].url)
        assertEquals("https://example.net/docs?lang=zh", items[2].url)
    }

    @Test
    fun `baidu resolves the real destination instead of the tracking redirect`() {
        val items = BaiduHtmlAdapter().parseResults(fixture("baidu-real.html"))

        // The advertisement card (mu=http://nourl.ubs.baidu.com/...) is not a result.
        assertEquals(3, items.size)
        assertTrue(items.none { it.url.contains("nourl.ubs") })

        // Real destination from mu= on the result container, with a real snippet -- previously the
        // adapter returned http://www.baidu.com/link?url=... here and no snippet at all.
        assertEquals("https://kotlinlang.org/docs/coroutines-basics.html", items[0].url)
        assertTrue(items[0].snippet!!.contains("coroutines"))
        assertEquals("baidu-html", items[0].source)

        assertEquals("https://cloud.tencent.com/developer/news/360536", items[1].url)
        assertTrue(items[1].snippet!!.contains("协程"))
        assertEquals("https://www.jianshu.com/p/52ed2771465e", items[2].url)
        assertTrue(items.none { it.url.contains("baidu.com/link?") })
    }

    @Test
    fun `baidu falls back to data-url and data-feedback when mu is missing`() {
        val html = """
            <div class="result c-container">
              <h3 class="cosc-title t"><a href="http://www.baidu.com/link?url=OPAQUE">数据网址回退</a></h3>
              <div data-url="https://fallback.example.com/page" class="tts"></div>
            </div>
            <div class="result c-container">
              <h3 class="cosc-title t"><a href="http://www.baidu.com/link?url=OPAQUE2">反馈回退</a></h3>
              <div data-feedback="{&quot;title&quot;:&quot;t&quot;,&quot;url&quot;:&quot;https://feedback.example.com/x&quot;}"></div>
            </div>
        """.trimIndent()
        val items = BaiduHtmlAdapter().parseResults(html)

        assertEquals(2, items.size)
        assertEquals("https://fallback.example.com/page", items[0].url)
        assertEquals("https://feedback.example.com/x", items[1].url)
    }

    @Test
    fun `baidu drops a result that only exposes an opaque redirect`() {
        val html = """
            <div class="result c-container">
              <h3 class="cosc-title t"><a href="http://www.baidu.com/link?url=OPAQUE">只有跳转链</a></h3>
            </div>
        """.trimIndent()
        // Storing the redirect would hand the caller a tracking link rather than the result.
        assertTrue(BaiduHtmlAdapter().parseResults(html).isEmpty())
    }

    @Test
    fun `every engine returns an empty list for an unrecognised layout`() {
        val html = fixture("unrecognised.html")
        assertTrue(GoogleHtmlAdapter().parseResults(html).isEmpty())
        assertTrue(BingHtmlAdapter().parseResults(html).isEmpty())
        assertTrue(BaiduHtmlAdapter().parseResults(html).isEmpty())
    }

    @Test
    fun `each engine recognises its alternate result markup`() {
        // Google's older layout nests the anchor inside the heading instead of the heading inside the anchor.
        val google = GoogleHtmlAdapter().parseResults(
            """
            <html><body>
            <h3 class="r"><a href="https://kotlinlang.org/docs/home.html">Kotlin 官方文档</a></h3>
            <span class="st">Kotlin 官方文档与教程。</span>
            </body></html>
            """.trimIndent(),
        )
        assertEquals(1, google.size)
        assertEquals("Kotlin 官方文档", google[0].title)
        assertEquals("https://kotlinlang.org/docs/home.html", google[0].url)
        assertEquals("Kotlin 官方文档与教程。", google[0].snippet)

        // Bing also emits plain `tilk` anchors for some result types.
        val bing = BingHtmlAdapter().parseResults(
            """
            <html><body>
            <a class="tilk" href="https://example.com/page">备用结果标题</a>
            </body></html>
            """.trimIndent(),
        )
        assertEquals(1, bing.size)
        assertEquals("https://example.com/page", bing[0].url)

        // Baidu's newer heading class list carries no standalone `t`; mu supplies the destination and
        // the opaque /link?url= redirect must never become the stored URL.
        val baidu = BaiduHtmlAdapter().parseResults(
            """
            <html><body>
            <div class="result c-container" mu="https://fallback.example.org/article">
              <h3 class="c-title"><a href="https://www.baidu.com/link?url=Fallback1">备用结果标题</a></h3>
            </div>
            </body></html>
            """.trimIndent(),
        )
        assertEquals(1, baidu.size)
        assertEquals("https://fallback.example.org/article", baidu[0].url)
    }

    // ---------------------------------------------------------------- request shape

    @Test
    fun `each engine builds an encoded absolute search url that carries the result limit`() {
        assertEquals(
            "https://www.google.com/search?q=kotlin+%E5%8D%8F%E7%A8%8B&num=7&hl=zh-CN&pws=0",
            GoogleHtmlAdapter().searchUrl("kotlin 协程", 7),
        )
        assertEquals(
            "https://www.bing.com/search?q=kotlin+%E5%8D%8F%E7%A8%8B&count=7&mkt=zh-CN&setlang=zh-hans",
            BingHtmlAdapter().searchUrl("kotlin 协程", 7),
        )
        assertEquals(
            "https://www.baidu.com/s?wd=kotlin+%E5%8D%8F%E7%A8%8B&rn=7&ie=utf-8",
            BaiduHtmlAdapter().searchUrl("kotlin 协程", 7),
        )
    }

    // ---------------------------------------------------------------- HTTP path (stubbed, still offline)

    @Test
    fun `search truncates parsed results to the requested limit`() = runBlocking {
        val adapters = listOf(
            GoogleHtmlAdapter(stubClient(fixture("google-results.html"))),
            BingHtmlAdapter(stubClient(fixture("bing-results.html"))),
            BaiduHtmlAdapter(stubClient(fixture("baidu-real.html"))),
        )
        for (adapter in adapters) {
            val result = adapter.search("kotlin", 2, 20_000)
            assertTrue("${adapter.id} should succeed", result is SearchAdapterResult.Success)
            assertEquals("${adapter.id} limit", 2, (result as SearchAdapterResult.Success).items.size)
        }
    }

    @Test
    fun `search reports a reason instead of throwing when the layout is unknown`() = runBlocking {
        val adapters = listOf(
            GoogleHtmlAdapter(stubClient(fixture("unrecognised.html"))),
            BingHtmlAdapter(stubClient(fixture("unrecognised.html"))),
            BaiduHtmlAdapter(stubClient(fixture("unrecognised.html"))),
        )
        for (adapter in adapters) {
            val result = adapter.search("kotlin", 10, 20_000)
            assertTrue("${adapter.id} must not report success", result is SearchAdapterResult.Failure)
            val detail = (result as SearchAdapterResult.Failure).detail
            assertTrue(detail, detail.contains("not recognised"))
        }
    }

    @Test
    fun `search reports an http error as a failure value`() = runBlocking {
        for (adapter in listOf(
            GoogleHtmlAdapter(stubClient(fixture("google-results.html"), code = 503)),
            BingHtmlAdapter(stubClient(fixture("bing-results.html"), code = 503)),
            BaiduHtmlAdapter(stubClient(fixture("baidu-real.html"), code = 503)),
        )) {
            val result = adapter.search("kotlin", 10, 20_000)
            assertEquals("HTTP 503", (result as SearchAdapterResult.Failure).detail)
        }
    }

    @Test
    fun `anti bot pages are reported as challenges rather than empty results`() = runBlocking {
        val google = GoogleHtmlAdapter(stubClient("<html><body>Our systems have detected unusual traffic</body></html>"))
        assertTrue((google.search("kotlin", 10, 20_000) as SearchAdapterResult.Failure).detail.contains("anti-bot"))

        val bing = BingHtmlAdapter(stubClient("<html><body>Verify you are human</body></html>"))
        assertTrue((bing.search("kotlin", 10, 20_000) as SearchAdapterResult.Failure).detail.contains("anti-bot"))

        val baidu = BaiduHtmlAdapter(stubClient("<html><body>百度安全验证</body></html>"))
        assertTrue((baidu.search("kotlin", 10, 20_000) as SearchAdapterResult.Failure).detail.contains("anti-bot"))
    }

    // ---------------------------------------------------------------- aggregation

    @Test
    fun `duckduckgo adapter returns results for an ordinary sub megabyte page`() = runBlocking {
        val html = fixture("duckduckgo-results.html")
        assertTrue("fixture must stay far below the 1 MB ceiling", html.length < 1_000_000)

        // Regression guard: reading the body with okio's readByteArray(1_000_001) threw EOFException for
        // every page shorter than the ceiling, so this adapter reported "adapter failed" for every real
        // response and the whole search tool returned zero results.
        val result = DuckDuckGoHtmlAdapter(stubClient(html)).search("kotlin", 10, 20_000)
        assertTrue(result is SearchAdapterResult.Success)
        val items = (result as SearchAdapterResult.Success).items
        assertEquals(2, items.size)
        assertEquals("https://kotlinlang.org/docs/home.html", items[0].url)
        assertEquals("duckduckgo-html", items[0].source)
    }

    @Test
    fun `the four engine set aggregates sources and isolates a broken engine`() = runBlocking {
        val broken = object : PublicSearchAdapter {
            override val id = "broken"
            override suspend fun search(query: String, limit: Int, timeoutMs: Long) = SearchAdapterResult.Failure("boom")
        }
        val service = PublicSearchService(
            listOf(
                GoogleHtmlAdapter(stubClient(fixture("google-results.html"))),
                BingHtmlAdapter(stubClient(fixture("bing-results.html"))),
                BaiduHtmlAdapter(stubClient(fixture("baidu-real.html"))),
                broken,
            ),
        )
        val result = service.search("kotlin", SearchOptions(maxResults = 20, timeoutMs = 20_000))

        assertEquals(9, result.items.size)
        assertEquals(
            setOf("google-html", "bing-html", "baidu-html"),
            result.items.map { it.source }.toSet(),
        )
        assertEquals("broken", result.failures.single().source)
        assertTrue(result.items.all { it.url.startsWith("http") })
        assertFalse(result.items.any { it.title.isBlank() })
    }

    @Test
    fun `the aggregate interleaves engines instead of letting the first one fill the budget`() = runBlocking {
        // Regression: concatenating engines in adapter order meant the first engine with a full page took
        // the whole budget. Measured with the app's default maxResults=10 and the engines' real result
        // counts (bing 10, baidu 8) the aggregate returned bing=10 and nothing else, so adding engines
        // silently replaced the previous one rather than adding to it.
        val service = PublicSearchService(
            listOf(
                fixedEngine("engine-a", 10),
                fixedEngine("engine-b", 8),
                fixedEngine("engine-c", 5),
            ),
        )
        val items = service.search("kotlin", SearchOptions(maxResults = 10, timeoutMs = 20_000)).items

        assertEquals(10, items.size)
        val bySource = items.groupingBy { it.source }.eachCount()
        assertEquals(3, bySource.size)
        assertTrue(bySource.toString(), bySource.all { it.value >= 3 })
        // each engine's own ranking order survives the interleave
        assertEquals(listOf("engine-a-1", "engine-b-1", "engine-c-1", "engine-a-2"), items.take(4).map { it.title })
    }

    /** An adapter that always answers with [count] distinct hits. */
    private fun fixedEngine(id: String, count: Int): PublicSearchAdapter = object : PublicSearchAdapter {
        override val id = id
        override suspend fun search(query: String, limit: Int, timeoutMs: Long) =
            SearchAdapterResult.Success((1..count).map { SearchItem("$id-$it", "https://$id.example/$it", source = id) })
    }

    // ---------------------------------------------------------------- real-world guards

    @Test
    fun `google reports the real JS-only shell as the failure reason`() = runBlocking {
        val shell = fixture("google-shell.html")
        // the trimmed real response carries no result markup at all
        assertTrue(GoogleHtmlAdapter().parseResults(shell).isEmpty())

        val result = GoogleHtmlAdapter(stubClient(shell)).search("kotlin", 10, 20_000)
        assertTrue("expected a failure but got $result", result is SearchAdapterResult.Failure)
        val detail = (result as SearchAdapterResult.Failure).detail
        assertTrue(detail, detail.contains("JS-only shell"))
        assertTrue(detail, detail.contains("SG_REL"))
        // not the misleading "selectors are stale" wording, and not an anti-bot accusation
        assertFalse(detail.contains("layout not recognised"))
        assertFalse(detail.contains("anti-bot"))
    }

    @Test
    fun `an engine rejects another engine's page instead of parsing it`() = runBlocking {
        val baiduPage = fixture("baidu-real.html")
        val bingPage = fixture("bing-real.html")
        val googlePage = fixture("google-shell.html")

        // Measured on the real pages: a Baidu result page matches Google's generic heading pattern, so
        // without this guard Google returned eight www.baidu.com/link?url=... entries for a Baidu body.
        assertTrue(GoogleHtmlAdapter().parseResults(baiduPage).isNotEmpty())
        assertTrue(
            (GoogleHtmlAdapter(stubClient(baiduPage)).search("q", 10, 20_000) as SearchAdapterResult.Failure)
                .detail.contains("Baidu result page"),
        )

        assertTrue(
            (BingHtmlAdapter(stubClient(baiduPage)).search("q", 10, 20_000) as SearchAdapterResult.Failure)
                .detail.contains("Baidu result page"),
        )
        assertTrue(
            (GoogleHtmlAdapter(stubClient(bingPage)).search("q", 10, 20_000) as SearchAdapterResult.Failure)
                .detail.contains("Bing result page"),
        )
        assertTrue(
            (BaiduHtmlAdapter(stubClient(bingPage)).search("q", 10, 20_000) as SearchAdapterResult.Failure)
                .detail.contains("Bing result page"),
        )
        assertTrue(
            (BaiduHtmlAdapter(stubClient(googlePage)).search("q", 10, 20_000) as SearchAdapterResult.Failure)
                .detail.contains("Google JS-only shell"),
        )
    }

    @Test
    fun `an oversized page is rejected but a realistically large one is read`() = runBlocking {
        // Regression: the cap used to be 1 MB, while a real baidu.com/s response is 1 055 019 bytes,
        // which silently disabled that engine.
        assertEquals(4_000_000L, SEARCH_RESPONSE_BYTE_LIMIT)
        val realistic = "<html><body>" + "x".repeat(1_050_000) + fixture("baidu-real.html") + "</body></html>"
        assertTrue("fixture must exceed the old 1 MB cap", realistic.length > 1_000_000)
        val ok = BaiduHtmlAdapter(stubClient(realistic)).search("q", 10, 20_000)
        assertEquals(3, (ok as SearchAdapterResult.Success).items.size)

        val oversized = "<html><body>" + "x".repeat(4_000_001) + "</body></html>"
        assertEquals(
            "response exceeds size limit",
            (BaiduHtmlAdapter(stubClient(oversized)).search("q", 10, 20_000) as SearchAdapterResult.Failure).detail,
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/search/$name")) { "missing test fixture $name" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    /** Answers every request from [payload]; bypasses the SSRF-checking DNS because nothing is dialled. */
    private fun stubClient(payload: String, code: Int = 200): (Dns) -> OkHttpClient = {
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(if (code == 200) "OK" else "Stubbed")
                    .body(payload.toResponseBody("text/html; charset=UTF-8".toMediaType()))
                    .build()
            }
            .build()
    }
}
