package com.openminis.app.feature.runtime

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claim-lease coverage for the runtime inbox.
 *
 * Before this, `claimed` was a one-shot boolean with no reset path: a process
 * death between "claim taken" and "message handed to the model" left the
 * envelope marked for good, invisible to `claimNextStep`,
 * `claimNextNotification` and `claimNextTurn` alike, while its receipt still
 * read CLAIMED. The payload stayed on disk and was never delivered again, so
 * the loss was silent.
 *
 * A claim now carries a timestamp and expires. The clock is injected so
 * "the claimant died" can be simulated without sleeping, and the last test goes
 * through the real store file so the crash boundary is exercised end to end
 * instead of only in memory.
 */
class RuntimeMessageClaimLeaseTest {

    private val lease = RuntimeSessionTree.MESSAGE_CLAIM_LEASE_MILLIS

    private class Fixture(val tree: RuntimeSessionTree, val childId: String)

    /** Root -> child with one authorized NOTIFY already sitting in the inbox. */
    private fun fixture(clock: () -> Long): Fixture {
        val tree = RuntimeSessionTree(clock = clock)
        val root = tree.createRoot("root", RuntimeModelSnapshot("p", "m"))
        val child = tree.createChild(root.id, "child", RuntimeModelSnapshot("p", "m")).getOrThrow()
        assertTrue(tree.addSubscription(child.id, root.id, setOf(RuntimeEdgePermission.NOTIFY)).accepted)
        assertTrue(tree.send(root.id, child.id, "notice", RuntimeDelivery.NOTIFY).accepted)
        return Fixture(tree, child.id)
    }

