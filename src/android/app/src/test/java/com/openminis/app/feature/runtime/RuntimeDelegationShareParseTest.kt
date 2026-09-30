package com.openminis.app.feature.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * How a dispatcher states what to share.
 *
 * `request.md:138` puts the choice in the dispatcher's hands — 「派遣方，可以主动
 * 选择分享我们当前绘画的哪些上下文，或者说选择直接主动全选」 — so the composer
 * command has to carry it, and a command that names a selection the runtime
 * cannot honour must be rejected rather than quietly dispatched without it.
 */
class RuntimeDelegationShareParseTest {

    @Test
    fun `share all is parsed as the whole conversation`() {
        val request = RuntimeDelegationParser.parse("/subagent --share=all carry on from here")

        assertNotNull(request)
        assertEquals(RuntimeContextShareRequest.All, request!!.contextShare)
        assertEquals("carry on from here", request.prompt)
    }

    @Test
    fun `share all is case insensitive`() {
        assertEquals(
            RuntimeContextShareRequest.All,
            RuntimeDelegationParser.parse("/team --share=ALL do it")?.contextShare,
        )
        assertEquals(
            RuntimeContextShareRequest.All,
            RuntimeDelegationParser.parse("/subagent --share=All do it")?.contextShare,
        )
    }

    @Test
    fun `an index list is parsed, de-duplicated and ordered`() {
        val request = RuntimeDelegationParser.parse("/subagent --share=3,0,3,7 review these")

        assertEquals(
            RuntimeContextShareRequest.Selected(listOf(0, 3, 7)),
            request?.contextShare,
        )
        assertEquals("review these", request?.prompt)
    }

    @Test
    fun `no share option means nothing is shared`() {
        // The previous behaviour, preserved: delegation without an explicit
        // request must not start offering the child the parent's history.
        assertNull(RuntimeDelegationParser.parse("/subagent just do the task")?.contextShare)
        assertNull(RuntimeDelegationParser.parse("/subagent --model=x just do the task")?.contextShare)
    }

    @Test
    fun `a malformed share rejects the command instead of dropping the request`() {
        // Silently stripping `--share` would dispatch a child that cannot see
        // what the dispatcher promised it, and the dispatcher would get no
        // signal at all. Rejecting surfaces the usage line instead.
        assertNull(RuntimeDelegationParser.parse("/subagent --share=dunno do it"))
        assertNull(RuntimeDelegationParser.parse("/subagent --share=1,x do it"))
        assertNull(RuntimeDelegationParser.parse("/subagent --share=-1 do it"))
        assertNull(RuntimeDelegationParser.parse("/subagent --share= do it"))
        assertNull(RuntimeDelegationParser.parse("/subagent --share=1,,2 do it"))
    }

    @Test
    fun `an empty index list is not a selection`() {
        assertNull(RuntimeDelegationParser.parse("/subagent --share=, do it"))
    }

    @Test
    fun `a quoted share value survives the tokenizer`() {
        assertEquals(
            RuntimeContextShareRequest.Selected(listOf(1, 2)),
            RuntimeDelegationParser.parse("/subagent --share=\"1,2\" do it")?.contextShare,
        )
    }
}
