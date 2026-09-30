package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-android-child-restart] A restarted child has to be re-run on the task it was
 * actually given — or not re-run at all.
 *
 * `RuntimeSessionNode.task` looks like the place to read that from, and it is
 * not: `SessionTreeRuntime.createChild` defaults `task` to `model.note`, and
 * `RuntimeSessionCoordinator.delegate` fills that note from the composer's
 * `--note=` option. So for a plain `/subagent …` command the node's task is
 * EMPTY, and a restart driven by it alone would relaunch the child with no idea
 * what it was asked to do.
 *
 * The child's own opening transcript row does hold it: `RuntimeChildRunner`
 * writes the prompt as the session's first message before the model is called and
 * never rewrites it, so it survives the interruption, the restart and app death.
 * Hence the order pinned below, and the `null` case: with no task text anywhere,
 * the launcher refuses rather than inventing one.
 */
class RestartedChildTaskPromptTest {

    @Test
    fun `the child's own opening message wins over the node's task field`() {
        assertEquals(
            "what the child was actually delegated with",
            "inspect the runtime tree and report the interrupted children",
            restartedChildTaskPrompt(
                fromNode = "review this",
                fromTranscript = "inspect the runtime tree and report the interrupted children",
            ),
        )
    }

    @Test
    fun `the node's task field is the fallback when the transcript has nothing`() {
        assertEquals("review this", restartedChildTaskPrompt(fromNode = "review this", fromTranscript = null))
        assertEquals("review this", restartedChildTaskPrompt(fromNode = "review this", fromTranscript = "   "))
    }

    @Test
    fun `no task anywhere refuses instead of inventing one`() {
        assertNull(restartedChildTaskPrompt(fromNode = null, fromTranscript = null))
        assertNull(restartedChildTaskPrompt(fromNode = "", fromTranscript = ""))
        assertNull(restartedChildTaskPrompt(fromNode = "  \n", fromTranscript = "\t"))
    }

    @Test
    fun `the recovered task is trimmed, so a padded prompt is re-run cleanly`() {
        assertEquals(
            "inspect the runtime tree",
            restartedChildTaskPrompt(fromNode = null, fromTranscript = "\n  inspect the runtime tree \n"),
        )
    }
}
