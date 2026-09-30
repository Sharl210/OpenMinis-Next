package com.openminis.app.feature.runtime

import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMRequestDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two stop-report factories `RuntimeChildRunner` uses for a delegated child,
 * pinned as behaviour rather than as source text.
 *
 * ## Why they are separate functions
 *
 * Both call sites live inside `RuntimeChildRunner.execute`, which needs a live
 * provider stack, a `ChatRepository` and an Android `Context` — unreachable from a
 * plain JVM test. Extracting the field mapping into the companion object (the same
 * seam `runChildAttempt` and `resolveChildSessionId` already use for exactly this
 * reason) is what makes `request.md:248`'s payload contract assertable at all.
 *
 * ## What is actually being protected here
 *
 *  1. `lastSentBody` must carry the text the CHILD'S MODEL produced — 「模型最后
 *     一次发送的正文」 — not the prompt the parent handed it. `lastSentBodyTail`
 *     then caps it at 200 characters.
 *  2. The abnormal path must report the status code, the error response and the
 *     response headers the failing provider round-trip collected.
 *  3. A user pressing stop (a `CancellationException`, which is not an
 *     `LLMError`) must NOT be dressed up as a provider error: no status code, no
 *     error body, no headers.
 */
class RuntimeChildRunnerStopReportTest {

    // --------------------------------------------------- undeclaredEndStopReport

    @Test
    fun `the abnormal end carries the model's own last text, not the parent's prompt`() {
        val report = RuntimeChildRunner.undeclaredEndStopReport(
            childSessionId = "child",
            output = "I could not finish because the log file is missing.",
            endReason = ChildEndReason.NO_TOOL_CALL,
        )

        assertEquals("child", report.nodeId)
        assertEquals(
            "request.md:248 asks for the text this model last produced: " +
                "${report.lastSentBody}",
            "I could not finish because the log file is missing.",
            report.lastSentBody,
        )
        assertFalse("an undeclared end is never a normal completion", report.completedNormally)
        assertTrue(
            "the reason a parent can act on must name the missing completion tool: ${report.debugInfo}",
            report.debugInfo!!.contains(ChildCompletionProtocol.TOOL_NAME),
        )
        assertTrue(
            "and the end reason: ${report.debugInfo}",
            report.debugInfo!!.contains(ChildEndReason.NO_TOOL_CALL.name),
        )
    }

    @Test
    fun `the model's text is reported as its final 200 characters`() {
        val long = ("0123456789".repeat(40)) // 400 chars
        val report = RuntimeChildRunner.undeclaredEndStopReport(
            childSessionId = "child",
            output = long,
            endReason = ChildEndReason.TURN_BUDGET_EXHAUSTED,
        )

        assertEquals(
            "「最后200个字」 is a 200-character tail, not the whole body",
            long.takeLast(RuntimeStopReport.MAX_TAIL_CHARS),
            report.lastSentBodyTail,
        )
        assertEquals(200, report.lastSentBodyTail!!.length)
    }

    @Test
    fun `an abnormal end reports no HTTP diagnostics of its own`() {
        // The child's model round-tripped fine; only its completion was missing, so
        // inventing a status code or an error body here would be fabrication.
        val report = RuntimeChildRunner.undeclaredEndStopReport(
            childSessionId = "child",
            output = "partial work",
            endReason = ChildEndReason.NO_TOOL_CALL,
        )

        assertNull(report.statusCode)
        assertNull(report.errorResponse)
        assertTrue(report.responseHeaders.isEmpty())
    }

    // -------------------------------------------------- providerFailureStopReport

    @Test
    fun `a provider failure reports every diagnostic the failing round-trip collected`() {
        val error = LLMError.ProviderError(
            "upstream",
            LLMRequestDiagnostics(
                statusCode = 503,
                responseHeaders = mapOf("x-request-id" to "r1", "retry-after" to "30"),
                errorResponse = "upstream unavailable",
                requestBody = "the-request-body",
                debugInfo = "OpenAI HTTP response",
            ),
        )

        val report = RuntimeChildRunner.providerFailureStopReport(
            childSessionId = "child",
            error = error,
            fallbackBody = "fallback prompt",
        )

        assertEquals("child", report.nodeId)
        assertEquals(503, report.statusCode)
        assertEquals("upstream unavailable", report.errorResponse)
        assertEquals("r1", report.responseHeaders["x-request-id"])
        assertEquals("30", report.responseHeaders["retry-after"])
        assertEquals("OpenAI HTTP response", report.debugInfo)
        assertEquals("the-request-body", report.lastSentBody)
        assertFalse(report.completedNormally)
    }

    @Test
    fun `a network failure with no HTTP response stays honest instead of inventing one`() {
        val error = LLMError.NetworkError(
            java.io.IOException("offline"),
            LLMRequestDiagnostics(debugInfo = "offline"),
        )

        val report = RuntimeChildRunner.providerFailureStopReport(
            childSessionId = "child",
            error = error,
            fallbackBody = "fallback prompt",
        )

        assertNull("no response arrived, so no status code may appear", report.statusCode)
        assertNull(report.errorResponse)
        assertTrue(report.responseHeaders.isEmpty())
        assertEquals("offline", report.debugInfo)
        assertEquals(
            "with no request body either, the child's prompt is the last honest substitute",
            "fallback prompt",
            report.lastSentBody,
        )
    }

    @Test
    fun `a user pressing stop is never reported as a provider error`() {
        // The cancel path reaches the same onFailure handler (runChildAttempt sends
        // CancellationException there so cleanup still runs). A raw
        // CancellationException is not an LLMError, so every diagnostic must stay
        // absent: the runtime reports "the child stopped", not "the provider broke".
        val cancellation = kotlinx.coroutines.CancellationException("Stopped by user")

        val report = RuntimeChildRunner.providerFailureStopReport(
            childSessionId = "child",
            error = cancellation,
            fallbackBody = "the prompt",
        )

        assertNull("a deliberate stop is not an HTTP failure", report.statusCode)
        assertNull(report.errorResponse)
        assertTrue("and it collected no response headers", report.responseHeaders.isEmpty())
        assertEquals("Stopped by user", report.debugInfo)
        assertFalse("a stop is still not a normal completion", report.completedNormally)
    }

    @Test
    fun `a non-LLM throwable still produces a usable report`() {
        val report = RuntimeChildRunner.providerFailureStopReport(
            childSessionId = "child",
            error = IllegalStateException("boom"),
            fallbackBody = "the prompt",
        )

        assertNull(report.statusCode)
        assertEquals("boom", report.debugInfo)
        assertEquals("the prompt", report.lastSentBody)
        assertFalse(report.completedNormally)
    }
}
