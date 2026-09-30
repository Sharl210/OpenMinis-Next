package com.openminis.app.data.repository

import android.database.sqlite.SQLiteBlobTooBigException
import com.openminis.app.data.db.ChatDao
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.FolderEntity
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.agent.SoulIcon
import com.openminis.app.data.model.MessagePartsCodec
import com.openminis.app.data.model.MessageProvenance
import com.openminis.app.data.model.ModelAttributionSnapshot
import com.openminis.app.data.model.ThinkingLevel
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * [T-android-loadmessages-fallback-truncation] Page through a fixed number of
 * rows, tolerating slices that cannot be read.
 *
 * `loadPage(offset, limit)` returns the slice's rows plus whether it had to fall
 * back to reading one row at a time (`fellBack`). An EMPTY slice means two
 * different things depending on that flag:
 *
 *  - normal path → there is genuinely nothing more, so stopping is right;
 *  - row-by-row fallback → every row in this slice was unreadable (an oversized
 *    blob). Treating that as "nothing more" silently dropped the whole REST of
 *    the transcript, so the user's later messages simply vanished from the
 *    session. `offset` is a DB-row position, so skipping past an unreadable
 *    slice and continuing is safe and cannot loop (offset always advances).
 *
 * Generic over the row type and free of DAO/Android dependencies so the policy
 * is testable on the JVM.
 */
internal suspend fun <T> loadAllPages(
    total: Int,
    pageSize: Int,
    loadPage: suspend (offset: Int, limit: Int) -> Pair<List<T>, Boolean>,
): List<T> {
    if (total <= 0 || pageSize <= 0) return emptyList()
    val out = ArrayList<T>(total)
    var offset = 0
    while (offset < total) {
        val (page, fellBack) = loadPage(offset, pageSize)
        if (page.isEmpty()) {
            if (fellBack && offset + pageSize < total) {
                offset += pageSize
                continue
            }
            break
        }
        out.addAll(page)
        offset += pageSize
    }
    return out
}

class ChatRepository(internal val dao: ChatDao) {
    fun observeSessions(): Flow<List<ChatSessionEntity>> = dao.observeSessions()

    suspend fun createSession(
        modelId: String,
        title: String? = null,
        // [T-memory-global-toggle-settings-ui-android] honor the global
        // memory default at row-insert time. Caller (ChatViewModel) reads
        // MemoryGlobalPrefs.isGlobalEnabled and passes the value through
        // here; existing call sites that omit it keep the prior
        // memoryEnabled=1 behavior (legacy default).
        memoryEnabled: Boolean = true,
    ): ChatSessionEntity {
        val now = System.currentTimeMillis()
        val session = ChatSessionEntity(
            id = UUID.randomUUID().toString(),
            title = title,
            modelId = modelId,
            createdAt = now,
            updatedAt = now,
            memoryEnabled = if (memoryEnabled) 1 else 0,
        )
        dao.insertSession(session)
        return session
    }

    suspend fun insertSession(session: ChatSessionEntity) {
        dao.insertSession(session)
    }

    suspend fun getSession(id: String): ChatSessionEntity? = dao.getSession(id)

    suspend fun listSessions(): List<ChatSessionEntity> = dao.listSessions()

    suspend fun sessionTokenUsages(sessionId: String): List<String> = dao.tokenUsages(sessionId)

    fun observeTokenUsagesForSessions(sessionIds: List<String>): Flow<List<com.openminis.app.data.db.SessionTokenUsageRow>> =
        dao.observeTokenUsagesForSessions(sessionIds)

    suspend fun tokenUsagesForSessions(sessionIds: List<String>): List<com.openminis.app.data.db.SessionTokenUsageRow> =
        dao.tokenUsagesForSessions(sessionIds)

    /**
     * [T-android-topology-first-message] Each session's OPENING message, as text.
     *
     * The topology graph shows this as a node's body, per the requirement: "就是这个
     * 节点所对应的对话内容的第一条消息" (request.md:37). Before this the graph had
     * no first-message data and fell back to a session title or the model id, so the
     * box the user reads was showing something the requirement never asked for.
     *
     * Reuses [extractTextForOffload] rather than decoding `parts_json` again: that
     * helper already owns "what does this message actually say" (text → media label
     * → tool titles), including stripping `<system-reminder>` wrappers. A second
     * decoder here would be a second definition of that question — the exact
     * duplication this codebase has been bitten by elsewhere.
     *
     * Sessions with no rows yet are simply absent from the map, so the caller can
     * tell "no message" apart from "empty message".
     */
    suspend fun firstMessagesForSessions(sessionIds: List<String>): Map<String, String> {
        if (sessionIds.isEmpty()) return emptyMap()
        return dao.firstMessagesForSessions(sessionIds)
            .associate { row -> row.sessionId to extractTextForOffload(row.partsJson).trim() }
            .filterValues { it.isNotEmpty() }
    }

    /**
     * [T-android-session-paused-badge-hardkill] Session ids whose agent loop was
     * left interrupted, derived purely from the persisted message tail — so the
     * PAUSED badge survives a hard process death (where the lifecycle-callback
     * push never runs). Lightweight: one query for the last message per session,
     * then the SAME interrupted-tail predicate as ChatViewModel.loadSession's
     * detection (kept in sync intentionally). Mirrors iOS
     * ChatStore.interruptedSessionIds.
     *
     * THREE-STATE CONTRACT — the nullable return is load-bearing. `null` means
     * "the tail scan FAILED, so the interrupted set is UNKNOWN"; it does NOT
     * mean "nothing is interrupted". Callers must skip their reconcile on
     * `null`: `SessionBadgeStore.reconcileInterruptedSessions` removes AND
     * PERSISTS the removal of a PAUSED badge for every session missing from the
     * set it is handed, so handing it `emptySet()` after a failed read wipes
     * every PAUSED badge and writes that wipe to disk. Collapsing the failure
     * into `emptySet()` is exactly how "query failed" became indistinguishable
     * from "nothing is interrupted" — both MinisApp call sites (cold start and
     * foreground resume) branch on `?: return@launch` for this reason.
     */
    suspend fun interruptedSessionIds(): Set<String>? {
        val tails = try {
            dao.lastMessageTailPerSession()
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            // Cancellation is not a read failure. Reporting UNKNOWN here would
            // turn a cancelled scan into a "successful, empty" answer for any
            // caller that does not itself check for cancellation.
            throw cancellation
        } catch (error: Throwable) {
            android.util.Log.w(
                "ChatRepository",
                "interruptedSessionIds: tail scan failed — interrupted set is UNKNOWN, not empty",
                error,
            )
            return null
        }
        val result = HashSet<String>()
        for (row in tails) {
            if (isInterruptedTail(row.role, row.partsJson)) result.add(row.sessionId)
        }
        return result
    }

