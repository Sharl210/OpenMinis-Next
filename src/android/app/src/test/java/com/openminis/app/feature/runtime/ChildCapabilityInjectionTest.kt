package com.openminis.app.feature.runtime

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.tools.AgentTools
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-child-agent-completion] Requirement (`request.md:105`): every
 * top-down message must carry the delegation capability metadata — can THIS
 * node delegate further, and can ITS subagents delegate — so the receiver knows
 * whether it is the final executor or an intermediate manager.
 *
 * These guards pin the metadata contract itself, and the PER-MESSAGE injection
 * point is pinned by behaviour: driving the production drain against a real
 * runtime tree and moving the tree between two drains, so the reminder is
 * observed to be rebuilt from the live snapshot rather than from a value
 * captured when the child was born.
 *
 * The BIRTH injection point is still pinned by a source-level test at the bottom
 * of this file (`the birth injection is wired to the coordinator snapshot and not
 * to a literal null`). That one is honest about what it is: the wiring there is
 * two adjacent lines in one function, and `RuntimeChildRunner.execute` cannot be
 * run in this module (it needs a provider stack).
 */
class ChildCapabilityInjectionTest {

    private fun capabilities(
        selfCanDelegate: Boolean = true,
        descendantCanDelegate: Boolean = true,
        effectiveDepth: Int = 2,
        currentActiveChildren: Int = 0,
        maxParallelSubagents: Int = 5,
        depth: Int = 0,
        maxDepth: Int = 2,
        delegationMode: DelegationMode = DelegationMode.TRADITIONAL,
    ) = AgentCapabilitySnapshot(
        nodeId = "child-1",
        rootId = "root",
        depth = depth,
        role = "child",
        task = "research",
        maxDepth = maxDepth,
        effectiveDepth = effectiveDepth,
        selfCanDelegate = selfCanDelegate,
        descendantCanDelegate = descendantCanDelegate,
        maxParallelSubagents = maxParallelSubagents,
        currentActiveChildren = currentActiveChildren,
        delegationMode = delegationMode,
        configRevision = 7L,
    )

    // ─── 能力块内容 ──────────────────────────────────────────────────────

    @Test
    fun `capability block states both delegation flags plus the parallel budget`() {
        val block = ChildCompletionProtocol.capabilityBlock(
            capabilities(currentActiveChildren = 2, maxParallelSubagents = 5),
        )

        // Both questions the requirement names must be answered, by name.
        assertTrue("must answer the layer capacity question: $block", block.contains("Delegation capacity of your layer"))
        assertTrue("must answer the descendant question: $block", block.contains("Can your own subagents delegate"))
        assertTrue(block.contains("ALLOWED"))
        // The budget the child plans against, both used and total.
        assertTrue("must expose the parallel budget: $block", block.contains("2/5"))
        assertTrue(block.contains("child-1"))
        assertTrue(block.contains("depth 0 of 2"))
        assertTrue(block.contains(DelegationMode.TRADITIONAL.name))
    }

    // ─── 诚实性：不得承诺不存在的工具 ────────────────────────────────────

    @Test
    fun `the block never promises a delegation tool the child does not have`() {
        val block = ChildCompletionProtocol.capabilityBlock(
            capabilities(selfCanDelegate = true, descendantCanDelegate = true),
        )
        val promised = block.contains("You may split the request and delegate")

        // The invariant: the block may only ORDER a split when a spawn tool
        // actually exists. Capacity in the tree and ability in the model are two
        // different things, and the tool list is the authority on the latter.
        assertEquals(
            "the block must order a split if and only if the child is given a tool to perform it; " +
                "block=$block; child tools=${AgentTools.makeChildAgentTools().map { it.name }}",
            ChildCompletionProtocol.childHasDelegationTool,
            promised,
        )
    }

