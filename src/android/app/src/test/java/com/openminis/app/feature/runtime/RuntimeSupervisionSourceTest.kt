package com.openminis.app.feature.runtime

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `supervisedDescendants` is a SCOPED READ of the live runtime tree: the caller
 * names a chat session, and the answer is that session's own descendants and
 * nothing else.
 *
 * ## What this file used to be, and why that was not evidence
 *
 * It read `RuntimeSessionCoordinator.kt` as TEXT and asserted substrings such as
 * `activeRuntimeIds[actorSessionId]` and `store.snapshot().descendants(actor)`.
 * A substring proves only that the characters exist somewhere in the file — a
 * comment, a doc string, or a branch production never evaluates satisfies it
 * just as well. Neither assertion observed a single node coming out of the
 * read, so a coordinator that returned every node in the tree, or none, or
 * another root's children, would have kept both green.
 *
 * ## What replaces it
 *
 * The real coordinator, over a real runtime tree on disk, with two roots in it.
 * Each assertion below is a set of node ids that actually came back:
 *
 *  - the actor's own subtree, including a grandchild (so this is a *subtree*
 *    read, not a children read);
 *  - an unrelated root's child, which must NOT appear — the old file had no
 *    assertion that could tell these two apart;
 *  - a blank or unknown actor, which must supervise nothing rather than fall
 *    back to "the whole tree";
 *  - the session→runtime-node mapping, exercised the one way it is observable:
 *    a conversation that ran twice has its children under `chat#run-N`, not
 *    under the plain session id, so a read that skipped the mapping would come
 *    back empty while the raw-id node sits there with no children of its own.
 */
class RuntimeSupervisionSourceTest {

    /**
     * A throw-away app context. The directory fields are deliberately NOT named
     * `filesDir` / `cacheDir`: `Context` already declares those names, so inside
     * the anonymous wrapper below an unqualified `filesDir` would resolve to the
     * wrapper's own property and `override fun getFilesDir() = filesDir` would
     * recurse until the stack died.
     */
    private class TestRoot {
        val dir: File = Files.createTempDirectory("runtime-supervision").toFile()
        val filesRoot: File = File(dir, "files").apply { mkdirs() }

        fun context(): Context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = filesRoot
        }

        fun coordinator(): RuntimeSessionCoordinator = RuntimeSessionCoordinator.open(context())

        fun dispose() {
            dir.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------ scoping

    @Test
    fun `supervisedDescendants returns the actor's own subtree and no other root's`() {
        val root = TestRoot()
        try {
            val coordinator = root.coordinator()
            assertEquals("root-a", coordinator.startRoot("root-a"))
            assertTrue(coordinator.startChild("root-a", "child-a"))
            assertTrue(coordinator.startChild("child-a", "grand-a"))
            assertEquals("root-b", coordinator.startRoot("root-b"))
            assertTrue(coordinator.startChild("root-b", "child-b"))

            assertEquals(
                "the read must be the actor's whole subtree — a grandchild is part of it",
                listOf("child-a", "grand-a"),
                coordinator.supervisedDescendants("root-a").map { it.id },
            )
            assertFalse(
                "a sibling root's child must not be supervised by this actor",
                coordinator.supervisedDescendants("root-a").any { it.id == "child-b" },
            )
            assertEquals(
                "and the sibling root supervises only its own",
                listOf("child-b"),
                coordinator.supervisedDescendants("root-b").map { it.id },
            )
        } finally {
            root.dispose()
        }
    }

    @Test
    fun `a branch supervises its own subtree, not a sibling branch of the same conversation`() {
        val root = TestRoot()
        try {
            val coordinator = root.coordinator()
            assertEquals("root-a", coordinator.startRoot("root-a"))
            assertTrue(coordinator.startChild("root-a", "child-a"))
            // A SECOND branch under the same root, i.e. the same conversation.
            assertTrue(coordinator.startChild("root-a", "child-b"))
            assertTrue(coordinator.startChild("child-a", "grand-a"))

            // The actor is a branch, not a conversation root.
            assertEquals(
                "a branch supervises its own descendants and nothing beside them",
                listOf("grand-a"),
                coordinator.supervisedDescendants("child-a").map { it.id },
            )
            // `child-b` is in the SAME conversation as the actor but is not in the
            // actor's subtree. That combination is what makes this the
            // discriminating shape for the scoping rule: every other actor in this
            // file is a conversation root, and an unrelated root stays excluded
            // whether the rule is "my subtree" or the wider "my whole
            // conversation". Measured: widening the read to conversation scope
            // leaves the file's other tests green and only this one red.
            assertFalse(
                "a sibling branch is in the same conversation but still not mine to supervise",
                coordinator.supervisedDescendants("child-a").any { it.id == "child-b" },
            )
            // Scoping is by DESCENT, so an ancestor is not a supervisor's subject
            // either. A conversation-scoped read returns the root here as well.
            assertFalse(
                "an ancestor is not a descendant of the branch it heads",
                coordinator.supervisedDescendants("child-a").any { it.id == "root-a" },
            )

            // Control: the conversation root still supervises both branches, so
            // the narrow result above is not an empty or broken tree. This holds
            // under both rules, which is why it cannot replace the assertions
            // above. Order-insensitive on purpose: the two rules return the same
            // SET here in a different order.
            assertEquals(
                "the conversation root supervises both branches",
                listOf("child-a", "child-b", "grand-a"),
                coordinator.supervisedDescendants("root-a").map { it.id }.sorted(),
            )
        } finally {
            root.dispose()
        }
    }

    @Test
    fun `an actor with no runtime node supervises nothing rather than everything`() {
        val root = TestRoot()
        try {
            val coordinator = root.coordinator()
            assertEquals("root-a", coordinator.startRoot("root-a"))
            assertTrue(coordinator.startChild("root-a", "child-a"))

            assertTrue(
                "a blank actor id is not a wildcard: ${coordinator.supervisedDescendants("").map { it.id }}",
                coordinator.supervisedDescendants("").isEmpty(),
            )
            assertTrue(
                "whitespace is not a wildcard either",
                coordinator.supervisedDescendants("   ").isEmpty(),
            )
            assertTrue(
                "a session the runtime tree has never seen has no descendants — and must not " +
                    "fall back to every node in the tree: ${coordinator.supervisedDescendants("never-started").map { it.id }}",
                coordinator.supervisedDescendants("never-started").isEmpty(),
            )
        } finally {
            root.dispose()
        }
    }

    // ------------------------------------------------- the session→node mapping

    @Test
    fun `the actor is resolved to its live runtime node, not to the raw session id`() {
        val root = TestRoot()
        try {
            val coordinator = root.coordinator()
            assertEquals("chat", coordinator.startRoot("chat"))
            coordinator.finishRoot("chat")
            // A second run of the same conversation mints `chat#run-N`; this run's
            // children hang off THAT node, exactly as the delete/resolve paths
            // assume (`SessionSubtreeDeletionPlanTest` pins the same shape).
            assertEquals("chat#run-1", coordinator.startRoot("chat"))
            assertTrue(coordinator.startChild("chat", "child"))

            assertEquals(
                "fixture: the finished first run kept no children, so reading through the raw " +
                    "session id would have to answer empty",
                emptyList<String>(),
                coordinator.topologySnapshot().nodes.filter { it.parentId == "chat" }.map { it.id },
            )
            assertEquals(
                "the read must follow the session→runtime mapping; skipping it would report an " +
                    "actor that has a live child as having none",
                listOf("child"),
                coordinator.supervisedDescendants("chat").map { it.id },
            )
        } finally {
            root.dispose()
        }
    }
}
