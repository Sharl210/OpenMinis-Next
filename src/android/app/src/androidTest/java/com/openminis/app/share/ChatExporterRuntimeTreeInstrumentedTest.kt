package com.openminis.app.share

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.feature.runtime.RuntimeModelSnapshot
import com.openminis.app.feature.runtime.RuntimeSessionNode
import com.openminis.app.feature.runtime.RuntimeTopologySnapshot
import android.net.Uri
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [T-android-runtime-tree-export] The **production** session-tree export, on a
 * device, against real SQLite and a real `FileProvider`.
 *
 * WHY THIS FILE HAD TO EXIST. The requirement is explicit:
 *
 * > "每个子会话可以单独导出 JSON 或纯文本；总会话可以导出 ZIP，每个子会话一个目录，
 * >  主会话为顶层单文件，统一选择 JSON 或纯文本后打包。如果没有子代理，则直接按原来
 * >  默认行为导出裸文件。子会话里还可能有子会话，导出时逐级建目录；任意会话若包含
 * >  子会话，导出会话就是 ZIP。"  (`request.md:3`)
 *
 * The code that satisfies it is `ChatExporter.exportForRuntimeTree` (reached from the
 * session list's long-press export menu). Before this file, that function had **no
 * behavioural test at all** — its only "coverage" was
 * `ChatExporterRuntimeTreeSourceTest`, which asserts that the source text contains the
 * substring `"fun exportForRuntimeTree("`. A source-text check passes no matter what the
 * body does, so the production path could have returned an empty ZIP and stayed green.
 *
 * Meanwhile `SessionTreeRuntime.exportZip` — a *different* implementation of the same
 * idea, with **zero production callers** — does have a real ZIP round-trip test. The net
 * effect was the worst of both worlds: the requirement's live implementation was
 * unverified while a green test was attached to dead code. This file fixes the first half
 * by testing what actually runs.
 *
 * IT IS INSTRUMENTED ON PURPOSE. Three of the things it must prove are not reachable from
 * the JVM: real `FileProvider.getUriForFile` (which also re-verifies the authority after
 * the package rename to `com.openminis.next`), real SQLite rows behind
 * `streamTranscript`, and real files written to `cacheDir`. Written to be runnable rather
 * than decorative: if it has not been executed in an environment, report it as UNRUN, not
 * as passing coverage.
 */
@RunWith(AndroidJUnit4::class)
class ChatExporterRuntimeTreeInstrumentedTest {

    private var database: AppDatabase? = null

    @After
    fun tearDown() {
        database?.close()
        database = null
    }

    private fun context(): Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun repository(): ChatRepository {
        val db = Room.inMemoryDatabaseBuilder(context(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        database = db
        return ChatRepository(db.chatDao())
    }

    /** A session row plus one assistant message, so the transcript is non-empty. */
    private suspend fun seed(repo: ChatRepository, id: String, title: String, text: String) {
        repo.insertSession(
            ChatSessionEntity(id = id, title = title, modelId = "test-model", createdAt = 1L, updatedAt = 1L),
        )
        repo.appendMessage(
            sessionId = id,
            role = "assistant",
            partsJson = """[{"type":"text","value":"$text"}]""",
        )
    }

    private fun node(id: String, parentId: String?, rootId: String, depth: Int) = RuntimeSessionNode(
        id = id,
        parentId = parentId,
        rootId = rootId,
        depth = depth,
        model = RuntimeModelSnapshot(provider = "test", model = "test-model"),
        createdAtMillis = 1L,
        updatedAtMillis = 1L,
    )

    private fun snapshot(vararg nodes: RuntimeSessionNode) =
        RuntimeTopologySnapshot(nodes = nodes.toList(), edges = emptyList(), subscriptions = emptyList())

    /**
     * The bytes behind an exported [uri], read **through the ContentResolver**.
     *
     * Deliberately not `File(uri.path)`: a FileProvider URI is
     * `content://<authority>/shared/<name>`, so `uri.path` is the path *relative to
     * the provider root* (e.g. `/shared/x.json`), never an absolute filesystem path.
     * `File(uri.path)` would silently point at a nonexistent path under the root and
     * make every assertion below fail for the wrong reason. Reading through the
     * resolver is also the stronger check: it proves the provider can actually serve
     * the artifact to the receiving app, which is what sharing does.
     */
    private fun bytesOf(uri: android.net.Uri): ByteArray =
        requireNotNull(context().contentResolver.openInputStream(uri)) {
            "content resolver could not open $uri"
        }.use { it.readBytes() }

    /** Entry name -> uncompressed size, for the ZIP behind [uri]. */
    private fun zipEntries(uri: android.net.Uri): Map<String, Int> = buildMap {
        ZipInputStream(bytesOf(uri).inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                put(entry.name, zip.readBytes().size)
                zip.closeEntry()
            }
        }
    }

    /** Entry name -> decoded text, for the ZIP behind [uri]. */
    private fun zipTexts(uri: android.net.Uri): Map<String, String> = buildMap {
        ZipInputStream(bytesOf(uri).inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                put(entry.name, zip.readBytes().toString(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    /** File name of the exported artifact, taken from the URI's last path segment. */
    private fun nameOf(uri: android.net.Uri): String = uri.lastPathSegment.orEmpty()

    // ─────────────────────────── leaf: bare file, not a ZIP ───────────────────────────

    @Test
    fun aSessionWithNoSubSessionsExportsABareFileNotAZip() = runBlocking {
        val repo = repository()
        seed(repo, ROOT, "Leaf", "hello leaf")

        val (uri, _) = ChatExporter.exportForRuntimeTree(
            context = context(),
            session = repo.getSession(ROOT)!!,
            repository = repo,
            format = "json",
            topology = snapshot(node(ROOT, null, ROOT, 0)),
        )

        val name = nameOf(uri)
        val bytes = bytesOf(uri)
        assertTrue("the provider must actually hand over bytes for $uri", bytes.isNotEmpty())
        // ".zip" here would mean a leaf was wrongly packaged as an archive — the
        // requirement says a session without sub-sessions keeps the default bare export.
        assertFalse("a leaf must not be packed as a zip: $name", name.endsWith(".zip"))
        assertTrue("expected a .json bare file, got $name", name.endsWith(".json"))
        assertFalse(
            "a bare JSON export must not carry the ZIP magic bytes",
            bytes.take(2) == listOf<Byte>(0x50, 0x4B),
        )
        assertTrue(
            "the transcript text should be present",
            bytes.toString(Charsets.UTF_8).contains("hello leaf"),
        )
    }

    @Test
    fun theTextFormatProducesATxtBareFile() = runBlocking {
        val repo = repository()
        seed(repo, ROOT, "Leaf", "plain text body")
        val (uri, _) = ChatExporter.exportForRuntimeTree(
            context = context(),
            session = repo.getSession(ROOT)!!,
            repository = repo,
            format = "text",
            topology = snapshot(node(ROOT, null, ROOT, 0)),
        )
        val name = nameOf(uri)
        assertTrue("expected a .txt bare file, got $name", name.endsWith(".txt"))
        assertTrue(
            "the plain-text transcript should be present",
            bytesOf(uri).toString(Charsets.UTF_8).contains("plain text body"),
        )
    }

    // ─────────────────────── a session with children becomes a ZIP ─────────────────────

    @Test
    fun aSessionWithASubSessionExportsAZipHoldingOneDirectoryPerSession() = runBlocking {
        val repo = repository()
        seed(repo, ROOT, "Tree", "root body")
        seed(repo, CHILD, "Delegated", "child body")

        val (uri, _) = ChatExporter.exportForRuntimeTree(
            context = context(),
            session = repo.getSession(ROOT)!!,
            repository = repo,
            format = "json",
            topology = snapshot(
                node(ROOT, null, ROOT, 0),
                node(CHILD, ROOT, ROOT, 1),
            ),
        )

        val name = nameOf(uri)
        assertTrue("a session with sub-sessions must export a zip, got $name", name.endsWith(".zip"))

        val entries = zipEntries(uri)
        // The root is a top-level single file inside the archive …
        assertTrue(
            "root transcript missing; entries=$entries",
            entries.keys.any { it == "$ROOT/messages.json" },
        )
        assertTrue(
            "root session metadata missing; entries=$entries",
            entries.keys.any { it == "$ROOT/session.json" },
        )
        // … and each sub-session lives in its own directory under children/.
        assertTrue(
            "child must be placed under children/; entries=$entries",
            entries.keys.any { it == "$ROOT/children/$CHILD/messages.json" },
        )
        assertTrue(
            "child must be placed under children/; entries=$entries",
            entries.keys.any { it == "$ROOT/children/$CHILD/session.json" },
        )
    }

    @Test
    fun aGrandchildIsNestedOneLevelDeeper() = runBlocking {
        // "子会话里还可能有子会话，导出时逐级建目录" — the depth must be reflected in the path.
        val repo = repository()
        seed(repo, ROOT, "Tree", "root body")
        seed(repo, CHILD, "Delegated", "child body")
        seed(repo, GRANDCHILD, "Delegated twice", "grandchild body")

        val (uri, _) = ChatExporter.exportForRuntimeTree(
            context = context(),
            session = repo.getSession(ROOT)!!,
            repository = repo,
            format = "json",
            topology = snapshot(
                node(ROOT, null, ROOT, 0),
                node(CHILD, ROOT, ROOT, 1),
                node(GRANDCHILD, CHILD, ROOT, 2),
            ),
        )

        val entries = zipEntries(uri)
        val expected = "$ROOT/children/$CHILD/children/$GRANDCHILD/messages.json"
        assertTrue(
            "the grandchild must be one directory deeper ($expected); entries=$entries",
            entries.keys.any { it == expected },
        )
        // And the whole point: every session's own transcript is present, not only the root's.
        assertTrue(entries.keys.any { it == "$ROOT/children/$CHILD/messages.json" })
        assertTrue(entries.keys.any { it == "$ROOT/messages.json" })
    }

    @Test
    fun eachArchivedSessionCarriesItsOwnTranscriptContent() = runBlocking {
        // Guards against "the right file names, but every entry holds the root's text" —
        // a mistake that name-only assertions cannot see.
        val repo = repository()
        seed(repo, ROOT, "Tree", "ROOT_MARKER")
        seed(repo, CHILD, "Delegated", "CHILD_MARKER")

        val (uri, _) = ChatExporter.exportForRuntimeTree(
            context = context(),
            session = repo.getSession(ROOT)!!,
            repository = repo,
            format = "json",
            topology = snapshot(node(ROOT, null, ROOT, 0), node(CHILD, ROOT, ROOT, 1)),
        )

        val contents = zipTexts(uri)
        val rootBody = contents["$ROOT/messages.json"].orEmpty()
        val childBody = contents["$ROOT/children/$CHILD/messages.json"].orEmpty()
        assertTrue("root entry must hold the root's text; got $rootBody", rootBody.contains("ROOT_MARKER"))
        assertTrue("child entry must hold the child's text; got $childBody", childBody.contains("CHILD_MARKER"))
        assertFalse("the child entry must not be a copy of the root's", childBody.contains("ROOT_MARKER"))
    }

    @Test
    fun theTextFormatKeepsTheTxtExtensionInsideTheArchive() = runBlocking {
        val repo = repository()
        seed(repo, ROOT, "Tree", "root body")
        seed(repo, CHILD, "Delegated", "child body")

        val (uri, _) = ChatExporter.exportForRuntimeTree(
            context = context(),
            session = repo.getSession(ROOT)!!,
            repository = repo,
            format = "text",
            topology = snapshot(node(ROOT, null, ROOT, 0), node(CHILD, ROOT, ROOT, 1)),
        )

        val entries = zipEntries(uri)
        assertTrue("expected messages.txt; entries=$entries", entries.keys.any { it == "$ROOT/messages.txt" })
        assertTrue(
            "the child transcript must sit under children/ and keep the .txt extension; entries=$entries",
            entries.keys.any { it == "$ROOT/children/$CHILD/messages.txt" },
        )
        // [T-transcript-ext-only] 这里原本断言 `entries.keys.none { it.endsWith(".json") }` ——
        // 而 `ChatExporter.kt:223` **无条件**（与 format 无关）为每个会话写一个
        // `session.json` 元数据边车。于是"没有条目以 .json 结尾"对**任何** text 导出都为假：
        // 它禁掉的是边车，而不是本用例名字所指的东西（归档内**转录正文**的扩展名）。
        // 注意同一文件 `theTextFormatKeepsTheTxtExtensionInsideTheArchive` 的说明文字写的是
        // "expected the child under children/ with .txt"，但原断言**根本没有检查子条目** ——
        // 说明与断言不符，且方向相反（一个该存在的没被检查，一个不该被禁的被禁了）。
        // 现在只对转录正文下判据，并把"子条目确实在 children/ 下且是 .txt"补成正向断言。
        assertTrue(
            "no transcript may be written as .json inside a text archive; entries=$entries",
            entries.keys.none { it.endsWith("/messages.json") || it == "messages.json" },
        )
    }

    // ────────────── integration: the renamed package still resolves its provider ──────────────

    @Test
    fun theReturnedUriResolvesThroughTheAppsFileproviderAuthority() = runBlocking {
        // `exportForRuntimeTree` builds the authority as "${context.packageName}.fileprovider"
        // and calls FileProvider.getUriForFile. That throws if the manifest declares a
        // different authority — which is exactly what a careless package rename does. So this
        // test also re-verifies that renaming applicationId to com.openminis.next left the
        // FileProvider wired up.
        val repo = repository()
        seed(repo, ROOT, "Tree", "root body")
        seed(repo, CHILD, "Delegated", "child body")

        val (uri, _) = ChatExporter.exportForRuntimeTree(
            context = context(),
            session = repo.getSession(ROOT)!!,
            repository = repo,
            format = "json",
            topology = snapshot(node(ROOT, null, ROOT, 0), node(CHILD, ROOT, ROOT, 1)),
        )

        assertEquals("content", uri.scheme)
        assertEquals(
            "the uri authority must follow the installed package name",
            "${context().packageName}.fileprovider",
            uri.authority,
        )
        // Reading through the provider is what a receiving app does when the user shares the file.
        val resolved = context().contentResolver.openInputStream(uri)
        assertTrue("the exported uri must be readable through the provider", resolved != null)
        resolved!!.close()
    }

    private companion object {
        // Short ids on purpose: the exporter truncates each path segment to 16 chars, so
        // longer ids would make this file assert against a truncation instead of the layout.
        const val ROOT = "export-root"
        const val CHILD = "export-child"
        const val GRANDCHILD = "export-grand"
    }
}
