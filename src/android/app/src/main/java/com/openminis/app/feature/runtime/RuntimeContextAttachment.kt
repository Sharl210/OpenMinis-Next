package com.openminis.app.feature.runtime

/**
 * What a dispatcher asked to share with the peer it is dispatching to.
 *
 * This is the REQUEST half and it carries no text: the sender names a
 * selection, never the messages themselves. The requirement behind it, from
 * `plans/ULW-2026-09-25-01/request.md:138`:
 *
 *   「派遣方，可以主动选择分享我们当前绘画的哪些上下文，或者说选择直接主动全选」
 *
 * The second half of the same requirement is why nothing here can hold message
 * text — the attachment must be a reference the receiver pulls from, not a
 * transcript the sender pushes:
 *
 *   「附加过去的，并不是以文字消息附加过去的……接收方可以用这个对话ID向我们运
 *     行时中心去查询，然后查询对应指定的一些索引号……就不会一下子把上下文给撑
 *     爆如果直接纯文本硬塞的话」
 */
sealed interface RuntimeContextShareRequest {
    /** "Share my whole conversation" — `--share=all`. */
    data object All : RuntimeContextShareRequest

    /**
     * "Share exactly these messages" — `--share=0,7,9`. Zero-based positions in
     * the sender's own conversation, oldest first.
     */
    data class Selected(val indices: List<Int>) : RuntimeContextShareRequest
}

/**
 * Which messages of the sender's conversation an attachment points at.
 *
 * [All] keeps the COUNT rather than an expanded list so a 400-message
 * conversation costs the same one integer as a one-message conversation. That
 * is the point of the reference form; expanding it here would be the "纯文本硬
 * 塞" failure with extra steps.
 */
sealed interface RuntimeContextSelection {
    data class All(val messageCount: Int) : RuntimeContextSelection {
        init {
            require(messageCount >= 0) { "messageCount must be non-negative" }
        }
    }

    data class Indices(val indices: List<Int>) : RuntimeContextSelection {
        init {
            require(indices.isNotEmpty()) { "an attachment must reference at least one message" }
            require(indices.all { it >= 0 }) { "message indices are zero-based and cannot be negative" }
            require(indices == indices.distinct().sorted()) {
                "message indices must be unique and ascending"
            }
        }
    }
}

/**
 * A reference to messages the sender offered, identified by the sender's own
 * conversation locator plus the indices into it.
 *
 * This type deliberately has NO body/text/content field, and adding one would
 * defeat its purpose: a receiver with a small context window reads only the
 * indices it can afford (`RuntimeCommunicationQuery` is the read side), whereas
 * an inlined transcript is charged to every receiver whether or not it needed
 * any of it. `referentialOnly` states that invariant for callers and tests.
 */
data class RuntimeContextAttachment(
    /** The sender's conversation locator — the "对话ID" the receiver queries by. */
    val conversationId: RuntimeConversationAddress,
    val selection: RuntimeContextSelection,
) {
    /** The referenced positions, expanded only when small enough to be a list. */
    val indices: List<Int>
        get() = when (val current = selection) {
            is RuntimeContextSelection.All -> (0 until current.messageCount).toList()
            is RuntimeContextSelection.Indices -> current.indices
        }

    /** How many messages the receiver was offered. */
    val size: Int
        get() = when (val current = selection) {
            is RuntimeContextSelection.All -> current.messageCount
            is RuntimeContextSelection.Indices -> current.indices.size
        }

    /** Always true: this type cannot carry message text. See the class KDoc. */
    val referentialOnly: Boolean
        get() = true
}

/**
 * Turns a dispatcher's [RuntimeContextShareRequest] into the reference that is
 * recorded alongside the message.
 *
 * [transcript] is accepted for exactly two things: sizing "all", and dropping
 * indices the sender's conversation no longer has. Its CONTENTS are never read
 * into the result — see [capsule] for the only text this module produces, and
 * note that it is derived from the attachment alone.
 */
object RuntimeContextAttachments {

    /**
     * Resolve [request] against the sender's own conversation.
     *
     * Returns null when there is nothing to attach: no request, an empty
     * conversation, or a selection whose every index is out of range. A null is
     * the honest answer in all three cases — an attachment referencing nothing
     * would tell the receiver to query for messages that do not exist.
     */
    fun resolve(
        request: RuntimeContextShareRequest?,
        conversationId: RuntimeConversationAddress,
        transcript: List<String>,
    ): RuntimeContextAttachment? = when (request) {
        null -> null
        is RuntimeContextShareRequest.All ->
            if (transcript.isEmpty()) {
                null
            } else {
                RuntimeContextAttachment(
                    conversationId = conversationId,
                    selection = RuntimeContextSelection.All(transcript.size),
                )
            }
        is RuntimeContextShareRequest.Selected -> {
            val inRange = request.indices.filter { it in transcript.indices }.distinct().sorted()
            if (inRange.isEmpty()) {
                null
            } else {
                RuntimeContextAttachment(
                    conversationId = conversationId,
                    selection = RuntimeContextSelection.Indices(inRange),
                )
            }
        }
    }

    /**
     * The receiver-facing description of an attachment: the conversation
     * locator and the index list, and nothing else.
     *
     * Derived from the attachment alone — this function cannot see the
     * transcript, which is what makes "the record carries a reference, not the
     * context" a property of the code rather than a promise. Long selections
     * collapse to a count so the capsule always fits
     * [RuntimeCommunicationMetadata.MAX_SUMMARY_CHARS].
     */
    fun capsule(attachment: RuntimeContextAttachment): String = buildString {
        append("attached context ")
        append(attachment.conversationId.value)
        append(" indices=")
        if (attachment.size <= MAX_LISTED_INDICES) {
            append(attachment.indices.joinToString(","))
        } else {
            append("<all ").append(attachment.size).append(" messages>")
        }
        append(" (")
        append(attachment.size)
        append(" message(s) referenced; text is not included)")
    }

    /**
     * Above this many referenced messages the capsule lists a count instead of
     * every index, so the summary cap cannot silently truncate the locator.
     */
    const val MAX_LISTED_INDICES = 64
}
