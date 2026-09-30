package com.openminis.app.feature.runtime

import org.json.JSONObject

/**
 * [T-android-parent-inbox-consumer] What a drained inbox envelope *means* to the
 * conversation that has to show it — and the decision of when it must reach the
 * MODEL rather than only the user.
 *
 * This class exists because of a hole rather than a preference. The runtime
 * writes parent notifications by itself (`SessionTreeRuntime.notifyParentOnStop`,
 * `deleteSubtree`, `purgeSubtrees`), `RuntimeSessionCoordinator.drainInbox` now
 * claims them, and the only place left that could turn an envelope into
 * something a human reads is `ChatViewModel` — a `ViewModel` whose constructor
 * needs the whole Android chat stack and which this module's unit-test source
 * set has no Robolectric to build. Keeping the mapping here, as a plain JVM
 * class with no Android import, is what makes "the child stopped" →
 * "a sentence naming that child" an *executed* fact in tests instead of a
 * source-text claim.
 *
 * ## Where the user-facing sentence comes from (the `payload` vs `taskIntent` decision)
 *
 * Both fields are available and they say different things:
 *
 *  - `taskIntent` is the structured record (`RuntimeSystemMessageMetadata.toJson`
 *    for stop notifications; a `kind`/`targetNodeId`/`affectedNodeIds` object for
 *    deletions). It is the machine-readable statement of WHAT happened and to
 *    WHOM, and it is what the tests in this repo already assert on.
 *  - `payload` is a single English sentence — `"Child <id> completed."`,
 *    `"Subtree <id> deleted."`, `"Subtree deletion completed."` — written for the
 *    MODEL (the child runner injects it as `[runtime-message:<delivery>] <payload>`).
 *    It names raw node ids and, for the two deletion kinds, does not distinguish
 *    the deleted agent from the deleting one.
 *
 * So the sentence here is composed from `taskIntent` — kind first, then the node
 * the kind is about — and `payload` is used only as the fallback for a kind this
 * build does not know. That direction is deliberate: a new runtime-installed
 * notification must still be *surfaced* (dropping it would recreate the very
 * black hole this feature closes) even though it cannot yet be phrased well.
 * The raw JSON is never put in front of the user.
 *
 * ## De-duplication, and its lifetime
 *
 * The claim lease is not a read receipt. `SessionTreeRuntime.claim` hands the
 * same envelope to the same claimant again after
 * [RuntimeSessionTree.MESSAGE_CLAIM_LEASE_MILLIS], because it must not lose a
 * message whose claimant died. A parent drain can legitimately run more than ten
 * minutes apart (load the conversation in the morning, finish a run in the
 * afternoon), so the renderer needs its own "already shown" memory:
 * [surfacedIds], keyed by envelope id.
 *
 * Its lifetime is ONE renderer instance, i.e. one `ChatViewModel`, i.e. one
 * in-process life of one conversation — and that is the correct length, not a
 * shortcut:
 *
 *  - The card this class feeds (`ChatViewModel.appendSystemInfo`) is NOT
 *    persisted; it exists only in the live message list. So while the instance
 *    lives there is exactly one visible card per envelope, which is the
 *    guarantee asked for.
 *  - After the process dies the card is gone from the transcript too. Suppressing
 *    the re-claim at that point would mean the notification is shown to no one,
 *    ever — so a fresh instance deliberately starts empty and re-surfaces
 *    whatever is still claimable.
 *
 * Bounded on purpose: ids are only remembered for envelopes that were actually
 * handed to a renderer, and a mailbox cannot grow without the runtime writing to
 * it.
 */
