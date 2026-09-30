package com.openminis.app.share

import android.content.Context
import android.content.ContextWrapper
import com.openminis.app.data.db.ChatDao
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.feature.runtime.RuntimeModelSnapshot
import com.openminis.app.feature.runtime.RuntimeSessionCoordinator
import com.openminis.app.feature.runtime.RuntimeSessionNode
import com.openminis.app.feature.runtime.RuntimeTopologySnapshot
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The runtime-tree export, asserted on the archive it actually writes.
 *
 * ## What this file used to be, and why that was not evidence
 *
 * Every assertion here used to read `ChatExporter.kt` (plus two unrelated files)
 * as TEXT and assert substrings such as `ZipOutputStream` and `"/children/"`. A
 * substring proves only that the characters exist somewhere in the file: a
 * comment, a doc string, a constant, or a branch production never evaluates all
 * satisfy it. The same failure mode has already cost this repo twice (a
 * validation branch that is never evaluated; a field that is never written).
 * The assertions below are the archive, read back off disk.
 *
 * ## Why "read back off disk"
 *
 * The archive IS the deliverable — the user receives a `.zip`, so the bytes that
 * land in `cacheDir/shared/` are the only thing worth asserting. Checking an
 * object the writer happens to hold in memory would validate a copy. Every
 * assertion below opens the finished file with [ZipFile] and looks at entry
 * names, entry bytes and parsed JSON.
 *
 * ## The one boundary this file cannot cross, stated rather than implied
 *
 * [ChatExporter.exportForRuntimeTree] returns a `content://` Uri minted by
 * `FileProvider`, whose authority is resolved through the app's
 * `PackageManager` — that exists only inside a packaged APK, and this module's
 * unit-test source set has no Robolectric. The archive is fully written and
 * closed BEFORE the Uri is minted, so the assertions reach the artifact and stop
 * there; that is also why the helper below does not unwrap the returned `Pair`.
 *
 * The file name is kept (it ends in `SourceTest`) because the ULW acceptance
 * ledger cites `ChatExporterRuntimeTreeSourceTest` by name as evidence for its
 * export rows, and renaming it would break those references without adding a
 * single assertion.
 *
 * ONE SOURCE-TEXT ASSERTION REMAINS, and it is named as such rather than hidden:
 * [the share entry point reads the runtime topology and picks the MIME for the artifact it got]
 * still does `readText()` + `contains` on `SessionListScreen.kt`. Everything else
 * in this file reads the archive it produced. That single exception is called out
 * there (and nowhere claims this class reads no source at all — it used to, which
 * was an overstatement: the pattern it warns about is exactly the one it kept
 * using once).
 */
class ChatExporterRuntimeTreeSourceTest {

    // ------------------------------------------------------------ fixtures

    private class TempRoot {
        val dir: File = Files.createTempDirectory("chat-exporter").toFile()

        // Deliberately NOT named `cacheDir` / `filesDir`: `Context` already
        // declares those names, so inside the anonymous `ContextWrapper` below an
        // unqualified `cacheDir` would resolve to the wrapper's OWN property and
        // `override fun getCacheDir() = cacheDir` would recurse until the stack
        // died — a StackOverflowError that `exportForRuntimeTree`'s caller would
        // only see as "the export produced nothing".
        val cacheRoot: File = File(dir, "cache").apply { mkdirs() }
        val filesRoot: File = File(dir, "files").apply { mkdirs() }
        val sharedDir: File = File(cacheRoot, "shared")
        val mediaDir: File = File(filesRoot, "media").apply { mkdirs() }

        fun context(): Context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getCacheDir(): File = cacheRoot
            override fun getFilesDir(): File = filesRoot
            override fun getPackageName(): String = "com.openminis.app"
        }

