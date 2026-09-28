package com.openminis.app.data.model

import org.json.JSONArray
import org.json.JSONObject

/** Immutable request-time selection, independent of mutable provider settings. */
data class ModelRoleRequestSnapshot(
    val primaryEntryId: String?,
    val legacyPrimaryGroupId: String?,
    val childEntryIds: List<String>,
    val childGroupIds: List<String>,
    val childNotes: Map<String, String>,
    val titleEntryId: String?,
    val compactionEntryId: String?,
) {
    fun effectiveCompactionEntryId(): String? = compactionEntryId ?: primaryEntryId

    companion object {
        const val META_TYPE = "modelRequestSnapshot"

        fun from(config: ProviderConfig): ModelRoleRequestSnapshot = ModelRoleRequestSnapshot(
            primaryEntryId = config.primaryModelEntryId,
            legacyPrimaryGroupId = config.defaultPrimaryGroupId,
            childEntryIds = config.agentLoopModelEntryIds.distinct(),
            childGroupIds = config.agentLoopGroupIds.distinct(),
            childNotes = config.subAgentModelNotes.toMap(),
            titleEntryId = config.titleModelEntryId,
            compactionEntryId = config.compactionModelEntryId,
        )

        fun metadataPart(actual: ActualModelRequestSnapshot, selection: ModelRoleRequestSnapshot): JSONObject =
            JSONObject().put("type", META_TYPE).put("value", JSONObject()
                .put("role", actual.role.name)
                .put("entryId", actual.entryId)
                .put("modelId", actual.modelId)
                .put("displayName", actual.displayName)
                .put("providerType", actual.providerTypeRaw)
                .put("providerInstanceId", actual.providerInstanceId)
                .put("primaryEntryId", selection.primaryEntryId)
                .put("compactionEntryId", selection.compactionEntryId)
                .put("effectiveCompactionEntryId", selection.effectiveCompactionEntryId())
            )

        fun metadataOf(partsJson: String): JSONObject? = runCatching {
            val parts = JSONArray(partsJson)
            (0 until parts.length()).asSequence()
                .mapNotNull { parts.optJSONObject(it) }
                .firstOrNull { it.optString("type") == META_TYPE }
                ?.optJSONObject("value")
        }.getOrNull()

        fun withMetadata(partsJson: String, actual: ActualModelRequestSnapshot, selection: ModelRoleRequestSnapshot): String {
            val parts = runCatching { JSONArray(partsJson) }.getOrElse { return partsJson }
            val out = JSONArray().put(metadataPart(actual, selection))
            for (index in 0 until parts.length()) {
                val part = parts.opt(index)
                if (part is JSONObject && part.optString("type") == META_TYPE) continue
                if (part != null) out.put(part)
            }
            return out.toString()
        }
    }
}
