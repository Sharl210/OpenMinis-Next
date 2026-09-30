package com.openminis.app.ui.chat

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavior coverage for the conversation map's SEARCH SEMANTICS and for the
 * jump it performs when a row is tapped — the four semantics the acceptance
 * ledger recorded as never covered:
 *
 *  1. whether attachments / tool blocks / thinking text can be found at all;
 *  2. what a very long single message does to search and to the row preview;
 *  3. what a jump does when its target was deleted or the list is being
 *     rewritten by a streaming turn;
 *  4. whether a jump that cannot land tells the user anything.
 *
 * Requirement: `plans/ULW-2026-09-25-01/request.md:21` — 「…也要加一个搜索绘画，
 * 就是或者叫做对话地图…点进去以后就是把所有的进行折叠起来，就是一行对话一行对话
 * 的那种感觉」. A map whose rows can only be found by their `content` does not
 * satisfy "one line per turn" for turns whose text lives elsewhere (an
 * image-only send has a blank `content` by design — see
 * `ChatViewModel.retryFromMessage`).
 *
 * Everything here drives the real production functions. Compose itself is not
 * reachable from `src/test` (no Robolectric in this module), so the two things
 * that live purely in composition — the snackbar call site and the `remember`
 * that memoises the index — are covered by the pure decisions they branch on:
 * [planConversationMapJump] and [conversationMapRowIndexWithin]. That split is
 * deliberate: this file asserts observable behavior of the logic, never the
 * text of a source file.
 */
class ConversationMapSearchCoverageTest {

    private fun userMessage(
        id: String,
        content: String = "",
        attachments: List<String> = emptyList(),
    ): ChatMessage = ChatMessage(
        id = id,
        role = "user",
        content = content,
        attachmentNames = attachments,
    )

    private fun block(
        id: String,
        kind: String = "tool_use",
        name: String = "",
        title: String = "",
        args: String = "",
        content: String = "",
    ): AssistantBlock = AssistantBlock(
        id = id,
        kind = kind,
        content = content,
        toolName = name,
        toolTitle = title,
        toolArgs = args,
    )

    private fun matchedIds(messages: List<ChatMessage>, query: String): List<String> =
        filterConversationMapItems(messages, query).map { it.id }

    // ------------------------------------------------------------------
    // (a) which fields a search can reach
    // ------------------------------------------------------------------

    @Test
    fun `an attachment-only turn is found by its file name`() {
        val message = userMessage("m1", content = "", attachments = listOf("quarterly-report.pdf"))

        assertEquals(listOf("m1"), matchedIds(listOf(message), "quarterly-report.pdf"))
        // case-insensitive, matching the pre-existing search contract
        assertEquals(listOf("m1"), matchedIds(listOf(message), "REPORT.PDF"))
        // and the row itself is no longer blank: "(empty message)" is gone
        assertEquals(
            "quarterly-report.pdf",
            filterConversationMapItems(listOf(message), "").single().preview,
        )
    }

    @Test
    fun `tool name title arguments and output are all searchable`() {
        val message = ChatMessage(
            id = "a1",
            role = "assistant",
            content = "",
            toolBlocks = listOf(
                block(
                    id = "t1",
                    name = "shell_execute",
                    title = "Run shell command",
                    args = """{"command":"du -sh /var/minis"}""",
                    content = "1.2G\t/var/minis\nDISTINCT-TOOL-OUTPUT-MARKER",
                ),
            ),
        )
        val messages = listOf(message)

        assertEquals(listOf("a1"), matchedIds(messages, "shell_execute"))
        assertEquals(listOf("a1"), matchedIds(messages, "run shell command"))
        assertEquals(listOf("a1"), matchedIds(messages, "du -sh")) // raw JSON args
        assertEquals(listOf("a1"), matchedIds(messages, "distinct-tool-output-marker"))
    }

    @Test
    fun `thinking text is searchable`() {
        val message = ChatMessage(
            id = "a2",
            role = "assistant",
            content = "final answer",
            toolBlocks = listOf(
                block(id = "th1", kind = "thinking", title = "Thinking", content = "REASONING-MARKER-9"),
            ),
        )

        assertEquals(listOf("a2"), matchedIds(listOf(message), "reasoning-marker-9"))
    }

    @Test
    fun `role ordinals and whitespace-collapsed matches are unchanged`() {
        val messages = listOf(
            userMessage("m1", content = "hello\nworld"),
            ChatMessage(id = "m2", role = "assistant", content = "second"),
            ChatMessage(id = "m3", role = "assistant", content = "third"),
        )

        // a query that spans the stored newline keeps matching
        assertEquals(listOf("m1"), matchedIds(messages, "hello world"))
        // role and ordinal matching are preserved
        assertEquals(listOf("m2", "m3"), matchedIds(messages, "assistant"))
        assertEquals(listOf("m2"), matchedIds(messages, "2"))
        // a blank query returns every message, in order
        assertEquals(listOf("m1", "m2", "m3"), matchedIds(messages, "   "))
        // and an unmatched query returns nothing (the "no matches" empty state)
        assertEquals(emptyList<String>(), matchedIds(messages, "no-such-text-anywhere"))
    }

