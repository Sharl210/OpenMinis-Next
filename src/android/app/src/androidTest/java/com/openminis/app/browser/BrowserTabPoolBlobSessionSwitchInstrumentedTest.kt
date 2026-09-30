package com.openminis.app.browser

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BrowserTabPoolBlobSessionSwitchInstrumentedTest {
    private lateinit var context: Context
    private lateinit var pool: BrowserTabPool
    private lateinit var filesDir: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        filesDir = context.filesDir
        pool = BrowserTabPool(context)
        deleteSession("blob-switch-a")
        deleteSession("blob-switch-b")
        pool.setSession("blob-switch-a")
    }

    @After
    fun tearDown() {
        pool.blobDownloadWriter = { file, bytes -> file.writeBytes(bytes) }
        deleteSession("blob-switch-a")
        deleteSession("blob-switch-b")
    }

    @Test
    fun oldBlobJobSettlesIntoOriginalSessionAfterSwitch() = runBlocking {
        val aStarted = CountDownLatch(1)
        val releaseA = CountDownLatch(1)
        val bStarted = CountDownLatch(1)
        val releaseB = CountDownLatch(1)
        pool.blobDownloadWriter = { file, bytes ->
            // [T-android-browser-download-dir] Identify the session by the FILE
            // NAME the pool chose, not by walking up the directory tree.
            //
            // This used to test `file.parentFile?.parentFile?.name`, i.e. it
            // assumed the depth `<session>/workspace/<file>`. When downloads
            // moved one level deeper into `workspace/downloads/`, that guess
            // started matching the OTHER branch, so session A's write released
            // session B's latch and the test failed with a bare AssertionError —
            // reporting a product bug that did not exist. A depth-based guess
            // about an implementation detail is not a thing to assert on.
            if (file.name == "a.txt") {
                aStarted.countDown()
                assertTrue(releaseA.await(5, TimeUnit.SECONDS))
            } else {
                bStarted.countDown()
                assertTrue(releaseB.await(5, TimeUnit.SECONDS))
            }
            // Pin the layout itself where it is actually decided.
            assertEquals(
                "browser downloads must land in the dedicated subdirectory",
                "downloads",
                file.parentFile?.name,
            )
            file.writeBytes(bytes)
        }

        pool.saveBlobDownload("A-data".toByteArray(), "a.txt", "text/plain")
        assertTrue(aStarted.await(5, TimeUnit.SECONDS))
        pool.setSession("blob-switch-b")
        pool.saveBlobDownload("B-data".toByteArray(), "b.txt", "text/plain")
        assertTrue(bStarted.await(5, TimeUnit.SECONDS))
        assertEquals("b.txt", pool.activeDownload.value?.filename)

        releaseA.countDown()
        // [T-android-cross-session-download-settle] A's download must end
        // CANCELLED, not COMPLETED.
        //
        // `setSession` destroys every tab and cancels the previous session's
        // in-flight downloads (BrowserTabPool.kt:270-276), which is the product
        // contract: a browser session's tabs — and the downloads they started —
        // do not survive a session switch. Asserting COMPLETED here contradicted
        // that contract, so it could only ever pass by racing the cancellation;
        // it timed out instead.
        //
        // The state must also be RECORDED. `updateDownloadEntry` resolves the
        // target session through `downloadBindings[id]`, so cancelling while the
        // binding was already removed left A's persisted entry stuck at
        // DOWNLOADING forever. `setSession` now settles before dropping the
        // binding.
        awaitState(filesDir, "blob-switch-a", "a.txt", "FAILED", 0L)
        assertEquals(
            "the cancelled download must not be left claiming to be in progress",
            "FAILED",
            downloadStateOf(filesDir, "blob-switch-a", "a.txt"),
        )
        assertFalse(File(filesDir, "browser_tabs/blob-switch-b.downloads.json").readText().contains("a.txt"))
        assertEquals("b.txt", pool.activeDownload.value?.filename)

        releaseB.countDown()
        awaitState(filesDir, "blob-switch-b", "b.txt", "COMPLETED", 6L)
    }

    /** Raw recorded state for `filename` in `session`, or null when absent. */
    private fun downloadStateOf(root: File, session: String, filename: String): String? {
        val file = File(root, "browser_tabs/$session.downloads.json")
        if (!file.isFile) return null
        val rows = JSONObject(file.readText()).optJSONArray("downloads") ?: return null
        return (0 until rows.length())
            .map { rows.getJSONObject(it) }
            .firstOrNull { it.optString("filename") == filename }
            ?.optString("state")
    }

    private suspend fun awaitState(root: File, session: String, filename: String, expected: String, expectedBytes: Long) {
        withTimeout(5_000L) {
            while (true) {
                val file = File(root, "browser_tabs/$session.downloads.json")
                if (file.isFile) {
                    val rows = JSONObject(file.readText()).optJSONArray("downloads")
                    val row = rows?.let { array ->
                        (0 until array.length()).map { array.getJSONObject(it) }
                            .firstOrNull { it.optString("filename") == filename }
                    }
                    if (row?.optString("state") == expected) {
                        assertEquals(expectedBytes, row.optLong("bytesDone"))
                        return@withTimeout
                    }
                }
                delay(20L)
            }
        }
    }

    private fun deleteSession(session: String) {
        File(filesDir, "minis-sessions/$session").deleteRecursively()
        File(filesDir, "browser_tabs/$session.downloads.json").delete()
    }
}
