package com.openminis.app.ui.sessions

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural guard for the user-facing recursive delete: every entry point must
 * reach the one recursive helper, that helper must clean the runtime tree as
 * well as the chat rows, and the owner-only purge must stay off the agent tool
 * surface.
 *
 * The behaviour itself is pinned elsewhere (`SessionSubtreeDeletionPlanTest` and
 * `SessionSubtreeDeletionPipelineTest` on the JVM, `SessionSubtreeDeletion`
 * instrumented tests against Room and a real tree). What this file adds is what
 * those cannot see: that a *fourth* entry point added later routes through the
 * same path instead of quietly deleting a single row — which is exactly how the
 * recursive half went missing in the first place.
 */
class SessionSubtreeDeletionWiringSourceTest {

    private fun source(relative: String): String {
        val file = File("src/main/java/$relative").takeIf { it.isFile }
            ?: File("app/src/main/java/$relative")
        assertTrue("source file missing: $relative", file.isFile)
        return file.readText()
    }

    private fun viewModel(): String =
        source("com/openminis/app/ui/sessions/SessionListViewModel.kt")

    private fun wiring(): String =
        source("com/openminis/app/ui/sessions/SessionSubtreeDeletionWiring.kt")

    /** The production source root, resolved the same way [source] resolves a file. */
    private fun mainRoot(): File =
        sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull { it.isDirectory }
            ?: throw AssertionError("main source root not found; run from the module or repo root")

