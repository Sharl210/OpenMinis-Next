package com.openminis.app.browser

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserHistoryStoreTest {
    @Test
    fun `history and bookmarks normalize and survive a store restart`() {
        val directory = Files.createTempDirectory("browser-history").toFile()
        try {
            val context = TestContext(directory)
            val first = BrowserHistoryStore(context)

            val recorded = first.record("  EXAMPLE.com/  ", "Example")
            assertTrue("a write that reached disk reports itself as persisted", recorded.persisted)
            val added = first.addBookmark("https://Example.com/docs/", "Docs")
            assertTrue(added.persisted)
            val bookmark = added.value
            assertNotNull(bookmark)
            assertEquals("https://example.com/docs", bookmark?.url)
            assertTrue(first.isBookmarked(" HTTPS://EXAMPLE.COM/docs/ "))

            val restored = BrowserHistoryStore(context)
            assertEquals("https://example.com", restored.getEntries().single().url)
            assertEquals("https://example.com/docs", restored.getBookmarks().single().url)
            assertEquals(bookmark?.id, restored.findBookmark("https://example.com/docs/")?.id)
            assertFalse(restored.isBookmarked("https://example.com/other"))
            val deleted = restored.deleteHistoryForUrl(" HTTPS://EXAMPLE.COM/ ")
            assertTrue(deleted.value)
            assertTrue(deleted.persisted)
            assertTrue(restored.getEntries().isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `invalid primary file falls back to a valid backup`() {
        val directory = Files.createTempDirectory("browser-history-recovery").toFile()
        try {
            val root = JSONObject().apply {
                put("history", JSONArray().put(JSONObject().apply {
                    put("id", "h1")
                    put("url", "EXAMPLE.com/recovered/")
                    put("title", "Recovered")
                    put("timestamp", System.currentTimeMillis())
                }))
                put("bookmarks", JSONArray().put(JSONObject().apply {
                    put("id", "b1")
                    put("url", "https://example.com/saved/")
                    put("title", "Saved")
                }))
            }
            File(directory, "browser_history.json").writeText("{broken", Charsets.UTF_8)
            File(directory, "browser_history.json.bak").writeText(root.toString(), Charsets.UTF_8)

            val restored = BrowserHistoryStore(TestContext(directory))
            assertEquals(
                "recovering from the backup is reported, not hidden",
                BrowserHistoryStore.LoadOutcome.RECOVERED,
                restored.loadStatus.outcome,
            )
            assertEquals("browser_history.json.bak", restored.loadStatus.source)
            assertEquals("https://example.com/recovered", restored.getEntries().single().url)
            assertEquals("https://example.com/saved", restored.getBookmarks().single().url)
            assertNull(restored.findBookmark("missing-bookmark"))
            assertTrue(File(directory, "browser_history.json").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    private class TestContext(private val directory: File) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = directory
    }
}
