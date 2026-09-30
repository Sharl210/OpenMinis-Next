package com.openminis.app.ui.chat

import com.openminis.app.data.model.ThinkingLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-assistant-attribution] Guards the per-message model attribution.
 *
 * The requirement is that a user who switches models mid-thread can see, for
 * each reply, which model produced it and at which thinking level. The data was
 * always persisted and `assistantHeaderSnapshot` always assembled it, yet the
 * header rendered the fixed Soul name because it took no parameters — nothing
 * failed, because nothing asserted. These tests are the missing assertion.
 */
class AssistantAttributionTest {

    private fun snapshot(
        model: String? = null,
        provider: String? = null,
        level: ThinkingLevel? = null,
    ) = AssistantHeaderSnapshot(
        modelDisplayName = model,
        providerType = provider,
        thinkingLevel = level,
    )

    // --- provider name comes back as something a human reads ---

    @Test
    fun `persisted provider enum name is shown in its display form`() {
        // The DB stores ProviderType.name (see ProviderConfigMapping), so the
        // header must map back through the enum. Rendering the raw enum name
        // would show "openAI"/"xAI" to the user.
        assertEquals("Anthropic", providerDisplayName("anthropic"))
        assertEquals("OpenAI", providerDisplayName("openAI"))
        assertEquals("Google Gemini", providerDisplayName("gemini"))
    }

    @Test
    fun `an unknown provider value is surfaced rather than dropped`() {
        // A provider written by another platform, or a future enum entry, must
        // still be identifiable: showing nothing is the very failure being
        // fixed here.
        assertEquals("someFutureProvider", providerDisplayName("someFutureProvider"))
    }

    @Test
    fun `blank and null providers have no name`() {
        assertNull(providerDisplayName(null))
        assertNull(providerDisplayName(""))
        assertNull(providerDisplayName("   "))
    }

    // --- the composed label matches the chat title's style ---

    @Test
    fun `provider and model are joined with the middle dot`() {
        // "提供商 + 一个中心居中的点号 + 模型名字" — the same shape the chat
        // title subtitle already uses.
        assertEquals(
            "Anthropic · claude-opus-4",
            formatAssistantAttribution(snapshot(model = "claude-opus-4", provider = "anthropic")),
        )
    }

    @Test
    fun `attribution degrades instead of vanishing when only one half is known`() {
        // Older rows may have a model but no provider, or vice versa. Either one
        // alone still answers "which model answered?".
        assertEquals("gpt-5.6", formatAssistantAttribution(snapshot(model = "gpt-5.6")))
        assertEquals("OpenAI", formatAssistantAttribution(snapshot(provider = "openAI")))
        assertFalse(
            "a lone model must not render a dangling separator",
            formatAssistantAttribution(snapshot(model = "gpt-5.6"))!!.contains("·"),
        )
    }

    @Test
    fun `no identity at all yields null so the legacy Soul header still renders`() {
        // This is the fallback contract: null means "keep showing the Soul name",
        // so messages predating attribution are byte-identical to before.
        assertNull(formatAssistantAttribution(null))
        assertNull(formatAssistantAttribution(snapshot()))
        assertNull(formatAssistantAttribution(snapshot(model = "  ", provider = " ")))
    }

    @Test
    fun `whitespace-only pieces do not produce a separator-only label`() {
        assertEquals("gpt-5.6", formatAssistantAttribution(snapshot(model = "  gpt-5.6  ")))
        assertEquals("gpt-5.6", formatAssistantAttribution(snapshot(model = "gpt-5.6", provider = "   ")))
    }

    // --- the snapshot's own gate is the thing the header branches on ---

    @Test
    fun `a thinking level alone is not a model identity`() {
        // Documented contract on AssistantHeaderSnapshot: thinking-only metadata
        // must NOT replace the legacy header, because it does not answer "which
        // model produced this".
        val levelOnly = snapshot(level = ThinkingLevel.HIGH)
        assertFalse(levelOnly.hasModelIdentity)
        assertNull(formatAssistantAttribution(levelOnly))
    }

    @Test
    fun `a model or provider makes the snapshot a real identity`() {
        assertTrue(snapshot(model = "gpt-5.6").hasModelIdentity)
        assertTrue(snapshot(provider = "openAI").hasModelIdentity)
    }

