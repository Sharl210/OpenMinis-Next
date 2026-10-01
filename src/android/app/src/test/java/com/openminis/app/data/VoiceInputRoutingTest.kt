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
import com.openminis.app.data.repository.resolveVoiceInputCandidatesIn
import com.openminis.app.data.repository.resolveVoiceInputChoiceIn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-voice-input-choice] Which recogniser — a provider model or the device engine —
 * serves the next utterance, driven against the production rule itself.
 *
 * ## Why this file exists
 *
 * `ProviderRepository.resolveVoiceInputChoice` had **zero** test references. The
 * repository needs a Context, Room and EncryptedSharedPreferences, so the rule
 * could not be reached from a JVM test at all — the same problem
 * `resolveVoiceInputCandidatesIn`, `resolveVoiceOutputChoiceIn` and
 * `resolveVisionCandidatesIn` had already been extracted to solve. This file
 * follows that pattern; the repository method is now a one-line delegate.
 *
 * It has two production consumers, which is why being wrong here is not academic:
 *  - `ProviderSpeechRecognitionEngine` asks whether a provider entry exists at all;
 *  - `InlineVoiceInputPanel` renders the model label from the choice.
 *
 * ## What is asserted
 *
 * Resolution order, the `hasAudioInput` capability gate, the sentinel handling that
 * derives `systemPreferOffline`, and one cross-resolver agreement check.
 */
class VoiceInputRoutingTest {

    // ─── fixtures ────────────────────────────────────────────────────────────

    private fun instance(id: String, enabled: Boolean = true) = ProviderInstance(
        id = id,
        label = id,
        providerType = ProviderType.openAI,
        credentialType = ProviderCredential.apiKey,
        isEnabled = enabled,
    )

    /** An ASR-capable entry: `audio` IN, `text` out — i.e. `hasAudioInput`. */
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

    /** A TTS-capable entry: `text` in, `audio` OUT — `hasAudioOutput` only. */
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

    private fun configWith(
        group: ModelGroup?,
        entries: List<ModelEntry>,
        instances: List<ProviderInstance>,
    ) = ProviderConfig(
        instances = instances.toMutableList(),
        modelEntries = entries.toMutableList(),
        modelGroups = if (group != null) mutableListOf(group) else mutableListOf(),
        voiceInputGroupId = group?.id,
    )

    private fun groupOf(strategy: RoutingStrategy, vararg members: String) = ModelGroup(
        id = "voice-in",
        name = "Voice In",
        memberEntryIds = members.toMutableList(),
        strategy = strategy,
    )

    private fun sentinel(asr: String) = "${SystemVoiceIds.BUILTIN_PROVIDER_ID}/$asr"

    /** A two-member ASR group in the given strategy; both members usable. */
    private fun twoMemberGroup(strategy: RoutingStrategy) = configWith(
        group = groupOf(strategy, "e1", "e2"),
        entries = listOf(asrEntry("e1", "p1"), asrEntry("e2", "p2")),
        instances = listOf(instance("p1"), instance("p2")),
    )

    // ─── resolution order ───────────────────────────────────────────────────

    @Test
    fun `an explicit provider override wins over the group`() {
        val choice = resolveVoiceInputChoiceIn(twoMemberGroup(RoutingStrategy.fallback), voiceInputOverrideEntryId = "e2")

        assertEquals("the override's entry must beat the group's first member", "e2", choice.entry?.second?.id)
        assertFalse("a provider-backed choice is not the device engine", choice.isSystem)
    }

    @Test
    fun `a stale override falls through to the group`() {
        // The shape a deleted provider leaves behind. Recovering through the group
        // keeps the user on a cloud model; silently becoming the device engine
        // would switch their recogniser without saying so.
        val choice = resolveVoiceInputChoiceIn(
            twoMemberGroup(RoutingStrategy.fallback),
            voiceInputOverrideEntryId = "entry-that-was-deleted",
        )

        assertEquals("a removed override must recover through the group", "e1", choice.entry?.second?.id)
        assertFalse(choice.isSystem)
    }

    @Test
    fun `a system override selects the device recogniser`() {
        val choice = resolveVoiceInputChoiceIn(
            twoMemberGroup(RoutingStrategy.fallback),
            voiceInputOverrideEntryId = SystemVoiceIds.BUILTIN_PROVIDER_ID,
        )

        assertTrue(choice.isSystem)
        assertNull("a System choice carries no provider entry", choice.entry)
    }

