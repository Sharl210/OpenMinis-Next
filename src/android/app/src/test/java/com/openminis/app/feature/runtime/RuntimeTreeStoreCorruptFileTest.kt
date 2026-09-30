package com.openminis.app.feature.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-runtime-tree-corrupt-restore] A runtime tree that cannot be restored
 * must be *reported*, and must leave a consistent tree behind.
 *
 * ## What was wrong
 *
 * `RuntimeTreeStore.loadLocked` used to be:
 *
 * ```kotlin
 * runCatching { tree.restoreJson(file.readText(StandardCharsets.UTF_8)) }
 * ```
 *
 * `RuntimeSessionTree.restoreJson` reports failure by **returning `false`** — it wraps
 * its own body in `runCatching`, so it never throws. The `runCatching` here therefore
 * only ever caught a failing `readText`, and the `false` was **discarded**: a file that
 * failed validation left the app on a silently empty tree with nothing anywhere saying
 * so. Nothing else could notice either — a caller asking the tree for its topology gets
 * the same answer ("no nodes") for "the tree really is empty" and "the tree on disk was
 * not seen", which is exactly what [RuntimeTreeStoreEmptyTreeFileTest] pins as the
 * reason a file-size probe cannot carry that verdict.
 *
 * ## Why the fix is not just a log line
 *
 * `restoreJson` clears every collection **before** it repopulates them, and it can
 * still throw after that point — an out-of-range config value (`require` on `maxDepth`,
 * `maxParallelSubagents`, `leaseMillis`), or a `nodes` entry that is not a JSON object
 * (`JSONArray.getJSONObject(i)`). So a failed restore can leave a **half-built** tree:
 * some nodes present, `rootId` set, and the derived `children` index never populated
 * (the parent-linking pass runs after the whole node loop). That is *less* consistent
 * than an empty tree, so the fix restores a known-empty document after a failure
 * instead of leaving whatever the partial restore produced.
 *
 * The last test below is the discriminating one: without the reset the tree keeps the
 * node that the partial restore managed to add before it failed.
 */
class RuntimeTreeStoreCorruptFileTest {

    /** Captures the store's own signal that it discarded a persisted tree. */
    private class Reports {
        val messages = mutableListOf<String>()
        fun record(detail: String) {
            messages.add(detail)
        }
    }

    private fun replaceAtomically(source: File, target: File) {
        Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    }

    private fun storeIn(dir: File, name: String, reports: Reports): Pair<RuntimeTreeStore, File> {
        val file = File(dir, name)
        val store = RuntimeTreeStore.openForTest(
            file,
            replaceFile = ::replaceAtomically,
            reportCorruption = reports::record,
        )
        return store to file
    }

    private fun tempDir(): File = Files.createTempDirectory("runtime-tree-corrupt").toFile()

    private fun nodeCount(store: RuntimeTreeStore): Int = store.snapshot().topology().nodes.size

    @Test
    fun `a missing file is a first run and is not reported as corruption`() {
        val dir = tempDir()
        try {
            val reports = Reports()
            val (store, file) = storeIn(dir, "absent.json", reports)
            assertFalse("fixture: the file must not exist", file.exists())
            assertEquals("a first run must not be reported", emptyList<String>(), reports.messages)
            assertEquals("a first run starts from an empty tree", 0, nodeCount(store))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a valid tree restores without reporting anything`() {
        val dir = tempDir()
        try {
            val file = File(dir, "valid.json")
            val writer = RuntimeTreeStore.openForTest(file, replaceFile = ::replaceAtomically)
            assertTrue(
                "fixture: writing a real tree has to succeed",
                writer.update { createRoot("root", RuntimeModelSnapshot("p", "m")) },
            )

            val reports = Reports()
            val reader = RuntimeTreeStore.openForTest(
                file,
                replaceFile = ::replaceAtomically,
                reportCorruption = reports::record,
            )
            assertEquals("a healthy restore must stay silent", emptyList<String>(), reports.messages)
            assertEquals("the persisted root must come back", 1, nodeCount(reader))
            assertEquals("root", reader.snapshot().topology().nodes.single().id)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a file that is not JSON at all is reported`() {
        val dir = tempDir()
        try {
            val reports = Reports()
            val file = File(dir, "garbage.json")
            file.writeText("this is not a runtime tree")

            val store = RuntimeTreeStore.openForTest(
                file,
                replaceFile = ::replaceAtomically,
                reportCorruption = reports::record,
            )

            assertEquals("exactly one report", 1, reports.messages.size)
            assertEquals("an unparseable tree starts empty", 0, nodeCount(store))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a restore that fails part-way is reported and leaves no half-built tree`() {
        val dir = tempDir()
        try {
            val reports = Reports()
            val file = File(dir, "half.json")
            // Parses as JSON, so `restoreJson` gets past `JSONObject(raw)`, clears the
            // tree, and adds the first node — then `getJSONObject(1)` throws because
            // the second element is a string, not an object. That is the half-built
            // state: `nodes` has one entry and the parent-linking pass never ran.
            file.writeText("""{"nodes":[{"id":"ghost","rootId":"ghost"}, "not-an-object"]}""")

            val store = RuntimeTreeStore.openForTest(
                file,
                replaceFile = ::replaceAtomically,
                reportCorruption = reports::record,
            )

            assertEquals("exactly one report", 1, reports.messages.size)
            assertEquals(
                "a partial restore must not be left standing: without the reset this " +
                    "is 1, because the node the partial restore added survives",
                0,
                nodeCount(store),
            )
            // ...and the tree must still be usable, not merely empty-looking.
            assertTrue(
                "an empty tree restored after corruption must accept new work",
                store.update { createRoot("fresh", RuntimeModelSnapshot("p", "m")) },
            )
            assertEquals(1, nodeCount(store))
        } finally {
            dir.deleteRecursively()
        }
    }
}