    @Test
    fun `matches beyond the truncated preview are still found`() {
        val message = userMessage("m4", content = "prefix " + "x".repeat(400) + " TAIL-MARKER")

        val row = filterConversationMapItems(listOf(message), "tail-marker").single()

        assertEquals("m4", row.id)
        assertTrue("preview must stay truncated", row.preview.length <= 180)
    }

    // ------------------------------------------------------------------
    // (b) very long single message
    // ------------------------------------------------------------------

    @Test
    fun `a long message stays fully searchable while its preview stays bounded`() {
        // The OOM/卡顿 half of the ledger question was answered by reading the
        // implementation (no length cap, no chunking: the haystack is the whole
        // string, the renderer only ever sees the preview) — deliberately NOT by
        // materialising a 100k-character message here. What this pins is the
        // contract that a cap would break: text far past the preview window, and
        // past any plausible 4K/8K/16K slice, is still findable.
        val body = "a".repeat(20_000)
        val message = userMessage("big", content = body + " END-OF-LONG-MESSAGE")

        val row = filterConversationMapItems(listOf(message), "end-of-long-message").single()

        assertEquals("big", row.id)
        assertEquals(180, row.preview.length)
    }

    // ------------------------------------------------------------------
    // (c) deleted target / list changing under the jump
    // ------------------------------------------------------------------

    @Test
    fun `a jump whose target was deleted is resolved as gone, not as a scroll elsewhere`() {
        val messages = (1..5).map { userMessage("m$it", content = "line $it") }

        assertEquals(
            ConversationMapJumpPlan.TargetGone,
            planConversationMapJump(messages, "deleted-while-map-open", visibleCap = 200, capStep = 100),
        )
    }

    @Test
    fun `a jump to a target outside the window asks for exactly the pages that bring it in`() {
        // 400 messages, 200-message window, 100-message step, target at index 50:
        // the window must reach 400 - 50 = 350, i.e. two 100-message bumps.
        val messages = (0 until 400).map { userMessage("m$it", content = "line $it") }

        val plan = planConversationMapJump(messages, "m50", visibleCap = 200, capStep = 100)

        assertTrue("expected Ready, got $plan", plan is ConversationMapJumpPlan.Ready)
        plan as ConversationMapJumpPlan.Ready
        assertEquals(50, plan.targetIndex)
        assertEquals(350, plan.requiredCap)
        assertEquals(2, plan.pagesToLoad)
        assertTrue(
            "paging must actually cover the target",
            200 + plan.pagesToLoad * 100 >= plan.requiredCap,
        )
    }

    @Test
    fun `a jump inside the window needs no paging`() {
        val messages = (0 until 400).map { userMessage("m$it", content = "line $it") }

        val plan = planConversationMapJump(messages, "m399", visibleCap = 200, capStep = 100)

        assertTrue("expected Ready, got $plan", plan is ConversationMapJumpPlan.Ready)
        plan as ConversationMapJumpPlan.Ready
        assertEquals(399, plan.targetIndex)
        assertEquals(1, plan.requiredCap)
        assertEquals(0, plan.pagesToLoad)
    }

    // ------------------------------------------------------------------
    // (d) a jump that cannot land must end, and must be reportable
    // ------------------------------------------------------------------

    @Test
    fun `row lookup gives up instead of waiting forever when no row ever matches`() = runBlocking {
        // Models the live list: rows are published asynchronously and the flow
        // never completes, so "wait until some row matches" has nothing to
        // terminate it except a deadline. Before the bound existed this call
        // suspended for the rest of the screen's life — the outer withTimeout
        // below is what turns that into a visible failure instead of a hang.
        val rows = MutableSharedFlow<List<FlatChatItem>>(replay = 1, extraBufferCapacity = 1)
        rows.tryEmit(listOf(FlatChatItem.AssistantHeader("some-other-message")))

        val index = withTimeout(4_000) {
            conversationMapRowIndexWithin(rows, "never-rendered", timeoutMs = 250)
        }

        assertEquals(-1, index)
    }

    @Test
    fun `row lookup returns the displayed index as soon as the row appears`() = runBlocking {
        val older = ChatMessage("older", "user", "older text")
        val displayed = listOf(
            FlatChatItem.AssistantLegacyContent("target", "target text", false),
            FlatChatItem.AssistantHeader("target"),
            FlatChatItem.UserBubble(older),
        )
        val rows = MutableSharedFlow<List<FlatChatItem>>(replay = 1, extraBufferCapacity = 1)
        rows.tryEmit(displayed.filterNot { it.messageIdOrNull() == "target" })
        launch {
            delay(50)
            rows.emit(displayed)
        }

        val index = withTimeout(4_000) {
            conversationMapRowIndexWithin(rows, "target", timeoutMs = 3_000)
        }

        assertEquals(0, index)
    }

    @Test
    fun `a blank target id never waits`() = runBlocking {
        val rows = MutableSharedFlow<List<FlatChatItem>>(replay = 1, extraBufferCapacity = 1)

        val index = withTimeout(4_000) {
            conversationMapRowIndexWithin(rows, "", timeoutMs = 3_000)
        }

        assertEquals(-1, index)
    }
}