    @Test
    fun `the thinking level carried by a snapshot is the historical one`() {
        // The header renders the level recorded WITH the message, not the
        // session's current level — otherwise a past reply would appear to have
        // been produced at a level the user picked afterwards.
        val recorded = snapshot(model = "gpt-5.6", level = ThinkingLevel.MAX)
        assertEquals(ThinkingLevel.MAX, recorded.thinkingLevel)
        assertEquals(ThinkingLevel.OFF, snapshot(model = "x", level = ThinkingLevel.OFF).thinkingLevel)
    }

    // --- the WIRING, which the pure-function tests above cannot cover ---
    //
    // The original defect was not a formatting bug: the header never received
    // the value at all. `AssistantHeader()` took no parameters and the flat
    // item carried only a message id, so formatting could have been perfect and
    // the user would still have seen "Minis". These tests go through the real
    // row builder, which is where the attribution actually travels — and they
    // are the assertions that fail before the parameter was threaded through.

    private fun assistantMessage(
        id: String,
        model: String? = null,
        provider: String? = null,
        level: ThinkingLevel? = null,
    ) = ChatMessage(
        id = id,
        role = "assistant",
        content = "answer",
        modelDisplayName = model,
        providerType = provider,
        thinkingLevel = level,
    )

    private fun headerFor(messages: List<ChatMessage>): FlatChatItem.AssistantHeader? =
        buildFlatChatItems(messages)
            .filterIsInstance<FlatChatItem.AssistantHeader>()
            .firstOrNull()

    @Test
    fun `the header row carries this turn's model attribution end to end`() {
        val header = headerFor(
            listOf(
                ChatMessage(id = "u1", role = "user", content = "hi"),
                assistantMessage("a1", model = "gpt-5.6", provider = "openAI", level = ThinkingLevel.HIGH),
            ),
        )
        assertTrue("no header row was produced", header != null)
        // This is the exact chain that was broken: message → snapshot → header.
        assertEquals(
            "OpenAI · gpt-5.6",
            formatAssistantAttribution(header!!.snapshot),
        )
        assertEquals(ThinkingLevel.HIGH, header.snapshot?.thinkingLevel)
    }

    @Test
    fun `two turns with different models keep their own attribution`() {
        // The whole point of the requirement: the user switches models mid-thread
        // ("我期间可能进行模型切换"). A header that showed the session's CURRENT
        // model would make both replies claim the same one.
        val rows = buildFlatChatItems(
            listOf(
                ChatMessage(id = "u1", role = "user", content = "q1"),
                assistantMessage("a1", model = "claude-opus-4", provider = "anthropic"),
                ChatMessage(id = "u2", role = "user", content = "q2"),
                assistantMessage("a2", model = "gpt-5.6", provider = "openAI"),
            ),
        ).filterIsInstance<FlatChatItem.AssistantHeader>()

        assertEquals("both turns must produce a header", 2, rows.size)
        assertEquals(
            "Anthropic · claude-opus-4",
            formatAssistantAttribution(rows[0].snapshot),
        )
        assertEquals("OpenAI · gpt-5.6", formatAssistantAttribution(rows[1].snapshot))
    }

    @Test
    fun `a legacy message with no attribution still produces the fallback header`() {
        // Rows written before attribution existed must still get a header — it
        // just falls back to the Soul name. The row must exist, so that a null
        // snapshot cannot silently mean "no header at all".
        val header = headerFor(
            listOf(
                ChatMessage(id = "u1", role = "user", content = "hi"),
                assistantMessage("a1"),
            ),
        )
        assertTrue("legacy turns must keep their header", header != null)
        assertNull("and must have no attribution to render", header!!.snapshot)
    }

    @Test
    fun `a thinking level alone does not become an attribution in the real row`() {
        // Mirrors the snapshot-level contract at the wiring level: thinking-only
        // metadata must not replace the Soul name, because it does not say which
        // model answered.
        val header = headerFor(
            listOf(
                ChatMessage(id = "u1", role = "user", content = "hi"),
                assistantMessage("a1", level = ThinkingLevel.MAX),
            ),
        )
        assertTrue(header != null)
        assertNull(formatAssistantAttribution(header!!.snapshot))
        assertEquals(ThinkingLevel.MAX, header.snapshot?.thinkingLevel)
    }
}
