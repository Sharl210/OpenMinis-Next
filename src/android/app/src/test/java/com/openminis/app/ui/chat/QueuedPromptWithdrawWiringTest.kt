package com.openminis.app.ui.chat

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wiring guards for the queued-message withdraw affordances
 * (plans/ULW-2026-09-25-01/request.md:158 撤回并编辑 and :174 撤回).
 *
 * These two defects were only ever reachable through a live ViewModel + Room + a
 * composed bubble, so there is no pure-JVM seam for "the button is gone from the
 * screen" or "the refusal is on screen". What CAN be pinned here is the shape of
 * the wiring — which expression decides visibility, and whether the Boolean result
 * is consumed — because that is exactly where both bugs lived:
 *
 *   1. visibility was driven by a hand-set `isQueued` that the claim never cleared,
 *      so a prompt the model was already reading still offered 撤回/撤回并编辑;
 *   2. both callbacks dropped `withdrawQueuedMessage(...)` / `discardQueuedMessage(...)`'s
 *      result on the floor, so a refused tap produced no feedback at all.
 *
 * The rules themselves are behaviour-tested in [QueuedPromptLifecycleUiTest];
 * this class pins that production consults them. Text matching is weaker than a
 * runtime assertion — that weakness is inherent to the missing seam, and each
 * assertion below is written to fail if the old expression comes back.
 */
class QueuedPromptWithdrawWiringTest {

