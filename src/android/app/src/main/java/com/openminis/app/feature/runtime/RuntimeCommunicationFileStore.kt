package com.openminis.app.feature.runtime

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Restart-safe storage for runtime communication metadata.
 * This file deliberately stores no payload, transcript, attachment, or media bytes.
 */
class RuntimeCommunicationFileStore(private val file: File) {
    @Synchronized
    fun read(): List<RuntimeCommunicationMetadata> {
        if (!file.isFile) return emptyList()
        return runCatching {
            val root = JSONObject(file.readText(StandardCharsets.UTF_8))
            require(root.optInt("schemaVersion", -1) == SCHEMA_VERSION)
            val rows = root.optJSONArray("records") ?: JSONArray()
            buildList(rows.length()) {
                for (index in 0 until rows.length()) add(decode(rows.getJSONObject(index)))
            }
        }.getOrElse { emptyList() }
    }

    @Synchronized
    fun write(records: List<RuntimeCommunicationMetadata>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        val root = JSONObject().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("records", JSONArray(records.map(::encode)))
        }
        tmp.writeText(root.toString(), StandardCharsets.UTF_8)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            error("Unable to atomically replace ${file.absolutePath}")
        }
    }

    private fun encode(record: RuntimeCommunicationMetadata): JSONObject = JSONObject().apply {
        put("recordId", record.recordId)
        put("sender", peer(record.sender))
        put("receiver", peer(record.receiver))
        putNullable("originalMessageId", record.originalMessageId)
        putNullable("replyRoute", record.replyRoute?.let(::replyRoute))
        put("direction", record.direction.name)
        put("timestampMillis", record.timestampMillis)
        put("state", record.state.name)
        put("summary", record.summary)
        put("senderCapabilityVersion", version(record.senderCapabilityVersion))
        put("receiverCapabilityVersion", version(record.receiverCapabilityVersion))
        put("directoryPolicy", policy(record.directoryPolicy))
        putNullable("attachedContext", record.attachedContext?.let(::attachment))
    }

    private fun decode(json: JSONObject): RuntimeCommunicationMetadata = RuntimeCommunicationMetadata(
        recordId = json.getString("recordId"),
        sender = decodePeer(json.getJSONObject("sender")),
        receiver = decodePeer(json.getJSONObject("receiver")),
        originalMessageId = json.optString("originalMessageId", null),
        replyRoute = json.optJSONObject("replyRoute")?.let(::decodeReplyRoute),
        direction = enumValueOf(json.getString("direction")),
        timestampMillis = json.getLong("timestampMillis"),
        state = enumValueOf(json.getString("state")),
        summary = json.getString("summary"),
        senderCapabilityVersion = decodeVersion(json.getJSONObject("senderCapabilityVersion")),
        receiverCapabilityVersion = decodeVersion(json.getJSONObject("receiverCapabilityVersion")),
        directoryPolicy = decodePolicy(json.getJSONObject("directoryPolicy")),
        attachedContext = json.optJSONObject("attachedContext")?.let(::decodeAttachment),
    )

    /**
     * The attachment is written as a locator plus indices and nothing else.
     * There is no branch here that could write message text — the only fields
     * this function knows about are the ones [RuntimeContextAttachment] has, and
     * it has no text field by construction.
     */
    private fun attachment(value: RuntimeContextAttachment) = JSONObject().apply {
        put("conversationId", value.conversationId.value)
        when (val selection = value.selection) {
            is RuntimeContextSelection.All -> {
                put("mode", MODE_ALL)
                put("messageCount", selection.messageCount)
            }
            is RuntimeContextSelection.Indices -> {
                put("mode", MODE_INDICES)
                put("indices", JSONArray(selection.indices.toList()))
            }
        }
    }

    private fun decodeAttachment(value: JSONObject): RuntimeContextAttachment? {
        val address = value.optString("conversationId", null)
            ?.let { RuntimeConversationAddress.parse(it) } ?: return null
        return when (value.optString("mode", null)) {
            MODE_ALL -> RuntimeContextAttachment(
                conversationId = address,
                selection = RuntimeContextSelection.All(value.optInt("messageCount", 0)),
            )
            MODE_INDICES -> {
                val rows = value.optJSONArray("indices") ?: JSONArray()
                val indices = buildList(rows.length()) {
                    for (index in 0 until rows.length()) add(rows.optInt(index, -1))
                }.filter { it >= 0 }.distinct().sorted()
                if (indices.isEmpty()) null
                else RuntimeContextAttachment(
                    conversationId = address,
                    selection = RuntimeContextSelection.Indices(indices),
                )
            }
            // An unknown mode is a record this build cannot interpret. Dropping
            // the attachment is right — inventing a selection would tell the
            // receiver to query for messages the sender never offered.
            else -> null
        }
    }

    private fun peer(value: RuntimeCommunicationPeer) = JSONObject().apply {
        put("address", value.address.value)
        put("sessionId", value.sessionId)
        putNullable("executionId", value.executionId)
    }

    private fun decodePeer(value: JSONObject) = RuntimeCommunicationPeer(
        RuntimeConversationAddress.parse(value.getString("address")) ?: error("invalid peer address"),
        value.getString("sessionId"), value.optString("executionId", null),
    )

    private fun replyRoute(value: RuntimeCommunicationReplyRoute) = JSONObject().apply {
        putNullable("replyToRecordId", value.replyToRecordId)
        putNullable("replyToMessageId", value.replyToMessageId)
        putNullable("replyAddress", value.replyAddress?.value)
    }

    private fun decodeReplyRoute(value: JSONObject) = RuntimeCommunicationReplyRoute(
        value.optString("replyToRecordId", null), value.optString("replyToMessageId", null),
        value.optString("replyAddress", null)?.let { RuntimeConversationAddress.parse(it) },
    )

    private fun version(value: RuntimeCapabilitySnapshotVersion) = JSONObject().apply {
        put("configRevision", value.configRevision); put("capabilityRevision", value.capabilityRevision)
    }

    private fun decodeVersion(value: JSONObject) = RuntimeCapabilitySnapshotVersion(
        value.getLong("configRevision"), value.getLong("capabilityRevision"),
    )

    private fun policy(value: RuntimeCommunicationDirectoryPolicy) = JSONObject().apply {
        put("mode", value.mode.name); put("routeKind", value.routeKind.name)
    }

    private fun decodePolicy(value: JSONObject) = RuntimeCommunicationDirectoryPolicy(
        enumValueOf(value.getString("mode")), enumValueOf(value.getString("routeKind")),
    )

    private fun JSONObject.putNullable(key: String, value: Any?) {
        put(key, value ?: JSONObject.NULL)
    }

    companion object {
        private const val SCHEMA_VERSION = 1

        /**
         * Attachment selection tags. Wire values are explicit strings rather
         * than enum ordinals so reordering [RuntimeContextSelection] can never
         * reinterpret a record already on disk.
         */
        private const val MODE_ALL = "ALL"
        private const val MODE_INDICES = "INDICES"
    }
}
