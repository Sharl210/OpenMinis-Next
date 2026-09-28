package com.openminis.app.backup

import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.CompactMarkerEntity
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Shared wire codec for model-attribution snapshot fields. */
internal object BackupModelSnapshotCodec {
    private fun timestamp(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(millis)

    fun sessionTitleFields(session: ChatSessionEntity): JsonObject = buildJsonObject {
        put("titleModelEntryId", session.titleModelEntryId?.let(::JsonPrimitive) ?: JsonNull)
        put("titleModelId", session.titleModelId?.let(::JsonPrimitive) ?: JsonNull)
        put("titleModelDisplayName", session.titleModelDisplayName?.let(::JsonPrimitive) ?: JsonNull)
        put("titleProviderType", session.titleProviderType?.let(::JsonPrimitive) ?: JsonNull)
        put("titleGeneratedAt", session.titleGeneratedAt?.let { JsonPrimitive(timestamp(it)) } ?: JsonNull)
    }

    fun markerFields(marker: CompactMarkerEntity): JsonObject = buildJsonObject {
        put("modelRole", marker.modelRole?.let(::JsonPrimitive) ?: JsonNull)
        put("modelEntryId", marker.modelEntryId?.let(::JsonPrimitive) ?: JsonNull)
        put("modelId", marker.modelId?.let(::JsonPrimitive) ?: JsonNull)
        put("modelDisplayName", marker.modelDisplayName?.let(::JsonPrimitive) ?: JsonNull)
        put("providerType", marker.providerType?.let(::JsonPrimitive) ?: JsonNull)
        put("providerInstanceId", marker.providerInstanceId?.let(::JsonPrimitive) ?: JsonNull)
        put("effectiveCompactionEntryId", marker.effectiveCompactionEntryId?.let(::JsonPrimitive) ?: JsonNull)
        put("modelGeneratedAt", marker.modelGeneratedAt?.let { JsonPrimitive(timestamp(it)) } ?: JsonNull)
    }

    fun stringOrNull(obj: JsonObject, key: String): String? =
        obj[key]?.let { if (it == JsonNull) null else (it as? JsonPrimitive)?.content }

    fun millisOrNull(obj: JsonObject, key: String): Long? = stringOrNull(obj, key)?.let { raw ->
        raw.toLongOrNull() ?: runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.parse(raw)?.time
        }.recoverCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.parse(raw)?.time
        }.getOrNull()
    }
}