    @Test
    fun `while the child has no spawn tool the managing verdicts say so and forbid planning the split`() {
        listOf(
            capabilities(selfCanDelegate = true, descendantCanDelegate = true),
            capabilities(selfCanDelegate = true, descendantCanDelegate = false),
        ).forEach { caps ->
            val block = ChildCompletionProtocol.capabilityBlock(caps)

            // Guard: if a spawn tool is ever added this test's premise is gone and
            // it must be revisited rather than silently passing.
            assertFalse(
                "this test pins the no-tool world; child tools are now " +
                    "${AgentTools.makeChildAgentTools().map { it.name }}",
                ChildCompletionProtocol.childHasDelegationTool,
            )
            assertTrue("must state the tool is unavailable: $block", block.contains("Delegation tool available to you: NONE"))
            assertTrue("must say the capacity is not actionable: $block", block.contains("not something you can act on"))
            assertTrue("must say the child cannot start the split: $block", block.contains("Since you cannot start that split"))
            assertTrue("must redirect to reporting it instead: $block", block.contains("state plainly in your result"))
            assertFalse("must not order an impossible split: $block", block.contains("You may split the request and delegate"))
        }
    }

    @Test
    fun `the advertised delegation tool set matches the commands the app really parses`() {
        // The names that would count as a spawn affordance must be the composer
        // commands, otherwise the block could call a tool "available" that the
        // delegation parser would never accept.
        val parserSource = sequenceOf(
            File("src/main/java/com/openminis/app/feature/runtime/RuntimeDelegation.kt"),
            File("app/src/main/java/com/openminis/app/feature/runtime/RuntimeDelegation.kt"),
        ).first { it.isFile }.readText()

        listOf("/subagent", "/team", "/delegate").forEach { command ->
            assertTrue("the parser must still know $command", parserSource.contains("\"$command\""))
        }
    }

    // ─── 角色结论 ────────────────────────────────────────────────────────

    @Test
    fun `a node that can both work and delegate is told it is an intermediate manager`() {
        val block = ChildCompletionProtocol.capabilityBlock(
            capabilities(selfCanDelegate = true, descendantCanDelegate = true),
        )

        assertTrue(
            "a node whose subagents can also delegate is a multi-level manager: $block",
            block.contains("INTERMEDIATE MANAGER"),
        )
        assertFalse(block.contains("FINAL EXECUTOR"))
    }

    @Test
    fun `a node whose subagents cannot delegate is told to hand down self-contained work`() {
        val block = ChildCompletionProtocol.capabilityBlock(
            capabilities(selfCanDelegate = true, descendantCanDelegate = false),
        )

        assertTrue(
            "one layer above final executors: $block",
            block.contains("MANAGING LAYER OVER FINAL EXECUTORS"),
        )
        assertTrue("must say the subagents are final executors: $block", block.contains("final executor"))
        assertFalse(block.contains("FINAL EXECUTOR. You are at the deepest"))
    }

    @Test
    fun `a node at the depth limit is told it is the final executor`() {
        val block = ChildCompletionProtocol.capabilityBlock(
            capabilities(selfCanDelegate = false, descendantCanDelegate = false, effectiveDepth = 0),
        )

        assertTrue("$block", block.contains("Delegation capacity of your layer: NONE"))
        assertTrue("must reach the role conclusion: $block", block.contains("FINAL EXECUTOR"))
        assertTrue("must explain why: $block", block.contains("maximum delegation depth"))
        assertFalse(
            "must not keep the temporary verdict: $block",
            block.contains("EXECUTOR FOR NOW"),
        )
    }

