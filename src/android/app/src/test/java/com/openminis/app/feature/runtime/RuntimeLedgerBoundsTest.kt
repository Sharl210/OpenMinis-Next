package com.openminis.app.feature.runtime

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
    private fun legacyJson(events: Int = 0, receipts: Int = 0, stopReceipts: Int = 0): String {
        val json = JSONObject(treeWith().also { it.createRoot("root", model) }.toJson())
        assertFalse("a default-config tree must not write budget keys", json.getJSONObject("config").has("maxEvents"))
        assertFalse(
            "the control ledger's budget is not written on a default-config tree either",
            json.getJSONObject("config").has("maxControlReceipts"),
        )
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
        if (stopReceipts > 0) {
            json.put("stopReceipts", JSONArray().apply {
                for (i in 0 until stopReceipts) put(
                    JSONObject().apply {
                        put("operationId", "legacy-stop-$i")
                        put("idempotencyKey", "legacy-stop-key-$i")
                        put("type", "STOP_DESCENDANT")
                        put("actorNodeId", "root")
                        put("targetNodeId", "root")
                        put("accepted", true)
                        put("stateBefore", "RUNNING")
                        put("stateAfter", "STOP_REQUESTED")
                        put("affectedNodeIds", JSONArray(listOf("root")))
                        put("createdAtMillis", i.toLong())
                        put("reason", JSONObject.NULL)
                        put("eventId", JSONObject.NULL)
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
        assertFalse(config.has("maxControlReceipts"))
        assertFalse(config.has("maxControlReceiptChars"))

        assertFalse(tree.eventRetention().truncated)
        assertEquals(0, tree.eventRetention().droppedEvents)
        assertFalse(tree.receiptRetention().truncated)
        assertEquals(0, tree.receiptRetention().droppedReceipts)
        assertFalse(tree.controlReceiptRetention().truncated)
        assertEquals(0, tree.controlReceiptRetention().droppedReceipts)
        assertEquals(0, tree.controlReceiptRetention().droppedIdempotencyKeys)
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
        val legacy = """{"config":{"maxEvents":0,"maxEventChars":-5,"maxDeliveryReceipts":3000000,"maxDeliveryReceiptChars":1,"maxControlReceipts":-1,"maxControlReceiptChars":999999999}}"""
        assertTrue("an old or corrupt config must not make the tree unloadable", tree.restoreJson(legacy))

        assertEquals(1, tree.config.maxEvents)
        assertEquals(1, tree.config.maxEventChars)
        assertEquals(RuntimeTreeConfig.MAX_DELIVERY_RECEIPTS_LIMIT, tree.config.maxDeliveryReceipts)
        assertEquals(1, tree.config.maxDeliveryReceiptChars)
        assertEquals(1, tree.config.maxControlReceipts)
        assertEquals(RuntimeTreeConfig.MAX_CONTROL_RECEIPT_CHARS_LIMIT, tree.config.maxControlReceiptChars)
    }

    /**
     * Distinguishes "custom budgets round-trip" from "a raised ceiling is
     * silently reset to the default on the next restart", which would make the
     * configurable part of this feature a lie.
     */
    @Test
    fun customBudgetsSurviveASaveAndRestore() {
        val source = treeWith(RuntimeTreeConfig().apply { maxEvents = 4_321; maxDeliveryReceipts = 1_234; maxControlReceipts = 321 })
        source.createRoot("root", model)
        repeat(10) { source.appendTranscript("root", "user", "m$it") }

        val restored = treeWith()
        assertTrue(restored.restoreJson(source.toJson()))
        assertEquals(4_321, restored.config.maxEvents)
        assertEquals(1_234, restored.config.maxDeliveryReceipts)
        assertEquals(321, restored.config.maxControlReceipts)
    }

    /**
     * Issues [count] distinct stop operations against one child and returns what
     * the tree answered to each.
     *
     * The first is accepted; the rest are refused with "target is not active"
     * because the child is already stopping — and are filed anyway, which is the
     * point of using this shape: a refusal is an audit fact and counts against the
     * ledger exactly like an acceptance, so a budget that only bounded the
     * accepted ones would not be a budget.
     */
    private fun stopRepeatedly(
        tree: RuntimeSessionTree,
        rootId: String,
        childId: String,
        count: Int,
        reason: String = "stop $count",
    ): List<RuntimeStopReceipt> = (0 until count).map { i ->
        tree.stopDescendant(
            RuntimeStopRequest(
                actorNodeId = rootId,
                targetNodeId = childId,
                rootId = rootId,
                reason = reason,
                operationId = "op-$i",
                idempotencyKey = "key-$i",
            ),
        )
    }

    /**
     * Distinguishes "the control (stop) ledger is bounded" from "every stop files
     * a receipt that nothing ever removes" — the state this ledger was in, where a
     * session that is stopped repeatedly grows by ~1 982 bytes of tree JSON per
     * stop, linearly and with no knee.
     *
     * Refusals are counted too (see [stopRepeatedly]), and the arithmetic is
     * pinned: retained + dropped has to be the whole history, or the number cannot
     * be audited against the operations that were actually issued.
     */
    @Test
    fun controlReceiptLedgerIsBoundedAndCountsWhatItDropped() {
        val tree = treeWith(RuntimeTreeConfig().apply { maxControlReceipts = 5 })
        val root = tree.createRoot("root", model)
        val child = tree.createChild("root", "child", model).getOrThrow()
        tree.start(child.id)

        stopRepeatedly(tree, root.id, child.id, count = 20)

        val retention = tree.controlReceiptRetention()
        assertEquals(5, retention.retainedReceipts)
        assertTrue("a control ledger that dropped receipts must say so", retention.truncated)
        assertEquals("every dropped receipt is counted exactly once", 15, retention.droppedReceipts)
        assertEquals(
            "retained + dropped must be the whole history, so the count is auditable",
            20,
            retention.retainedReceipts + retention.droppedReceipts,
        )
        assertEquals("the newest operation is never a casualty of its own arrival", "op-19", tree.stopReceipt("op-19")?.operationId)
    }

    /**
     * **The test this change exists for.**
     *
     * The control ledger doubles as the tree's stop idempotency table, and the
     * index that makes the lookup work (`controlOperationIdByIdempotencyKey`) is a
     * *derived view* of it — not a second ledger that can be capped on its own.
     * Bound the receipts without taking the index with them and two things break
     * at once: the index keeps every key it has ever recorded, so the memory
     * problem is only half solved; and it keeps naming operations the ledger no
     * longer holds, so "this key is known" stops being a true statement and the
     * lookup's inner `?.let` falls through as a matter of routine instead of as an
     * impossible case.
     *
     * What it distinguishes: "the index is a view of the ledger and is reclaimed
     * with it" from "the index outlives the ledger it indexes". The second is the
     * defect a receipts-only ceiling would introduce, and it turns **both**
     * assertions below red while leaving the rest of this file green — which is
     * the whole reason they are written as a pair.
     */
    @Test
    fun controlLedgerEvictionTakesTheIdempotencyIndexWithIt() {
        val tree = treeWith(RuntimeTreeConfig().apply { maxControlReceipts = 5 })
        val root = tree.createRoot("root", model)
        val child = tree.createChild("root", "child", model).getOrThrow()
        tree.start(child.id)

        stopRepeatedly(tree, root.id, child.id, count = 20)

        val retention = tree.controlReceiptRetention()
        assertEquals(
            "five receipts were kept, so the fifteen keys that named the rest must have gone too",
            15,
            retention.droppedIdempotencyKeys,
        )
        assertTrue(
            "and that loss has to be visible rather than silent",
            retention.droppedIdempotencyKeys > 0,
        )
        assertEquals(
            "no idempotency key may name an operation this ledger no longer holds",
            0,
            tree.danglingIdempotencyKeyCount(),
        )
        // The third surface the loss has to be visible on. `receiptRetention()`
        // and the JSON block are read by code; this one is read by a human looking
        // at an export, and it would otherwise report the other two ledgers as
        // untouched while this one had taken fifteen operations.
        val text = String(tree.export("text").bytes, Charsets.UTF_8)
        assertTrue(
            "the text export must report this ledger's loss too, keys included",
            text.contains("controlEvicted=15 receipts / 15 idempotency keys"),
        )
    }

    /**
     * The counterweight to the eviction: capping the ledger must not break the
     * deduplication that made keeping it worthwhile.
     *
     * This is the in-repo guard for the behaviour `RuntimeDelegationTest` already
     * pins — "same idempotency key, new operation id, one receipt" — re-run on a
     * tree whose ceiling is small enough that eviction has to be in play. If
     * eviction ever started taking receipts a retry still needs, this goes red.
     */
    @Test
    fun idempotentRetryInsideTheWindowIsStillAnsweredByTheOriginalReceipt() {
        val tree = treeWith(RuntimeTreeConfig().apply { maxControlReceipts = 3 })
        val root = tree.createRoot("root", model)
        val child = tree.createChild("root", "child", model).getOrThrow()
        tree.start(child.id)

        val operations = stopRepeatedly(tree, root.id, child.id, count = 3)
        assertEquals(
            "the window is exactly full, so nothing has been evicted yet",
            0,
            tree.controlReceiptRetention().droppedReceipts,
        )

        val replay = tree.stopDescendant(
            RuntimeStopRequest(
                actorNodeId = root.id,
                targetNodeId = child.id,
                rootId = root.id,
                operationId = "op-0-retry",
                idempotencyKey = "key-0",
            ),
        )
        assertEquals("a retry inside the window is still recognised as a retry", "op-0", replay.operationId)
        assertEquals("and answered with the receipt that already exists", operations[0], replay)
        assertEquals(
            "answering a retry must not file a second record",
            3,
            tree.controlReceiptRetention().retainedReceipts,
        )
    }

    /**
     * What a *bounded* idempotency window means, stated so it cannot be quietly
     * assumed away: a retry that arrives after its receipt has been evicted is a
     * new request, and is carried out again.
     *
     * That is the honest cost of having a ceiling at all, not a defect this change
     * introduces — an unbounded window is the thing being removed, and no amount
     * of keeping the *key* around could have avoided it, because the lookup needs
     * the receipt itself to answer. What the change does owe the caller is that
     * the ledger stops claiming otherwise: the index must not keep a key it can no
     * longer resolve, and the loss must be counted where a reader can see it.
     */
    @Test
    fun retryOutsideTheEvictedWindowIsExecutedAgainAndTheIndexSaysNothing() {
        val tree = treeWith(RuntimeTreeConfig().apply { maxControlReceipts = 2 })
        val root = tree.createRoot("root", model)
        val child = tree.createChild("root", "child", model).getOrThrow()
        tree.start(child.id)

        stopRepeatedly(tree, root.id, child.id, count = 5)
        assertTrue(
            "the oldest operations are the ones that left",
            tree.controlReceiptRetention().droppedReceipts > 0,
        )

        val replay = tree.stopDescendant(
            RuntimeStopRequest(
                actorNodeId = root.id,
                targetNodeId = child.id,
                rootId = root.id,
                operationId = "op-0-retry",
                idempotencyKey = "key-0",
            ),
        )
        assertNotEquals("the evicted key is genuinely unknown again", "op-0", replay.operationId)
        assertEquals("so this really was carried out as a fresh request", "op-0-retry", replay.operationId)
        assertEquals(
            "and nothing in the index pretends key-0 is still recognised",
            0,
            tree.danglingIdempotencyKeyCount(),
        )
    }

    /**
     * The control half of the restore guarantee: a legacy file's stop ledger is
     * trimmed on load, so all three ledgers start applying their ceilings at the
     * same moment rather than two of them now and one at its next append.
     *
     * This is the reachable case — the ceiling is new, so every tree already on
     * disk was written without it — and the index has to come out of it consistent
     * as well, since the file brought one in.
     */
    @Test
    fun restoringALegacyOverBudgetControlLedgerTrimsItOnTheSpot() {
        val opened = treeWith()
        assertTrue(opened.restoreJson(legacyJson(stopReceipts = 2_500)))

        val retention = opened.controlReceiptRetention()
        assertEquals(
            "the budget holds the moment the tree is loaded",
            RuntimeTreeConfig.DEFAULT_CONTROL_RECEIPTS_LIMIT,
            retention.retainedReceipts,
        )
        assertTrue("and it says so rather than looking complete", retention.truncated)
        assertEquals(
            "the dropped count matches the excess exactly",
            2_500 - RuntimeTreeConfig.DEFAULT_CONTROL_RECEIPTS_LIMIT,
            retention.droppedReceipts,
        )
        assertEquals(
            "and no key from the file survived the receipt it pointed at",
            0,
            opened.danglingIdempotencyKeyCount(),
        )
    }

    /**
     * Distinguishes "the control ledger's eviction is durable and reported" from
     * "a restart launders a trimmed ledger into one that looks whole" — the
     * failure the persisted counters exist to prevent, one ledger over.
     *
     * The index count is part of that record: a restart that forgot how many keys
     * left with the receipts would leave the next reader unable to tell a tree
     * whose index was trimmed from one whose index was never touched.
     */
    @Test
    fun trimmedControlLedgerSurvivesARestartStillSayingSo() {
        val source = treeWith(RuntimeTreeConfig().apply { maxControlReceipts = 4 })
        val root = source.createRoot("root", model)
        val child = source.createChild("root", "child", model).getOrThrow()
        source.start(child.id)
        stopRepeatedly(source, root.id, child.id, count = 12)
        val before = source.controlReceiptRetention()
        assertEquals(8, before.droppedReceipts)
        assertEquals(8, before.droppedIdempotencyKeys)

        val restored = treeWith(RuntimeTreeConfig().apply { maxControlReceipts = 4 })
        assertTrue(restored.restoreJson(source.toJson()))

        val after = restored.controlReceiptRetention()
        assertEquals("the trimmed ledger is exactly as long after a restart", 4, after.retainedReceipts)
        assertTrue("a restart must not turn a trimmed ledger into a complete one", after.truncated)
        assertEquals(before.droppedReceipts, after.droppedReceipts)
        assertEquals(before.droppedChars, after.droppedChars)
        assertEquals(
            "the index loss is remembered too, or the restart launders it",
            before.droppedIdempotencyKeys,
            after.droppedIdempotencyKeys,
        )
        assertEquals(
            "and what came back is a consistent pair of tables",
            0,
            restored.danglingIdempotencyKeyCount(),
        )

        val block = JSONObject(restored.toJson())
            .getJSONObject("ledgerRetention")
            .getJSONObject("stopReceipts")
        assertEquals(8, block.getInt("droppedReceipts"))
        assertEquals(8, block.getInt("droppedIdempotencyKeys"))
    }

    /**
     * The one thing bounding this ledger *does* cost, pinned so it can never be
     * discovered as a surprise: an operation has to be aborted after its receipt
     * has left the window, [RuntimeSessionTree.abort] updates the receipt's
     * `stateAfter` in place — and there is no longer a receipt to update.
     * `RuntimeDelegationTest` asserts that update inside the window; this asserts
     * what remains outside it.
     *
     * Not a silent failure, and deliberately not "fixed" by pinning the receipt:
     * the terminal state is recorded in the `events` ledger, which is
     * unconditional and independent of this budget, so the audit chain is unbroken
     * and `stopReceipt(...)` returning `null` is the same honest "not in the
     * window" answer it gives for an operation that never existed. Pinning would
     * mean an unbounded set again, one `pendingStopOperationByNodeId` entry at a
     * time — which is the defect this change removes, merely better disguised.
     */
    @Test
    fun abortingAfterTheReceiptLeftTheWindowIsRecordedButNotAttachedToIt() {
        val tree = treeWith(RuntimeTreeConfig().apply { maxControlReceipts = 2 })
        val root = tree.createRoot("root", model)
        val child = tree.createChild("root", "child", model).getOrThrow()
        tree.start(child.id)

        val first = tree.stopDescendant(
            RuntimeStopRequest(
                actorNodeId = root.id,
                targetNodeId = child.id,
                rootId = root.id,
                operationId = "op-first",
                idempotencyKey = "key-first",
            ),
        )
        assertTrue(first.accepted)
        // Two later operations push the first one out of a two-receipt window.
        stopRepeatedly(tree, root.id, child.id, count = 2)
        assertNull("the first receipt really is out of the window", tree.stopReceipt("op-first"))

        assertTrue("aborting must still work, and must not throw", tree.abort(child.id, abnormal = false, reason = "stop acknowledged"))
        assertNull("with no receipt left there is nothing to attach the state to", tree.stopReceipt("op-first"))
        assertTrue(
            "but the terminal state must still be auditable, or this would be a silent loss",
            tree.events().any { it.kind == "aborted" && it.nodeId == child.id },
        )
        assertEquals("and the ledger stays consistent through it", 0, tree.danglingIdempotencyKeyCount())
    }
}
