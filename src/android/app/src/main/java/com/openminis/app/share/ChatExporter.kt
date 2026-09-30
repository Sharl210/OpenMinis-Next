package com.openminis.app.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.model.MessagePartsCodec
import com.openminis.app.data.model.MessageProvenance
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.nodeIdentity
import com.openminis.app.data.repository.sessionSubtreeDeletionPlan
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Streaming chat export — T-export-optimize (b443b54d).
 *
 * Previously [SessionListScreen.exportSession] loaded every [MessageEntity]
 * for a session at once, built the full JSON / TXT payload as a single
 * [String] in memory, and handed that to [Intent.EXTRA_TEXT]. Hundreds of
 * messages caused jank, ghosting and OOM crashes.
 *
 * This exporter:
 *   - paginates the session via [ChatRepository.loadMessagePageRaw] in
 *     batches of [BATCH_SIZE], releasing each batch after it's written;
 *   - streams the serialized output into `cacheDir/export-staging/<uuid>/`
 *     using a [BufferedWriter] so peak memory stays bounded;
 *   - wraps the staged transcript + a `session.json` metadata sidecar in
 *     a single [ZipOutputStream]-built archive;
 *   - moves the final `.zip` into `cacheDir/shared/` (already declared in
 *     `file_provider_paths.xml`), where it can be handed out via
 *     [FileProvider];
 *   - cleans the staging directory on success and failure.
 *
 * Runs on [Dispatchers.IO]; [progress] is a [StateFlow] so a future UI
 * (progress overlay) can subscribe without re-architecting the call site.
 */
object ChatExporter {

    private const val BATCH_SIZE = 50
    private const val LOG_CATEGORY = "ChatExporter"

    /**
     * [T-android-human-turn-count-parity] Speaker label for the plain-text
     * export.
     *
     * Exposed (and pure) so the mapping is unit-testable without an Android
     * runtime: it is the one place the text export has to decide authorship, and
     * it used to decide from the `role` column alone — which mislabels every tool
     * result and `<system-reminder>` resume entry (both persisted with the API's
     * `user` role) as the human's own words.
     */
    internal fun speakerLabel(role: String, partsJson: String): String = when {
        role == "user" && MessagePartsCodec.hasHumanTurnContent(partsJson) -> "You"
        role == "user" -> when (MessagePartsCodec.provenanceOf(partsJson)) {
            MessageProvenance.MANUAL_USER -> "You"
            MessageProvenance.SYSTEM_CARD -> "System"
            else -> "Tool"
        }
        role == "system" -> "System"
        else -> "Assistant"
    }

    sealed interface Progress {
        data object Idle : Progress
        data class Running(val done: Int, val total: Int) : Progress
        data class Done(val zipUri: Uri, val summary: Summary) : Progress
        data class Failed(val throwable: Throwable) : Progress
    }

    /**
     * Lightweight summary of a finished export. Populated as we stream so
     * the multi-select / "ready to share" UI can render a key-value preview
     * without re-reading the payload.
     */
    data class Summary(
        val format: String,              // "json" | "text"
        val messageCount: Int,
        val firstCreatedAt: Long?,       // ms, or null if empty
        val lastCreatedAt: Long?,
        val imageAttachments: Int,
        val videoAttachments: Int,
        val estimatedBytes: Long,
        /**
         * [T-android-runtime-export-honest-degradation] Runtime node ids the durable
         * tree still lists whose Chat row is gone, so this export could NOT include
         * them. Empty means every node the export set contains was written.
         *
         * The two sets can differ: the runtime tree is authoritative for WHICH nodes
         * belong to a session, while the Chat rows are what can actually be written,
         * and the delete path clears Chat rows without touching the runtime tree. A
         * node can therefore outlive its chat. Those ids used to be dropped by a
         * `mapNotNull`, which made a short archive indistinguishable from a complete
         * one: the ZIP still succeeded and the share sheet still offered
         * `application/zip`.
         *
         * WHAT "EMPTY" DOES *NOT* CLAIM. The export set is defined by
         * [runtimeExportNodeIdsFor] and an empty [missingRuntimeNodeIds] only says
         * nothing from THAT set was dropped — it is not a claim that the runtime tree
         * holds no other node belonging to this conversation. Two gaps live outside
         * this list and are reported honestly rather than papered over:
         *  - an attachment whose file is missing from disk is skipped without being
         *    counted (`writeReferencedAttachments`), so an archive can still differ
         *    byte-wise from a full one while reporting nothing here;
         *  - a transcript is capped by what the repository returns, and the summary
         *    carries no expected message count, so a truncated transcript also stays
         *    quiet here.
         */
        val missingRuntimeNodeIds: List<String> = emptyList(),
    )

