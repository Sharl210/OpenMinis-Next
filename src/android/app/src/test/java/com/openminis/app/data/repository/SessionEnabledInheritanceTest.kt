package com.openminis.app.data.repository

import com.openminis.app.feature.runtime.RuntimeModelSnapshot
import com.openminis.app.feature.runtime.RuntimeSessionNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * [T-skill-mcp-session-switch-inheritance] JVM coverage for the parent-chain
 * resolution shared by [SkillRepository] and [MCPRepository].
 *
 * The chain itself (which session hangs under which) is injected as a plain map
 * here, so these tests pin the *rules* — precedence, live re-reads, top-down
 * direction, and the safety guarantees — without touching SQLite, the runtime
 * tree, or Android.
 */
class SessionEnabledInheritanceTest {

    // -- Precedence --

    @Test
    fun `own override beats parent override and global`() {
        val parents = mapOf("child" to "parent")
        val overrides = mapOf("child" to false, "parent" to true)

        assertFalse(resolve("child", parents = parents, overrides = overrides, global = true))
    }

    @Test
    fun `own override turned on wins even when an ancestor turned the entry off`() {
        val parents = mapOf("child" to "parent")
        val overrides = mapOf("child" to true, "parent" to false)

        assertTrue(resolve("child", parents = parents, overrides = overrides, global = true))
    }

    // -- Inheritance --

    @Test
    fun `child without an override inherits the parent`() {
        val parents = mapOf("child" to "parent")
        val overrides = mapOf("parent" to false)

        assertFalse(resolve("child", parents = parents, overrides = overrides, global = true))
    }

    @Test
    fun `grandchild inherits the grandparent through the middle session`() {
        val parents = mapOf("grandchild" to "child", "child" to "parent")
        val overrides = mapOf("parent" to false)

        assertFalse(resolve("grandchild", parents = parents, overrides = overrides, global = true))
        assertFalse(resolve("child", parents = parents, overrides = overrides, global = true))
    }

    @Test
    fun `nearest ancestor override wins over a higher one`() {
        val parents = mapOf("grandchild" to "child", "child" to "parent")
        val overrides = mapOf("grandchild" to true, "child" to true, "parent" to false)

        assertTrue(resolve("grandchild", parents = parents, overrides = overrides, global = false))
    }

    /** The requirement's exact scenario: the main conversation turns an entry
     *  off, and the sub-agent sessions below it lose access too. */
    @Test
    fun `root conversation turning an entry off reaches the whole subtree`() {
        val parents = mapOf("child" to "root", "grandchild" to "child")
        val overrides = mapOf("root" to false)

        assertFalse(resolve("root", parents = parents, overrides = overrides, global = true))
        assertFalse(resolve("child", parents = parents, overrides = overrides, global = true))
        assertFalse(resolve("grandchild", parents = parents, overrides = overrides, global = true))
    }

    // -- Live re-read (no snapshot at spawn time) --

    @Test
    fun `an ancestor change is visible to the descendant on its next read`() {
        val parents = mapOf("grandchild" to "child", "child" to "parent")
        val overrides = mutableMapOf<String, Boolean>()

        // Nothing overridden yet: everyone follows the global default.
        assertTrue(resolve("grandchild", parents = parents, overrides = overrides, global = true))

        // The ancestor flips it off — the descendant was never re-created and
        // holds no copy, so the next read must already see the new value.
        overrides["parent"] = false
        assertFalse(resolve("grandchild", parents = parents, overrides = overrides, global = true))

        // And a later flip back propagates just as immediately.
        overrides["parent"] = true
        assertTrue(resolve("grandchild", parents = parents, overrides = overrides, global = true))
    }

    // -- Top-down only --

    @Test
    fun `a child override never moves the parent`() {
        val parents = mapOf("child" to "parent")
        val overrides = mutableMapOf("child" to false)

        assertFalse(resolve("child", parents = parents, overrides = overrides, global = true))
        assertTrue(resolve("parent", parents = parents, overrides = overrides, global = true))
    }

