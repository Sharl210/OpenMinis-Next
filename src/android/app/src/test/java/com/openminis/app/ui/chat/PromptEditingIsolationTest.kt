package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMResponse
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.LLMProvider
import com.openminis.app.ui.chat.ChatViewModel.Companion.callCompactModelWithRetry
import com.openminis.app.ui.chat.ChatViewModel.Companion.callTitleModelWithRetry
import com.openminis.app.ui.settings.AgentBehaviorSettings
import com.openminis.app.ui.settings.agentRetryRunnerFromSettings
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `plans/ULW-2026-09-25-01/request.md:246` — the two editable prompts must be
 * editable **as two independent things**, and "reset to default" must be a real
 * per-prompt operation:
 *
 *   标题生成和压缩模型。他们不仅仅只是支持模型的选择，还要去支持对应的提示词的编辑，
 *   然后还要可以进行一个重置为默认的提示词
 *
 * The gap this file closes is *isolation*, which `ModelRoleSettingsEffectTest`
 * does not cover. That file proves each selector works on its own, and its
 * file-wide `source.contains("effectiveTitleSystemPrompt(providerRepository.titlePrompt)")`
 * does catch a plain field swap — the field name is inside the needle. What a
 * `contains` cannot see is the other way two roles collapse into one:
 * **another call site**. A duplicated prompt selection next to the compaction
 * dispatch, or a second title path that grows its own copy, leaves the original
 * line intact and that assertion green. So the wiring tests at the bottom are
 * per-call-site — every occurrence must read its own field and must NOT read the
 * other one — and count the occurrences so a new one has to be looked at.
 *
 * No Android, no device: the selectors are pure and both governing request
 * functions are `ChatViewModel.Companion` members reachable from a JVM test.
 */
class PromptEditingIsolationTest {

    // ── 1. Two prompts, not one prompt used twice ───────────────────────

    @Test
    fun `the two prompts have two different defaults`() {
        val title = effectiveTitleSystemPrompt(null)
        val compaction = effectiveCompactionSystemPrompt(null)

        assertNotEquals(
            "one default used for both roles is how 'two editable prompts' becomes one",
            title,
            compaction,
        )
        assertTrue(
            "the title default must keep the JSON shape the title parser depends on",
            title.contains("\"title\""),
        )
        assertTrue(
            "the compaction default must keep the structured summary contract",
            compaction.contains("MUST PRESERVE"),
        )
        assertFalse(
            "the compaction prompt must not carry the title JSON contract",
            compaction.contains("\"title\": \"...\""),
        )
    }

    @Test
    fun `editing one prompt leaves the other byte-identical`() {
        val both = RoleEdits(titlePrompt = "TITLE-ONLY EDIT", compactionPrompt = "COMPACTION-ONLY EDIT")

        assertEquals("TITLE-ONLY EDIT", both.titleSent)
        assertEquals("COMPACTION-ONLY EDIT", both.compactionSent)

        val compactionBefore = both.compactionSent
        both.titlePrompt = "TITLE EDIT v2"
        assertEquals("TITLE EDIT v2", both.titleSent)
        assertEquals(
            "changing the title edit must not move the compaction prompt by a single byte",
            compactionBefore,
            both.compactionSent,
        )

        val titleBefore = both.titleSent
        both.compactionPrompt = "COMPACTION EDIT v2"
        assertEquals("COMPACTION EDIT v2", both.compactionSent)
        assertEquals(
            "and the reverse direction must hold too",
            titleBefore,
            both.titleSent,
        )
    }

    @Test
    fun `resetting one prompt does not reset the other`() {
        // The UI's reset button writes null through ProviderRepository.resetTitlePrompt
        // / resetCompactionPrompt; null is exactly what this models.
        val both = RoleEdits(titlePrompt = "TITLE-ONLY EDIT", compactionPrompt = "COMPACTION-ONLY EDIT")

        both.titlePrompt = null
        assertEquals(TITLE_GEN_SYSTEM_PROMPT, both.titleSent)
        assertEquals(
            "resetting the title prompt must not discard the user's compaction edit",
            "COMPACTION-ONLY EDIT",
            both.compactionSent,
        )

        both.titlePrompt = "TITLE-ONLY EDIT"
        both.compactionPrompt = null
        assertEquals(COMPACTION_DEFAULT_SYSTEM_PROMPT, both.compactionSent)
        assertEquals(
            "resetting the compaction prompt must not discard the user's title edit",
            "TITLE-ONLY EDIT",
            both.titleSent,
        )
    }

