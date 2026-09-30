package com.openminis.app.feature.runtime

/**
 * [T-android-conversation-id-prefix] The one place that decides what a
 * *conversation ID* looks like when a MODEL sees it.
 *
 * Requirement (request.md:136, verbatim): 「我们这里就需要有一些提示词工程就是我们
 * 我们的哈希ID，包括子代理那个哈希ID，**他ID前面都要有一个特定的前缀**，然后模型识别
 * 到……我要让他知道他看到了这一组哈希id然后带有特定前缀意思就是是我们的……我们这个
 * 应用环境里面专用的对话id……所以可以它是可以拿去查询对应的对话内容的」.
 *
 * Three properties the requirement asks for, and how each is honoured here:
 *
 *  1. **One ID, not two.** The requirement is explicit that the conversation ID
 *     and a sub-agent's "hash ID" are 「实际上是同一个ID」. That is already true
 *     underneath: [RuntimeSessionCoordinator.startChild] passes the CHILD SESSION
 *     id straight through as the node id (`SessionTreeRuntime.createChild`'s
 *     `childId`), so a child's runtime node id IS its conversation id. There is
 *     no second numbering scheme to reconcile — only a naming job.
 *
 *  2. **A prefix models can recognise.** [PREFIX] is added at the SINGLE
 *     boundary every model-facing string goes through, so "the ID the model read
 *     in tool A" and "the ID the model was told about in the system prompt" are
 *     the same string by construction rather than by discipline.
 *
 *  3. **The stored ID is untouched.** Deliberately a DISPLAY transform. Putting
 *     the prefix into `ChatSessionEntity.id` would reach the Room primary key,
 *     navigation routes, the `__new__` draft sentinel, the `sessionId#run-N`
 *     root-generation join, backup export/import matching and every
 *     `take(8)`-style prefix match in the tree — a migration with no bearing on
 *     what the requirement actually asks for, which is that the model
 *     *recognise* the ID. [conversationIdOf] and [strip] convert in both
 *     directions, so every tool that accepts an id can accept exactly what the
 *     model was handed.
 */
object ConversationIdProtocol {

    /**
     * The prefix. Chosen so it cannot be confused with either half of the
     * system it sits in front of:
     *
     *  - `minis` names the product (OpenMinis), so a model reading it in a
     *    transcript can attribute it without a lookup table.
     *  - `conv` names the OBJECT (a conversation), which is what the requirement
     *    calls the thing — not "session", not "node", not "agent", all of which
     *    are internal names a model has no reason to know.
     *  - the trailing `-` is the separator. It is what makes [strip]
     *    unambiguous AND makes collision with a raw UUID impossible: a bare
     *    UUID is `[0-9a-f-]{36}` and can never contain the letter `s`, let alone
     *    the literal `minis-conv-`. A prefixed string therefore always reveals
     *    which form it is, with no heuristic and no length check.
     */
    const val PREFIX = "minis-conv-"

    /**
     * The sibling prefix for a single MESSAGE's id, covering the other half of
     * the same requirement (request.md:136 opens with 「它有一个复制消息ID，或者或者
     * 叫做复制对话ID」).
     *
     * A separate prefix rather than reusing [PREFIX]: the two ids are handed to
     * a model in the same breath (a query result carries both the conversation it
     * came from and the id of each message), so they must be distinguishable by
     * looking at them. `minis-msg-` is exactly as collision-proof against a raw
     * UUID as [PREFIX] is, for the same reason.
     */
    const val MESSAGE_PREFIX = "minis-msg-"

    /**
     * Separator between a session id and its ROOT RE-RUN generation, produced by
     * [RuntimeSessionCoordinator.startRoot] when an existing root node had
     * already reached a terminal status (`"$sessionId#run-${++generation}"`).
     *
     * It is a **runtime generation counter, not part of the conversation's
     * identity**: re-running one conversation's root does not turn it into a
     * different conversation, and a model that was handed `...#run-2` could not
     * tell whether it was looking at another conversation or the same one again.
     * [conversationIdOf] therefore drops it, so the ID the model sees is always
     * the conversation, never the run.
     *
     * The `#run-N` node ids keep working as runtime ids — this only changes what
     * is *shown*.
     */
    private const val RUN_GENERATION_MARKER = "#run-"

    /**
     * The stable conversation identity behind any runtime id: the `#run-N`
     * generation suffix removed, surrounding whitespace trimmed.
     *
     * Idempotent, and total on blank input (`""` in → `""` out) so callers can
     * hand it an argument they have not validated yet.
     */
    fun conversationIdOf(raw: String): String =
        raw.trim().substringBefore(RUN_GENERATION_MARKER).trim()

    /**
     * What the MODEL should see for [raw]. Idempotent — feeding an already
     * prefixed id back in returns it unchanged, so an exit that normalises
     * before formatting cannot double the prefix.
     *
     * Blank stays blank: a missing id must render as missing, never as a
     * prefixed empty string that looks like a real (but broken) id.
     */
    fun prefixedConversationId(raw: String): String {
        val id = conversationIdOf(strip(raw))
        return if (id.isEmpty()) "" else PREFIX + id
    }