    @Test
    fun `a node that is merely out of parallel slots is told that, not that it is a leaf`() {
        val block = ChildCompletionProtocol.capabilityBlock(
            capabilities(
                selfCanDelegate = false,
                descendantCanDelegate = true,
                effectiveDepth = 2,
                currentActiveChildren = 5,
                maxParallelSubagents = 5,
            ),
        )

        assertTrue("$block", block.contains("Delegation capacity of your layer: NONE"))
        // The reason matters: out of slots is temporary, out of depth is not.
        assertTrue("must name the exhausted slot budget: $block", block.contains("5/5"))
        assertFalse("must not claim it is out of depth: $block", block.contains("maximum delegation depth"))
        // Slot-blocked is its own role verdict, NOT the permanent one. Reading
        // "FINAL EXECUTOR" directly above "your own subagents can delegate? YES"
        // would be the block arguing with itself, which is the role confusion the
        // requirement exists to remove.
        assertTrue("must give the temporary verdict: $block", block.contains("EXECUTOR FOR NOW"))
        assertFalse(
            "must not hand a merely slot-blocked layer the permanent verdict: $block",
            block.contains("FINAL EXECUTOR"),
        )
        // The verdict must still tell it what to DO, not just what it is.
        assertTrue("must tell it to start working: $block", block.contains("Start on it"))
        assertTrue(
            "the still-delegatable descendants must remain visible: $block",
            block.contains("Can your own subagents delegate? YES"),
        )
    }

    // ─── 两处注入点都要携带 ──────────────────────────────────────────────

    @Test
    fun `both the birth injection and every parent message carry the same capability block`() {
        val caps = capabilities(currentActiveChildren = 1, maxParallelSubagents = 3)
        val block = ChildCompletionProtocol.capabilityBlock(caps)
        // Guard against the vacuous case: every string contains "", so a block
        // that silently became empty would make the contains() checks below
        // pass while carrying nothing.
        assertTrue("the block itself must not be empty", block.isNotBlank())

        val birth = ChildCompletionProtocol.systemInstruction(caps)
        val reminder = ChildCompletionProtocol.reminderFor(RuntimeDelivery.QUEUE, caps)

        assertTrue("birth injection must carry the block: $birth", birth.contains(block))
        assertTrue("per-message injection must carry the block: $reminder", reminder!!.contains(block))
        // Spelling the payload out keeps the check meaningful even if the block
        // text is edited: the role question must reach the model, not just some
        // substring that happens to be shared.
        listOf(birth, reminder).forEach { text ->
            assertTrue("must carry the capability heading: $text", text.contains("### Your delegation capability"))
            assertTrue("must carry the role verdict: $text", text.contains("Your role:"))
        }
        // The completion protocol must survive alongside it.
        assertTrue(birth.contains(ChildCompletionProtocol.TOOL_NAME))
        assertTrue(reminder.contains(ChildCompletionProtocol.TOOL_NAME))
    }

    @Test
    fun `every parent-originated delivery gets the capability block`() {
        val caps = capabilities()

        listOf(
            RuntimeDelivery.QUEUE,
            RuntimeDelivery.STEER,
            RuntimeDelivery.NOTIFY,
        ).forEach { delivery ->
            val reminder = ChildCompletionProtocol.reminderFor(delivery, caps)
            assertTrue("$delivery must carry the capability heading", reminder!!.contains("### Your delegation capability"))
            assertTrue("$delivery must carry the role verdict", reminder.contains("Your role:"))
            assertTrue("$delivery must carry the parallel budget", reminder.contains("5 subagent slot"))
        }
    }

    @Test
    fun `team peer messages stay free of the injection`() {
        // Requirement keeps sibling traffic from re-teaching the protocol; the
        // capability metadata travels with top-down messages only.
        assertNull(ChildCompletionProtocol.reminderFor(RuntimeDelivery.TEAM_PEER, null))
        assertNull(ChildCompletionProtocol.reminderFor(RuntimeDelivery.TEAM_PEER, capabilities()))
    }

    // ─── null 必须优雅降级 ───────────────────────────────────────────────

    @Test
    fun `an unresolved snapshot degrades to unknown instead of claiming no-delegation`() {
        val block = ChildCompletionProtocol.capabilityBlock(null)

        assertTrue("must say it is unknown: $block", block.contains("Unknown"))
        assertTrue("must warn against assuming either way: $block", block.contains("NOT established"))
        // `false` means "certainly cannot delegate" and null means "do not know".
        // Blurring them would silently turn a depth-capped child into a manager.
        assertFalse("must not claim the child cannot delegate: $block", block.contains("FINAL EXECUTOR"))
        assertFalse("must not emit a definitive NO: $block", block.contains("NONE —"))
        assertFalse("must not claim a tool verdict: $block", block.contains("Delegation tool available to you"))
    }