    @Test
    fun `both prompts reset means both defaults, and that is the shipping state`() {
        val fresh = RoleEdits(titlePrompt = null, compactionPrompt = null)

        assertEquals(TITLE_GEN_SYSTEM_PROMPT, fresh.titleSent)
        assertEquals(COMPACTION_DEFAULT_SYSTEM_PROMPT, fresh.compactionSent)
        assertNotEquals(fresh.titleSent, fresh.compactionSent)
    }

    // ── 2. The edit is sent verbatim: no templating, no localization ────

    @Test
    fun `an edited prompt is sent byte-identical, placeholders and all`() {
        // Pinned because "user-editable prompt" is easy to over-promise: if the
        // receive side substituted anything, the user's text would not be what
        // the model reads. It does not — the selector is the whole story.
        val edit = "TITLE {language} {locale} {{placeholder}} %s \$TITLE \u4E2D\u6587\u6A21\u677F"

        assertEquals(edit, effectiveTitleSystemPrompt(edit))
        assertEquals(edit, effectiveCompactionSystemPrompt(edit))
    }

    @Test
    fun `the editable system prompt carries no localization directive`() {
        // Localization exists, but it is not a property of the editable prompt:
        // titleLanguageDirective() is appended to the USER prompt at the call
        // site, leaving the system prompt alone. An edit must not be able to
        // suppress — or accidentally duplicate — it.
        val edit = "MY OWN TITLE RULES"
        val sent = effectiveTitleSystemPrompt(edit)

        assertFalse(sent.contains("app interface language"))
        assertFalse(sent.contains("\u754C\u9762\u8BED\u8A00"))
        assertEquals(edit, sent)
    }

    // ── 3. Normalization contract: the repository's null and this agree ──

    @Test
    fun `every value the repository stores as null means unset here too`() {
        // ProviderRepository's setters apply
        //   value?.trim()?.takeIf { it.isNotEmpty() }
        // so null, "" and every all-whitespace string are stored as null. Each of
        // those must land on the default rather than on an empty system prompt —
        // an empty one would silently drop the JSON-shape instruction the title
        // parser depends on.
        val storedAsNull: List<String?> = listOf(null, "", " ", "\t", "\n", "   \n\t  ", "\u3000")

        for (value in storedAsNull) {
            val label = "prompt stored as null for input " + describe(value)
            assertEquals("title $label must mean unset", TITLE_GEN_SYSTEM_PROMPT, effectiveTitleSystemPrompt(value))
            assertEquals(
                "compaction $label must mean unset",
                COMPACTION_DEFAULT_SYSTEM_PROMPT,
                effectiveCompactionSystemPrompt(value),
            )
        }
    }

    @Test
    fun `a setter-normalized value round-trips unchanged through the selector`() {
        // What the repository can actually hold: trimmed, non-empty. Feeding any
        // such value back in must return that exact value.
        val normalized = listOf("kept", "line one\nline two", "  inner spacing  kept", "\u4E2D\u6587\u63D0\u793A\u8BCD")

        for (value in normalized) {
            assertEquals(value, effectiveTitleSystemPrompt(value))
            assertEquals(value, effectiveCompactionSystemPrompt(value))
        }
    }

    @Test
    fun `a non-blank edit with surrounding whitespace is passed through untrimmed`() {
        // CHARACTERIZATION of a known divergence, not an endorsement:
        // ProviderRepository's setter TRIMS (so the UI can never store
        // "  kept  "), while the selector only rejects BLANK and does not trim.
        // The two normalizers therefore differ for input that reaches config
        // without going through the setter — the backup scalar merge
        // (ProviderConfig.mergeBackupScalars) and direct ProviderConfig
        // construction both bypass it. Documented here so that changing the
        // selector's normalization is a conscious decision with a red test,
        // rather than a silent second definition of "unset".
        assertEquals("  kept  ", effectiveTitleSystemPrompt("  kept  "))
        assertEquals("  kept  ", effectiveCompactionSystemPrompt("  kept  "))
    }

