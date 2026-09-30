package com.openminis.app.feature.runtime

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour tests for the tree-wide ledger ceilings ([RuntimeTreeConfig.maxEvents],
 * [RuntimeTreeConfig.maxEventChars], [RuntimeTreeConfig.maxDeliveryReceipts],
 * [RuntimeTreeConfig.maxDeliveryReceiptChars]).
 *
 * What each test can distinguish is stated in its name and KDoc: a test that
 * cannot fail for the reason it claims to guard is not evidence.
 */
class RuntimeLedgerBoundsTest {

    private val model = RuntimeModelSnapshot(provider = "test", model = "test-model")

    private fun treeWith(config: RuntimeTreeConfig = RuntimeTreeConfig()): RuntimeSessionTree =
        RuntimeSessionTree(config, clock = { 1_000L })

    /**
     * A tree file in the shape an *unbounded* version wrote: no budget keys in
     * `config` at all, plus however many events / receipts the test needs.
     *
     * This is the reachable legacy case the ceilings have to survive: the
     * ceilings are new, so every tree already on disk was written without them,
     * and such a file carries no `maxEvents` key for restore to read — the
     * store's own default is what applies.
     */
    private fun legacyJson(events: Int = 0, receipts: Int = 0): String {
        val json = JSONObject(treeWith().also { it.createRoot("root", model) }.toJson())
        assertFalse("a default-config tree must not write budget keys", json.getJSONObject("config").has("maxEvents"))
        if (events > 0) {
            json.put("events", JSONArray().apply {
                for (i in 0 until events) put(
                    JSONObject().apply {
                        put("id", "legacy-event-$i")
                        put("nodeId", "root")
                        put("kind", "transcript_appended")
                        put("timestampMillis", i.toLong())
                        put("payload", "m$i")
                    }
                )
            })
        }
        if (receipts > 0) {
            json.put("deliveryReceipts", JSONArray().apply {
                for (i in 0 until receipts) put(
                    JSONObject().apply {
                        put("id", "legacy-receipt-$i")
                        put("messageId", "legacy-message-$i")
                        put("fromNodeId", "root")
                        put("toNodeId", "root")
                        put("delivery", "QUEUE")
                        put("accepted", true)
                        put("createdAtMillis", i.toLong())
                        put("reason", JSONObject.NULL)
                        put("status", "ENQUEUED")
                    }
                )
            })
        }
        return json.toString()
    }

    /**
     * Distinguishes "the ledger is bounded" from "the ledger grows with the
     * number of state transitions". Before the ceiling existed this assertion
     * could not hold for any append count.
     */
    @Test
    fun eventLedgerIsBoundedAndCountsWhatItDropped() {
        val tree = treeWith(RuntimeTreeConfig().apply { maxEvents = 10 })
        tree.createRoot("root", model)
        repeat(50) { tree.appendTranscript("root", "user", "m$it", messageId = "msg-$it") }

        val retention = tree.eventRetention()
        assertEquals(10, retention.retainedEvents)
        assertTrue("a ledger that dropped events must say so", retention.truncated)
        assertEquals("every dropped event is counted exactly once", 41, retention.droppedEvents)
        assertEquals(
            "retained + dropped must be the whole history, so the count is auditable",
            51,
            retention.retainedEvents + retention.droppedEvents,
        )
    }

    /**
     * Distinguishes "oldest-first eviction" from "the write that triggered the
     * eviction is itself a casualty", which would silently swallow the evidence
     * of the very transition that caused it.
     */
    @Test
    fun newestEventIsNeverEvictedByItsOwnArrival() {
        val tree = treeWith(RuntimeTreeConfig().apply { maxEvents = 5 })
        tree.createRoot("root", model)
        repeat(30) { tree.appendTranscript("root", "user", "m$it", messageId = "msg-$it") }

        assertEquals("the newest event survives", "msg-29", tree.events().last().payload)
        assertEquals(5, tree.events().size)
    }