        fun dispose() {
            dir.deleteRecursively()
        }
    }

    /**
     * A [ChatDao] serving the three reads the exporter performs, failing loudly
     * on anything else. A JDK proxy rather than a hand-written fake: `ChatDao` is
     * a large Room interface and the subject here is the archive, not a
     * re-implementation of Room.
     */
    private class TranscriptChatDao(
        private val sessions: Map<String, ChatSessionEntity>,
        private val messages: Map<String, List<MessageEntity>>,
    ) : InvocationHandler {

        val dao: ChatDao = Proxy.newProxyInstance(
            ChatDao::class.java.classLoader,
            arrayOf(ChatDao::class.java),
            this,
        ) as ChatDao

        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
            val raw = args?.toList().orEmpty()
            // A suspend DAO method carries a trailing Continuation; this fake
            // answers synchronously, so the continuation is only stripped.
            val arguments = if (raw.lastOrNull() is kotlin.coroutines.Continuation<*>) raw.dropLast(1) else raw
            return when (method.name) {
                "getSession" -> sessions[arguments[0] as String]
                "messageCountForSession" -> messages[arguments[0] as String].orEmpty().size
                "loadMessagesPage" -> {
                    val rows = messages[arguments[0] as String].orEmpty()
                    val offset = arguments[1] as Int
                    val limit = arguments[2] as Int
                    if (offset >= rows.size) emptyList() else rows.subList(offset, minOf(rows.size, offset + limit))
                }
                "toString" -> "TranscriptChatDao(${messages.size} sessions)"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments.firstOrNull()
                else -> error(
                    "TranscriptChatDao: unexpected ${method.name}(...). The export reached for a DAO " +
                        "method this fake does not model — model it explicitly instead of letting it pass silently.",
                )
            }
        }
    }

    private fun session(id: String, title: String) = ChatSessionEntity(
        id = id,
        title = title,
        modelId = "test-model",
        createdAt = 1_000L,
        updatedAt = 1_000L,
    )

    private fun message(id: String, sessionId: String, role: String, text: String, order: Int) =
        MessageEntity(
            id = id,
            sessionId = sessionId,
            role = role,
            partsJson = """[{"type":"text","value":${JSONObject.quote(text)}}]""",
            createdAt = 1_000L + order,
            sortOrder = order,
        )

    private fun node(id: String, parentId: String?, rootId: String, depth: Int) = RuntimeSessionNode(
        id = id,
        parentId = parentId,
        rootId = rootId,
        depth = depth,
        model = RuntimeModelSnapshot("test-provider", "test-model"),
        createdAtMillis = 1L,
        updatedAtMillis = 1L,
    )

    /**
     * Run the real export and return the artifact it produced.
     *
     * The Uri cannot be minted under a plain JVM (see the class comment), so the
     * call is not unwrapped; the archive it wrote is the subject of every
     * assertion below, and it is complete on disk before the Uri is attempted.
     */
    private fun exportedArtifact(
        root: TempRoot,
        session: ChatSessionEntity,
        repository: ChatRepository,
        topology: RuntimeTopologySnapshot,
        format: String,
    ): File = runBlocking {
        // The Uri cannot be minted under a plain JVM (see the class comment), so
        // only that last step may fail. Anything else must NOT be swallowed: a
        // silently empty shared/ would turn every assertion below into a
        // confusing "expected 1 artifact" failure, so the throwable is carried
        // into the assertion message.
        val failure = runCatching {
            ChatExporter.exportForRuntimeTree(
                context = root.context(),
                session = session,
                repository = repository,
                format = format,
                topology = topology,
            )
        }.exceptionOrNull()
        val produced = root.sharedDir.listFiles().orEmpty()
        assertEquals(
            "the export must leave exactly one artifact in cacheDir/shared " +
                "(produced=${produced.map { it.name }}); the call itself failed with: $failure",
            1,
            produced.size,
        )
        produced.single()
    }

    private fun zipEntries(archive: File): Map<String, ByteArray> = ZipFile(archive).use { zip ->
        zip.entries().asSequence().filter { !(it as ZipEntry).isDirectory }.associate { entry ->
            entry.name to zip.getInputStream(entry).use { it.readBytes() }
        }
    }

    private fun ZipFile.text(name: String): String =
        getInputStream(getEntry(name) ?: error("archive entry missing: $name")).use {
            it.readBytes().toString(Charsets.UTF_8)
        }

    private fun textOf(partsJson: String): String =
        JSONArray(partsJson).getJSONObject(0).getString("value")

    // ------------------------------------------------- the recursive archive

    @Test
    fun `a session with descendants exports one archive whose entries mirror the runtime tree`() {
        val root = TempRoot()
        try {
            val sessions = listOf(
                session("root", "Root Chat"),
                session("child", "Child Chat"),
                session("grand", "Grandchild Chat"),
            ).associateBy { it.id }
            val messages = mapOf(
                "root" to listOf(message("m1", "root", "user", "root question", 0)),
                "child" to listOf(message("m2", "child", "user", "child question", 0)),
                "grand" to listOf(message("m3", "grand", "user", "grand question", 0)),
            )
            val repository = ChatRepository(TranscriptChatDao(sessions, messages).dao)
            val topology = RuntimeTopologySnapshot(
                nodes = listOf(
                    node("root", null, "root", 0),
                    node("child", "root", "root", 1),
                    node("grand", "child", "root", 2),
                ),
                edges = emptyList(),
                subscriptions = emptyList(),
            )

            val archive = exportedArtifact(root, sessions.getValue("root"), repository, topology, "json")

            assertTrue("a session with descendants must export an archive, got ${archive.name}", archive.name.endsWith(".zip"))
            assertEquals(
                "each node contributes its transcript next to a session.json sidecar, and a " +
                    "descendant's directory is nested under its parent's /children/ segment — this is " +
                    "the structure the old text assertion only claimed to check",
                listOf(
                    "root/messages.json",
                    "root/session.json",
                    "root/children/child/messages.json",
                    "root/children/child/session.json",
                    "root/children/child/children/grand/messages.json",
                    "root/children/child/children/grand/session.json",
                ).sorted(),
                zipEntries(archive).keys.sorted(),
            )

            // The entries are not empty placeholders: each transcript carries the
            // rows of ITS OWN node, and each sidecar says where that node sits.
            ZipFile(archive).use { zip ->
                val grandTranscript = JSONArray(zip.text("root/children/child/children/grand/messages.json"))
                assertEquals(1, grandTranscript.length())
                assertEquals("m3", grandTranscript.getJSONObject(0).getString("id"))
                assertEquals("grand question", textOf(grandTranscript.getJSONObject(0).getString("content")))

                val grandMeta = JSONObject(zip.text("root/children/child/children/grand/session.json"))
                assertEquals("grand", grandMeta.getString("session_id"))
                assertEquals("child", grandMeta.getString("parent_id"))
                assertEquals("root", grandMeta.getString("root_id"))
                assertEquals(2, grandMeta.getInt("depth"))

                val rootMeta = JSONObject(zip.text("root/session.json"))
                assertEquals("root", rootMeta.getString("session_id"))
                assertEquals(0, rootMeta.getInt("depth"))
                assertNull("the root has no parent_id", rootMeta.opt("parent_id"))
                assertEquals("Root Chat", rootMeta.getString("title"))

                val childMeta = JSONObject(zip.text("root/children/child/session.json"))
                assertEquals("root", childMeta.getString("parent_id"))
                assertEquals(1, childMeta.getInt("depth"))

                val rootTranscript = JSONArray(zip.text("root/messages.json"))
                assertEquals(1, rootTranscript.length())
                assertEquals("root question", textOf(rootTranscript.getJSONObject(0).getString("content")))
            }
        } finally {
            root.dispose()
        }
    }

    @Test
    fun `the topology the exporter consumes is the coordinator's own snapshot of the live tree`() {
        val root = TempRoot()
        try {
            // Both ends are production: the snapshot comes from the real
            // coordinator (this is the assertion the old text check made about
            // `fun topologySnapshot()` existing), and the tree it describes is
            // then handed to the real exporter.
            val coordinator = RuntimeSessionCoordinator.open(root.context())
            assertEquals("root", coordinator.startRoot("root"))
            assertTrue(coordinator.startChild("root", "child"))

            val topology = coordinator.topologySnapshot()
            assertEquals(
                "the snapshot must describe the real nodes, with the child under the root",
                listOf("root" to null, "child" to "root"),
                topology.nodes.sortedBy { it.depth }.map { it.id to it.parentId },
            )
            assertEquals(
                "and it must resolve the chat session's descendants for the exporter",
                listOf("child"),
                topology.descendantsForChatSession("root").map { it.id },
            )

            val sessions = listOf(session("root", "Root Chat"), session("child", "Child Chat")).associateBy { it.id }
            val repository = ChatRepository(
                TranscriptChatDao(
                    sessions,
                    mapOf(
                        "root" to listOf(message("r1", "root", "user", "root only", 0)),
                        "child" to listOf(message("c1", "child", "user", "child only", 0)),
                    ),
                ).dao,
            )

            val archive = exportedArtifact(root, sessions.getValue("root"), repository, topology, "json")
            assertTrue(
                "the coordinator's own snapshot must be enough to produce the nested archive",
                zipEntries(archive).keys.contains("root/children/child/messages.json"),
            )
        } finally {
            root.dispose()
        }
    }

    @Test
    fun `a session with no descendants exports a bare transcript file instead of an archive`() {
        val root = TempRoot()
        try {
            val only = session("root", "Root Chat")
            val repository = ChatRepository(
                TranscriptChatDao(
                    mapOf("root" to only),
                    mapOf("root" to listOf(message("m1", "root", "user", "typed by the human", 0))),
                ).dao,
            )
            val topology = RuntimeTopologySnapshot(
                nodes = listOf(node("root", null, "root", 0)),
                edges = emptyList(),
                subscriptions = emptyList(),
            )

            val artifact = exportedArtifact(root, only, repository, topology, "text")

            assertEquals(
                "a leaf session is shared as a bare transcript, not an archive " +
                    "(the title is sanitised for a file name: non-alphanumerics become '_')",
                "Root_Chat-root.txt",
                artifact.name,
            )
            assertFalse("the leaf branch must not archive", artifact.name.endsWith(".zip"))
            assertEquals(
                "the leaf transcript is the plain-text rendering of the session",
                "Root Chat\n\nYou: typed by the human\n\n",
                artifact.readText(Charsets.UTF_8),
            )
        } finally {
            root.dispose()
        }
    }

    @Test
    fun `the archive keeps only the node's own rows, so transcripts cannot leak sideways`() {
        val root = TempRoot()
        try {
            val sessions = listOf(session("root", "Root Chat"), session("child", "Child Chat")).associateBy { it.id }
            val repository = ChatRepository(
                TranscriptChatDao(
                    sessions,
                    mapOf(
                        "root" to listOf(message("r1", "root", "user", "root only", 0)),
                        "child" to listOf(message("c1", "child", "user", "child only", 0)),
                    ),
                ).dao,
            )

            val archive = exportedArtifact(root, sessions.getValue("root"), repository, attachmentTopology(), "json")
            ZipFile(archive).use { zip ->
                val rootTranscript = zip.text("root/messages.json")
                val childTranscript = zip.text("root/children/child/messages.json")
                assertTrue(rootTranscript.contains("root only"))
                assertFalse("the root transcript must not contain the child's rows", rootTranscript.contains("child only"))
                assertTrue(childTranscript.contains("child only"))
                assertFalse("the child transcript must not contain the root's rows", childTranscript.contains("root only"))
            }
        } finally {
            root.dispose()
        }
    }

    // --------------------------------------------- the one retained (C) check

    /**
     * (C) KEPT AS A STRUCTURAL CHECK — the only two assertions of the old file
     * that could not be turned into a behaviour test, and they are kept rather
     * than dropped because dropping them would remove the sole guard on the
     * share wiring.
     *
     * Why this cannot be behavioural, in terms this repo can check: `exportSession`
     * is a private top-level function in `SessionListScreen.kt`, runs inside a
     * coroutine launched from a Compose callback, resolves the topology through
     * `RuntimeSessionCoordinator.open(context)`, and finishes with
     * `context.startActivity(chooser)`. Asserting "the share intent carries this
     * MIME for this artifact" needs a Compose screen and a live Activity — this
     * module's unit-test source set has neither Robolectric nor compose-ui-test,
     * and the exported-file behaviour that CAN be exercised is already asserted
     * above against the real archive.
     *
     * What is left is a structural fact about the file: the MIME the share intent
     * is built with for each artifact kind, and that the export path is the one
     * that consults the runtime tree. A future edit that removes the archive MIME
     * — the case that would hand a `.zip` to the share sheet as `text/plain` —
     * still trips this.
     *
     * Two details keep this from being worse than useless, on the principle that a
     * text check which reddens for the wrong reason gets deleted by the next
     * person and then guards nothing:
     *
     *  - comments are stripped before matching, so *documenting* the share path
     *    cannot redden it. A bare `contains` on raw source cannot tell a mention
     *    in prose from a mention in code, and the better a file is documented the
     *    more often it lies;
     *  - every assertion is confined to the `exportSession` body, so an unrelated
     *    later use of the same MIME or of `topologySnapshot()` elsewhere in the
     *    screen cannot satisfy this check on its own.
     */
    @Test
    fun `the share entry point reads the runtime topology and picks the MIME for the artifact it got`() {
        val screen = File("src/main/java/com/openminis/app/ui/sessions/SessionListScreen.kt")
            .takeIf { it.isFile }
            ?: File("app/src/main/java/com/openminis/app/ui/sessions/SessionListScreen.kt")
        assertTrue("SessionListScreen.kt not found; run from the module or repo root", screen.isFile)
        val code = screen.readText().lines().filterNot { line ->
            val t = line.trim()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }.joinToString("\n")

        val exportStart = code.indexOf("private fun exportSession")
        assertTrue(
            "SessionListScreen.kt must still hold the exportSession entry point this guard is about",
            exportStart >= 0,
        )
        val exportBody = code.substring(exportStart)

        assertTrue(
            "the share entry point must consult the runtime tree rather than the session rows alone",
            exportBody.contains("topologySnapshot()"),
        )
        assertTrue("JSON share MIME missing", exportBody.contains("application/json"))
        assertTrue("text share MIME missing", exportBody.contains("text/plain"))
        assertTrue(
            "a session that became an archive must be shared as one, not as its transcript format",
            exportBody.contains("application/zip"),
        )
        assertTrue(
            "the archive MIME must be selected before the transcript formats, or a .zip is shared as text/plain",
            exportBody.indexOf("application/zip") < exportBody.indexOf("application/json"),
        )
    }

    // -------------------------------------------------------------- attachments

    /** A `mediaRef` part, exactly as the paste path persists one. */
    private fun mediaRefMessage(id: String, sessionId: String, relativePath: String) = MessageEntity(
        id = id,
        sessionId = sessionId,
        role = "user",
        partsJson = """[{"type":"text","value":"look"},{"type":"mediaRef","value":""" +
            """{"kind":"image","relativePath":${JSONObject.quote(relativePath)}}}]""",
        createdAt = 1_000L,
        sortOrder = 0,
    )

    private fun attachmentTopology() = RuntimeTopologySnapshot(
        nodes = listOf(node("root", null, "root", 0), node("child", "root", "root", 1)),
        edges = emptyList(),
        subscriptions = emptyList(),
    )

    @Test
    fun `attachments referenced by a message are copied into the archive under the node's directory`() {
        val root = TempRoot()
        try {
            val picture = File(root.mediaDir, "img/pic.png").apply { parentFile?.mkdirs() }
            picture.writeBytes(byteArrayOf(1, 2, 3, 4, 5))

            val sessions = listOf(session("root", "Root Chat"), session("child", "Child Chat")).associateBy { it.id }
            val repository = ChatRepository(
                TranscriptChatDao(
                    sessions,
                    mapOf("child" to listOf(mediaRefMessage("m1", "child", "img/pic.png"))),
                ).dao,
            )

            val archive = exportedArtifact(root, sessions.getValue("root"), repository, attachmentTopology(), "json")
            val entries = zipEntries(archive)

            assertTrue(
                "the referenced attachment must be archived beside the node's transcript: ${entries.keys}",
                entries.containsKey("root/children/child/attachments/img/pic.png"),
            )
            assertTrue(
                "the archived bytes must be the file's, not a re-encoded stub",
                entries.getValue("root/children/child/attachments/img/pic.png")
                    .contentEquals(byteArrayOf(1, 2, 3, 4, 5)),
            )
        } finally {
            root.dispose()
        }
    }

    @Test
    fun `a mediaRef that escapes the media root is left out of the archive`() {
        val root = TempRoot()
        try {
            // A REAL file outside the media root, so the only thing that can keep
            // it out of the archive is the path guard — not a missing file.
            val escaped = File(root.filesRoot, "escaped.png").apply { writeBytes(byteArrayOf(9, 9, 9)) }
            assertTrue("fixture: the escaping target must exist", escaped.isFile)

            val sessions = listOf(session("root", "Root Chat"), session("child", "Child Chat")).associateBy { it.id }
            val repository = ChatRepository(
                TranscriptChatDao(
                    sessions,
                    mapOf("child" to listOf(mediaRefMessage("m1", "child", "../escaped.png"))),
                ).dao,
            )

            val archive = exportedArtifact(root, sessions.getValue("root"), repository, attachmentTopology(), "json")
            val entries = zipEntries(archive)

            assertFalse(
                "a relativePath resolving outside filesDir/media must not be archived; got ${entries.keys}",
                entries.keys.any { it.contains("escaped") },
            )
            // …and the export still produced the node directories, i.e. the guard
            // skipped ONE attachment rather than aborting the export.
            assertTrue(entries.containsKey("root/children/child/messages.json"))
            assertTrue(entries.containsKey("root/children/child/session.json"))
        } finally {
            root.dispose()
        }
    }

    @Test
    fun `a mediaRef pointing at a missing file is skipped without failing the export`() {
        val root = TempRoot()
        try {
            val sessions = listOf(session("root", "Root Chat"), session("child", "Child Chat")).associateBy { it.id }
            val repository = ChatRepository(
                TranscriptChatDao(
                    sessions,
                    mapOf("child" to listOf(mediaRefMessage("m1", "child", "img/gone.png"))),
                ).dao,
            )

            val entries = zipEntries(
                exportedArtifact(root, sessions.getValue("root"), repository, attachmentTopology(), "json"),
            )
            assertEquals(
                "a dangling mediaRef must contribute no entry and take nothing else down",
                listOf(
                    "root/children/child/messages.json",
                    "root/children/child/session.json",
                    "root/messages.json",
                    "root/session.json",
                ).sorted(),
                entries.keys.sorted(),
            )
        } finally {
            root.dispose()
        }
    }

    // ------------------------------- an incomplete tree must not look complete

    /**
     * The artifact AND the returned [Summary] together, which [exportedArtifact]
     * cannot give back: that helper goes through `exportForRuntimeTree`, whose last
     * step mints a `content://` Uri and therefore throws on a bare JVM — the summary
     * would be gone before it could be asserted.
     */
    private fun exportedSummary(
        root: TempRoot,
        session: ChatSessionEntity,
        repository: ChatRepository,
        topology: RuntimeTopologySnapshot,
        format: String,
    ): Pair<File, ChatExporter.Summary> = runBlocking {
        ChatExporter.writeRuntimeTreeExport(
            context = root.context(),
            session = session,
            repository = repository,
            format = format,
            topology = topology,
        )
    }

    /**
     * The reproducer for the silent-drop defect, as a fixture.
     *
     * `root → child → grand`, and `child` has **no Chat row** while `grand` does.
     * That is not a contrived shape: the runtime tree is written by the coordinator
     * and the Chat rows by the chat repository, and the session-delete path clears
     * the latter without touching the former — so a node really can outlive its chat.
     *
     * `grand` is deliberately kept even though its parent is gone: the old code
     * dropped exactly the nodes it could not resolve and then kept walking, so the
     * failure mode to pin is "one node vanished while the archive still succeeded",
     * not "the subtree collapsed".
     */
    private fun treeWithADeletedChatRow(): Triple<RuntimeTopologySnapshot, ChatRepository, ChatSessionEntity> {
        val topology = RuntimeTopologySnapshot(
            nodes = listOf(
                node("root", null, "root", 0),
                node("child", "root", "root", 1),
                node("grand", "child", "root", 2),
            ),
            edges = emptyList(),
            subscriptions = emptyList(),
        )
        val sessions = listOf(session("root", "Root Chat"), session("grand", "Grand Chat")).associateBy { it.id }
        val repository = ChatRepository(
            TranscriptChatDao(
                sessions,
                mapOf(
                    "root" to listOf(message("m1", "root", "user", "root question", 0)),
                    "grand" to listOf(message("m3", "grand", "user", "grand question", 0)),
                ),
            ).dao,
        )
        return Triple(topology, repository, sessions.getValue("root"))
    }

    @Test
    fun `a runtime node whose chat row is gone is named in the result, not silently dropped`() {
        val root = TempRoot()
        try {
            val (topology, repository, rootSession) = treeWithADeletedChatRow()
            val (archive, summary) = exportedSummary(root, rootSession, repository, topology, "json")

            assertEquals(
                "the export must name every runtime node it could not include",
                listOf("child"),
                summary.missingRuntimeNodeIds,
            )
            // …and the nodes that DO still have chats are still exported, so this is
            // a report of a partial export rather than an abort.
            val entries = zipEntries(archive)
            assertTrue(
                "the surviving nodes must still be archived: ${entries.keys}",
                entries.containsKey("root/messages.json"),
            )
            assertTrue(
                "the surviving grandchild must still be archived under its runtime path: ${entries.keys}",
                entries.containsKey("root/children/child/children/grand/messages.json"),
            )
            assertFalse(
                // Asserts the dropped node contributed no transcript ENTRY. It cannot
                // speak about directory entries: `zipEntries` filters those out before
                // this map is built, and the exporter never writes one — an earlier
                // version of this message claimed "must not get a directory either",
                // which made the assertion look like it covered more than it does.
                "the node with no chat row must not contribute a transcript entry: ${entries.keys}",
                entries.containsKey("root/children/child/messages.json"),
            )
        } finally {
            root.dispose()
        }
    }

    @Test
    fun `the archive carries the omission, so the fact survives leaving the device`() {
        val root = TempRoot()
        try {
            val (topology, repository, rootSession) = treeWithADeletedChatRow()
            val (archive, _) = exportedSummary(root, rootSession, repository, topology, "json")

            val entries = zipEntries(archive)
            assertTrue(
                "an archive with missing nodes must say so inside itself: ${entries.keys}",
                entries.containsKey(ChatExporter.DEGRADED_MANIFEST_ENTRY),
            )
            val manifest = JSONObject(
                entries.getValue(ChatExporter.DEGRADED_MANIFEST_ENTRY).toString(Charsets.UTF_8),
            )
            assertEquals("the archive must not claim to be complete", false, manifest.getBoolean("complete"))
            assertEquals("root", manifest.getString("root_session_id"))
            assertEquals(1, manifest.getInt("missing_count"))
            assertEquals(
                listOf("child"),
                manifest.getJSONArray("missing_runtime_node_ids").let { array ->
                    (0 until array.length()).map { array.getString(it) }
                },
            )
            assertEquals(
                "the manifest must list what IS in the archive, so a reader can tell the two apart",
                listOf("root", "grand"),
                manifest.getJSONArray("exported_node_ids").let { array ->
                    (0 until array.length()).map { array.getString(it) }
                },
            )
        } finally {
            root.dispose()
        }
    }

    /**
     * The control. Without this, a "fix" that reports a missing node unconditionally
     * — or one that adds the manifest entry to every archive — would still pass the
     * two tests above while breaking the complete case and every existing layout
     * assertion in this file.
     */
    @Test
    fun `a tree whose chats all exist reports nothing missing and gains no extra entry`() {
        val root = TempRoot()
        try {
            val topology = RuntimeTopologySnapshot(
                nodes = listOf(
                    node("root", null, "root", 0),
                    node("child", "root", "root", 1),
                    node("grand", "child", "root", 2),
                ),
                edges = emptyList(),
                subscriptions = emptyList(),
            )
            val sessions = listOf(
                session("root", "Root Chat"),
                session("child", "Child Chat"),
                session("grand", "Grand Chat"),
            ).associateBy { it.id }
            val repository = ChatRepository(
                TranscriptChatDao(
                    sessions,
                    mapOf(
                        "root" to listOf(message("m1", "root", "user", "root question", 0)),
                        "child" to listOf(message("m2", "child", "user", "child question", 0)),
                        "grand" to listOf(message("m3", "grand", "user", "grand question", 0)),
                    ),
                ).dao,
            )

            val (archive, summary) = exportedSummary(root, sessions.getValue("root"), repository, topology, "json")

            assertTrue(
                "a complete export must report nothing missing, got ${summary.missingRuntimeNodeIds}",
                summary.missingRuntimeNodeIds.isEmpty(),
            )
            assertEquals(
                "a complete archive must keep exactly the entries it had before this fix",
                listOf(
                    "root/children/child/children/grand/messages.json",
                    "root/children/child/children/grand/session.json",
                    "root/children/child/messages.json",
                    "root/children/child/session.json",
                    "root/messages.json",
                    "root/session.json",
                ).sorted(),
                zipEntries(archive).keys.sorted(),
            )
        } finally {
            root.dispose()
        }
    }

    // ------------------------- the branch a parentId walk cannot see at all

    /**
     * The second silent-loss shape, pinned so it cannot come back unnoticed.
     *
     * A conversation that is opened again gets a **brand-new root**
     * `<sessionId>#run-<n>` whose `parentId` is `null` (see
     * `RuntimeSessionCoordinator.startRoot`). Its children then hang off that new
     * root, so `grand.parentId == "child#run-2"` with no `parentId` path back to
     * `root`. The repository documents this shape because the DELETE path hit it
     * first (`SessionSubtreeDeletionPlan`); the export's `parentId` walk simply
     * does not reach it.
     *
     * The consequence before this test existed: that whole branch was invisible —
     * not written AND not counted — so `missingRuntimeNodeIds` stayed empty and the
     * archive called itself complete. `grand` here HAS a chat row, so this is real
     * content disappearing while the export reports success.
     *
     * What the fix does and does NOT do, asserted together on purpose:
     *  - it NAMES `grand` in the result, so the loss stops being silent;
     *  - it still does not archive `grand`, because which directory a re-run node
     *    should occupy is a product decision (see `uncoveredExportNodeIds`);
     *  - it does NOT report `child#run-2` itself. That node is the *same
     *    conversation* as `child`, whose content IS in the archive, so flagging it
     *    would be a false alarm on a shape the export already handles — which is
     *    why the subtraction is done by session identity rather than by node id.
     */
    @Test
    fun `a branch hung off a re-run root is named as uncovered instead of vanishing`() {
        val root = TempRoot()
        try {
            val topology = RuntimeTopologySnapshot(
                nodes = listOf(
                    node("root", null, "root", 0),
                    node("child", "root", "root", 1),
                    // The re-run root: same conversation as `child`, new node, no parent.
                    node("child#run-2", null, "child#run-2", 0),
                    node("grand", "child#run-2", "child#run-2", 1),
                ),
                edges = emptyList(),
                subscriptions = emptyList(),
            )
            val sessions = listOf(
                session("root", "Root Chat"),
                session("child", "Child Chat"),
                session("grand", "Grand Chat"),
            ).associateBy { it.id }
            val repository = ChatRepository(
                TranscriptChatDao(
                    sessions,
                    mapOf(
                        "root" to listOf(message("m1", "root", "user", "root question", 0)),
                        "child" to listOf(message("m2", "child", "user", "child question", 0)),
                        "grand" to listOf(message("m3", "grand", "user", "grand question", 0)),
                    ),
                ).dao,
            )

            val (archive, summary) = exportedSummary(root, sessions.getValue("root"), repository, topology, "json")
            val entries = zipEntries(archive)

            assertEquals(
                "the unreachable branch must be named, not silently dropped",
                listOf("grand"),
                summary.missingRuntimeNodeIds,
            )
            assertFalse(
                "the re-run ROOT is the same conversation as `child`, whose content is " +
                    "already archived — reporting it would be a false alarm: ${summary.missingRuntimeNodeIds}",
                summary.missingRuntimeNodeIds.contains("child#run-2"),
            )
            // The report is real: `grand` has a chat row and real messages, and it
            // still is not in the archive. That boundary is documented in
            // `uncoveredExportNodeIds` rather than hidden behind a green test.
            assertFalse(
                "this fix reports the uncovered branch, it does not relocate it: ${entries.keys}",
                entries.keys.any { it.contains("grand") },
            )
            assertTrue(
                "the reachable half of the tree is still exported: ${entries.keys}",
                entries.containsKey("root/children/child/messages.json"),
            )
        } finally {
            root.dispose()
        }
    }

    /**
     * The control for the subtraction rule: an ORDINARY re-run adds a second root
     * for a conversation whose content is already archived, and must not be reported
     * as missing. Without this, normalising by node id instead of session identity
     * would look correct on the test above while spamming the user on every re-run.
     */
    @Test
    fun `an ordinary re-run of an exported child is not reported as a missing conversation`() {
        val root = TempRoot()
        try {
            val topology = RuntimeTopologySnapshot(
                nodes = listOf(
                    node("root", null, "root", 0),
                    node("child", "root", "root", 1),
                    node("child#run-2", null, "child#run-2", 0),
                ),
                edges = emptyList(),
                subscriptions = emptyList(),
            )
            val sessions = listOf(
                session("root", "Root Chat"),
                session("child", "Child Chat"),
            ).associateBy { it.id }
            val repository = ChatRepository(
                TranscriptChatDao(
                    sessions,
                    mapOf(
                        "root" to listOf(message("m1", "root", "user", "root question", 0)),
                        "child" to listOf(message("m2", "child", "user", "child question", 0)),
                    ),
                ).dao,
            )

            val (archive, summary) = exportedSummary(root, sessions.getValue("root"), repository, topology, "json")

            assertTrue(
                "`child` and `child#run-2` are one conversation, and its content is in " +
                    "the archive — so nothing is missing, got ${summary.missingRuntimeNodeIds}",
                summary.missingRuntimeNodeIds.isEmpty(),
            )
            assertEquals(
                "a re-run must not change the archive's layout",
                listOf(
                    "root/children/child/messages.json",
                    "root/children/child/session.json",
                    "root/messages.json",
                    "root/session.json",
                ).sorted(),
                zipEntries(archive).keys.sorted(),
            )
        } finally {
            root.dispose()
        }
    }
}
