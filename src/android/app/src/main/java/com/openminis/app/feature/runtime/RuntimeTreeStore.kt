package com.openminis.app.feature.runtime

import android.content.Context
import com.openminis.app.data.NextDataRoot
import com.openminis.app.logging.AppLogger
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID

/**
 * Small durable boundary for the recursive runtime tree.
 *
 * The existing chat database owns transcript data. Runtime state is ephemeral
 * orchestration state, so it is persisted as one atomically replaced JSON file:
 * a process death can recover the tree without changing the chat schema.
 */
class RuntimeTreeStore private constructor(
    private val file: File,
    config: RuntimeTreeConfig = RuntimeTreeConfig(),
    /**
     * Where a restore that did not succeed is reported.
     *
     * Injectable for the same reason [replaceFile] is: a unit test cannot read the
     * daily log, and this is the one signal that a persisted tree was discarded.
     * The default writes to the log the rest of the runtime uses.
     *
     * Declared **before** [replaceFile] on purpose. Every caller of [openForTest]
     * passes `replaceFile` as a trailing lambda, and Kotlin binds a trailing lambda to
     * the *last* parameter — so adding this after it silently moved every one of those
     * call sites onto this parameter and left `replaceFile` unpassed.
     */
    private val reportCorruption: (String) -> Unit = { detail -> AppLogger.warning(TAG, detail) },
    private val replaceFile: (File, File) -> Unit = { source, target ->
        Files.move(
            source.toPath(),
            target.toPath(),
            ATOMIC_MOVE,
            REPLACE_EXISTING,
        )
    },
) {
    private val tree = RuntimeSessionTree(config)
    private val lock = Any()

    init {
        synchronized(lock) {
            loadLocked()
        }
    }

    fun snapshot(): RuntimeSessionTree = tree

    fun persist(): Boolean = synchronized(lock) { persistLocked() }

    fun update(block: RuntimeSessionTree.() -> Unit): Boolean = synchronized(lock) {
        val previous = tree.toJson()
        tree.block()
        if (persistLocked()) return@synchronized true
        tree.restoreJson(previous)
        false
    }

    /**
     * Node-lease reconciliation. Expired *message* claim leases are reconciled
     * in the same pass so a message claimed by a claimant that died before
     * consuming it is durably requeued, not just requeued in memory; the return
     * value stays the stale node ids callers already expect.
     */
    fun reconcile(nowMillis: Long = System.currentTimeMillis()): List<String> = synchronized(lock) {
        val stale = tree.reconcileLeases(nowMillis)
        val requeued = tree.reconcileMessageClaims(nowMillis)
        if (stale.isNotEmpty() || requeued.isNotEmpty()) persistLocked()
        stale
    }

    /**
     * Restore the tree from disk.
     *
     * [RuntimeSessionTree.restoreJson] reports failure by returning `false` — it does
     * not throw, because it wraps its own body in `runCatching` — so the `runCatching`
     * that used to be here only ever caught a failing `readText`, and the `false` was
     * discarded. A file that failed validation therefore left the app running on a
     * silently empty tree, with nothing anywhere saying so. `SessionListViewModel`
     * separately logs a degraded read it cannot interpret, which is how this stayed
     * invisible: the tree it was shown really was empty.
     *
     * An empty tree is also the *wrong* resting state, because `restoreJson` clears
     * every collection before it repopulates them: a throw part-way through — an
     * out-of-range config value, or a `nodes` entry that is not an object — leaves a
     * **half-built** tree (some nodes present, the derived `children` index never
     * populated) that is less consistent than an empty one. So a failed restore is
     * followed by a restore of a known-empty document, and the corruption is reported.
     *
     * A missing file is a normal first run and stays silent.
     */
    private fun loadLocked() {
        if (!file.isFile) return
        val raw = runCatching { file.readText(StandardCharsets.UTF_8) }.getOrNull()
        if (raw == null) {
            tree.restoreJson(EMPTY_TREE_JSON)
            reportCorruption("the runtime tree file exists but could not be read")
            return
        }
        if (!tree.restoreJson(raw)) {
            tree.restoreJson(EMPTY_TREE_JSON)
            reportCorruption("the runtime tree file exists but is not a valid runtime tree")
        }
    }

    private fun persistLocked(): Boolean {
        return runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.${UUID.randomUUID()}.tmp")
            try {
                tmp.writeText(tree.toJson(), StandardCharsets.UTF_8)
                replaceFile(tmp, file)
            } finally {
                if (tmp.exists()) tmp.delete()
            }
            true
        }.getOrDefault(false)
    }

    companion object {
        private const val DIRECTORY = "runtime"
        private const val FILE = "session-tree.json"
        private const val TAG = "RuntimeTreeStore"

        /**
         * A well-formed document describing a tree with nothing in it. Used to put the
         * tree back into a consistent state after a partial restore — see [loadLocked].
         */
        private const val EMPTY_TREE_JSON = "{}"

        fun open(context: Context, config: RuntimeTreeConfig = RuntimeTreeConfig()): RuntimeTreeStore =
            RuntimeTreeStore(File(context.applicationContext.filesDir, "$DIRECTORY/$FILE"), config)

        /**
         * Opt-in Next boundary. The existing [open] path is intentionally left
         * unchanged so current runtime state is not migrated implicitly.
         */
        fun openNext(context: Context, config: RuntimeTreeConfig = RuntimeTreeConfig()): RuntimeTreeStore =
            RuntimeTreeStore(NextDataRoot.runtimeTreeFile(context.applicationContext.filesDir), config)

        internal fun openForTest(
            file: File,
            config: RuntimeTreeConfig = RuntimeTreeConfig(),
            reportCorruption: (String) -> Unit = { detail -> AppLogger.warning(TAG, detail) },
            replaceFile: (File, File) -> Unit,
        ): RuntimeTreeStore = RuntimeTreeStore(file, config, reportCorruption, replaceFile)

    }
}
