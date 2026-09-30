package com.openminis.app.ui.chat

// [T-android-split-chat] Chat data models extracted verbatim from
// ChatViewModel.kt: StreamingDelta, ChatMessage, QueuedPrompt,
// ToolBlockStatus, SlashCommand, AssistantBlock. Full import block copied
// from ChatViewModel.kt (unused=warnings). Visibility unchanged (public).

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.lazy.LazyListState
import com.openminis.app.agent.Level
import com.openminis.app.agent.ToolLoopDetector
import com.openminis.app.browser.BrowserActionInput
import com.openminis.app.browser.BrowserTabPool
import com.openminis.app.data.db.MessageEntity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Extension
import com.openminis.app.data.BPETokenizer
import com.openminis.app.data.ContextOffload
import com.openminis.app.data.ContextPolicy
import com.openminis.app.logging.AppLogger
import com.openminis.app.data.FileMentionIndex
import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.MessageProvenance
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.R
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.provider.ImageBudget
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.terminal.MinisOpenUrlBroker
import com.openminis.app.terminal.MinisUrlMarker
import com.openminis.app.tools.AgentTools
import com.openminis.app.tools.FileEditTool
import com.openminis.app.tools.FileReadTool
import com.openminis.app.tools.FileWriteTool
import com.openminis.app.tools.MemoryTools
import com.openminis.app.tools.ReadImageTool
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.service.SessionConcurrencyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.json.JSONObject

/**
 * Per-message streaming snapshot — the high-frequency fields that
 * [ChatViewModel.updateAssistantMessage] used to write straight into
 * [ChatMessage] (and re-publish via the `messages` StateFlow on every
 * token). Splitting them off into a side-channel
 * ([ChatViewModel.streamingById]) keeps the `messages` reference stable
 * during a turn, so the ChatScreen top-level composable's reads
 * (`messages.any/.associate/.isNotEmpty/.lastOrNull`) don't recompose on
 * every token — only on message-level structural changes (new message,
 * delete, retry, etc.).
 *
 * Renderers that care about streaming content subscribe per-item; the
 * effective render value is `streamingById[id]?.content ?: message.content`
 * (and analogously for the other fields). At the end of a streaming turn
 * the side-channel is drained back into the canonical message and the
 * map entry is removed.
 */
data class StreamingDelta(
    val content: String,
    val toolBlocks: List<AssistantBlock>,
    val isAwaitingModelResponse: Boolean,
)

/**
 * Immutable attribution captured for one assistant message.
 *
 * The fields mirror the nullable attribution columns on [MessageEntity].
 * Null means that the row predates message attribution (or that the live
 * request had no resolved model), so the UI must retain the legacy Soul header.
 */
data class AssistantHeaderSnapshot(
    val modelDisplayName: String? = null,
    val providerType: String? = null,
    val providerInstanceId: String? = null,
    val thinkingLevel: ThinkingLevel? = null,
) {
    /** True when this message has enough model identity to replace the legacy header. */
    val hasModelIdentity: Boolean
        get() = !modelDisplayName.isNullOrBlank() || !providerType.isNullOrBlank()
}

