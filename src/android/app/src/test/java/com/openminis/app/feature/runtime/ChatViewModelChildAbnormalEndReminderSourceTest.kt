package com.openminis.app.feature.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-child-agent-completion] Requirement (`request.md:9`): an abnormal
 * child end must be identifiable **to the main agent**, as supporting
 * information for deciding whether to restart the child.
 *
 * The defect this guards: `runChildAttempt` returns `Result.success` for a child
 * that never called the completion tool, so a possibly-truncated reply reached
 * the parent looking exactly like a clean result. The abnormal end existed only
 * in the runtime tree and in a stop report with no readers.
 *
 * ## The split in this file, stated up front
 *
 *  - **[A] ONE behavioural test** — `the natural-end flag the guard reads is the
 *    protocol's own verdict` runs the real predicate and asserts its truth table
 *    (an undeclared end is not natural; only the completion tool is). That is
 *    the verdict the whole feature rests on, and it is executed, not read.
 *  - **[C] THREE structural pins, kept deliberately** — the other three tests
 *    assert on the text of `ChatViewModel.kt`. They are kept because the
 *    behaviour they describe is NOT reachable from this module's unit-test
 *    source set: the injection is built inside
 *    `ChatViewModel.publishDelegatedChildOutcome`, a private suspend member of a
 *    `ViewModel` whose constructor needs the entire Android chat stack (Room
 *    `ChatDao`, provider repository, coroutine scopes). No test in this repo
 *    constructs a `ChatViewModel`, and this module has neither Robolectric nor
 *    compose-ui-test. Deleting them would remove the only guard on the call
 *    site; inventing a "behavioural" test for it would need a fake of the whole
 *    app. Each of the three carries an inline `(C)` note saying so.
 *
 * The *downstream* half of this feature is behavioural elsewhere and is NOT
 * duplicated here: `SystemRowDispatchTest` (`the injected text is read back
 * verbatim from the persisted row`, `an injected row is not a human turn and not
 * a navigation anchor`) pins that a `TOOL_INJECTION` `<system-reminder>` row is
 * persisted verbatim, renders as a neutral system row rather than a human
 * bubble, and counts as no human turn; `HumanTurnCountParityTest`
 * (`the resume reminder is not a human turn on either side`) pins the UI/DB
 * parity of that count. What none of them can see is whether
 * `ChatViewModel` ever writes such a row — which is exactly what the three
 * retained pins cover.
 */
class ChatViewModelChildAbnormalEndReminderSourceTest {

    private fun source(): String = sequenceOf(
        File("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt"),
        File("app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt"),
    ).first { it.isFile }.readText()

    /**
     * The delegated-child write-back block: the body of
     * `publishDelegatedChildOutcome`, which is where the result AND the
     * abnormal-end reminder are written.
     *
     * [T-android-child-restart] This used to be extracted as "from
     * `dispatchDelegationCommand` to the next `private var currentModel`", which
     * quietly assumed two things that both stopped being true:
     *
     *  - that the reminder is built INSIDE `dispatchDelegationCommand` — it moved
     *    into `publishDelegatedChildOutcome` so the restart launcher can reuse the
     *    same write-back instead of copying it (one implementation, two callers);
     *  - that nothing else is declared between that function and `currentModel`.
     *    When an unrelated feature (`surfaceRuntimeInbox`) was added in between,
     *    the span grew past the end of the function it was meant to describe and
     *    picked up an `appendSystemInfo` call belonging to that other function —
     *    turning "the reminder must not be a UI-only system card" red for a reason
     *    that had nothing to do with the reminder.
     *
     * Anchor on the function that actually owns the reminder; bound it by the next
     * sibling declaration. That bound can only ever make the span SMALLER than the
     * function, never larger, so a future member inserted after it cannot
     * re-contaminate the span.
     */
    private fun dispatchBlock(text: String): String {
        val start = text.indexOf("private suspend fun publishDelegatedChildOutcome(")
        assertTrue(
            "publishDelegatedChildOutcome must exist — it is where a delegated child's result " +
                "and its abnormal-end reminder are written",
            start >= 0,
        )
        val end = listOf("\n    private ", "\n    internal ", "\n    @", "\n    val ")
            .map { text.indexOf(it, start + 1) }
            .filter { it > start }
            .minOrNull() ?: text.length
        assertTrue("could not bound publishDelegatedChildOutcome", end > start)
        return text.substring(start, end)
    }

    private fun reminderText(block: String): String {
        val start = block.indexOf("childResult?.takeIf { !it.completedNaturally }")
        assertTrue("the abnormal-end injection must be guarded by completedNaturally", start >= 0)
        return block.substring(start)
    }