    /**
     * [T-android-session-paused-badge-hardkill] The interrupted-tail predicate
     * over a raw `parts_json` string, matching ChatViewModel.loadSession's
     * AgentContentPart-based logic:
     *   - role USER + ALL parts are tool_result (tools ran, next model call never
     *     fired), OR the single synthetic "Continue" reminder text part, OR
     *   - role ASSISTANT + any tool_use part (model asked for tools that never ran)
     * Part type discriminator is the JSON "type" field — the @SerialName values
     * from [com.openminis.app.data.model.ContentPart]: "toolUse" / "toolResult"
     * / "text" (camelCase, NOT snake_case); text payload is the "value" field.
     */
    private fun isInterruptedTail(role: String, partsJson: String): Boolean {
        val arr = runCatching { org.json.JSONArray(partsJson) }.getOrNull() ?: return false
        val types = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { types.add(it.optString("type")) }
        }
        return when (role.uppercase()) {
            "USER" -> {
                val allToolResults = types.isNotEmpty() && types.all { it == "toolResult" }
                val isContinueReminder = arr.length() == 1 &&
                    arr.optJSONObject(0)?.takeIf { it.optString("type") == "text" }
                        ?.optString("value")
                        ?.contains("The user stopped the previous response") == true
                allToolResults || isContinueReminder
            }
            "ASSISTANT" -> types.any { it == "toolUse" }
            else -> false
        }
    }

    suspend fun updateSessionTitle(id: String, title: String) {
        dao.updateSessionTitle(id, title, System.currentTimeMillis())
    }

    suspend fun updateSessionTitleAndCategoryWithModelSnapshot(
        id: String,
        title: String,
        category: String?,
        entryId: String?,
        modelId: String?,
        displayName: String?,
        providerType: String?,
        generatedAt: Long?,
    ) {
        dao.updateSessionTitleAndCategoryWithModelSnapshot(
            id, title, category, entryId, modelId, displayName, providerType, generatedAt,
            System.currentTimeMillis(),
        )
    }


    suspend fun updateSessionTitleAndCategory(id: String, title: String, category: String?) {
        dao.updateSessionTitleAndCategory(id, title, category, System.currentTimeMillis())
    }

    suspend fun updateSessionModel(sessionId: String, modelId: String) {
        dao.updateSessionModel(sessionId, modelId)
    }

    suspend fun updateSessionBinding(sessionId: String, binding: String, modelId: String) {
        dao.updateSessionBinding(sessionId, binding, modelId)
    }

    suspend fun deleteSession(id: String) {
        dao.deleteSessionIfPresent(id)
    }

    /** Deletes an already-authorized Chat Room session subtree atomically. */
    suspend fun deleteSessionSubtree(sessionIds: List<String>): Int =
        dao.deleteSessionSubtree(sessionIds)

    /**
     * Which of [ids] still have a session row.
     *
     * [deleteSessionSubtree] refuses the whole batch when one id is unknown, and
     * a subtree resolved out of the runtime tree can contain nodes for sessions
     * the database never had — so a recursive delete intersects with this before
     * asking for the atomic delete. Chunked to stay under SQLite's
     * bound-parameter ceiling, matching the delete's own 500-row chunking.
     */
    suspend fun existingSessionIds(ids: Collection<String>): List<String> {
        val distinct = ids.filter { it.isNotBlank() }.distinct()
        if (distinct.isEmpty()) return emptyList()
        return distinct.chunked(500).flatMap { dao.existingSessionIds(it) }
    }

    // ─── Session groups ("folders") ────────────────────────────────────────
    // [T-android-session-grouping] Ported from iOS ChatStore's Folders section.
    // Code says Folder, UI says Group — see FolderEntity for why.

    fun observeFolders(): Flow<List<FolderEntity>> = dao.observeFolders()

    suspend fun listFolders(): List<FolderEntity> = dao.listFolders()

    suspend fun getFolder(id: String): FolderEntity? = dao.getFolder(id)

    /**
     * Create a group. The description is trimmed and capped at
     * [FolderEntity.DESC_MAX_CHARS]; blank collapses to null so "no
     * description" is one value rather than two.
     */
    suspend fun createFolder(
        name: String,
        description: String? = null,
        origin: String = FolderEntity.ORIGIN_MANUAL,
    ): FolderEntity {
        val now = System.currentTimeMillis()
        val folder = FolderEntity(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            origin = origin,
            description = description?.trim()?.take(FolderEntity.DESC_MAX_CHARS)?.ifBlank { null },
            createdAt = now,
            updatedAt = now,
        )
        dao.insertFolder(folder)
        return folder
    }

    /**
     * Rename / re-describe. The UUID key is untouched, so members never move —
     * that is the whole reason the group is keyed by UUID and not by name.
     *
     * @param description null LEAVES the stored value alone; an empty string
     *   clears it. Callers that always pass the field through (e.g. a rename
     *   dialog) must therefore seed the field from the current value, or they
     *   will wipe descriptions they never meant to touch.
     */
    suspend fun renameFolder(id: String, name: String, description: String? = null) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        dao.renameFolder(
            id = id,
            name = trimmed,
            description = description?.trim()?.take(FolderEntity.DESC_MAX_CHARS),
            updatedAt = System.currentTimeMillis(),
        )
    }

    /**
     * Update the user-visible group identity without changing its UUID or members.
     * [icon] is either a normalized SoulIcon emoji/data URI or null for default.
     */
    suspend fun updateFolderIdentity(
        id: String,
        name: String,
        description: String? = null,
        icon: String? = null,
    ) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val normalizedIcon = when {
            icon == null -> null
            icon.trim().isBlank() -> null
            else -> {
                val value = icon.trim()
                when {
                    SoulIcon.normalizeEmojiInput(value) == value -> value
                    SoulIcon.isDataUri(value) &&
                        value.length <= SoulIcon.MAX_DATA_URI_CHARS &&
                        SoulIcon.decode(value) != null -> value
                    else -> null
                }
            }
        }
        dao.updateFolderIdentity(
            id = id,
            name = trimmed,
            description = description?.trim()?.take(FolderEntity.DESC_MAX_CHARS),
            icon = normalizedIcon,
            updatedAt = System.currentTimeMillis(),
        )
    }

    suspend fun toggleFolderPin(id: String): Boolean {
        val current = dao.getFolder(id) ?: return false
        val nowPinned = current.pinnedAt == null
        val now = System.currentTimeMillis()
        dao.setFolderPinned(id, if (nowPinned) now else null, now)
        return nowPinned
    }

    /**
     * Dissolve a group: drop the group row and return its members to ungrouped.
     * **No session is deleted** — this is the ONLY delete operation on a group,
     * and it can never cost the user a conversation.
     *
     * Members are read BEFORE the clear because the ids are the return value
     * (callers use them to refresh, and a future sync layer would need them to
     * push each freed session).
     *
     * @return ids of the sessions that became ungrouped.
     */
    suspend fun dissolveFolder(id: String): List<String> {
        val memberIds = dao.sessionIdsInFolder(id)
        dao.clearFolderForSessions(id)
        dao.deleteFolder(id)
        return memberIds
    }

    suspend fun sessionIdsInFolder(folderId: String): List<String> = dao.sessionIdsInFolder(folderId)

    /**
     * Move sessions into a group, or out of one when [folderId] is null.
     *
     * Writes only `folder_id`, never `updated_at` — filing is organizational
     * and must not re-sort the session list.
     */
    suspend fun setFolderForSessions(folderId: String?, sessionIds: List<String>) {
        if (sessionIds.isEmpty()) return
        for (sid in sessionIds) dao.setSessionFolder(sid, folderId)
    }

    /**
     * File a session only if it is still ungrouped. The condition is part of the
     * UPDATE, so a hand-filed session can never be overridden by an automatic
     * write racing it.
     *
     * @return true if this call actually filed the session.
     */
    suspend fun setFolderIfUnfiled(folderId: String, sessionId: String): Boolean =
        dao.setSessionFolderIfUnfiled(sessionId, folderId) > 0

    /**
     * Name → group, case- and whitespace-insensitive. Duplicate-tolerant by
     * construction (names are not unique); returns the most recently updated
     * match, which is what [listFolders]' ordering already puts first.
     */
    suspend fun findFolderByName(name: String): FolderEntity? {
        val needle = name.trim().lowercase()
        if (needle.isEmpty()) return null
        return dao.listFolders().firstOrNull { it.name.trim().lowercase() == needle }
    }

    suspend fun searchSessions(query: String): List<ChatSessionEntity> =
        dao.searchSessions("%$query%")

    fun observeMessages(sessionId: String): Flow<List<MessageEntity>> =
        dao.observeMessages(sessionId)

    /**
     * Load all messages for a session in bounded pages instead of a
     * single SELECT * batch. The legacy `dao.loadMessages` path issued
     * one query whose Cursor result, once materialised, easily exceeded
     * the per-CursorWindow 2 MB ceiling on a session containing even one
     * large tool_result blob (Issue #17) — Android then aborted with
     * SQLiteBlobTooBigException and the chat loader hung the UI thread.
     *
     * This paginated loader keeps each underlying query small enough that
     * the CursorWindow can hold a normal-shaped page. If a page still contains
     * an individual >2MB row we re-fetch that range row-by-row and DROP
     * the rows that genuinely cannot be materialised — the rest of the
     * transcript still loads, instead of the whole load crashing.
     *
     * MEASURED, so nobody re-derives it from the old wording: this comment used
     * to claim the unreadable row was replaced by a "proxy MessageEntity" that
     * kept the transcript continuous. No such substitution exists anywhere in
     * the repository — `loadPageRowByRow` skips the row (`?: continue`), so a
     * message that cannot be read is ABSENT from the returned list, not
     * represented by a stand-in. Consumers that pair UI positions with DB rows
     * must therefore tolerate a gap rather than assume they line up 1:1.
     * Introducing a real placeholder would invent message content; that is a
     * product decision, not a comment fix.
     *
     * [T-android-loadmessages-fallback-truncation] A page made up ENTIRELY of
     * unreadable rows comes back empty through this fallback, which must not be
     * read as "no more rows" — see [loadAllPages].
     *
     * Existing oversized rows are not migrated; new oversized inserts
     * are prevented by the cap in [appendMessage].
     */
    suspend fun loadMessages(sessionId: String): List<MessageEntity> {
        // T-android-crash-safe-mode-v2: defensive guard. ChatViewModel.loadSession
        // is already gated upstream, but loadMessages has other call sites
        // (compaction, fork, regenerate-title, debug menu) that could fire
        // from a foreground retry or a Flow collector before the safe-mode
        // dialog is dismissed. Returning an empty list mirrors the "no rows
        // for this session" branch and is harmless for every caller.
        if (com.openminis.app.crash.CrashFrequencyDetector.isSafeMode()) {
            android.util.Log.w(
                "ChatRepository",
                "loadMessages: safe-mode active, skipping (sessionId=$sessionId)",
            )
            return emptyList()
        }
        val total = dao.messageCountForSession(sessionId)
        if (total == 0) return emptyList()
        return loadAllPages(total, LOAD_PAGE_SIZE) { offset, limit ->
            try {
                dao.loadMessagesPage(sessionId, offset, limit) to false
            } catch (e: SQLiteBlobTooBigException) {
                // Fall back to single-row pages so we can isolate the
                // offending blob(s) and serve the rest of the slice.
                loadPageRowByRow(sessionId, offset, limit) to true
            } catch (e: IllegalStateException) {
                // Some Room/SQLite combinations wrap the CursorWindow
                // overflow in IllegalStateException("Couldn't read row N,
                // col N from CursorWindow"); treat the same way.
                if (e.message?.contains("CursorWindow", ignoreCase = true) == true) {
                    loadPageRowByRow(sessionId, offset, limit) to true
                } else {
                    throw e
                }
            }
        }
    }

    private suspend fun loadPageRowByRow(
        sessionId: String,
        baseOffset: Int,
        limit: Int,
    ): List<MessageEntity> {
        val result = ArrayList<MessageEntity>(limit)
        for (i in 0 until limit) {
            val row = try {
                dao.loadMessagesPage(sessionId, baseOffset + i, 1).firstOrNull()
            } catch (e: SQLiteBlobTooBigException) {
                null
            } catch (e: IllegalStateException) {
                if (e.message?.contains("CursorWindow", ignoreCase = true) == true) null else throw e
            } ?: continue
            result.add(row)
        }
        return result
    }

    /**
     * [T-android-persistence-result-check] Returns the number of rows actually
     * deleted, so callers can tell "the rewind happened" from "silently did
     * nothing" — the previous `Unit` return made a failed cut indistinguishable
     * from a successful one.
     */
    suspend fun deleteMessagesAfter(sessionId: String, keepCount: Int): Int =
        dao.deleteMessagesAfter(sessionId, keepCount)

    /**
     * Rewrite a single message row's parts_json in place. Used by
     * [com.openminis.app.ui.chat.ChatViewModel.rerunFromToolBlock]'s block-
     * boundary cut to trim the kept assistant row to the parts before the
     * target tool_use. Mirrors iOS ChatStore.updateMessageParts.
     */
    /** Returns 1 when the row was rewritten, 0 when no such row exists. */
    suspend fun updateMessageParts(id: String, partsJson: String): Int =
        dao.updateMessageParts(id, partsJson)

    /**
     * Replace one persisted human-user row in place.
     *
     * Returns the parts_json that was ACTUALLY stored — or null when no row
     * matched the id (the failure the old `Boolean` carried).
     *
     * [T-android-persistence-result-check] Returning the persisted payload
     * matters because this function caps oversized input
     * ([MAX_MESSAGE_PARTS_JSON_LENGTH]): a caller that rendered its own uncapped
     * copy showed the user the full text while the database held the truncated
     * version, so an edited message visibly shrank on the next reload. A caller
     * that builds its UI from this return value cannot drift.
     */
    suspend fun replaceUserMessageInPlace(messageId: String, partsJson: String): String? {
        val capped = if (partsJson.length > MAX_MESSAGE_PARTS_JSON_LENGTH) {
            buildTruncatedPartsJson(partsJson)
        } else {
            partsJson
        }
        val rows = dao.replaceUserMessagePartsInPlace(
            id = messageId,
            partsJson = capped,
            updatedAt = System.currentTimeMillis(),
        )
        return capped.takeIf { rows == 1 }
    }

    /** [T-error-persist-android] Set/clear the error sticker on a row by id. */
    suspend fun updateMessageErrorInfo(messageId: String, errorInfo: String?) =
        dao.updateMessageErrorInfo(messageId, errorInfo)

    /**
     * [T-error-persist-android] Set/clear the error sticker on a session's last
     * assistant row. See [ChatDao.updateLastAssistantError]. No-op when no
     * assistant row exists yet.
     */
    suspend fun updateLastAssistantError(sessionId: String, errorInfo: String?) =
        dao.updateLastAssistantError(sessionId, errorInfo)

    /**
     * [T-token-attribution-snapshot] `modelSnapshot` records which model
     * ACTUALLY produced this message.
     *
     * It must be supplied by the caller from the request context — do NOT
     * resolve it in here by reading the session. The session's `model_id` is
     * rewritten on every switch, including automatic failover, and by the time
     * a turn finishes it may already point at a different model than the one
     * that served it. Reading it here would reproduce the exact bug this
     * snapshot exists to fix, only scoped to one row instead of the whole
     * session.
     *
     * [T-android-thinking-level-persist] `thinkingLevel` follows the identical
     * rule and for the identical reason: it is the level the reply was
     * produced at, so the CALLER supplies it from the turn it is persisting —
     * never `_thinkingLevel.value` read at persist time, which is one mutable
     * setting shared by the whole session and would re-label every restored row
     * with whatever the user has selected now. Null means "not recorded" (rows
     * written before this argument existed); it is stored as NULL and the UI
     * then shows no level capsule rather than substituting a current value.
     *
     * Appended at the END of the parameter list so existing positional callers
     * (`appendMessage(id, "assistant", parts, tokenJson)`) keep compiling.
     */
    suspend fun appendMessage(
        sessionId: String,
        role: String,
        partsJson: String,
        tokenUsage: String? = null,
        reasoningContent: String? = null,
        modelSnapshot: ModelAttributionSnapshot? = null,
        provenance: MessageProvenance = MessageProvenance.UNKNOWN,
        thinkingLevel: ThinkingLevel? = null,
    ): MessageEntity {
        val sortOrder = dao.nextSortOrder(sessionId)
        val now = System.currentTimeMillis()
        // Cap the body so a runaway tool_result (e.g. a 13 MB browser_use
        // dump — Issue #17) cannot land an oversize blob into a Room row
        // that later fails CursorWindow's 2 MB ceiling on read. We keep
        // the row in the same parts_json shape (text part) so downstream
        // parsers — UI rendering and JSON-array consumers in DAO/search
        // — never break on the truncated payload.
        val capped = if (partsJson.length > MAX_MESSAGE_PARTS_JSON_LENGTH) {
            buildTruncatedPartsJson(partsJson)
        } else {
            partsJson
        }
        val source = if (provenance == MessageProvenance.UNKNOWN) {
            MessagePartsCodec.provenanceOf(partsJson)
        } else {
            provenance
        }
        val persistedParts = MessagePartsCodec.withProvenance(capped, source)
        val message = MessageEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            role = role,
            partsJson = persistedParts,
            createdAt = now,
            tokenUsage = tokenUsage,
            sortOrder = sortOrder,
            reasoningContent = reasoningContent,
            modelId = modelSnapshot?.modelId,
            modelDisplayName = modelSnapshot?.displayName,
            providerType = modelSnapshot?.providerTypeRaw,
            providerInstanceId = modelSnapshot?.providerInstanceId,
            // .name is the enum's stable wire token ("HIGH"), the same encoding
            // ThinkingLevel.decoded reads back and kotlinx already writes.
            thinkingLevel = thinkingLevel?.name,
        )
        dao.insertMessage(message)
        // [T-android-preview-flicker-toolresult] Only overwrite the preview
        // when this row actually yields one. A tool-result row is
        // `[{"type":"toolResult",…}]`, a shape extractTextPreview does not
        // summarize (it handles text / mediaRef / toolUse), so it returns
        // null — and writing that null blanked the column, flipping the
        // session list to "No messages yet" the instant a tool finished. The
        // live preview pushed before the tool ran had just put the tool title
        // there, so a multi-tool run visibly oscillated between the title and
        // the empty state on every tool boundary.
        //
        // The row's own timestamp is still worth recording: it is what keeps
        // the session sorted as recently-active while a long tool chain runs.
        val preview = extractTextPreview(capped)
        if (preview != null) {
            dao.updateLastMessage(sessionId, preview, now)
        } else {
            dao.touchSession(sessionId, now)
        }
        return message
    }

    /**
     * [T-android-session-last-message-live-tool-call] Update ONLY the session's
     * `last_message` preview (and `updated_at`) from an in-progress assistant
     * turn's parts_json — WITHOUT inserting a message row. The agent loop
     * persists the authoritative assistant row only at turn end (after tools
     * execute); during a long tool call the session list would otherwise show a
     * stale preview (or "No messages yet" for a turn with no prior text). This
     * pushes the live tool-call summary / partial text into the list the moment
     * the model emits it, mirroring how iOS overlays the live VM's last message.
     *
     * Uses the same [extractTextPreview] as [appendMessage], so a text-only turn
     * shows its text and a tool-only turn shows the tool summary. No-op when the
     * payload yields no preview (avoids overwriting a good preview with null).
     */
    suspend fun updateSessionPreview(sessionId: String, partsJson: String) {
        val preview = extractTextPreview(partsJson) ?: return
        dao.updateLastMessage(sessionId, preview, System.currentTimeMillis())
    }




    // ───────────────── T188: minis-sessions-cli backend ─────────────────
    //
    // Three high-level queries surfaced to SessionsOffloadHandler. The DAO
    // side handles raw SQL + result projection; we add the JSON parsing,
    // text extraction, and snippet trimming. Mirrors iOS
    // `ChatStore.swift` L774-1023 line-by-line so the offload tool's
    // output shape is identical across platforms.

    /**
     * Backs `minis-sessions-cli list`. Returns sessions ordered by
     * last_active DESC, optionally filtered by id list, keyword AND, and
     * a date range on `updated_at` (so the user's "show me sessions
     * touched in March 2026" works on the timestamp the session-list UI
     * already exposes).
     *
     * Keyword AND semantics: each keyword has to land *somewhere* — in
     * the title or in any message's parts_json. Two keywords mean both
     * must match (possibly in different messages). This matches iOS,
     * which intentionally avoids requiring keywords to co-occur in one
     * row so a multi-turn session about "python" + "flask" still hits.
     */
    suspend fun querySessionsMeta(
        sessionIds: List<String>?,
        keywords: List<String>?,
        limit: Int,
        startMs: Long?,
        endMs: Long?,
    ): List<SessionMeta> {
        val conditions = mutableListOf<String>()
        val args = mutableListOf<Any>()

        if (!sessionIds.isNullOrEmpty()) {
            conditions += "s.id IN (${sessionIds.joinToString(",") { "?" }})"
            args.addAll(sessionIds)
        }
        if (startMs != null) {
            conditions += "s.updated_at >= ?"
            args += startMs
        }
        if (endMs != null) {
            conditions += "s.updated_at <= ?"
            args += endMs
        }
        if (!keywords.isNullOrEmpty()) {
            for (kw in keywords) {
                val pat = "%$kw%"
                conditions +=
                    "(s.title LIKE ? OR EXISTS (SELECT 1 FROM messages m " +
                    "WHERE m.session_id = s.id AND m.parts_json LIKE ?))"
                args += pat
                args += pat
            }
        }
        val where = if (conditions.isEmpty()) "" else "WHERE " + conditions.joinToString(" AND ")
        val sql = """
            SELECT s.id, s.title,
                   (SELECT m2.parts_json FROM messages m2
                    WHERE m2.session_id = s.id AND m2.role = 'user'
                    ORDER BY m2.sort_order ASC LIMIT 1) AS first_user_msg,
                   s.source, s.created_at, s.updated_at,
                   (SELECT COUNT(*) FROM messages m3 WHERE m3.session_id = s.id) AS msg_count
            FROM sessions s
            $where
            ORDER BY s.updated_at DESC
            LIMIT ?
        """.trimIndent()
        args += limit

        val rows = dao.runSessionsMetaQuery(
            androidx.sqlite.db.SimpleSQLiteQuery(sql, args.toTypedArray()),
        )
        return rows.map { r ->
            val preview = r.firstUserMsg?.let { extractTextForOffload(it) }
                ?.takeIf { it.isNotBlank() }
                ?.take(60)
            SessionMeta(
                id = r.id,
                title = r.title,
                preview = preview,
                source = r.source,
                startedAt = r.createdAt,
                lastActive = r.updatedAt,
                messageCount = r.msgCount,
            )
        }
    }

    /**
     * Backs `minis-sessions-cli search`. Over-fetches `limit * 3` rows
     * because parts_json LIKE matches can hit tool-call JSON metadata
     * (e.g. a tool name that happens to contain the keyword) rather than
     * actual user-visible text. We parse each row's parts_json on the
     * Kotlin side, drop rows whose extracted text is blank or whose
     * keyword didn't survive the parse, and trim to [limit] on the way
     * out. Mirrors iOS ChatStore.searchMessages.
     *
     * [offset] is a RAW ROW offset into the result set, applied by SQL before any
     * of that parse-time filtering runs. It is therefore NOT a hit index, and it
     * is not interchangeable with one — see [MessageWindow].
     */
    suspend fun searchMessages(
        sessionIds: List<String>?,
        keywords: List<String>,
        limit: Int,
        startMs: Long?,
        endMs: Long?,
        // Appended last and defaulted so the existing positional call in
        // SessionsOffloadHandler keeps its meaning; inserting it before
        // `startMs`/`endMs` would silently rebind them.
        offset: Int = 0,
    ): List<MessageSearchMatch> =
        searchMessageWindow(sessionIds, keywords, limit, startMs, endMs, offset).items

    /** [searchMessages] plus the bookkeeping needed to resume exactly; see [MessageWindow]. */
    suspend fun searchMessageWindow(
        sessionIds: List<String>?,
        keywords: List<String>,
        limit: Int,
        startMs: Long?,
        endMs: Long?,
        offset: Int = 0,
    ): MessageWindow<MessageSearchMatch> {
        if (keywords.isEmpty()) return MessageWindow(emptyList(), emptyList(), 0, false)
        val conditions = mutableListOf<String>()
        val args = mutableListOf<Any>()

        for (kw in keywords) {
            conditions += "m.parts_json LIKE ?"
            args += "%$kw%"
        }
        if (!sessionIds.isNullOrEmpty()) {
            conditions += "m.session_id IN (${sessionIds.joinToString(",") { "?" }})"
            args.addAll(sessionIds)
        }
        if (startMs != null) {
            conditions += "m.created_at >= ?"
            args += startMs
        }
        if (endMs != null) {
            conditions += "m.created_at <= ?"
            args += endMs
        }
        val where = conditions.joinToString(" AND ")
        val window = limit * 3
        val safeOffset = offset.coerceAtLeast(0)
        val sql = """
            SELECT m.session_id, m.id, m.role, m.created_at, m.parts_json
            FROM messages m
            WHERE $where
            ORDER BY m.created_at DESC, m.sort_order ASC
            LIMIT ?
            OFFSET ?
        """.trimIndent()
        args += window
        args += safeOffset

        val rows = dao.runMessageSearchQuery(
            androidx.sqlite.db.SimpleSQLiteQuery(sql, args.toTypedArray()),
        )
        val out = mutableListOf<MessageSearchMatch>()
        val indices = mutableListOf<Int>()
        var consumed = rows.size
        var brokeEarly = false
        for ((i, r) in rows.withIndex()) {
            val text = extractTextForOffload(r.partsJson)
            if (text.isBlank()) continue
            val snip = keywordSnippet(text, keywords, SNIPPET_MAX)
            if (snip.isBlank()) continue
            out += MessageSearchMatch(r.sessionId, r.id, r.role, r.createdAt, snip)
            indices += i
            if (out.size >= limit) {
                // Stopped at its own cap, so rows past `i` were never looked at.
                consumed = i + 1
                brokeEarly = true
                break
            }
        }
        return MessageWindow(
            items = out,
            windowIndices = indices,
            rowsExamined = consumed,
            moreMayRemain = brokeEarly || rows.size >= window,
        )
    }

    /**
     * Backs `minis-sessions-cli messages --id ... --offset --limit`.
     * Skips messages whose extracted text is blank (system-only reminder
     * content, all-tool-use turns) so the agent sees a contiguous
     * user-visible transcript.
     */
    suspend fun loadMessagePage(
        sessionId: String,
        offset: Int,
        limit: Int,
        // [T-android-sessions-cli-full] Per-message text cap. Default stays the
        // documented 600; `minis-sessions-cli messages --full` passes
        // MESSAGE_TEXT_MAX_FULL (50000) so exports aren't silently gutted.
        maxChars: Int = MESSAGE_TEXT_MAX,
        // [T-android-sessions-cli-messages-daterange] GH#200. Inclusive,
        // independently optional created_at bounds; null = unbounded on that
        // side, so existing callers keep the previous behaviour untouched.
        startMs: Long? = null,
        endMs: Long? = null,
    ): List<MessagePageItem> =
        loadMessageWindow(sessionId, offset, limit, maxChars, startMs, endMs).items

    /** [loadMessagePage] plus the bookkeeping needed to resume exactly; see [MessageWindow]. */
    suspend fun loadMessageWindow(
        sessionId: String,
        offset: Int,
        limit: Int,
        maxChars: Int = MESSAGE_TEXT_MAX,
        startMs: Long? = null,
        endMs: Long? = null,
    ): MessageWindow<MessagePageItem> {
        val rows = if (startMs == null && endMs == null) {
            dao.loadMessagesPage(sessionId, offset, limit)
        } else {
            dao.loadMessagesPageInRange(sessionId, offset, limit, startMs, endMs)
        }
        val items = mutableListOf<MessagePageItem>()
        val indices = mutableListOf<Int>()
        rows.forEachIndexed { i, e ->
            val text = extractTextForOffload(e.partsJson)
            if (text.isBlank()) return@forEachIndexed
            items += MessagePageItem(
                e.id, e.role, e.createdAt, text.take(maxChars),
                // Mark messages that exceeded the cap so the caller can emit
                // "truncated": true (mirrors iOS SessionsOffloadBridge).
                truncated = text.length > maxChars,
            )
            indices += i
        }
        return MessageWindow(
            items = items,
            windowIndices = indices,
            rowsExamined = rows.size,
            moreMayRemain = rows.size >= limit,
        )
    }

    suspend fun messageCount(sessionId: String): Int = dao.messageCountForSession(sessionId)

    /**
     * [T-android-sessions-cli-messages-daterange] Count under the same optional
     * range [loadMessagePage] filters by, so `total` and the returned slice
     * always describe the same set.
     */
    suspend fun messageCountInRange(sessionId: String, startMs: Long?, endMs: Long?): Int =
        if (startMs == null && endMs == null) {
            dao.messageCountForSession(sessionId)
        } else {
            dao.messageCountForSessionInRange(sessionId, startMs, endMs)
        }

    /**
     * Paginated raw [MessageEntity] page — used by [com.openminis.app.share.ChatExporter]
     * to stream-export long sessions without loading every message into
     * memory. Unlike [loadMessagePage] this does not strip / project the
     * row; the exporter needs the full `parts_json` payload to serialize.
     */
    suspend fun loadMessagePageRaw(
        sessionId: String,
        offset: Int,
        limit: Int,
    ): List<MessageEntity> =
        dao.loadMessagesPage(sessionId, offset, limit)

    /**
     * Walk parts_json and concatenate every `{type:"text", value:...}`
     * block (newline-joined) after running [stripSystemReminders] on
     * each. Distinct from [extractTextPreview] / [cleanPreview] above —
     * those collapse markdown for a 100-char single-line preview, while
     * this preserves the full text the offload caller wants to inspect.
     */
    /**
     * [T-android-topology-first-message] Relaxed from `private` so
     * [firstMessagesForSessions] — and anything else that needs "what does this
     * message say" — can reuse THIS definition instead of writing a second one.
     */
    internal fun extractTextForOffload(partsJson: String): String {
        return try {
            val arr = org.json.JSONArray(partsJson)
            val texts = mutableListOf<String>()
            var hasMedia = false
            val toolUses = mutableListOf<org.json.JSONObject>()
            val toolResults = mutableListOf<org.json.JSONObject>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                when (o.optString("type")) {
                    "text" -> {
                        val v = o.optString("value", "")
                        if (v.isNotBlank()) texts.add(stripSystemReminders(v))
                    }
                    "mediaRef" -> hasMedia = true
                    "toolUse" -> o.optJSONObject("value")?.let { toolUses.add(it) }
                    "toolResult" -> o.optJSONObject("value")?.let { toolResults.add(it) }
                }
            }
            if (texts.isNotEmpty()) return texts.joinToString("\n")
            if (hasMedia) return "[Image]"
            if (toolUses.isNotEmpty()) {
                return toolUses.joinToString(", ") { tu ->
                    val title = tu.optString("name", "tool")
                    val inp = tu.optString("input", "")
                    val toolTitle = try {
                        org.json.JSONObject(inp).optString("tool_title", "")
                    } catch (_: Exception) { "" }
                    if (toolTitle.isNotBlank()) toolTitle.take(100) else title
                }
            }
            if (toolResults.isNotEmpty()) {
                return toolResults.joinToString("\n") { tr ->
                    val output = tr.optString("output", "").take(200)
                    "[Tool result: $output]"
                }
            }
            ""
        } catch (_: Exception) {
            stripSystemReminders(partsJson)
        }
    }

    /**
     * Center a snippet of [maxLength] chars on the earliest keyword
     * match (case-insensitive). Tail/head ellipses indicate truncation
     * boundaries. If no keyword survives the parts_json → text reduction
     * (rare but possible — a SQL LIKE hit on tool-use JSON that the text
     * extractor strips), we return the leading [maxLength] chars so the
     * offload caller still sees *something*.
     */
    private fun keywordSnippet(text: String, keywords: List<String>, maxLength: Int): String {
        if (text.isEmpty()) return ""
        val lower = text.lowercase()
        var earliest = text.length
        for (kw in keywords) {
            val pos = lower.indexOf(kw.lowercase())
            if (pos in 0 until earliest) earliest = pos
        }
        if (earliest == text.length) return text.take(maxLength)
        val half = maxLength / 2
        val start = (earliest - half).coerceAtLeast(0)
        val end = (start + maxLength).coerceAtMost(text.length)
        var s = text.substring(start, end)
        if (start > 0) s = "…$s"
        if (end < text.length) s = "$s…"
        return s
    }

    companion object {
        private fun cleanPreview(raw: String): String {
            return stripSystemReminders(raw)
                .replace(Regex("[\r\n]+"), " ")      // newlines → space
                .replace(Regex("#{1,6}\\s"), "")      // headings: ## Title → Title
                .replace(Regex("\\*{1,3}|_{1,3}"), "")// bold/italic markers
                .replace(Regex("~~"), "")              // strikethrough
                .replace(Regex("`{1,3}"), "")          // inline/fenced code markers
                .replace(Regex("^\\s*[-*+]\\s", RegexOption.MULTILINE), "") // list bullets
                .replace(Regex("^\\s*\\d+\\.\\s", RegexOption.MULTILINE), "") // ordered list
                .replace(Regex("^>\\s?", RegexOption.MULTILINE), "")       // blockquote
                .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1") // [text](url) → text
                .replace(Regex("!\\[([^]]*)]\\([^)]+\\)"), "$1") // ![alt](url) → alt
                .replace(Regex("\\s{2,}"), " ")        // collapse whitespace
                .trim()
                .take(100)
        }

        /**
         * Build a short preview string for a `toolUse` value block. Used by the
         * session list when an assistant turn is mid-tool-call and has no text
         * part yet. Strategy mirrors iOS ChatStore.summarizeToolUse (T-ios-
         * session-last-message-tool-call):
         *   1. Prefer model-supplied `tool_title` (carried in the on-disk shape
         *      as `value.description`, or inside the embedded `input` JSON).
         *   2. Else pick the most meaningful arg per known tool family.
         *   3. Else fall back to `🔧 <toolName>`.
         * Output capped at 100 chars to match cleanPreview's text ceiling.
         */
        private fun summarizeToolUse(value: org.json.JSONObject): String {
            val toolName = value.optString("name", "")
            // `description` is where ChatViewModel persists the captured
            // tool_title (see writeAssistantParts / writeAssistantPartsForLive
            // in ChatViewModel.kt — both pass block.toolTitle into "description").
            val description = value.optString("description", "").trim()

            // `input` is stored as an escaped JSON STRING, not a nested object
            // (see ChatViewModel.kt:6491 / :6539). Parse defensively.
            val input: org.json.JSONObject = try {
                val raw = value.opt("input")
                when (raw) {
                    is org.json.JSONObject -> raw
                    is String -> if (raw.isBlank()) org.json.JSONObject() else org.json.JSONObject(raw)
                    else -> org.json.JSONObject()
                }
            } catch (_: Exception) {
                org.json.JSONObject()
            }

            fun str(key: String): String? {
                val v = input.optString(key, "").trim()
                return if (v.isEmpty()) null else v
            }
            fun cap(s: String, n: Int = 100): String =
                if (s.length > n) s.substring(0, n) + "…" else s

            // 1. tool_title — checked both on the outer `description` field and
            //    inside `input` (the model writes it into args; we mirror what
            //    iOS does and accept either location).
            val title = str("tool_title") ?: description.takeIf { it.isNotEmpty() }
            if (title != null) return cap(cleanPreview(title))

            // 2. per-tool key argument
            when (toolName) {
                "shell_execute" -> str("command")?.let { return cap(cleanPreview("$ $it")) }
                "file_read" -> str("path")?.let { return cap(cleanPreview("Reading $it")) }
                "file_write" -> str("path")?.let { return cap(cleanPreview("Writing $it")) }
                "file_edit" -> str("path")?.let { return cap(cleanPreview("Editing $it")) }
                "browser_use" -> {
                    val action = str("action") ?: "browse"
                    val url = str("url")
                    return if (url != null) cap(cleanPreview("$action $url"))
                    else cap(cleanPreview("browser_use $action"))
                }
                "memory_write" -> str("content")?.let { return cap(cleanPreview("memory_write: $it")) }
                "memory_get" -> {
                    val arr = input.optJSONArray("keywords")
                    if (arr != null && arr.length() > 0) {
                        val joined = buildString {
                            for (i in 0 until arr.length()) {
                                if (i > 0) append(", ")
                                append(arr.optString(i))
                            }
                        }
                        if (joined.isNotBlank()) return cap(cleanPreview("memory_get: $joined"))
                    }
                    str("keywords")?.let { return cap(cleanPreview("memory_get: $it")) }
                }
            }

            // 3. final fallback
            return cap("🔧 ${toolName.ifBlank { "tool" }}")
        }

        // `internal` so the restore path can rebuild a session's preview from the
        // messages it just imported — see BackupImporter [T-android-restore-preview].
        internal fun extractTextPreview(partsJson: String): String? {
            try {
                val array = org.json.JSONArray(partsJson)
                var hasMedia = false
                // T-android-session-last-message-tool-call: also track the most
                // recent tool_use so a mid-tool-call assistant turn (no text yet)
                // renders as a short tool summary instead of falling through to
                // "No messages yet" in the session list.
                var lastToolUse: org.json.JSONObject? = null
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val type = obj.optString("type")
                    if (type == "text") {
                        val text = obj.optString("value", "")
                        if (text.isNotBlank()) {
                            return cleanPreview(text)
                        }
                    } else if (type == "mediaRef") {
                        hasMedia = true
                    } else if (type == "toolUse") {
                        val v = obj.optJSONObject("value")
                        if (v != null) lastToolUse = v
                    }
                }
                if (hasMedia) return "[Image]"
                if (lastToolUse != null) return summarizeToolUse(lastToolUse)
            } catch (_: Exception) {
                if (partsJson.isNotBlank()) return cleanPreview(partsJson)
            }
            return null
        }
        // <system-reminder>...</system-reminder> blocks are runtime nudges
        // injected into user-role messages by the harness (e.g. task-tracker
        // reminders). They never represent what the user actually typed, so
        // they must not show up in the session-list "last message" preview.
        // DOTALL flag covers multi-line reminder bodies; reluctant
        // quantifier so back-to-back reminders don't merge into one match.
        private val SYSTEM_REMINDER_RE =
            Regex("""<system-reminder>.*?</system-reminder>""", RegexOption.DOT_MATCHES_ALL)

        internal fun stripSystemReminders(raw: String): String =
            SYSTEM_REMINDER_RE.replace(raw, "").trim()

        // T188: snippet/length caps mirror iOS SessionsOffload.m. 600 chars
        // is a balance between giving the agent enough context to
        // disambiguate similar messages and not blowing past the agent's
        // context budget on a long search result.
        internal const val SNIPPET_MAX = 600
        internal const val MESSAGE_TEXT_MAX = 600
        // [T-android-sessions-cli-full] Per-message cap when the caller passes
        // `--full` — matches iOS SessionsOffloadBridge's 50_000 and the CLI
        // help's documented upper bound. A single message beyond this is still
        // truncated and flagged with "truncated": true.
        internal const val MESSAGE_TEXT_MAX_FULL = 50_000

        // Issue #17 — page size for the chat loader. 200 rows per query
        // keeps a normal-shaped CursorWindow well under 2 MB while
        // still amortising query overhead for long sessions.
        private const val LOAD_PAGE_SIZE = 200

        // Issue #17 — hard cap on a single message's parts_json. 500_000
        // chars ≈ 500 KB ASCII (worst case ~2 MB UTF-8 for 4-byte runs;
        // still small enough that any single resulting row fits inside
        // a single CursorWindow). New oversize payloads (browser_use
        // dumps, paste-bomb tool_results) are truncated at insert time
        // and replaced with a single text part carrying a marker, so
        // they remain JSON-parseable downstream.
        internal const val MAX_MESSAGE_PARTS_JSON_LENGTH = 500_000

        internal fun buildTruncatedPartsJson(original: String): String {
            val source = MessagePartsCodec.provenanceOf(original)
            val keep = original.take(MAX_MESSAGE_PARTS_JSON_LENGTH)
            val marker = "\n\n[Content truncated at " +
                "${MAX_MESSAGE_PARTS_JSON_LENGTH / 1000} KB — original length " +
                "${original.length} chars]"
            val combined = keep + marker
            // Wrap in a single text part so JSONArray parsers (preview
            // extractor, search, exporter) see a well-formed payload. Re-add
            // the source marker separately because truncating raw JSON must not
            // silently turn a known row back into UNKNOWN.
            val truncated = org.json.JSONArray().put(
                org.json.JSONObject().put("type", "text").put("value", combined),
            ).toString()
            return MessagePartsCodec.withProvenance(truncated, source)
        }
    }
}

