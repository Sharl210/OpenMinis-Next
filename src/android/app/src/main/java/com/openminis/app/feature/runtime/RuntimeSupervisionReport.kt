package com.openminis.app.feature.runtime

import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-android-supervise-conversation-id] The model-facing shape of a supervision
 * report.
 *
 * Requirement (request.md:132, verbatim): 「我们的每一个相对主代理它都要有一个工具，
 * 就是和你这里的环境一样，它可以查看代理，就是查看每一个代理的**运行状况**，可以还有
 * 它的**哈希ID**…主代理嘛。它作为一个编排者…就是需要**时不时地去查看一下自己的名下的
 * 子代理**，是不是…**在按你自己预想的，某一些在正常的工作**」.
 *
 * Why this is a separate object rather than JSON built inline in the tool
 * executor: the same node shape has to be produced for the actor AND for every
 * descendant, and the two used to be built by two different inline blocks that
 * had already drifted (the actor reported `id`/`status`/`parent_id` while a
 * descendant additionally reported `root_id`/`depth` — so "the same field means
 * the same thing" was only true by luck). One function, both call sites. It is
 * pure (no Android, no Context) so the shape is testable on the JVM.
 *
 * **The "hash ID".** The requirement calls a sub-agent's id a 哈希ID and states
 * it is 「实际上是同一个ID」 as the conversation ID — which is already true
 * underneath: [RuntimeSessionCoordinator.startChild] hands the child SESSION id
 * straight to `SessionTreeRuntime.createChild` as the node id. This report
 * therefore exposes that one id in two forms and never invents a second:
 *
 *  - `id` — the raw runtime node id, kept for existing callers and for the
 *    `#run-N` root-generation spelling (see [ConversationIdProtocol]).
 *  - `conversation_id` — the SAME id in [ConversationIdProtocol.PREFIX] form,
 *    i.e. exactly what the user copies out of the UI and exactly what
 *    `conversation_query` accepts. A parent that reads this can act on it
 *    without a translation step.
 *
 * **"运行状况" beyond the status word.** A bare `RUNNING` cannot answer the
 * question the requirement actually asks — 「是不是在正常的工作」 — because a node
 * whose owning process died keeps saying `RUNNING` until its lease expires.
 * [nodeJson] therefore also carries `abnormal`, `lease_remaining_ms` and
 * `last_activity_age_ms`.
 */
internal object RuntimeSupervisionReport {

    /** How long the node's task description may be in the report. */
    private const val MAX_TASK_CHARS = 200

    fun toJson(snapshot: RuntimeSupervisionSnapshot, nowMillis: Long): JSONObject {
        val actor = snapshot.actorNode
        return JSONObject()
            .put("ok", actor != null)
            .put("configuration_revision", snapshot.configurationRevision)
            .put("actor", actor?.let { nodeJson(it, nowMillis) } ?: JSONObject.NULL)
            .put("capabilities", capabilitiesJson(snapshot))
            .put("descendants", JSONArray().apply {
                snapshot.descendants.forEach { put(nodeJson(it, nowMillis)) }
            })
    }

    /**
     * One node, in the single shape shared by the actor and every descendant.
     *
     * Field order is deliberate: identity → liveness → assignment. A parent
     * scanning the report reads "which conversation", then "is it alive", then
     * "was it told to do what I think".
     */
    fun nodeJson(node: RuntimeSessionNode, nowMillis: Long): JSONObject = JSONObject()
        // ── identity ────────────────────────────────────────────────────────
        .put("id", node.id)
        .put("conversation_id", ConversationIdProtocol.prefixedConversationId(node.id))
        // [T-android-conversation-id-query] The runtime ROUTE address for the same
        // node, because request.md:132 calls a sub-agent's identifier a 哈希ID and
        // this is what the app's existing 哈希ID means. Both are reported rather
        // than only one, and here is why that is not redundancy:
        //
        //  - `conversation_id` is REVERSIBLE to the session id, which is what
        //    `conversation_query`, `restart_descendant` and `stop_descendant`
        //    actually accept — it is the one a parent can ACT on.
        //  - `address` is the one-way locator that appears in the context
        //    attachments a parent sends (`RuntimeContextAttachments.capsule`) and
        //    in `communication_query` peer records. A parent seeing one of those
        //    can only correlate it to a child if the child is reported with it.
        //
        // They are deliberately NOT merged into one field: an address cannot be
        // resolved to a conversation, so presenting it as an id a caller could
        // query with would be a false promise.
        //
        // The generation is stripped BEFORE the address is derived, never inside
        // RuntimeConversationAddress: a re-run root's node id is
        // `"$sessionId#run-N"` (RuntimeSessionCoordinator.startRoot), and hashing
        // THAT would produce a different address from the one the gateway derives
        // for the same conversation (`RuntimeCommunicationGateway.peer` hashes the
        // session id). Two spellings of one conversation's address is exactly the
        // confusion the two-field shape above exists to avoid — so the
        // normalisation lives at the call site, where the runtime-id → conversation
        // rule already lives (`ConversationIdProtocol.conversationIdOf`).
        .put(
            "address",
            RuntimeConversationAddress
                .fromStableSessionId(ConversationIdProtocol.conversationIdOf(node.id))
                .value,
        )
        .put("parent_id", node.parentId ?: JSONObject.NULL)
        .put("root_id", node.rootId)
        .put("root_conversation_id", ConversationIdProtocol.prefixedConversationId(node.rootId))
        .put("depth", node.depth)
        .put("role", node.role)
        .put("delegation_mode", node.delegationMode.name)
        // ── liveness ("is this actually working, or only saying RUNNING?") ──
        .put("status", node.status.name)
        // Lease-expiry / crash flag. Without it the status word above is the
        // only signal, and it is the one a dead child keeps repeating.
        .put("abnormal", node.abnormal)
        .put(
            "lease_remaining_ms",
            node.leaseUntilMillis?.let { it - nowMillis } ?: JSONObject.NULL,
        )
        // Staleness: how long since the node last wrote anything to the tree.
        .put("last_activity_age_ms", (nowMillis - node.updatedAtMillis).coerceAtLeast(0L))
        .put("created_at_ms", node.createdAtMillis)
        // ── assignment ("was it told to do what I think it was?") ───────────
        .put("task", node.task.take(MAX_TASK_CHARS))
        .put("model", JSONObject().put("provider", node.model.provider).put("model", node.model.model))

    private fun capabilitiesJson(snapshot: RuntimeSupervisionSnapshot): Any {
        val capabilities = snapshot.actorCapabilities ?: return JSONObject.NULL
        return JSONObject()
            .put("node_id", capabilities.nodeId)
            // The capability block is also a model-facing id exit, so it carries
            // the same prefixed spelling as everywhere else. request.md:136 asks
            // the model to recognise the prefix as this app's own conversation
            // id; an exit that omits it would teach the opposite.
            .put(
                "conversation_id",
                ConversationIdProtocol.prefixedConversationId(capabilities.nodeId),
            )
            .put("root_id", capabilities.rootId)
            .put(
                "root_conversation_id",
                ConversationIdProtocol.prefixedConversationId(capabilities.rootId),
            )
            .put("depth", capabilities.depth)
            .put("role", capabilities.role)
            .put("effective_depth", capabilities.effectiveDepth)
            .put("self_can_delegate", capabilities.selfCanDelegate)
            .put("descendant_can_delegate", capabilities.descendantCanDelegate)
            .put("active_children", capabilities.currentActiveChildren)
            .put("max_parallel_subagents", capabilities.maxParallelSubagents)
            .put("config_revision", capabilities.configRevision)
    }
}