    /**
     * Blanks the bodies of comments — and, when [blankLiterals] is true, also the
     * bodies of string/char literals. Both keep offsets and newlines.
     *
     * Two masks are needed, and they are not interchangeable:
     *   * *calls* must be read with literals blanked, so a KDoc or a log string that
     *     merely mentions the name is not mistaken for reachability;
     *   * the *dispatch site* is found by the tool's own name literal, which only
     *     exists if literals are kept.
     */
    private fun blank(text: String, blankLiterals: Boolean): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val next = text.getOrNull(i + 1)
            when {
                c == '/' && next == '/' -> while (i < text.length && text[i] != '\n') {
                    out.append(' ')
                    i++
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
                !blankLiterals -> {
                    out.append(c)
                    i++
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

    /** Comment bodies blanked, literals kept — for finding names the code quotes. */
    private fun noComments(text: String): String = blank(text, blankLiterals = false)

    /** Comment *and* literal bodies blanked — for deciding what the code calls. */
    private fun codeOf(text: String): String = blank(text, blankLiterals = true)

    private fun lineOf(text: String, offset: Int): Int =
        text.substring(0, offset.coerceIn(0, text.length)).count { it == '\n' } + 1

    /** Every `.kt` under the production source root, as (root-relative path, text). */
    private fun allProductionSources(): List<Pair<String, String>> {
        val root = mainRoot()
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(root).invariantSeparatorsPath to it.readText() }
            .sortedBy { it.first }
            .toList()
    }

    /**
     * The `relative:line` of every *reference* to the identifier [name] that could
     * reach the symbol — the declaration (`fun name(`) and prose excluded.
     *
     * Deliberately not restricted to `name(`. Two escapes were measured on fake
     * trees against narrower versions of this scanner, and both stayed green:
     *
     *   * a *function reference* — `val f = coordinator::purgeSubtrees; f(…)` reaches
     *     the owner-only entry point with no parentheses next to the name;
     *   * an *import alias* — `import …RuntimeSessionCoordinator.purgeSubtrees as zap`
     *     then `zap(c, ids, …)`, where the call site does not spell the name at all.
     *
     * Import lines are therefore counted, not skipped: in Kotlin a same-package
     * alias is impossible, so an aliased call requires an import that names the
     * symbol — and a file on the agent tool surface importing the owner-only purge
     * is precisely what this test exists to forbid.
     */
    private fun callSites(relative: String, text: String, name: String): List<String> {
        val code = codeOf(text)
        return Regex("\\b" + Regex.escape(name) + "\\b").findAll(code).mapNotNull { m ->
            val before = code.substring(0, m.range.first)
            val token = before.trimEnd().takeLastWhile { !it.isWhitespace() }
            val line = lineOf(text, m.range.first)
            // `fun name(` — the declaration itself, not a use.
            if (token == "fun") null else "$relative:$line"
        }.toList()
    }

    /**
     * (C) KEPT, NOT CONVERTIBLE — the call graph of the three entry points.
     *
     * `deleteSession`, `deleteSelected` and `deleteFolderWithSessions` are members
     * of `SessionListViewModel`: constructing it needs a Room `ChatDao`, the
     * Compose-driven session list and a live Android main dispatcher. This module's
     * unit-test source set has no Robolectric, and no test in the repo builds a
     * `SessionListViewModel` instance (only the companion's pure helpers are
     * exercised, as in `GroupSuggestionParseTest`), so "this entry point routes
     * through the recursive helper" cannot be observed — only read.
     *
     * Kept rather than dropped because it is the sole guard on the regression that
     * started this feature: a *fourth* entry point added later deleting a single
     * row instead of routing through `deleteSessionSubtrees`. The behaviour on the
     * other side of that call is pinned by `SessionSubtreeDeletionPlanTest` and
     * `SessionSubtreeDeletionPipelineTest`.
     *
     * This is the authoritative copy. `SessionDeletionCleanupSourceTest` used to
     * assert a subset of these same six literals; that copy was removed and folded
     * in here, because two copies of one rule count it twice — and, as that pair
     * had already done, drift apart.
     */
    @Test
    fun `every user delete entry point routes through the recursive helper`() {
        val text = viewModel()
        assertTrue("recursive cleanup helper missing", text.contains("deleteSessionSubtrees"))
        assertTrue(
            "single delete bypasses the recursive helper",
            Regex("fun deleteSession\\(id: String\\).*?deleteSessionSubtrees\\(listOf\\(id\\)\\)", RegexOption.DOT_MATCHES_ALL)
                .containsMatchIn(text),
        )
        assertTrue(
            "bulk delete bypasses the recursive helper",
            Regex("fun deleteSelected\\(\\).*?deleteSessionSubtrees\\(ids\\)", RegexOption.DOT_MATCHES_ALL)
                .containsMatchIn(text),
        )
        assertTrue(
            "group delete bypasses the recursive helper",
            Regex("fun deleteFolderWithSessions\\(folderId: String\\).*?deleteSessionSubtrees\\(memberIds\\)", RegexOption.DOT_MATCHES_ALL)
                .containsMatchIn(text),
        )
        assertTrue(
            "delete set no longer resolved from the runtime topology",
            text.contains("resolveSessionSubtreeDeletionPlan("),
        )
        assertTrue(
            "a session the last attempt left unfinished is no longer retried",
            text.contains("retryStore.ids()"),
        )
    }

    @Test
    fun `an unreadable runtime tree aborts the delete instead of silently deleting one row`() {
        val text = viewModel()
        val code = codeOf(text)
        // The original defect was "deleting a conversation removes only that
        // conversation". A degraded path that deletes the conversation while the
        // descendants are unknowable reproduces it, so that path must not exist.
        //
        // This used to be `Regex("topology\\.isFailure.*?return@withContext",
        // DOT_MATCHES_ALL)`, whose `.*?` happily crosses anything — including a
        // silent downgrade to a single-row delete. Measured on a fake tree:
        // inserting `store.deleteSubtree(id)` between the guard and the
        // `return@withContext` left all 26 tests green, which is the exact defect
        // the assertion was written for.
        //
        // So the abort branch is delimited with brace matching instead, and the
        // assertion is about what that branch *does*: it logs and returns, and it
        // reaches no deletion surface first.
        val guardAt = code.indexOf("if (topology.isFailure)")
        assertTrue("no unreadable-topology guard", guardAt >= 0)
        val open = code.indexOf('{', guardAt)
        assertTrue("the guard has no branch body", open >= 0)
        var depth = 0
        var close = -1
        for (i in open until code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        close = i
                        break
                    }
                }
            }
        }
        assertTrue("unbalanced braces in the isFailure guard", close > open)
        val branch = text.substring(open, close)
        val branchCalls = Regex("[A-Za-z_][A-Za-z0-9_.]*\\s*\\(")
            .findAll(codeOf(branch)).map { it.value.trimEnd(' ', '(') }.toSet()
        val deletionSurface = branchCalls.filter { call ->
            listOf("delete", "remove", "purge", "clear", "store", "repository", "dao")
                .any { call.substringAfterLast('.').lowercase().contains(it) }
        }
        assertTrue(
            "the unreadable-topology branch must not reach any deletion surface before it " +
                "bails out — deleting the conversation while its descendants are unknowable is " +
                "the original defect. Calls found in the branch: $branchCalls; " +
                "deletion-shaped: $deletionSurface",
            deletionSurface.isEmpty(),
        )
        assertTrue(
            "the branch must actually return, or it only logs and falls through to the delete",
            branch.contains("return@withContext"),
        )