/** T188: shape of a session row surfaced to `minis-sessions-cli list`. */
data class SessionMeta(
    val id: String,
    val title: String?,
    val preview: String?,
    val source: String?,
    val startedAt: Long,    // ms — sessions.created_at
    val lastActive: Long,   // ms — sessions.updated_at
    val messageCount: Int,
)

/** T188: a single matching message returned by `minis-sessions-cli search`. */
data class MessageSearchMatch(
    val sessionId: String,
    val messageId: String,
    val role: String,
    val createdAt: Long,
    val snippet: String,
)

/** T188: a single message in the paginated transcript returned by
 *  `minis-sessions-cli messages`. */
data class MessagePageItem(
    val messageId: String,
    val role: String,
    val createdAt: Long,
    val text: String,
    // [T-android-sessions-cli-full] True when the stored text exceeded the
    // requested cap and [text] is a prefix. Surfaced as "truncated": true.
    val truncated: Boolean = false,
)

/**
 * One window of rows, plus the bookkeeping a caller needs to continue from
 * exactly where this window stopped.
 *
 * Rows are dropped during the parse — a blank `parts_json` extracts to no text,
 * and a LIKE hit on tool-call metadata produces no snippet — so the items that
 * survive are NOT a contiguous run of the underlying scan, and "the count of
 * items I got" cannot be turned back into "where the scan should resume".
 * [windowIndices] keeps that mapping, and [rowsExamined] keeps the other half.
 *
 * Both halves are needed because the two ways a page can be short mean opposite
 * things: the budget dropped items (there is more to send, right here), or the
 * scan ran out (there is not). Reporting only `items.size` conflates them.
 */
data class MessageWindow<T>(
    val items: List<T>,
    /** Parallel to [items]: each item's 0-based row index within this window. */
    val windowIndices: List<Int>,
    /** Rows consumed from the window, i.e. the index just past the last one read. */
    val rowsExamined: Int,
    /**
     * True when the scan stopped with rows still unexamined — either because it
     * filled the window, or because it hit its own cap before the window ended.
     */
    val moreMayRemain: Boolean,
)