    // ── 4. Wiring: each call site reads its own field ────────────────────

    @Test
    fun `the title call sites read the title prompt and never the compaction prompt`() {
        val chat = chatViewModelSource()
        val sessions = sessionListViewModelSource()

        assertScoped(chat, "effectiveTitleSystemPrompt(", "ChatViewModel.kt", ".titlePrompt", ".compactionPrompt")
        assertScoped(
            sessions,
            "effectiveTitleSystemPrompt(",
            "SessionListViewModel.kt",
            ".titlePrompt",
            ".compactionPrompt",
        )
    }

    @Test
    fun `the compaction call site reads the compaction prompt and never the title prompt`() {
        assertScoped(
            chatViewModelSource(),
            "effectiveCompactionSystemPrompt(",
            "ChatViewModel.kt",
            ".compactionPrompt",
            ".titlePrompt",
        )
    }

    @Test
    fun `each prompt selector has exactly one call site per path`() {
        // A deliberate tripwire, not an accident: the selector exists so the auto
        // path and the manual regenerate path cannot drift into two definitions of
        // "the title prompt". Adding a legitimate third caller is expected to
        // require updating this test — that edit is the review point.
        val chat = chatViewModelSource()

        assertEquals(
            "one title selection site in ChatViewModel (the auto path)",
            1,
            occurrences(chat, "effectiveTitleSystemPrompt(").size,
        )
        assertEquals(
            "one compaction selection site in ChatViewModel",
            1,
            occurrences(chat, "effectiveCompactionSystemPrompt(").size,
        )
        assertEquals(
            "one title selection site in SessionListViewModel (the manual regenerate path)",
            1,
            occurrences(sessionListViewModelSource(), "effectiveTitleSystemPrompt(").size,
        )
    }

    // ── 5. Behavioural: the isolation survives the real dispatch wrappers ─

    @Test
    fun `the title dispatch carries the title edit while the compaction edit is set`() = runTest {
        val both = RoleEdits(titlePrompt = "TITLE-ONLY EDIT", compactionPrompt = "COMPACTION-ONLY EDIT")
        val provider = SystemPromptRecordingProvider()

        callTitleModelWithRetry(
            provider = provider,
            prompt = "User: hi",
            systemPrompt = both.titleSent,
            maxTokens = 100,
            runner = singleAttemptRunner(),
        )

        assertEquals("TITLE-ONLY EDIT", provider.lastSystemPrompt)
        assertNotEquals("COMPACTION-ONLY EDIT", provider.lastSystemPrompt)
    }

    @Test
    fun `the compaction dispatch carries the compaction edit while the title edit is set`() = runTest {
        val both = RoleEdits(titlePrompt = "TITLE-ONLY EDIT", compactionPrompt = "COMPACTION-ONLY EDIT")
        val provider = SystemPromptRecordingProvider()

        callCompactModelWithRetry(
            provider = provider,
            userMessage = "Compact this conversation into a context summary: \u2026",
            systemPrompt = both.compactionSent,
            maxTokens = 1024,
            runner = singleAttemptRunner(),
        )

        assertEquals("COMPACTION-ONLY EDIT", provider.lastSystemPrompt)
        assertNotEquals("TITLE-ONLY EDIT", provider.lastSystemPrompt)
    }