    /**
     * Distinguishes "a tree that never hit a ceiling is unchanged" from "every
     * saved tree silently gained fields", which would make existing JSON
     * round-trip tests red and change stored bytes for no reason.
     */
    @Test
    fun treeInsideItsBudgetsSerializesWithoutRetentionKeys() {
        val tree = treeWith()
        tree.createRoot("root", model)
        val child = tree.createChild("root", "child", model).getOrThrow()
        tree.start(child.id)
        tree.send("root", child.id, "hello", RuntimeDelivery.QUEUE)

        val json = JSONObject(tree.toJson())
        assertFalse("no ledger was trimmed, so no ledger block belongs in the JSON", json.has("ledgerRetention"))
        val config = json.getJSONObject("config")
        assertFalse("default budgets are not written", config.has("maxEvents"))
        assertFalse(config.has("maxEventChars"))
        assertFalse(config.has("maxDeliveryReceipts"))
        assertFalse(config.has("maxDeliveryReceiptChars"))

        assertFalse(tree.eventRetention().truncated)
        assertEquals(0, tree.eventRetention().droppedEvents)
        assertFalse(tree.receiptRetention().truncated)
        assertEquals(0, tree.receiptRetention().droppedReceipts)
    }

    /**
     * The hard constraint on applying budgets during restore: a tree that was
     * already inside its budgets must come back *exactly* as it was written.
     *
     * This is the in-repo guard for the property the out-of-repo measurement
     * established by diffing UUID-normalised JSON before and after the change
     * (both 71,952 bytes, empty diff). If reclaim-on-restore ever started
     * touching under-budget trees, this goes red.
     */
    @Test
    fun treeInsideItsBudgetsRoundTripsByteForByte() {
        val source = treeWith()
        source.createRoot("root", model)
        val child = source.createChild("root", "child", model).getOrThrow()
        source.start(child.id)
        source.send("root", child.id, "hello", RuntimeDelivery.QUEUE)
        source.appendTranscript(child.id, "user", "q", messageId = "m1")
        val json = source.toJson()

        val restored = treeWith()
        assertTrue(restored.restoreJson(json))
        assertEquals("an under-budget tree must round-trip byte-for-byte", json, restored.toJson())
    }

    /**
     * Distinguishes "the eviction is durable and reported" from "a restart
     * launders a trimmed ledger into one that looks whole" — the failure the
     * persisted counters exist to prevent.
     */
    @Test
    fun trimmedLedgerSurvivesARestartStillSayingSo() {
        val source = treeWith(RuntimeTreeConfig().apply { maxEvents = 20 })
        source.createRoot("root", model)
        repeat(100) { source.appendTranscript("root", "user", "m$it", messageId = "msg-$it") }
        val before = source.eventRetention()
        // 100 appends plus the root's own `root_created` transition = 101 events,
        // of which the ledger keeps the newest 20.
        assertEquals(101, before.retainedEvents + before.droppedEvents)
        assertEquals(81, before.droppedEvents)

        val restored = treeWith(RuntimeTreeConfig().apply { maxEvents = 20 })
        assertTrue(restored.restoreJson(source.toJson()))

        val after = restored.eventRetention()
        assertEquals("the trimmed ledger is exactly as long after a restart", 20, after.retainedEvents)
        assertTrue("a restart must not turn a trimmed ledger into a complete one", after.truncated)
        assertEquals(before.droppedEvents, after.droppedEvents)
        assertEquals(before.droppedChars, after.droppedChars)
        assertTrue("the counts also survive in the serialized form", JSONObject(restored.toJson()).has("ledgerRetention"))
    }

    /**
     * Distinguishes "a tree loaded from disk is bounded immediately" from "it
     * stays over budget until some later append happens to evict it".
     *
     * The second is the reachable defect this guards: every tree already on disk
     * was written before these ceilings existed, and `RuntimeTreeStore.update`
     * serializes the whole tree twice per state mutation — so an over-budget
     * tree loaded and left alone is still paid for on every change.
     */
    @Test
    fun restoringALegacyOverBudgetTreeTrimsItOnTheSpot() {
        val opened = treeWith()
        assertTrue(opened.restoreJson(legacyJson(events = 10_001)))

        val retention = opened.eventRetention()
        assertEquals(
            "the budget holds the moment the tree is loaded",
            RuntimeTreeConfig.DEFAULT_EVENTS_LIMIT,
            retention.retainedEvents,
        )
        assertTrue("and it says so rather than looking complete", retention.truncated)
        assertEquals(
            "the dropped count matches the excess exactly",
            10_001 - RuntimeTreeConfig.DEFAULT_EVENTS_LIMIT,
            retention.droppedEvents,
        )
        assertEquals(RuntimeTreeConfig.DEFAULT_EVENTS_LIMIT, opened.events().size)
    }

