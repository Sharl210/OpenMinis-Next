package com.openminis.app.sandbox

import java.io.File
import java.lang.reflect.Method
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [T-android-offload-tmp-leak] Discriminators that drive the PRODUCTION sweep.
 *
 * ## Why a second file next to [OffloadReplySweepTest]
 *
 * `OffloadReplySweepTest` re-implements the predicate (`eligible`) inside the test
 * and calls nothing from production. Measured: that file compiles and passes with
 * **zero** production sources on the compile path (`COMPILE_RC=0`, `class=1`,
 * `OK (5 tests)` — only the test class is ever built). It therefore cannot see any
 * change to `NativeOffloadServer.sweepStaleReplies`, and it does not: against
 * widened copies of the production predicate (source copied to /tmp, one clause
 * weakened per run),
 *
 * | widened rule | `OffloadReplySweepTest` (5 tests) | this file |
 * |---|---|---|
 * | prefix filter dropped (`f.isFile` only — claims any file in the shared tmp) | 5 green, 0 red | 1 red |
 * | TTL guard dropped (in-session sweep deletes what `cat` has not read yet) | 5 green, 0 red | 1 red |
 * | `f.isFile` guard dropped (a same-named directory is claimed) | 5 green, 0 red | 1 red |
 *
 * ## How production is reached without touching it
 *
 * The decision lives in a `private fun` on a Kotlin `object` whose target directory
 * is `private var rootfsTmpDir`, so there is no callable seam. This file sets that
 * field and invokes the method reflectively, then looks at the filesystem: the
 * assertions are about which files are still there, i.e. about the real deletion,
 * not about a restated predicate. If the member names change, this file fails
 * loudly (NoSuchField/NoSuchMethod) instead of silently drifting.
 *
 * The prefix literal is read back out of the production source as a premise, so a
 * fixture built on a renamed prefix cannot quietly stop matching production.
 */
class OffloadReplySweepProductionDiscriminatorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val ttlMs = 10 * 60 * 1000L

    /** The production prefix, read out of the production source so a rename fails loudly. */
    private val prefix: String = run {
        val source = File("src/main/java/com/openminis/app/sandbox/NativeOffload.kt")
        assertTrue("premise: production source must be readable at ${source.absolutePath}", source.isFile)
        val match = Regex("""REPLY_PREFIX\s*=\s*"([^"]+)"""").find(source.readText())
        assertTrue("premise: production must still declare REPLY_PREFIX", match != null)
        match!!.groupValues[1]
    }

    private val serverClass: Class<*> get() = Class.forName("com.openminis.app.sandbox.NativeOffloadServer")
    private val serverInstance: Any get() = serverClass.getDeclaredField("INSTANCE").get(null)

    /** Runs the production sweep with its directory pointed at [dir]. */
    private fun sweep(all: Boolean, dir: File) {
        val instance = serverInstance
        serverClass.getDeclaredField("rootfsTmpDir").apply { isAccessible = true }.set(instance, dir)
        val method: Method = serverClass
            .getDeclaredMethod("sweepStaleReplies", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
        method.invoke(instance, all)
    }

    private fun write(name: String, ageMs: Long): File =
        File(tmp.root, name).apply {
            writeText("output")
            setLastModified(System.currentTimeMillis() - ageMs)
        }

    @Test
    fun `the start sweep still removes every reply file regardless of age`() {
        val fresh = write("${prefix}123-1", 0)
        val old = write("${prefix}456-2", 24 * 60 * 60 * 1000L)

        sweep(all = true, dir = tmp.root)

        assertTrue("the start sweep must claim a just-written orphan", !fresh.exists())
        assertTrue("the start sweep must claim an old orphan", !old.exists())
    }

    @Test
    fun `the in-session sweep still spares the reply whose cat has not run yet`() {
        val justWritten = write("${prefix}789-3", 0)
        val secondsOld = write("${prefix}789-4", 5_000L)
        val stale = write("${prefix}111-5", ttlMs + 60_000L)

        sweep(all = false, dir = tmp.root)

        assertTrue(
            "a just-written reply must survive the in-session sweep — deleting it turns the " +
                "offload call into 'No such file or directory'",
            justWritten.exists(),
        )
        assertTrue("a seconds-old reply must still survive", secondsOld.exists())
        assertTrue("a file past the TTL must be claimed", !stale.exists())
    }

    @Test
    fun `unrelated files in the shared tmp are never deleted`() {
        // /tmp is shared with the guest: the sweep may only ever claim files it wrote.
        val userFile = write("important.txt", 24 * 60 * 60 * 1000L)
        val toolFile = write("pip-build-abc", 24 * 60 * 60 * 1000L)

        sweep(all = true, dir = tmp.root)
        sweep(all = false, dir = tmp.root)

        assertTrue("a user's file must survive", userFile.exists())
        assertTrue("a tool's scratch file must survive", toolFile.exists())
    }

    @Test
    fun `a directory named like a reply file is not claimed`() {
        val dir = File(tmp.root, "${prefix}222-6")
        assertTrue("premise: the fixture directory exists", dir.mkdirs())
        assertEquals("premise: it is a directory", true, dir.isDirectory)

        sweep(all = true, dir = tmp.root)

        assertTrue("only regular files are swept", dir.exists())
    }
}