data class ChatMessage(
    val id: String,
    val role: String,
    val content: String,
    val isStreaming: Boolean = false,
    // True while waiting on the network for the next model response chunk —
    // either before the first chunk of a turn, or in the gap after tool results
    // are sent back and before the next turn starts streaming. Cleared the moment
    // the next content chunk (text / thinking / tool_use) arrives.
    val isAwaitingModelResponse: Boolean = false,
    val imageUris: List<Uri> = emptyList(),
    val attachmentNames: List<String> = emptyList(),
    // T150: file:// URIs of non-image attachments that the user bubble's
    // file chip taps into FilePreviewScreen. Aligned with the non-image
    // suffix of `attachmentNames` (after the imageUris-many image entries).
    val attachmentUris: List<Uri> = emptyList(),
    val toolBlocks: List<AssistantBlock> = emptyList(),
    // T300: thinking-level snapshot at the moment this assistant message
    // was created. Used by the chat UI to suppress the "Deep Thinking"
    // collapsible when the user's per-session toggle is OFF (forced-
    // reasoning models on OpenRouter still emit reasoning_content even
    // though the wire request omits the reasoning field — see the T300
    // analysis report for why we hide rather than silence). In-memory
    // only; assistant messages restored from DB get null and fall back
    // to the chat's current thinking level at render time.
    val thinkingLevel: com.openminis.app.data.model.ThinkingLevel? = null,
    // [T-android-message-model-title] Attribution captured at message write
    // time. These remain nullable so pre-attribution rows keep the legacy Soul
    // header instead of showing a guessed current model.
    val modelDisplayName: String? = null,
    val providerType: String? = null,
    val providerInstanceId: String? = null,
    val error: String? = null,
    // Queued user prompt awaiting injection into the running agent loop.
    // Mirrors iOS ChatMessage.isQueued / queuedPromptId.
    val isQueued: Boolean = false,
    val queuedPromptId: String? = null,
    val queuedDelivery: QueuedPromptDelivery? = null,
    // Set to true when this message belongs to a range that has been folded
    // into a compact summary marker. Mirrors iOS ChatMessage.isCompactedHistory:
    // the message stays in the UI, but renders at reduced opacity so the user
    // can still scroll/read it while seeing it's no longer in the model's
    // active context window.
    val isCompactedHistory: Boolean = false,
    // Explicit UI provenance is independent from the API role. Rows written by
    // older builds have no marker and therefore remain UNKNOWN.
    val provenance: MessageProvenance = MessageProvenance.UNKNOWN,
    // [T-android-r37-system-row] Verbatim injected text of a row the app
    // synthesised on the API's `user` role (see [isAppSystemRow]), carried for
    // DISPLAY ONLY.
    //
    // `content` deliberately stays stripped, and that is load-bearing: a
    // reminder-only row persists no human text, and `content` is what
    // [countsAsHumanTurn], title generation and the retry / delete / rewind
    // anchors read. Putting the raw `<system-reminder>` there would make
    // harness plumbing count as a human turn and shift every cut-off by one.
    // The raw text rides here instead, so the neutral system row can show the
    // injection verbatim behind a tap while the model-facing bytes, the DB row
    // and the turn counting all stay exactly as they are.
    val injectedSystemText: String? = null,
    // Every DB row id this UI message represents — usually a single id,
    // but consecutive assistant turns get merged in `loadSessionMessages`
    // and the merged bubble carries every source row's id here. Phase
    // 2.5 boundary resolution looks up `lastCompactedMessageId` /
    // `firstKeptMessageId` against this set so a merged-into-tail row
    // still locates the right divider position. Mirrors iOS
    // ChatMessage.sourceSortOrder, which serves the same UI↔raw mapping
    // role (AIChatViewModel.swift:3411, 3421).
    val sourceDbIds: List<String> = emptyList(),
) {
    /** Snapshot used by the assistant header, or null for legacy rows. */
    val assistantHeaderSnapshot: AssistantHeaderSnapshot?
        get() {
            val hasAnyAttribution = !modelDisplayName.isNullOrBlank() ||
                !providerType.isNullOrBlank() ||
                providerInstanceId != null ||
                thinkingLevel != null
            return if (hasAnyAttribution) {
                AssistantHeaderSnapshot(
                    modelDisplayName = modelDisplayName,
                    providerType = providerType,
                    providerInstanceId = providerInstanceId,
                    thinkingLevel = thinkingLevel,
                )
            } else null
        }

    /**
     * [T-android-message-model-title] True only when a model identity is
     * available. Thinking-only metadata must not replace the legacy header.
     */
    val hasAssistantModelAttribution: Boolean
        get() = assistantHeaderSnapshot?.hasModelIdentity == true

    /**
     * [T-bridge-message-ui-leak-android] True when this UI message is the
     * internal role-alternation bridge that `injectQueuedPromptsAsNewTurn`
     * inserts into `agentHistory` (see ChatViewModel). It is an internal
     * LLM-facing message and must NEVER render as a chat bubble.
     *
     * On Android the bridge goes into `agentHistory` ONLY (never persisted
     * to the DB, never appended to `_messages`), so it cannot currently
     * leak through any UI path — unlike iOS, where a persisted bridge row
     * leaked after the 2026-07-23 wording change. This property exists as a
     * belt-and-suspenders filter (applied at the `uiMessages` sink) so a
     * future refactor that accidentally routes the bridge into `_messages`
     * still can't surface it. Mirrors iOS `ChatMessage.isInternalBridge`.
     */
    val isInternalBridge: Boolean
        get() = role == "assistant" && isInternalBridgeText(content)

    companion object {
        /** Current bridge wording — MUST stay byte-identical to the string
         *  written in ChatViewModel.injectQueuedPromptsAsNewTurn. */
        private const val INTERNAL_BRIDGE_TEXT =
            "(Interrupted mid-task by a new user message. Decide based on the new " +
                "message and overall context whether the prior task should continue — do " +
                "not forget or abandon it unless the user explicitly says to stop, or the " +
                "new message makes clear it is no longer needed.)"

        /**
         * Every bridge text this app has ever generated. Matching only the
         * current constant would miss a message produced by an OLDER build
         * carrying the previous wording — exactly the leak class iOS hit after
         * its 2026-07-23 wording change (d2e111e9). Match against the full set
         * so old and new bridges are both recognized. Mirrors iOS
         * `RawMessage.internalBridgeTexts`.
         */
        private val INTERNAL_BRIDGE_TEXTS = listOf(
            INTERNAL_BRIDGE_TEXT,
            // Pre-2026-07-23 wording.
            "(Interrupted mid-task to handle your new message. Will return to the prior task after.)",
        )

        /** True when [text] is any known internal-bridge string. Trims
         *  leading/trailing whitespace to tolerate encoding drift from any
         *  round-trip, matching iOS `RawMessage.isInternalBridgeText`. */
        fun isInternalBridgeText(text: String): Boolean {
            val trimmed = text.trim()
            return INTERNAL_BRIDGE_TEXTS.any { trimmed == it }
        }
    }
}

