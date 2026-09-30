package com.openminis.app.browser

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * [T-android-browser-history-contract] Locks the browser history store's
 * **app-global** design, and covers the behaviours it never had tests for.
 *
 * WHY "APP-GLOBAL" IS THE RIGHT CONTRACT TO LOCK, and why this is not a bug fix:
 * the store keeps one `filesDir/browser_history.json` with no session id, so
 * conversation A can see what conversation B browsed. That was recorded as a
 * possible design defect. Reading the requirement settles it the other way:
 * `request.md:95` asks only that history and bookmarks exist and that the AI has
 * tools to read and open them — a search for 隔离 / 每个会话 / 按会话 across the whole
 * requirement file returns **zero** hits. So global sharing is not prohibited, and
 * the honest response is to pin the actual behaviour instead of "fixing" it into a
 * data-model migration nobody asked for. Without a test, the next person to notice
 * this will read it as a bug, and the outcome will be decided by whoever looks last.
 *
 * This is an instrumented test because the JVM cannot build the store at all: it
 * takes a `Context` and is constructed through `getInstance`. That is also why the
 * store had no coverage before.
 *
 * The test writes to the app's REAL history file, so the original bytes are saved
 * and restored — a test that silently wipes a developer's browsing history is its
 * own defect.
 */
@RunWith(AndroidJUnit4::class)
class BrowserHistoryStoreInstrumentedTest {

