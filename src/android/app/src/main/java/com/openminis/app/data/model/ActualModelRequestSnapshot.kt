package com.openminis.app.data.model

/** Immutable identity captured for one actual request; never resolved from future config. */
data class ActualModelRequestSnapshot(
    val role: ModelRole,
    val entryId: String,
    val modelId: String,
    val displayName: String,
    val providerTypeRaw: String,
    val providerInstanceId: String,
) {
    companion object {
        fun capture(role: ModelRole, entry: ModelEntry, providerTypeRaw: String): ActualModelRequestSnapshot = ActualModelRequestSnapshot(
            role = role,
            entryId = entry.id,
            modelId = entry.model.id,
            displayName = entry.model.displayName,
            providerTypeRaw = providerTypeRaw,
            providerInstanceId = entry.providerInstanceId,
        )

        fun captureWithoutEntry(
            role: ModelRole,
            model: LLMModel,
            providerTypeRaw: String,
            providerInstanceId: String = "",
        ): ActualModelRequestSnapshot = ActualModelRequestSnapshot(
            role = role,
            entryId = "",
            modelId = model.id,
            displayName = model.displayName,
            providerTypeRaw = providerTypeRaw,
            providerInstanceId = providerInstanceId,
        )
    }
}

enum class ModelRole { PRIMARY, CHILD, TITLE, COMPACTION }
