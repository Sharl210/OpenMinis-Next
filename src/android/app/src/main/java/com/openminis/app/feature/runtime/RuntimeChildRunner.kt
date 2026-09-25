package com.openminis.app.feature.runtime

import android.content.Context
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.ModelAttributionSnapshot
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.service.SessionActivityTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
    suspend fun execute(
        parentSessionId: String,
        request: RuntimeDelegationRequest,
        preferredEntry: ModelEntry? = null,
        parentModel: RuntimeModelSnapshot? = null,
    ): Result<RuntimeChildExecutionResult> = try {
        executeInternal(parentSessionId, request, preferredEntry, parentModel)
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
        val child = withContext(Dispatchers.IO) {
            chatRepository.createSession(
                modelId = entry.model.id,
                title = "Sub-agent: ${request.prompt.replace(Regex("\\s+"), " ").take(48)}",
            )
        }
        val childSessionId = child.id
        if (!SessionActivityTracker.beginDelegatedChild(
                parentSessionId,
                childSessionId,
                request,
                childModel,
                parentModel ?: childModel,
            )) {
            withContext(Dispatchers.IO) { chatRepository.deleteSession(childSessionId) }
            return Result.failure(IllegalStateException("Runtime tree rejected delegated child"))
        }

        val attribution = ModelAttributionSnapshot(
            modelId = entry.model.id,
            displayName = entry.model.displayName,
            providerTypeRaw = instance.providerType.name,
            providerInstanceId = instance.id,
        )
        return runChildAttempt(
            onSuccess = { result ->
                SessionActivityTracker.publishLastReply(childSessionId, result.output)
                SessionActivityTracker.finishDelegatedChild(parentSessionId, childSessionId)
            },
            onFailure = {
                SessionActivityTracker.finishDelegatedChild(parentSessionId, childSessionId, failed = true)
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
            val injection = RoundInjectionCoordinator(context).beforeModelCall(childSessionId)
            injection.prompt?.let { prompt ->
                RoundInjectionCoordinator(context).appendToHistory(responseMessages, prompt)
            }
            val response = withContext(Dispatchers.IO) {
                provider.sendMessage(
                    messages = responseMessages,
                    systemPrompt = buildSystemPrompt(request),
                    maxTokens = minOf(
                        16_384,
                        provider.effectiveMaxOutputTokens(entry.model),
                    ),
                )
            }
            withContext(Dispatchers.IO) {
                val usageJson = response.usage?.let {
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
                    textPartsJson(response.text),
                    tokenUsage = usageJson,
                    modelSnapshot = attribution,
                )
            }
            RuntimeChildExecutionResult(
                childSessionId = childSessionId,
                model = childModel,
                modelSnapshot = attribution,
                output = response.text,
            )
        }
    }

    /**
     * Keeps cancellation distinct from ordinary child failures. The caller
     * receives the original CancellationException so structured concurrency can
     * unwind; cleanup still runs exactly once before it is rethrown.
     */
    companion object {
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

    private fun buildSystemPrompt(request: RuntimeDelegationRequest): String = buildString {
        append("You are a delegated child agent. Solve only the delegated request and return a concise, actionable result.")
        if (request.note.isNotBlank()) append("\nParent note: ").append(request.note)
        if (request.mode == DelegationMode.TEAM) {
            append("\nThis child belongs to an explicitly enabled Agent Team; do not contact peers unless a durable team message is provided.")
        } else {
            append("\nUse private parent-child communication; do not assume peer access.")
        }
    }

    private fun textPartsJson(text: String): String = JSONArray()
        .put(JSONObject().put("type", "text").put("value", text))
        .toString()
}

data class RuntimeChildExecutionResult(
    val childSessionId: String,
    val model: RuntimeModelSnapshot,
    val modelSnapshot: ModelAttributionSnapshot,
    val output: String,
)