    @Test
    fun `injections still work when the runtime could not resolve the node`() {
        // A null snapshot must degrade, never crash and never drop the protocol.
        val birth = ChildCompletionProtocol.systemInstruction(null)
        val reminder = ChildCompletionProtocol.reminderFor(RuntimeDelivery.QUEUE, null)

        assertTrue(birth.contains(ChildCompletionProtocol.TOOL_NAME))
        assertTrue(birth.contains("Unknown"))
        assertTrue(reminder!!.contains(ChildCompletionProtocol.TOOL_NAME))
        assertTrue(reminder.contains("Unknown"))
    }

    // ─── 接线：能力快照真的被传进两处注入 ────────────────────────────────

    private fun childRunnerSource(): String = sequenceOf(
        File("src/main/java/com/openminis/app/feature/runtime/RuntimeChildRunner.kt"),
        File("app/src/main/java/com/openminis/app/feature/runtime/RuntimeChildRunner.kt"),
    ).first { it.isFile }.readText()

    /**
     * Blanks the *bodies* of comments and string/char literals, preserving offsets
     * and newlines.
     *
     * Every assertion in `the birth injection …` reads this text instead of the raw
     * file, and that is the point: `RuntimeChildRunner.kt` legitimately mentions
     * `buildSystemPrompt` and `capabilitySnapshot` in prose (see the KDoc at the
     * call site), so a search over the raw file can be satisfied by a *comment*.
     * That is not hypothetical — it was measured: adding one comment containing
     * `ChildCompletionProtocol.systemInstruction(capabilities)` near the top of the
     * file, and then deleting the real birth injection, left all three of the old
     * assertions green.
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

    private fun codeOf(region: String): String = blankCommentsAndLiterals(region)

    /** Replaces the callee text with spaces, so it cannot satisfy an argument match. */
    private fun blankFirstCallName(original: String): String {
        val code = codeOf(original)
        val open = code.indexOf('(')
        assertTrue("no call in the region", open > 0)
        var start = open
        while (start > 0 && (code[start - 1].isLetterOrDigit() || code[start - 1] == '_' || code[start - 1] == '.')) {
            start--
        }
        return original.substring(0, start) + " ".repeat(open - start) + original.substring(open)
    }

