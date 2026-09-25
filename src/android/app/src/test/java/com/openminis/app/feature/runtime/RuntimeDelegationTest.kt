package com.openminis.app.feature.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeDelegationTest {
    @Test
    fun `command recognizer catches empty and full width delegation commands`() {
        assertTrue(RuntimeDelegationParser.isCommand("/team"))
        assertTrue(RuntimeDelegationParser.isCommand("／subagent\t"))
        assertTrue(RuntimeDelegationParser.isCommand("/delegate do work"))
        assertFalse(RuntimeDelegationParser.isCommand("/teammate do work"))
    }

    @Test
    fun `parser keeps traditional mode and defaults`() {
        val request = RuntimeDelegationParser.parse("/subagent inspect the runtime tree")
        assertNotNull(request)
        assertEquals(DelegationMode.TRADITIONAL, request?.mode)
        assertEquals("inspect the runtime tree", request?.prompt)
        assertEquals(RuntimeModelSnapshot.DEFAULT_CAPABILITIES, request?.capabilities)
    }

    @Test
    fun `parser reads team options and quoted note`() {
        val request = RuntimeDelegationParser.parse(
            "/team --provider=anthropic --model=claude-sonnet " +
                "--note=\"review this\" --capabilities=text_input,reasoning summarize tree",
        )
        assertNotNull(request)
        assertEquals(DelegationMode.TEAM, request?.mode)
        assertEquals("anthropic", request?.provider)
        assertEquals("claude-sonnet", request?.model)
        assertEquals("review this", request?.note)
        assertEquals(setOf("text_input", "reasoning"), request?.capabilities)
        assertEquals("summarize tree", request?.prompt)
    }

    @Test
    fun `team peer delivery is persisted and queue downgrades after child completion`() {
        val tree = RuntimeSessionTree(clock = { 10L })
        val root = tree.createRoot(
            "root",
            RuntimeModelSnapshot("p", "m"),
            delegationMode = DelegationMode.TEAM,
        )
        val childA = tree.createChild(root.id, "a", RuntimeModelSnapshot("p", "a")).getOrThrow()
        val childB = tree.createChild(root.id, "b", RuntimeModelSnapshot("p", "b")).getOrThrow()
        tree.start(root.id)
        tree.start(childA.id)
        tree.start(childB.id)

        val peer = tree.send(childA.id, childB.id, "hello", RuntimeDelivery.TEAM_PEER)
        assertTrue(peer.accepted)
        assertEquals(RuntimeDelivery.TEAM_PEER, peer.effectiveDelivery)
        assertEquals("hello", tree.claimNextStep(childB.id)?.payload)

        tree.complete(childB.id)
        val queued = tree.send(childA.id, childB.id, "next", RuntimeDelivery.STEER)
        assertTrue(queued.accepted)
        assertEquals(RuntimeDelivery.QUEUE, queued.effectiveDelivery)
        assertEquals("next", tree.claimNextTurn(childB.id)?.payload)
    }

    @Test
    fun `traditional mode rejects peer delivery`() {
        val tree = RuntimeSessionTree(clock = { 10L })
        val root = tree.createRoot("root", RuntimeModelSnapshot("p", "m"))
        val child = tree.createChild(root.id, "child", RuntimeModelSnapshot("p", "m")).getOrThrow()
        tree.start(root.id)
        tree.start(child.id)
        val receipt = tree.send(root.id, child.id, "peer", RuntimeDelivery.TEAM_PEER)
        assertFalse(receipt.accepted)
        assertNull(tree.claimNextStep(child.id))
    }
}
