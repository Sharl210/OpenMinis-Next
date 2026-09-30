package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-compact-boundary-dbid-resolution] A rendered bubble is not
 * one-to-one with a persisted row, and FOUR separate call sites resolve a
 * compact marker's `lastCompactedMessageId` / `firstKeptMessageId` back to a
 * bubble:
 *
 *  1. ChatViewModel `compactAllImpl` — the graying state machine (sets
 *     `passedCutoff`),
 *  2. ChatViewModel `compactAllImpl` — the "N messages compacted" count,
 *  3. ChatViewModel Phase 2.5 `uiIdxForDbId`,
 *  4. ChatViewModel Phase 2.5 v1 `bIdx`.
 *
 * (1) and (2) originally tested `msg.id == cutoffId` only. Consecutive assistant
 * rows MERGE into one bubble that keeps only the LAST row's id, while the
 * earlier rows survive in `sourceDbIds` — and the compact anchor is frequently
 * one of those folded rows (the merge code says so in its own comment). So the
 * cutoff was never found, `passedCutoff` stayed false, and the whole transcript
 * was grayed, live tail included.
 *
 * All four now go through [bubbleRepresentsDbId]; these cases pin that rule.
 * `ChatMessage` itself is not constructed here — its `imageUris` is
 * `List<android.net.Uri>`, unbuildable in a plain-JVM test — so the rule is
 * exercised through the pure function the extension delegates to.
 */
class CompactBoundaryDbIdResolutionTest {

    private val foldedAway = "assistant-row-1"   // folded into the merged bubble
    private val mergedTail = "assistant-row-2"   // the bubble's own id
    private val bubbleIds = listOf(foldedAway, mergedTail)

    @Test
    fun `a bubbled row resolves by its own id`() {
        assertTrue(bubbleRepresentsDbId(mergedTail, bubbleIds, mergedTail))
    }

    @Test
    fun `a row folded into a merged bubble still resolves through sourceDbIds`() {
        // The regression: this used to return false, so the graying pass never
        // reached its cutoff.
        assertTrue(bubbleRepresentsDbId(mergedTail, bubbleIds, foldedAway))
    }

    @Test
    fun `an unrelated row does not resolve`() {
        assertFalse(bubbleRepresentsDbId(mergedTail, bubbleIds, "some-other-row"))
    }

    @Test
    fun `a bubble with no source rows matches only its own id`() {
        // Synthetic rows (dividers, notices, locally appended bubbles) have ids
        // that match no persisted row; they must not be dragged in by a marker.
        assertTrue(bubbleRepresentsDbId("sysinfo_1", emptyList(), "sysinfo_1"))
        assertFalse(bubbleRepresentsDbId("sysinfo_1", emptyList(), mergedTail))
    }

    @Test
    fun `a blank anchor matches no bubble`() {
        // A marker with a missing anchor must not resolve to a synthetic bubble
        // whose id happens to be blank, or the walk would gray from the wrong
        // place. `indexOfLast` returning -1 is the caller's "skip graying" signal.
        assertFalse(bubbleRepresentsDbId("", emptyList(), mergedTail))
        assertFalse(bubbleRepresentsDbId(mergedTail, bubbleIds, ""))
    }

    @Test
    fun `an unresolvable anchor means no graying, not graying everything`() {
        // Faithful model of the production block: resolve the anchor FIRST; only
        // when it resolves does the walk gray anything. Without that guard the
        // walk grays every bubble it visits — including the tail still live in
        // the model's context — which reads as "everything was compacted".
        val bubbles = listOf(
            "user-1" to listOf("user-1"),
            mergedTail to bubbleIds,
            "user-3" to listOf("user-3"),
        )
        val missingAnchor = "deleted-row"

        fun grayedFor(anchor: String): List<Boolean> {
            val anchorIdx = bubbles.indexOfLast { (id, sources) ->
                bubbleRepresentsDbId(id, sources, anchor)
            }
            if (anchorIdx < 0) return bubbles.map { false }
            var passedCutoff = false
            return bubbles.map { (id, sources) ->
                if (passedCutoff) {
                    false
                } else {
                    if (bubbleRepresentsDbId(id, sources, anchor)) passedCutoff = true
                    true
                }
            }
        }

        assertEquals(
            "unresolvable anchor must gray nothing",
            listOf(false, false, false),
            grayedFor(missingAnchor),
        )
        // …and a resolvable one still grays up to and including its bubble, so
        // the guard cannot silently disable the feature.
        assertEquals(
            "anchor inside the merged bubble grays that bubble and everything before it",
            listOf(true, true, false),
            grayedFor(foldedAway),
        )
    }
}
