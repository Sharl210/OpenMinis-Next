package com.openminis.app.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserUrlNormalizerTest {
    @Test
    fun `normalizes scheme host whitespace and root slash without dropping query`() {
        assertEquals("https://example.com", BrowserUrlNormalizer.normalize("  EXAMPLE.COM/  "))
        assertEquals(
            "https://example.com/path?a=1#section",
            BrowserUrlNormalizer.normalize(" HTTPS://EXAMPLE.COM/path?a=1#section "),
        )
        assertEquals("https://example.com/path", BrowserUrlNormalizer.normalize("//EXAMPLE.COM/path/"))
    }

    @Test
    fun `keeps distinct paths queries and non-http schemes`() {
        assertEquals("https://example.com/a", BrowserUrlNormalizer.normalize("example.com/a"))
        assertEquals("https://example.com/a?x=1", BrowserUrlNormalizer.normalize("example.com/a?x=1"))
        assertEquals("about:blank", BrowserUrlNormalizer.normalize(" about:blank "))
    }
}
