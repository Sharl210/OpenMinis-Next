package com.openminis.app.data.model

/**
 * Resolves the explicitly selected auxiliary model roles without changing the
 * legacy group routing contract. A null selection deliberately means fallback:
 * title uses the legacy title subgroup and compaction uses the primary model.
 */
object ModelRoleSelectionResolver {
    fun explicitPrimaryEntry(config: ProviderConfig): ModelEntry? =
        config.primaryModelEntryId?.let { id -> config.modelEntries.firstOrNull { it.id == id } }

    fun explicitTitleEntry(config: ProviderConfig): ModelEntry? =
        config.titleModelEntryId?.let { id -> config.modelEntries.firstOrNull { it.id == id } }

    fun explicitCompactionEntry(config: ProviderConfig): ModelEntry? =
        config.compactionModelEntryId?.let { id -> config.modelEntries.firstOrNull { it.id == id } }

    // NOTE: no `effectivePrimaryEntry` / `effectiveCompactionEntry` here on
    // purpose. Two things the live path (ModelRoleRequestSnapshot) does NOT
    // reproduce, and re-adding a private copy would silently diverge:
    //   - there is no "legacy primary" fallback for the primary slot, and
    //   - a missing primary yields a null compaction entry instead of throwing.
    // Whether those gaps are intended is an OPEN question — settle it before
    // introducing another resolution path next to the snapshot one.

    /**
     * Which model a conversation with no model binding of its own runs on.
     *
     * The ORDER is the point, and it is why this lives here instead of staying
     * two `?:` at the call site: the user's explicit **Primary Agent Model**
     * setting must win, because that is what the Settings row says it does.
     * `primaryModelEntryId` used to be written by the picker and read by nothing
     * on the chat path — a new conversation ran on `lastUsedEntryId` / the
     * newest provider instead, which made the setting decorative.
     *
     * [visibleEntries] is the caller's already-filtered usable list (enabled
     * provider, not hidden), so a selection whose entry was since removed or
     * hidden falls through instead of resolving to something uncallable.
     *
     * @param lastUsedEntryId the model the composer last used — the tier for a
     *   user who never expressed a preference; the previous behaviour.
     * @param newestTextEntry the existing last-resort default.
     */
    fun newChatDefaultEntry(
        config: ProviderConfig,
        visibleEntries: List<ModelEntry>,
        lastUsedEntryId: String?,
        newestTextEntry: ModelEntry?,
    ): ModelEntry? {
        explicitPrimaryEntry(config)
            ?.let { explicit -> visibleEntries.firstOrNull { it.id == explicit.id } }
            ?.let { return it }
        visibleEntries.firstOrNull { it.id == lastUsedEntryId }?.let { return it }
        return newestTextEntry
    }
}
