package com.openminis.app.feature.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Why "the tree file has content" is not a usable *failure* signal for the
 * `runtime_tree` step of a conversation delete.
 *
 * `RuntimeSessionCoordinator.topologySnapshot()` returns the same thing — no
 * nodes — in two situations that need opposite handling:
 *
 *  - the tree is perfectly readable and genuinely holds nothing, which is the
 *    normal state for a conversation that predates the runtime tree, and for any
 *    moment after the last node was purged;
 *  - the file could not be parsed (`RuntimeTreeStore.loadLocked` swallows the
 *    error and leaves the tree empty), so nodes that are still on disk were
 *    simply not seen.
 *
 * `SessionListViewModel` tells them apart with a cheap existence/size probe
 * (`runtimeTreeFileHasContent`) instead of parsing the file — deliberately, so
 * as not to become a second, divergent reader of a format the store owns. That
 * probe is fine for the log line it was written for, but it cannot carry a
 * *verdict*, because the first situation also writes a non-empty file: an empty
 * tree still serializes `rootId` / `rootIds` / `config`. Escalating the probe
 * into "the step failed" would therefore leave a permanent `runtime_tree` debt
 * on every delete performed while the tree happens to be empty — a delete that
 * could never succeed, because there is nothing to purge and nothing to fix.
 *
 * This pins that fact on the real store so the next person considering that
 * escalation sees it before shipping it.
 */
class RuntimeTreeStoreEmptyTreeFileTest {

    private fun storeIn(dir: File, name: String): Pair<RuntimeTreeStore, File> {
        val file = File(dir, name)
        val store = RuntimeTreeStore.openForTest(file) { source, target ->
            Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        }
        return store to file
    }

    @Test
    fun `an empty tree and an unreadable tree are indistinguishable to a size probe`() {
        val dir = Files.createTempDirectory("runtime-tree-probe").toFile()
        try {
            // (1) A tree that is readable and holds nothing: persisted normally.
            val (emptyStore, emptyFile) = storeIn(dir, "readable-empty.json")
            assertTrue("fixture: persisting an empty tree has to succeed", emptyStore.update { })
            assertEquals(
                "fixture: the persisted tree really holds no nodes",
                0,
                emptyStore.snapshot().topology().nodes.size,
            )

            // (2) A tree file that cannot be parsed: the store swallows the failure
            // and reports the same empty tree. This is the state the delete path
            // wants to treat as "sub-agents could not be resolved".
            val corruptFile = File(dir, "unreadable.json")
            corruptFile.writeText("{ this is not the tree format")
            val (corruptStore, _) = storeIn(dir, "unreadable.json")
            assertEquals(
                "fixture: a malformed file also reads back as a tree with no nodes",
                0,
                corruptStore.snapshot().topology().nodes.size,
            )

            // The two states differ in every way that matters, and identically in
            // every way the probe can see.
            assertTrue("the readable-but-empty file is on disk", emptyFile.isFile)
            assertTrue(
                "an empty tree still writes ${emptyFile.length()} bytes (rootId/rootIds/config), so " +
                    "'the tree file has content' is true for a tree that is simply empty",
                emptyFile.length() > 0L,
            )
            assertTrue(
                "the unreadable file looks the same to a size probe (${corruptFile.length()} bytes)",
                corruptFile.length() > 0L,
            )
        } finally {
            dir.deleteRecursively()
        }
    }
}
