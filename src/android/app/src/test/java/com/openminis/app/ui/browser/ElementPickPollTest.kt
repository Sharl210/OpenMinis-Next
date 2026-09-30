package com.openminis.app.ui.browser

import com.openminis.app.shared.KotlinSourceText
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-browser-element-pick-feedback] An element pick that ends without picking
 * anything has to say so.
 *
 * ## What was wrong
 *
 * The pick loop in `BrowserSheet` ran `repeat(240) { delay(250) … }` and, when the loop
 * simply ended, disarmed the page and returned — **no toast, no state change the user
 * could see**. The only visible trace of an active picker was the toolbar tint, which
 * went back to normal exactly as it does after a successful pick. Two of the three exit
 * paths behaved that way: the picker failing to arm at all (`if (!armed.success)
 * return@launch`) and the 60 s window closing.
 *
 * ## What this test can and cannot pin
 *
 * The rule — *when* the picker gives up and *what counts as a pick* — is now the pure
 * function [elementPickPoll], so a JVM test can drive it. That is what most of this file
 * does, and it is real behavioural evidence: each assertion names an input triple whose
 * verdict changes if the rule changes.
 *
 * **The toast itself is not covered here.** Showing it needs a device, and Compose UI has
 * no JVM seam in this repository (there is no Compose test infrastructure at all —
 * `grep -rn "createComposeRule" src/test src/androidTest` is empty). The last test is a
 * **wiring pin**, not behaviour: it proves the two messages are still referenced from the
 * real source, so neither can be quietly dropped back to silence without deleting a
 * string that something names. It does **not** prove the toast appears, nor that it
 * appears on the path a user actually takes.
 */
class ElementPickPollTest {

    // ---- the window ----------------------------------------------------------------

    @Test
    fun `the poll window is the sixty seconds the picker has always used`() {
        assertEquals(
            "240 polls 250 ms apart is the 60 s window; naming both numbers is what lets " +
                "this be pinned instead of re-derived from the loop",
            60_000L,
            ELEMENT_PICK_POLLS * ELEMENT_PICK_POLL_MILLIS,
        )
    }

    // ---- when it gives up ----------------------------------------------------------

    @Test
    fun `the last poll with nothing picked times out`() {
        assertEquals(
            "this is the verdict the loop used to reach by falling out of itself, with " +
                "nothing downstream able to tell it apart from a successful start",
            ElementPickPoll.TimedOut,
            elementPickPoll(readSucceeded = true, readText = "null", pollsLeftAfterThis = 0),
        )
    }

    @Test
    fun `a poll with time left keeps waiting instead of timing out early`() {
        assertEquals(
            ElementPickPoll.Waiting,
            elementPickPoll(readSucceeded = true, readText = "null", pollsLeftAfterThis = 1),
        )
    }

    @Test
    fun `a failed read is not a pick and still reaches the timeout at the end`() {
        assertEquals(
            "a read that failed must not be mistaken for an element",
            ElementPickPoll.Waiting,
            elementPickPoll(readSucceeded = false, readText = "", pollsLeftAfterThis = 5),
        )
        assertEquals(
            "…and a picker whose reads keep failing must still end up telling the user",
            ElementPickPoll.TimedOut,
            elementPickPoll(readSucceeded = false, readText = "", pollsLeftAfterThis = 0),
        )
    }

    // ---- what counts as a pick -----------------------------------------------------

    @Test
    fun `the page's element comes back as the pick`() {
        val verdict = elementPickPoll(
            readSucceeded = true,
            readText = """{"domPath":"body > button","stableSelector":"#save","visibleText":"Save"}""",
            pollsLeftAfterThis = 12,
        )
        val picked = verdict as? ElementPickPoll.Picked
        assertNotNull("an element on the page has to be a pick, not a wait: $verdict", picked)
        assertEquals("body > button", picked!!.json.getString("domPath"))
        assertEquals("#save", picked.json.getString("stableSelector"))
    }

