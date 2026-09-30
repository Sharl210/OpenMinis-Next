package com.openminis.app.feature.runtime

import android.content.Context
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ModelAttributionSnapshot
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMRequestDiagnostics
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.systemPromptWithModelFragments
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.tools.AgentToolExecutor
import com.openminis.app.tools.ChatRepositoryConversationSource
import com.openminis.app.tools.AgentTools
import com.openminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Executes one delegated turn without borrowing the parent's UI history or
 * agent loop. The child still uses the app's real provider factory and Room
 * transcript, so a later child continuation can reload the same session.
 */
class RuntimeChildRunner(
    private val context: Context,
    private val chatRepository: ChatRepository,
    private val providerRepository: ProviderRepository,
) {
    /**
     * Run one delegated child turn.
     *
     * [existingChildSessionId] is the `[T-android-child-restart]` seam: it is the
     * RESTART path. Passing a session id runs that child AGAIN on the session it
     * already owns — same transcript row, same node — instead of minting a new
     * session, which is the only way "reuse the child's context" can mean
     * anything. It defaults to null, so every ordinary delegation keeps creating
     * a fresh session exactly as before.
     */
    suspend fun execute(
        parentSessionId: String,
        request: RuntimeDelegationRequest,
        preferredEntry: ModelEntry? = null,
        parentModel: RuntimeModelSnapshot? = null,
        existingChildSessionId: String? = null,
        /**
         * Called once the child session exists and its runtime node is RUNNING,
         * before the child's first model call.
         *
         * This is the seam a dispatcher needs to record something ABOUT the
         * message it is delivering — today, the reference to the context it
         * chose to share (`RuntimeChildRunner`'s caller owns the communication
         * directory). It is a callback rather than a runner-owned write because
         * the runner has no directory and should not grow one: the record
         * belongs to whoever dispatched.
         */
        onChildSessionReady: (suspend (childSessionId: String) -> Unit)? = null,
    ): Result<RuntimeChildExecutionResult> = try {
        executeInternal(parentSessionId, request, preferredEntry, parentModel, existingChildSessionId, onChildSessionReady)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }

    private suspend fun executeInternal(
        parentSessionId: String,
        request: RuntimeDelegationRequest,
        preferredEntry: ModelEntry?,
        parentModel: RuntimeModelSnapshot?,
        existingChildSessionId: String?,
        onChildSessionReady: (suspend (childSessionId: String) -> Unit)?,
    ): Result<RuntimeChildExecutionResult> {
        if (parentSessionId.isBlank()) return Result.failure(IllegalArgumentException("parent session is blank"))
        providerRepository.awaitConfigLoaded()
        val entry = resolveEntry(request, preferredEntry)
            ?: return Result.failure(IllegalStateException("No matching model for delegated request"))
        val instance = providerRepository.instance(entry.providerInstanceId)
            ?: return Result.failure(IllegalStateException("Provider instance is unavailable"))
        if (!instance.isEnabled) return Result.failure(IllegalStateException("Provider is disabled"))
        if (!providerRepository.hasAnyCredential(instance)) {
            return Result.failure(IllegalStateException("Provider has no usable credential"))
        }
        val apiKey = providerRepository.usableApiKey(instance)
            ?: return Result.failure(IllegalStateException("Provider credential is unavailable"))
        val provider = ProviderFactory.create(instance, apiKey, entry.model, context)
        val childModel = RuntimeModelSnapshot(
            provider = instance.providerType.name,
            model = entry.model.id,
            note = request.note,
            capabilities = request.capabilities,
        )
        val plan = resolveChildSessionId(
            existingChildSessionId = existingChildSessionId,
            create = {
                withContext(Dispatchers.IO) {
                    chatRepository.createSession(
                        modelId = entry.model.id,
                        title = "Sub-agent: ${request.prompt.replace(Regex("\\s+"), " ").take(48)}",
                    ).id
                }
            },
        )
        val childSessionId = plan.sessionId
        if (!beginChildRun(
                sessionCreatedByThisRun = plan.createdByThisRun,
                begin = {
                    SessionActivityTracker.beginDelegatedChild(
                        parentSessionId,
                        childSessionId,
                        request,
                        childModel,
                        parentModel ?: childModel,
                    )
                },
                deleteSession = {
                    withContext(Dispatchers.IO) { chatRepository.deleteSession(childSessionId) }
                },
            )
        ) {
            return Result.failure(IllegalStateException("Runtime tree rejected delegated child"))
        }

        // [T-android-stop-descendant-cancel] This coroutine IS the child from here
        // on, so it is the only place that can hand out a handle that actually
        // stops it. The runtime can mark the node `STOP_REQUESTED` and cannot do
        // more than that — a mark does not interrupt a provider call or a
        // transcript write — so `stop_descendant` used to return "stopped" while
        // this run carried on to its own end.
        //
        // Registered before the child's first model call (the session exists and
        // its node is RUNNING) and removed by the coroutine's own completion, so
        // the window in which a stop can reach it is exactly the window in which
        // it is running. See [SessionActivityTracker.cancelChildSessions].
        SessionActivityTracker.registerChildCanceller(childSessionId, currentCoroutineContext()[Job])

        // The child exists and is RUNNING: this is the last moment before its
        // first model call, so anything the receiver is supposed to be told
        // about the message must be recorded here rather than after the run.
        // A failing recorder must not take the delegation down with it — the
        // child's work is the point, the record is a side effect.
        if (onChildSessionReady != null) {
            runCatching { onChildSessionReady(childSessionId) }.onFailure { error ->
                AppLogger.warning(
                    TAG,
                    "child-session-ready hook failed for $childSessionId: ${error.message}",
                )
            }
        }

        val attribution = ModelAttributionSnapshot(
            modelId = entry.model.id,
            displayName = entry.model.displayName,
            providerTypeRaw = instance.providerType.name,
            providerInstanceId = instance.id,
        )
        val toolExecutor = AgentToolExecutor(
            context = context.applicationContext,
            // [T-android-conversation-id-query] Same port, same store as the main
            // agent's executor, so a child reading a conversation sees exactly
            // what the main agent would.
            conversationSource = ChatRepositoryConversationSource(chatRepository),
        )
        return runChildAttempt(
            onSuccess = { result ->
                SessionActivityTracker.publishLastReply(childSessionId, result.output)
                // [T-android-child-agent-completion] A natural end is declared ONLY
                // by the child calling the completion tool. Anything else —
                // including a perfectly successful model round-trip — is an
                // abnormal end, so the parent (and any intermediate relative of
                // the root) can see it and decide whether to restart the child.
                if (result.completedNaturally) {
                    SessionActivityTracker.finishDelegatedChild(parentSessionId, childSessionId)
                } else {
                    SessionActivityTracker.finishDelegatedChild(
                        parentSessionId,
                        childSessionId,
                        failed = true,
                        report = undeclaredEndStopReport(
                            childSessionId = childSessionId,
                            output = result.output,
                            endReason = result.endReason,
                        ),
                    )
                }
            },
            onFailure = { error ->
                SessionActivityTracker.finishDelegatedChild(
                    parentSessionId,
                    childSessionId,
                    failed = true,
                    report = providerFailureStopReport(
                        childSessionId = childSessionId,
                        error = error,
                        fallbackBody = request.prompt,
                    ),
                )
            },

        ) {
            withContext(Dispatchers.IO) {
                chatRepository.appendMessage(
                    childSessionId,
                    "user",
                    textPartsJson(request.prompt),
                    modelSnapshot = attribution,
                )
            }
            val responseMessages = mutableListOf(
                LLMMessage(
                    role = LLMMessage.Role.USER,
                    content = request.prompt,
                    contentParts = listOf(AgentContentPart.Text(request.prompt)),
                ),
            )
            val runtimeCoordinator = RuntimeSessionCoordinator.open(context.applicationContext)
            // [T-android-agent-messaging] The inbox is drained at the top of EVERY
            // model round instead of once before the loop.
            //
            // Why: claiming once made "steer" impossible to honour. A message a
            // parent sends while the child is working lands in the child's inbox
            // after the single drain has already run, so it sat there until the
            // child's run ENDED — and since one delegated run is one loop, a
            // single-round child never read it at all. The delivery lanes
            // (STEER/NOTIFY/QUEUE) and the runtime's claim lease were all working;
            // only the reader was in the wrong place. Draining per round is what
            // makes `claimNextStep`'s "next step" mean the next model call.
            //
            // The two collections below are what keep the move safe:
            //  - [consumedMessages] is every envelope this run absorbed, because
            //    each one is answered after the run (see the reply loop below).
            //  - [claimedIds] is the run-scoped de-duplication the claim lease
            //    cannot provide: the lease only holds for
            //    `MESSAGE_CLAIM_LEASE_MILLIS`, so a child that outlives it would
            //    otherwise re-claim — and re-act on, and re-answer — its own
            //    already-consumed messages.
            val consumedMessages = mutableListOf<RuntimeEnvelope>()
            val claimedIds = mutableSetOf<String>()
            val injection = RoundInjectionCoordinator(context).beforeModelCall(childSessionId)
            injection.prompt?.let { prompt ->
                RoundInjectionCoordinator(context).appendToHistory(responseMessages, prompt)
            }
            // [T-android-child-agent-completion] The birth injection carries the
            // same capability metadata the per-message reminder does, so the
            // child knows from its first token whether it is the final executor
            // or a manager. Read at run start; the reminder refreshes it.
            val systemPrompt = buildSystemPrompt(
                request,
                runtimeCoordinator.capabilitySnapshot(childSessionId),
                childSessionId,
            )
            // [T-android-model-prompt-fragments] A delegated child runs a real
            // agent loop with real tools, so it needs the per-model-family
            // BEHAVIOUR fragment: a Gemini child that narrates a tool call as
            // plain text has no consumer for it either, and its tool set is not
            // empty (subagent_complete / supervise_descendants / message_peer /
            // conversation_query / web_search / web_fetch).
            //
            // `includeCapability = false` is deliberate, not an oversight. That
            // fragment's second sentence is an instruction naming a specific
            // tool — "call `shell_execute` with ffmpeg or similar tools to
            // extract text/metadata first" — and this task's tool set does NOT
            // contain `shell_execute`: `AgentTools.makeChildAgentTools()` adds
            // only the six names above (the `shell_execute` definition is added
            // by `makeAgentTools()` alone), and a child that called it anyway
            // would get ChildAgentLoop's "Tool 'shell_execute' is not available
            // to a delegated child agent". Appending the fragment here would
            // therefore buy a false escape hatch at the cost of a wasted turn —
            // the same "a stub is worse than absence" shape the tool layer
            // already warns about. It would also describe a situation that
            // cannot arise: a child's seed message is plain text, so it never
            // receives an image/PDF/audio the hint could apply to. The child
            // already has the honest equivalent — `unavailableToolMessage` tells
            // it to state plainly what it could not do.
            //
            // Composed once per child run from the fragment-free
            // `buildSystemPrompt(...)` result. This path resolves ONE entry and
            // has no fallback, so nothing can leave this stale mid-run. Kept as
            // a separate val (rather than wrapping the call above) so the call
            // site line the capability wiring test anchors on stays
            // byte-identical.
            val childSystemPrompt = systemPromptWithModelFragments(
                systemPrompt,
                entry.model,
                includeCapability = false,
            )
            val maxTokens = minOf(16_384, provider.effectiveMaxOutputTokens(entry.model))
            val loop = ChildAgentLoop(
                executeTool = { call ->
                    executeChildTool(call, childSessionId, toolExecutor, runtimeCoordinator)
                },
            )
            val outcome = loop.run(
                seed = responseMessages,
                beforeTurn = { messages ->
                    messages += claimPendingRuntimeMessages(
                        runtimeCoordinator = runtimeCoordinator,
                        childSessionId = childSessionId,
                        alreadyClaimedIds = claimedIds,
                        into = consumedMessages,
                    )
                },
            ) { messages ->
                streamChildTurn(
                    provider,
                    entry.model,
                    messages,
                    // [T-android-agent-messaging] `systemPromptWithModelFragments`
                    // takes and returns a nullable base (null in → null out), but
                    // `systemPrompt` above is a non-null String from
                    // `buildSystemPrompt`, so the null branch is unreachable here.
                    // `streamChildTurn` requires a non-null prompt, so the fallback
                    // states that instead of forcing the whole parameter nullable:
                    // a null prompt there would have to mean something, and the
                    // only thing it could mean is "someone passed a null base".
                    childSystemPrompt ?: systemPrompt,
                    maxTokens,
                )
            }
            consumedMessages.forEach { envelope ->
                val replyDelivery = if (envelope.delivery == RuntimeDelivery.TEAM_PEER) {
                    RuntimeDelivery.TEAM_PEER
                } else {
                    RuntimeDelivery.QUEUE
                }
                runtimeCoordinator.send(
                    fromSessionId = childSessionId,
                    toSessionId = envelope.fromNodeId,
                    payload = outcome.text,
                    delivery = replyDelivery,
                )
            }
            withContext(Dispatchers.IO) {
                val usageJson = outcome.usage?.let {
                    JSONObject().apply {
                        put("inputTokens", it.inputTokens)
                        put("outputTokens", it.outputTokens)
                        put("cacheCreationTokens", it.cacheCreationInputTokens ?: 0)
                        put("cacheReadTokens", it.cacheReadInputTokens ?: 0)
                        put("latestContextTokens", it.latestContextTokens)
                    }.toString()
                }
                chatRepository.appendMessage(
                    childSessionId,
                    "assistant",
                    textPartsJson(outcome.text),
                    tokenUsage = usageJson,
                    modelSnapshot = attribution,
                )
            }
            RuntimeChildExecutionResult(
                childSessionId = childSessionId,
                model = childModel,
                modelSnapshot = attribution,
                output = outcome.text,
                endReason = outcome.endReason,
                completionSummary = outcome.completionSummary,
            )
        }
    }

    companion object {
        private const val TAG = "RuntimeChildRunner"

        /**
         * [T-android-child-restart] Which ChatSession one delegated run uses, and
         * how much of it this run is allowed to destroy.
         *
         * Two callers want two different things and both come through here, so
         * "does this run need a new session?" has exactly one answer:
         *
         *  - an ordinary delegation mints a fresh session, so `create` runs;
         *  - a RESTART re-runs an interrupted child on the session id it already
         *    had. That transcript is the context being reused, so `create` must
         *    NOT run: a second session would leave the interrupted child's rows
         *    orphaned and the runtime node pointing at a session nobody reads.
         *
         * [ChildSessionPlan.createdByThisRun] is carried out of here rather than
         * re-derived at the cleanup site, because it decides something with real
         * consequences: when the runtime tree refuses the child the runner
         * deletes the session it was working with — correct for a session this
         * run created, data loss for one it was asked to reuse.
         */
        internal suspend fun resolveChildSessionId(
            existingChildSessionId: String?,
            create: suspend () -> String,
        ): ChildSessionPlan {
            val existing = existingChildSessionId?.trim()?.takeIf { it.isNotBlank() }
            return if (existing != null) {
                ChildSessionPlan(sessionId = existing, createdByThisRun = false)
            } else {
                ChildSessionPlan(sessionId = create(), createdByThisRun = true)
            }
        }

        /**
         * [T-android-child-restart] Register the child in the runtime tree, and
         * undo the session only when undoing it is safe.
         *
         * The delete used to be unconditional on this failure path. It reads as
         * routine cleanup ("the run never happened, drop the session"), and it is
         * — for a session this run created. On the restart path the session is
         * the interrupted child's own transcript, so the same line would answer a
         * refused launch by destroying the context the restart exists to reuse.
         * The two cases are only distinguishable through
         * [ChildSessionPlan.createdByThisRun], so they are kept together: a
         * caller that cannot say which one it is does not get to delete.
         *
         * Exposed for tests: the reused-session half is unreachable from a JVM
         * test through [execute], which needs a provider stack, so the decision
         * itself has to be callable on its own to be pinned at all.
         */
        internal suspend fun beginChildRun(
            sessionCreatedByThisRun: Boolean,
            begin: suspend () -> Boolean,
            deleteSession: suspend () -> Unit,
        ): Boolean {
            if (begin()) return true
            if (sessionCreatedByThisRun) deleteSession()
            return false
        }

        /**
         * The stop report for a child whose model produced output but never declared
         * a natural end (`request.md:248`, the abnormal-stop payload).
         *
         * Exposed for tests for the same reason as the neighbours above: the call
         * site lives inside `execute`, which needs a provider stack, so the field
         * mapping — above all that `lastSentBody` carries the MODEL's own last text
         * (「模型最后一次发送的正文…最后200个字」) rather than the parent's prompt —
         * can only be pinned if the decision itself is callable on its own.
         */
        internal fun undeclaredEndStopReport(
            childSessionId: String,
            output: String,
            endReason: ChildEndReason,
        ): RuntimeStopReport = RuntimeStopReport(
            nodeId = childSessionId,
            debugInfo = "Delegated child ended without calling " +
                "'${ChildCompletionProtocol.TOOL_NAME}' " +
                "(end_reason=${endReason.name})",
            lastSentBody = output,
            completedNormally = false,
        )

        /**
         * The stop report for a child whose run FAILED, carrying whatever the failing
         * provider round-trip collected (`request.md:248`: 状态码 / 错误响应 / 响应头).
         *
         * [fallbackBody] is used only when the failure carried no request body. A
         * `CancellationException` (the user pressing stop) is not an [LLMError], so
         * every diagnostic stays absent — a user-initiated stop must never be
         * dressed up as a provider error.
         *
         * [T-android-DIAG-248] A provider failure raised inside a STREAM does not
         * arrive here as an [LLMError]. Every provider ends its streaming
         * `callbackFlow` with `cancel("Stream error", mapError(e))`, so the child —
         * which streams (see [streamChildTurn]) — sees a `CancellationException`
         * whose CAUSE is the [LLMError]. Reading only the top-level throwable
         * silently dropped the status code, the error body and the response
         * headers on every streamed failure, which is the whole point of this
         * report. The cause chain is therefore walked, using the same technique
         * the main agent loop already applies to this wrapper
         * (`ChatViewModel.unwrapFlowException`). A genuine user stop has no
         * [LLMError] anywhere in its chain, so it still reports nothing.
         */
        internal fun providerFailureStopReport(
            childSessionId: String,
            error: Throwable,
            fallbackBody: String,
        ): RuntimeStopReport {
            val diagnostics = llmErrorInCauseChain(error)?.diagnostics
            return RuntimeStopReport(
                nodeId = childSessionId,
                statusCode = diagnostics?.statusCode,
                errorResponse = diagnostics?.errorResponse,
                responseHeaders = diagnostics?.responseHeaders ?: emptyMap(),
                debugInfo = diagnostics?.debugInfo ?: error.message,
                lastSentBody = diagnostics?.requestBody ?: fallbackBody,
                completedNormally = false,
            )
        }

        /**
         * [T-android-DIAG-248] Finds the [LLMError] a failure actually means, even
         * when a streaming `cancel("Stream error", <LLMError>)` buried it as a
         * cause. See [providerFailureStopReport] for why this is needed.
         *
         * Depth-bounded so a self-referential cause chain cannot spin.
         */
        private fun llmErrorInCauseChain(error: Throwable): LLMError? {
            var current: Throwable? = error
            var depth = 0
            while (current != null && depth < MAX_CAUSE_DEPTH) {
                if (current is LLMError) return current
                current = current.cause
                depth++
            }
            return null
        }

        /** Generous enough for `cancel(msg, cause)` wrappers, bounded against cycles. */
        private const val MAX_CAUSE_DEPTH = 8

        /**
         * Keeps cancellation distinct from ordinary child failures. The caller
         * receives the original CancellationException so structured concurrency can
         * unwind; cleanup still runs exactly once before it is rethrown.
         */
        internal suspend fun <T> runChildAttempt(
            onSuccess: suspend (T) -> Unit = {},
            onFailure: suspend (Throwable) -> Unit = {},
            block: suspend () -> T,
        ): Result<T> = try {
            val value = block()
            onSuccess(value)
            Result.success(value)
        } catch (error: CancellationException) {
            try {
                onFailure(error)
            } catch (_: Throwable) {
                // Preserve the original cancellation even if cleanup fails.
            }
            throw error
        } catch (error: Throwable) {
            onFailure(error)
            Result.failure(error)
        }

        /**
         * [T-android-agent-messaging] Drain the child's three inbox lanes and turn
         * what came out into the messages the child's model will actually read.
         *
         * One reader per lane, in the same order the run body used before this was
         * extracted (NOTIFY, then STEER/TEAM_PEER, then QUEUE), so a message still
         * arrives under its own heading — `claimNextStep` matches STEER and
         * TEAM_PEER, `claimNextNotification` matches NOTIFY and `claimNextTurn`
         * matches QUEUE (see `SessionTreeRuntime`). At most one envelope per lane
         * per call, matching the previous behaviour.
         *
         * Extracted from the run body so the delivery contract is a plain JVM
         * function that a unit test can drive against a real tree, instead of a
         * block only reachable behind a live provider. The run body supplies the
         * two collections because they outlive one drain:
         *
         * @param alreadyClaimedIds every envelope id this run has absorbed.
         *   `SessionTreeRuntime.claim` stamps a claim lease
         *   ([SessionTreeRuntime.MESSAGE_CLAIM_LEASE_MILLIS]) and refuses to hand
         *   the same envelope to the same claimant again while it holds — but the
         *   lease EXPIRES, so a run longer than the lease would re-claim its own
         *   message on a later round. Returning only unseen ids is the run-scoped
         *   guarantee the lease cannot give. Re-claiming is harmless here: the
         *   claim is refreshed, the envelope is simply not re-injected.
         * @param into the run's list of everything it absorbed. Each envelope on
         *   it is answered once after the run (see the reply loop in the run
         *   body), which is why re-claimed envelopes must not be re-added.
         */
        internal fun claimPendingRuntimeMessages(
            runtimeCoordinator: RuntimeSessionCoordinator,
            childSessionId: String,
            alreadyClaimedIds: MutableSet<String>,
            into: MutableList<RuntimeEnvelope>,
        ): List<LLMMessage> = listOfNotNull(
            runtimeCoordinator.claimNextNotification(childSessionId),
            runtimeCoordinator.claimNextStep(childSessionId),
            runtimeCoordinator.claimNextTurn(childSessionId),
        ).filter { envelope -> alreadyClaimedIds.add(envelope.id) }
            .map { envelope ->
                into += envelope
                // The capability snapshot is re-read per message, not captured
                // once: `self_can_delegate` moves as children are spawned and
                // settle, so the block must describe the tree as it is when the
                // message is read.
                runtimeMessageFor(envelope, runtimeCoordinator.capabilitySnapshot(childSessionId))
            }

        /**
         * [T-android-agent-messaging] One inbox envelope as the child's model sees
         * it.
         *
         * The prefix names the delivery class, because the child has to be able to
         * tell a steering instruction from a queued turn from a bare notice — the
         * three arrive in one conversation and mean different things.
         *
         * The reminder is appended for every PARENT-originated message and skipped
         * for TEAM_PEER traffic (peers are siblings, not the child's principal);
         * that decision lives in `ChildCompletionProtocol.reminderFor`, so the
         * prompt and the delivery rule cannot drift.
         */
        internal fun runtimeMessageFor(
            envelope: RuntimeEnvelope,
            capabilities: AgentCapabilitySnapshot?,
        ): LLMMessage {
            val base = "[runtime-message:${envelope.delivery}] ${envelope.payload}"
            val reminder = ChildCompletionProtocol.reminderFor(envelope.delivery, capabilities)
            val message = if (reminder == null) base else "$base\n$reminder"
            return LLMMessage(
                role = LLMMessage.Role.USER,
                content = message,
                contentParts = listOf(AgentContentPart.Text(message)),
            )
        }
    }

    private fun resolveEntry(
        request: RuntimeDelegationRequest,
        preferredEntry: ModelEntry?,
    ): ModelEntry? {
        val entries = providerRepository.allVisibleEntries()
        val modelNeed = request.model?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        val providerNeed = request.provider?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        fun ModelEntry.matchesModel(): Boolean = modelNeed == null ||
            model.id.lowercase() == modelNeed || model.displayName.lowercase() == modelNeed
        fun ModelEntry.matchesProvider(): Boolean {
            if (providerNeed == null) return true
            val instance = providerRepository.instance(providerInstanceId) ?: return false
            return providerNeed == instance.id.lowercase() ||
                providerNeed == instance.label.trim().lowercase() ||
                providerNeed == instance.providerType.name.lowercase() ||
                providerNeed == instance.providerType.displayName.lowercase() ||
                providerNeed == model.provider.lowercase()
        }
        if (modelNeed == null && providerNeed == null) {
            preferredEntry?.let { preferred ->
                entries.firstOrNull { it.id == preferred.id }?.let { return it }
            }
        }
        return entries.firstOrNull { it.matchesModel() && it.matchesProvider() }
            ?: if (modelNeed == null && providerNeed == null) {
                preferredEntry?.let { preferred -> entries.firstOrNull { it.id == preferred.id } }
                    ?: entries.firstOrNull()
            } else {
                null
            }
    }

    /**
     * [T-android-child-agent-completion] One streamed child turn.
     *
     * Streaming (not `sendMessage`) is load-bearing: [LLMResponse] has no field
     * for tool calls, and every provider's `sendMessageClamped` concatenates
     * text/usage only — so a non-streaming child could never surface a tool
     * call, and a child handed the completion tool would be unable to use it.
     * `streamMessage` is the same channel the main agent loop drives.
     */
    private suspend fun streamChildTurn(
        provider: LLMProvider,
        model: LLMModel,
        messages: List<LLMMessage>,
        systemPrompt: String,
        maxTokens: Int,
    ): ChildTurnResult = withContext(Dispatchers.IO) {
        val text = StringBuilder()
        val toolCalls = mutableListOf<ChildToolCall>()
        var usage: LLMUsage? = null
        var stopReason: String? = null
        provider.streamMessage(
            messages = messages,
            systemPrompt = systemPrompt,
            maxTokens = maxTokens,
            tools = AgentTools.makeChildAgentTools(),
        ).collect { chunk ->
            when (chunk) {
                is LLMStreamChunk.Text -> text.append(chunk.text)
                is LLMStreamChunk.ToolCallComplete ->
                    toolCalls.add(ChildToolCall(chunk.id, chunk.name, chunk.args))
                is LLMStreamChunk.Usage -> usage = chunk.usage
                is LLMStreamChunk.Finished -> stopReason = chunk.stopReason
                else -> Unit
            }
        }
        ChildTurnResult(
            text = text.toString(),
            toolCalls = toolCalls,
            usage = usage,
            stopReason = stopReason,
        )
    }

    /**
     * Executes one child tool call.
     *
     * The completion tool never reaches here — [ChildAgentLoop] intercepts it,
     * because its "effect" is the protocol signal itself, not a side effect.
     * Every other name either runs through [AgentToolExecutor] or comes back as
     * an explicit "not available to a delegated child" error, so the model can
     * finish with what it has instead of silently pretending it ran something.
     */
    private suspend fun executeChildTool(
        call: ChildToolCall,
        childSessionId: String,
        executor: AgentToolExecutor,
        runtimeCoordinator: RuntimeSessionCoordinator,
    ): ChildToolOutcome {
        val result = runCatching {
            executor.execute(call.name, call.args.toString(), childSessionId, runtimeCoordinator)
        }.getOrElse { error ->
            ToolExecutionResult(
                ChildAgentLoop.unavailableToolMessage(call.name) +
                    " (execution failed: ${error.message})",
                false,
            )
        }
        return when {
            result != null -> ChildToolOutcome(result.output, isError = !result.success)
            else -> ChildToolOutcome(
                ChildAgentLoop.unavailableToolMessage(call.name),
                isError = true,
            )
        }
    }

    private fun buildSystemPrompt(
        request: RuntimeDelegationRequest,
        capabilities: AgentCapabilitySnapshot?,
        childSessionId: String,
    ): String = buildString {
        append("You are a delegated child agent. Solve only the delegated request and return a concise, actionable result.")
        if (request.note.isNotBlank()) append("\nParent note: ").append(request.note)
        if (request.mode == DelegationMode.TEAM) {
            append("\nThis child belongs to an explicitly enabled Agent Team; do not contact peers unless a durable team message is provided.")
        } else {
            append("\nUse private parent-child communication; do not assume peer access.")
        }
        // [T-android-child-agent-completion] Birth injection. Appended here and
        // ONLY here: this builder is the delegated-child system prompt, so the
        // main agent (which has its own prompt path) never receives the
        // completion protocol or the completion tool.
        append("\n\n").append(ChildCompletionProtocol.systemInstruction(capabilities))
        // [T-android-conversation-id-query] request.md:136 asks for 「前置性的注入」
        // that teaches the model what a `minis-conv-` ID is and what it can be
        // used for. Injected here — with THIS child's own id — so the model meets
        // the concept, and its own ID, before it ever sees one in a tool result.
        append("\n\n").append(ConversationIdProtocol.systemInstruction(childSessionId))
    }

    private fun textPartsJson(text: String): String = JSONArray()
        .put(JSONObject().put("type", "text").put("value", text))
        .toString()
}

/**
 * [T-android-child-restart] The session one delegated run works in, plus whether
 * this run is the one that created it.
 *
 * [createdByThisRun] is not bookkeeping: it is the difference between cleaning up
 * after a run that never started and deleting an interrupted child's history.
 * See `RuntimeChildRunner.resolveChildSessionId` / `beginChildRun`.
 */
internal data class ChildSessionPlan(
    val sessionId: String,
    val createdByThisRun: Boolean,
)

/**
 * Result of one delegated child run.
 *
 * [endReason] is the authoritative "how did this end" signal: only
 * [ChildEndReason.COMPLETION_TOOL] is a natural completion, and
 * [completedNaturally] is derived from it rather than from the transport
 * succeeding — a child whose model call returned 200 but never declared
 * completion is not a completed child.
 */
data class RuntimeChildExecutionResult(
    val childSessionId: String,
    val model: RuntimeModelSnapshot,
    val modelSnapshot: ModelAttributionSnapshot,
    val output: String,
    val endReason: ChildEndReason,
    val completionSummary: String? = null,
) {
    val completedNaturally: Boolean get() = endReason == ChildEndReason.COMPLETION_TOOL
}
