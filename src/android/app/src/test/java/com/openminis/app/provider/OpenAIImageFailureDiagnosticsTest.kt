package com.openminis.app.provider

import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.feature.runtime.RuntimeChildRunner
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-android-DIAG-248] `request.md:248` requires an abnormally stopped child to
 * report 「停止运行的对应的那个请求的状态码以及错误响应以及响应头」 to its direct
 * parent. The chain that carries them is
 * `LLMRequestDiagnostics` → `LLMError.diagnostics` →
 * `RuntimeChildRunner.providerFailureStopReport` → `RuntimeStopReport`, and the
 * chat + Anthropic + Gemini routes all fill the first hop in.
 *
 * The two OpenAI image routes did not: they threw a bare
 * `mapHttpError(statusCode, responseBody)`, so an image-generation failure
 * reached the parent with `statusCode == null`, no error body and no headers.
 * Nothing in the suite covered it — `OpenAIProviderTest` only pins the chat
 * route, `OpenAIEditImageTest` only asserts that *some* error propagates.
 *
 * These assertions are behaviour on the real wire (MockWebServer) and on the
 * real report builder, not source text, so they fail again if the diagnostics
 * are ever dropped from either image route.
 */
class OpenAIImageFailureDiagnosticsTest {
    private lateinit var server: MockWebServer
    private lateinit var provider: OpenAIProvider

    private val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        provider = OpenAIProvider(
            apiKey = "test-key",
            model = LLMModel.gpt4oMini,
            basePath = server.url("/").toString().trimEnd('/'),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /**
     * 418 is deliberate: 400 would be swallowed by the `b64_json` auto-probe on
     * both image routes, and 401/403/429 map to error types whose assertions
     * belong to the chat test. 418 lands on the generic `ProviderError`.
     */
    private fun enqueueFailure() {
        server.enqueue(
            MockResponse()
                .setResponseCode(418)
                .addHeader("x-diag", "openai-images")
                .addHeader("retry-after", "30")
                .setBody("""{"error":{"message":"images teapot"}}"""),
        )
    }

    private fun providerErrorFrom(block: suspend () -> Unit): LLMError.ProviderError {
        val error = runCatching { runBlocking { block() } }.exceptionOrNull()
        assertTrue("expected a ProviderError, got $error", error is LLMError.ProviderError)
        return error as LLMError.ProviderError
    }

    // ── images/generations ───────────────────────────────────────────────────

    @Test
    fun `generateImage HTTP failure carries status code, error body and headers`() {
        enqueueFailure()

        val error = providerErrorFrom { provider.generateImage("a lighthouse at dusk") }

        val d = error.diagnostics ?: throw AssertionError(
            "images/generations dropped its diagnostics: an abnormal-stop report " +
                "from this route would carry no status code and no error body",
        )
        assertEquals(418, d.statusCode)
        assertEquals("""{"error":{"message":"images teapot"}}""", d.errorResponse)
        assertEquals("openai-images", d.responseHeaders["x-diag"])
        assertEquals("30", d.responseHeaders["retry-after"])
        assertTrue(
            "the request body was in hand on this route, so it must be reported",
            d.requestBody?.contains("a lighthouse at dusk") == true,
        )
    }

    // ── images/edits ─────────────────────────────────────────────────────────

    @Test
    fun `editImage HTTP failure carries status code, error body and headers`() {
        enqueueFailure()

        val error = providerErrorFrom {
            provider.editImage("repaint it", listOf(LLMMessage.ImagePart(pngBytes, "image/png")))
        }

        val d = error.diagnostics ?: throw AssertionError(
            "images/edits dropped its diagnostics: an abnormal-stop report " +
                "from this route would carry no status code and no error body",
        )
        assertEquals(418, d.statusCode)
        assertEquals("""{"error":{"message":"images teapot"}}""", d.errorResponse)
        assertEquals("openai-images", d.responseHeaders["x-diag"])
        assertEquals("30", d.responseHeaders["retry-after"])
        // Multipart is never materialised as a string on this route, so an
        // absent request body is the honest answer — not an invented one.
        assertNull(d.requestBody)
    }

    // ── the two routes must stay tellable apart in a triage log ───────────────

    @Test
    fun `the two image routes report distinguishable debug info`() {
        val generation = run {
            enqueueFailure()
            providerErrorFrom { provider.generateImage("p") }.diagnostics
        }
        val edit = run {
            enqueueFailure()
            providerErrorFrom {
                provider.editImage("p", listOf(LLMMessage.ImagePart(pngBytes, "image/png")))
            }.diagnostics
        }

        val genInfo = generation?.debugInfo ?: throw AssertionError("generations: no debugInfo")
        val editInfo = edit?.debugInfo ?: throw AssertionError("edits: no debugInfo")
        assertEquals("OpenAI images/generations HTTP 418", genInfo)
        assertEquals("OpenAI images/edits HTTP 418", editInfo)
        assertTrue(
            "a triage log must make it obvious which image route failed",
            genInfo != editInfo,
        )
    }

    // ── end to end: what the direct parent actually receives ──────────────────

    @Test
    fun `a failed image generation reaches the parent with every diagnostic`() {
        enqueueFailure()
        val error = providerErrorFrom { provider.generateImage("a lighthouse at dusk") }

        val report = RuntimeChildRunner.providerFailureStopReport(
            childSessionId = "child",
            error = error,
            fallbackBody = "fallback prompt",
        )

        assertEquals(418, report.statusCode)
        assertEquals("""{"error":{"message":"images teapot"}}""", report.errorResponse)
        assertEquals("openai-images", report.responseHeaders["x-diag"])
        assertEquals("30", report.responseHeaders["retry-after"])
        assertTrue(
            "the JSON body we actually sent is what the parent should see, not the bare prompt",
            report.lastSentBody?.contains("a lighthouse at dusk") == true,
        )
        assertTrue(report.debugInfo?.contains("images/generations") == true)
    }

    @Test
    fun `a failed image edit reaches the parent with every diagnostic`() {
        enqueueFailure()
        val error = providerErrorFrom {
            provider.editImage("repaint it", listOf(LLMMessage.ImagePart(pngBytes, "image/png")))
        }

        val report = RuntimeChildRunner.providerFailureStopReport(
            childSessionId = "child",
            error = error,
            fallbackBody = "fallback prompt",
        )

        assertEquals(418, report.statusCode)
        assertEquals("""{"error":{"message":"images teapot"}}""", report.errorResponse)
        assertEquals("openai-images", report.responseHeaders["x-diag"])
        assertEquals(
            "no multipart string was ever materialised, so the child's prompt is the honest substitute",
            "fallback prompt",
            report.lastSentBody,
        )
        assertTrue(report.debugInfo?.contains("images/edits") == true)
    }
}
