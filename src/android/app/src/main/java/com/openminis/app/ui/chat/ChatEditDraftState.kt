package com.openminis.app.ui.chat

/**
 * The composer has two independent buffers while a message is being edited:
 * the ordinary draft the user had before entering edit mode, and one singleton
 * edit draft for the selected message.  This model deliberately contains no
 * ViewModel, Compose, persistence, send, retry, or delete behavior so it can be
 * integrated by the chat UI without changing those policies accidentally.
 */
data class ChatDraftSnapshot(
    val text: String = "",
    val attachments: List<InputAttachment> = emptyList(),
) {
    /** Prevent a caller-owned mutable list from becoming part of a snapshot. */
    fun frozen(): ChatDraftSnapshot = copy(attachments = attachments.toList())
}

enum class EditCommitMode {
    /** Replace the selected message in place; this is not a new send. */
    UPDATE_IN_PLACE,
}

/**
 * The outcome of a commit. The "no send / no retry / no deletion of later
 * messages" policy is carried by [mode] (`UPDATE_IN_PLACE`) and stated in
 * [ChatEditDraftState.commitEditing]'s contract — it used to also be mirrored
 * here as three `get() = false` markers, which no production code read and
 * which could not fail, so they have been removed rather than left to read as
 * guarantees that something was testing.
 */
data class EditCommitResult(
    val messageId: String,
    val replacement: ChatDraftSnapshot,
    val mode: EditCommitMode = EditCommitMode.UPDATE_IN_PLACE,
)

/**
 * Stateful, single-edit composer model.
 *
 * Entering edit mode saves the current ordinary draft and swaps in the target
 * message snapshot.  Entering another target while already editing only
 * replaces the singleton edit buffer; it never overwrites the saved ordinary
 * draft.  Exit and commit both restore the saved ordinary draft.
 */
class ChatEditDraftState(initialDraft: ChatDraftSnapshot = ChatDraftSnapshot()) {
    private var ordinaryDraft: ChatDraftSnapshot = initialDraft.frozen()
    private var savedOrdinaryDraft: ChatDraftSnapshot? = null
    private var activeEdit: ActiveEdit? = null

    private data class ActiveEdit(
        val messageId: String,
        val draft: ChatDraftSnapshot,
    )

    val editingMessageId: String?
        get() = activeEdit?.messageId

    val isEditing: Boolean
        get() = activeEdit != null

    /** The buffer the composer should currently render. */
    fun currentDraft(): ChatDraftSnapshot =
        (activeEdit?.draft ?: ordinaryDraft).frozen()

    /** The ordinary buffer, even while an edit buffer is active. */
    fun ordinaryDraft(): ChatDraftSnapshot = ordinaryDraft.frozen()

    /** The singleton edit buffer, or null outside edit mode. */
    fun editDraft(): ChatDraftSnapshot? = activeEdit?.draft?.frozen()

    /** Replace the ordinary buffer before entering edit mode. */
    fun setOrdinaryDraft(snapshot: ChatDraftSnapshot) {
        check(activeEdit == null) { "cannot replace ordinary draft while editing" }
        ordinaryDraft = snapshot.frozen()
    }

    /** Update whichever buffer the composer is currently displaying. */
    fun updateCurrentDraft(text: String, attachments: List<InputAttachment>) {
        val next = ChatDraftSnapshot(text, attachments).frozen()
        val edit = activeEdit
        if (edit == null) ordinaryDraft = next
        else activeEdit = edit.copy(draft = next)
    }

    /**
     * Enter edit mode, or switch the existing singleton edit target.
     * [messageId] is required because the eventual commit is an in-place update.
     */
    fun beginEditing(messageId: String, target: ChatDraftSnapshot): ChatDraftSnapshot {
        require(messageId.isNotBlank()) { "messageId must not be blank" }
        val next = target.frozen()
        if (activeEdit == null) savedOrdinaryDraft = ordinaryDraft.frozen()
        activeEdit = ActiveEdit(messageId, next)
        return next.frozen()
    }

    /** Switch only the edit buffer; the ordinary draft remains untouched. */
    fun switchEditingTarget(messageId: String, target: ChatDraftSnapshot): ChatDraftSnapshot {
        check(activeEdit != null) { "cannot switch an edit target outside edit mode" }
        require(messageId.isNotBlank()) { "messageId must not be blank" }
        val next = target.frozen()
        activeEdit = ActiveEdit(messageId, next)
        return next.frozen()
    }

    /** Leave edit mode without sending or changing the selected message. */
    fun exitEditing(): ChatDraftSnapshot {
        val restored = savedOrdinaryDraft ?: ordinaryDraft
        ordinaryDraft = restored.frozen()
        savedOrdinaryDraft = null
        activeEdit = null
        return ordinaryDraft.frozen()
    }

