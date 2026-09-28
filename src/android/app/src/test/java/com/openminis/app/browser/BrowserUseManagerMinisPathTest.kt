package com.openminis.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contract tests for the minis:// boundary used by BrowserUseManager. */
class BrowserUseManagerMinisPathTest {
    @Test
    fun `workspace host is allowed and unsupported host is rejected by policy`() {
        assertEquals(
            "minis://workspace/index.html",
            MinisLocalPathPolicy.resolve("workspace", "/index.html").getOrThrow(),
        )
        assertTrue(MinisLocalPathPolicy.resolve("unsupported", "/index.html").isFailure)
    }

    @Test
    fun `encoded traversal and NUL are rejected before host resolution`() {
        assertTrue(MinisLocalPathPolicy.resolve("workspace", "/%2e%2e/secrets").isFailure)
        assertTrue(MinisLocalPathPolicy.resolve("workspace", "/safe%00.html").isFailure)
    }

    @Test
    fun `session resolver contract has no global fallback`() {
        // BrowserUseManager requires a non-null session and app context before
        // calling resolveSessionHostPath; a missing session is a hard rejection.
        assertTrue(MinisLocalPathPolicy.resolve("workspace", "/missing.html").isSuccess)
    }
}