    /**
     * The receipt half of the same guarantee: a legacy file's receipt ledger is
     * trimmed on load too, so the two ledgers cannot diverge in when the ceiling
     * starts applying.
     */
    @Test
    fun restoringALegacyOverBudgetReceiptLedgerTrimsItOnTheSpot() {
        val opened = treeWith()
        assertTrue(opened.restoreJson(legacyJson(receipts = 9_000)))

        val retention = opened.receiptRetention()
        assertEquals(RuntimeTreeConfig.DEFAULT_DELIVERY_RECEIPTS_LIMIT, retention.retainedReceipts)
        assertTrue(retention.truncated)
        assertEquals(
            9_000 - RuntimeTreeConfig.DEFAULT_DELIVERY_RECEIPTS_LIMIT,
            retention.droppedReceipts,
        )
    }

    /**
     * Distinguishes "a budget lowered on a live tree is applied to memory
     * already spent" from "a ceiling that only ever affects future writes" —
     * the difference between reclaiming and merely capping.
     *
     * Note the deliberate `0` before the lowering: with the ceiling still in
     * force there is genuinely nothing to reclaim, and reporting that as `0`
     * (rather than as a failure) is the contract.
     */
    @Test
    fun reclaimAppliesALoweredBudgetToALiveTree() {
        val tree = treeWith()
        tree.createRoot("root", model)
        repeat(9_000) { tree.appendTranscript("root", "user", "m$it") }
        assertEquals(RuntimeTreeConfig.DEFAULT_EVENTS_LIMIT, tree.eventRetention().retainedEvents)
        assertEquals("nothing to reclaim while the ceiling still holds", 0, tree.reclaimLedgers())

        tree.config.maxEvents = 100
        assertEquals("the lowered ceiling is applied to what is already held", 7_900, tree.reclaimLedgers())
        assertEquals(100, tree.eventRetention().retainedEvents)
        assertEquals("and a second call has nothing left to do", 0, tree.reclaimLedgers())
    }

    /**
     * Distinguishes "nothing to do" from "it trimmed something", so a caller can
     * tell a no-op from an error instead of reading both as success.
     */
    @Test
    fun reclaimReportsZeroWhenThereIsNothingToDo() {
        val tree = treeWith()
        tree.createRoot("root", model)
        repeat(5) { tree.appendTranscript("root", "user", "m$it") }
        assertEquals("nothing over budget means nothing freed", 0, tree.reclaimLedgers())
        assertEquals("and a second call is still a no-op", 0, tree.reclaimLedgers())
    }

    /**
     * Distinguishes the three states that must never be collapsed: "not a node
     * of this tree", "a node that genuinely produced no events", and "a node
     * with events". Collapsing the first two makes a caller's `?:` dead code.
     */
    @Test
    fun eventsOfSeparatesUnknownNodeFromNodeWithNoEvents() {
        val tree = treeWith()
        tree.createRoot("root", model)
        val child = tree.createChild("root", "child", model).getOrThrow()

        assertNull("an unknown node is a failed query, not an empty history", tree.eventsOf("does-not-exist"))
        assertEquals(0, tree.eventsOf(child.id)!!.size)
        assertTrue("the parent produced events", tree.eventsOf("root")!!.isNotEmpty())
    }