    @Test
    fun `sibling branches do not see each other's overrides`() {
        val parents = mapOf("siblingA" to "parent", "siblingB" to "parent")
        val overrides = mapOf("siblingA" to false)

        assertFalse(resolve("siblingA", parents = parents, overrides = overrides, global = true))
        assertTrue(resolve("siblingB", parents = parents, overrides = overrides, global = true))
    }

    // -- Safety --

    @Test
    fun `a two node cycle terminates and still honours overrides inside it`() {
        val parents = mapOf("a" to "b", "b" to "a")

        assertTrue(resolve("a", parents = parents, overrides = emptyMap(), global = true))
        // The override found on the far side of the loop still applies.
        assertFalse(resolve("a", parents = parents, overrides = mapOf("b" to false), global = true))
    }

    @Test
    fun `a self referential parent link terminates`() {
        val parents = mapOf("a" to "a")

        assertTrue(resolve("a", parents = parents, overrides = emptyMap(), global = true))
    }

    @Test
    fun `an unbounded parent chain stops at the depth ceiling`() {
        val hops = AtomicInteger(0)
        val value = resolveInheritedEnabled(
            sessionId = "start",
            parentOf = { hops.incrementAndGet(); "$it-next" },
            overrideOf = { null },
            globalDefault = { true },
        )

        assertTrue(value)
        assertTrue(
            "walked ${hops.get()} hops, ceiling is $MAX_INHERITED_SESSION_CHAIN_DEPTH",
            hops.get() <= MAX_INHERITED_SESSION_CHAIN_DEPTH,
        )
    }

    @Test
    fun `a dangling parent id falls back to the global default`() {
        // The link exists but points at a session that is not in the tree.
        val parents = mapOf("child" to "ghost")

        assertFalse(resolve("child", parents = parents, overrides = emptyMap(), global = false))
        assertTrue(resolve("child", parents = parents, overrides = emptyMap(), global = true))
    }

    @Test
    fun `a session the runtime tree does not know falls back to the global default`() {
        assertTrue(resolve("draft__new__abc", global = true))
        assertFalse(resolve("draft__new__abc", global = false))
    }

    @Test
    fun `the global default terminates the chain and missing entries read as off`() {
        val parents = mapOf("child" to "parent")
        val overrides = mapOf("child" to false, "parent" to true)
        // Not every ancestor is consulted once the global default answers.
        assertTrue(resolve("parent", parents = parents, overrides = overrides, global = false))

        assertFalse(resolve("orphan", overrides = emptyMap(), global = null))
    }

    // -- Runtime tree → parent index --

    @Test
    fun `parent index keeps child under root`() {
        val index = sessionParentIndex(
            listOf(
                node(id = "root", parentId = null),
                node(id = "child", parentId = "root"),
            )
        )

        assertEquals("root", index["child"])
        assertNull(index["root"])
    }

    /** A repeated run of the same conversation gets a fresh `#run-<n>` root; the
     *  child's link to it must still reach the conversation's own overrides,
     *  which are keyed by the plain session id. */
    @Test
    fun `parent index folds run suffixed roots back onto the conversation`() {
        val index = sessionParentIndex(
            listOf(
                node(id = "root#run-2", parentId = null),
                node(id = "child", parentId = "root#run-2"),
                node(id = "grandchild", parentId = "child"),
            )
        )

        assertEquals("root", index["child"])
        assertEquals("child", index["grandchild"])
        assertNull(index["root"])

        // End to end: the conversation's override reaches the grandchild even
        // though the runtime root carries a run suffix.
        val overrides = mapOf("root" to false)
        assertFalse(
            resolveInheritedEnabled(
                sessionId = "grandchild",
                parentOf = index::get,
                overrideOf = overrides::get,
                globalDefault = { true },
            )
        )
    }

    /** A link to a node that is not in the snapshot must be dropped, not walked. */
    @Test
    fun `parent index drops links whose parent node is absent`() {
        val index = sessionParentIndex(
            listOf(
                node(id = "root", parentId = null),
                node(id = "child", parentId = "deleted-parent"),
            )
        )

        assertNull(index["child"])

        // End to end: the stale link degrades to "no parent" → global default.
        assertTrue(
            resolveInheritedEnabled(
                sessionId = "child",
                parentOf = index::get,
                overrideOf = { null },
                globalDefault = { true },
            )
        )
    }

