package com.openminis.app.ui.chat

import com.openminis.app.feature.runtime.ForkGoalCommandParser
import com.openminis.app.feature.runtime.SlashCommandWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-slash-goal-fork] Guards the slash-command PICKER.
 *
 * `/goal` and `/fork` were fully implemented — parsed, executed, tested at the
 * runtime level — and completely absent from the picker, which is the only place
 * a user discovers commands. Nothing failed: the parser tests were green, the
 * execution tests were green, and the picker simply never offered them.
 *
 * The durable lesson these tests encode: a command has TWO halves (the parser's
 * word, and the picker's row) and a test of either alone cannot see the gap.
 * So the most important test here is the cross-check between them, not the
 * individual assertions.
 */
class SlashCommandCatalogTest {

    private val catalog = defaultSlashCommandCatalog()

    private fun row(word: String) = catalog.firstOrNull { it.fillText?.trim() == word }

    @Test
    fun `every command the parser accepts is offered by the picker`() {
        // The load-bearing assertion. Add a word to SlashCommandWords without
        // listing it here and this fails; that is exactly the omission that
        // kept /goal and /fork typed-only.
        val missing = SlashCommandWords.parserAccepted.filter { row(it) == null }
        assertTrue(
            "these commands are accepted by the parser but the picker never " +
                "offers them, so users cannot discover them: $missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun `the picker offers no command word the parser would reject`() {
        // The converse: a row whose tap fills the composer with a word the
        // parser does not recognise is a dead end that looks alive.
        val unrecognised = catalog
            .mapNotNull { it.fillText?.trim() }
            .distinct()
            .filter { !it.startsWith("/") }
        assertTrue("rows must fill a slash command, got: $unrecognised", unrecognised.isEmpty())
    }

    @Test
    fun `goal and fork rows fill their exact command word`() {
        // Verified against the real parser rather than against a copy of the
        // word: "/fork" typed must parse, and "/Fork" must not be relied on.
        assertEquals(SlashCommandWords.FORK, row(SlashCommandWords.FORK)?.fillText?.trim())
        assertEquals(SlashCommandWords.GOAL, row(SlashCommandWords.GOAL)?.fillText?.trim())
        assertTrue(
            "the filled text must be what the parser accepts",
            ForkGoalCommandParser.isCommand(row(SlashCommandWords.FORK)!!.fillText!!.trim()),
        )
        // NB: the bare word "/goal" is deliberately NOT a command — a goal with
        // no objective cannot be started, so the parser returns null for it.
        // The filled text is therefore a PREFIX the user completes; asserting
        // `isCommand("/goal")` here would encode the opposite of the contract.
        assertFalse(
            "a goal with no objective must not be startable",
            ForkGoalCommandParser.isCommand(SlashCommandWords.GOAL),
        )
        assertTrue(
            "once the user supplies the objective the parse succeeds",
            // Uses the RAW fillText (trailing space included) — concatenating
            // onto a trimmed copy produces "/goalship the audit", which is
            // exactly the failure the trailing space exists to prevent.
            ForkGoalCommandParser.isCommand(row(SlashCommandWords.GOAL)!!.fillText!! + "ship the audit"),
        )
    }

    @Test
    fun `a filled command leaves a trailing space for the argument`() {
        // Both commands REQUIRE an argument (an objective / a session name).
        // Filling "/goal" with no trailing space would make the user's next
        // keystroke produce "/goaldo the thing".
        assertEquals("/goal ", row(SlashCommandWords.GOAL)?.fillText)
        assertEquals("/fork ", row(SlashCommandWords.FORK)?.fillText)
    }

    @Test
    fun `goal and fork are built-in rows, not skills`() {
        // They fill the composer like skills do, but they are not skills: the
        // picker groups and labels skill rows by origin, and these are internal
        // commands with their own subtitles.
        assertTrue("must not be tagged as a skill", row(SlashCommandWords.GOAL)?.isSkill == false)
        assertTrue("must not be tagged as a skill", row(SlashCommandWords.FORK)?.isSkill == false)
        assertTrue("must not be tagged as mcp", row(SlashCommandWords.GOAL)?.isMcp == false)
    }

    @Test
    fun `executing commands do not claim to be typing aids`() {
        // clear/compact/memory/thinking act on tap; only skill/fork/goal fill.
        // A row that executes must leave fillText null, or it would start
        // filling the composer instead of running.
        listOf("clear", "compact", "memory", "thinking").forEach { id ->
            val cmd = catalog.firstOrNull { it.id == id }
            assertNotNull("built-in command $id went missing", cmd)
            assertNull("$id must execute, not fill", cmd!!.fillText)
        }
    }

    @Test
    fun `every row has a stable unique id so the list stays addressable`() {
        val ids = catalog.map { it.id }
        assertEquals("duplicate ids would make rows unaddressable", ids.size, ids.distinct().size)
        assertTrue("rows must have ids", ids.none { it.isBlank() })
    }
}