    /**
     * Distinguishes "the character budget is enforced" from "only the count
     * budget is", which would leave the growing thing (payload text) unbounded
     * whenever events are few but large.
     */
    @Test
    fun characterBudgetEvictsIndependentlyOfTheCountBudget() {
        val tree = treeWith(RuntimeTreeConfig().apply { maxEvents = 1_000_000; maxEventChars = 500 })
        tree.createRoot("root", model)
        repeat(60) { tree.appendTranscript("root", "user", "x".repeat(200), messageId = "msg-$it") }

        val retention = tree.eventRetention()
        assertTrue("the count budget was never the binding one here", retention.retainedEvents < 60)
        assertTrue(retention.truncated)
        assertTrue("retained chars must respect the ceiling", retention.retainedChars <= 500 || retention.retainedEvents == 1)
        assertEquals("the newest event still survives", "msg-59", tree.events().last().payload)
    }

    /**
     * Distinguishes "the receipt ledger is bounded" from "send grows it without
     * limit", and pins that rejected deliveries are counted too — a rejection is
     * an audit fact, and dropping it silently would be the same defect.
     */
    @Test
    fun receiptLedgerIsBoundedAndCountsRejections() {
        val tree = treeWith(RuntimeTreeConfig().apply { maxDeliveryReceipts = 10 })
        tree.createRoot("root", model)
        val child = tree.createChild("root", "child", model).getOrThrow()
        repeat(40) { tree.send("root", child.id, "", RuntimeDelivery.QUEUE) }

        val retention = tree.receiptRetention()
        assertEquals(10, retention.retainedReceipts)
        assertTrue(retention.truncated)
        assertTrue("rejections are counted, not silently discarded", retention.droppedReceipts > 0)
        assertEquals(10, tree.receipts().size)
    }

    /**
     * Guards the receipt character total against the defect an independent audit
     * found: the claim and lease-expiry paths rewrite a receipt's `reason` in
     * place, and `reason` is a counted field. When those two sites bypassed the
     * accounting, the cached total drifted below the truth on every claim cycle
     * — and a budget that reads a stale total is a budget that stops firing, so
     * the character ceiling could be exceeded for real while the cache still
     * looked under it.
     *
     * What it distinguishes: "every path that can change a receipt also updates
     * the total" from "one path forgot".
     */
    @Test
    fun claimAndLeaseExpiryKeepTheReceiptCharacterTotalHonest() {
        val tree = treeWith(RuntimeTreeConfig().apply { maxDeliveryReceiptChars = 400_000 })
        tree.createRoot("root", model)
        val child = tree.createChild("root", "child", model).getOrThrow()
        repeat(20) { i ->
            tree.send("root", child.id, "step $i", RuntimeDelivery.QUEUE)
            tree.claimNextTurn(child.id)
        }
        // Force a lease expiry too: `requeueExpiredClaim` is the second site that
        // rewrites a receipt in place.
        tree.reconcileMessageClaims(nowMillis = RuntimeSessionTree.MESSAGE_CLAIM_LEASE_MILLIS + 2_000L)

        val cached = tree.receiptRetention().retainedChars
        val truth = tree.receipts().sumOf { it.fromNodeId.length + it.toNodeId.length + (it.reason?.length ?: 0) }
        assertEquals("the cached character total must equal the ledger it describes", truth, cached)
        assertTrue("claim rewrites must actually grow the counted text, or this proves nothing", cached > 0)
    }

    /**
     * The same guarantee at the point where it matters: the character ceiling
     * has to fire on a ledger whose totals were built up through claim
     * rewrites, not only on one built by appends.
     */
    @Test
    fun characterBudgetFiresOnALedgerBuiltByClaimRewrites() {
        val source = treeWith(RuntimeTreeConfig().apply { maxDeliveryReceipts = 1_000; maxDeliveryReceiptChars = 1_000_000 })
        source.createRoot("root", model)
        val child = source.createChild("root", "child", model).getOrThrow()
        repeat(30) { i ->
            source.send("root", child.id, "step $i", RuntimeDelivery.QUEUE)
            source.claimNextTurn(child.id)
            source.reconcileMessageClaims(nowMillis = RuntimeSessionTree.MESSAGE_CLAIM_LEASE_MILLIS + 2_000L)
        }

        val tight = treeWith(RuntimeTreeConfig().apply { maxDeliveryReceipts = 1_000; maxDeliveryReceiptChars = 1_000_000 })
        assertTrue(tight.restoreJson(source.toJson()))
        // `restoreJson` takes the budgets from the file, so a budget handed to
        // the fresh tree is overwritten whenever the file carries that key. The
        // ceiling is therefore lowered *after* restoring — the same order a
        // caller would use on a live tree.
        tight.config.maxDeliveryReceiptChars = 100
        val freed = tight.reclaimLedgers()
        assertTrue("a ledger over its character ceiling must be reclaimed", freed > 0)
        assertTrue(
            "and the retained total must actually be inside the ceiling afterwards",
            tight.receiptRetention().retainedChars <= 100 || tight.receiptRetention().retainedReceipts == 1,
        )
    }