    @Test
    fun `an abnormal child end injects a reminder the main agent actually reads`() {
        // (C) KEPT, NOT CONVERTIBLE — this asserts on the TEXT of ChatViewModel.kt.
        // The reminder is built in `publishDelegatedChildOutcome`, a private
        // suspend member of ChatViewModel; reaching it needs a constructed
        // ChatViewModel (Room ChatDao + provider repository + Dispatchers.Main)
        // and this module has no Robolectric. The assertions are kept rather than
        // dropped because each one pins a distinct way the reminder can fail to
        // reach the model, and an empty file here would leave the call site
        // unguarded.
        val block = dispatchBlock(source())
        val injected = reminderText(block)

        // 1) It must be a <system-reminder> USER row. LLMMessage.Role has no
        //    SYSTEM member, so anything routed through ChatMessage(role="system")
        //    never reaches a provider.
        assertTrue("must carry the reminder wrapper: $injected", injected.contains("<system-reminder>"))
        assertTrue("must be a USER row: $injected", injected.contains("LLMMessage.Role.USER"))
        // 2) It must enter agentHistory: that list, not _messages, is what the
        //    next model call is built from.
        assertTrue("must be appended to agentHistory: $injected", injected.contains("agentHistory.add("))
        // 3) It must be persisted with the injection provenance, so a reload does
        //    not resurrect it as a human turn or drop it from history.
        assertTrue(
            "must persist as a tool injection: $injected",
            injected.contains("MessageProvenance.TOOL_INJECTION"),
        )
        // 4) It must NOT be routed through the UI-only system card helper, whose
        //    role="system" rows never reach the model.
        assertFalse(
            "must not be a UI-only system card: $injected",
            injected.contains("appendSystemInfo"),
        )
    }

    @Test
    fun `the reminder describes the end reason and hands the restart decision to the main agent`() {
        // (C) KEPT, NOT CONVERTIBLE — same seam as above: the reminder string is
        // assembled inline in `publishDelegatedChildOutcome`, which no JVM test
        // can call. The alternative would be to mirror the wording in the test,
        // which is the assertion equivalent of a copy of the code: it would go on
        // passing after production stopped producing the sentence.
        val injected = reminderText(dispatchBlock(source()))

        // The requirement is "supporting information so the parent can judge".
        // A reminder that only says "abnormal" without the reason, or that issues
        // an order instead of leaving the decision, would not serve that.
        assertTrue("must name the end reason: $injected", injected.contains("end_reason="))
        assertTrue("must name the end reason field: $injected", injected.contains("ended.endReason.name"))
        assertTrue(
            "must hand the restart decision back: $injected",
            injected.contains("whether to restart"),
        )
        assertTrue(
            "must warn the output may be incomplete: $injected",
            injected.contains("possibly incomplete"),
        )
        // The completion tool name must come from the protocol object, not a
        // re-typed literal that could drift from the actual tool.
        assertTrue(
            "must reference the protocol's tool constant: $injected",
            injected.contains("ChildCompletionProtocol.TOOL_NAME"),
        )
    }

    @Test
    fun `a child that declared completion carries no reminder`() {
        // (C) KEPT, NOT CONVERTIBLE — what this really pins is a property of the
        // ChatViewModel call site: the injection is conditional, and there is
        // exactly one of them. The CONDITION itself is covered behaviourally by
        // the last test in this file (`completedNaturally` false for
        // NO_TOOL_CALL, true for COMPLETION_TOOL), but "the guard is applied, and
        // applied once" is a fact about a private suspend function of a ViewModel
        // that no unit test here can construct. `SystemRowDispatchTest`-style
        // render tests cannot see it either: they start from a row that already
        // exists.
        val block = dispatchBlock(source())

        // The polarity must be "abnormal only". If the guard were inverted or
        // dropped, every healthy delegation would be flagged to the main agent,
        // which is the mirror-image defect.
        assertTrue(
            "the injection must be conditional on NOT completing naturally",
            block.contains("childResult?.takeIf { !it.completedNaturally }"),
        )
        assertTrue(
            "the natural-end predicate must come from the result, not a re-derived guess",
            block.contains("completedNaturally"),
        )
        // Guard against a second, unconditional injection being added later.
        assertEquals(
            "exactly one reminder injection site",
            1,
            Regex("childResult\\?\\.takeIf \\{ !it\\.completedNaturally \\}").findAll(block).count(),
        )
    }

    @Test
    fun `the natural-end flag the guard reads is the protocol's own verdict`() {
        // (A) ALREADY BEHAVIOURAL — kept as written. This constructs the real
        // result object and executes the real predicate, so it is evidence and
        // not a text pin; it is the one assertion in this file that would go red
        // if `completedNaturally` stopped being derived from the end reason.
        //
        // The guard above is only as good as `completedNaturally`, so pin that it
        // is derived from the end reason rather than from transport success.
        val result = RuntimeChildExecutionResult(
            childSessionId = "child",
            model = RuntimeModelSnapshot(provider = "p", model = "m"),
            modelSnapshot = com.openminis.app.data.model.ModelAttributionSnapshot(
                modelId = "m",
                displayName = "M",
                providerTypeRaw = "p",
                providerInstanceId = "i",
            ),
            output = "out",
            endReason = ChildEndReason.NO_TOOL_CALL,
        )
        assertFalse("a child that never completed must not look natural", result.completedNaturally)

        val natural = result.copy(endReason = ChildEndReason.COMPLETION_TOOL)
        assertTrue("only the completion tool is a natural end", natural.completedNaturally)
    }
}