    /**
     * Commit the selected message as UPDATE_IN_PLACE and restore the ordinary
     * draft. No send, retry, or deletion of later messages is performed here.
     */
    fun commitEditing(): EditCommitResult {
        val edit = checkNotNull(activeEdit) { "cannot commit outside edit mode" }
        val result = EditCommitResult(edit.messageId, edit.draft.frozen())
        val restored = savedOrdinaryDraft ?: ordinaryDraft
        ordinaryDraft = restored.frozen()
        savedOrdinaryDraft = null
        activeEdit = null
        return result
    }
}

enum class QueuedPromptDelivery {
    QUEUE,
    STEER,
}

enum class QueuedPromptLifecycle {
    ENQUEUED,
    CLAIMED,
    CONSUMED,
    WITHDRAWN_EDIT,
}

data class QueuedPromptState(
    val messageId: String,
    val delivery: QueuedPromptDelivery,
    val lifecycle: QueuedPromptLifecycle = QueuedPromptLifecycle.ENQUEUED,
) {
    init {
        require(messageId.isNotBlank()) { "messageId must not be blank" }
    }

    fun claim(): QueuedPromptState {
        check(lifecycle == QueuedPromptLifecycle.ENQUEUED) {
            "only an enqueued prompt can be claimed"
        }
        return copy(lifecycle = QueuedPromptLifecycle.CLAIMED)
    }

    fun consume(): QueuedPromptState {
        check(lifecycle == QueuedPromptLifecycle.CLAIMED) {
            "only a claimed prompt can be consumed"
        }
        return copy(lifecycle = QueuedPromptLifecycle.CONSUMED)
    }

    /**
     * Edit withdrawal is intentionally narrower than generic cancellation:
     * only an unconsumed ENQUEUED prompt may become WITHDRAWN_EDIT. A claimed
     * or consumed prompt is immutable from this operation's perspective.
     */
    fun withdrawForEdit(): QueuedPromptWithdrawal {
        return if (lifecycle == QueuedPromptLifecycle.ENQUEUED) {
            QueuedPromptWithdrawal(
                accepted = true,
                state = copy(lifecycle = QueuedPromptLifecycle.WITHDRAWN_EDIT),
            )
        } else {
            QueuedPromptWithdrawal(
                accepted = false,
                state = this,
                reason = "only ENQUEUED prompts can be withdrawn for edit",
            )
        }
    }
}

data class QueuedPromptWithdrawal(
    val accepted: Boolean,
    val state: QueuedPromptState,
    val reason: String? = null,
)

/**
 * Keyed lifecycle ledger shared by QUEUE and STEER deliveries. The lock makes
 * claim and withdrawal a single winner; a failed execution can roll back its
 * claim without losing the prompt.
 */
class QueuedPromptLedger {
    private val states = LinkedHashMap<String, QueuedPromptState>()

    @Synchronized
    fun register(promptId: String, messageId: String, delivery: QueuedPromptDelivery) {
        require(promptId.isNotBlank()) { "promptId must not be blank" }
        check(promptId !in states) { "prompt already registered: $promptId" }
        states[promptId] = QueuedPromptState(messageId, delivery)
    }

    @Synchronized
    fun state(promptId: String): QueuedPromptState? = states[promptId]

    @Synchronized
    fun claim(promptId: String): Boolean {
        val current = states[promptId] ?: return false
        if (current.lifecycle != QueuedPromptLifecycle.ENQUEUED) return false
        states[promptId] = current.claim()
        return true
    }

    @Synchronized
    fun rollbackClaim(promptId: String): Boolean {
        val current = states[promptId] ?: return false
        if (current.lifecycle != QueuedPromptLifecycle.CLAIMED) return false
        states[promptId] = current.copy(lifecycle = QueuedPromptLifecycle.ENQUEUED)
        return true
    }

    @Synchronized
    fun consume(promptId: String): Boolean {
        val current = states[promptId] ?: return false
        if (current.lifecycle != QueuedPromptLifecycle.CLAIMED) return false
        states[promptId] = current.consume()
        return true
    }

    @Synchronized
    fun withdrawForEdit(promptId: String): QueuedPromptWithdrawal? {
        val current = states[promptId] ?: return null
        val result = current.withdrawForEdit()
        if (result.accepted) states[promptId] = result.state
        return result
    }

    @Synchronized
    fun restoreEdited(promptId: String): Boolean {
        val current = states[promptId] ?: return false
        if (current.lifecycle != QueuedPromptLifecycle.WITHDRAWN_EDIT) return false
        states[promptId] = current.copy(lifecycle = QueuedPromptLifecycle.ENQUEUED)
        return true
    }
}
