package com.openminis.app.feature.runtime

import android.content.Context
import com.openminis.app.data.NextDataRoot
import java.io.File
import java.nio.charset.StandardCharsets

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
        tree.block()
        persistLocked()
    }

    fun reconcile(nowMillis: Long = System.currentTimeMillis()): List<String> = synchronized(lock) {
        val stale = tree.reconcileLeases(nowMillis)
        if (stale.isNotEmpty()) persistLocked()
        stale
    }

    private fun loadLocked() {
        if (!file.isFile) return
        runCatching {
            tree.restoreJson(file.readText(StandardCharsets.UTF_8))
        }
    }

    private fun persistLocked(): Boolean {
        return runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(tree.toJson(), StandardCharsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.delete()
                check(tmp.renameTo(file)) { "runtime tree rename failed" }
            }
            true
        }.getOrDefault(false)
    }

    companion object {
        private const val DIRECTORY = "runtime"
        private const val FILE = "session-tree.json"

        fun open(context: Context, config: RuntimeTreeConfig = RuntimeTreeConfig()): RuntimeTreeStore =
            RuntimeTreeStore(File(context.applicationContext.filesDir, "$DIRECTORY/$FILE"), config)

        /**
         * Opt-in Next boundary. The existing [open] path is intentionally left
         * unchanged so current runtime state is not migrated implicitly.
         */
        fun openNext(context: Context, config: RuntimeTreeConfig = RuntimeTreeConfig()): RuntimeTreeStore =
            RuntimeTreeStore(NextDataRoot.runtimeTreeFile(context.applicationContext.filesDir), config)
    }
}