    @Test
    fun `with neither prompt edited the two dispatches still carry different defaults`() = runTest {
        val titleProvider = SystemPromptRecordingProvider()
        val compactionProvider = SystemPromptRecordingProvider()
        val fresh = RoleEdits(titlePrompt = null, compactionPrompt = null)

        callTitleModelWithRetry(
            provider = titleProvider,
            prompt = "User: hi",
            systemPrompt = fresh.titleSent,
            maxTokens = 100,
            runner = singleAttemptRunner(),
        )
        callCompactModelWithRetry(
            provider = compactionProvider,
            userMessage = "Compact this conversation into a context summary: \u2026",
            systemPrompt = fresh.compactionSent,
            maxTokens = 1024,
            runner = singleAttemptRunner(),
        )

        assertEquals(TITLE_GEN_SYSTEM_PROMPT, titleProvider.lastSystemPrompt)
        assertEquals(COMPACTION_DEFAULT_SYSTEM_PROMPT, compactionProvider.lastSystemPrompt)
        assertNotEquals(titleProvider.lastSystemPrompt, compactionProvider.lastSystemPrompt)
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /**
     * The pair of edits the Settings screen lets the user make, plus the pair of
     * system prompts the two paths derive from them — the same two derivations
     * the production call sites make, nothing more.
     */
    private class RoleEdits(var titlePrompt: String?, var compactionPrompt: String?) {
        val titleSent: String get() = effectiveTitleSystemPrompt(titlePrompt)
        val compactionSent: String get() = effectiveCompactionSystemPrompt(compactionPrompt)
    }

    /** Renders a maybe-absent string so a failure names the input that broke it. */
    private fun describe(value: String?): String {
        if (value == null) return "null"
        val escaped = value.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t").replace("\r", "\\r")
        return "\"" + escaped + "\""
    }

    private fun singleAttemptRunner() = agentRetryRunnerFromSettings(
        AgentBehaviorSettings(autoRetryEnabled = false),
        sleep = {},
        onRetry = { _, _, _ -> },
    )

    private fun occurrences(haystack: String, needle: String): List<Int> {
        val found = mutableListOf<Int>()
        var from = haystack.indexOf(needle)
        while (from >= 0) {
            found += from
            from = haystack.indexOf(needle, from + needle.length)
        }
        return found
    }

    /**
     * Asserts that EVERY call to [call] is surrounded by [expectedField] and not
     * by [forbiddenField].
     *
     * Scoping to a window is what makes this stronger than the file-wide
     * `contains(expectedCallWithField)` used elsewhere:
     *
     *  - a `contains` is satisfied by ONE occurrence, so a second call site — the
     *    duplicate that the shared selector exists to prevent — is invisible to
     *    it, including a duplicate that reads the wrong field;
     *  - it also survives reformatting, because it does not require the call and
     *    the field to be adjacent in one exact string.
     *
     * The window is wide enough for a whole call expression and far narrower than
     * the distance between the two call sites (they are thousands of lines apart),
     * so it cannot reach across them.
     */
    private fun assertScoped(
        source: String,
        call: String,
        fileName: String,
        expectedField: String,
        forbiddenField: String,
    ) {
        val sites = occurrences(source, call)
        assertTrue("$fileName must call $call at least once", sites.isNotEmpty())
        for (site in sites) {
            val window = source.substring(
                maxOf(0, site - WINDOW_CHARS),
                minOf(source.length, site + call.length + WINDOW_CHARS),
            )
            val line = source.substring(0, site).count { it == '\n' } + 1
            assertTrue(
                "$fileName:$line — $call must read $expectedField",
                window.contains(expectedField),
            )
            assertFalse(
                "$fileName:$line — $call must not read $forbiddenField; the two editable " +
                    "prompts must stay two independent things",
                window.contains(forbiddenField),
            )
        }
    }

    private fun sourceOf(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"))
        return candidates.firstOrNull { it.isFile && it.length() > 0 }?.readText()
            ?: error("$relative not found from ${File(".").absolutePath}")
    }

    private fun chatViewModelSource(): String =
        sourceOf("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt")

    private fun sessionListViewModelSource(): String =
        sourceOf("src/main/java/com/openminis/app/ui/sessions/SessionListViewModel.kt")

    private companion object {
        /** Wide enough to cover a whole call expression, far narrower than the
         *  distance between the two call sites (they are thousands of lines apart). */
        const val WINDOW_CHARS = 2000
    }
}

/** Records the system prompt the dispatch actually handed the provider. */
private class SystemPromptRecordingProvider : LLMProvider {

    override val name: String = "system-prompt-recording-fake"

    override var model: LLMModel = LLMModel(id = "fake-1", displayName = "Fake", provider = "fake")

    var lastSystemPrompt: String? = null
        private set

    override suspend fun sendMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): LLMResponse {
        lastSystemPrompt = systemPrompt
        return LLMResponse(text = "ok", stopReason = "stop", usage = null)
    }

    override fun streamMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): Flow<LLMStreamChunk> = flowOf()
}
