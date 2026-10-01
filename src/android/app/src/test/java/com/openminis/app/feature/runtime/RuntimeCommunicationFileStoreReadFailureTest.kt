package com.openminis.app.feature.runtime

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-communication-metadata-read-failure] A metadata file that cannot be read
 * back must be reported, because the read is all-or-nothing and its failure looks
 * exactly like an empty mailbox.
 *
 * `read()` used to be `runCatching { … }.getOrElse { emptyList() }`, and the body it
 * wrapped is worse than it looks:
 *
 *  - `decode` uses `getString` / `getLong` / `enumValueOf`, **all of which throw**;
 *  - the loop runs inside a single `buildList`.
 *
 * So **one unreadable record discards every record in the file**, and the caller gets
 * the same `emptyList()` it would get for a mailbox that was never written. Losing this
 * file means runtime messages stop being deliverable across a restart — silently.
 *
 * Three different failures are distinguishable and must not be reported as one, because
 * they point at different problems:
 *
 *  1. the file cannot be read at all;
 *  2. it is not JSON, or its schema version is not the one this build reads;
 *  3. one record inside it cannot be decoded — and *which* record matters, since the
 *     report otherwise leaves the reader guessing whether one record or two hundred
 *     were lost.
 *
 * The contract is deliberately unchanged: a failed read still returns `emptyList()`.
 * What changed is that the discard is now visible, and says which of the three it was.
 */
class RuntimeCommunicationFileStoreReadFailureTest {

    private class Reports {
        val messages = mutableListOf<String>()
        fun record(detail: String) {
            messages.add(detail)
        }
    }

    private fun tempDir(): File = Files.createTempDirectory("comm-metadata").toFile()

    private fun store(file: File, reports: Reports) =
        RuntimeCommunicationFileStore(file, reportCorruption = reports::record)

    /** Mirrors the construction the existing persistence tests use. */
    private fun record(id: String) = RuntimeCommunicationMetadata(
        recordId = id,
        sender = peer("sender-$id"),
        receiver = peer("receiver-$id"),
        originalMessageId = "message-$id",
        replyRoute = RuntimeCommunicationReplyRoute(replyToRecordId = "reply-$id"),
        direction = RuntimeCommunicationDirection.OUTBOUND,
        timestampMillis = 1_700_000_000_000L,
        state = RuntimeCommunicationState.DELIVERED,
        summary = "hello",
        senderCapabilityVersion = RuntimeCapabilitySnapshotVersion(1, 2),
        receiverCapabilityVersion = RuntimeCapabilitySnapshotVersion(3, 4),
        directoryPolicy = RuntimeCommunicationDirectoryPolicy(
            RuntimeCommunicationDirectoryMode.TEAM,
            RuntimeCommunicationRouteKind.TEAM_PEER,
        ),
    )

    private fun peer(sessionId: String) = RuntimeCommunicationPeer(
        RuntimeConversationAddress.fromStableSessionId(sessionId),
        sessionId,
    )

    @Test
    fun `a missing file is an empty mailbox and is not reported as corruption`() {
        val dir = tempDir()
        try {
            val reports = Reports()
            val store = store(File(dir, "absent.json"), reports)
            assertEquals(emptyList<RuntimeCommunicationMetadata>(), store.read())
            assertEquals("a first run must not be reported", emptyList<String>(), reports.messages)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a healthy write is read back without reporting anything`() {
        val dir = tempDir()
        try {
            val file = File(dir, "metadata.json")
            store(file, Reports()).write(listOf(record("r1")))

            val reports = Reports()
            val read = store(file, reports)
            assertEquals("a healthy read must stay silent", emptyList<String>(), reports.messages)
            assertEquals("the record has to come back", 1, read.read().size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a file that is not JSON is reported`() {
        val dir = tempDir()
        try {
            val file = File(dir, "metadata.json")
            file.writeText("this is not json")

            val reports = Reports()
            assertEquals(emptyList<RuntimeCommunicationMetadata>(), store(file, reports).read())
            assertEquals("exactly one report", 1, reports.messages.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a schema version this build does not read is reported as a version problem`() {
        val dir = tempDir()
        try {
            val file = File(dir, "metadata.json")
            file.writeText("""{"schemaVersion":99,"records":[]}""")

            val reports = Reports()
            assertEquals(emptyList<RuntimeCommunicationMetadata>(), store(file, reports).read())
            assertEquals(1, reports.messages.size)
            assertTrue(
                "a version mismatch must say so rather than report a generic decode failure: " +
                    reports.messages.toString(),
                reports.messages.single().contains("schema version"),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * [T-android-comm-store-per-record] The test this replaces asserted the OPPOSITE of
     * its own best feature: it required the report (good) *and* `read.size == 0` (bad),
     * pinning "one unreadable record costs every record in the file" as the contract.
     *
     * That contract was not defensible, and the reasons are all outside this file:
     * the consumer is `store.read().forEach(directory::append)`, which already accepts a
     * short list; records are self-contained (no sequence number, no cross-record
     * invariant); and the KDoc's only argument was that the silence deserved a report —
     * it never said why the rest had to go. `BackupImporter.parseProviderConfigLeniently`
     * settled the same question from a real incident, where one unknown provider type
     * took eight providers and their credentials with it.
     *
     * The consequence was not academic: losing every record means runtime messages stop
     * being deliverable across a restart, and the caller cannot tell that apart from a
     * mailbox that was never written.
     *
     * So the report assertions are KEPT exactly as they were — the index and the total
     * are still what makes the log useful — and the data assertion is inverted. A
     * version mismatch is a property of the FILE and is still refused whole; that is
     * covered by the test above.
     */
    @Test
    fun `one unreadable record is reported with its index and costs only itself`() {
        val dir = tempDir()
        try {
            val file = File(dir, "metadata.json")
            // Build the "one good, one bad" fixture out of what the store's own writer
            // produces, so the good record cannot drift from what `decode` requires --
            // a hand-written stub would silently stop being valid and the test would
            // then be proving something else. The appended second record is missing
            // every field `decode` needs, so it throws. The good record is still lost
            // with it: that is the amplification this pins.
            store(file, Reports()).write(listOf(record("good")))
            // Append the bad record through the same JSON types the store writes with,
            // rather than splicing text: a string splice has to guess where the array
            // ends, and a nested array in the good record makes it guess wrong.
            val root = JSONObject(file.readText())
            val records = root.getJSONArray("records")
            assertEquals("fixture: exactly the one good record to start with", 1, records.length())
            records.put(JSONObject().put("recordId", "bad"))
            file.writeText(root.toString())

            val reports = Reports()
            val read = store(file, reports).read()

            assertEquals("one report for one load", 1, reports.messages.size)
            val message = reports.messages.single()
            assertTrue(
                "the report has to name the record index: $message",
                message.contains("record 1"),
            )
            assertTrue(
                "the report has to say how many records were in the file: $message",
                message.contains("of 2"),
            )
            assertEquals(
                "the good record must survive -- a record this build cannot read may " +
                    "not cost the records it can",
                1,
                read.size,
            )
            assertEquals(
                "…and it must be the good one, not a placeholder",
                "good",
                read.single().recordId,
            )
        } finally {
            dir.deleteRecursively()
        }
    }
}