    @Test
    fun `a pick on the very last poll is honoured rather than turned into a timeout`() {
        // The budget check must not run before the pick check: a user who clicks on the
        // final tick has picked something, and reporting that as "nothing was selected"
        // is the one outcome the message must never be wrong about.
        val verdict = elementPickPoll(
            readSucceeded = true,
            readText = """{"domPath":"body"}""",
            pollsLeftAfterThis = 0,
        )
        assertTrue("a pick on the last tick is still a pick, got $verdict", verdict is ElementPickPoll.Picked)
    }

    @Test
    fun `the literal null, blank text and non-JSON text all mean nothing was picked`() {
        assertNull("the still-armed page answers with the literal null", parsePickedElement("null"))
        assertNull("…and it may be padded", parsePickedElement("  null  "))
        assertNull("an empty answer is not an element", parsePickedElement(""))
        assertNull("whitespace is not an element", parsePickedElement("   "))
        assertNull("text that is not a JSON object is not an element", parsePickedElement("not json at all"))
        assertNull("a JSON array is not an element", parsePickedElement("[]"))

        for (raw in listOf("null", "", "   ", "not json at all", "[]")) {
            assertEquals(
                "\"$raw\" must keep the poller waiting, not end the pick",
                ElementPickPoll.Waiting,
                elementPickPoll(readSucceeded = true, readText = raw, pollsLeftAfterThis = 3),
            )
        }
    }

    @Test
    fun `the verdict depends only on its three inputs`() {
        val raw = """{"domPath":"body"}"""
        repeat(2) {
            assertEquals(
                ElementPickPoll.Waiting,
                elementPickPoll(readSucceeded = true, readText = "null", pollsLeftAfterThis = 4),
            )
            assertTrue(
                elementPickPoll(readSucceeded = true, readText = raw, pollsLeftAfterThis = 4)
                    is ElementPickPoll.Picked,
            )
        }
        assertEquals(
            "an already-parsed object is returned as-is, not rebuilt",
            "body",
            (elementPickPoll(true, raw, 0) as ElementPickPoll.Picked).json.getString("domPath"),
        )
    }

    // ---- wiring pin (not behaviour) ------------------------------------------------

    @Test
    fun `both new messages are still referenced from the sheet that shows them`() {
        val sheet = locate("src/main/java/com/openminis/app/ui/browser/BrowserSheet.kt")
        assertTrue("could not locate BrowserSheet.kt", sheet != null)
        val code = KotlinSourceText.noComments(sheet!!.readText())

        // Comments are blanked first: a message named only in a comment is not shown to
        // anybody, and that is precisely the shape this pin exists to reject.
        for (key in listOf("browser_element_pick_timeout", "browser_element_pick_unavailable")) {
            assertTrue(
                "R.string.$key must still be referenced from BrowserSheet.kt — the two " +
                    "silent exits were the defect, and a resource that nothing names is " +
                    "how they come back",
                code.contains("R.string.$key"),
            )
        }

        // The window is stated once, as a named constant. An inline 240 would let the
        // loop and this test drift apart while both still looked right.
        assertTrue(
            "the poll count must come from ELEMENT_PICK_POLLS, not an inline literal",
            code.contains("repeat(ELEMENT_PICK_POLLS)"),
        )
    }

    private fun locate(relative: String): File? {
        val working = File(System.getProperty("user.dir") ?: ".")
        return generateSequence(working) { it.parentFile }
            .take(8)
            .map { File(it, relative) }
            .firstOrNull { it.isFile }
    }

    @Test
    fun `the wiring pin fails when a comment is the only mention`() {
        // The judge, judged: the assertion above is only worth anything if blanking
        // comments actually removes the key from what it reads.
        val withComment = "// shows $${'$'}{R.string.browser_element_pick_timeout}\n"
        assertTrue(
            "kdoc: the raw text does mention the key",
            withComment.contains("R.string.browser_element_pick_timeout"),
        )
        assertTrue(
            "kdoc: but the comment-stripped text must not",
            !KotlinSourceText.noComments(withComment).contains("R.string.browser_element_pick_timeout"),
        )
    }
}
