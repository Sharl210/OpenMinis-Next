package com.openminis.app.data

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.model.RoutingStrategy
import com.openminis.app.data.model.SystemVoiceIds
import com.openminis.app.data.repository.resolveVoiceOutputChoiceIn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-voice-output-fallback] Which engine reads a reply aloud, and whether a failed
 * provider is allowed to degrade to the device speech engine — driven against the
 * production rule itself.
 *
 * ## Why this file exists
 *
 * `ProviderRepository.resolveVoiceOutputChoice` had **zero** test references while
 * being the only place `RoutingStrategy.none` is interpreted for the output side.
 * The repository needs a Context, Room and EncryptedSharedPreferences, so the
 * decision could not be reached from a JVM test at all — the same problem that
 * `resolveVoiceInputCandidatesIn` and `resolveVisionCandidatesIn` had already been
 * extracted to solve. This file follows that pattern: the rule is
 * `resolveVoiceOutputChoiceIn`, and the repository method is a one-line delegate.
 *
 * ## The rule under test
 *
 * `allowSystemFallback` has no input-side counterpart and is the reason this file
 * is worth having:
 *
 *  - an explicit override pins ONE provider ⇒ `false` (asking for a specific voice
 *    and silently getting a different one is the failure this forbids);
 *  - otherwise it tracks the group's strategy ⇒ a `none` group is `false`, since
 *    `none` is the user saying "never move off the member I chose";
 *  - `fallback` / `loadBalance` groups are `true` — those strategies already
 *    express willingness to move off the selected member.
 *
 * Getting this backwards is silent: read-aloud still works, it just speaks in a
 * voice the user did not choose, or stops speaking instead of degrading.
 */
class VoiceOutputRoutingTest {

    // ─── fixtures ────────────────────────────────────────────────────────────

    private fun instance(id: String, enabled: Boolean = true) = ProviderInstance(
        id = id,
        label = id,
        providerType = ProviderType.openAI,
        credentialType = ProviderCredential.apiKey,
        isEnabled = enabled,
    )

    /** A TTS-capable entry: `text` in, `audio` OUT — i.e. `hasAudioOutput`. */
    private fun ttsEntry(uuid: String, providerInstanceId: String) = ModelEntry(
        providerInstanceId = providerInstanceId,
        baseModel = LLMModel(
            id = "model-$uuid",
            displayName = "Model $uuid",
            provider = "openai",
            inputModalities = listOf("text"),
            outputModalities = listOf("text", "audio"),
        ),
        uuid = uuid,
    )

    /** An ASR-capable entry: `audio` IN, `text` out — `hasAudioInput` only. */
    private fun asrEntry(uuid: String, providerInstanceId: String) = ModelEntry(
        providerInstanceId = providerInstanceId,
        baseModel = LLMModel(
            id = "model-$uuid",
            displayName = "Model $uuid",
            provider = "openai",
            inputModalities = listOf("text", "audio"),
            outputModalities = listOf("text"),
        ),
        uuid = uuid,
    )

    private fun configWith(
        group: ModelGroup?,
        entries: List<ModelEntry>,
        instances: List<ProviderInstance>,
    ) = ProviderConfig(
        instances = instances.toMutableList(),
        modelEntries = entries.toMutableList(),
        modelGroups = if (group != null) mutableListOf(group) else mutableListOf(),
        voiceOutputGroupId = group?.id,
    )

    private fun groupOf(strategy: RoutingStrategy, vararg members: String) = ModelGroup(
        id = "voice-out",
        name = "Voice Out",
        memberEntryIds = members.toMutableList(),
        strategy = strategy,
    )

    /** A [p1/e1, p2/e2] output group in the given strategy; both members usable. */
    private fun twoMemberGroup(strategy: RoutingStrategy) = configWith(
        group = groupOf(strategy, "e1", "e2"),
        entries = listOf(ttsEntry("e1", "p1"), ttsEntry("e2", "p2")),
        instances = listOf(instance("p1"), instance("p2")),
    )

    // ─── allowSystemFallback: the rule with no input-side counterpart ────────

    @Test
    fun `an explicit provider override forbids degrading to the device engine`() {
        // The user named one voice. If it fails, speaking in a different one is
        // worse than not speaking: the strategy under test is "do not move".
        val config = twoMemberGroup(RoutingStrategy.fallback)
        val choice = resolveVoiceOutputChoiceIn(config, voiceOutputOverrideEntryId = "e2")

        assertEquals("the override's entry must win over the group's first member", "e2", choice.entry?.second?.id)
        assertFalse(
            "an explicit override must not be allowed to degrade to the device engine",
            choice.allowSystemFallback,
        )
    }

    @Test
    fun `a none group refuses to degrade to the device engine`() {
        // `none` is the user pinning the group. Degrading to the device engine
        // would contradict the one strategy whose whole meaning is "never move".
        val choice = resolveVoiceOutputChoiceIn(twoMemberGroup(RoutingStrategy.none), voiceOutputOverrideEntryId = null)

        assertEquals("e1", choice.entry?.second?.id)
        assertFalse(
            "a `none` group must fail loudly instead of reading the reply in another voice",
            choice.allowSystemFallback,
        )
    }

    @Test
    fun `a fallback group may degrade to the device engine`() {
        val choice = resolveVoiceOutputChoiceIn(twoMemberGroup(RoutingStrategy.fallback), voiceOutputOverrideEntryId = null)

        assertEquals("e1", choice.entry?.second?.id)
        assertTrue(
            "`fallback` already expresses a willingness to move off the selected member",
            choice.allowSystemFallback,
        )
    }

