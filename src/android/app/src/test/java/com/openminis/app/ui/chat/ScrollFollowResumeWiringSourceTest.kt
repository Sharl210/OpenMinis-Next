package com.openminis.app.ui.chat

import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-panel-parked-at-end] **Source-text assertions** (not behaviour),
 * in the same spirit as [ModelPromptFragmentWiringSourceTest]: a state machine
 * that is correct and behaviourally well covered can still be unreachable in
 * production when the UI never dispatches the event it needs. That is exactly
 * the defect this file guards — `ScrollFollowEvent.AtBottomReached` is only
 * useful if some production call site dispatches it once the view is parked at
 * the end, and no JVM behavioural test can see a missing call site (the
 * scrollable lives in a Compose screen whose construction needs the Android
 * runtime).
 *
 * ## What is asserted
 *
 * The long-content follow contract has two halves that must exist as a pair:
 *
 *  - **pause**: `observeVerticalDrag` on a panel scroller (and the message
 *    list's drag interactions) may park the machine in `PAUSED_BY_USER`;
 *  - **resume**: `ObserveFollowResume` turns "parked at the end with nothing
 *    scrolling" back into following.
 *
 * Deleting either half keeps every behavioural unit test green while the
 * user-visible behaviour regresses to "the panel keeps growing but stays where
 * it was, so you only ever see that one window".
 *
 * ## Wrapping-tolerance
 *
 * These files are edited by several agents at once and Kotlin call sites get
 * re-wrapped, so the assertions are written against **bounded windows around an
 * anchor** rather than whole lines: a formatter may move the bytes and the test
 * still means "this scroller both arms and clears the pause".
 */
class ScrollFollowResumeWiringSourceTest {

    private fun source(relative: String): String = sequenceOf(
        File(relative),
        File("app/$relative"),
    ).first { it.isFile }.readText()

    private fun indexes(haystack: String, needle: String): List<Int> {
        val found = mutableListOf<Int>()
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return found
            found += at
            from = at + needle.length
        }
    }

    /**
     * A scroller that can arm the pause must install the matching resume in the
     * same composable scope, and the resume must be reachable through the
     * position event.
     */
    private fun assertPauseAndResumeArePaired(relative: String) {
        val text = source(relative)
        val pauseSites = indexes(text, "observeVerticalDrag(")
        val resumeSites = indexes(text, "ObserveFollowResume(")

        assertTrue("$relative: expected at least one pause site", pauseSites.isNotEmpty())
        assertEquals(
            "$relative: every scroller that can arm a pause must install a resume",
            pauseSites.size,
            resumeSites.size,
        )
        for (pause in pauseSites) {
            val nearest = resumeSites.minOf { abs(it - pause) }
            assertTrue(
                "$relative: the resume install must sit in the same panel scope as its pause " +
                    "(nearest resume is $nearest chars away)",
                nearest < 2_000,
            )
        }
        for (resume in resumeSites) {
            val window = text.substring(resume, minOf(text.length, resume + 800))
            assertTrue(
                "$relative: the resume install must be able to dispatch the position event — " +
                    "window: ${window.take(120)}",
                window.contains("ScrollFollowEvent.AtBottomReached"),
            )
        }
    }

    @Test
    fun `thinking panel pairs its pause with a resume`() {
        assertPauseAndResumeArePaired("src/main/java/com/openminis/app/ui/chat/ChatAssistantMessageUI.kt")
    }

    @Test
    fun `code block pairs its pause with a resume`() {
        assertPauseAndResumeArePaired("src/main/java/com/openminis/app/ui/chat/StreamingMarkdownText.kt")
    }

    @Test
    fun `message list resumes once it is parked at the end`() {
        val text = source("src/main/java/com/openminis/app/ui/chat/ChatScreen.kt")
        val resumeSites = indexes(text, "ObserveFollowResume(")

        assertEquals("ChatScreen must install exactly one parked-at-end resume", 1, resumeSites.size)
        val window = text.substring(resumeSites.single(), minOf(text.length, resumeSites.single() + 900))
        assertTrue(
            "the list's resume must go through applyScrollFollow… — window: ${window.take(200)}",
            window.contains("applyScrollFollow"),
        )
        assertTrue(
            "…and it must dispatch the position event — window: ${window.take(200)}",
            window.contains("ScrollFollowEvent.AtBottomReached"),
        )
        assertTrue(
            "the list's resume must judge the real bottom, not a mid-viewport offset",
            window.contains("isNearBottom"),
        )
    }
}