    @Test
    fun `a system sentinel member selects the device recogniser`() {
        // A sentinel inside the group must win over the members AFTER it — that is
        // how a group says "the device engine, by preference".
        val config = configWith(
            group = groupOf(RoutingStrategy.fallback, SystemVoiceIds.BUILTIN_PROVIDER_ID, "e1"),
            entries = listOf(asrEntry("e1", "p1")),
            instances = listOf(instance("p1")),
        )
        val choice = resolveVoiceInputChoiceIn(config, voiceInputOverrideEntryId = null)

        assertTrue("the sentinel must select the device recogniser", choice.isSystem)
        assertNull(choice.entry)
    }

    @Test
    fun `nothing configured resolves to the device recogniser`() {
        val config = configWith(
            group = null,
            entries = listOf(asrEntry("e1", "p1")),
            instances = listOf(instance("p1")),
        )
        val choice = resolveVoiceInputChoiceIn(config, voiceInputOverrideEntryId = null)

        assertTrue("with no group the device recogniser is the only answer", choice.isSystem)
        assertNull(choice.entry)
        assertNull(
            "no sentinel was reached, so no online/offline preference is expressed — " +
                "`null` (let the platform decide) is NOT the same as `false` (a sentinel " +
                "explicitly chose the online one)",
            choice.systemPreferOffline,
        )
    }

    @Test
    fun `a disabled instance is not chosen`() {
        val config = configWith(
            group = groupOf(RoutingStrategy.fallback, "e1", "e2"),
            entries = listOf(asrEntry("e1", "p1"), asrEntry("e2", "p2")),
            instances = listOf(instance("p1", enabled = false), instance("p2")),
        )
        val choice = resolveVoiceInputChoiceIn(config, voiceInputOverrideEntryId = null)

        assertEquals("the disabled instance's entry must be skipped", "e2", choice.entry?.second?.id)
    }

    // ─── the capability gate ────────────────────────────────────────────────

    /**
     * The gate is `hasAudioInput`, and its output-side sibling uses
     * `hasAudioOutput` — so a copy-paste across the two resolvers would pick a
     * text-to-SPEECH model to *accept* speech. Transcription would then fail at the
     * provider, or come back as audio where text was expected, while every
     * assertion about order and strategy in this file stayed green.
     */
    @Test
    fun `a model that only produces audio is never chosen to accept it`() {
        val config = configWith(
            group = groupOf(RoutingStrategy.fallback, "tts", "asr"),
            entries = listOf(ttsEntry("tts", "p1"), asrEntry("asr", "p2")),
            instances = listOf(instance("p1"), instance("p2")),
        )
        val choice = resolveVoiceInputChoiceIn(config, voiceInputOverrideEntryId = null)

        assertEquals(
            "the TTS-only entry must be skipped in favour of the ASR entry",
            "asr",
            choice.entry?.second?.id,
        )
    }

    @Test
    fun `a TTS-only override is not honoured as an input choice`() {
        // Same gate on the override path: naming an output-only model must not
        // produce an input choice that cannot hear.
        val config = configWith(
            group = groupOf(RoutingStrategy.fallback, "asr"),
            entries = listOf(ttsEntry("tts", "p1"), asrEntry("asr", "p2")),
            instances = listOf(instance("p1"), instance("p2")),
        )
        val choice = resolveVoiceInputChoiceIn(config, voiceInputOverrideEntryId = "tts")

        assertEquals("the unusable override must fall through to the group", "asr", choice.entry?.second?.id)
    }

    // ─── systemPreferOffline: the input side's own field ────────────────────

    /**
     * The online and offline recognisers are two distinct sentinel MEMBERS, so
     * which one the walk reached is the entire answer. Getting this backwards is
     * silent — transcription still works, it just runs the other engine — and it is
     * the one thing about this resolver that the output side cannot express, since
     * `VoiceOutputChoice` has no comparable field. A transcription of the output
     * rule therefore cannot catch it.
     */
    @Test
    fun `the offline sentinel member selects the offline recogniser`() {
        val config = configWith(
            group = groupOf(RoutingStrategy.fallback, sentinel(SystemVoiceIds.SYSTEM_ASR_OFFLINE), "e1"),
            entries = listOf(asrEntry("e1", "p1")),
            instances = listOf(instance("p1")),
        )
        val choice = resolveVoiceInputChoiceIn(config, voiceInputOverrideEntryId = null)

        assertTrue(choice.isSystem)
        assertEquals(
            "the offline sentinel must be read as the offline preference",
            true,
            choice.systemPreferOffline,
        )
    }

