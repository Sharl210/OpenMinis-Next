package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMResponse
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelRoleSelectionResolver
import com.openminis.app.data.model.ProviderConfig
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R55 — `plans/ULW-2026-09-25-01/request.md:217` and `:246`:
 *
 *   「标题生成模型和对话压缩模型…跟主代理和子代理模型，就是一共有4类模型要设置」
 *   「标题生成和压缩模型…不仅仅只是支持模型的选择，还要去支持对应的提示词的编辑」
 *
 * Two claims are easy to fake and are therefore the ones tested here:
 *
 *  1. A setting can be *stored* and never read. `primaryModelEntryId` was exactly
 *     that — the Settings picker wrote it and no chat-path code read it, so a new
 *     conversation ran on the last-used model instead. [newChatDefaultEntry...]
 *     below pins the resolution order that makes the setting real.
 *  2. An edited prompt can be *saved* and never *sent*. The prompt assertions go
 *     through the production selector AND the production request function, with a
 *     provider that records what it was actually asked, so a saved-but-ignored
 *     edit fails them.
 *
 * The Source-level assertions at the end are the wiring half: a selector nothing
 * calls would satisfy the pure-function tests above and still ship a dead
 * setting.
 */
class ModelRoleSettingsEffectTest {

    // ── 1. The four role settings actually resolve ───────────────────────

    @Test
    fun `the configured primary agent model wins over last used and newest`() {
        val config = ProviderConfig(
            modelEntries = mutableListOf(entry("a"), entry("b"), entry("primary")),
            primaryModelEntryId = "primary",
        )

        val resolved = ModelRoleSelectionResolver.newChatDefaultEntry(
            config = config,
            visibleEntries = config.modelEntries.toList(),
            lastUsedEntryId = "a",
            newestTextEntry = config.modelEntries.first { it.id == "b" },
        )

        assertEquals(
            "the Primary Agent Model setting must decide, or it is decoration",
            "primary",
            resolved?.id,
        )
    }

    @Test
    fun `without a primary selection the previous tiers still apply`() {
        val entries = listOf(entry("a"), entry("b"))
        val config = ProviderConfig(modelEntries = entries.toMutableList())

        assertEquals(
            "tier 2: last used",
            "a",
            ModelRoleSelectionResolver.newChatDefaultEntry(config, entries, "a", entries[1])?.id,
        )
        assertEquals(
            "tier 3: the newest text model",
            "b",
            ModelRoleSelectionResolver.newChatDefaultEntry(config, entries, "gone", entries[1])?.id,
        )
        assertNull(ModelRoleSelectionResolver.newChatDefaultEntry(config, entries, null, null))
    }

    @Test
    fun `a primary selection whose entry is no longer usable falls through`() {
        // The user may have deleted the entry, hidden it, or disabled its
        // provider. Resolving to something uncallable would be worse than
        // falling back, so the setting only wins while it is still resolvable.
        val config = ProviderConfig(
            modelEntries = mutableListOf(entry("a")),
            primaryModelEntryId = "deleted-entry",
        )

        val resolved = ModelRoleSelectionResolver.newChatDefaultEntry(
            config = config,
            visibleEntries = config.modelEntries.toList(),
            lastUsedEntryId = "a",
            newestTextEntry = null,
        )

        assertEquals("a", resolved?.id)
    }

    @Test
    fun `the title and compaction selections are read from their own keys`() {
        // Three independent keys, three independent answers: sharing one field
        // between them is how "4 classes of model" silently becomes one.
        val config = ProviderConfig(
            modelEntries = mutableListOf(entry("primary"), entry("title"), entry("compact")),
            primaryModelEntryId = "primary",
            titleModelEntryId = "title",
            compactionModelEntryId = "compact",
        )

        assertEquals("primary", ModelRoleSelectionResolver.explicitPrimaryEntry(config)?.id)
        assertEquals("title", ModelRoleSelectionResolver.explicitTitleEntry(config)?.id)
        assertEquals("compact", ModelRoleSelectionResolver.explicitCompactionEntry(config)?.id)
    }

    // ── 2. An edited prompt reaches the real request ─────────────────────

    @Test
    fun `an edited title prompt is what the title request carries`() = runTest {
        val provider = RecordingProvider()

        callTitleModelWithRetry(
            provider = provider,
            prompt = "User: hi",
            systemPrompt = effectiveTitleSystemPrompt("MY OWN TITLE RULES"),
            maxTokens = 100,
            runner = runner(),
        )

        assertEquals(
            "the edited prompt must reach the provider, not just the prefs",
            "MY OWN TITLE RULES",
            provider.lastSystemPrompt,
        )
    }

    @Test
    fun `an unedited title prompt sends the built-in default`() = runTest {
        val provider = RecordingProvider()

        callTitleModelWithRetry(
            provider = provider,
            prompt = "User: hi",
            systemPrompt = effectiveTitleSystemPrompt(null),
            maxTokens = 100,
            runner = runner(),
        )

        assertEquals(TITLE_GEN_SYSTEM_PROMPT, provider.lastSystemPrompt)
        assertTrue(
            "the default must keep the JSON shape the title parser depends on",
            provider.lastSystemPrompt!!.contains("\"title\""),
        )
    }

