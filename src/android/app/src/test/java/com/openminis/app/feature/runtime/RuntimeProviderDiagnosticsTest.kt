package com.openminis.app.feature.runtime

import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMRequestDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeProviderDiagnosticsTest {
    @Test
    fun `diagnostics survive provider error and map to stop report`() {
        val error = LLMError.ProviderError(
            "upstream",
            LLMRequestDiagnostics(
                statusCode = 503,
                responseHeaders = mapOf("x-request-id" to "r1"),
                errorResponse = "busy",
                requestBody = "x".repeat(250),
                debugInfo = "http failure",
            ),
        )
        val d = error.diagnostics!!
        val report = RuntimeStopReport(
            nodeId = "child",
            statusCode = d.statusCode,
            responseHeaders = d.responseHeaders,
            errorResponse = d.errorResponse,
            debugInfo = d.debugInfo,
            lastSentBody = d.requestBody,
        )
        assertEquals(503, report.statusCode)
        assertEquals("busy", report.errorResponse)
        assertEquals("r1", report.responseHeaders["x-request-id"])
        assertEquals(200, report.lastSentBodyTail!!.length)
        assertEquals("http failure", report.debugInfo)
    }

    @Test
    fun `network failure without response keeps status absent`() {
        val error = LLMError.NetworkError(java.io.IOException("offline"), LLMRequestDiagnostics(debugInfo = "offline"))
        assertNull(error.diagnostics!!.statusCode)
        assertEquals("offline", error.diagnostics!!.debugInfo)
    }
}