    @Test
    fun `the online sentinel member selects the online recogniser`() {
        val config = configWith(
            group = groupOf(RoutingStrategy.fallback, sentinel(SystemVoiceIds.SYSTEM_ASR_ONLINE), "e1"),
            entries = listOf(asrEntry("e1", "p1")),
            instances = listOf(instance("p1")),
        )
        val choice = resolveVoiceInputChoiceIn(config, voiceInputOverrideEntryId = null)

        assertTrue(choice.isSystem)
        assertEquals(
            "the online sentinel must be read as the online preference, not as offline",
            false,
            choice.systemPreferOffline,
        )
    }

    @Test
    fun `an offline sentinel override selects the offline recogniser`() {
        // The same derivation must hold on the override path, where the id is the
        // whole provider id rather than a group member.
        val choice = resolveVoiceInputChoiceIn(
            twoMemberGroup(RoutingStrategy.fallback),
            voiceInputOverrideEntryId = sentinel(SystemVoiceIds.SYSTEM_ASR_OFFLINE),
        )

        assertTrue(choice.isSystem)
        assertEquals(true, choice.systemPreferOffline)
    }

    @Test
    fun `the tts sentinel is not treated as an offline preference`() {
        // `SYSTEM_TTS` shares the sentinel prefix but names the OUTPUT engine.
        // EndsWith must not match it — otherwise an audio-out sentinel would read
        // as "prefer offline recognition".
        val choice = resolveVoiceInputChoiceIn(
            twoMemberGroup(RoutingStrategy.fallback),
            voiceInputOverrideEntryId = sentinel(SystemVoiceIds.SYSTEM_TTS),
        )

        assertTrue(choice.isSystem)
        assertEquals(
            "only the ASR-offline sentinel may set the offline preference",
            false,
            choice.systemPreferOffline,
        )
    }

    // ─── agreement with the resolver that actually drives fail-over ─────────

    /**
     * `InlineVoiceInputPanel` shows the user the member this resolver picks; the
     * speech engine starts from `resolveVoiceInputCandidatesIn`. The repository's
     * own KDoc justifies sharing the walk on the grounds that they are "the same
     * first-usable-member-in-group-order walk" — this asserts that, rather than
     * restating either rule, so a change to one side alone goes red.
     *
     * Scoped to a zero load-balance seed on purpose: with a non-zero seed the
     * candidates list is deliberately rotated while the displayed choice is not,
     * which is a label question rather than a routing one.
     */
    @Test
    fun `the displayed choice is the member the engine would try first`() {
        val cases = listOf(
            "two usable members, fallback" to twoMemberGroup(RoutingStrategy.fallback),
            "two usable members, loadBalance" to twoMemberGroup(RoutingStrategy.loadBalance),
            "two usable members, none" to twoMemberGroup(RoutingStrategy.none),
            "first member unusable" to configWith(
                group = groupOf(RoutingStrategy.none, "e1", "e2"),
                entries = listOf(asrEntry("e1", "p1"), asrEntry("e2", "p2")),
                instances = listOf(instance("p1", enabled = false), instance("p2")),
            ),
            "no usable member" to configWith(
                group = groupOf(RoutingStrategy.fallback, "e1"),
                entries = listOf(ttsEntry("e1", "p1")),
                instances = listOf(instance("p1")),
            ),
            "no group at all" to configWith(
                group = null,
                entries = listOf(asrEntry("e1", "p1")),
                instances = listOf(instance("p1")),
            ),
        )

        for ((label, config) in cases) {
            val displayed = resolveVoiceInputChoiceIn(config, voiceInputOverrideEntryId = null)
            val first = resolveVoiceInputCandidatesIn(config, voiceInputOverrideEntryId = null, loadBalanceSeed = 0)
                .firstOrNull()

            assertEquals(
                "$label: the panel's choice and the engine's first candidate must be the same entry",
                first?.second?.id,
                displayed.entry?.second?.id,
            )
        }
    }

    // ─── a property a transcription of the rule cannot express ──────────────

    /**
     * The returned pair must be the CONFIG's own objects, not lookalikes rebuilt
     * from ids. Callers cache, bind and compare these by identity; a transcription
     * of the ordering rule works on id strings and would stay green while every
     * downstream `===` check failed.
     */
    @Test
    fun `the returned entry and instance are the config's own objects`() {
        val config = twoMemberGroup(RoutingStrategy.fallback)
        val choice = resolveVoiceInputChoiceIn(config, voiceInputOverrideEntryId = null)

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
