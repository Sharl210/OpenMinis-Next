package com.openminis.app.tools

import android.content.Context
import com.openminis.app.feature.runtime.ConversationIdProtocol
import com.openminis.app.feature.runtime.DelegationMode
import com.openminis.app.feature.runtime.RuntimeDelegationRequest
import com.openminis.app.feature.runtime.RuntimeModelSnapshot
import com.openminis.app.feature.runtime.RuntimeSessionCoordinator
import com.openminis.app.feature.runtime.RuntimeCommunicationCursor
import com.openminis.app.feature.runtime.RuntimeCommunicationGateway
import com.openminis.app.feature.runtime.RuntimeCommunicationRepository
import com.openminis.app.feature.runtime.RuntimeDelivery
import com.openminis.app.feature.runtime.RuntimeDeliveryReceipt
import com.openminis.app.feature.runtime.RuntimeSupervisionReport
import com.openminis.app.ui.settings.AgentBehaviorSettingsPrefs
import com.openminis.app.web.BaiduHtmlAdapter
import com.openminis.app.web.BingHtmlAdapter
import com.openminis.app.web.DuckDuckGoHtmlAdapter
import com.openminis.app.web.GoogleHtmlAdapter
import com.openminis.app.web.PublicSearchService
import com.openminis.app.web.PublicWebFetcher
import com.openminis.app.web.WebFetchOptions
import com.openminis.app.web.WebFetchResult
import com.openminis.app.web.effectiveSearchOptions
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-android-child-agent-tools] Session-agnostic executor for the agent tools
 * that need nothing from the chat UI.
 *
 * Why this exists: [com.openminis.app.ui.chat.ChatViewModel.executeTool] is a
 * member function whose branches borrow UI-owned state (streaming tool blocks,
 * the per-session browser pool, the offload shell bridge, the root-goal
 * runtime). A delegated child agent — driven by
 * [com.openminis.app.feature.runtime.RuntimeChildRunner] — has none of that, so
 * it cannot call `executeTool`. Rather than copy those branches (two
 * implementations that drift), the branches whose only dependencies are a
 * [Context], a session id and the process-wide runtime coordinator live here,
 * and ChatViewModel's dispatcher delegates to this class for exactly those
 * names. One implementation, two callers.
 *
 * The tools this executor owns are enumerated by [PORTABLE_TOOL_NAMES] — a naming
 * of that set, NOT an enforcement mechanism. Everything else stays in ChatViewModel
 * and is NOT offered to a delegated child, because it genuinely cannot work
 * without the UI scope it is written against; a tool that IS safe for a child
 * belongs in that set *and* in
 * [com.openminis.app.tools.AgentTools.makeChildAgentTools]. See
 * [com.openminis.app.feature.runtime.ChildAgentLoop] for the child-side surface.
 *
 * The set and [execute]'s `when` are two lists of the same thing, and nothing in
 * production forces them to agree — [execute] dispatches on the name and never
 * consults the set. What keeps them in step is
 * `com.openminis.app.tools.AgentToolsChildToolsTest` and
 * `com.openminis.app.feature.runtime.ChildAgentLoopTest`, which assert
 * `makeChildAgentTools() == {completion} ∪ PORTABLE_TOOL_NAMES` and "every
 * advertised child tool has an executor". Both are test-only guards: add a tool
 * to one side alone and they go red — which is exactly what happened when
 * `conversation_query` was added to the child surface without being added here.
 */