    /**
     * Distinguishes "a node's export answers that node's question" from "it
     * repeats the tree-wide total", which marks nodes that lost nothing and
     * tells a reader nothing about the node in hand. The tree-wide ledger can
     * evict one node's events while leaving a sibling's untouched.
     */
    @Test
    fun perNodeExportReportsThisNodesOwnDroppedEvents() {
        val tree = treeWith(RuntimeTreeConfig().apply { maxEvents = 5 })
        tree.createRoot("A", model)
        // A is filled FIRST so that B's own `root_created` and both its appends
        // land inside the retained window: the point of the case is a node that
        // lost nothing sitting next to one that lost almost everything.
        repeat(20) { tree.appendTranscript("A", "user", "a$it") }
        tree.createRoot("B", model)
        repeat(2) { tree.appendTranscript("B", "user", "b$it") }

        val perNode = zipEntries(tree.exportZip("json").bytes)
        val a = JSONObject(perNode.getValue("A/node.json")).getJSONObject("ledgerRetention")
        val b = JSONObject(perNode.getValue("B/node.json")).getJSONObject("ledgerRetention")
        val treeWide = JSONObject(tree.toJson()).getJSONObject("ledgerRetention").getJSONObject("events")

        assertEquals("every one of B's events is inside the window, so B lost none", 0, b.getInt("droppedEvents"))
        assertEquals("and B's retained count is B's own, not the tree's", 3, b.getInt("retainedEvents"))
        assertTrue("A's oldest events were the ones evicted", a.getInt("droppedEvents") > 0)
        assertEquals(
            "the per-node counts must add up to the tree-wide total",
            treeWide.getInt("droppedEvents"),
            a.getInt("droppedEvents") + b.getInt("droppedEvents"),
        )
    }

    /**
     * The text export's per-node marker must be about *that node*.
     *
     * Found by falsification, not by design: reverting `nodeText` to the
     * tree-wide test (`eventEvictions.dropped > 0`) left every test green, which
     * meant the marker had no coverage at all. What this distinguishes: "a node
     * that lost nothing is reported as complete" from "it inherits its
     * sibling's loss", which is the misleading direction that matters — a reader
     * who is told a complete node was trimmed stops trusting the marker, and a
     * reader who is told nothing cannot tell that a node was emptied.
     */
    @Test
    fun perNodeTextExportMarkerIsAboutThatNode() {
        val tree = treeWith(RuntimeTreeConfig().apply { maxEvents = 5 })
        tree.createRoot("A", model)
        repeat(20) { tree.appendTranscript("A", "user", "a$it") }
        tree.createRoot("B", model)
        repeat(2) { tree.appendTranscript("B", "user", "b$it") }

        val nodeTexts = zipEntries(tree.exportZip("text").bytes).filterKeys { it.endsWith("node.txt") }
        val aText = nodeTexts.getValue("A/node.txt")
        val bText = nodeTexts.getValue("B/node.txt")

        assertTrue("A lost events, so A says so", aText.lines().any { it.startsWith("ledger-truncated") })
        assertFalse(
            "B lost nothing, so B must not inherit A's truncation marker",
            bText.lines().any { it.startsWith("ledger-truncated") },
        )
    }

