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
            if (file.parentFile?.parentFile?.name == "blob-switch-a") {
                aStarted.countDown()
                assertTrue(releaseA.await(5, TimeUnit.SECONDS))
            } else {
                bStarted.countDown()
                assertTrue(releaseB.await(5, TimeUnit.SECONDS))
            }
            file.writeBytes(bytes)
        }

        pool.saveBlobDownload("A-data".toByteArray(), "a.txt", "text/plain")
        assertTrue(aStarted.await(5, TimeUnit.SECONDS))
        pool.setSession("blob-switch-b")
        pool.saveBlobDownload("B-data".toByteArray(), "b.txt", "text/plain")
        assertTrue(bStarted.await(5, TimeUnit.SECONDS))
        assertEquals("b.txt", pool.activeDownload.value?.filename)

        releaseA.countDown()
        awaitState(filesDir, "blob-switch-a", "a.txt", "COMPLETED", 6L)
        assertFalse(File(filesDir, "browser_tabs/blob-switch-b.downloads.json").readText().contains("a.txt"))
        assertEquals("b.txt", pool.activeDownload.value?.filename)

        releaseB.countDown()
        awaitState(filesDir, "blob-switch-b", "b.txt", "COMPLETED", 6L)
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
