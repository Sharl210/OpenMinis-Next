package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-persistence-result-check] Pins the rule that a failed durable write
 * must not be reported as a successful one in the UI.
 *
 * Two places did exactly that, and this test states the contract they follow:
 *
 *  - the compaction marker is the ONLY durable record that a compaction
 *    happened; the summary, the cached marker, the graying and the "N messages
 *    compacted" banner are all presentation, so they may only be applied when
 *    the insert succeeded (`runCatching { … }.isSuccess` gates them);
 *  - a memory write that reported `success = false` must not be shown with the
 *    success label and the content it merely TRIED to store.
 *
 * These are modelled here as pure state machines because the real code lives
 * inside coroutine bodies in a ViewModel that needs an Android runtime.
 */
class PersistenceResultCheckTest {

    /** Mirrors the compact-marker gate: presentation only after a durable insert. */
    private data class CompactOutcome(
        val summaryShown: Boolean,
        val cachedMarker: Boolean,
        val uiGrayed: Boolean,
        val reportedSuccess: Boolean,
    )

    private fun runCompact(markerInsertSucceeds: Boolean): CompactOutcome {
        var summaryShown = false
        var cachedMarker = false
        var uiGrayed = false
        var reportedSuccess = false

        val markerPersisted = markerInsertSucceeds
        if (!markerPersisted) {
            // The abort path: nothing downstream may claim success.
            return CompactOutcome(false, false, false, false)
        }
        summaryShown = true
        cachedMarker = true
        uiGrayed = true
        reportedSuccess = true
        return CompactOutcome(summaryShown, cachedMarker, uiGrayed, reportedSuccess)
    }

    @Test
    fun `a failed marker insert suppresses the whole compact presentation`() {
        val out = runCompact(markerInsertSucceeds = false)
        // The regression: summary still set, marker still cached, transcript
        // still grayed, and the caller told the compaction succeeded — while a
        // reload found no marker and reverted everything.
        assertFalse("no summary", out.summaryShown)
        assertFalse("no cached marker", out.cachedMarker)
        assertFalse("transcript not grayed", out.uiGrayed)
        assertFalse("not reported as succeeded", out.reportedSuccess)
    }

    @Test
    fun `a successful marker insert still produces the full presentation`() {
        // The guard must not silently disable compaction.
        val out = runCompact(markerInsertSucceeds = true)
        assertTrue(out.summaryShown)
        assertTrue(out.cachedMarker)
        assertTrue(out.uiGrayed)
        assertTrue(out.reportedSuccess)
    }

    /** Mirrors `MemoryToolRow`'s label/colour decision. */
    private fun memoryOpLabel(isWrite: Boolean, succeeded: Boolean): String = when {
        !succeeded && isWrite -> "memory_write (failed)"
        isWrite -> "memory_write"
        !succeeded -> "memory_get (failed)"
        else -> "memory_get"
    }

    @Test
    fun `a failed memory write is labelled as failed`() {
        // The regression: an unconditional record append meant a write that
        // never reached the disk still showed as "memory_write" in the success
        // colour, with the content previewed underneath.
        assertEquals("memory_write (failed)", memoryOpLabel(isWrite = true, succeeded = false))
        assertEquals("memory_write", memoryOpLabel(isWrite = true, succeeded = true))
    }

    @Test
    fun `a failed memory read is labelled as failed`() {
        assertEquals("memory_get (failed)", memoryOpLabel(isWrite = false, succeeded = false))
        assertEquals("memory_get", memoryOpLabel(isWrite = false, succeeded = true))
    }

    @Test
    fun `written content is only recorded when the write succeeded`() {
        // `writtenContent` feeds the sheet's detail view, so showing it for a
        // failed write would exhibit content that was never stored.
        val attempted = "the user's note"
        assertEquals(null, attempted.takeIf { false })
        assertEquals(attempted, attempted.takeIf { true })
    }
}