internal class RuntimeInboxSurfacing(
    /**
     * Human name of a conversation, by node id — normally the child session's
     * title. Returns null when there is no name to use, in which case the node id
     * is shown shortened instead of silently rendering an unnamed "a sub-agent".
     */
    private val displayNameOf: (String) -> String? = { null },
) {

    /** One drained envelope as the chat layer needs it. */
    internal data class Notice(
        /** The envelope this came from — a stable id the chat row can be keyed by. */
        val envelopeId: String,
        /** The neutral system-card line. Always non-blank. */
        val cardText: String,
        /**
         * A `<system-reminder>` block for the model, or null when the envelope is
         * a notice the model does not need (see [modelReminderFor]).
         */
        val modelReminder: String?,
    )

    private val surfacedIds = LinkedHashSet<String>()

    /**
     * Every conversation id [envelopes] refer to, so the caller can resolve
     * display names before rendering — including the node a `child_subtree_deleted`
     * envelope is ABOUT, which is not always the node it is FROM.
     */
    fun referencedNodeIds(envelopes: List<RuntimeEnvelope>): Set<String> {
        val ids = LinkedHashSet<String>()
        for (envelope in envelopes) {
            val metadata = metadataOf(envelope)
            ids += envelope.fromNodeId
            metadata?.optString("childNodeId")?.takeIf { it.isNotBlank() }?.let { ids += it }
            metadata?.optString("targetNodeId")?.takeIf { it.isNotBlank() }?.let { ids += it }
        }
        return ids
    }

    /**
     * Render [envelopes], skipping any envelope id already surfaced by THIS
     * instance. The order of [envelopes] is preserved, so a caller that drains in
     * claim order shows the notices in the order they happened.
     */
    fun noticesFor(envelopes: List<RuntimeEnvelope>): List<Notice> {
        val notices = ArrayList<Notice>(envelopes.size)
        for (envelope in envelopes) {
            if (!surfacedIds.add(envelope.id)) continue
            notices += Notice(
                envelopeId = envelope.id,
                cardText = cardTextFor(envelope).trim().take(MAX_CARD_CHARS),
                modelReminder = modelReminderFor(envelope),
            )
        }
        return notices
    }

    /** Is [id] already surfaced by this instance? Exposed for diagnostics and tests. */
    fun hasSurfaced(envelopeId: String): Boolean = envelopeId in surfacedIds

    // --------------------------------------------------------------- phrasing

    /**
     * The user-facing line for one envelope.
     *
     * Written to a budget rather than to taste: see [MAX_CARD_CHARS]. Which clause
     * comes first is therefore a decision, not an accident — the sub-agent and the
     * verb are at the FRONT, because that is the part `request.md:248` asks the
     * user to be told, and anything after it is the part that could be ellipsised
     * away on a narrow screen.
     *
     * Every branch reads the STRUCTURED record (`taskIntent`) and never the
     * model-facing payload, so a raw id or a raw English sentence written for the
     * model cannot reach the user by default — the payload is only the fallback for
     * a kind this build cannot phrase yet, where showing the runtime's own sentence
     * is better than silently dropping the notification.
     */
    private fun cardTextFor(envelope: RuntimeEnvelope): String {
        val metadata = metadataOf(envelope)
        return when (metadata?.optString("kind")) {
            KIND_CHILD_COMPLETED -> {
                val child = metadata.optString("childNodeId").ifBlank { envelope.fromNodeId }
                "Sub-agent ${label(child)} finished."
            }

            KIND_CHILD_ABNORMAL_STOP -> {
                val child = metadata.optString("childNodeId").ifBlank { envelope.fromNodeId }
                buildString {
                    append("Sub-agent ").append(label(child)).append(" stopped abnormally")
                    // request.md:248 names the failing request's status code as the
                    // first thing the parent must be told, and a bare status is a
                    // few characters — it fits inside the one-line budget below.
                    // The error body and the response headers are far too long for
                    // this row, so they ride the model reminder instead (see
                    // [modelReminderFor]); this row stays the headline.
                    val status = (metadata.opt("statusCode") as? Number)?.toInt()
                    if (status != null) append(" (HTTP ").append(status).append(')')
                    append('.')
                }
            }

            KIND_CHILD_STOPPED_BY_REQUEST -> {
                // [T-android-stop-request-kind] Somebody asked for this stop (the
                // user pressed 「停止」, an ancestor called `stop_descendant`, or the
                // conversation's subtree was deleted) — so the card must not borrow
                // the vocabulary of a crash. Three things follow from that, and all
                // three are deliberate:
                //
                //  - the verb is "stopped on request", not "stopped abnormally";
                //  - there is no `(HTTP nnn)` suffix, because a deliberate stop has
                //    no failing response behind it (a `CancellationException` is not
                //    an [LLMError], so [RuntimeSystemMessageMetadata] carries no
                //    status for it) — the suffix would name a status that does not
                //    exist;
                //  - no model reminder is produced (see [modelReminderFor]), so
                //    nobody is asked to "decide whether to restart it": the party
                //    that decided is the party that asked.
                val child = metadata.optString("childNodeId").ifBlank { envelope.fromNodeId }
                "Sub-agent ${label(child)} stopped on request."
            }

            KIND_CHILD_SUBTREE_DELETED -> {
                val target = metadata.optString("targetNodeId").ifBlank { envelope.fromNodeId }
                "Sub-agent ${label(target)} and its subtree deleted."
            }

            KIND_SUBTREE_DELETE_RESULT ->
                // The executor's own receipt for the deletion above. Named without
                // repeating WHICH subtree, because that card is already on screen —
                // two cards saying the same thing is how a notification system
                // starts reading as noise.
                "Subtree deletion confirmed."

            // A kind this build does not phrase yet. The runtime's own sentence is
            // the only honest thing left to show, and showing it beats dropping a
            // notification the user has no other way to learn about.
            else -> envelope.payload.ifBlank {
                "The runtime delivered a notification to this conversation."
            }
        }
    }

    /**
     * How a sub-agent is named in a card: its conversation title when the caller
     * could resolve one, otherwise a shortened node id.
     *
     * The two forms are mutually exclusive rather than combined, and that is a
     * budget decision: `"Fix parser" (a1b2c3d4)` costs about ten characters of
     * layout that the verb at the end of the sentence needs more ([MAX_CARD_CHARS]).
     * The title is what the user recognises from the session list, so it wins; the
     * id is the fallback that keeps an unresolvable sub-agent from rendering as a
     * blank.
     *
     * A title longer than [NAME_MAX_CHARS] is clipped rather than passed through,
     * for the same reason — an unbounded name would push "stopped abnormally" past
     * the ellipsis and leave the user with a name and no event.
     */
    private fun label(nodeId: String): String {
        val name = displayNameOf(nodeId)?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
        if (name.isEmpty()) return "\"${nodeId.take(SHORT_ID_CHARS)}\""
        val clipped = if (name.length <= NAME_MAX_CHARS) name else name.take(NAME_MAX_CHARS - 1) + "…"
        return "\"$clipped\""
    }

    // ------------------------------------------------------------ model traffic

    /**
     * The `<system-reminder>` this envelope must add to the model's context, or
     * null when it must not.
     *
     * `NOTIFY` is a NOTICE: "this child stopped", "this subtree was deleted". It
     * is surfaced as a card and nothing else, because the model already learns
     * the consequence through `ChatViewModel.publishDelegatedChildOutcome` (the
     * child's output plus, for an abnormal end, its own reminder), and a second
     * reminder about the same event is noise the parent then has to reconcile.
     *
     * `STEER`, `TEAM_PEER` and `QUEUE` are the opposite: they are MESSAGES other
     * agents addressed TO this agent — "change what you are doing", "here is a
     * request", "here is a turn to run". They are claimable only once, and this
     * drain is the claimant. Before this feature they sat unread in the mailbox;
     * after it they would be consumed and dropped on the floor, which would make
     * the hole bigger than the one being fixed. So they are injected.
     */
    private fun modelReminderFor(envelope: RuntimeEnvelope): String? {
        val metadata = metadataOf(envelope)
        if (envelope.delivery == RuntimeDelivery.NOTIFY) {
            // A NOTICE normally stays a card. The one exception is the abnormal
            // stop: `request.md:248` requires the failing request's status code,
            // error response and response headers to be reported to the parent,
            // and a one-line card that is ellipsised by design cannot carry an
            // error body. The model reminder is the only channel that reaches the
            // parent agent, so the diagnostic detail belongs there.
            return if (metadata?.optString("kind") == KIND_CHILD_ABNORMAL_STOP) {
                abnormalStopReminder(envelope, metadata)
            } else {
                null
            }
        }
        val sender = displayNameOf(envelope.fromNodeId)?.trim().orEmpty()
            .ifEmpty { envelope.fromNodeId.take(SHORT_ID_CHARS) }
        val payload = envelope.payload
            .replace("</system-reminder>", "<\u200b/system-reminder>")
            .take(MODEL_PAYLOAD_MAX_CHARS)
        return buildString {
            append("<system-reminder>[runtime-message:")
            append(envelope.delivery.name)
            append("] A message addressed to you arrived in this conversation's runtime mailbox ")
            append("while no running turn was reading it, so no turn has answered it yet. ")
            append("Sender: ").append(sender).append(" (conversation ").append(envelope.fromNodeId).append("). ")
            append("Treat it as inbound agent traffic from that conversation and decide what to do with it.")
            append("\n\n").append(payload)
            append("</system-reminder>")
        }
    }

    /**
     * The `<system-reminder>` for a sub-agent that stopped abnormally.
     *
     * Carries what the one-line card cannot: the failing request's status code,
     * error response and response headers, plus the tail of the body the child
     * last sent. Unlike the other NOTIFY notices this one is not redundant with
     * `ChatViewModel.publishDelegatedChildOutcome` — that reminder reports only
     * the child's own `end_reason`, so without this block the HTTP diagnostics
     * would be collected by the provider, threaded through the stop report and
     * then shown to nobody.
     *
     * Every diagnostic field is optional: a child stopped by lease expiry or
     * before its first model call has no HTTP response at all.
     */
    private fun abnormalStopReminder(envelope: RuntimeEnvelope, metadata: JSONObject): String {
        val child = metadata.optString("childNodeId").ifBlank { envelope.fromNodeId }
        val status = (metadata.opt("statusCode") as? Number)?.toInt()
        val debugInfo = metadata.optString("debugInfo").trim()
        val errorResponse = metadata.optString("errorResponse").trim()
        val bodyTail = metadata.optString("lastSentBodyTail").trim()
        val headers = runCatching {
            metadata.optJSONObject("responseHeaders")?.let { json ->
                json.keys().asSequence().sorted()
                    .joinToString("\n") { "$it: ${json.optString(it)}" }
            }
        }.getOrNull().orEmpty()
        return buildString {
            append("<system-reminder>The sub-agent ").append(child)
            append(" in this conversation stopped abnormally")
            if (status != null) append(" (HTTP ").append(status).append(')')
            append(". ")
            append("Decide whether to restart it, redo the work, or continue from what it returned.")
            if (status != null) append("\nstatus: ").append(status)
            if (debugInfo.isNotEmpty()) {
                append("\nreason: ").append(debugInfo.take(MODEL_DIAGNOSTIC_FIELD_CHARS))
            }
            if (errorResponse.isNotEmpty()) {
                append("\nerror response: ").append(errorResponse.take(MODEL_DIAGNOSTIC_FIELD_CHARS))
            }
            if (headers.isNotEmpty()) {
                append("\nresponse headers:\n").append(headers.take(MODEL_DIAGNOSTIC_FIELD_CHARS))
            }
            if (bodyTail.isNotEmpty()) {
                append("\nlast sent body (tail): ").append(bodyTail.take(MODEL_DIAGNOSTIC_FIELD_CHARS))
            }
            append("</system-reminder>")
        }
    }

    private fun metadataOf(envelope: RuntimeEnvelope): JSONObject? {
        val raw = envelope.taskIntent
        if (raw.isBlank()) return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    internal companion object {
        const val KIND_CHILD_COMPLETED = "child_completed"
        const val KIND_CHILD_ABNORMAL_STOP = "child_abnormal_stop"

        /**
         * [T-android-stop-request-kind] A child that stopped because it was asked to.
         *
         * Separate from [KIND_CHILD_ABNORMAL_STOP] because the two describe different
         * events that happen to share one bit (`completedNormally = false` in the stop
         * report): one is a failure, the other is an obeyed instruction.
         *
         * The token lives here rather than next to the code that writes it, because
         * `SessionTreeRuntime` compiles standalone (no Android, no other class of this
         * feature) and the runtime harnesses rely on that — so it writes the literal.
         * `RuntimeStoppedByRequestNotificationTest` asserts the emitted kind and the
         * card drawn from it in the same run, which is what keeps the two in step.
         */
        const val KIND_CHILD_STOPPED_BY_REQUEST = "child_stopped_by_request"
        const val KIND_CHILD_SUBTREE_DELETED = "child_subtree_deleted"
        const val KIND_SUBTREE_DELETE_RESULT = "subtree_delete_result"

        /** Matches the `take(8)` convention this codebase already uses for ids. */
        private const val SHORT_ID_CHARS = 8

        /** Longest conversation title that may appear inside a card. See [label]. */
        private const val NAME_MAX_CHARS = 24

        private const val MODEL_PAYLOAD_MAX_CHARS = 400

        /**
         * Per-field cap on the diagnostics carried by [abnormalStopReminder].
         *
         * An error body can be a whole HTML page and a response can carry dozens of
         * headers; the reminder is injected into the parent's context on every
         * drain, so an unbounded field would be a context bomb rather than a
         * debugging aid. Truncation is per field so a huge `errorResponse` cannot
         * consume the budget of the headers that come after it.
         */
        private const val MODEL_DIAGNOSTIC_FIELD_CHARS = 400

        /**
         * Upper bound on a card line.
         *
         * Not cosmetic: `FallbackInfoBlock` — the row every one of these cards
         * renders through — draws its label with `maxLines = 1` and
         * `TextOverflow.Ellipsis` at 10sp, so a longer sentence is not "longer",
         * it is *truncated*, and whatever the runtime's structured record said
         * after that point never reaches the user at all. [MAX_CARD_CHARS] is
         * roughly that row's capacity on a phone, and the per-kind phrasing above
         * is written to fit inside it; the assertion in
         * `RuntimeParentInboxDrainTest` pins it so a future change cannot quietly
         * push the interesting half of a sentence past the ellipsis.
         */
        const val MAX_CARD_CHARS = 64
    }
}