/** Only a explicitly marked human-authored user row may drive turn navigation. */
internal fun ChatMessage.isManualHumanUser(): Boolean =
    role == "user" && provenance == MessageProvenance.MANUAL_USER

/**
 * [T-android-r37-system-row] Does this row belong to the app layer's SYSTEM
 * class while riding the API's `user` role?
 *
 * R37 splits the transcript into three classes — system / assistant / human —
 * and this is the class test for the first one on the data side. The wire
 * protocol has only two roles, so the app synthesises some rows on the USER
 * side: the `<system-reminder>` re-entries written by `resume()` and by the
 * delegated-child abnormal-end report (TOOL_INJECTION), and — defensively —
 * anything stamped SYSTEM_CARD. None of them is the human's own words or the
 * assistant's reply, so the transcript must draw them as a neutral system row
 * and never as a user bubble.
 *
 * Deliberately an ALLOW-LIST of system provenances, never
 * `provenance != MANUAL_USER`: every row written before provenance existed
 * carries UNKNOWN and those rows ARE the human's own text, so a negative test
 * would silently move every legacy human turn into the system class.
 *
 * Display only. The row still IS a `user` role message, is still persisted
 * unchanged and is still sent to the provider verbatim.
 */
internal fun ChatMessage.isAppSystemRow(): Boolean =
    role == "user" && provenance in APP_SYSTEM_PROVENANCES

/**
 * [T-android-r37-system-row] The provenances that mean "the app wrote this row
 * and no human turn happened" for DISPLAY classification.
 *
 * One set, two readers: [isAppSystemRow] (which sees a whole ChatMessage) and the
 * DB→UI mapping in ChatViewModel (which only has `role` + provenance in hand).
 * Keeping them on one value is what stops the transcript filter and the row
 * builder from disagreeing about which rows are system rows.
 */
internal val APP_SYSTEM_PROVENANCES = setOf(
    MessageProvenance.TOOL_INJECTION,
    MessageProvenance.SYSTEM_CARD,
)

/**
 * [T-android-r37-system-row] Will this message be drawn as the human's bubble?
 *
 * Used by [buildFlatChatItems] to decide the "back-to-back user sends" gap: the
 * gap exists because two consecutive BUBBLES have no assistant header between
 * them, so a neutral system row in between means there is nothing to separate
 * and the extra top padding must not be added.
 */
