package com.openminis.app.provider

import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.feature.runtime.RuntimeChildRunner
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-android-DIAG-248] The SSE-level half of `request.md:248`, asserted
 * end-to-end on the path a delegated child actually travels.
 *
 * ## What is being pinned
 *
 * OpenRouter (and any proxy shaped like it) can report a failure **inside** the
 * event stream — `data: {"error":{"code":402,…}}` on a **200 OK** connection —
 * instead of with an HTTP status. Two things used to go wrong on that route, and
 * both together meant the parent received an abnormal-stop notice it could not
 * act on:
 *
 *  1. the branch threw a bare `mapHttpError(code, event.toString())`, so no
 *     diagnostics were collected at all;
 *  2. even once they were, the child would never have seen them: a child streams
 *     (`RuntimeChildRunner.streamChildTurn`), and every provider closes its
 *     streaming `callbackFlow` with `cancel("Stream error", mapError(e))` — so
 *     what reaches `providerFailureStopReport` is a `CancellationException`
 *     whose **cause** is the `LLMError`, and reading only the top-level throwable
 *     drops the status code, the error body and the headers.
 *
 * The status code must stay **absent** on this route, and that is deliberate:
 * the HTTP response that carried the event was a 200, so reporting
 * `response.code` would tell the parent "the failure was 200". Null is the only
 * honest answer. The error body, by contrast, IS in hand.
 *
 * Behaviour only: a real MockWebServer, the real stream parser, the real child
 * entry point and the real report builder. No source text is asserted.
 */
class OpenAISseInlineErrorDiagnosticsTest {
    private lateinit var server: MockWebServer
    private lateinit var provider: OpenAIProvider

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

    /** An inline error event, exactly as OpenRouter emits it: no `choices`. */
    private fun sseInlineError(code: Int, message: String) {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("""data: {"error":{"code":$code,"message":"$message"}}""" + "\n\n"),
        )
    }

    /** Drives one child-style streamed turn and returns what the child would throw. */
    private fun streamedFailure(): Throwable {
        val error = runCatching {
            runBlocking {
                provider.streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024).toList()
            }
        }.exceptionOrNull()
        return error ?: throw AssertionError("the inline SSE error must propagate, not be swallowed")
    }

    /** The report the direct parent is actually handed for that failure. */
    private fun reportFor(error: Throwable) = RuntimeChildRunner.providerFailureStopReport(
        childSessionId = "child",
        error = error,
        fallbackBody = "the prompt",
    )

    @Test
    fun `an SSE inline error reaches the parent with the error body and no invented status code`() {
        sseInlineError(402, "Insufficient credits")

        val report = reportFor(streamedFailure())

        assertNull(
            "the HTTP response was a 200, so no status code may be reported here",
            report.statusCode,
        )
        assertTrue(
            "no HTTP response was involved in this failure, so no headers may appear",
            report.responseHeaders.isEmpty(),
        )
        assertTrue(
            "the parent must be able to read what went wrong, got ${report.errorResponse}",
            report.errorResponse?.contains("Insufficient credits") == true,
        )
        assertEquals("OpenAI SSE inline error code=402", report.debugInfo)
        assertEquals(
            "the SSE route carries no request body diagnostic, so the child's prompt is the honest substitute",
            "the prompt",
            report.lastSentBody,
        )
    }

    @Test
    fun `the streamed failure is wrapped, and the report must still find the provider error`() {
        sseInlineError(402, "Insufficient credits")

        val error = streamedFailure()

        // Asserted as "the top-level throwable is NOT the provider error" rather
        // than as "its cause IS one". The wrapper's DEPTH depends on the
        // cancellation path — probed as exactly one layer when the flow is
        // collected directly, and deeper under the gradle unit-test runner —
        // while "the child never receives an LLMError at the top level" holds in
        // every environment, and that is precisely the fact the old
        // `(error as? LLMError)?.diagnostics` cast depended on.
        assertTrue(
            "the provider wraps stream failures; if this ever changes, revisit the report builder",
            error is kotlinx.coroutines.CancellationException,
        )
        assertFalse(
            "the child must not receive the provider error unwrapped — the old cast relied on the opposite",
            error is LLMError,
        )

        val report = reportFor(error)
        assertTrue(
            "however deeply the provider wrapped it, the error body must still reach the parent",
            report.errorResponse?.contains("Insufficient credits") == true,
        )
    }

    @Test
    fun `an SSE inline error keeps the raw event text, not just the message field`() {
        sseInlineError(402, "Insufficient credits")

        val body = reportFor(streamedFailure()).errorResponse ?: throw AssertionError("no errorResponse")

        // Asserted by containment, never by full-string equality: org.json's
        // `JSONObject` is backed by a HashMap, so re-serializing the event does
        // NOT preserve key order (probed: {"z":1,"a":2,"m":3} round-trips as
        // {"a":2,"m":3,"z":1}). An exact-match assertion here would pass or fail
        // on hash order rather than on behaviour.
        assertTrue("the error envelope must survive: $body", body.contains("\"error\""))
        assertTrue("the reported code must survive: $body", body.contains("402"))
        assertTrue("the reported message must survive: $body", body.contains("Insufficient credits"))
    }

    @Test
    fun `a different inline error code is reflected in the debug info`() {
        sseInlineError(429, "Rate limit exceeded")

        val report = reportFor(streamedFailure())

        assertNull(report.statusCode)
        assertEquals("OpenAI SSE inline error code=429", report.debugInfo)
        assertTrue(report.errorResponse?.contains("Rate limit exceeded") == true)
    }

    @Test
    fun `a deliberate stop is still never reported as a provider failure`() {
        // The guard on the cause-chain walk: a real stop has no LLMError anywhere
        // in its chain, so nothing may be invented for it. Pinned here as well as
        // in RuntimeChildRunnerStopReportTest because this file introduced the walk.
        val stop = kotlinx.coroutines.CancellationException("Stopped by user")

        val report = reportFor(stop)

        assertNull("a deliberate stop is not an HTTP failure", report.statusCode)
        assertNull(report.errorResponse)
        assertTrue(report.responseHeaders.isEmpty())
        assertEquals("Stopped by user", report.debugInfo)
        assertEquals("the prompt", report.lastSentBody)
    }
}