    private fun source(relative: String): String {
        val candidates = listOf(
            File(relative),
            File("app/$relative"),
            File("upstream/src/android/app/$relative"),
            File("../$relative"),
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("$relative not found; run from the module or repo root")
    }

    /** Slice `from` (inclusive) up to the next occurrence of `to` at/after it. */
    private fun slice(full: String, from: String, to: String): String {
        val start = full.indexOf(from)
        assertTrue("missing marker: $from", start >= 0)
        val end = full.indexOf(to, start + from.length)
        assertTrue("missing end marker '$to' after '$from'", end > start)
        return full.substring(start, end)
    }

    /**
     * The *whole* declaration of `fun name(...)` — signature and body.
     *
     * Why this exists: `slice(full, "fun name(", "<some line in the file>")` bounds
     * a window by an unrelated piece of text, so its content is an accident of edit
     * history rather than a property of the code. Measured on `sendMessage`: the
     * window bounded by `val trimmed = text.trim()` was 18 lines (8004–8022) and
     * the first `enqueuePrompt(text)` call was at 8025 — three lines outside it.
     * So `assertFalse(… contains("enqueuePrompt"))` could never observe the thing
     * it claimed to forbid, while merely moving a pure expression
     * (`val trimmed = text.trim()`) one line up would have turned it red without
     * any behaviour changing.
     *
     * Here the end is derived from the language: brace matching from the
     * declaration, on text with comments and string/char literal bodies blanked,
     * so a `}` inside a literal cannot close the block early.
     */
    private fun ktBlock(full: String, decl: String): String {
        val marker = if (decl.startsWith("fun ")) decl else "fun $decl"
        val start = full.indexOf(marker)
        assertTrue("missing declaration: $marker", start >= 0)
        val scanned = blankCommentsAndLiterals(full.substring(start))
        val open = scanned.indexOf('{')
        assertTrue("'$marker' has no body block", open >= 0)
        var depth = 0
        for (i in open until scanned.length) {
            when (scanned[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return full.substring(start, start + i + 1)
                }
            }
        }
        throw AssertionError("unbalanced braces in '$marker'")
    }

    /**
     * Blanks the *bodies* of comments and string/char literals, preserving offsets
     * and newlines. Braces, parentheses and commas never survive from a comment or
     * a literal, so they cannot be mistaken for syntax by [ktBlock]; the literals
     * themselves remain readable in the original text that callers print.
     */
    private fun blankCommentsAndLiterals(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val next = text.getOrNull(i + 1)
            when {
                c == '/' && next == '/' -> {
                    while (i < text.length && text[i] != '\n') {
                        out.append(' ')
                        i++
                    }
                }
                c == '/' && next == '*' -> {
                    out.append("  ")
                    i += 2
                    while (i < text.length && !(text[i] == '*' && text.getOrNull(i + 1) == '/')) {
                        out.append(if (text[i] == '\n') '\n' else ' ')
                        i++
                    }
                    if (i < text.length) {
                        out.append("  ")
                        i += 2
                    }
                }
                c == '"' && text.startsWith("\"\"\"", i) -> {
                    out.append("   ")
                    i += 3
                    while (i < text.length && !text.startsWith("\"\"\"", i)) {
                        out.append(if (text[i] == '\n') '\n' else ' ')
                        i++
                    }
                    if (i < text.length) {
                        out.append("   ")
                        i += 3
                    }
                }
                c == '"' || c == '\'' -> {
                    val quote = c
                    out.append(' ')
                    i++
                    while (i < text.length && text[i] != quote && text[i] != '\n') {
                        if (text[i] == '\\') {
                            out.append(' ')
                            i++
                        }
                        out.append(' ')
                        i++
                    }
                    if (i < text.length && text[i] == quote) {
                        out.append(' ')
                        i++
                    }
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /** 1-based line number of [offset] in [text]. */
    private fun lineOf(text: String, offset: Int): Int =
        text.substring(0, offset.coerceIn(0, text.length)).count { it == '\n' } + 1

    /** The body of a declaration sliced with [ktBlock], from its opening brace. */
    private fun bodyOf(declaration: String): String {
        val open = blankCommentsAndLiterals(declaration).indexOf('{')
        assertTrue("no body block in ${declaration.take(60)}", open >= 0)
        return declaration.substring(open)
    }

    private val chatViewModel: String by lazy { source("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt") }
    private val chatScreen: String by lazy { source("src/main/java/com/openminis/app/ui/chat/ChatScreen.kt") }
    private val bubbleUi: String by lazy { source("src/main/java/com/openminis/app/ui/chat/ChatUserMessageUI.kt") }

    @Test
    fun `claiming a prompt takes the withdraw affordances down with it`() {
        val claim = slice(
            chatViewModel,
            "private fun claimQueued(delivery: QueuedPromptDelivery)",
            "private fun rollbackQueued(",
        )

        assertTrue(
            "claimQueued must republish the bubble flag from the ledger; a claimed prompt is " +
                "already being read and must stop offering 撤回并编辑",
            claim.contains("mirrorQueuedWithdrawable("),
        )
        assertFalse(
            "claimQueued must not hand-set the bubble flag — that is the drift that left the " +
                "buttons on a prompt the ledger refuses to withdraw",
            claim.contains("isQueued"),
        )
        assertTrue(
            "the bubble flag must be the ledger's verdict projected through the shared policy",
            chatViewModel.contains("QueuedPromptUiPolicy.isWithdrawable(queuedPromptLedger"),
        )
    }

    @Test
    fun `rollback and consume republish the flag from the ledger too`() {
        val rollback = slice(chatViewModel, "private fun rollbackQueued(", "private fun consumeQueued(")
        val consume = slice(chatViewModel, "private fun consumeQueued(", "/** Remove only an unconsumed prompt")

        assertTrue(
            "a rolled-back claim is ENQUEUED again, so rollback must restore the affordances " +
                "through the same projection",
            rollback.contains("mirrorQueuedWithdrawable("),
        )
        assertTrue(consume.contains("mirrorQueuedWithdrawable("))
    }

    @Test
    fun `a refused withdraw is reported instead of dropping the result`() {
        val notice = slice(
            chatScreen,
            "val reportQueuedWithdraw: (Boolean) -> Boolean",
            "// User bubbles intentionally don't register",
        )
        assertTrue(
            "the refusal must reach the user — chat_queued_withdraw_failed is the string for it",
            notice.contains("R.string.chat_queued_withdraw_failed"),
        )
        assertTrue("the notice must actually be shown", notice.contains("showSnackbar"))
        assertTrue(
            "the mapping from result to outcome must come from the shared policy",
            notice.contains("QueuedPromptUiPolicy.withdrawOutcome("),
        )

        val callbacks = slice(chatScreen, "onWithdraw = if (messageWithdrawable)", "onPreviewFile = { uri, name ->")
        assertTrue(
            "onWithdraw must consume withdrawQueuedMessage's result",
            callbacks.contains("reportQueuedWithdraw(viewModel.withdrawQueuedMessage(item.message.id))"),
        )
        assertTrue(
            "onDiscardQueued must consume discardQueuedMessage's result",
            callbacks.contains("reportQueuedWithdraw(viewModel.discardQueuedMessage(item.message.id))"),
        )
        assertFalse(
            "the old form discarded the result — a refused 撤回 changed nothing on screen",
            chatScreen.contains("safeMutate { viewModel.discardQueuedMessage(item.message.id) }"),
        )
        assertFalse(
            "the old form swallowed a refused 撤回并编辑",
            chatScreen.contains("safeMutate { if (viewModel.withdrawQueuedMessage(item.message.id))"),
        )
    }

    @Test
    fun `bubble action visibility is decided in one place`() {
        assertTrue(
            "Retry / Edit / Delete-from-here must be gated by the shared policy",
            chatScreen.contains("QueuedPromptUiPolicy.canActOnSentMessage("),
        )
        assertFalse(
            "three call sites re-derived the gate inline before this; keeping one copy means " +
                "the policy cannot drift from the menu",
            chatScreen.contains("isStreaming || item.message.isQueued"),
        )
        assertTrue(
            "撤回并编辑 / 撤回 hang off the bubble flag the claim mirrors",
            chatScreen.contains("onWithdraw = if (messageWithdrawable)"),
        )
    }

    @Test
    fun `committing an edit reaches neither the retry nor the delete machinery`() {
        // request.md:158 asks for three things at once: the send button must not
        // really send, must not auto-press Retry ("重试按钮是要把下面的消息都会被冲掉嘛,
        // 所以我们就是这就是个危险动作,我们不自动去做"), and must not drop the
        // messages after the edited one.
        //
        // This used to be "guarded" by three `get() = false` markers on
        // EditCommitResult that no production code read — always true, so they
        // could not fail. A guard that cannot fail is not a guard. This one reads
        // the real commit body and fails if the machinery is ever wired in.
        val commit = slice(
            chatViewModel,
            "private fun commitEditIfActive(text: String): Boolean {",
            "private fun guessMimeType(",
        )
        for (dangerous in listOf("retryFromMessage", "retryLast", "deleteFromHere", "runAgentLoop", "enqueuePrompt")) {
            assertFalse(
                "commitEditIfActive must not reach $dangerous — an edit is an in-place write-back, " +
                    "never a send, a retry, or a deletion of later messages",
                commit.contains(dangerous),
            )
        }
        assertTrue(
            "…and it must actually write the edit back in place",
            commit.contains("replaceUserMessageInPlace("),
        )
    }

    @Test
    fun `editing never dispatches a provider turn`() {
        // The window used to be `slice(chatViewModel, "private fun sendMessage(…",
        // "val trimmed = text.trim()")`. That is 18 lines (8004–8022) and stops
        // three lines *before* the first `enqueuePrompt(text)` call at 8025, so
        // the old `assertFalse(send contains "enqueuePrompt")` could not fail for
        // the reason it exists. Worse, the window's lower bound was a *pure
        // expression* the author happened to write there: moving
        // `val trimmed = text.trim()` one line up shrank the window to 8004–8005
        // (measured) and turned the test red with no behaviour change at all.
        //
        // Two design notes, both deliberate:
        //
        //  1. The guard is matched as *shape*, not spelling:
        //     `if (<something>(text)) return`. Renaming `commitEditIfActive` keeps
        //     this green, which is right — a rename is not a broken guard.
        //  2. "Nothing runs before the guard" was too strong (a hoisted
        //     `val x = text.trim()` is a call and changes nothing), so what is
        //     pinned is exactly the property this test claims: the guard is ahead
        //     of every *dispatch or enqueue entry point*. Those entries are named
        //     because they are the seams this class already names elsewhere — a
        //     local variable's spelling never is.
        val send = ktBlock(chatViewModel, "sendMessage(text: String, skipContextCheck: Boolean)")
        val body = bodyOf(send)
        // Matched on the comment/string-free mask: a commented-out guard is prose.
        // (A fake-tree probe caught the first version of this line matching a
        // comment — deleting the real guard while leaving the line behind stayed
        // green, which is the same failure mode this whole test was rewritten for.)
        val masked = blankCommentsAndLiterals(send)

        val guard = Regex("if \\(\\s*[A-Za-z_][A-Za-z0-9_.]*\\(text\\)\\s*\\)\\s*return\\b").find(masked)
        assertTrue(
            "request.md:158 — tapping send while editing must write the edit back and bail out, " +
                "not send. No executable `if (<predicate>(text)) return` guard found in " +
                "sendMessage's body; removing it lets an edit fall through to the dispatch path " +
                "below.",
            guard != null,
        )
        val guardAt = guard!!.range.first

        // `enqueuePrompt` is the one the old window missed by three lines; the
        // others are the dispatch seams a falling-through send can reach. Each is
        // pinned by *position*, which is the property, not by absence.
        for (entry in listOf("enqueuePrompt(", "runAgentLoop(", "retryFromMessage(")) {
            // Masks, not the raw body: a commented-out `enqueuePrompt(` is not a
            // reachable queueing site, and counting it would make this red for a
            // note instead of for behaviour.
            val at = masked.indexOf(entry)
            if (at < 0) continue
            assertTrue(
                "the edit guard must precede $entry — the guard sits at line " +
                    "${lineOf(send, guardAt)} but $entry is at line ${lineOf(send, at)}; a guard " +
                    "behind it would still let an edit be queued or dispatched as a new turn",
                guardAt < at,
            )
        }
    }

    @Test
    fun `editing is never hijacked by a slash command`() {
        val slash = slice(
            chatViewModel,
            "fun tryExecuteInputAsSlashCommand(text: String): Boolean {",
            "val trimmed = text.trim()",
        )
        assertTrue(
            "both ChatScreen send paths intercept slash commands before sendMessage, so the " +
                "commit guard alone cannot protect the edit — while editing a send must commit",
            slash.contains("if (_editingMessageId.value != null) return false"),
        )
    }

    @Test
    fun `the edit-mode send button does not read as Send`() {
        assertTrue(
            "request.md:158 — the icon becomes a push-up glyph while editing",
            chatScreen.contains("R.string.chat_edit_push_back"),
        )
        assertTrue(
            "…and the label follows, so a screen reader is not told the edit will be sent",
            chatScreen.contains("if (editingId != null) Icons.Default.VerticalAlignTop"),
        )
    }

    @Test
    fun `the failure string exists in the default and Chinese resources`() {
        assertTrue(
            source("src/main/res/values/strings.xml").contains("name=\"chat_queued_withdraw_failed\""),
        )
        assertTrue(
            source("src/main/res/values-zh/strings.xml").contains("name=\"chat_queued_withdraw_failed\""),
        )
    }

    @Test
    fun `the inline bubble cancel announces the action it performs`() {
        assertTrue(
            "the ✕ runs onWithdraw (withdraw-and-edit), so it must not announce the discard string",
            bubbleUi.contains("contentDescription = stringResource(R.string.chat_queued_withdraw_and_edit)"),
        )
    }
}