internal fun ChatMessage.rendersAsUserBubble(): Boolean =
    role == "user" && !isAppSystemRow()

/**
 * [T-android-r37-system-row] Verbatim TEXT parts of a persisted row, read
 * straight out of its `parts_json` — no stripping, no rewriting.
 *
 * The neutral system row shows the injected text exactly as the model received
 * it (that identity is the point of the row: it is a view onto a wire message,
 * not a paraphrase), while the transcript's own `content` for that row stays
 * stripped for turn counting. Returns null when the row carries no non-blank
 * text part — a `toolResult`-only row has nothing to display and is still
 * dropped upstream.
 *
 * Pure, so the DB→UI extraction is testable without Room; malformed JSON answers
 * null (the caller then falls back to the ordinary drop rule) rather than
 * throwing.
 */
internal fun verbatimTextPartsOf(partsJson: String): String? {
    val array = runCatching { org.json.JSONArray(partsJson) }.getOrNull() ?: return null
    val joined = StringBuilder()
    for (index in 0 until array.length()) {
        val part = array.optJSONObject(index) ?: continue
        if (part.optString("type") != "text") continue
        val value = part.optString("value", "")
        if (value.isBlank()) continue
        if (joined.isNotEmpty()) joined.append('\n')
        joined.append(value)
    }
    return joined.toString().takeIf { it.isNotBlank() }
}

/**
 * [T-android-compact-boundary-dbid-resolution] Does this rendered bubble stand
 * for the given persisted DB row?
 *
 * A bubble is NOT one-to-one with a DB row in two directions:
 *  - consecutive assistant rows MERGE into one bubble, whose `id` is only the
 *    LAST row's id — every earlier folded row lives on in `sourceDbIds`. The
 *    compact anchor is frequently one of those folded rows, so testing `id`
 *    alone misses it.
 *  - system rows (dividers, notices) have synthetic ids that match no DB row.
 *
 * Every consumer of a marker's `lastCompactedMessageId` / `firstKeptMessageId`
 * must ask the question through this one function; the compact-all graying pass
 * used to test `id` alone and, when the anchor was a folded row, never found its
 * cutoff and grayed the ENTIRE transcript — including the tail that was still
 * live in the model's context.
 */
internal fun bubbleRepresentsDbId(
    bubbleId: String,
    sourceDbIds: List<String>,
    dbId: String,
): Boolean = bubbleId == dbId || dbId in sourceDbIds

/** UI-side projection of [bubbleRepresentsDbId] for a rendered bubble. */
internal fun ChatMessage.representsDbId(dbId: String): Boolean =
    bubbleRepresentsDbId(id, sourceDbIds, dbId)

/**
 * [T-android-human-turn-count-parity] The pure half of the "which human turn is
 * this?" rule used by the retry / delete / edit cut-offs.
 *
 * It MUST agree with [MessagePartsCodec.hasHumanTurnContent]: an index counted
 * over the UI list is paired with a `sort_order` found by counting DB rows, so
 * any disagreement makes the cut-off miss and silently skips
 * `deleteMessagesAfter` (that is the bug this pairs with).
 *
 * `attachmentCount` is the number of attachments on the bubble (images plus
 * files). It stands in for the persisted `mediaRef` part, and it is a plain
 * count rather than a `Uri` list so the rule stays testable without Android.
 *
 * Deliberately NOT provenance-based: rows written before the marker existed
 * have no provenance at all, so a marker test would drop every legacy turn.
 */
internal fun isHumanTurnShape(role: String, text: String, attachmentCount: Int): Boolean =
    role == "user" && (text.isNotBlank() || attachmentCount > 0)

/**
 * UI-side projection of [isHumanTurnShape] for a rendered bubble. Keep the two
 * in step — `HumanTurnCountParityTest` pins the cases that matter.
 */
internal fun ChatMessage.countsAsHumanTurn(): Boolean =
    isHumanTurnShape(role, content, imageUris.size + attachmentNames.size)