    private fun inboxOf(tree: RuntimeSessionTree): List<JSONObject> {
        val array = JSONObject(tree.toJson()).getJSONArray("inbox")
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    @Test
    fun `claim is not redelivered while its lease is unexpired`() {
        var now = 1_000L
        val f = fixture { now }

        assertEquals("notice", f.tree.claimNextNotification(f.childId)?.payload)
        assertEquals(1, inboxOf(f.tree).size)

        // Same process, same tick: every claim entry point must skip it.
        assertNull(f.tree.claimNextNotification(f.childId))
        assertNull(f.tree.claimNextStep(f.childId))
        assertNull(f.tree.claimNextTurn(f.childId))

        // One millisecond short of one lease: still held.
        now += lease - 1
        assertNull(f.tree.claimNextNotification(f.childId))
        assertEquals(RuntimeReceiptStatus.CLAIMED, f.tree.receipts().last().status)
        assertEquals("message claimed", f.tree.receipts().last().reason)
        assertTrue(f.tree.reconcileMessageClaims(now).isEmpty())
    }

    @Test
    fun `expired claim is redelivered as the same envelope with a fresh stamp`() {
        var now = 1_000L
        val f = fixture { now }
        val first = f.tree.claimNextNotification(f.childId)
        assertNotNull(first)
        assertEquals(1_000L, first!!.claimedAtMillis)

        // Exactly one lease later the claim has lapsed: it belongs to a claimant
        // that died before consuming the message.
        now += lease
        val second = f.tree.claimNextNotification(f.childId)
        assertNotNull(second)
        assertEquals(first.id, second!!.id)
        assertEquals("notice", second.payload)
        assertEquals(now, second.claimedAtMillis)
        assertNotEquals(first.claimedAtMillis, second.claimedAtMillis)

        // The same envelope is requeued and re-claimed, never copied or removed.
        assertEquals(1, inboxOf(f.tree).size)
        assertEquals(now, inboxOf(f.tree).single().getLong("claimedAtMillis"))

        // Both the lapsed claim and the new one stay traceable.
        assertTrue(f.tree.events().any { it.kind == "message_claim_lease_expired" })
        assertEquals(2, f.tree.events().count { it.kind == "message_claimed" })
    }

    @Test
    fun `claim lease survives a json round trip and is judged from the persisted stamp`() {
        var now = 5_000L
        val f = fixture { now }
        assertNotNull(f.tree.claimNextNotification(f.childId))
        val snapshot = f.tree.toJson()

        // Restart one millisecond before the lease lapses, with no in-memory
        // state carried over: the message is still held.
        var restoredNow = 5_000L + lease - 1
        val held = RuntimeSessionTree(clock = { restoredNow })
        assertTrue(held.restoreJson(snapshot))
        assertEquals(RuntimeReceiptStatus.CLAIMED, held.receipts().last().status)
        assertNull(held.claimNextNotification(f.childId))

        // Restart after the lease lapsed, with no reconciliation pass having run:
        // the durable stamp alone is enough to make the message claimable again.
        restoredNow = 5_000L + lease
        val recovered = RuntimeSessionTree(clock = { restoredNow })
        assertTrue(recovered.restoreJson(snapshot))
        assertEquals("notice", recovered.claimNextNotification(f.childId)?.payload)
        assertEquals(1, inboxOf(recovered).size)
    }

    @Test
    fun `pre-lease json carrying only the claimed flag is treated as expired`() {
        var now = 7_000L
        val f = fixture { now }
        assertNotNull(f.tree.claimNextNotification(f.childId))

        // Reproduce what the pre-lease writer produced, literally, instead of
        // trusting today's encoder.
        val legacy = JSONObject(f.tree.toJson())
        val legacyInbox = legacy.getJSONArray("inbox")
        for (i in 0 until legacyInbox.length()) {
            val entry = legacyInbox.getJSONObject(i)
            assertTrue(entry.getBoolean("claimed"))
            entry.remove("claimedAtMillis")
            assertFalse(entry.has("claimedAtMillis"))
        }
        assertTrue(legacy.toString().contains("\"claimed\":true"))
        assertFalse(legacy.toString().contains("claimedAtMillis"))

        // The claim time of such a record is unknowable, so it must be read as
        // reclaimable: delivering it once too often beats losing it forever.
        val restored = RuntimeSessionTree(clock = { now })
        assertTrue(restored.restoreJson(legacy.toString()))
        assertEquals("notice", restored.claimNextNotification(f.childId)?.payload)
    }

    @Test
    fun `expired claim returns the receipt to enqueued and records the expiry`() {
        var now = 9_000L
        val f = fixture { now }
        assertNotNull(f.tree.claimNextNotification(f.childId))
        assertEquals(RuntimeReceiptStatus.CLAIMED, f.tree.receipts().last().status)

        now += lease + 1
        val requeued = f.tree.reconcileMessageClaims(now)
        assertEquals(1, requeued.size)
        assertEquals(RuntimeReceiptStatus.ENQUEUED, f.tree.receipts().last().status)
        assertEquals("claim lease expired; message requeued", f.tree.receipts().last().reason)
        assertEquals(1, f.tree.events().count { it.kind == "message_claim_lease_expired" })
        assertFalse(inboxOf(f.tree).single().getBoolean("claimed"))

        // Idempotent: an already requeued envelope is not requeued twice.
        assertTrue(f.tree.reconcileMessageClaims(now).isEmpty())
        assertEquals(1, f.tree.events().count { it.kind == "message_claim_lease_expired" })

        // And the requeued message is deliverable again.
        assertEquals("notice", f.tree.claimNextNotification(f.childId)?.payload)
        assertEquals(RuntimeReceiptStatus.CLAIMED, f.tree.receipts().last().status)

        // Nothing lapsed in the meantime: an unclaimed inbox stays untouched.
        val fresh = fixture { now }
        assertTrue(fresh.tree.reconcileMessageClaims(now).isEmpty())
        assertEquals(RuntimeReceiptStatus.ENQUEUED, fresh.tree.receipts().last().status)
    }

    @Test
    fun `store reconcile durably requeues an expired claim before the next process reads it`() {
        val dir = Files.createTempDirectory("runtime-claim-lease").toFile()
        val file = File(dir, "session-tree.json")
        val store = RuntimeTreeStore.openForTest(file) { source, target ->
            Files.move(
                source.toPath(),
                target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }
        assertTrue(
            store.update {
                val root = createRoot("root", RuntimeModelSnapshot("p", "m"))
                val child = createChild(root.id, "child", RuntimeModelSnapshot("p", "m")).getOrThrow()
                assertTrue(addSubscription(child.id, root.id, setOf(RuntimeEdgePermission.NOTIFY)).accepted)
                assertTrue(send(root.id, child.id, "notice", RuntimeDelivery.NOTIFY).accepted)
            },
        )

        // The store owns its own clock, so read the durable stamp back instead of
        // assuming a wall-clock value for the claim.
        var claimedId: String? = null
        assertTrue(store.update { claimedId = claimNextNotification("child")?.id })
        assertNotNull(claimedId)
        val onDisk = JSONObject(file.readText())
        val stamp = onDisk.getJSONArray("inbox").getJSONObject(0).getLong("claimedAtMillis")
        assertTrue(file.readText().contains("\"claimed\":true"))

        // Still inside the lease: the pass must not touch the claim.
        store.reconcile(stamp + lease - 1)
        assertTrue(file.readText().contains("\"claimed\":true"))

        // One millisecond past it the requeue has to be on disk before the next
        // process reads the file - that is the whole crash guarantee.
        store.reconcile(stamp + lease)
        val durable = file.readText()
        assertFalse(durable.contains("\"claimed\":true"))
        assertTrue(JSONObject(durable).getJSONArray("inbox").getJSONObject(0).getBoolean("claimed").not())

        val reopened = RuntimeTreeStore.openForTest(file) { source, target ->
            Files.move(
                source.toPath(),
                target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }
        var redelivered: String? = null
        assertTrue(reopened.update { redelivered = claimNextNotification("child")?.payload })
        assertEquals("notice", redelivered)
    }
}
