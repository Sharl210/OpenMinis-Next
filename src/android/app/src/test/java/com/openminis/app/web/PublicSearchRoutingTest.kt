package com.openminis.app.web

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class PublicSearchRoutingTest {
    @Test
    fun `search limits clamp and empty adapter reports structured failure`() = runBlocking {
        val options = SearchOptions(maxResults = 500, timeoutMs = 900_000).normalized()
        assertEquals(50, options.maxResults)
        assertEquals(120_000L, options.timeoutMs)

        val result = PublicSearchService(emptyList()).search("query", options)
        assertTrue(result.items.isEmpty())
        assertEquals("service", result.failures.single().source)
        assertTrue(result.failures.single().detail.contains("not configured"))
    }

    @Test
    fun `aggregator clamps results and deduplicates urls`() = runBlocking {
        var seenLimit = 0
        var seenTimeout = 0L
        val adapter = object : PublicSearchAdapter {
            override val id = "fake"
            override suspend fun search(query: String, limit: Int, timeoutMs: Long): SearchAdapterResult {
                seenLimit = limit
                seenTimeout = timeoutMs
                return SearchAdapterResult.Success(listOf(
                    SearchItem("one", "https://example.com/a", source = id),
                    SearchItem("duplicate", "https://example.com/a", source = id),
                    SearchItem("two", "https://example.org/b", source = id),
                ))
            }
        }
        val result = PublicSearchService(listOf(adapter)).search("  query ", SearchOptions(1, 20_000))
        assertEquals("query", result.query)
        assertEquals(1, seenLimit)
        assertEquals(20_000L, seenTimeout)
        assertEquals(1, result.items.size)
        assertFalse(result.items.first().title == "duplicate")
    }

    @Test
    fun `URL policy rejects non HTTP local and private resolved targets`() {
        assertTrue(WebAccessPolicy.validateUrl("file:///etc/passwd").isFailure)
        assertTrue(WebAccessPolicy.validateUrl("http://localhost/admin").isFailure)
        assertTrue(WebAccessPolicy.validateUrl("http://127.0.0.1/admin").isSuccess)
        assertTrue(WebAccessPolicy.validateResolvedAddresses(
            "127.0.0.1", listOf(InetAddress.getByName("127.0.0.1")),
        ).isFailure)
        assertTrue(WebAccessPolicy.validateResolvedAddresses(
            "example.com", listOf(InetAddress.getByName("8.8.8.8")),
        ).isSuccess)
    }
}