    private lateinit var context: Context
    private lateinit var file: File
    private var original: ByteArray? = null

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        file = File(context.filesDir, FILENAME)
        original = if (file.exists()) file.readBytes() else null
        // Temp/backup siblings are part of the store's crash-recovery protocol;
        // a leftover from a previous run would change which file `load()` picks.
        File(context.filesDir, "$FILENAME.tmp").delete()
        File(context.filesDir, "$FILENAME.bak").delete()
        file.delete()
    }

    @After
    fun tearDown() {
        // Restore exactly what was there, so the developer's history survives.
        file.delete()
        File(context.filesDir, "$FILENAME.tmp").delete()
        File(context.filesDir, "$FILENAME.bak").delete()
        original?.let { file.writeBytes(it) }
    }

    private fun freshStore(): BrowserHistoryStore = BrowserHistoryStore(context)

    private fun writeRaw(contents: String) {
        file.writeText(contents, Charsets.UTF_8)
    }

    // ---- the global contract ----------------------------------------------------

    @Test
    fun historyIsStoredInOneAppLevelFileWithNoSessionScope() {
        // The contract, stated three ways so a future "add session scoping" change
        // has to fail loudly rather than quietly.
        freshStore().record("https://example.com/a", "A")

        assertEquals(
            "history lives at filesDir/browser_history.json, not under a session dir",
            File(context.filesDir, FILENAME).absolutePath,
            file.absolutePath,
        )
        assertFalse("the path must not carry a session component", file.absolutePath.contains("session"))
        assertTrue("the file must exist after a record", file.exists())
    }

    @Test
    fun aSecondCallerSeesTheFirstCallersHistory() {
        // This is the behaviour the audit flagged. Pinned deliberately: it IS the
        // design (requirement asks for nothing else), not an accident.
        freshStore().record("https://example.com/shared", "Shared")

        val otherCaller = freshStore()
        assertEquals(
            "history is shared across callers by design",
            listOf("https://example.com/shared"),
            otherCaller.getEntries().map { it.url },
        )
    }

    @Test
    fun anEntryCarriesNoSessionIdentity() {
        // A field-level guard: if a sessionId were ever added to Entry to "fix" the
        // sharing, this test is where that decision becomes visible.
        val entry = freshStore().apply { record("https://example.com/x", "X") }.getEntries().single()
        val fields = entry.javaClass.declaredFields.map { it.name }.toSet()
        assertFalse(
            "Entry must not grow a session field without revisiting the contract; fields=$fields",
            fields.any { it.contains("session", ignoreCase = true) },
        )
    }

    // ---- behaviours that had no coverage ---------------------------------------

    @Test
    fun consecutiveVisitsToTheSameCanonicalUrlAreCollapsed() {
        val store = freshStore()
        store.record("https://example.com/page", "P")
        store.record("https://example.com/page", "P again")
        assertEquals("a repeated visit must not append a duplicate", 1, store.getEntries().size)

        // The dedupe is CONSECUTIVE-only, which is the part that is easy to get wrong
        // by reading "deduplicate" in the source comment too broadly.
        store.record("https://example.com/other", "O")
        store.record("https://example.com/page", "P back")
        assertEquals(3, store.getEntries().size)
    }

    @Test
    fun aBlankUrlIsIgnoredRatherThanStored() {
        val store = freshStore()
        store.record("", "nothing")
        assertEquals(0, store.getEntries().size)
    }

    @Test
    fun entriesOlderThanSevenDaysArePrunedOnLoad() {
        val now = System.currentTimeMillis()
        val day = 24 * 60 * 60 * 1000L
        writeRaw(
            JSONObject().apply {
                put(
                    "history",
                    JSONArray().apply {
                        put(historyRow("old", "https://old.example", now - 9 * day))
                        put(historyRow("fresh", "https://fresh.example", now - day))
                    },
                )
                put("bookmarks", JSONArray())
            }.toString(),
        )

        val urls = freshStore().getEntries().map { it.url }
        assertEquals("only the in-window entry survives a load", listOf("https://fresh.example"), urls)
    }

    @Test
    fun theLegacyBareArrayFileIsStillReadable() {
        // Older releases wrote a top-level JSON array; the upgrade path must not
        // discard browsing history. This is the kind of compatibility branch that
        // silently rots without a fixture.
        val now = System.currentTimeMillis()
        writeRaw(JSONArray().apply { put(historyRow("legacy", "https://legacy.example", now)) }.toString())

        assertEquals(
            listOf("https://legacy.example"),
            freshStore().getEntries().map { it.url },
        )
    }

    @Test
    fun clearingHistoryKeepsBookmarks() {
        // The ledger listed "bookmark vs history isolation" as untested; clearing one
        // must not take the other with it.
        val store = freshStore()
        store.record("https://example.com/keep-me", "Keep")
        store.addBookmark("https://example.com/bookmark", "BM")

        store.clear()

        assertEquals("history is cleared", 0, store.getEntries().size)
        assertEquals("bookmarks survive a history clear", 1, store.getBookmarks().size)
        assertEquals("https://example.com/bookmark", store.getBookmarks().single().url)
    }

    @Test
    fun clearingBookmarksKeepsHistory() {
        // The mirror, so a shared code path between the two clears is caught.
        val store = freshStore()
        store.record("https://example.com/hist", "H")
        store.addBookmark("https://example.com/bm", "BM")

        store.clearBookmarks()

        assertEquals(0, store.getBookmarks().size)
        assertEquals(1, store.getEntries().size)
    }

    @Test
    fun aCorruptFileDoesNotTakeBookmarksDownWithIt() {
        // `loadFromFile` returns false and `load()` then returns early — so a corrupt
        // file must leave the store empty-but-usable rather than throwing on first use.
        writeRaw("{ this is not json")
        val store = freshStore()
        assertEquals(0, store.getEntries().size)
        // And it must still be usable afterwards.
        store.record("https://example.com/after", "After")
        assertEquals(1, store.getEntries().size)
    }

    private fun historyRow(id: String, url: String, timestamp: Long) = JSONObject().apply {
        put("id", id)
        put("url", url)
        put("title", id)
        put("timestamp", timestamp)
        put("domain", "example")
    }

    private companion object {
        /** Mirrors `BrowserHistoryStore`'s private FILENAME. */
        const val FILENAME = "browser_history.json"
    }
}