class AgentToolExecutor(
    private val context: Context,
    /**
     * [T-android-child-restart] The re-execution half of a descendant restart,
     * supplied by whoever owns a coroutine scope and the parent's transcript.
     *
     * It is a constructor parameter — and null by default — precisely because
     * this class cannot supply it honestly: `restart_descendant` runs inside the
     * main agent's tool dispatch, while launching a child is a long-running job
     * that has to outlive the tool call and append its result to the parent's
     * history (that is what
     * [com.openminis.app.ui.chat.ChatViewModel.dispatchDelegationCommand] does for
     * the normal delegation path). A `null` here is a missing capability, not a
     * silent one: [restartDescendant] turns it into an explicit failure rather
     * than a "restarted" that never ran anything.
     */
    private val launchRestartedChild: RestartedChildLauncher? = null,
    /**
     * [T-android-conversation-id-query] The read port behind `conversation_query`.
     *
     * Null by default and NOT defaulted to a Context-built store: the live chat
     * store is a `ChatRepository`, which both production callers already hold, so
     * passing it in is the honest wiring. A null here produces an explicit
     * failure result naming the missing capability (see [conversationQuery])
     * rather than an empty transcript that would read as "that conversation has
     * nothing in it" — the one answer this tool must never invent.
     */
    private val conversationSource: ConversationTranscriptSource? = null,
    /**
     * [T-android-agent-messaging] The communication directory behind
     * `message_child`.
     *
     * Passed in rather than built from [context] on purpose. The gateway writes
     * the directory by rewriting the WHOLE file
     * ([RuntimeCommunicationFileStore.write]), and
     * [RuntimeCommunicationRepository] keeps its own in-memory copy loaded at
     * construction — so two repositories over the same path do not merge, the
     * later writer simply drops what the other one added. The chat view already
     * owns a repository for the same file (`recordDelivered` on the delegation
     * path), so the executor must be handed THAT instance instead of minting a
     * second one. Null here stays an explicit failure, never a "sent" that was
     * only recorded locally.
     */
    private val communicationRepository: RuntimeCommunicationRepository? = null,
) {

    /**
     * Execute one of [PORTABLE_TOOL_NAMES].
     *
     * @return the tool result, or `null` when [name] is not owned by this
     *   executor — callers use the null to fall through to their own dispatch
     *   instead of silently reporting an unknown tool.
     */
    suspend fun execute(
        name: String,
        argsJson: String,
        sessionId: String,
        runtimeCoordinator: RuntimeSessionCoordinator? = null,
    ): ToolExecutionResult? = when (name) {
        WEB_SEARCH -> webSearch(argsJson)
        WEB_FETCH -> webFetch(argsJson)
        SUPERVISE_DESCENDANTS -> superviseDescendants(argsJson, sessionId, runtimeCoordinator)
        MESSAGE_CHILD -> messageChild(argsJson, sessionId, runtimeCoordinator)
        MESSAGE_PEER -> messagePeer(argsJson, sessionId, runtimeCoordinator)
        RESTART_DESCENDANT -> restartDescendant(argsJson, sessionId, runtimeCoordinator)
        CONVERSATION_QUERY -> conversationQuery(argsJson)
        else -> null
    }

    /**
     * [T-android-conversation-id-query] Read another conversation by its ID.
     *
     * Read-only by construction: the only things this function does with the
     * store are [ConversationTranscriptSource.count], `.page` and `.search`.
     *
     * No authorization, per the requirement's 「不需要有鉴权」 — the argument
     * that makes that safe is that a conversation ID is an unguessable UUID, so
     * possession of the ID IS the capability. What is enforced instead is the
     * 截流 the same requirement demands: a hard character budget per call plus a
     * `next_cursor` for everything left over, so a caller cannot pull a whole
     * conversation in one shot and flood its own context.
     */
    private suspend fun conversationQuery(argsJson: String): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: return ToolExecutionResult(
                "{\"ok\":false,\"error\":{\"code\":\"INVALID_ARGUMENTS\",\"message\":\"Invalid JSON\"}}",
                false,
                toolTitle = "Conversation Query",
            )
        val title = args.optString("tool_title", "Conversation Query")
        val rawId = args.optString("conversation_id", "").trim()
        if (rawId.isBlank()) {
            return ToolExecutionResult(
                JSONObject().put("ok", false).put(
                    "error",
                    JSONObject().put("code", "INVALID_ARGUMENTS")
                        .put("message", "conversation_id is required"),
                ).toString(),
                false,
                toolTitle = title,
            )
        }
        // [T-android-conversation-id-query] Reject a runtime ROUTE address before
        // doing anything else. `openminis-conv:<sha256>` is a different object
        // from a conversation id: it is one-way (SHA-256), so it cannot be turned
        // back into a session id, and querying the store with it would find no
        // session and return an empty page. To the model that reads as "that
        // conversation has no messages" — a wrong answer stated confidently, which
        // is worse than a refusal that says which id to use instead.
        if (ConversationIdProtocol.isRuntimeAddress(rawId)) {
            return ToolExecutionResult(
                JSONObject().put("ok", false).put(
                    "error",
                    JSONObject().put("code", "ADDRESS_NOT_QUERYABLE")
                        .put(
                            "message",
                            "`${ConversationIdProtocol.RUNTIME_ADDRESS_PREFIX}…` is a runtime routing " +
                                "address, not a conversation id: it is a one-way hash and cannot be " +
                                "resolved back to a conversation. Use a conversation id " +
                                "(`${ConversationIdProtocol.PREFIX}…`) instead — copy one from the " +
                                "chat's overflow menu or read it from supervise_descendants' " +
                                "`conversation_id` field.",
                        ),
                ).toString(),
                false,
                toolTitle = title,
            )
        }
        // Accepts either spelling: the prefixed id the model was handed, or a
        // bare id it read somewhere internal. One rule, applied here, so no
        // caller has to know which form is "the" form.
        val conversationId = ConversationIdProtocol.strip(rawId)
        if (conversationId.isBlank()) {
            return ToolExecutionResult(
                JSONObject().put("ok", false).put(
                    "error",
                    JSONObject().put("code", "INVALID_ARGUMENTS")
                        .put("message", "conversation_id is empty after removing the prefix"),
                ).toString(),
                false,
                toolTitle = title,
            )
        }
        val source = conversationSource
            ?: return ToolExecutionResult(
                JSONObject().put("ok", false).put(
                    "error",
                    JSONObject().put("code", "UNAVAILABLE")
                        .put("message", "conversation reading is not available in this context"),
                ).toString(),
                false,
                toolTitle = title,
            )

        // Bounds mirror R43's directory so both read tools reject an absurd page
        // and an absurd budget the same way, with the same reason names.
        val limit = args.optInt("limit", ConversationQueryPolicy.defaultPageLimit())
        if (limit !in 1..ConversationQueryPolicy.maxPageLimit()) {
            return ToolExecutionResult(
                JSONObject().put("ok", false).put(
                    "error",
                    JSONObject().put("code", "PAGE_LIMIT_EXCEEDED")
                        .put("message", "limit must be between 1 and ${ConversationQueryPolicy.maxPageLimit()}"),
                ).toString(),
                false,
                toolTitle = title,
            )
        }
        val budget = args.optInt("result_budget_chars", ConversationQueryPolicy.defaultBudgetChars())
        if (budget !in 1..ConversationQueryPolicy.maxBudgetChars()) {
            return ToolExecutionResult(
                JSONObject().put("ok", false).put(
                    "error",
                    JSONObject().put("code", "RESULT_BUDGET_EXCEEDED")
                        .put("message", "result_budget_chars must be between 1 and ${ConversationQueryPolicy.maxBudgetChars()}"),
                ).toString(),
                false,
                toolTitle = title,
            )
        }
        val cursor = args.optString("cursor", "").trim()
            .takeIf { it.isNotEmpty() }
            ?.let { RuntimeCommunicationCursor(it) }
        val offset = ConversationQueryPolicy.decodeCursor(cursor).getOrElse {
            return ToolExecutionResult(
                JSONObject().put("ok", false).put(
                    "error",
                    JSONObject().put("code", "INVALID_CURSOR")
                        .put("message", "the cursor is not one this tool issued; omit it to start over"),
                ).toString(),
                false,
                toolTitle = title,
            )
        }
        val keyword = args.optString("query", "").trim()
        val searching = keyword.isNotEmpty()

        val json = runCatching {
            if (searching) {
                val slice = source.search(conversationId, keyword, offset, limit)
                val kept = ConversationQueryPolicy.fitToBudget(slice.items, budget)
                ConversationQueryPolicy.resultJson(
                    conversationIdRaw = conversationId,
                    messages = kept,
                    // Hits found in the window THIS call examined. Not the
                    // session's match total — see resultJson's contract.
                    totalMatching = slice.items.size,
                    // Derived from where the scan stopped, not from what was
                    // delivered: `offset + kept.size` mixed the two index spaces
                    // and produced a cursor that stood still or went backwards.
                    nextCursor = ConversationQueryPolicy.nextCursor(slice, kept.size, offset),
                    budgetChars = budget,
                    mode = "search",
                )
            } else {
                val total = source.count(conversationId)
                val slice = source.page(
                    conversationId,
                    offset,
                    limit,
                    ConversationQueryPolicy.MESSAGE_TEXT_MAX,
                )
                val kept = ConversationQueryPolicy.fitToBudget(slice.items, budget)
                ConversationQueryPolicy.resultJson(
                    conversationIdRaw = conversationId,
                    messages = kept,
                    totalMatching = total,
                    // `total` is a raw row count and so is `resumeAfter`, so they
                    // share an index space and the comparison is exact — no extra
                    // round trip for a session whose size divides evenly.
                    nextCursor = ConversationQueryPolicy.nextCursor(
                        slice,
                        kept.size,
                        offset,
                        totalRows = total,
                    ),
                    budgetChars = budget,
                    mode = "page",
                )
            }
        }.getOrElse { error ->
            // [T-android-cancel-not-a-failure] `runCatching` catches
            // CancellationException like any other throwable. Swallowing it here
            // would hand the agent loop a fabricated "READ_FAILED" result while
            // the surrounding coroutine was being cancelled (user Stop), so a
            // read that never finished would look like a read that ran and
            // failed. Cancellation is not a read failure — rethrow it and let
            // the caller's own cancel path report the outcome.
            if (error is kotlinx.coroutines.CancellationException) throw error
            return ToolExecutionResult(
                JSONObject().put("ok", false).put(
                    "error",
                    JSONObject().put("code", "READ_FAILED")
                        .put("message", error.message ?: "reading the conversation failed"),
                ).toString(),
                false,
                toolTitle = title,
            )
        }
        return ToolExecutionResult(json.toString(), true, toolTitle = title)
    }

    private suspend fun webSearch(argsJson: String): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: return ToolExecutionResult(
                "{\"ok\":false,\"error\":{\"code\":\"INVALID_ARGUMENTS\",\"message\":\"Invalid JSON\"}}",
                false,
                toolTitle = "Search Web",
            )
        val query = args.optString("query", "").trim()
        val title = args.optString("tool_title", "Search Web")
        if (query.isBlank()) {
            return ToolExecutionResult(
                "{\"ok\":false,\"error\":{\"code\":\"INVALID_ARGUMENTS\",\"message\":\"query is required\"}}",
                false,
                toolTitle = title,
            )
        }
        val prefs = AgentBehaviorSettingsPrefs(context).load()
        val options = effectiveSearchOptions(
            requestedMaxResults = args.optInt("max_results", prefs.webSearchMaxResults),
            requestedTimeoutMs = args.optLong("timeout_ms", prefs.webRequestTimeoutMs),
            userMaxResults = prefs.webSearchMaxResults,
            userTimeoutMs = prefs.webRequestTimeoutMs,
        )
        // [T-android-web-search-engines] The aggregated engine: the three mainstream engines named by
        // the requirement first, then DuckDuckGo as the independent safety net. Order matters only when
        // several engines answer, because the service de-duplicates and truncates to maxResults — so the
        // named engines must come first or the default settings would never surface them.
        val result = PublicSearchService(
            listOf(
                GoogleHtmlAdapter(),
                BingHtmlAdapter(),
                BaiduHtmlAdapter(),
                DuckDuckGoHtmlAdapter(),
            ),
        ).search(query, options)
        val json = JSONObject().put("ok", result.failures.isEmpty() || result.items.isNotEmpty())
            .put("query", result.query)
            .put("results", JSONArray().apply {
                result.items.forEach { item ->
                    put(
                        JSONObject()
                            .put("title", item.title).put("url", item.url)
                            .put("snippet", item.snippet ?: JSONObject.NULL).put("source", item.source),
                    )
                }
            })
            .put("failures", JSONArray().apply {
                result.failures.forEach { failure ->
                    put(JSONObject().put("source", failure.source).put("detail", failure.detail))
                }
            })
        return ToolExecutionResult(json.toString(), result.items.isNotEmpty(), toolTitle = title)
    }

    private suspend fun webFetch(argsJson: String): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: return ToolExecutionResult(
                "{\"ok\":false,\"error\":{\"code\":\"INVALID_ARGUMENTS\",\"message\":\"Invalid JSON\"}}",
                false,
                toolTitle = "Fetch Web",
            )
        val url = args.optString("url", "").trim()
        val title = args.optString("tool_title", "Fetch Web")
        if (url.isBlank()) {
            return ToolExecutionResult(
                "{\"ok\":false,\"error\":{\"code\":\"INVALID_ARGUMENTS\",\"message\":\"url is required\"}}",
                false,
                toolTitle = title,
            )
        }
        val prefs = AgentBehaviorSettingsPrefs(context).load()
        val timeout = args.optLong("timeout_ms", prefs.webRequestTimeoutMs).coerceAtMost(prefs.webRequestTimeoutMs)
        val maxBytes = args.optInt("max_bytes", PublicWebFetcher.DEFAULT_MAX_BYTES)
            .coerceAtMost(PublicWebFetcher.DEFAULT_MAX_BYTES)
        return when (val result = PublicWebFetcher().fetch(url, WebFetchOptions(timeoutMs = timeout, maxBytes = maxBytes))) {
            is WebFetchResult.Success -> ToolExecutionResult(
                JSONObject().put("ok", true).put("url", result.url).put("content", result.body).toString(),
                true,
                toolTitle = title,
            )
            is WebFetchResult.Failure -> ToolExecutionResult(
                JSONObject()
                    .put("ok", false).put("url", result.url)
                    .put("error", JSONObject().put("code", result.reason.name).put("message", result.detail))
                    .toString(),
                false,
                toolTitle = title,
            )
        }
    }

    private suspend fun superviseDescendants(
        argsJson: String,
        sessionId: String,
        runtimeCoordinator: RuntimeSessionCoordinator?,
    ): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: return ToolExecutionResult("{\"ok\":false,\"error\":\"invalid arguments\"}", false, toolTitle = "Supervise Descendants")
        val title = args.optString("tool_title", "Supervise Descendants")
        val actor = sessionId.trim()
        if (actor.isBlank()) {
            return ToolExecutionResult("{\"ok\":false,\"error\":\"actor session is missing\"}", false, toolTitle = title)
        }
        if (runtimeCoordinator == null) {
            return ToolExecutionResult(
                JSONObject().put("ok", false).put("error", "runtime coordinator is unavailable").toString(),
                false,
                toolTitle = title,
            )
        }
        val snapshot = runCatching { runtimeCoordinator.supervisionSnapshot(actor) }.getOrElse { error ->
            return ToolExecutionResult(
                JSONObject().put("ok", false).put("error", error.message ?: "runtime supervision failed").toString(),
                false,
                toolTitle = title,
            )
        }
        // [T-android-supervise-conversation-id] The report shape lives in
        // RuntimeSupervisionReport so the actor and every descendant are
        // serialised by ONE function — they used to be two inline blocks that had
        // already drifted apart. See that file for why `conversation_id` (the
        // requirement's 哈希ID) is the same id as the session, in prefix form.
        val json = RuntimeSupervisionReport.toJson(snapshot, System.currentTimeMillis())
        return ToolExecutionResult(json.toString(), snapshot.actorNode != null, toolTitle = title)
    }

    /**
     * [T-android-agent-messaging] Send one message from the bound session to one
     * of its descendants, through the runtime's authorized mailbox.
     *
     * The chain this closes: the runtime tree could already carry parent→child
     * messages — [com.openminis.app.feature.runtime.SessionTreeRuntime.send]
     * enqueues into a child's inbox with ancestry/edge authorization, and
     * [com.openminis.app.feature.runtime.RuntimeChildRunner] drains
     * STEER/QUEUE/NOTIFY into the child's next model call — but nothing in
     * production ever called it, so no message was ever sent. This branch is the
     * missing producer, not a second implementation: it goes through
     * [RuntimeCommunicationGateway.send], the same entry the delegation path
     * uses.
     *
     * Everything that decides WHETHER the message may be sent (does the target
     * exist, same root, a real delivery edge carrying the required permission,
     * payload within limit) is decided by the runtime tree behind the gateway,
     * not here. Re-deriving authorization in the tool would create a second,
     * weaker copy of the rule. What this function owns is the part the tree
     * cannot know: which of the model's id spellings resolves to a node, and
     * what the model is TOLD when the answer is no.
     *
     * Failure is never softened: a rejected send returns `success = false` with
     * the runtime's own reason, because "the parent believes it steered a
     * sub-agent that received nothing" is the one outcome this tool must never
     * produce.
     */
    private fun messageChild(
        argsJson: String,
        sessionId: String,
        runtimeCoordinator: RuntimeSessionCoordinator?,
    ): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: return messageChildFailure(
                "INVALID_ARGUMENTS",
                "the arguments are not valid JSON",
                "Message Child",
            )
        val title = args.optString("tool_title", "Message Child")
        // The actor is the application-bound session, never an argument field:
        // the schema is documentation for the model, so an identity field could
        // only ever be a forgery attempt. Same discipline as superviseDescendants.
        val actor = sessionId.trim()
        if (actor.isEmpty()) {
            return messageChildFailure(
                "NO_ACTOR",
                "the current session is unknown, so there is no sender to authorize against",
                title,
            )
        }
        val rawTarget = args.optString("target_session_id", "").trim()
        if (rawTarget.isEmpty()) {
            return messageChildFailure(
                "INVALID_ARGUMENTS",
                "target_session_id is required: name the sub-agent to message " +
                    "(call supervise_descendants for the current list)",
                title,
            )
        }
        // A runtime ROUTE address is not a conversation: `openminis-conv:<sha256>`
        // is one-way, so it cannot be resolved back to a node and would come back
        // as the tree's generic "unknown node" — a true sentence that sends the
        // model looking for the wrong mistake. Name the actual problem instead.
        if (ConversationIdProtocol.isRuntimeAddress(rawTarget)) {
            return messageChildFailure(
                "ADDRESS_NOT_MESSAGEABLE",
                "`${ConversationIdProtocol.RUNTIME_ADDRESS_PREFIX}…` is a runtime routing address, " +
                    "not a conversation id: it is a one-way hash and cannot be resolved to a " +
                    "sub-agent. Use the `conversation_id` " +
                    "(`${ConversationIdProtocol.PREFIX}…`) or the raw `id` that " +
                    "supervise_descendants reported.",
                title,
            )
        }
        // Accepts either spelling — the prefixed id the model was handed, or the
        // bare id it read somewhere internal — for the same reason
        // conversation_query does: "the ID you were given" and "the ID that
        // works" must be the same string.
        val target = ConversationIdProtocol.strip(rawTarget)
        if (target.isEmpty()) {
            return messageChildFailure(
                "INVALID_ARGUMENTS",
                "target_session_id is empty after removing the conversation-id prefix",
                title,
            )
        }
        val payload = args.optString("message", "").trim()
        if (payload.isEmpty()) {
            return messageChildFailure(
                "INVALID_ARGUMENTS",
                "message is required and must not be blank: an empty steer would interrupt the " +
                    "sub-agent with nothing",
                title,
                target,
            )
        }
        val requested = args.optString("delivery", AgentTools.DELIVERY_STEER).trim().lowercase()
        val delivery = when (requested) {
            // Blank is the default rather than an error: `delivery` is optional,
            // and the schema's stated default has to be the one that applies when
            // the model omits the field.
            "", AgentTools.DELIVERY_STEER -> RuntimeDelivery.STEER
            AgentTools.DELIVERY_QUEUE -> RuntimeDelivery.QUEUE
            AgentTools.DELIVERY_NOTIFY -> RuntimeDelivery.NOTIFY
            else -> return messageChildFailure(
                "UNKNOWN_DELIVERY",
                "delivery '$requested' is not one of ${AgentTools.DELIVERY_STEER}, " +
                    "${AgentTools.DELIVERY_QUEUE}, ${AgentTools.DELIVERY_NOTIFY}; nothing was sent",
                title,
                target,
            )
        }
        if (runtimeCoordinator == null) {
            return messageChildFailure(
                "UNAVAILABLE",
                "the runtime coordinator is unavailable, so no message was delivered",
                title,
                target,
            )
        }
        val repository = communicationRepository
            ?: return messageChildFailure(
                "UNAVAILABLE",
                "the communication directory is unavailable in this context, so no message was " +
                    "delivered",
                title,
                target,
            )
        val result = runCatching {
            runtimeCoordinator.communicationGateway(repository).send(
                actorSessionId = actor,
                targetSessionId = target,
                payload = payload,
                delivery = delivery,
            )
        }.getOrElse { error ->
            return messageChildFailure(
                "SEND_FAILED",
                error.message ?: "the runtime failed to accept the message",
                title,
                target,
            )
        }
        return when (result) {
            is RuntimeCommunicationGateway.SendResult.Accepted -> ToolExecutionResult(
                JSONObject()
                    .put("ok", true)
                    .put("delivered", true)
                    .put("target_session_id", target)
                    .put("conversation_id", ConversationIdProtocol.prefixedConversationId(target))
                    .put("requested_delivery", delivery.name.lowercase())
                    .put(
                        "delivery",
                        result.receipt.effectiveDelivery?.name?.lowercase()
                            ?: delivery.name.lowercase(),
                    )
                    .put("message_id", result.receipt.messageId ?: JSONObject.NULL)
                    .put("record_id", result.recordId)
                    .put(
                        "note",
                        result.receipt.reason
                            ?: "queued in the sub-agent's inbox; it is read at the sub-agent's " +
                                "next model step",
                    )
                    .toString(),
                true,
                toolTitle = title,
            )
            is RuntimeCommunicationGateway.SendResult.Rejected -> messageChildFailure(
                "DELIVERY_REJECTED",
                result.reason,
                title,
                target,
                result.receipt,
            )
        }
    }

    /**
     * The one shape a failed `message_child` takes.
     *
     * `delivered = false` and `accepted = false` are stated explicitly rather
     * than left to be inferred from `ok = false`, because this project's
     * recurring defect is a failure that reads like a success somewhere up the
     * stack. A caller that only checks one field still cannot mistake this for a
     * delivery.
     */
    private fun messageChildFailure(
        code: String,
        detail: String,
        title: String,
        target: String? = null,
        receipt: RuntimeDeliveryReceipt? = null,
    ): ToolExecutionResult {
        val json = JSONObject()
            .put("ok", false)
            .put("accepted", false)
            .put("delivered", false)
        if (!target.isNullOrEmpty()) json.put("target_session_id", target)
        receipt?.messageId?.let { json.put("message_id", it) }
        receipt?.effectiveDelivery?.let { json.put("effective_delivery", it.name.lowercase()) }
        json.put("error", JSONObject().put("code", code).put("message", detail))
        return ToolExecutionResult(json.toString(), false, toolTitle = title)
    }

    /**
     * [team-peer-mesh] Send one message from the bound session to a Team PEER —
     * a sibling sub-agent under the same runtime root.
     *
     * The chain this closes: joining a Team builds a bidirectional `TEAM_PEER`
     * edge between every pair of members and `SessionTreeRuntime.send` authorizes
     * peer delivery off it, but delegated children were handed NO messaging tool
     * at all, so request.md:5 「所有子代理可以相互沟通」 and request.md:33 「从和从之间
     * 可以 P2P」 had no producer: the addresses existed and nothing could dial.
     * This branch is that producer. It does not add a delivery mechanism — it
     * reaches the same authorized mailbox `message_child` uses.
     *
     * Authorization stays entirely in the runtime tree: same root, Team mode on
     * both endpoints, and an existing SEND-bearing peer edge. Nothing is
     * re-derived here, because a second, weaker copy of the rule is how an
     * unauthorized message gets through.
     *
     * One deviation from [messageChild] is deliberate and worth stating, because
     * it is forced by the wiring rather than chosen. The directory-recording half
     * goes through [RuntimeCommunicationGateway], which needs a
     * [RuntimeCommunicationRepository]: that instance owns an in-memory copy of
     * the directory file, so a second repository over the same path would silently
     * drop the records the first one wrote (`ChatViewModel` states the rule as
     * "One file, one instance"). A delegated child's executor is constructed
     * WITHOUT one (`RuntimeChildRunner`), so when it is absent this function
     * delivers through `RuntimeSessionCoordinator.send` — the same authorization
     * entry the gateway wraps — and says so in its reply instead of failing. A
     * silent downgrade would be worse than either alternative; a hard failure
     * would make the tool unusable for the only callers it exists for.
     *
     * Failure is never softened, exactly like [messageChild]: a refusal returns
     * `success = false` carrying the runtime's own reason, because "a teammate
     * believes it asked for help that was never sent" is the one outcome this tool
     * must never produce.
     */
    private suspend fun messagePeer(
        argsJson: String,
        sessionId: String,
        runtimeCoordinator: RuntimeSessionCoordinator?,
    ): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: return messageChildFailure("INVALID_ARGUMENTS", "the arguments are not valid JSON", "Message Peer")
        val title = args.optString("tool_title", "Message Peer")
        // Identity discipline copied from [messageChild]: the actor is the
        // application-bound session and is never read from the arguments.
        val actor = sessionId.trim()
        if (actor.isEmpty()) {
            return messageChildFailure(
                "NO_ACTOR",
                "the current session is unknown, so there is no sender to authorize against",
                title,
            )
        }
        val rawTarget = args.optString("target_session_id", "").trim()
        if (rawTarget.isEmpty()) {
            return messageChildFailure(
                "INVALID_ARGUMENTS",
                "target_session_id is required: name the peer sub-agent to message " +
                    "(call supervise_descendants for the current list)",
                title,
            )
        }
        // Same one-way-hash problem as `message_child`: a runtime ROUTE address
        // cannot be resolved back to a node, so forwarding it would come back as
        // the tree's generic "unknown node".
        if (ConversationIdProtocol.isRuntimeAddress(rawTarget)) {
            return messageChildFailure(
                "ADDRESS_NOT_MESSAGEABLE",
                "`${ConversationIdProtocol.RUNTIME_ADDRESS_PREFIX}…` is a runtime routing address, " +
                    "not a conversation id: it is a one-way hash and cannot be resolved to a " +
                    "sub-agent. Use the `conversation_id` (`${ConversationIdProtocol.PREFIX}…`) or " +
                    "the raw `id` that supervise_descendants reported.",
                title,
            )
        }
        val target = ConversationIdProtocol.strip(rawTarget)
        if (target.isEmpty()) {
            return messageChildFailure(
                "INVALID_ARGUMENTS",
                "target_session_id is empty after removing the conversation-id prefix",
                title,
            )
        }
        if (target == actor) {
            return messageChildFailure(
                "INVALID_ARGUMENTS",
                "a session cannot message itself: peers are the OTHER members of this team",
                title,
                target,
            )
        }
        val payload = args.optString("message", "").trim()
        if (payload.isEmpty()) {
            return messageChildFailure(
                "INVALID_ARGUMENTS",
                "message is required and must not be blank: an empty peer message would wake the " +
                    "peer with nothing",
                title,
                target,
            )
        }
        if (runtimeCoordinator == null) {
            return messageChildFailure(
                "UNAVAILABLE",
                "the runtime coordinator is unavailable, so no message was delivered",
                title,
                target,
            )
        }
        val repository = communicationRepository
        if (repository == null) {
            // See the KDoc: the child-side executor has no directory instance, so
            // the message goes through the same authorization entry the gateway
            // itself calls, and the reply states that no directory record was
            // written rather than pretending otherwise.
            val receipt = runCatching {
                runtimeCoordinator.send(actor, target, payload, RuntimeDelivery.TEAM_PEER)
            }.getOrElse { error ->
                return messageChildFailure(
                    "SEND_FAILED",
                    error.message ?: "the runtime failed to accept the message",
                    title,
                    target,
                )
            }
            return if (receipt.accepted) {
                messagePeerDelivered(
                    title = title,
                    target = target,
                    receipt = receipt,
                    recordId = null,
                    note = "queued in the peer's inbox; it is read at the peer's next model step. " +
                        "No communication-directory record was written: this context has no " +
                        "directory instance.",
                )
            } else {
                messageChildFailure(
                    "DELIVERY_REJECTED",
                    receipt.reason ?: "runtime delivery rejected",
                    title,
                    target,
                    receipt,
                )
            }
        }
        val result = runCatching {
            runtimeCoordinator.communicationGateway(repository).send(
                actorSessionId = actor,
                targetSessionId = target,
                payload = payload,
                delivery = RuntimeDelivery.TEAM_PEER,
            )
        }.getOrElse { error ->
            return messageChildFailure(
                "SEND_FAILED",
                error.message ?: "the runtime failed to accept the message",
                title,
                target,
            )
        }
        return when (result) {
            is RuntimeCommunicationGateway.SendResult.Accepted -> messagePeerDelivered(
                title = title,
                target = target,
                receipt = result.receipt,
                recordId = result.recordId,
                note = "queued in the peer's inbox; it is read at the peer's next model step.",
            )
            is RuntimeCommunicationGateway.SendResult.Rejected -> messageChildFailure(
                "DELIVERY_REJECTED",
                result.reason,
                title,
                target,
                result.receipt,
            )
        }
    }

    /**
     * The one shape a delivered peer message takes. Stated positively and in the
     * same field names [messageChild] uses, so a reader of either reply fails the
     * same way when it is a failure.
     */
    private fun messagePeerDelivered(
        title: String,
        target: String,
        receipt: RuntimeDeliveryReceipt,
        recordId: String?,
        note: String,
    ): ToolExecutionResult = ToolExecutionResult(
        JSONObject()
            .put("ok", true)
            .put("delivered", true)
            .put("target_session_id", target)
            .put("conversation_id", ConversationIdProtocol.prefixedConversationId(target))
            .put("delivery", RuntimeDelivery.TEAM_PEER.name.lowercase())
            .put("message_id", receipt.messageId ?: JSONObject.NULL)
            .put("record_id", recordId ?: JSONObject.NULL)
            .put("note", receipt.reason ?: note)
            .toString(),
        true,
        toolTitle = title,
    )

    /**
     * [T-android-child-restart] Restart one abnormally interrupted descendant of
     * the bound session.
     *
     * Identity discipline copied verbatim from [superviseDescendants]: the actor
     * is `sessionId`, which the application binds — there is NO identity field in
     * the argument JSON, and any `actor_session_id` the model invents is simply
     * never read. The schema is documentation for the model, not a security
     * boundary, so the boundary has to be that the actor is never taken from the
     * arguments at all.
     *
     * Honesty discipline: a successful [RuntimeSessionCoordinator.restartDescendant]
     * only re-arms the runtime NODE. The agent itself is re-run by
     * [RestartedChildLauncher], which the app must supply. When it is absent the
     * result is a failure that says so — reporting success here would tell the
     * parent a child is working when nothing is running it, which is worse than
     * having no restart tool at all.
     */
    private suspend fun restartDescendant(
        argsJson: String,
        sessionId: String,
        runtimeCoordinator: RuntimeSessionCoordinator?,
    ): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: return ToolExecutionResult(
                "{\"ok\":false,\"error\":\"invalid arguments\"}",
                false,
                toolTitle = "Restart Descendant",
            )
        val title = args.optString("tool_title", "Restart Descendant")
        val childSessionId = args.optString("child_session_id", "").trim()
        val reason = args.optString("reason", "").trim()
        if (childSessionId.isBlank()) {
            return ToolExecutionResult(
                JSONObject().put("ok", false)
                    .put("error", "child_session_id is required: name the descendant to restart")
                    .toString(),
                false,
                toolTitle = title,
            )
        }
        if (runtimeCoordinator == null) {
            return ToolExecutionResult(
                JSONObject().put("ok", false)
                    .put("child_session_id", childSessionId)
                    .put("error", "runtime coordinator is unavailable")
                    .toString(),
                false,
                toolTitle = title,
            )
        }
        // Refuse BEFORE touching the tree when there is no launch path.
        //
        // This ordering is the whole point. Restarting is a two-part operation:
        // return the node to RUNNING, then actually re-run the agent. If the node
        // were re-armed first and the launch then found impossible, the tree would
        // report a live child that nothing is running — `supervise_descendants`
        // would stop listing it as interrupted, and the parent would wait for a
        // result that can never arrive. Checking the second half first means the
        // node keeps saying ABNORMAL_INTERRUPTION, which is still true.
        val launcher = launchRestartedChild
        if (launcher == null) {
            return ToolExecutionResult(
                JSONObject().put("ok", false)
                    .put("child_session_id", childSessionId)
                    .put("error", "restart is not available: this build has no launch path that can " +
                        "re-run a delegated child, so the child was left untouched and is still " +
                        "marked as abnormally interrupted. Do not retry this call.")
                    .toString(),
                false,
                toolTitle = title,
            )
        }
        // Reconstruct WHAT to re-run from the node itself rather than from the
        // argument JSON: the model does not get to restate the delegated task,
        // and the node already carries the task text and the model it was born
        // with. A restart therefore resumes the same work on the same model —
        // not a new, model-chosen piece of work.
        val node = runCatching { runtimeCoordinator.supervisionSnapshot(sessionId.trim()) }
            .getOrNull()
            ?.descendants
            ?.firstOrNull { it.id == childSessionId }
        val request = RuntimeDelegationRequest(
            prompt = node?.task?.takeIf { it.isNotBlank() }
                ?: "Resume and complete your previously delegated task. Your earlier run was " +
                    "interrupted before it finished.",
            mode = node?.delegationMode ?: DelegationMode.TRADITIONAL,
            model = node?.model?.model?.takeIf { it.isNotBlank() },
            capabilities = node?.model?.capabilities ?: RuntimeModelSnapshot.DEFAULT_CAPABILITIES,
        )
        val restarted = runCatching {
            runtimeCoordinator.restartDescendant(
                // The actor is the application-bound session. It is deliberately
                // read from the `sessionId` parameter and never from `args`: the
                // schema is documentation for the model, so an argument field
                // could be forged, while this binding cannot.
                parentSessionId = sessionId.trim(),
                childSessionId = childSessionId,
                reason = reason,
            )
        }.getOrElse { error ->
            return ToolExecutionResult(
                JSONObject().put("ok", false)
                    .put("child_session_id", childSessionId)
                    .put("error", error.message ?: "runtime restart failed")
                    .toString(),
                false,
                toolTitle = title,
            )
        }
        if (!restarted) {
            // Not a crash-recovery candidate: the id is unknown, belongs to
            // another session's tree, is a generation root of this session rather
            // than one of its sub-agents, is not a descendant of this session, or
            // the node is still running (restarting live work is refused on
            // purpose). Nothing changed.
            return ToolExecutionResult(
                JSONObject().put("ok", false)
                    .put("child_session_id", childSessionId)
                    .put("error", "restart rejected: target must be an existing descendant of " +
                        "this session whose runtime node is terminal")
                    .toString(),
                false,
                toolTitle = title,
            )
        }
        val launched = runCatching {
            launcher.restart(
                parentSessionId = sessionId.trim(),
                childSessionId = childSessionId,
                request = request,
            )
        }
            .getOrElse { error ->
                // [T-android-child-restart] Cancellation is not a launch failure,
                // but it still leaves the child un-run. Both halves matter, in
                // this order:
                //
                //  * roll back FIRST — the node was re-armed before the launch, so
                //    a cancelled restart would otherwise leave a RUNNING node that
                //    nothing drives, which is the exact state this whole file is
                //    written to avoid;
                //  * then RETHROW — `runCatching` catches CancellationException
                //    like any other throwable, and swallowing it here would tell
                //    the caller the restart was attempted-and-failed while the
                //    surrounding coroutine was being cancelled. The sweep is
                //    unreachable without a real launcher (it needs a coroutine
                //    scope, a provider call and the parent's transcript), so its
                //    length — and therefore its cancellability — arrived with the
                //    wiring, not before it.
                if (error is kotlinx.coroutines.CancellationException) {
                    markRestartedChildAbnormal(
                        runtimeCoordinator,
                        sessionId.trim(),
                        childSessionId,
                        "restart cancelled before the child ran",
                    )
                    throw error
                }
                // The node was re-armed but the child never started. Put the node
                // back to the state that is actually true rather than leaving a
                // RUNNING node that nothing drives.
                markRestartedChildAbnormal(runtimeCoordinator, sessionId.trim(), childSessionId, error.message)
                return ToolExecutionResult(
                    JSONObject().put("ok", false)
                        .put("child_session_id", childSessionId)
                        .put("error", error.message ?: "restart launch failed")
                        .toString(),
                    false,
                    toolTitle = title,
                )
            }
        if (!launched.ok) {
            markRestartedChildAbnormal(runtimeCoordinator, sessionId.trim(), childSessionId, launched.output)
        }
        return if (launched.ok) {
            ToolExecutionResult(
                JSONObject().put("ok", true)
                    .put("child_session_id", launched.childSessionId)
                    .put("end_reason", launched.endReason)
                    .put("result", launched.output)
                    .toString(),
                true,
                toolTitle = title,
            )
        } else {
            ToolExecutionResult(
                JSONObject().put("ok", false)
                    .put("child_session_id", childSessionId)
                    .put("error", launched.output.ifBlank { "restarted child did not run" })
                    .toString(),
                false,
                toolTitle = title,
            )
        }
    }

    /**
     * Undo a restart whose child never actually started.
     *
     * A node is returned to RUNNING by
     * [RuntimeSessionCoordinator.restartDescendant] and only then handed to the
     * launcher. If that hand-off fails, leaving the node RUNNING is a false
     * statement about the tree: nothing is driving it, `supervise_descendants`
     * would stop reporting it as interrupted, and the parent would wait for a
     * result that cannot arrive. Marking it abnormal again restores the state that
     * is actually true, so a failed restart leaves the parent exactly where it
     * started — able to try again — instead of in a state that looks better than
     * reality.
     *
     * Best effort by design: this runs on an already-failing path, and the caller
     * reports the original failure regardless of whether the rollback succeeded.
     */
    private fun markRestartedChildAbnormal(
        runtimeCoordinator: RuntimeSessionCoordinator,
        parentSessionId: String,
        childSessionId: String,
        detail: String?,
    ) {
        runCatching {
            runtimeCoordinator.failRestartedChild(
                parentSessionId = parentSessionId,
                childSessionId = childSessionId,
                reason = "restart did not start the child" +
                    (detail?.takeIf { it.isNotBlank() }?.let { ": ${it.take(200)}" } ?: ""),
            )
        }
    }

    companion object {
        const val WEB_SEARCH = "web_search"
        const val WEB_FETCH = "web_fetch"
        const val SUPERVISE_DESCENDANTS = "supervise_descendants"

        /**
         * [T-android-conversation-id-query] The read tool behind the conversation
         * ID. Named next to the other runtime-facing tools so the ID the UI copies
         * and the ID this tool takes are visibly the same idea.
         */
        const val CONVERSATION_QUERY = "conversation_query"

        /** See [restartDescendant]; the name comes from [AgentTools] so the two cannot drift. */
        const val RESTART_DESCENDANT = AgentTools.RESTART_DESCENDANT_TOOL_NAME

        /**
         * [T-android-agent-messaging] The producer half of the parent→child
         * conversation. The name comes from [AgentTools] so the definition the
         * model reads and the branch that runs cannot drift apart.
         */
        const val MESSAGE_CHILD = AgentTools.MESSAGE_CHILD_TOOL_NAME

        /**
         * [team-peer-mesh] The producer half of teammate-to-teammate traffic. The
         * name comes from [AgentTools] so the definition the model reads and the
         * branch that runs cannot drift apart.
         *
         * Unlike [MESSAGE_CHILD] this one is CHILD-facing, so it belongs in
         * [PORTABLE_TOOL_NAMES] and deliberately NOT in
         * [MAIN_AGENT_ONLY_TOOL_NAMES]: the requirement is 「所有子代理可以相互沟通」
         * (request.md:5), i.e. the sibling direction, and the main agent already
         * reaches downwards with `message_child`. Handing the main agent both names
         * would make the two easy to confuse — they differ in who must be the
         * target, and `message_child`'s contract is "a reported failure means
         * nothing was delivered", which a mixed-up call would quietly violate.
         */
        const val MESSAGE_PEER = AgentTools.MESSAGE_PEER_TOOL_NAME

        /**
         * Tool names owned by [AgentToolExecutor] that a DELEGATED CHILD is also
         * given. ChatViewModel routes these from its `else` branch, so adding a
         * name here is the single place a name becomes shared between the chat
         * dispatcher and the delegated-child dispatcher.
         *
         * Note this set is the CHILD-facing inventory, not "every name this
         * executor answers to": [MESSAGE_CHILD] and [RESTART_DESCENDANT] are
         * dispatched here but deliberately withheld from a delegated child, so
         * they are named in their own constants and listed in
         * [MAIN_AGENT_ONLY_TOOL_NAMES] instead of being added here — adding them
         * would advertise to a child a tool it is not given
         * (`AgentToolsChildToolsTest` pins that equality).
         *
         * CORRECTNESS CONTRACT, stated here because nothing in production enforces
         * it: this set must equal the set of names [execute] answers to, MINUS
         * [MAIN_AGENT_ONLY_TOOL_NAMES]. [execute] dispatches through a `when` on
         * the name and never reads this set, so the two can silently disagree —
         * as they did when `conversation_query` was added to
         * [com.openminis.app.tools.AgentTools.makeChildAgentTools] and to
         * [execute] but not here, leaving two red tests. The guards are
         * `AgentToolsChildToolsTest` and `ChildAgentLoopTest`, and they are the
         * only thing holding this invariant: update this set whenever a tool is
         * added to the child surface.
         */
        val PORTABLE_TOOL_NAMES: Set<String> =
            setOf(WEB_SEARCH, WEB_FETCH, SUPERVISE_DESCENDANTS, CONVERSATION_QUERY, MESSAGE_PEER)

        /**
         * [T-android-child-restart] Executor-owned tools a DELEGATED CHILD must
         * never be handed.
         *
         * [RESTART_DESCENDANT] is dispatched by this executor but is
         * main-agent-only: a child has no descendants of its own to recover — its
         * subtree belongs to the parent that spawned it — and a child able to
         * re-arm siblings would let delegated work widen its own authority. So the
         * name is routed here and deliberately kept out of
         * [com.openminis.app.tools.AgentTools.makeChildAgentTools].
         *
         * [MESSAGE_CHILD] carries the same reasoning: its target is a descendant
         * of the acting session, and a child handed it could address its own
         * children directly. That traffic already has a path — a child's result
         * travels back to its parent when its run ends — so the tool adds
         * authority without adding capability, and stays absent from the child
         * surface.
         *
         * Same status as [PORTABLE_TOOL_NAMES], and worth saying plainly rather
         * than implying otherwise: this is a DECLARATION with no production
         * consumer. Nothing reads it to filter a tool list — the withholding is
         * done by simply not adding these names to
         * [com.openminis.app.tools.AgentTools.makeChildAgentTools]. Its one
         * reader is `AgentToolsRestartDescendantTest`, which asserts
         * `restart_descendant` is listed here AND absent from the child surface.
         * Treat it as the written-down half of a two-part fact; the enforced half
         * is the child tool list itself.
         */
        val MAIN_AGENT_ONLY_TOOL_NAMES: Set<String> = setOf(RESTART_DESCENDANT, MESSAGE_CHILD)
    }
}

/**
 * [T-android-child-restart] Re-runs one restarted delegated child.
 *
 * Separate from [AgentToolExecutor] because the two halves have different
 * owners: the executor owns *whether the node may be restarted* (authorization,
 * ancestry, terminal state), while the launcher owns *actually running the
 * agent* (coroutine scope, provider call, parent-transcript write-back). Only
 * the app can implement the second half.
 */
fun interface RestartedChildLauncher {
    /**
     * Run [childSessionId] again under [parentSessionId] and return its outcome.
     *
     * Must not report success unless the child really ran: [RestartedChildRun.ok]
     * is the only signal [AgentToolExecutor] forwards to the model.
     */
    suspend fun restart(
        parentSessionId: String,
        childSessionId: String,
        request: RuntimeDelegationRequest,
    ): RestartedChildRun
}

/** Outcome of one [RestartedChildLauncher.restart] call. */
data class RestartedChildRun(
    val ok: Boolean,
    val childSessionId: String,
    val output: String,
    val endReason: String = "",
)