    /**
     * [T-android-conversation-id-query] The OTHER prefixed id this app puts in
     * front of a model, which must never be mistaken for [PREFIX].
     *
     * [RuntimeConversationAddress] derives `openminis-conv:<sha256>` from a
     * session id and is what the runtime routes messages and context
     * attachments by (`RuntimeCommunicationGateway`, `RuntimeCommunicationFileStore`,
     * `RuntimeContextAttachment`). Its string legitimately reaches model-visible
     * text: [RuntimeContextAttachments.capsule] writes it into the summary
     * recorded for a delegated child, and `communication_query` returns peer
     * addresses.
     *
     * The two look alike and mean different things, so [isRuntimeAddress] exists
     * to tell them apart at the ONE boundary that matters: a tool argument. The
     * hash is one-way (SHA-256), so an address cannot be turned back into a
     * session id and therefore cannot be used to read a conversation — a read
     * tool handed one must say so rather than silently returning "no messages",
     * which would read as "that conversation is empty".
     */
    const val RUNTIME_ADDRESS_PREFIX = "openminis-conv:"

    /**
     * True when [text] is a runtime ROUTE address rather than a conversation id.
     *
     * Checked before [PREFIX] everywhere, because `openminis-conv:` also contains
     * the substring `minis-conv` — a substring test is NOT a safe way to tell
     * these apart, and only an exact leading-token check is.
     */
    fun isRuntimeAddress(text: String): Boolean =
        text.trim().startsWith(RUNTIME_ADDRESS_PREFIX)

    /** True when [text] already carries [PREFIX] (leading whitespace ignored). */
    fun isPrefixed(text: String): Boolean = text.trim().startsWith(PREFIX)

    /**
     * The inverse of [prefixedConversationId]: accepts **either** form and
     * returns the bare conversation id, so a tool argument works whether the
     * model copied the prefixed string or passed the raw one.
     *
     * This is what lets the prefix be additive rather than breaking: tool
     * schemas can keep documenting `target_session_id` / `child_session_id` /
     * `conversation_id`, and both spellings resolve to the same node.
     */
    fun strip(text: String): String {
        val trimmed = text.trim()
        val withoutPrefix = if (isPrefixed(trimmed)) trimmed.substring(PREFIX.length) else trimmed
        return conversationIdOf(withoutPrefix)
    }

    /**
     * The message-id counterpart of [prefixedConversationId]: what a MODEL should
     * see for one message row's id. Idempotent and blank-preserving.
     */
    fun prefixedMessageId(raw: String): String {
        val id = stripMessageId(raw)
        return if (id.isEmpty()) "" else MESSAGE_PREFIX + id
    }

    /**
     * Accepts **either** form of a message id and returns the bare row id, so
     * `conversation_detail` works whether the model passed the prefixed string it
     * was handed or the raw id it saw somewhere internal.
     */
    fun stripMessageId(text: String): String {
        val trimmed = text.trim()
        return if (trimmed.startsWith(MESSAGE_PREFIX)) {
            trimmed.substring(MESSAGE_PREFIX.length).trim()
        } else {
            trimmed
        }
    }

    /**
     * The birth injection that teaches the model what the prefix means.
     *
     * Language: English, matching every other model-facing injected block in
     * this feature package (`ChildCompletionProtocol.systemInstruction` /
     * `MESSAGE_REMINDER`, the `<system-reminder>` re-entry rows). A second
     * language here would make the same concept read as two different things
     * depending on which injection a model happened to see.
     *
     * The three facts the requirement asks this text to carry, in order:
     * what the prefix means, that such an ID names a conversation inside THIS
     * app, and that it can be taken to the runtime to read that conversation —
     * progressively, one bounded page at a time, because the requirement's
     * stated reason for the whole feature is 「如果一次性把所有的对话内容都塞回给
     * 这个工具调用里面那么模型可能会一下把上下文给搞炸掉」.
     */
    fun systemInstruction(rawSessionId: String): String {
        val own = prefixedConversationId(rawSessionId)
        return buildString {
            append("[conversation-id] ")
            if (own.isNotEmpty()) {
                append("Your own conversation ID is ").append(own).append(". ")
            }
            append(
                "Every conversation in this app — the main agent, each delegated sub-agent, " +
                    "and each related main agent — has an ID that begins with `" + PREFIX + "`. " +
                    "That prefix marks the app's own conversation IDs, so an ID carrying it can be " +
                    "recognised directly instead of being guessed at, and it is the same ID the " +
                    "runtime tree reports for that conversation.",
            )
            append(
                "\n- To read what another conversation contains, pass its `" + PREFIX + "` ID as " +
                    "the `conversation_id` argument of `conversation_query` (with `conversation_detail` " +
                    "for one message). This works for conversations you share no ancestry with — you do " +
                    "not need to be its parent, and no handshake is required.",
            )
            append(
                "\n- Reads are progressive: each call returns one bounded page plus a `next_cursor`. " +
                    "Search or page with `query`/`cursor` and pull only what you need, exactly like " +
                    "querying a database — never try to pull a whole conversation in one call, because " +
                    "an unbounded dump is what floods your context window.",
            )
            // [T-android-conversation-id-query] Two prefixed forms exist and they
            // are NOT two conversations. A model that conflates them would treat
            // one conversation as two, so the instruction names both shapes
            // literally instead of leaving it to inference.
            append(
                "\n- One disambiguation, because you will meet two prefixed id forms and they are not " +
                    "two conversations. `" + PREFIX + "<uuid>` (and `" + MESSAGE_PREFIX + "<uuid>` for a " +
                    "single message) identify a conversation and a message, and these are what " +
                    "`conversation_query` accepts. A separate form, `" +
                    RUNTIME_ADDRESS_PREFIX + "<sha256>`, is the runtime's ROUTING address for a " +
                    "conversation — it appears in the context attachments a dispatcher shares and in " +
                    "`communication_query` results. The route address is a one-way hash, so it cannot " +
                    "be turned back into a conversation and cannot be read with: when you need to read " +
                    "content, use the `" + PREFIX + "` form.",
            )
        }
    }
}
