package com.openminis.app.feature.runtime

import com.openminis.app.shared.KotlinSourceText
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-round-injection-counter-loss] A round-injection counter that cannot be read
 * back must be reported, because losing it is not a neutral reset.
 *
 * ## The discard
 *
 * `RoundInjectionCoordinator.readState` ended in
 * `runCatching { … }.getOrDefault(RoundInjectionState())`. A stored entry that cannot be
 * parsed therefore produced exactly what a session that has *never been counted* produces,
 * so nothing downstream could tell the two apart.
 *
 * ## Why that is not harmless
 *
 * `RoundInjectionPolicy.beforeModelCall` numbers calls as `state.totalModelCalls + 1`, and
 * treats call number 1 as the session's first: that is when the start prompt fires. A
 * counter reset to zero therefore makes the *next* call in a mid-session conversation look
 * like the first call, and the session-start prompt is injected again. The periodic
 * schedule restarts with it. The tests below drive that consequence through the real
 * policy rather than describing it.
 *
 * ## What is and is not covered
 *
 * The parse rule and the consequence are both real behavioural evidence. **The report
 * itself is not**: the coordinator needs a `Context`, so a JVM test cannot construct it and
 * cannot observe the message. The last test is a wiring pin — it shows `readState` still
 * routes through the parse function and no longer swallows the failure with a bare
 * `getOrDefault`, which is the shape the defect had. It does not prove the log is written.
 */
class RoundInjectionCounterLossTest {

    // ---- the parse rule ------------------------------------------------------------

    @Test
    fun `a stored counter reads back as it was written`() {
        val parsed = parseRoundInjectionState("""{"totalModelCalls":7,"lastInjectionCall":5}""")
        assertNotNull("a well-formed entry has to be readable", parsed)
        assertEquals(7, parsed!!.totalModelCalls)
        assertEquals(5, parsed.lastInjectionCall)
    }

    @Test
    fun `an explicit null last injection stays null`() {
        // The coordinator writes JSONObject.NULL when nothing has been injected yet; a
        // session that has never injected is not the same as one whose last injection was
        // call 0.
        val parsed = parseRoundInjectionState("""{"totalModelCalls":3,"lastInjectionCall":null}""")
        assertNotNull(parsed)
        assertNull(parsed!!.lastInjectionCall)
    }

    @Test
    fun `a counter that cannot be read back is null, not a default`() {
        // This is the whole fix: null says "unreadable", which the caller must report. A
        // default would say "never counted", which is a different fact and the reason the
        // loss used to be invisible.
        for (raw in listOf("", "   ", "not json", "[]", "42", "null")) {
            assertNull("「$raw」 must not read back as a counter", parseRoundInjectionState(raw))
        }
    }

    @Test
    fun `a negative counter is clamped rather than trusted`() {
        val parsed = parseRoundInjectionState("""{"totalModelCalls":-4}""")
        assertNotNull(parsed)
        assertEquals("a negative count must not survive", 0, parsed!!.totalModelCalls)
    }

    @Test
    fun `a non-positive last injection reads as never injected`() {
        // `takeIf { it > 0 }` matches how the coordinator writes it: absent, null, 0 and a
        // negative all mean "nothing injected yet".
        for (raw in listOf(
            """{"totalModelCalls":2,"lastInjectionCall":0}""",
            """{"totalModelCalls":2,"lastInjectionCall":-3}""",
            """{"totalModelCalls":2}""",
        )) {
            assertNull("「$raw」 has no usable last injection", parseRoundInjectionState(raw)!!.lastInjectionCall)
        }
    }

    // ---- why the discard matters, driven through the real policy -------------------

    @Test
    fun `a lost counter makes the session-start prompt fire again mid-session`() {
        val settings = RoundInjectionSettings(
            injectOnStart = true,
            startPrompt = "start of session",
            periodicEnabled = false,
            interval = 20,
        )

        // What the session really is: call number 6, start prompt already delivered.
        val real = RoundInjectionState(totalModelCalls = 5, lastInjectionCall = 1)
        val realDecision = RoundInjectionPolicy.beforeModelCall(real, settings)
        assertNull("mid-session, the start prompt must not fire again", realDecision.second.prompt)
        assertEquals(false, realDecision.second.isStartPrompt)

        // What the session looks like after the counter is discarded.
        val lost = RoundInjectionState()
        val lostDecision = RoundInjectionPolicy.beforeModelCall(lost, settings)
        assertEquals(
            "this is the consequence of the silent discard: the counter is back at zero, so " +
                "the next call is call 1 and the start prompt is injected a second time",
            "start of session",
            lostDecision.second.prompt,
        )
        assertTrue(lostDecision.second.isStartPrompt)
    }

    @Test
    fun `a lost counter also restarts the periodic schedule`() {
        val settings = RoundInjectionSettings(
            injectOnStart = false,
            periodicEnabled = true,
            periodicPrompt = "periodic",
            interval = 4,
        )

        // Injected at call 2 with an interval of 4, so the next periodic prompt is due at
        // call 6: invocation - lastInjectionCall = 6 - 2 = 4.
        assertNull(
            "call 5 is one short of the interval",
            RoundInjectionPolicy.beforeModelCall(
                RoundInjectionState(totalModelCalls = 4, lastInjectionCall = 2),
                settings,
            ).second.prompt,
        )
        assertEquals(
            "call 6 is exactly due",
            "periodic",
            RoundInjectionPolicy.beforeModelCall(
                RoundInjectionState(totalModelCalls = 5, lastInjectionCall = 2),
                settings,
            ).second.prompt,
        )

        // With the counter lost there is no last injection to measure from, so the
        // interval is measured from zero instead — a different schedule entirely.
        val lostDecision = RoundInjectionPolicy.beforeModelCall(RoundInjectionState(), settings)
        assertNull(
            "with no last injection the interval counts from zero, so call 1 is not due yet",
            lostDecision.second.prompt,
        )
        assertEquals(1, lostDecision.first.totalModelCalls)
    }

    // ---- wiring pin (not behaviour) ------------------------------------------------

    @Test
    fun `the reader still routes through the parse rule and no longer swallows the failure`() {
        val source = locate("src/main/java/com/openminis/app/feature/runtime/RoundInjection.kt")
        assertTrue("could not locate RoundInjection.kt", source != null)
        val code = KotlinSourceText.noComments(source!!.readText())

        val readState = code.substringAfter("private fun readState(", "")
        assertTrue("readState has to exist", readState.isNotEmpty())
        val body = readState.substringBefore("\n    private fun ")
        assertTrue(
            "readState must route through parseRoundInjectionState, which is the only " +
                "thing that can tell \"unreadable\" from \"never counted\"",
            body.contains("parseRoundInjectionState("),
        )
        assertTrue(
            "readState must report the discard; without this call the counter loss is " +
                "silent again, which is exactly the defect",
            body.contains("reportCorruption("),
        )
        assertTrue(
            "the bare default that caused the silent loss must not come back",
            !body.contains("getOrDefault(RoundInjectionState())"),
        )
    }

    private fun locate(relative: String): File? {
        val working = File(System.getProperty("user.dir") ?: ".")
        return generateSequence(working) { it.parentFile }
            .take(8)
            .map { File(it, relative) }
            .firstOrNull { it.isFile }
    }
}