    @Test
    fun `a loadBalance group may degrade to the device engine`() {
        val choice = resolveVoiceOutputChoiceIn(twoMemberGroup(RoutingStrategy.loadBalance), voiceOutputOverrideEntryId = null)

        assertEquals("e1", choice.entry?.second?.id)
        assertTrue("`loadBalance` may also degrade", choice.allowSystemFallback)
    }

    // ─── resolution order ───────────────────────────────────────────────────

    @Test
    fun `no configured group resolves to the device engine`() {
        val config = configWith(
            group = null,
            entries = listOf(ttsEntry("e1", "p1")),
            instances = listOf(instance("p1")),
        )
        val choice = resolveVoiceOutputChoiceIn(config, voiceOutputOverrideEntryId = null)

        assertTrue("with nothing configured the device engine is the only answer", choice.isSystemEngine)
        assertNull(choice.entry)
        assertTrue("isSystem is derived from the entry, not stored separately", choice.isSystem)
    }

    @Test
    fun `a system sentinel member selects the device engine`() {
        // A System sentinel inside the group must win over the members after it —
        // that is how the group expresses "the device engine, by preference".
        val config = configWith(
            group = groupOf(RoutingStrategy.fallback, SystemVoiceIds.BUILTIN_PROVIDER_ID, "e1"),
            entries = listOf(ttsEntry("e1", "p1")),
            instances = listOf(instance("p1")),
        )
        val choice = resolveVoiceOutputChoiceIn(config, voiceOutputOverrideEntryId = null)

        assertTrue("the sentinel must select the device engine", choice.isSystemEngine)
        assertNull("a System choice carries no provider entry", choice.entry)
    }

    @Test
    fun `a system override selects the device engine`() {
        val choice = resolveVoiceOutputChoiceIn(
            twoMemberGroup(RoutingStrategy.fallback),
            voiceOutputOverrideEntryId = SystemVoiceIds.BUILTIN_PROVIDER_ID,
        )
        assertTrue(choice.isSystemEngine)
        assertNull(choice.entry)
    }

    /**
     * A stale override is the shape a deleted provider leaves behind. Falling
     * through to the group is the recovery; returning a System choice instead
     * would silently switch the user to the device voice.
     */
    @Test
    fun `a stale override falls through to the group`() {
        val choice = resolveVoiceOutputChoiceIn(
            twoMemberGroup(RoutingStrategy.fallback),
            voiceOutputOverrideEntryId = "entry-that-was-deleted",
        )

        assertEquals(
            "a removed override must recover through the group, not silently become the device engine",
            "e1",
            choice.entry?.second?.id,
        )
        assertFalse(choice.isSystemEngine)
        assertTrue(choice.allowSystemFallback)
    }

    // ─── the capability gate ────────────────────────────────────────────────

    /**
     * The gate is `hasAudioOutput`, and the input side of this same file uses
     * `hasAudioInput` — so a copy-paste across the two resolvers would pick a
     * speech-to-TEXT model to *produce* speech. Read-aloud would then fail at the
     * provider, or worse, return text where audio was expected, and every
     * assertion about order/strategy above would stay green.
     */
    @Test
    fun `a model that only consumes audio is never chosen to produce it`() {
        val config = configWith(
            group = groupOf(RoutingStrategy.fallback, "asr", "tts"),
            entries = listOf(asrEntry("asr", "p1"), ttsEntry("tts", "p2")),
            instances = listOf(instance("p1"), instance("p2")),
        )
        val choice = resolveVoiceOutputChoiceIn(config, voiceOutputOverrideEntryId = null)

        assertEquals(
            "the ASR-only entry must be skipped in favour of the TTS entry",
            "tts",
            choice.entry?.second?.id,
        )
    }

    @Test
    fun `an ASR-only override is not honoured as an output choice`() {
        // Same gate on the override path: naming an input-only model must not
        // produce an output choice that cannot speak.
        val config = configWith(
            group = groupOf(RoutingStrategy.fallback, "tts"),
            entries = listOf(asrEntry("asr", "p1"), ttsEntry("tts", "p2")),
            instances = listOf(instance("p1"), instance("p2")),
        )
        val choice = resolveVoiceOutputChoiceIn(config, voiceOutputOverrideEntryId = "asr")

        assertEquals("the unusable override must fall through to the group", "tts", choice.entry?.second?.id)
    }

    @Test
    fun `a disabled instance is not chosen`() {
        val config = configWith(
            group = groupOf(RoutingStrategy.fallback, "e1", "e2"),
            entries = listOf(ttsEntry("e1", "p1"), ttsEntry("e2", "p2")),
            instances = listOf(instance("p1", enabled = false), instance("p2")),
        )
        val choice = resolveVoiceOutputChoiceIn(config, voiceOutputOverrideEntryId = null)

        assertEquals("the disabled instance's entry must be skipped", "e2", choice.entry?.second?.id)
    }

    // ─── a property a transcription of the rule cannot express ──────────────

    /**
     * The returned pair must be the CONFIG's own objects, not lookalikes rebuilt
     * from ids. A caller caches, binds and compares these by identity; a
     * transcription of the ordering rule works on id strings and would stay green
     * while every downstream `===` check failed.
     */
    @Test
    fun `the returned entry and instance are the config's own objects`() {
        val config = twoMemberGroup(RoutingStrategy.fallback)
        val choice = resolveVoiceOutputChoiceIn(config, voiceOutputOverrideEntryId = null)

        assertSame(
            "the chosen entry must be the config's own ModelEntry",
            config.modelEntries.first { it.id == "e1" },
            choice.entry?.second,
        )
        assertSame(
            "…and its instance must be the config's own ProviderInstance",
            config.instances.first { it.id == "p1" },
            choice.entry?.first,
        )
    }
}