    @Test
    fun `a blank title prompt means unset, not an empty system prompt`() {
        assertEquals(TITLE_GEN_SYSTEM_PROMPT, effectiveTitleSystemPrompt("   "))
        assertEquals(TITLE_GEN_SYSTEM_PROMPT, effectiveTitleSystemPrompt(""))
        assertEquals(TITLE_GEN_SYSTEM_PROMPT, effectiveTitleSystemPrompt(null))
        assertEquals("kept", effectiveTitleSystemPrompt("kept"))
    }

    @Test
    fun `an edited compaction prompt is what the compaction request carries`() = runTest {
        val provider = RecordingProvider()

        callCompactModelWithRetry(
            provider = provider,
            userMessage = "Compact this conversation into a context summary: …",
            systemPrompt = effectiveCompactionSystemPrompt("MY OWN COMPACTION RULES"),
            maxTokens = 1024,
            runner = runner(),
        )

        assertEquals("MY OWN COMPACTION RULES", provider.lastSystemPrompt)
    }

    @Test
    fun `a blank compaction prompt means unset, not an empty system prompt`() {
        assertEquals(COMPACTION_DEFAULT_SYSTEM_PROMPT, effectiveCompactionSystemPrompt(" "))
        assertEquals(COMPACTION_DEFAULT_SYSTEM_PROMPT, effectiveCompactionSystemPrompt(null))
        assertEquals("kept", effectiveCompactionSystemPrompt("kept"))
    }

    @Test
    fun `the compaction default the editor shows is the one the request sends`() {
        // The editor renders `config.compactionPrompt ?: COMPACTION_DEFAULT_SYSTEM_PROMPT`
        // and "reset" writes null. Those two are only honest if the constant IS
        // the runtime default — this used to be false, with a short placeholder
        // in the UI and a long structured prompt in the ViewModel.
        assertEquals(
            "what the editor displays must be what an unset prompt sends",
            effectiveCompactionSystemPrompt(null),
            COMPACTION_DEFAULT_SYSTEM_PROMPT,
        )
        assertTrue(
            "the structured instructions are the default, not a one-liner",
            COMPACTION_DEFAULT_SYSTEM_PROMPT.contains("MUST PRESERVE"),
        )
        assertFalse(
            "a second copy of the default prompt in the ViewModel is how they drifted apart",
            chatViewModelSource().contains("Your summary will REPLACE the original messages"),
        )
    }

    // ── 3. Wiring: the selectors are actually called ─────────────────────

    @Test
    fun `the chat path selects the prompts through the shared selector`() {
        val source = chatViewModelSource()

        assertTrue(
            "the auto title path must use the shared selector",
            source.contains("effectiveTitleSystemPrompt(providerRepository.titlePrompt)"),
        )
        assertTrue(
            "the compaction path must use the shared selector",
            source.contains("effectiveCompactionSystemPrompt(providerRepository.compactionPrompt)"),
        )
    }

    @Test
    fun `the manual regenerate path shares the same title prompt selector`() {
        val source = sessionListViewModelSource()

        assertTrue(
            "SessionListViewModel.regenerateTitle must not keep its own prompt logic",
            source.contains("effectiveTitleSystemPrompt(providerRepository.titlePrompt)"),
        )
    }

    @Test
    fun `the new-chat default model goes through the resolver that reads the setting`() {
        val source = chatViewModelSource()

        assertTrue(
            "a new chat must resolve its model through the one function that reads primaryModelEntryId",
            source.contains("providerRepository.newChatDefaultEntry()"),
        )
        assertFalse(
            "the old two-tier chain bypassed the Primary Agent Model setting entirely",
            source.contains("val entry = providerRepository.lastUsedVisibleEntry()"),
        )
    }

    @Test
    fun `a delegation records the shared context before the child runs`() {
        val source = chatViewModelSource()

        assertTrue(
            "the dispatcher's --share must be handed to the run",
            source.contains("onChildSessionReady = { childSessionId ->"),
        )
        assertTrue(
            "and it must be resolved into a reference, not into text",
            source.contains("RuntimeContextAttachments.resolve("),
        )
        assertTrue(
            "the record must go through the existing communication directory",
            source.contains("recordDelivered("),
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun entry(id: String) = ModelEntry(
        providerInstanceId = "instance-1",
        baseModel = LLMModel(id = "model-$id", displayName = "Model $id", provider = "fake"),
        uuid = id,
    )

    /** Single attempt: this file tests prompts, not retry budgets (see R26's own file). */
    private fun runner() = agentRetryRunnerFromSettings(
        AgentBehaviorSettings(autoRetryEnabled = false),
        sleep = {},
        onRetry = { _, _, _ -> },
    )

    private fun sourceOf(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"))
        return candidates.firstOrNull { it.isFile && it.length() > 0 }?.readText()
            ?: error("$relative not found from ${File(".").absolutePath}")
    }

    private fun chatViewModelSource(): String =
        sourceOf("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt")

    private fun sessionListViewModelSource(): String =
        sourceOf("src/main/java/com/openminis/app/ui/sessions/SessionListViewModel.kt")
}

/**
 * Records what the provider was actually asked, so a prompt that only reached
 * the preferences cannot pass as a prompt that changed the request.
 */
private class RecordingProvider : LLMProvider {

    override val name: String = "recording-fake"

    override var model: LLMModel = LLMModel(id = "fake-1", displayName = "Fake", provider = "fake")

    var lastSystemPrompt: String? = null
        private set
    var lastUserMessage: String? = null
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
        lastUserMessage = messages.lastOrNull()?.content
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
