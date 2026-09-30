package com.openminis.app.feature.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-goal-auto-continuation] Wiring pins for `/goal`'s unattended
 * auto-advance (`plans/ULW-2026-09-25-01/request.md:31`).
 *
 * The defect this whole task fixes was a *wiring* defect, not a policy one: the
 * saved continuation prompt existed and the consumption existed, but the only
 * caller sat inside the user-send path, so the mode could not advance without a
 * human. The behaviour of the ceiling and of "root primary only" is tested as
 * behaviour in [GoalAutoContinuationPolicyTest]; what cannot be reached from a
 * plain JVM test is `ChatViewModel` itself (this module has no Robolectric, and
 * the class needs Room, a provider stack and Compose state). So the trigger
 * points, the cancel guard and the single-injection path are pinned as source
 * shapes here — the same convention the repo already uses for exactly this seam
 * (`ChatViewModelChildAbnormalEndReminderSourceTest`,
 * `QueuedPromptWithdrawWiringTest`). Text matching is weaker than a runtime
 * assertion; each assertion is written to fail if the old shape comes back.
 */
class GoalAutoContinuationWiringTest {

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
     * The *whole* declaration of `fun name(...)`, signature and body.
     *
     * Why not `slice(full, "fun name(", "<some other line>")`: that window is
     * bounded by another piece of text in the file, so its content depends on
     * lines that have nothing to do with the claim. When the two markers happen
     * to be close together the window can collapse onto the parameter list —
     * and then an assertion about a *parameter's use* is satisfied by the
     * parameter's own name. That is exactly what happened here: the window was
     * `private fun resumeRun(\n phase: String,\n requireCanResume: Boolean,\n
     * markStreamErrorOnFailure: Boolean = false,\n ) {\n` — 148 characters,
     * 14229–14233, containing not one line of the body. `resumeRun` could have
     * dropped `markStreamErrorOnFailure` on the floor (the goal would then sit
     * ACTIVE after a failed continuation, with nothing left to re-arm it) and
     * this file stayed green.
     *
     * So the window is derived from the *language* instead: brace matching from
     * the declaration, with strings and comments stripped so a brace inside a
     * string ("}") cannot close it early. Bracket-only members (no braces) are
     * not used by this file.
     */
    private fun ktSlice(full: String, decl: String): String {
        val marker = if (decl.startsWith("fun ")) decl else "fun $decl"
        val start = full.indexOf(marker)
        assertTrue("missing declaration: $marker", start >= 0)
        val body = stripCommentsAndStrings(full.substring(start))
        val open = body.indexOf('{')
        assertTrue("'$marker' has no body block", open >= 0)
        var depth = 0
        for (i in open until body.length) {
            when (body[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return full.substring(start, start + i + 1)
                }
            }
        }
        return fail("unbalanced braces in '$marker'")
    }

    private fun <T> fail(message: String): T = throw AssertionError(message)

    /** The body block of an already-sliced declaration, opening brace included. */
    private fun bodyOf(declaration: String): String {
        val open = stripCommentsAndStrings(declaration).indexOf('{')
        assertTrue("no body block in: ${declaration.take(80)}", open >= 0)
        return declaration.substring(open)
    }

    /**
     * Replaces comment bodies and string/char literal bodies with spaces, keeping
     * offsets and newlines. Braces, brackets and commas are never touched, so a
     * literal `"}"` or a commented-out `if (…) use(…)` cannot be mistaken for code.
     */
    private fun stripCommentsAndStrings(text: String): String {
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
                c == '"' -> {
                    out.append(' ')
                    i++
                    while (i < text.length && text[i] != '"' && text[i] != '\n') {
                        if (text[i] == '\\') {
                            out.append(' ')
                            i++
                        }
                        out.append(' ')
                        i++
                    }
                    if (i < text.length && text[i] == '"') {
                        out.append(' ')
                        i++
                    }
                }
                c == '\'' -> {
                    out.append(' ')
                    i++
                    while (i < text.length && text[i] != '\'' && text[i] != '\n') {
                        if (text[i] == '\\') {
                            out.append(' ')
                            i++
                        }
                        out.append(' ')
                        i++
                    }
                    if (i < text.length && text[i] == '\'') {
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

    /** The parameter list of `fun name(...)`, from the declaration. */
    private fun paramList(fn: String, decl: String): String {
        val open = fn.indexOf('(')
        assertTrue("missing parameter list", open >= 0)
        val text = stripCommentsAndStrings(fn)
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return fn.substring(open + 1, i)
                }
            }
        }
        return fail("unbalanced parentheses in '$decl'")
    }