        assertTrue(
            "the abort has to be visible in the logs",
            text.contains("subtree delete aborted: runtime topology unreadable"),
        )
    }

    @Test
    fun `the wired cleanup covers the chat rows, the runtime tree and the artifacts`() {
        val text = wiring()
        assertTrue("atomic subtree row delete missing", text.contains("deleteSessionSubtree(live)"))
        // Was `text.contains("purgeSubtrees(nodeIds")` — a literal that pins the
        // *local variable's* spelling. Renaming `nodeIds` to anything else (a pure
        // refactor) turned it red, measured; the property it was after is "this
        // wiring actually calls the owner-only purge, and uses the result", which is
        // what [callSites] + the result-binding check below state without naming a
        // local. The caller-identity assertion in the test below covers the same
        // call site from the other side.
        val purgeCalls = callSites("com/openminis/app/ui/sessions/SessionSubtreeDeletionWiring.kt", text, "purgeSubtrees")
        assertEquals(
            "the wired cleanup must reach the runtime purge exactly once: $purgeCalls",
            1,
            purgeCalls.size,
        )
        assertTrue(
            "the purge's receipt must be bound to a val and checked — an unchecked purge result " +
                "is how 'the tree was not persisted' becomes silent data loss",
            Regex("val\\s+\\w+\\s*=\\s*[\\w.]*purgeSubtrees\\s*\\(").containsMatchIn(codeOf(text)) ||
                Regex("purgeSubtrees\\s*\\([^)]*\\)\\s*\\?:").containsMatchIn(codeOf(text)),
        )
        assertTrue("media cleanup missing", text.contains("deleteSessionMedia(sessionId)"))
        // Read off the wiring file rather than driven: `OffloadPermissionManager`
        // holds a session's grants in private in-memory maps and exposes no reader
        // (`clearSessionGrants` removes from two maps and returns `Unit`), so a
        // behavioural test could only assert that the call did not throw — which is
        // not evidence. The other seven cleanup steps ARE behavioural; see
        // `SessionDeletionCleanupSourceTest`'s `the wired cleanup deletes the
        // subtree's chat rows, purges its runtime nodes and drops its artifacts`.
        assertTrue(
            "permission cleanup missing",
            text.contains("OffloadPermissionManager.clearSessionGrants(sessionId)"),
        )
        assertTrue("goal cleanup missing", text.contains("GoalRuntimeStore.open(context, sessionId).delete()"))
        assertTrue("communication cleanup missing", text.contains("deleteForSession(sessionId)"))
        assertTrue("view model release missing", text.contains("ChatViewModelStore.release(sessionId)"))
        assertTrue("badge cleanup missing", text.contains("SessionBadgeStore.clear(sessionId)"))
    }

    @Test
    fun `the owner-only runtime purge is unreachable from the agent tool surface`() {
        // R44 line 254: an agent must not be able to delete itself. The guard
        // itself lives in `SessionTreeRuntime.deleteSubtree` (strict-ancestor
        // executor) and refuses agent-issued calls; `purgeSubtrees` exists for the
        // human owner, who is not a node in the tree.
        //
        // This used to be `for (path in listOf(AgentTools.kt, AgentToolExecutor.kt,
        // ChatViewModel.kt)) assertFalse(read(path).contains("purgeSubtrees("))`.
        // That pinned "these three files do not contain this string" and called it
        // "unreachable from the agent tool surface". Measured on a fake tree: adding
        // a *new* file with a real `coordinator.purgeSubtrees(...)` call left it
        // green; so did a new file whose only content was a KDoc mentioning the
        // name; so did a file whose `purgeSubtrees(` was not even valid Kotlin. At
        // the same time `SessionSubtreeDeletionWiring.kt:133` — a production caller
        // that is *not* in the list — could have been deleted without a murmur.
        //
        // What replaces it: the caller set is discovered tree-wide and asserted to
        // be exactly the human-owner path. That makes the first assertion below a
        // built-in positive control (it fails if the scanner stops finding the one
        // real call), so the second cannot pass vacuously.
        val purge = "purgeSubtrees"
        val sources = allProductionSources()
        assertTrue(
            "the scanner read ${sources.size} production .kt files — an empty or truncated " +
                "scan would make every assertion below vacuous",
            sources.size > 100,
        )

        val callers = sources
            .flatMap { (relative, text) -> callSites(relative, text, purge) }
            .sorted()
        assertEquals(
            "the owner-only runtime purge's callers are pinned by identity, not by a " +
                "hand-maintained allow-list of *other* files: a new caller anywhere in this tree " +
                "turns this red. The expected two are the owner chain itself — the session list " +
                "enters through the coordinator, and the coordinator delegates to the runtime. " +
                "Found: $callers",
            listOf(
                "com/openminis/app/feature/runtime/RuntimeSessionCoordinator.kt:701",
                "com/openminis/app/ui/sessions/SessionSubtreeDeletionWiring.kt:133",
            ),
            callers,
        )

        // The agent tool surface, discovered rather than hardcoded: every file in
        // the `tools` package, plus every file that names the agent-facing delete
        // tool (that is how the dispatcher is found without a path list).
        val toolsPackage = sources.filter { "/tools/" in it.first }
        val dispatchFiles = sources.filter { (_, text) ->
            noComments(text).contains("\"delete_subtree\"")
        }
        assertTrue(
            "the `tools` package must exist and be non-trivial, or this test is checking nothing: " +
                "found ${toolsPackage.size} files",
            toolsPackage.size >= 5,
        )
        assertTrue(
            "the agent-facing `delete_subtree` tool must be dispatched from somewhere: found " +
                dispatchFiles.map { it.first },
            dispatchFiles.isNotEmpty(),
        )

        val toolSurface = (toolsPackage + dispatchFiles).distinctBy { it.first }
        val offenders = toolSurface
            .flatMap { (relative, text) -> callSites(relative, text, purge) }
        assertEquals(
            "no part of the agent tool surface may call the owner-only purge — an agent that can " +
                "reach it can delete itself, which is exactly what the strict-ancestor guard in " +
                "`deleteSubtree` exists to prevent. Offending call sites: $offenders",
            emptyList<String>(),
            offenders,
        )

        // …and the guarded entry point is still the one the tool surface uses, so
        // "no purge call here" cannot be satisfied by the tool having no way to
        // delete at all (a regression this file would otherwise not notice).
        val guarded = toolSurface
            .flatMap { (relative, text) -> callSites(relative, text, "deleteSubtree") }
        assertTrue(
            "the agent-facing delete must still route through the guarded `deleteSubtree`, " +
                "otherwise the assertions above pass because deletion was removed entirely: " +
                "surface = ${toolSurface.map { it.first }}",
            guarded.isNotEmpty(),
        )
    }

    // The strict-ancestor guard itself is deliberately NOT pinned as source text
    // here. Two reasons, both learned the hard way:
    //
    //  1. It is behaviour, and behaviour is pinned where it can actually fail:
    //     `RuntimeSessionSubtreePurgeTest` drives a real coordinator and asserts
    //     that an agent deleting itself, deleting the conversation it runs in, and
    //     deleting a conversation in another tree are all still REJECTED. Remove
    //     the guard and those three go red — stronger evidence than any string.
    //  2. String-pinning the guard's *spelling* broke twice on changes that kept
    //     the guard alive: first when the ownership predicate was renamed to
    //     `isWithinConversationScope`, then again when `deleteSubtree` moved from a
    //     bare `executor` to `request.executorNodeId`. Both times the red told us
    //     nothing about whether an agent could still delete itself.
    //
    // What remains pinned above is the part behaviour tests cannot see: that a
    // fourth entry point added later routes through the recursive helper instead
    // of quietly deleting one row, and that the owner-only purge stays off the
    // agent tool surface.
}
