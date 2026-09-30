package com.openminis.app.data.repository

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-android-loadmessages-fallback-truncation] `loadMessages` pages a session's
 * rows to stay under the per-CursorWindow ceiling, and falls back to reading one
 * row at a time when a slice holds a blob too big to materialise.
 *
 * The bug these pin: through the fallback, an "empty page" does NOT mean "no more
 * rows" — it means every row in that slice was unreadable. The loader treated
 * both the same and stopped, silently dropping the entire rest of the transcript,
 * so the user's later messages vanished from the session.
 */
class ChatPagedLoadTest {

    /** Slice of `rows` at [offset, offset+limit), plus whether it fell back. */
    private fun loader(rows: List<String>, unreadable: Set<Int>): suspend (Int, Int) -> Pair<List<String>, Boolean> =
        { offset, limit ->
            val slice = (offset until minOf(offset + limit, rows.size))
            if (slice.none { it in unreadable }) {
                slice.map { rows[it] } to false
            } else {
                // Row-by-row fallback: unreadable rows are skipped, and a slice
                // made ENTIRELY of unreadable rows comes back empty.
                slice.filterNot { it in unreadable }.map { rows[it] } to true
            }
        }

    @Test
    fun `a healthy session loads every row in order`() = runBlocking {
        val rows = (0 until 10).map { "row$it" }
        val out = loadAllPages(rows.size, pageSize = 4, loadPage = loader(rows, emptySet()))
        assertEquals(rows, out)
    }

    @Test
    fun `a single unreadable row is skipped but the rest still load`() = runBlocking {
        val rows = (0 until 10).map { "row$it" }
        val out = loadAllPages(rows.size, pageSize = 4, loadPage = loader(rows, setOf(2)))
        // The rest of the transcript survives; only the oversized row is missing.
        assertEquals(rows.filterNot { it == "row2" }, out)
    }

    @Test
    fun `a fully unreadable middle page does not truncate the rest`() = runBlocking {
        // Rows 4..7 are all unreadable, so the fallback page for offset=4 is
        // EMPTY even though rows 8..9 exist. Breaking here lost rows 8..9.
        val rows = (0 until 10).map { "row$it" }
        val out = loadAllPages(rows.size, pageSize = 4, loadPage = loader(rows, setOf(4, 5, 6, 7)))
        assertEquals(listOf("row0", "row1", "row2", "row3", "row8", "row9"), out)
    }

    @Test
    fun `an unreadable final page still stops cleanly`() = runBlocking {
        val rows = (0 until 8).map { "row$it" }
        val out = loadAllPages(rows.size, pageSize = 4, loadPage = loader(rows, setOf(4, 5, 6, 7)))
        assertEquals(listOf("row0", "row1", "row2", "row3"), out)
    }

    @Test
    fun `a genuinely empty page from the normal path still stops`() = runBlocking {
        // No fallback involved: an empty slice means there are no rows left, so
        // continuing would be wrong (and pointless).
        var calls = 0
        val out = loadAllPages(total = 3, pageSize = 4) { _, _ ->
            calls++
            emptyList<String>() to false
        }
        assertEquals(emptyList<String>(), out)
        assertEquals("must not keep probing after a real empty page", 1, calls)
    }

    @Test
    fun `an empty session never calls the loader`() = runBlocking {
        var calls = 0
        val out = loadAllPages(total = 0, pageSize = 4) { _, _ ->
            calls++
            emptyList<String>() to false
        }
        assertEquals(emptyList<String>(), out)
        assertEquals(0, calls)
    }

    @Test
    fun `paging terminates even when every slice is unreadable`() = runBlocking {
        // Guards the `offset += pageSize; continue` branch against looping
        // forever: every page is empty, and `total` bounds the walk.
        val rows = (0 until 9).map { "row$it" }
        val out = loadAllPages(rows.size, pageSize = 4, loadPage = loader(rows, rows.indices.toSet()))
        assertEquals(emptyList<String>(), out)
    }
}