/** A user prompt queued while the agent loop is still running. Mirrors iOS QueuedPrompt. */
data class QueuedPrompt(
    val id: String,
    val text: String,
    val attachments: List<InputAttachment> = emptyList(),
    val delivery: QueuedPromptDelivery = QueuedPromptDelivery.QUEUE,
)

/**
 * Execution status of an assistant tool block. Mirrors iOS `ToolBlockStatus`
 * plus two Android-only granularity states for UI animation:
 *
 *  - `STREAMING`: partial tool-input JSON is still arriving (iOS `.streaming(bytes:)`).
 *  - `PENDING`: tool JSON is complete, waiting for the execution dispatcher
 *    to start. Brief window between ToolCallComplete and `executeTool()`
 *    invocation — visible when the agent pipelines multiple tool calls.
 *  - `RUNNING`: tool body is executing (iOS `.running`).
 *  - `SUCCESS`: tool returned without error (iOS `.success`).
 *  - `FAILED`: tool returned an error (iOS `.failed(message:)`).
 *  - `CANCELLED`: user cancelled mid-execution (iOS `.cancelled`).
 *  - `TIMEOUT`: wrapper timeout hit before the tool returned — distinct from
 *    FAILED so the UI can render a clock icon instead of a generic error.
 */
enum class ToolBlockStatus {
    STREAMING, PENDING, RUNNING, SUCCESS, FAILED, CANCELLED, TIMEOUT
}

/** Slash command descriptor shown in the "/" popup. Mirrors iOS SlashCommand. */
data class SlashCommand(
    val id: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val title: String,
    val subtitle: String,
    /**
     * [T-skill-slash a88ea8f9] True when this row was synthesized from an
     * installed Skill (vs. a built-in command). Skill rows fill the
     * composer with `/<name>` on tap and dismiss the menu — the actual
     * SKILL.md reading + behavior happens model-side when the message is
     * sent (skills already get injected into the system prompt via
     * SkillRepository.enabledForSession). Default false so existing
     * built-in rows construct unchanged.
     */
    val isSkill: Boolean = false,
    /**
     * [T-mcp-integration-android] True when this row was synthesized from a
     * configured MCP server (vs. a built-in command or a skill). Distinct from
     * [isSkill] so the picker can tag MCP rows with [mcp] + a wrench icon and
     * skills with ⚡. Tapping fills the composer with the server name; the
     * actual discovery/call happens model-side via minis-mcp-cli.
     */
    val isMcp: Boolean = false,
    /**
     * [T-android-slash-goal-fork] Exact text tapping this row puts in the
     * composer, or null when the row EXECUTES instead.
     *
     * Separates two questions that [isSkill] used to answer at once: where a row
     * CAME FROM, and what tapping it DOES. `/goal` and `/fork` need the
     * fill behaviour ("/goal " then the user types the objective) while being
     * built-in commands rather than skills — so reusing [isSkill] for them would
     * have dragged them into the skill group in the picker and mislabelled their
     * origin. One field, one meaning.
     */
    val fillText: String? = null,
)

data class AssistantBlock(
    val id: String,
    val kind: String,       // "text", "tool_use", "thinking", "info"
    val content: String = "",
    val toolStatus: ToolBlockStatus? = null,
    val toolTitle: String = "",
    val toolName: String = "",
    val toolArgs: String = "",   // raw JSON args for UI rendering (command, path, old_string, etc.)
    val durationMs: Long = 0L,
    val startTimeMs: Long = 0L,
    /** Page URL at time of browser action execution (mirrors iOS AssistantBlock.browserURL). */
    val browserURL: String? = null,
    /** Local file path to screenshot JPEG (mirrors iOS AssistantBlock.imageFilePath). */
    val imageFilePath: String? = null,
    /**
     * [T-android-gemini3-thoughtsig / #179] Gemini 3.x thought signature for a
     * tool_use block. Carried here so [buildTurnParts] (the persistence path,
     * which rebuilds ToolUse parts from blocks) can round-trip it to the DB.
     * Null for non-Gemini providers and thinking-off Gemini calls.
     */
    val thoughtSignature: String? = null,
) {
    val isText: Boolean get() = kind == "text"
}