    /** `Boolean` parameter names of `fun name(...)`. */
    private fun booleanParams(fn: String): List<String> =
        booleanParamsWithDefaults(fn).map { it.first }

    /**
     * `Boolean` parameters of `fun name(...)`, paired with their declared default
     * expression (`null` when the parameter has no default).
     *
     * The default matters for the call-site assertions: a call that omits a flag is
     * not necessarily wrong — it is wrong only if the value it silently inherits is
     * the wrong one. Asserting "the argument must be spelled `= true` here" would
     * fail a call site that legitimately relies on `= true` as the declared default,
     * i.e. a false red for an unchanged behaviour.
     */
    private fun booleanParamsWithDefaults(fn: String): List<Pair<String, String?>> =
        paramList(fn, "?").split(',', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { Regex("^[A-Za-z_][A-Za-z0-9_]*\\s*:\\s*Boolean\\b").containsMatchIn(it) }
            .map { param ->
                val name = param.substringBefore(':').trim()
                val afterType = param.substringAfter("Boolean", "")
                val default = afterType.substringAfter('=', "").trim().takeIf { it.isNotEmpty() }
                name to default
            }

    /** The `name = <expr>` arguments of every `fn(` call inside [holder]. */
    private fun namedArgs(holder: String, fn: String): Map<String, String> {
        val code = stripCommentsAndStrings(holder)
        val out = mutableMapOf<String, String>()
        val needle = "$fn("
        var from = 0
        while (true) {
            val at = code.indexOf(needle, from)
            if (at < 0) break
            // Not `foo.bar.fn(` — but `something.fn(` is a different symbol.
            if (at > 0 && (code[at - 1] == '.' || code[at - 1].isLetterOrDigit() || code[at - 1] == '_')) {
                from = at + 1
                continue
            }
            var i = at + needle.length
            var depth = 1
            val args = StringBuilder()
            while (i < code.length && depth > 0) {
                when (code[i]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) break
                    }
                }
                if (depth > 0) args.append(holder[i])
                i++
            }
            for (part in splitTopLevel(args.toString())) {
                val eq = part.indexOf('=')
                if (eq <= 0) continue
                val key = part.substring(0, eq).trim()
                if (!Regex("^[A-Za-z_][A-Za-z0-9_]*$").containsMatchIn(key)) continue
                out[key] = part.substring(eq + 1).trim()
            }
            from = at + needle.length
        }
        return out
    }

    /** Splits an argument list on commas that are not nested in brackets or literals. */
    private fun splitTopLevel(args: String): List<String> {
        val code = stripCommentsAndStrings(args)
        val out = mutableListOf<String>()
        var depth = 0
        var start = 0
        for (i in code.indices) {
            when (code[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ',' -> if (depth == 0) {
                    out += args.substring(start, i)
                    start = i + 1
                }
            }
        }
        out += args.substring(start)
        return out.filter { it.isNotBlank() }
    }

    /** `capabilitySnapshot(...)` → `capability`, i.e. the name a derived arg should have. */
    private fun derivedName(expr: String): String {
        val call = Regex("([A-Za-z_][A-Za-z0-9_]*)\\s*\\(").find(expr.trim())
        val base = call?.groupValues?.get(1) ?: expr.trim().substringBefore('.')
        return base.removePrefix("get").removeSuffix("For").lowercase()
    }

    /**
     * True when [param] is the parameter whose *use* [expr] provides: either the
     * same spelling, or the parameter name normalised equals the called getter's
     * name normalised (`capabilities` ← `capabilitySnapshot(...)`).
     *
     * This is deliberately spelling-tolerant on one side and spelling-strict on
     * the other: renaming the parameter at declaration and at the call site keeps
     * it green (no behaviour changed), while dropping the argument — `null`, or a
     * value from somewhere else — goes red.
     */
    private fun supplies(expr: String, param: String): Boolean {
        val e = expr.trim()
        if (e.isEmpty() || e == "null") return false
        if (e == param) return true
        val norm = { s: String -> s.lowercase().removeSuffix("s").replace("_", "") }
        return norm(param) == norm(derivedName(e))
    }

    private val chatViewModel: String by lazy { source("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt") }

    /** The tail of the ordinary send run, where `_isStreaming` is cleared. */
    private val sendRunTail: String by lazy {
        slice(
            chatViewModel,
            "send _isStreaming=false (about to set)",
            "AppLogger.info(TAG_STREAM, \"send streamJob EXIT\")",
        )
    }

    /** The tail of the shared interruption/continuation run. */
    private val continuationRunTail: String by lazy {
        slice(
            chatViewModel,
            "\"\$phase _isStreaming=false (about to set)\"",
            "AppLogger.info(TAG_STREAM, \"\$phase streamJob EXIT\")",
        )
    }

    private val autoContinueFn: String by lazy {
        slice(chatViewModel, "private fun maybeAutoContinueGoal(", "private fun scheduleGoalAutoContinuation(")
    }

    /**
     * The whole of `resumeRun` — signature *and* body. See [ktSlice] for why this
     * is not a text window bounded by another line of `ChatViewModel.kt`.
     */
    private val resumeRunDecl: String by lazy { ktSlice(chatViewModel, "resumeRun(") }

    /** `maybeAutoContinueGoal`'s `resumeRun` call, as named arguments. */
    private val goalResumeCall: Map<String, String> by lazy {
        namedArgs(ktSlice(chatViewModel, "maybeAutoContinueGoal("), "resumeRun").also {
            assertTrue(
                "the goal continuation must call resumeRun: $it",
                it.isNotEmpty(),
            )
        }
    }

    /** The user's own `resume()` entry point, as named arguments. */
    private val userResumeCall: Map<String, String> by lazy {
        namedArgs(ktSlice(chatViewModel, "fun resume()"), "resumeRun").also {
            assertTrue("resume() must call resumeRun: $it", it.isNotEmpty())
        }
    }

    /**
     * What `flag` is really set to at [call] — the argument when the call site names
     * it, otherwise the parameter's declared default. `null` means "the call site
     * neither passes it nor can it default", which is itself a failure.
     */
    private fun effectiveFlag(call: Map<String, String>, defaults: Map<String, String?>, flag: String): String? =
        call[flag] ?: defaults[flag]

    /**
     * The two Boolean parameters of `resumeRun`, identified by the *role each one
     * plays in the body* instead of by its spelling.
     *
     * This is the point of the whole exercise: the previous assertions matched the
     * names. Renaming a flag at its four sites is a pure refactor, so a name-based
     * assertion reports a broken guard where nothing broke — the same class of
     * mistake as reporting nothing when the guard really broke. Roles are derived
     * from shape:
     *
     *   * the flag that gates on the "tap Continue" banner conjoins with
     *     `_canResume` in a condition;
     *   * the flag that records a failed continuation as a stream error is the
     *     remaining Boolean parameter.
     *
     * A rename that preserves behaviour keeps both roles intact and stays green; a
     * deleted read, a wrong value at a call site (passed or inherited), or a `null`
     * argument goes red.
     */
    private class FlagRoles(val canResumeFlag: String, val markErrorFlag: String)

    private fun rolesOf(declaration: String): FlagRoles {
        val params = booleanParamsWithDefaults(declaration)
        assertEquals("resumeRun must declare two Boolean flags: $params", 2, params.size)
        val booleans = params.map { it.first }

        val body = stripCommentsAndStrings(bodyOf(declaration))
        val bannerGated = booleans.filter { flag ->
            val f = Regex.escape(flag)
            Regex("if\\s*\\([^)]*\\b$f\\b[^)]*_canResume").containsMatchIn(body) ||
                Regex("if\\s*\\([^)]*_canResume[^)]*\\b$f\\b").containsMatchIn(body)
        }
        assertEquals(
            "exactly one Boolean flag of resumeRun must gate on the 'tap Continue' banner " +
                "(`_canResume` in the same condition); found $bannerGated out of $booleans. If the " +
                "field was renamed too, update the role probe — the role, not the spelling, is " +
                "what this file asserts.",
            1,
            bannerGated.size,
        )
        val banner = bannerGated.single()
        val other = booleans.single { it != banner }
        assertTrue(
            "$other must be *read* in resumeRun's body, not merely declared: the flag that " +
                "records a failed continuation as a stream error has to gate a call, or the " +
                "parameter changes nothing no matter what the call sites pass.\n" +
                "        declared: $booleans\n" +
                "        body excerpt: ${bodyOf(declaration).take(300)}",
            Regex("if\\s*\\([^)]*\\b" + Regex.escape(other) + "\\b[^)]*\\)").containsMatchIn(body),
        )
        return FlagRoles(banner, other)
    }

    // ── C: the trigger exists, and it is "the run ended" ──────────────────

    @Test
    fun `a finished run asks whether the goal must continue itself`() {
        assertTrue(
            "the send run's tail must hand over to the auto-continuation check; without this " +
                "the mode only ever advances when a human types",
            sendRunTail.contains("scheduleGoalAutoContinuation("),
        )
        assertTrue(
            "the continuation run's tail must ask again, or the chain dies after one step",
            continuationRunTail.contains("scheduleGoalAutoContinuation("),
        )
        assertTrue(
            "the check must run after _isStreaming is cleared — a live run's own tail is the " +
                "next checker, and a run started for a still-streaming session would be dropped",
            sendRunTail.indexOf("scheduleGoalAutoContinuation(") >
                sendRunTail.indexOf("_isStreaming.value = false"),
        )
    }

    @Test
    fun `the trigger is the shared interruption run, not a second request path`() {
        assertTrue(
            "the goal continuation must re-enter the agent loop through resumeRun",
            autoContinueFn.contains("resumeRun("),
        )
        assertTrue(
            "…and it must reuse the goal phase label, which is also how the trigger recognises " +
                "a goal-driven run",
            autoContinueFn.contains("GOAL_CONTINUATION_PHASE"),
        )

        // ── what `resumeRun`'s two flags are for ────────────────────────────
        //
        // The old form of these two assertions matched the *parameter names* in a
        // 148-character window that held nothing but the signature, so they stayed
        // green even after the body stopped reading the flags (measured: deleting
        // `if (markStreamErrorOnFailure) …` kept all 24 tests green). The rewrite
        // pins three things, none of them a spelling:
        //   (a) the flags are identified by the role each plays in the body;
        //   (b) each role is really *read*, inside a condition;
        //   (c) the goal continuation supplies the values its path needs, and the
        //       user's own `resume()` supplies the opposite ones.
        // (b) is what the old form was pretending to do; (c) is what makes a
        // swapped or dropped argument visible.
        val roles = rolesOf(resumeRunDecl)
        val defaults = booleanParamsWithDefaults(resumeRunDecl).toMap()

        assertTrue(
            "the goal continuation's call site must *end up with* its error-recording flag " +
                "(`${roles.markErrorFlag}`) true — passed explicitly, or inherited from a default " +
                "that is itself true. A goal is re-armed only on an *error* completion, so a " +
                "continuation whose failure goes unmarked leaves the goal ACTIVE with nothing left " +
                "to continue it: call=$goalResumeCall defaults=$defaults",
            effectiveFlag(goalResumeCall, defaults, roles.markErrorFlag) == "true",
        )
        assertTrue(
            "the goal path must not depend on the 'tap Continue' banner being up — it is an " +
                "unattended continuation, so `${roles.canResumeFlag}` must *end up* false, whether " +
                "passed or inherited: call=$goalResumeCall defaults=$defaults",
            effectiveFlag(goalResumeCall, defaults, roles.canResumeFlag) == "false",
        )
        assertTrue(
            "the user's own resume() must keep the opposite choices — it is the 'tap Continue' " +
                "path, so `${roles.canResumeFlag}` must end up true, and an ordinary resume must " +
                "not synthesise a goal stop, so `${roles.markErrorFlag}` must not end up true: " +
                "call=$userResumeCall defaults=$defaults",
            effectiveFlag(userResumeCall, defaults, roles.canResumeFlag) == "true" &&
                effectiveFlag(userResumeCall, defaults, roles.markErrorFlag) != "true",
        )
    }

    @Test
    fun `a cancelled run is never continued`() {
        assertTrue(
            "a user who pressed stop must not have the goal restarted under them (send path)",
            sendRunTail.contains("isCancelled != true"),
        )
        assertTrue(
            "a user who pressed stop must not have the goal restarted under them (continuation path)",
            continuationRunTail.contains("isCancelled != true"),
        )
        assertTrue(
            "only a goal-driven run may turn its own normal end into a stop",
            continuationRunTail.contains("fromGoalRun = phase == GOAL_CONTINUATION_PHASE"),
        )
        assertTrue(
            "an ordinary user run must not synthesise a goal stop",
            sendRunTail.contains("fromGoalRun = false"),
        )
    }

    // ── A: the primary verdict comes from the runtime tree ───────────────

    @Test
    fun `the goal node is derived from the existing runtime-tree parent link`() {
        assertTrue(
            "the session's goal node must be derived from the runtime tree's parent link",
            chatViewModel.contains("goalNodeForChatSession(sessionId)"),
        )
        assertTrue(
            "…using the same parent lookup the skill/MCP inheritance chain uses",
            chatViewModel.contains("SessionTreeParentChain"),
        )
        assertTrue(
            "the goal runtime must be built from that node",
            chatViewModel.contains("ForkGoalRuntime(goalNode, goalStore.load())"),
        )
        assertFalse(
            "the pre-fix construction must be gone: every session used to be its own root, which " +
                "let a sub-agent own a goal",
            chatViewModel.contains("ForkGoalRuntime(ForkGoalNode(sessionId, sessionId)"),
        )
    }

    // ── C: one pending prompt, one injection ─────────────────────────────

    @Test
    fun `the automatic path injects through the one existing consumer`() {
        assertTrue(
            "the auto path must consume the pending prompt through the same function the user " +
                "send path uses",
            autoContinueFn.contains("consumePendingGoalContinuation()"),
        )
        assertFalse(
            "…and must not poll or inject on its own — a second injection site is exactly how " +
                "the same prompt reaches the model twice",
            autoContinueFn.contains("pollContinuationPrompt(") || autoContinueFn.contains("agentHistory.add("),
        )
        val spend = autoContinueFn.indexOf("noteAutoContinuation(")
        val consume = autoContinueFn.indexOf("consumePendingGoalContinuation()")
        assertTrue(
            "the budget must be spent before the prompt is consumed, so an attempt that dies in " +
                "between cannot be repeated for free",
            spend > 0 && consume > 0 && spend < consume,
        )
        assertTrue(
            "the existing user-send consumer must stay where it was",
            slice(
                chatViewModel,
                "private fun sendMessage(text: String, skipContextCheck: Boolean)",
                "val currentAttachments = _attachments.value",
            ).contains("consumePendingGoalContinuation()"),
        )
    }

    // ── B: the hand-over must leave the goal resumable ───────────────────

    /**
     * The hand-over card promises the reader that the goal is unfinished and its
     * saved progress is intact. The state machine already guarantees it
     * ([GoalAutoContinuationPolicyTest], [GoalAutoContinuationPersistenceTest]);
     * what this pins is that the production hand-over branch does not walk around
     * that guarantee — e.g. by consuming the pending prompt "to tidy up", which
     * would leave a goal nobody can continue.
     */
    @Test
    fun `the hand-over branch leaves the pending prompt alone`() {
        val start = autoContinueFn.indexOf("GoalAutoContinuationReason.BUDGET_EXHAUSTED ->")
        assertTrue("the hand-over branch must exist", start >= 0)
        val end = autoContinueFn.indexOf("goalRuntime.noteAutoContinuation(", start)
        assertTrue("could not bound the hand-over branch", end > start)
        val handOver = autoContinueFn.substring(start, end)

        assertFalse(
            "the hand-over must not consume the pending prompt: that is what leaves the user " +
                "able to continue the goal by hand after the budget is spent",
            handOver.contains("consumePendingGoalContinuation"),
        )
        assertFalse(
            "…and must not poll it either — polling is what marks it delivered",
            handOver.contains("pollContinuationPrompt"),
        )
        assertFalse(
            "…and must not resume the goal, which would clear the pending prompt as well",
            handOver.contains("resume("),
        )
        assertFalse(
            "…and must not spend more budget while handing over",
            handOver.contains("noteAutoContinuation"),
        )
        assertFalse(
            "…and must not delete the stored goal",
            handOver.contains("goalStore.delete"),
        )
        assertTrue(
            "the hand-over must still be visible to the user — a silent stop is the one outcome " +
                "the budget exists to avoid",
            handOver.contains("chat_goal_auto_continuation_stopped"),
        )
    }

    // ── B: one source for the ceiling ────────────────────────────────────

    @Test
    fun `the ceiling is declared once and never re-spelled at the trigger site`() {
        val mainRoot = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull { it.isDirectory }
            ?: error("main source root not found; run from the module or repo root")

        val sources = mainRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val declarations = sources.filter { it.readText().contains("const val MAX_AUTO_CONTINUATIONS") }
        assertEquals(
            "the auto-continuation ceiling must have exactly one declaration",
            listOf("GoalAutoContinuation.kt"),
            declarations.map { it.name },
        )
        assertFalse(
            "the trigger site must read the ceiling from the policy's verdict, not carry a copy; " +
                "one rule written out in two places is how they drift apart",
            chatViewModel.contains("MAX_AUTO_CONTINUATIONS"),
        )
        assertTrue(
            "the trigger site must go through the policy",
            autoContinueFn.contains("GoalAutoContinuationPolicy.decide("),
        )
    }
}
