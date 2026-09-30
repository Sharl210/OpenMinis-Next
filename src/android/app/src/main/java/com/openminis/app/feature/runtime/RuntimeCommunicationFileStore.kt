package com.openminis.app.feature.runtime

import com.openminis.app.logging.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Restart-safe storage for runtime communication metadata.
 * This file deliberately stores no payload, transcript, attachment, or media bytes.
 */
class RuntimeCommunicationFileStore(
    private val file: File,
    /**
     * Where metadata that could not be read back is reported.
     *
     * This read is all-or-nothing: [decode] uses `getString`/`getLong`/`enumValueOf`,
     * all of which throw, and the loop runs inside one `buildList`, so **a single
     * unreadable record discards every record in the file** — the caller gets the same
     * `emptyList()` as a mailbox that was never written. Losing this file means runtime
     * messages stop being deliverable across a restart, so that silence is worth a
     * report even though the contract itself is not changing here.
     *
     * Injectable so a unit test can observe it; the default logs.
     */
    private val reportCorruption: (String) -> Unit = { detail -> AppLogger.warning(TAG, detail) },
) {
    @Synchronized
    fun read(): List<RuntimeCommunicationMetadata> {
        if (!file.isFile) return emptyList()
        val raw = runCatching { file.readText(StandardCharsets.UTF_8) }.getOrNull()
        if (raw == null) {
            reportCorruption("the communication metadata file exists but could not be read")
            return emptyList()
        }
        return runCatching { parse(raw) }.getOrElse { error ->
            reportCorruption(
                "the communication metadata file exists but could not be decoded, so every " +
                    "record in it is being ignored: ${error.message}",
            )
            emptyList()
        }
    }

    private fun parse(raw: String): List<RuntimeCommunicationMetadata> {
        val root = JSONObject(raw)
        val version = root.optInt("schemaVersion", -1)
        require(version == SCHEMA_VERSION) {
            "its schema version is $version, not the $SCHEMA_VERSION this build reads"
        }
        val rows = root.optJSONArray("records") ?: JSONArray()
        return buildList(rows.length()) {
            for (index in 0 until rows.length()) {
                add(
                    runCatching { decode(rows.getJSONObject(index)) }.getOrElse { error ->
                        // Named so the report says which record, and out of how many --
                        // without this the log would say "undecodable" and leave the
                        // reader to guess whether one record or two hundred were lost.
                        throw IllegalStateException(
                            "record $index of ${rows.length()} could not be decoded: ${error.message}",
                            error,
                        )
                    },
                )
            }
        }
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
        private const val TAG = "RuntimeCommunicationStore"
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
