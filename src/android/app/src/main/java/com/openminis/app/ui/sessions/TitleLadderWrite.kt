package com.openminis.app.ui.sessions

import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.repository.ChatRepository

/**
 * [R55②] Record the title a model produced, together with the model that produced it.
 *
 * Top-level and `internal` for the same reason [dispatchTitleOverLadderIn] is:
 * `SessionListViewModel` cannot be constructed in this module's unit-test source
 * set — no Robolectric, and its `ProviderRepository` opens a Room database and
 * reads `SharedPreferences` — so the write a successful title walk implies is
 * separated from the ViewModel around it and executed directly by the JVM tests,
 * against a real [ChatRepository] over a recording `ChatDao`. The ViewModel still
 * owns the decision of *which* entry to write; this owns the shape of the write,
 * so "which model is the session row credited to" is a fact a test can observe
 * instead of a claim a test can only re-state.
 *
 * [entry] is the candidate that ACTUALLY answered — `TitleLadderOutcome.Written
 * .entry`, the entry the ladder returned. It is deliberately not "the session's
 * current model" and not the Primary Agent Model setting: those two were once
 * allowed to disagree with the model that really ran, and a session row naming a
 * model that never ran is a wrong answer rather than a missing one.
 *
 * [providerType] is resolved by the caller, because that lookup needs the live
 * provider config. When it is null the provider instance behind [entry] is gone,
 * so there is no honest provider kind to name: the title is still recorded, with
 * no attribution at all, through the writer that names no model.
 *
 * @return true when the model snapshot was recorded.
 */
internal suspend fun writeLadderTitle(
    chatRepository: ChatRepository,
    sessionId: String,
    entry: ModelEntry,
    title: String,
    category: String?,
    providerType: String?,
    generatedAt: Long = System.currentTimeMillis(),
): Boolean {
    if (providerType == null) {
        chatRepository.updateSessionTitleAndCategory(sessionId, title, category)
        return false
    }
    chatRepository.updateSessionTitleAndCategoryWithModelSnapshot(
        sessionId,
        title,
        category,
        entry.id,
        entry.model.id,
        entry.model.displayName,
        providerType,
        generatedAt,
    )
    return true
}
