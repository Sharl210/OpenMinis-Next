package com.openminis.app.browser

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the failure-visibility contract of [BrowserHistoryStore].
 *
 * The defect these tests exist for: `save()` swallowed every write failure into
 * a log line while the mutation had already been applied in memory, so a tapped
 * bookmark star turned yellow, the UI (and the agent tool that answered
 * "Bookmarked …") reported success, and the bookmark was simply gone at the next
 * launch — with nobody ever being told.
 *
 * Two properties are asserted throughout:
 *
 * 1. a mutation that could not be written reports `persisted = false` and a
 *    non-null reason, and
 * 2. memory and disk never disagree: after such a call the store is exactly the
 *    state a restart would load (the change is rolled back, not kept as a
 *    phantom "pending" state).
 *
 * Write failure is injected portably by occupying the temp-file path with a
 * directory: `save()` cannot open it for writing on any platform, which is the
 * same code path a full disk, a read-only `filesDir`, or a revoked permission
 * takes.
 */
class BrowserHistoryPersistenceFailureTest {

    @Test
    fun `a bookmark that cannot be written is reported, not silently kept in memory`() {
        val directory = Files.createTempDirectory("browser-history-write-fail").toFile()
        try {
            val store = BrowserHistoryStore(TestContext(directory))
            val first = store.addBookmark("https://example.com/a", "A")
            assertTrue("the control write must succeed", first.persisted)
            assertNotNull(first.value)

            blockWrites(directory)

            val failed = store.addBookmark("https://example.com/b", "B")
            assertFalse("a failed write must not be reported as saved", failed.persisted)
            assertNotNull("the caller needs a reason to show/report", failed.error)
            assertNull(failed.value)

            // Memory must agree with disk: the bookmark never happened.
            assertFalse(store.isBookmarked("https://example.com/b"))
            assertEquals(listOf("https://example.com/a"), store.getBookmarks().map { it.url })

            unblockWrites(directory)
            val restored = BrowserHistoryStore(TestContext(directory))
            assertEquals(
                "a restart must show the same thing the caller was told",
                listOf("https://example.com/a"),
                restored.getBookmarks().map { it.url },
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `a toggle that cannot be written keeps the previous state and does not claim success`() {
        val directory = Files.createTempDirectory("browser-history-toggle-fail").toFile()
        try {
            val store = BrowserHistoryStore(TestContext(directory))
            assertTrue(store.toggleBookmark("https://example.com/a", "A").persisted)
            assertTrue(store.isBookmarked("https://example.com/a"))

            blockWrites(directory)

            // Un-bookmarking fails: the bookmark must still be there afterwards.
            val off = store.toggleBookmark("https://example.com/a", "A")
            assertFalse(off.persisted)
            assertTrue("rolled back: the bookmark is still present", off.value)
            assertTrue(store.isBookmarked("https://example.com/a"))
            assertEquals(1, store.getBookmarks().size)

            unblockWrites(directory)
            val restored = BrowserHistoryStore(TestContext(directory))
            assertTrue(restored.isBookmarked("https://example.com/a"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `a history visit that cannot be written is rolled back and reported`() {
        val directory = Files.createTempDirectory("browser-history-record-fail").toFile()
        try {
            val store = BrowserHistoryStore(TestContext(directory))
            blockWrites(directory)

            val outcome = store.record("https://example.com/visited", "Visited")
            assertFalse(outcome.persisted)
            assertFalse(outcome.value)
            assertNotNull(outcome.error)
            assertTrue(
                "a visit that never reached disk must not sit in memory claiming it did",
                store.getEntries().isEmpty(),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `deletes and clears report a failed write and leave the data in place`() {
        val directory = Files.createTempDirectory("browser-history-delete-fail").toFile()
        try {
            val store = BrowserHistoryStore(TestContext(directory))
            store.record("https://example.com/h1", "H1")
            val entryId = store.getEntries().single().id
            store.record("https://example.com/h2", "H2")
            store.addBookmark("https://example.com/b1", "B1")
            val bookmarkId = store.getBookmarks().single().id

            blockWrites(directory)

            val deletedHistory = store.deleteHistory(entryId)
            assertFalse(deletedHistory.persisted)
            assertFalse(deletedHistory.value)
            assertTrue(store.getEntries().any { it.id == entryId })

            val removedBookmark = store.removeBookmark(bookmarkId)
            assertFalse(removedBookmark.persisted)
            assertFalse(removedBookmark.value)
            assertTrue(store.isBookmarked("https://example.com/b1"))

            val removedByUrl = store.removeBookmarkForUrl("https://example.com/b1")
            assertFalse(removedByUrl.persisted)
            assertTrue(store.isBookmarked("https://example.com/b1"))

            val updated = store.updateBookmark(bookmarkId, title = "Renamed")
            assertFalse(updated.persisted)
            assertNotNull(updated.error)
            assertEquals("rolled back: the old title is still in effect", "B1", updated.value?.title)
            assertEquals("B1", store.findBookmark(bookmarkId)?.title)

            val clearedBookmarks = store.clearBookmarks()
            assertFalse(clearedBookmarks.persisted)
            assertEquals(0, clearedBookmarks.value)
            assertEquals(1, store.getBookmarks().size)

            val clearedHistory = store.clear()
            assertFalse(clearedHistory.persisted)
            assertEquals(0, clearedHistory.value)
            assertEquals(2, store.getEntries().size)

            unblockWrites(directory)
            val restored = BrowserHistoryStore(TestContext(directory))
            assertEquals(2, restored.getEntries().size)
            assertEquals(1, restored.getBookmarks().size)
            assertEquals("B1", restored.findBookmark(bookmarkId)?.title)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `an unreadable history file is a read failure, not an empty library`() {
        val directory = Files.createTempDirectory("browser-history-load-fail").toFile()
        try {
            val file = File(directory, "browser_history.json")
            file.writeText("{not json at all", Charsets.UTF_8)

            val store = BrowserHistoryStore(TestContext(directory))
            assertTrue("the caller must be able to tell a read failure from an empty list", store.loadStatus.failed)
            assertEquals(BrowserHistoryStore.LoadOutcome.FAILED, store.loadStatus.outcome)
            assertNotNull(store.loadStatus.error)
            assertEquals("browser_history.json.corrupt", store.loadStatus.preservedCopy)

            // The unreadable bytes are kept for salvage instead of being left in
            // place for the next save to overwrite.
            assertFalse(File(directory, "browser_history.json").exists())
            val preserved = File(directory, "browser_history.json.corrupt")
            assertTrue(preserved.exists())
            assertEquals("{not json at all", preserved.readText(Charsets.UTF_8))

            // …and a later successful save must not destroy them either.
            assertTrue(store.addBookmark("https://example.com/x", "X").persisted)
            assertEquals("{not json at all", preserved.readText(Charsets.UTF_8))

            val restored = BrowserHistoryStore(TestContext(directory))
            assertEquals(listOf("https://example.com/x"), restored.getBookmarks().map { it.url })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `a store that has never been written is empty rather than failed`() {
        val directory = Files.createTempDirectory("browser-history-fresh").toFile()
        try {
            val store = BrowserHistoryStore(TestContext(directory))
            assertFalse(store.loadStatus.failed)
            assertEquals(BrowserHistoryStore.LoadOutcome.EMPTY, store.loadStatus.outcome)
            assertNull(store.loadStatus.error)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `successful mutations still survive a restart`() {
        val directory = Files.createTempDirectory("browser-history-round-trip").toFile()
        try {
            val store = BrowserHistoryStore(TestContext(directory))
            val added = store.addBookmark("https://example.com/a", "A")
            assertTrue(added.persisted)
            assertTrue(store.toggleBookmark("https://example.com/b", "B").persisted)
            val toggledOff = store.toggleBookmark("https://example.com/b", "B")
            assertTrue("toggling off succeeded", toggledOff.persisted)
            assertFalse("toggling off removes it", toggledOff.value)
            store.record("https://example.com/one", "One")
            store.record("https://example.com/two", "Two")

            val restored = BrowserHistoryStore(TestContext(directory))
            assertEquals(listOf("https://example.com/a"), restored.getBookmarks().map { it.url })
            assertEquals(2, restored.getEntries().size)

            assertTrue(restored.deleteHistoryForUrl("https://example.com/one").persisted)
            assertTrue(restored.removeBookmark(added.value!!.id).persisted)

            val final = BrowserHistoryStore(TestContext(directory))
            assertEquals(1, final.getEntries().size)
            assertEquals("https://example.com/two", final.getEntries().single().url)
            assertTrue(final.getBookmarks().isEmpty())

            assertTrue(final.clear().persisted)
            assertEquals(0, BrowserHistoryStore(TestContext(directory)).getEntries().size)
        } finally {
            directory.deleteRecursively()
        }
    }

    /** Make every write fail: the temp path `save()` opens is now a directory. */
    private fun blockWrites(directory: File) {
        val tmp = File(directory, "browser_history.json.tmp")
        tmp.delete()
        assertTrue("could not occupy the temp path", tmp.mkdirs())
    }

    private fun unblockWrites(directory: File) {
        File(directory, "browser_history.json.tmp").delete()
    }

    private class TestContext(private val directory: File) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = directory
    }
}