    private val _progress = MutableStateFlow<Progress>(Progress.Idle)
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    /**
     * Export according to the durable runtime tree: a leaf is shared as a bare
     * JSON/TXT file; a session with runtime children becomes a recursive ZIP.
     * Runtime node ids are the Chat session ids created by RuntimeSessionCoordinator.
     */
    suspend fun exportForRuntimeTree(
        context: Context,
        session: ChatSessionEntity,
        repository: ChatRepository,
        format: String,
        topology: com.openminis.app.feature.runtime.RuntimeTopologySnapshot,
    ): Pair<Uri, Summary> = withContext(Dispatchers.IO) {
        val (file, summary) = writeRuntimeTreeExport(context, session, repository, format, topology)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        _progress.value = Progress.Done(uri, summary)
        uri to summary
    }

    /**
     * [T-android-runtime-export-honest-degradation] Everything [exportForRuntimeTree]
     * does before it mints the `content://` Uri.
     *
     * Split out for one reason: `FileProvider.getUriForFile` resolves its authority
     * through the app's `PackageManager`, which does not exist outside a packaged
     * APK — so a plain JVM test could reach the archive on disk but never the
     * returned [Summary]. The degradation this export has to report lives in that
     * [Summary], so the seam has to sit here rather than at the call site.
     */
    internal suspend fun writeRuntimeTreeExport(
        context: Context,
        session: ChatSessionEntity,
        repository: ChatRepository,
        format: String,
        topology: com.openminis.app.feature.runtime.RuntimeTopologySnapshot,
    ): Pair<File, Summary> = withContext(Dispatchers.IO) {
        require(format == "json" || format == "text") { "format must be json or text" }
        val descendants = topology.descendantsForChatSession(session.id)
        val sharedDir = File(context.cacheDir, "shared").apply { mkdirs() }
        val safeTitle = (session.title ?: "conversation").replace(Regex("[^A-Za-z0-9_-]+"), "_").take(64).ifEmpty { "conversation" }
        val ext = if (format == "json") "json" else "txt"
        if (descendants.isEmpty()) {
            val file = File(sharedDir, "$safeTitle-${session.id.take(8)}.$ext")
            val summary = streamTranscript(repository, session, format == "json", file)
            return@withContext file to summary
        }
        val workDir = File(context.cacheDir, "export-staging/${UUID.randomUUID()}").apply { mkdirs() }
        try {
            // [T-android-runtime-export-honest-degradation] The runtime tree decides
            // WHICH nodes belong to this export, but only a node whose Chat row is
            // still there can actually be written. Collect the ids that cannot be
            // written instead of letting them disappear: they go into the [Summary]
            // the caller gets back AND into the archive itself, so neither the app
            // nor a reader of the .zip has to guess whether it is complete.
            val childSessions = ArrayList<ChatSessionEntity>(descendants.size)
            val missingNodeIds = ArrayList<String>()
            for (descendant in descendants) {
                val chat = repository.getSession(descendant.id)
                if (chat != null) childSessions += chat else missingNodeIds += descendant.id
            }
            // The walk above is `parentId`-based, and one real tree shape escapes it
            // entirely: a conversation that was opened again gets a brand-new root
            // `<sessionId>#run-<n>` with `parentId == null`, so a child of that re-run
            // has no `parentId` path back to this session at all. Such a branch is not
            // merely unwritten — it is invisible to `descendantsForChatSession`, so it
            // would not reach `missingNodeIds` either and the archive would keep saying
            // "complete". Naming it here is the difference between a report and a
            // guess. The branch is NOT added to the archive: which directory a re-run
            // node should occupy is a product decision (`B` and `B#run-2` are the same
            // conversation, so a naive inclusion would export it twice), and this fix
            // deliberately stays on the reporting side.
            missingNodeIds += topology.uncoveredExportNodeIds(session.id, descendants)
            val all = listOf(session) + childSessions
            var rootSummary: Summary? = null
            val zipFile = File(sharedDir, "$safeTitle-${session.id.take(8)}.zip")
            ZipOutputStream(FileOutputStream(zipFile).buffered()).use { zos ->
                for (chat in all) {
                    val transcript = File(workDir, "${chat.id}.$ext")
                    val summary = streamTranscript(repository, chat, format == "json", transcript)
                    if (chat.id == session.id) rootSummary = summary
                    val node = topology.nodes.firstOrNull { it.id == chat.id }
                    val parts = ArrayDeque<String>()
                    var current = node
                    while (current != null && current.id != session.id) {
                        parts.addFirst(current.id.take(16))
                        current = topology.nodes.firstOrNull { it.id == current.parentId }
                    }
                    val prefix = buildString {
                        append(session.id.take(16))
                        parts.forEach { append("/children/").append(it) }
                    }
                    zipFileEntry(zos, "$prefix/messages.$ext", transcript)
                    writeReferencedAttachments(context, repository, chat, prefix, zos)
                    zos.putNextEntry(ZipEntry("$prefix/session.json"))
                    zos.write(JSONObject().put("session_id", chat.id).put("title", chat.title ?: "").put("parent_id", node?.parentId).put("root_id", node?.rootId ?: session.id).put("depth", node?.depth ?: 0).toString(2).toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
                if (missingNodeIds.isNotEmpty()) {
                    // Only written when something IS missing, so its absence is itself
                    // the statement "this archive is complete".
                    zos.putNextEntry(ZipEntry(DEGRADED_MANIFEST_ENTRY))
                    zos.write(
                        degradedManifest(session.id, all, missingNodeIds)
                            .toString(2)
                            .toByteArray(Charsets.UTF_8),
                    )
                    zos.closeEntry()
                }
            }
            val summary = (rootSummary ?: Summary(format, 0, null, null, 0, 0, zipFile.length()))
                .copy(missingRuntimeNodeIds = missingNodeIds.toList())
            return@withContext zipFile to summary
        } finally {
            runCatching { workDir.deleteRecursively() }
        }
    }

    /**
     * [T-android-runtime-export-honest-degradation] Root-level archive entry written
     * ONLY when the runtime tree listed nodes this export could not include.
     */
    internal const val DEGRADED_MANIFEST_ENTRY = "export-degraded.json"

    /**
     * [T-android-runtime-export-honest-degradation] Runtime node ids that belong to
     * [rootSessionId]'s conversation but are NOT reachable by [alreadyWalking], the
     * export's own `parentId`-based descendant walk.
     *
     * WHY A SECOND DEFINITION OF "THE SUBTREE" EXISTS AT ALL — and why reusing the
     * first one fixes the problem instead of doubling it. A conversation that is
     * opened again gets a brand-new root node `<sessionId>#run-<n>` with
     * `parentId == null` (`RuntimeSessionCoordinator.startRoot`,
     * `SessionTreeRuntime.createRoot`), and its children then hang off that new
     * root. The repository already documents this shape, because the DELETE path
     * hit it first: `C.parentId == "B#run-2"`, whose parent is `null`, so a
     * `parentId` walk from the ancestor loses the whole branch
     * (`SessionSubtreeDeletionPlan`'s KDoc). Deletion answered it with a fixed
     * point over session *identities*; export never did, so such a branch was
     * neither written NOR counted — the archive stayed silent and still called
     * itself complete.
     *
     * This asks `sessionSubtreeDeletionPlan` — the closure deletion already uses —
     * for the full shape of the conversation, then subtracts what the export
     * walk already covers. Reusing that closure is the point: one definition of
     * "belongs to this conversation" means a tree shape deletion handles cannot be
     * a shape export forgets. `sessionExists = { true }` asks about the SHAPE of
     * the tree, not about which chat rows still exist (that is the other half of
     * [Summary.missingRuntimeNodeIds], computed by the caller).
     *
     * Identity, not id, decides "already covered": `B` and `B#run-2` are the same
     * conversation, so a node is only newly missing when no node of its
     * conversation was exported at all. Without that normalization every ordinary
     * re-run would be reported as a missing conversation the moment it has a
     * second root — a false alarm on a shape the export handles correctly today.
     *
     * The returned nodes are reported, never archived: which directory a re-run
     * node should occupy is a product decision, so this stays on the reporting
     * side and leaves the archive's layout exactly as it was.
     */
    internal fun com.openminis.app.feature.runtime.RuntimeTopologySnapshot.uncoveredExportNodeIds(
        rootSessionId: String,
        alreadyWalking: List<com.openminis.app.feature.runtime.RuntimeSessionNode>,
    ): List<String> {
        val plan = sessionSubtreeDeletionPlan(
            nodes = nodes,
            targetSessionIds = listOf(rootSessionId),
            sessionExists = { true },
        )
        val walkedIds = alreadyWalking.mapTo(HashSet<String>()) { it.id }
        val walkedIdentities = alreadyWalking.mapTo(HashSet<String>()) { nodeIdentity(it.id) }
        val rootIdentity = nodeIdentity(rootSessionId)
        return plan.runtimeNodeIds.filter { id ->
            // The conversation's own node(s) are the archive's root, not descendants.
            nodeIdentity(id) != rootIdentity &&
                id !in walkedIds &&
                nodeIdentity(id) !in walkedIdentities
        }
    }

    private fun degradedManifest(
        rootSessionId: String,
        exported: List<ChatSessionEntity>,
        missing: List<String>,
    ): JSONObject = JSONObject()
        .put("complete", false)
        .put("root_session_id", rootSessionId)
        .put("exported_node_ids", JSONArray(exported.map { it.id }))
        .put("missing_count", missing.size)
        .put("missing_runtime_node_ids", JSONArray(missing))

    /**
     * Stream-export [session] in [format] (`"json"` | `"text"`) and return
     * a content [Uri] pointing at the zipped archive, suitable for
     * [Intent.ACTION_SEND]. Throws on failure; caller's coroutine scope
     * decides how to surface it. [Progress] is published to [progress] as
     * batches are written.
     */
    suspend fun exportToZip(
        context: Context,
        session: ChatSessionEntity,
        repository: ChatRepository,
        format: String,
    ): Pair<Uri, Summary> = withContext(Dispatchers.IO) {
        val isJson = format == "json"
        val ext = if (isJson) "json" else "txt"
        val stagingRoot = File(context.cacheDir, "export-staging")
        val workDir = File(stagingRoot, UUID.randomUUID().toString())
        if (!workDir.mkdirs() && !workDir.isDirectory) {
            throw IllegalStateException("export-staging mkdir failed: ${workDir.absolutePath}")
        }

        try {
            val transcriptFile = File(workDir, "messages.$ext")
            val summary = streamTranscript(repository, session, isJson, transcriptFile)

            val metaFile = File(workDir, "session.json")
            writeSessionMeta(metaFile, session, summary)

            // Build zip under shared/ so FileProvider can hand it out.
            val sharedDir = File(context.cacheDir, "shared").apply { mkdirs() }
            val safeTitle = (session.title ?: "conversation")
                .replace(Regex("[^A-Za-z0-9_-]+"), "_")
                .take(64)
                .ifEmpty { "conversation" }
            val zipFile = File(sharedDir, "${safeTitle}-${session.id.take(8)}.zip")
            if (zipFile.exists()) zipFile.delete()

            ZipOutputStream(FileOutputStream(zipFile).buffered()).use { zos ->
                zipFileEntry(zos, "messages.$ext", transcriptFile)
                zipFileEntry(zos, "session.json", metaFile)
            }

            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, zipFile)
            _progress.value = Progress.Done(uri, summary)
            AppLogger.info(LOG_CATEGORY, "exportToZip ok: ${zipFile.absolutePath} (${zipFile.length()} bytes, ${summary.messageCount} msgs)")
            uri to summary
        } catch (t: Throwable) {
            _progress.value = Progress.Failed(t)
            AppLogger.error(LOG_CATEGORY, "exportToZip failed: ${t.message}")
            throw t
        } finally {
            // Always clean staging — the zip itself lives under shared/.
            runCatching { workDir.deleteRecursively() }
        }
    }

    private suspend fun streamTranscript(
        repository: ChatRepository,
        session: ChatSessionEntity,
        isJson: Boolean,
        out: File,
    ): Summary {
        val total = repository.messageCount(session.id)
        var done = 0
        var first: Long? = null
        var last: Long? = null
        var images = 0
        var videos = 0
        var bytes = 0L

        _progress.value = Progress.Running(0, total)

        BufferedWriter(OutputStreamWriter(FileOutputStream(out), Charsets.UTF_8)).use { writer ->
            if (isJson) {
                // Stream a hand-rolled JSON array — `[ {…}, {…}, … ]` —
                // so we never materialize the whole list at once.
                writer.write("[")
                var firstEntry = true
                forEachBatch(repository, session.id, total) { batch ->
                    for (msg in batch) {
                        if (!firstEntry) writer.write(",")
                        firstEntry = false
                        val obj = JSONObject().apply {
                            put("id", msg.id)
                            put("role", msg.role)
                            put("content", msg.partsJson)
                            put("created_at", msg.createdAt)
                        }
                        val rendered = obj.toString()
                        writer.write(rendered)
                        bytes += rendered.length.toLong()
                        if (first == null) first = msg.createdAt
                        last = msg.createdAt
                        val (img, vid) = countAttachments(msg.partsJson)
                        images += img
                        videos += vid
                        done += 1
                    }
                    writer.flush()
                    _progress.value = Progress.Running(done, total)
                }
                writer.write("]")
            } else {
                writer.write(session.title ?: "Conversation")
                writer.write("\n\n")
                forEachBatch(repository, session.id, total) { batch ->
                    for (msg in batch) {
                        // [T-android-human-turn-count-parity] Label by who really
                        // authored the row, not by its `role` column — see
                        // [speakerLabel]. A shared text transcript must not show
                        // harness plumbing as the user's own words.
                        val role = speakerLabel(msg.role, msg.partsJson)
                        val text = extractPlainText(msg.partsJson)
                        writer.write(role)
                        writer.write(": ")
                        writer.write(text)
                        writer.write("\n\n")
                        bytes += text.length.toLong() + role.length + 4
                        if (first == null) first = msg.createdAt
                        last = msg.createdAt
                        val (img, vid) = countAttachments(msg.partsJson)
                        images += img
                        videos += vid
                        done += 1
                    }
                    writer.flush()
                    _progress.value = Progress.Running(done, total)
                }
            }
        }

        return Summary(
            format = if (isJson) "json" else "text",
            messageCount = done,
            firstCreatedAt = first,
            lastCreatedAt = last,
            imageAttachments = images,
            videoAttachments = videos,
            estimatedBytes = bytes,
        )
    }

    private suspend inline fun forEachBatch(
        repository: ChatRepository,
        sessionId: String,
        total: Int,
        block: (List<MessageEntity>) -> Unit,
    ) {
        if (total <= 0) return
        var offset = 0
        while (offset < total) {
            val batch = repository.loadMessagePageRaw(sessionId, offset, BATCH_SIZE)
            if (batch.isEmpty()) break
            block(batch)
            offset += batch.size
            if (batch.size < BATCH_SIZE) break
        }
    }

    private suspend fun writeReferencedAttachments(
        context: Context,
        repository: ChatRepository,
        session: ChatSessionEntity,
        prefix: String,
        zos: ZipOutputStream,
    ) {
        val mediaRoot = File(context.filesDir, "media").canonicalFile
        val seen = mutableSetOf<String>()
        val total = repository.messageCount(session.id)
        forEachBatch(repository, session.id, total) { batch ->
            for (message in batch) {
                val parts = runCatching { JSONArray(message.partsJson) }.getOrNull() ?: continue
                for (index in 0 until parts.length()) {
                    val part = parts.optJSONObject(index) ?: continue
                    if (part.optString("type") != "mediaRef") continue
                    val value = part.optJSONObject("value") ?: continue
                    val relative = value.optString("relativePath").trim()
                    if (relative.isEmpty() || !seen.add(relative)) continue
                    val source = runCatching { File(mediaRoot, relative).canonicalFile }.getOrNull() ?: continue
                    if (!source.toPath().startsWith(mediaRoot.toPath()) || !source.isFile) continue
                    val safeName = relative.replace('\\', '/').trimStart('/')
                    zipFileEntry(zos, "$prefix/attachments/$safeName", source)
                }
            }
        }
    }
    private fun writeSessionMeta(file: File, session: ChatSessionEntity, summary: Summary) {
        val meta = JSONObject().apply {
            put("id", session.id)
            put("title", session.title ?: "")
            put("model_id", session.modelId)
            put("created_at", session.createdAt)
            put("message_count", summary.messageCount)
            summary.firstCreatedAt?.let { put("first_created_at", it) }
            summary.lastCreatedAt?.let { put("last_created_at", it) }
            put("image_attachments", summary.imageAttachments)
            put("video_attachments", summary.videoAttachments)
            put("format", summary.format)
        }
        file.writeText(meta.toString(2), Charsets.UTF_8)
    }

    private fun zipFileEntry(zos: ZipOutputStream, name: String, file: File) {
        zos.putNextEntry(ZipEntry(name))
        FileInputStream(file).use { it.copyTo(zos, bufferSize = 16 * 1024) }
        zos.closeEntry()
    }

    private fun extractPlainText(partsJson: String): String = try {
        val arr = JSONArray(partsJson)
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("type") == "text") {
                // [T-android-retry-attachment-loss] Strip the persisted
                // <user-attached-files> XML inventory from the human-readable
                // text export — it's model-facing metadata, not chat content.
                // (The JSON export above keeps full-fidelity parts_json.)
                var value = obj.optString("value")
                val start = value.indexOf("<user-attached-files>")
                if (start >= 0) {
                    val endTag = "</user-attached-files>"
                    val end = value.indexOf(endTag, start)
                    value = if (end >= 0) {
                        value.substring(0, start) + value.substring(end + endTag.length)
                    } else {
                        value.substring(0, start)
                    }.trim()
                }
                if (value.isNotEmpty()) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(value)
                }
            }
        }
        sb.toString()
    } catch (_: Throwable) {
        partsJson
    }

    /** Best-effort `(images, videos)` count by walking parts_json. */
    private fun countAttachments(partsJson: String): Pair<Int, Int> = try {
        val arr = JSONArray(partsJson)
        var img = 0
        var vid = 0
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            when (obj.optString("type")) {
                "image", "image_url" -> img += 1
                "video", "video_url" -> vid += 1
            }
        }
        img to vid
    } catch (_: Throwable) {
        0 to 0
    }
}