    /**
     * The whole block of the first `blockMarker` in [original] — from just before
     * the marker through the `}` that closes its first `{` — with the search done
     * on [codeOf] and the slice taken from the original text.
     *
     * Replaces `substringAfter("private fun buildSystemPrompt(").take(200)`: a
     * 200-character window has to be re-tuned every time a KDoc changes, and the
     * body it was meant to cover was never inside it.
     */
    private fun blockOf(original: String, blockMarker: String): String {
        val code = codeOf(original)
        val at = code.indexOf(blockMarker)
        assertTrue("'$blockMarker' must exist in RuntimeChildRunner.kt", at >= 0)
        val open = code.indexOf('{', at)
        assertTrue("'$blockMarker' has no block", open >= 0)
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        // Back up so the slice starts at the beginning of the line the
                        // declaration sits on — the declaration itself is part of what
                        // the argument scan below has to see.
                        var lineStart = at
                        while (lineStart > 0 && original[lineStart - 1] != '\n') lineStart--
                        return original.substring(lineStart, i + 1)
                    }
                }
            }
        }
        throw AssertionError("unbalanced braces in '$blockMarker'")
    }

    /**
     * The arguments of the first `marker(…)` in [region], as source text, with the
     * parentheses matched against [codeOf] so a `)` inside a string cannot end the
     * call early. Bounded to [region] instead of "the rest of the file" — that
     * difference is what makes a *comment elsewhere* unable to satisfy a caller.
     */
    private fun argsOf(region: String, marker: String): List<String> {
        val code = codeOf(region)
        val at = code.indexOf(marker)
        assertTrue("'$marker' not found in the region being checked", at >= 0)
        val open = code.indexOf('(', at + marker.length)
        assertTrue("no '(' after '$marker'", open >= 0)
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return splitTopLevel(region.substring(open + 1, i))
                }
            }
        }
        throw AssertionError("unbalanced parentheses after '$marker'")
    }

    /** Split on commas not nested in brackets or literals; empty entries dropped. */
    private fun splitTopLevel(args: String): List<String> {
        val code = codeOf(args)
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
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * Every call's argument list, in source order, for all call-like `…(…)` forms
     * in [region]. Used instead of naming the callee: "some call in this body
     * receives the snapshot parameter" is the property, and it survives a rename of
     * the callee while a deleted forwarding call goes red.
     */
    private fun allCallArgs(region: String): List<List<String>> {
        val code = codeOf(region)
        val out = mutableListOf<List<String>>()
        // Every '(' is considered, including the nested ones: `append(f(x))` must
        // yield both `f(x)`'s argument list and `append(…)`'s. Advancing past the
        // outer call's closing paren (the obvious first implementation) silently
        // skips every nested call — which is exactly where the injection lives.
        for (i in code.indices) {
            if (code[i] != '(') continue
            var depth = 0
            var j = i
            while (j < code.length) {
                when (code[j]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) break
                    }
                }
                j++
            }
            if (j >= code.length) break
            out += splitTopLevel(region.substring(i + 1, j))
        }
        return out
    }

    private fun textOf(message: LLMMessage): String =
        message.contentParts.filterIsInstance<AgentContentPart.Text>()
            .joinToString("") { it.text }
            .ifEmpty { message.content ?: "" }

    /**
     * A real runtime tree on a real temp directory plus the coordinator over it —
     * the same fixture, built the same way, as `RuntimeChildRunnerSteerPerTurnTest`.
     * `RuntimeSessionCoordinator` has a private constructor and
     * `RuntimeTreeStore.openForTest` is the only door that does not need an
     * Android `Context`, so the coordinator is built over the store by reflection.
     */
    private class LiveTree {
        val dir: File = Files.createTempDirectory("child-capability-live").toFile()
        val store: RuntimeTreeStore = RuntimeTreeStore.openForTest(File(dir, "session-tree.json")) { source, target ->
            Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        }
        val coordinator: RuntimeSessionCoordinator = run {
            val constructor = RuntimeSessionCoordinator::class.java
                .getDeclaredConstructor(RuntimeTreeStore::class.java)
            constructor.isAccessible = true
            constructor.newInstance(store)
        }

        init {
            assertTrue("the root must exist", coordinator.startRoot("parent") != null)
            assertTrue("the child must exist", coordinator.startChild("parent", "child"))
        }

        fun dispose() {
            dir.deleteRecursively()
        }
    }

    /**
     * One child run's drain state. Both collections outlive a single drain in the
     * run body, so they outlive one here too — the second drain must not re-deliver
     * what the first already absorbed.
     */
    private class ChildRun {
        private val consumed = mutableListOf<RuntimeEnvelope>()
        private val claimedIds = mutableSetOf<String>()

        fun drain(coordinator: RuntimeSessionCoordinator): List<LLMMessage> =
            RuntimeChildRunner.claimPendingRuntimeMessages(
                runtimeCoordinator = coordinator,
                childSessionId = "child",
                alreadyClaimedIds = claimedIds,
                into = consumed,
            )
    }

    /**
     * The per-message reminder must be built from the coordinator's snapshot AS IT
     * IS WHEN THE MESSAGE IS READ.
     *
     * Behaviour, not file text. The assertion this replaces looked for
     * `runtimeCoordinator.capabilitySnapshot(childSessionId)` inside the 300
     * characters after `ChildCompletionProtocol.reminderFor(` — and production had
     * since extracted that lookup into a local `capabilities`, which is the same
     * behaviour written differently. A character window measures spelling; it does
     * not measure whether the value is live.
     *
     * So this drives the production drain against a real tree and moves the tree
     * between two drains, which is exactly what the code comment claims to handle
     * ("`self_can_delegate` moves as children are spawned and settle"). The first
     * reminder must describe the tree before the spawn, the second the tree after.
     * A run that captured its snapshot once — the birth-injection value, which is
     * what the original assertion was written to keep this call from falling back
     * to — leaves the second reminder describing the old tree and turns this red.
     *
     * Boundary, stated: a snapshot that is re-read once per DRAIN rather than once
     * per envelope is not distinguishable here, because the real tree cannot move
     * between the three lane claims inside one drain. The claim being pinned is
     * "read at message time, not captured at birth", which is the one whose loss
     * produced a child that never learned it had become a manager.
     */
    @Test
    fun `the per-message reminder is built from the coordinator's live snapshot`() {
        val tree = LiveTree()
        try {
            val run = ChildRun()

            assertTrue(tree.coordinator.send("parent", "child", "first", RuntimeDelivery.STEER).accepted)
            val before = run.drain(tree.coordinator).single()
            val beforeSnapshot = tree.coordinator.capabilitySnapshot("child")
            assertTrue("the child must have a live node", beforeSnapshot != null)

            // The tree moves underneath the run: the child takes on a subagent. This
            // is what `currentActiveChildren` and `self_can_delegate` track.
            assertTrue(
                "the fixture must be able to change the child's capabilities",
                tree.coordinator.startChild("child", "grandchild"),
            )
            assertTrue(tree.coordinator.send("parent", "child", "second", RuntimeDelivery.QUEUE).accepted)
            val after = run.drain(tree.coordinator).single()
            val afterSnapshot = tree.coordinator.capabilitySnapshot("child")

            val beforeBlock = ChildCompletionProtocol.capabilityBlock(beforeSnapshot)
            val afterBlock = ChildCompletionProtocol.capabilityBlock(afterSnapshot)
            assertNotEquals(
                "the fixture must genuinely move the tree, or every assertion below is vacuous",
                beforeBlock,
                afterBlock,
            )

            val beforeText = textOf(before)
            val afterText = textOf(after)

            assertTrue(
                "the first reminder must carry the capability block of the tree it was read against:\n$beforeText",
                beforeText.contains(beforeBlock),
            )
            assertTrue(
                "the second reminder must carry the block of the tree as it is NOW, not the one at run start:\n$afterText",
                afterText.contains(afterBlock),
            )
            // Spelling the payload out keeps this meaningful even if the block text is
            // edited: the slot budget must reach the model, not just some shared
            // substring that happens to appear in both versions.
            val beforeSlots = "${beforeSnapshot!!.currentActiveChildren}/${beforeSnapshot.maxParallelSubagents}"
            val afterSlots = "${afterSnapshot!!.currentActiveChildren}/${afterSnapshot.maxParallelSubagents}"
            assertNotEquals("the slot budget must have moved with the tree", beforeSlots, afterSlots)
            assertTrue("the first reminder must carry the slot budget it was built with: $beforeText", beforeText.contains(beforeSlots))
            assertTrue("the second reminder must carry the NEW slot budget: $afterText", afterText.contains(afterSlots))
            assertFalse(
                "a stale snapshot would show the old budget here: $afterText",
                afterText.contains(beforeSlots),
            )
        } finally {
            tree.dispose()
        }
    }

    @Test
    fun `the birth injection is wired to the coordinator snapshot and not to a literal null`() {
        val source = childRunnerSource()
        val snapshotType = "AgentCapabilitySnapshot?"

        // ── 1) which function in this file is the system-prompt builder ──────
        //
        // Found by *shape*, not by name: among the functions that take a nullable
        // capability snapshot, the builder is the one that forwards its own snapshot
        // parameter into a call as that call's single argument — the birth
        // injection. Two functions in this file take the type (`buildSystemPrompt`
        // and the per-message `runtimeMessageFor`); only the birth path forwards it
        // on its own, because the reminder path passes the delivery class alongside
        // it. Naming the builder here would make a rename look like a broken guard;
        // requiring the *forwarding* is the property, and it survives a rename while
        // a deleted injection goes red.
        val declarations = Regex("fun\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(")
            .findAll(codeOf(source)).toList()
        val takers = declarations.map { m ->
            val name = m.groupValues[1]
            name to argsOf(source.substring(m.range.first), name)
        }.filter { (_, params) -> params.count { it.contains(snapshotType) } == 1 }

        val forwarders = takers.mapNotNull { (name, params) ->
            val snapshotParam = params.filter { it.contains(snapshotType) }
                .single().substringBefore(':').trim()
            val body = blockOf(source, "fun $name(")
            val forwards = allCallArgs(body).filter { args ->
                args.size == 1 && args.single() == snapshotParam
            }
            if (forwards.size == 1) Triple(name, snapshotParam, body) else null
        }
        assertEquals(
            "exactly one function in RuntimeChildRunner.kt must be the birth injection: a function " +
                "that takes a single $snapshotType and forwards that parameter into a call as the " +
                "call's only argument. Functions taking the type: ${takers.map { it.first }}; " +
                "forwarding exactly once: ${forwarders.map { it.first }}",
            1,
            forwarders.size,
        )
        val (builderName, snapshotParam, builderBody) = forwarders.single()

        // ── 2) the builder's body hands that snapshot to something ──────────
        //
        // "A call inside the builder receives this builder's own snapshot
        // parameter" — no callee name, no block name, no string literal. The old
        // form (`substringAfter("ChildCompletionProtocol.systemInstruction(")`,
        // then `substringBefore(")")`) took the *first* occurrence anywhere in the
        // file: one comment above the call site spelling that fragment made it
        // green even after the real injection was deleted (measured on a fake tree).
        val builderCalls = allCallArgs(builderBody)
        assertTrue(
            "the child system prompt must be built from more than the fixed preamble: $builderBody",
            builderCalls.size >= 2,
        )
        val forwarding = builderCalls.filter { args -> args.size == 1 && args.single() == snapshotParam }
        assertEquals(
            "the delegated child's system-prompt builder must forward its own $snapshotType " +
                "parameter (`$snapshotParam`) into exactly one call, so the child is told at birth " +
                "whether it is the final executor or a manager. Calls in `$builderName`: $builderCalls",
            1,
            forwarding.size,
        )
        assertNotEquals(
            "the birth injection must not be handed a literal null",
            "null",
            forwarding.single().single(),
        )

        // ── 3) the call site gives the builder a live snapshot ──────────────
        //
        // Comment/string-free code, argument list bounded by its own parentheses,
        // argument matched as a shape (a call on some receiver taking this child's
        // session id) rather than as the coordinator getter's spelling. The old
        // `substringAfter("val systemPrompt = buildSystemPrompt(").take(300)` walked
        // the whole file from the top and was itself satisfied by prose elsewhere.
        val callAt = codeOf(source).indexOf("$builderName(")
        assertTrue("the builder must be called: $builderName", callAt >= 0)
        val callArgs = allCallArgs(source.substring(source.lastIndexOf('\n', callAt) + 1))
            .firstOrNull { it.isNotEmpty() }
            ?: argsOf(source.substring(source.lastIndexOf('\n', callAt) + 1), builderName)
        val liveSnapshotArgs = callArgs.filter {
            Regex("^[A-Za-z_][A-Za-z0-9_]*\\s*\\.\\s*[A-Za-z_][A-Za-z0-9_]*\\s*\\(\\s*childSessionId\\s*\\)$")
                .containsMatchIn(it)
        }
        assertEquals(
            "the call site must hand `$builderName` a live snapshot of THIS child's capabilities — " +
                "a call on some receiver taking childSessionId, evaluated at run start. Args were: " +
                "$callArgs",
            1,
            liveSnapshotArgs.size,
        )
    }
}
