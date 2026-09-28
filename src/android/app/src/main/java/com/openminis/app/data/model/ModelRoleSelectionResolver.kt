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

    fun effectivePrimaryEntry(config: ProviderConfig, legacyPrimary: ModelEntry?): ModelEntry? =
        explicitPrimaryEntry(config) ?: legacyPrimary

    fun effectiveCompactionEntry(config: ProviderConfig, primary: ModelEntry?): ModelEntry =
        explicitCompactionEntry(config) ?: requireNotNull(primary) {
            "A primary model is required when no compaction model is selected"
        }
}
