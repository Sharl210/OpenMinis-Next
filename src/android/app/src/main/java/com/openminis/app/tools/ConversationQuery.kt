package com.openminis.app.tools

import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.feature.runtime.ConversationIdProtocol
import com.openminis.app.feature.runtime.RuntimeCommunicationCursor
import com.openminis.app.feature.runtime.RuntimeCommunicationDirectory
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-android-conversation-id-query] Reading another conversation's content, by
 * its conversation ID.
 *
 * Requirement (request.md:136, verbatim, in the order it makes its points):
 *
 *  - 「我希望每一个对话，不管是主代理还是子代理还是相对主代理，都有一个能力，就是拿到
 *    这一个ID，他能够向运行时核心去申请查看对应的对话内容」
 *  - 「它是一组能力它包含了搜索或者查询筛选呀之类的这种就是所有只读的操作」
 *  - 「如果一次性把所有的对话内容都塞回给这个工具调用里面那么模型可能会一下把上下文
 *    搞炸掉，所以你需要做一个**截流**…你要让它进行一个**渐进式的披露**，就是模型自己
 *    去一次一次去搜索」
 *  - 「假如说我在另一个代理或者另一个绘画…我想让另一个代理，他知道…如果这两个并没有
 *    血缘关系…我只能去导出对话，然后再导入给另一个代理，这种显然是信息损失极大的」
 *  - 「这个是不需要有健全的[鉴权]…而且哈希id本身也不可能通过状况[不可能被猜到]」
 *
 * ## Which store is read, and why
 *
 * `ChatRepository` — the Room `AppDatabase` the running app actually writes to
 * (every user send, every assistant turn, every child-agent transcript row goes
 * through `ChatRepository.appendMessage`). This is deliberate:
 *
 *  - `SessionTreeRuntime.appendTranscript` is defined but has **zero callers**
 *    anywhere in the module, so the runtime tree's own transcript holds nothing.
 *    Reading it would answer "what did this conversation say" with an empty list.
 *  - The `Next*` data layer (`NextAppGraph`, `NextDatabaseProvider`,
 *    `NextBackupImporter`, …) has **zero production callers** as well. Building
 *    this on it would add another well-formed implementation of a feature no
 *    running code path can reach — the exact defect class this repo keeps
 *    finding.
 *
 * So the read goes to the store the user's messages are actually in.
 *
 * ## No new data access, and no new budget
 *
 * The two reads reuse the paging and search that already exist and are already
 * exercised by the sessions CLI: [ChatRepository.loadMessagePage] (which already
 * strips `<system-reminder>` wrappers, skips blank rows and reports per-message
 * truncation) and [ChatRepository.searchMessages] (whose LIKE + snippet
 * behaviour is therefore shared rather than re-implemented).
 *
 * The page/budget constants and the cursor type are R43's, not new ones:
 * [RuntimeCommunicationDirectory.DEFAULT_PAGE_LIMIT],
 * [RuntimeCommunicationDirectory.MAX_PAGE_LIMIT],
 * [RuntimeCommunicationDirectory.DEFAULT_RESULT_BUDGET_CHARS],
 * [RuntimeCommunicationDirectory.MAX_RESULT_BUDGET_CHARS] and
 * [RuntimeCommunicationCursor]. One notion of "how big is a page" for both read
 * tools.
 *
 * ## Read-only, and not authorized
 *
 * Matching the requirement's explicit 「不需要有鉴权」: there is no permission
 * check, because the ID is an unguessable UUID and the operation is a read. What
 * IS enforced is the other half — [ConversationQueryPolicy] exposes no mutation
 * of any kind, and every result is bounded by an explicit character budget so a
 * single call cannot flood the caller's context.
 */
data class ConversationMessage(
    val messageId: String,
    val role: String,
    val createdAtMillis: Long,
    val text: String,
    val truncated: Boolean = false,
)

/**
 * The narrow read port. An interface so the tool's paging/budget behaviour is
 * testable on the JVM without Room (see [ConversationQueryPolicy]), while
 * production binds [ChatRepositoryConversationSource].
 */
interface ConversationTranscriptSource {
    suspend fun count(sessionId: String): Int

    suspend fun page(
        sessionId: String,
        offset: Int,
        limit: Int,
        maxChars: Int,
    ): List<ConversationMessage>

    suspend fun search(sessionId: String, keyword: String, limit: Int): List<ConversationMessage>
}

/** Production binding: the live chat store, through its existing readers. */
class ChatRepositoryConversationSource(
    private val chatRepository: ChatRepository,
) : ConversationTranscriptSource {

    override suspend fun count(sessionId: String): Int = chatRepository.messageCount(sessionId)

    override suspend fun page(
        sessionId: String,
        offset: Int,
        limit: Int,
        maxChars: Int,
    ): List<ConversationMessage> =
        chatRepository.loadMessagePage(sessionId, offset, limit, maxChars)
            .map {
                ConversationMessage(
                    messageId = it.messageId,
                    role = it.role,
                    createdAtMillis = it.createdAt,
                    text = it.text,
                    truncated = it.truncated,
                )
            }

    override suspend fun search(
        sessionId: String,
        keyword: String,
        limit: Int,
    ): List<ConversationMessage> =
        chatRepository.searchMessages(
            sessionIds = listOf(sessionId),
            keywords = listOf(keyword),
            limit = limit,
            startMs = null,
            endMs = null,
        ).map {
            ConversationMessage(
                messageId = it.messageId,
                role = it.role,
                createdAtMillis = it.createdAt,
                text = it.snippet,
                // A search hit is a bounded snippet of a possibly longer
                // message; saying so is what tells the model to page to the
                // full row instead of treating the snippet as the whole text.
                truncated = true,
            )
        }
}

