package com.openminis.app.feature.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [R1/R10] The configured delegation depth must actually bound the tree, and the
 * refusal must be *this* refusal.
 *
 * ## The defect this file pins
 *
 * `RuntimeSessionTree.createChild` refuses a child whose parent already sits at
 * `config.maxDepth`:
 *
 * ```kotlin
 * if (config.maxDepth == 0 || parent.depth + 1 > config.maxDepth) {
 *     return Result.failure(IllegalStateException("delegation depth limit reached"))
 * }
 * ```
 *
 * Measured before this file existed: the string `delegation depth limit reached`
 * appeared **zero** times under `src/test` and `src/androidTest`, while
 * `createChild` appeared 97 times. Deleting that whole `if` left the entire suite
 * green — "is the depth setting doing anything?" had no answer in the test suite.
 *
 * ## Why the assertions are shaped the way they are
 *
 * A bare `assertTrue(result.isFailure)` would **not** pin this branch. The very
 * next check in `createChild` is
 *
 * ```kotlin
 * if (capabilitySnapshot(parent.id)?.selfCanDelegate != true) { … "parent cannot delegate …" }
 * ```
 *
 * and `selfCanDelegate` is derived from the same depth arithmetic
 * (`effectiveDepth = maxDepth - node.depth`, `> 0`). So at the depth boundary a
 * *deleted* depth check is still a rejection — of the wrong kind, with the wrong
 * message, and (measured) with no test noticing. Every refusal assertion below
 * therefore pins the **class and message** of the failure, which is the only
 * thing that distinguishes "the depth limit refused this" from "something else
 * happened to refuse this for a different reason".
 *
 * The file also asserts the boundary from **both** sides: the deepest *permitted*
 * level must be created successfully. Without that half, a check relaxed by one
 * (`>` loosened to `>=`-style arithmetic) would keep every "refused" assertion
 * green while the user's configured depth silently lost a level.
 *
 * Nothing here is a source-text assertion: `RuntimeSessionTree` is a pure JVM
 * object (its only Android-facing input is a `clock` lambda), so this is real
 * production code under test.
 */
class DelegationDepthLimitTest {

    private val model = RuntimeModelSnapshot(provider = "test", model = "model")

    /**
     * A refusal caused by the depth limit, not by the capability rail that sits
     * one line below it.
     */
    private fun assertDepthRefusal(failure: Result<RuntimeSessionNode>) {
        val error = failure.exceptionOrNull()
        assertTrue(
            "expected a failed createChild, got ${failure.getOrNull()}",
            failure.isFailure,
        )
        assertEquals(IllegalStateException::class.java, error?.javaClass)
        assertEquals("delegation depth limit reached", error?.message)
    }

    @Test
    fun `default depth of two admits a grandchild and refuses the fourth level`() {
        val tree = RuntimeSessionTree(RuntimeTreeConfig(maxDepth = 2, maxParallelSubagents = 5))
        val root = tree.createRoot("root", model)
        assertEquals(0, root.depth)

        val child = tree.createChild(root.id, "child", model).getOrThrow()
        assertEquals(1, child.depth)

        // The deepest level the setting permits: depth == maxDepth. A check that
        // refuses one level too early fails HERE.
        val grandchild = tree.createChild(child.id, "grandchild", model).getOrThrow()
        assertEquals(2, grandchild.depth)

        // One level past the setting: depth would be maxDepth + 1.
        assertDepthRefusal(tree.createChild(grandchild.id, "great-grandchild", model))
        assertNull(tree.node("great-grandchild"))
    }

    @Test
    fun `maxDepth zero refuses every child of the root`() {
        val tree = RuntimeSessionTree(RuntimeTreeConfig(maxDepth = 0, maxParallelSubagents = 5))
        val root = tree.createRoot("root", model)

        assertDepthRefusal(tree.createChild(root.id, "child", model))
        assertNull(tree.node("child"))
        assertEquals(
            "a refused child must not reach the topology",
            listOf("root"),
            tree.topology().nodes.map { it.id },
        )
    }

    @Test
    fun `a refused child leaves no node and no child edge behind`() {
        val tree = RuntimeSessionTree(RuntimeTreeConfig(maxDepth = 1, maxParallelSubagents = 5))
        val root = tree.createRoot("root", model)
        val child = tree.createChild(root.id, "child", model).getOrThrow()

        val topologyBefore = tree.topology()
        val eventsBefore = tree.events().size

        assertDepthRefusal(tree.createChild(child.id, "grandchild", model))

        assertNull(tree.node("grandchild"))
        assertEquals(topologyBefore.nodes.map { it.id }.toSet(), tree.topology().nodes.map { it.id }.toSet())
        assertEquals(
            "the refusal is a returned failure, not a half-created node",
            topologyBefore.edges.size,
            tree.topology().edges.size,
        )
        assertEquals(eventsBefore, tree.events().size)
        assertEquals(listOf("child"), tree.descendants("root").map { it.id })
    }

    @Test
    fun `raising maxDepth at runtime admits the next level`() {
        val tree = RuntimeSessionTree(RuntimeTreeConfig(maxDepth = 1, maxParallelSubagents = 5))
        val root = tree.createRoot("root", model)
        val child = tree.createChild(root.id, "child", model).getOrThrow()

        assertDepthRefusal(tree.createChild(child.id, "grandchild", model))

        assertTrue(tree.updateConfig(maxDepth = 2))
        val grandchild = tree.createChild(child.id, "grandchild", model).getOrThrow()
        assertEquals(2, grandchild.depth)

        assertTrue(tree.updateConfig(maxDepth = 0))
        assertDepthRefusal(tree.createChild(grandchild.id, "great-grandchild", model))
    }
}