    @Test
    fun `a session id that merely contains the run marker is left alone`() {
        assertEquals("abc", canonicalRuntimeSessionId("abc"))
        assertEquals("abc", canonicalRuntimeSessionId("abc#run-4"))
        assertEquals("abc#run-x", canonicalRuntimeSessionId("abc#run-x"))
        assertEquals("#run-4", canonicalRuntimeSessionId("#run-4"))
    }

    // -- Write side: when does a session need its own row? --

    /**
     * The write-side half of the guarantee. A row that merely repeats the
     * inherited value pins the session and stops the ancestor reaching it, so
     * "no divergence" must mean "no row".
     */
    @Test
    fun `no row is written when the chosen value already matches the inherited one`() {
        assertFalse(shouldPinSessionOverride(enabled = true, inheritedValue = true))
        assertFalse(shouldPinSessionOverride(enabled = false, inheritedValue = false))
    }

    @Test
    fun `a row is written when the session genuinely diverges`() {
        assertTrue(shouldPinSessionOverride(enabled = false, inheritedValue = true))
        assertTrue(shouldPinSessionOverride(enabled = true, inheritedValue = false))
    }

    @Test
    fun `an entry the app does not know is always pinned`() {
        assertTrue(shouldPinSessionOverride(enabled = true, inheritedValue = null))
        assertTrue(shouldPinSessionOverride(enabled = false, inheritedValue = null))
    }

    @Test
    fun `inherited value comes from the nearest ancestor, not the global default`() {
        val parents = mapOf("grandchild" to "child", "child" to "parent")
        val overrides = mapOf("parent" to false)

        // The grandparent said off; a global "on" must not override that.
        assertFalse(
            inheritedValue("grandchild", parents = parents, overrides = overrides, global = true)!!
        )
        // And with nothing overridden it does fall through to the global default.
        assertTrue(
            inheritedValue("grandchild", parents = parents, overrides = emptyMap(), global = true)!!
        )
        // A session with no parent simply takes the global default.
        assertFalse(
            inheritedValue("root", parents = parents, overrides = overrides, global = false)!!
        )
        // An unknown entry reports "unknown" rather than a made-up value.
        assertNull(inheritedValue("root", parents = parents, overrides = emptyMap(), global = null))
    }

    /** End to end on the rule that makes propagation survive a child's own visit
     *  to the toggle: child turns the entry ON while the parent is ON → no row →
     *  the parent later turning OFF still reaches the child. */
    @Test
    fun `a child re-picking the inherited value keeps following its parent`() {
        val parents = mapOf("child" to "parent")
        val overrides = mutableMapOf("parent" to true)

        val chosen = true
        val inherited = inheritedValue("child", parents = parents, overrides = overrides, global = true)!!
        assertEquals(chosen, inherited)
        assertFalse(shouldPinSessionOverride(chosen, inherited))
        // No row was written, so the parent's later flip still propagates.
        overrides["parent"] = false
        assertFalse(resolve("child", parents = parents, overrides = overrides, global = true))
    }

    // -- Helpers --

    private fun resolve(
        sessionId: String,
        parents: Map<String, String> = emptyMap(),
        overrides: Map<String, Boolean> = emptyMap(),
        global: Boolean? = true,
    ): Boolean = resolveInheritedEnabled(
        sessionId = sessionId,
        parentOf = parents::get,
        overrideOf = overrides::get,
        globalDefault = { global },
    )

    private fun inheritedValue(
        sessionId: String,
        parents: Map<String, String> = emptyMap(),
        overrides: Map<String, Boolean> = emptyMap(),
        global: Boolean? = true,
    ): Boolean? = resolveInheritedValue(
        sessionId = sessionId,
        parentOf = parents::get,
        overrideOf = overrides::get,
        globalDefault = { global },
    )

    private fun node(id: String, parentId: String?): RuntimeSessionNode = RuntimeSessionNode(
        id = id,
        parentId = parentId,
        rootId = id.substringBefore("#run-"),
        depth = if (parentId == null) 0 else 1,
        model = RuntimeModelSnapshot(provider = "test", model = "test-model"),
        createdAtMillis = 0L,
        updatedAtMillis = 0L,
    )
}
