package com.openminis.app.feature.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The diagnostic half of `request.md:248`: an abnormal stop must report
 * 「停止运行的对应的那个请求的状态码以及错误响应以及响应头」 to the direct
 * parent, while a normal (HTTP 200) end must stay silent about them.
 *
 * ## Why this file exists
 *
 * The provider collects the status code, the error body and the response headers
 * (`LLMRequestDiagnostics`), `RuntimeChildRunner` puts them into a
 * `RuntimeStopReport`, and `RuntimeSystemMessageMetadata.toJson` can serialize
 * all three. Two hops then dropped them on the floor:
 *
 *  1. `abort()` built its own report from a reason string, so the structured
 *     fields — `responseHeaders` above all, which no reason line carries — never
 *     reached the parent notification;
 *  2. even once they arrived in `taskIntent`, `RuntimeInboxSurfacing` read none of
 *     them: the card said only "stopped abnormally" and a NOTIFY envelope produced
 *     no model reminder at all.
 *
 * Both are observable, so they are asserted as behaviour rather than as source
 * text: a REAL `RuntimeTreeStore` on a real temp directory, a REAL
 * `RuntimeSessionCoordinator`, the production `finishChild` stop path, the
 * production `drainInbox`, and the production renderer.
 *
 * ## What is NOT executed
 *
 * `ChatViewModel.surfaceRuntimeInbox()` — the last hop, which turns a `Notice`
 * into an `appendSystemInfo` card — cannot be constructed here (no Robolectric,
 * no `Dispatchers.Main`, and a constructor that needs Room and the provider
 * repository). That hop is unpinned by this file; the notice contract it consumes
 * is what is executed here, and `RuntimeParentInboxDrainTest` pins the call site
 * itself.
 */
class RuntimeAbnormalDiagnosticsSurfacingTest {

    private class Fixture {
        val dir: File = Files.createTempDirectory("runtime-diag-surfacing").toFile()
        private val store: RuntimeTreeStore = RuntimeTreeStore.openForTest(
            File(dir, "session-tree.json"),
        ) { source, target ->
            Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        }
        val coordinator: RuntimeSessionCoordinator = run {
            val ctor = RuntimeSessionCoordinator::class.java
                .getDeclaredConstructor(RuntimeTreeStore::class.java)
            ctor.isAccessible = true
            ctor.newInstance(store)
        }
        val names = mutableMapOf<String, String>()
        val surfacing = RuntimeInboxSurfacing { nodeId -> names[nodeId] }

        init {
            assertTrue(coordinator.startRoot("parent") != null)
            assertTrue(coordinator.startChild("parent", "child"))
        }

        fun dispose() {
            dir.deleteRecursively()
        }
    }

    /** Exactly what `RuntimeChildRunner.onFailure` hands the coordinator. */
    private fun providerFailureReport() = RuntimeStopReport(
        nodeId = "child",
        statusCode = 503,
        errorResponse = "upstream unavailable",
        responseHeaders = mapOf("x-request-id" to "r1", "retry-after" to "30"),
        debugInfo = "OpenAI HTTP response",
        lastSentBody = "x".repeat(250),
        completedNormally = false,
    )

    private fun notices(f: Fixture): List<RuntimeInboxSurfacing.Notice> {
        val drained = f.coordinator.drainInbox("parent")
        assertEquals("the parent's own mailbox must hold exactly one notice: $drained", 1, drained.size)
        return f.surfacing.noticesFor(drained)
    }

    /** The single notice for a fixture, failing loudly rather than throwing IndexOutOfBounds. */
    private fun onlyNotice(f: Fixture): RuntimeInboxSurfacing.Notice =
        notices(f).firstOrNull() ?: error("the parent was told nothing about its child")