    /**
     * Distinguishes "the per-node tally is durable" from "a restart makes every
     * node look complete" — the laundering path the tree-wide counters were
     * persisted to close, one level down. Also pins that the field is present
     * with an explicit zero, so "no events dropped" and "field missing" can
     * never be confused.
     */
    @Test
    fun perNodeEventDropTallySurvivesARestart() {
        val source = treeWith(RuntimeTreeConfig().apply { maxEvents = 5 })
        source.createRoot("A", model)
        source.createRoot("B", model)
        repeat(20) { source.appendTranscript("A", "user", "a$it") }
        repeat(2) { source.appendTranscript("B", "user", "b$it") }
        val before = JSONObject(zipEntries(source.exportZip("json").bytes).getValue("A/node.json"))
            .getJSONObject("ledgerRetention").getInt("droppedEvents")

        val restored = treeWith(RuntimeTreeConfig().apply { maxEvents = 5 })
        assertTrue(restored.restoreJson(source.toJson()))
        val after = JSONObject(zipEntries(restored.exportZip("json").bytes).getValue("A/node.json"))
            .getJSONObject("ledgerRetention").getInt("droppedEvents")
        assertEquals("a restart must not turn a trimmed node into a complete-looking one", before, after)
    }

    /**
     * Distinguishes "the ledger keeps at most one event when a single event
     * exceeds the character ceiling" from "an oversized payload can accumulate".
     * The newest event is never a casualty of its own arrival, so an oversized
     * one is retained — the property that keeps that bounded is that the very
     * next append evicts everything older.
     */
    @Test
    fun oversizedEventCannotGrowTheLedgerPastItself() {
        val tree = treeWith(RuntimeTreeConfig().apply { maxEvents = 1_000; maxEventChars = 1_000 })
        tree.createRoot("root", model)
        // An event's payload is the appended message's *id*, not its content, so
        // the oversized payload has to be injected through `messageId`. (A first
        // version of this test put the bulk in `content` and therefore measured
        // nothing at all: the payloads stayed six bytes long.)
        repeat(10) { tree.appendTranscript("root", "user", "tiny", messageId = "x".repeat(600_000) + "-$it") }

        assertEquals("the ledger holds exactly the newest oversized event", 1, tree.eventRetention().retainedEvents)
        assertTrue("and its size is the oversized payload", tree.eventRetention().retainedChars > 600_000)
        assertTrue("nothing older survives it", tree.events().last().payload.endsWith("-9"))
    }

    private fun zipEntries(bytes: ByteArray): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val zip = java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes))
        while (true) {
            val entry = zip.nextEntry ?: break
            out[entry.name] = String(zip.readBytes(), Charsets.UTF_8)
        }
        return out
    }

    /**
     * Distinguishes "an old JSON still restores" from "an out-of-range budget in
     * a stored file refuses to load", which would strand a user's existing tree
     * on an upgrade. `coerceIn`, never `require`.
     */
    @Test
    fun outOfRangeBudgetsInStoredJsonAreCoercedNotRefused() {
        val tree = treeWith()
        val legacy = """{"config":{"maxEvents":0,"maxEventChars":-5,"maxDeliveryReceipts":3000000,"maxDeliveryReceiptChars":1}}"""
        assertTrue("an old or corrupt config must not make the tree unloadable", tree.restoreJson(legacy))

        assertEquals(1, tree.config.maxEvents)
        assertEquals(1, tree.config.maxEventChars)
        assertEquals(RuntimeTreeConfig.MAX_DELIVERY_RECEIPTS_LIMIT, tree.config.maxDeliveryReceipts)
        assertEquals(1, tree.config.maxDeliveryReceiptChars)
    }

    /**
     * Distinguishes "custom budgets round-trip" from "a raised ceiling is
     * silently reset to the default on the next restart", which would make the
     * configurable part of this feature a lie.
     */
    @Test
    fun customBudgetsSurviveASaveAndRestore() {
        val source = treeWith(RuntimeTreeConfig().apply { maxEvents = 4_321; maxDeliveryReceipts = 1_234 })
        source.createRoot("root", model)
        repeat(10) { source.appendTranscript("root", "user", "m$it") }

        val restored = treeWith()
        assertTrue(restored.restoreJson(source.toJson()))
        assertEquals(4_321, restored.config.maxEvents)
        assertEquals(1_234, restored.config.maxDeliveryReceipts)
    }
}
