package com.openminis.app.tools

import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.MessageWindow
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
/**
 * One window of the transcript, plus everything needed to continue from exactly
 * where it stopped.
 *
 * These travel together because the cursor must not be derivable from anything
 * the caller already holds. The previous code made one from `offset + kept.size`,
 * which mixed two index spaces: `kept.size` counts DELIVERED items while a cursor
 * has to name a position in the UNDERLYING scan. The two diverge as soon as the
 * budget trims a page or a row is dropped during the parse — and the result was a
 * cursor that stood still (an all-blank window returned the same token forever)
 * or went backwards (a budget-trimmed page re-delivered what it had just sent).
 */
data class ConversationSlice(
    /** This window's messages, in the order the caller should render them. */
    val items: List<ConversationMessage>,
    /**
     * Parallel to [items]: the offset to resume from when the caller delivers
     * only the first `k` of them — `resumeOffsets[k]` is where the first
     * UNDELIVERED item sits.
     */
    val resumeOffsets: List<Int>,
    /** The offset to resume from once every item here has been delivered. */
    val resumeAfter: Int,
    /** True when the underlying scan stopped with rows still unexamined. */
    val moreMayRemain: Boolean,
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
    ): ConversationSlice

    /**
     * Search hits from row [offset] onward. [offset] is a raw-row position, not a
     * hit index, because the parse-time filtering that decides which rows become
     * hits happens after the scan. Search runs through the same cursor as [page]
     * so a caller can walk a long result set without a second mechanism.
     */
    suspend fun search(
        sessionId: String,
        keyword: String,
        offset: Int,
        limit: Int,
    ): ConversationSlice
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
    ): ConversationSlice =
        chatRepository.loadMessageWindow(sessionId, offset, limit, maxChars).toSlice(offset) {
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
        offset: Int,
        limit: Int,
    ): ConversationSlice =
        chatRepository.searchMessageWindow(
            sessionIds = listOf(sessionId),
            keywords = listOf(keyword),
            limit = limit,
            startMs = null,
            endMs = null,
            offset = offset,
        ).toSlice(offset) {
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
 * Rebases a window's row indices into absolute offsets, so that everything above
 * this line speaks one index space: positions in the underlying scan.
 */
private fun <T> MessageWindow<T>.toSlice(
    offset: Int,
    map: (T) -> ConversationMessage,
): ConversationSlice = ConversationSlice(
    items = items.map(map),
    resumeOffsets = windowIndices.map { offset + it },
    resumeAfter = offset + rowsExamined,
    moreMayRemain = moreMayRemain,
)

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
     * The cursor to hand back, derived from where the scan actually stopped.
     *
     * `null` means "there is nothing after what you just got". That is the one
     * claim [resultJson]'s contract forbids making when it is not true, and the
     * old predicate — `kept.size == limit` for search, `offset + kept.size < total`
     * for page — made it from the wrong quantity: both compared DELIVERED items
     * against a scan-level bound, so a page the budget had trimmed looked like the
     * end of the data.
     *
     * [deliveredCount] is how many of [slice]'s items survived the budget, so the
     * first undelivered one sits at `resumeOffsets[deliveredCount]`. Trimming can
     * therefore leave something behind even when the scan reached the end: the
     * budget's leftovers and the scan's leftovers are both leftovers, and the old
     * predicates only ever looked at the second kind.
     *
     * The last guard is not defensive. A cursor is a promise that asking again
     * with it returns something NEW; a cursor equal to the offset that produced
     * this window breaks that promise, and a caller that trusts it loops forever
     * on one page. An all-blank page used to do exactly that — every row dropped
     * during the parse, so `offset + 0` came back unchanged, forever. Refusing to
     * emit it turns a silent infinite loop into a clean stop.
     *
     * [totalRows] is the session's raw row count, when the caller has it. A window
     * that filled up cannot tell "there are more rows" from "that was exactly the
     * last one", so without this bound an exactly-divisible session costs one
     * extra round trip that returns nothing. It is deliberately NOT accepted for
     * search: there `total_matching` counts hits in the window, and comparing a
     * scan position against a hit count would be the same category error as the
     * bug this function replaces.
     */
    fun nextCursor(
        slice: ConversationSlice,
        deliveredCount: Int,
        offset: Int,
        totalRows: Int? = null,
    ): RuntimeCommunicationCursor? {
        val trimmed = deliveredCount < slice.items.size
        val hasMore = when {
            trimmed -> true
            totalRows != null -> slice.resumeAfter < totalRows
            else -> slice.moreMayRemain
        }
        if (!hasMore) return null
        val resume = if (trimmed) slice.resumeOffsets[deliveredCount] else slice.resumeAfter
        return resume.takeIf { it > offset }?.let { encodeCursor(it) }
    }

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
     * reach — so the model is never told "that's all" when it is not. Compute it
     * with [nextCursor] rather than by hand: the two conditions are not
     * expressible in terms of what was delivered.
     *
     * [totalMatching] deliberately means two different things, because the two
     * modes know different things. In `page` mode it is the number of rows in the
     * session, which is exact. In `search` mode it is the number of hits found in
     * the window this call actually examined — NOT a count of every match in the
     * session, which would need a second full scan. A caller that needs to know
     * whether search's count is final should page until `next_cursor` is null
     * rather than compare against it.
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