    @Test
    fun `a provider failure reaches the parent as structured diagnostics, not only a reason line`() {
        val f = Fixture()
        try {
            f.coordinator.finishChild("child", failed = true, report = providerFailureReport())

            val notice = onlyNotice(f)
            val reminder = notice.modelReminder
            assertTrue("an abnormal stop must reach the parent agent at all", reminder != null)
            val text = reminder.orEmpty()

            assertTrue(
                "request.md:248 requires the status code; got: $text",
                text.contains("503"),
            )
            assertTrue(
                "request.md:248 requires the error response; got: $text",
                text.contains("upstream unavailable"),
            )
            assertTrue(
                "request.md:248 requires the response headers; got: $text",
                text.contains("x-request-id: r1"),
            )
            assertTrue(
                "every collected header must travel, not just the first; got: $text",
                text.contains("retry-after: 30"),
            )
            assertTrue(
                "the last body the model sent must travel (capped at 200 chars); got: $text",
                text.contains("x".repeat(200)),
            )
            assertTrue(
                "the parent must be able to act on it; got: $text",
                text.contains("restart"),
            )
        } finally {
            f.dispose()
        }
    }

    @Test
    fun `the system card names the status code and stays inside its single line`() {
        val f = Fixture()
        try {
            f.names["child"] = "Parse the server logs"
            f.coordinator.finishChild("child", failed = true, report = providerFailureReport())

            val card = onlyNotice(f).cardText
            assertTrue("the card must name the sub-agent: $card", card.contains("Parse the server logs"))
            assertTrue("the card must say it stopped: $card", card.contains("stopped abnormally"))
            assertTrue("the card must carry the status code: $card", card.contains("503"))
            assertTrue(
                "the card is drawn with maxLines=1, so it must fit the budget: len=${card.length} $card",
                card.length <= RuntimeInboxSurfacing.MAX_CARD_CHARS,
            )
        } finally {
            f.dispose()
        }
    }

    @Test
    fun `a normal completion still reports no diagnostics at all`() {
        val f = Fixture()
        try {
            f.coordinator.finishChild("child", failed = false)

            val notice = onlyNotice(f)
            val card = notice.cardText
            assertFalse("a normal end must not carry a status code: $card", card.contains("HTTP"))
            assertTrue(
                "a normal end is a plain completion: $card",
                card.contains("finished"),
            )
            assertTrue(
                "a normal end must not interrupt the parent agent with a reminder",
                notice.modelReminder == null,
            )
        } finally {
            f.dispose()
        }
    }

    @Test
    fun `an abnormal stop with no HTTP response invents no diagnostics`() {
        val f = Fixture()
        try {
            // The lease-reaper / pre-first-model-call stop: nothing ever reached a
            // provider, so there is no status code to report and none may appear.
            f.coordinator.finishChild(
                "child",
                failed = true,
                report = RuntimeStopReport(
                    nodeId = "child",
                    debugInfo = "lease expired",
                    completedNormally = false,
                ),
            )

            val notice = onlyNotice(f)
            assertFalse(
                "no provider round-trip happened, so no status may be invented: ${notice.cardText}",
                notice.cardText.contains("HTTP"),
            )
            val text = notice.modelReminder.orEmpty()
            assertTrue("the parent must still be told it stopped: $text", text.contains("stopped abnormally"))
            assertTrue("the reason must survive: $text", text.contains("lease expired"))
        } finally {
            f.dispose()
        }
    }

    @Test
    fun `a hostile error body cannot blow the parent's context up`() {
        val f = Fixture()
        try {
            f.coordinator.finishChild(
                "child",
                failed = true,
                report = RuntimeStopReport(
                    nodeId = "child",
                    statusCode = 500,
                    errorResponse = "x".repeat(50_000),
                    completedNormally = false,
                ),
            )

            val text = onlyNotice(f).modelReminder.orEmpty()
            assertTrue(
                "a 50k error body is a context bomb, not a debugging aid (len=${text.length})",
                text.length < 3_000,
            )
        } finally {
            f.dispose()
        }
    }
}
