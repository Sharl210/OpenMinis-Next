package com.openminis.app.feature.runtime

import com.openminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [R7] The injected per-round prompt must actually reach the message list the
 * model is handed, with a role bridge in front of it when the turn before it was
 * the user's.
 *
 * ## The defect this file pins
 *
 * Two production sites inject a periodic/start prompt into a conversation:
 * `ChatViewModel` (per model round of the main agent) and `RuntimeChildRunner`
 * (per model round of a delegated child). Both funnel through
 * `RoundInjectionCoordinator.appendToHistory`.
 *
 * Measured before this file existed: the string `appendToHistory` appeared
 * **zero** times under `src/test` and `src/androidTest`. Deleting the append
 * leaves `injection.prompt` read by nobody — the child agent, in particular,
 * simply never receives the instruction, which is the requirement
 * 「包含各级子会话」 failing silently — and the whole suite stayed green.
 *
 * ## Why this iterates a real extraction rather than the coordinator
 *
 * `RoundInjectionCoordinator`'s constructor reads SharedPreferences, so it cannot
 * be built on the JVM (`unitTests.isReturnDefaultValues = true` makes
 * `applicationContext` null). The history shaping was therefore extracted, with
 * no behaviour change, into [appendInjectedPromptToHistory] — which
 * `appendToHistory` now delegates to, so this is the code production runs and
 * not a copy of it.
 *
 * ## Why the assertions are not `assertTrue(history.isNotEmpty())`
 *
 * A "something was appended" assertion survives every mutation that matters:
 * dropping the bridge, appending the bridge instead of the prompt, flipping the
 * roles, or appending the prompt in the wrong position all leave the list
 * non-empty. Every assertion below pins role **and** content **by index**, so it
 * names the turn it is about. The call sites themselves — the two lines whose
 * deletion motivated this file — are guarded by
 * `InjectedPromptAndCompactWiringSourceTest`, which is a source-text assertion
 * and says so.
 */
class RoundInjectionHistoryTest {

    private fun user(text: String) = LLMMessage(role = LLMMessage.Role.USER, content = text)
    private fun assistant(text: String) = LLMMessage(role = LLMMessage.Role.ASSISTANT, content = text)

    @Test
    fun `an injected prompt is appended as a user turn with its own text as content`() {
        val history = mutableListOf(assistant("previous answer"))

        appendInjectedPromptToHistory(history, "periodic reminder")

        assertEquals(2, history.size)
        assertEquals(LLMMessage.Role.USER, history.last().role)
        assertEquals("periodic reminder", history.last().content)
    }

    @Test
    fun `a user turn immediately before the injection gets an assistant bridge between them`() {
        val history = mutableListOf(user("what does this function do?"))

        appendInjectedPromptToHistory(history, "periodic reminder")

        assertEquals("no bridge means two USER turns in a row", 3, history.size)
        assertEquals(LLMMessage.Role.ASSISTANT, history[1].role)
        assertEquals(RoundInjectionCoordinator.ROLE_BRIDGE, history[1].content)
        assertEquals(LLMMessage.Role.USER, history[2].role)
        assertEquals("periodic reminder", history[2].content)
    }

    @Test
    fun `the bridge is inserted before the injected turn and never after it`() {
        val history = mutableListOf(user("first"))

        appendInjectedPromptToHistory(history, "reminder")

        // Ordering, stated positively: the injected instruction is the LAST thing
        // the model sees, so a bridge appended after it would mean the model's
        // final turn is an acknowledgement rather than the instruction.
        assertEquals(listOf(LLMMessage.Role.USER, LLMMessage.Role.ASSISTANT, LLMMessage.Role.USER), history.map { it.role })
        assertEquals("reminder", history.last().content)
    }

    @Test
    fun `no bridge is inserted when the turn before the injection is not the user`() {
        val history = mutableListOf(user("ask"), assistant("answer"))

        appendInjectedPromptToHistory(history, "reminder")

        assertEquals("a bridge here would be an unprompted assistant turn", 3, history.size)
        assertEquals(LLMMessage.Role.USER, history[2].role)
        assertEquals("reminder", history[2].content)
    }

    @Test
    fun `an empty history receives the injected turn alone`() {
        val history = mutableListOf<LLMMessage>()

        appendInjectedPromptToHistory(history, "start prompt")

        assertEquals(1, history.size)
        assertEquals(LLMMessage.Role.USER, history.single().role)
        assertEquals("start prompt", history.single().content)
    }

    @Test
    fun `content parts carry the same text the content does`() {
        val history = mutableListOf(user("ask"))

        appendInjectedPromptToHistory(history, "reminder")

        // The provider's multimodal path reads contentParts, not content: a turn
        // that carries the right `content` and empty/incorrect parts is sent to
        // the model as nothing.
        val injected = history.last()
        assertEquals(1, injected.contentParts.size)
        val text = injected.contentParts.single() as com.openminis.app.data.model.AgentContentPart.Text
        assertEquals("reminder", text.text)
        val bridged = history[1]
        assertTrue(bridged.contentParts.isNotEmpty())
    }

    @Test
    fun `two injections in a row each get their own bridge`() {
        val history = mutableListOf(user("ask"))

        appendInjectedPromptToHistory(history, "first reminder")
        appendInjectedPromptToHistory(history, "second reminder")

        assertEquals(
            listOf(
                LLMMessage.Role.USER,
                LLMMessage.Role.ASSISTANT,
                LLMMessage.Role.USER,
                LLMMessage.Role.ASSISTANT,
                LLMMessage.Role.USER,
            ),
            history.map { it.role },
        )
        assertEquals("second reminder", history.last().content)
    }
}