/**
 * The pure half of `conversation_query`: argument validation, page sizing,
 * budget enforcement and result assembly. No Android, no I/O.
 *
 * Everything here exists to make the requirement's 截流 ("throttle") real:
 * a caller can never receive more than `result_budget_chars` of text, and what
 * it does not receive comes back as a `next_cursor` instead of as silence.
 */
object ConversationQueryPolicy {

    /** Per-message text cap. Reuses the sessions CLI's own display cap. */
    const val MESSAGE_TEXT_MAX = ChatRepository.MESSAGE_TEXT_MAX

    private val CURSOR_PATTERN = Regex("m(\\d+)")

    fun defaultPageLimit(): Int = RuntimeCommunicationDirectory.DEFAULT_PAGE_LIMIT

    fun maxPageLimit(): Int = RuntimeCommunicationDirectory.MAX_PAGE_LIMIT

    fun defaultBudgetChars(): Int = RuntimeCommunicationDirectory.DEFAULT_RESULT_BUDGET_CHARS

    fun maxBudgetChars(): Int = RuntimeCommunicationDirectory.MAX_RESULT_BUDGET_CHARS

    /**
     * `null` when [token] is absent; `null` wrapped in a failure when it is
     * malformed — the caller distinguishes the two, because "no cursor" is the
     * normal first call and "bad cursor" is a caller error.
     */
    fun decodeCursor(cursor: RuntimeCommunicationCursor?): Result<Int> {
        if (cursor == null) return Result.success(0)
        val match = CURSOR_PATTERN.matchEntire(cursor.token.trim())
        return match?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it >= 0 }
            ?.let { Result.success(it) }
            ?: Result.failure(IllegalArgumentException("invalid cursor"))
    }

    fun encodeCursor(offset: Int): RuntimeCommunicationCursor =
        RuntimeCommunicationCursor("m$offset")

    /**
     * Trim [messages] so their combined text fits [budgetChars].
     *
     * Deliberately keeps AT LEAST the first message (truncating its text if it
     * alone overflows) rather than rejecting the whole page the way
     * [RuntimeCommunicationDirectory] does for metadata records. A hard reject
     * would dead-end exactly the caller this feature exists for: a model that
     * asked for one big message would get an error instead of a bounded part of
     * it, and would have to guess a smaller `limit` to make progress.
     */
    fun fitToBudget(
        messages: List<ConversationMessage>,
        budgetChars: Int,
    ): List<ConversationMessage> {
        if (messages.isEmpty()) return emptyList()
        val kept = ArrayList<ConversationMessage>(messages.size)
        var used = 0
        for (message in messages) {
            val cost = message.text.length
            if (kept.isEmpty()) {
                // The first message always survives, clipped if it must be.
                val room = budgetChars.coerceAtLeast(1)
                kept += if (cost <= room) {
                    message
                } else {
                    message.copy(text = message.text.take(room), truncated = true)
                }
                used += kept[0].text.length
                continue
            }
            if (used + cost > budgetChars) break
            kept += message
            used += cost
        }
        return kept
    }

    fun messageJson(message: ConversationMessage): JSONObject = JSONObject()
        .put("message_id", ConversationIdProtocol.prefixedMessageId(message.messageId))
        .put("role", message.role)
        .put("created_at", message.createdAtMillis)
        .put("text", message.text)
        .put("truncated", message.truncated)

    /**
     * Assemble the tool result. [nextCursor] is non-null whenever anything was
     * left behind — either messages the budget dropped or rows the page did not
     * reach — so the model is never told "that's all" when it is not.
     */
    fun resultJson(
        conversationIdRaw: String,
        messages: List<ConversationMessage>,
        totalMatching: Int,
        nextCursor: RuntimeCommunicationCursor?,
        budgetChars: Int,
        mode: String,
    ): JSONObject = JSONObject()
        .put("ok", true)
        // The echo is what closes the loop on the prefix: the caller sees the
        // same spelling it was handed, which is the evidence that the ID it
        // read in a transcript is the one this tool accepts.
        .put("conversation_id", ConversationIdProtocol.prefixedConversationId(conversationIdRaw))
        .put("mode", mode)
        .put("total_matching", totalMatching)
        .put("returned", messages.size)
        .put("budget_chars", budgetChars)
        .put("used_chars", messages.sumOf { it.text.length })
        .put("next_cursor", nextCursor?.token ?: JSONObject.NULL)
        .put(
            "messages",
            JSONArray().apply { messages.forEach { put(messageJson(it)) } },
        )
}
