package com.openminis.app.data

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.model.RoutingStrategy
import com.openminis.app.data.repository.resolveVoiceInputCandidatesIn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [T-voice-asr-group-failover] What a group's routing strategy does to the ORDER
 * of the members a request walks — pinned on the production rule itself.
 *
 * ## Why this file calls production instead of restating it
 *
 * The second test here used to end in a `when (strategy)` written out inside the
 * test — `none -> selected`, `fallback -> members.first()`, `loadBalance ->
 * members.last()` — with `strategy` hardcoded to `RoutingStrategy.none` and
 * `selected` a local the test had computed two lines earlier. The closing
 * `assertEquals(selected, resolved)` therefore compared a value with itself, and
 * the when's other two arms were unreachable. The file named no production
 * symbol at all: `resolveVoiceInputCandidates` could have rotated under `none`,
 * dropped a member, or moved PAST one, and this class stayed green. That is the
 * failure mode this rewrite removes.
 *
 * The rule is now `resolveVoiceInputCandidatesIn` — the pure core that
 * `ProviderRepository.resolveVoiceInputCandidates` delegates to. The repository
 * object itself needs a Context, Room and EncryptedSharedPreferences, which is
 * why the core is a top-level function (the same shape as
 * `resolveVisionCandidatesIn`, exercised by `VisionGroupGateTest`).
 */
class RoutingStrategyTest {

    @Test
    fun `none is a persisted routing strategy`() {
        val group = ModelGroup(name = "Pinned", strategy = RoutingStrategy.none)
        assertEquals(RoutingStrategy.none, group.strategy)
        assertEquals("none", group.strategy.name)
        assertEquals(RoutingStrategy.none, RoutingStrategy.valueOf("none"))
    }

    // ─── fixtures: a bound Voice Input group [e1, e2], both members usable ────

    private fun instance(id: String) = ProviderInstance(
        id = id,
        label = id,
        providerType = ProviderType.openAI,
        credentialType = ProviderCredential.apiKey,
        isEnabled = true,
    )

    /** An ASR-capable entry: `text` + `audio` in, i.e. `hasAudioInput`. */
    private fun asrEntry(uuid: String, providerInstanceId: String) = ModelEntry(
        providerInstanceId = providerInstanceId,
        baseModel = LLMModel(
            id = "model-$uuid",
            displayName = "Model $uuid",
            provider = "openai",
            inputModalities = listOf("text", "audio"),
        ),
        uuid = uuid,
    )

    private fun voiceConfig(strategy: RoutingStrategy): ProviderConfig = ProviderConfig(
        instances = mutableListOf(instance("p1"), instance("p2")),
        modelEntries = mutableListOf(asrEntry("e1", "p1"), asrEntry("e2", "p2")),
        modelGroups = mutableListOf(
            ModelGroup(
                id = "voice",
                name = "Voice",
                memberEntryIds = mutableListOf("e1", "e2"),
                strategy = strategy,
            ),
        ),
        voiceInputGroupId = "voice",
    )

    private fun ids(config: ProviderConfig, seed: Int): List<String> =
        resolveVoiceInputCandidatesIn(config, voiceInputOverrideEntryId = null, loadBalanceSeed = seed)
            .map { it.second.id }

    // ─── the routing rule, per strategy ──────────────────────────────────────

    /**
     * The original assertion, kept but no longer tautological: under `none` the
     * ladder is ONE member — the one the user selected — and no seed rotates it.
     */
    @Test
    fun `none keeps the selected member instead of rotating`() {
        val config = voiceConfig(RoutingStrategy.none)
        for (seed in listOf(0, 1, 7, -3)) {
            assertEquals("seed=$seed must not rotate a `none` group", listOf("e1"), ids(config, seed))
        }
        // An explicit selection is the only member; the group's other member is
        // not walked at all (that is what "no failover" means).
        val selected = resolveVoiceInputCandidatesIn(config, voiceInputOverrideEntryId = "e2", loadBalanceSeed = 1)
        assertEquals("an explicit selection is the only candidate", listOf("e2"), selected.map { it.second.id })
    }

    @Test
    fun `fallback keeps the group's order`() {
        val config = voiceConfig(RoutingStrategy.fallback)
        assertEquals(listOf("e1", "e2"), ids(config, 0))
        assertEquals(listOf("e1", "e2"), ids(config, 1))
        assertEquals(listOf("e1", "e2"), ids(config, 5))
    }

    @Test
    fun `loadBalance rotates the start position by the seed`() {
        val config = voiceConfig(RoutingStrategy.loadBalance)
        assertEquals(listOf("e1", "e2"), ids(config, 0))
        assertEquals(listOf("e2", "e1"), ids(config, 1))
        assertEquals(listOf("e2", "e1"), ids(config, 3)) // 3 % 2
        assertEquals(listOf("e1", "e2"), ids(config, 4))
        assertEquals(listOf("e1", "e2"), ids(config, -2)) // abs(-2) % 2
        assertEquals(listOf("e2", "e1"), ids(config, -1))
    }

    // ─── properties a transcription of the rule cannot express ───────────────

    /**
     * The candidates must be the CONFIGURED objects, not pairs rebuilt from their
     * ids. A transcription of the ordering rule works on id strings and cannot
     * express this: the assertions above would stay green while every caller —
     * which caches, binds and de-duplicates members by entry identity — got
     * lookalikes that compare equal by id and fail every `===`/`equals`-by-object
     * check downstream.
     */
    @Test
    fun `candidates are the configured entries and instances themselves`() {
        val config = voiceConfig(RoutingStrategy.loadBalance)
        val out = resolveVoiceInputCandidatesIn(config, voiceInputOverrideEntryId = null, loadBalanceSeed = 1)
        assertEquals(listOf("e2", "e1"), out.map { it.second.id })
        assertSame(
            "the rotated-in entry must be the config's own ModelEntry",
            config.modelEntries.first { it.id == "e2" },
            out[0].second,
        )
        assertSame(
            "…and its instance must be the config's own ProviderInstance",
            config.instances.first { it.id == "p2" },
            out[0].first,
        )
        assertSame(config.modelEntries.first { it.id == "e1" }, out[1].second)
        assertSame(config.instances.first { it.id == "p1" }, out[1].first)
    }

    /**
     * Rotation is computed on a LOCAL copy, so the group's own `memberEntryIds` —
     * the list the picker reads and the config writer persists — comes out of a
     * load-balanced resolve in exactly the order it went in. An implementation
     * that rotated that list in place (or rebuilt the group) would reorder the
     * user's group behind their back while every order-related assertion above
     * stayed green.
     */
    @Test
    fun `resolving does not reorder the group's own member list`() {
        val config = voiceConfig(RoutingStrategy.loadBalance)
        val members = config.modelGroups.single().memberEntryIds

        resolveVoiceInputCandidatesIn(config, voiceInputOverrideEntryId = null, loadBalanceSeed = 1)

        assertSame(
            "the group must still hold the same list object",
            members,
            config.modelGroups.single().memberEntryIds,
        )
        assertEquals("the group's own order must be untouched", listOf("e1", "e2"), members)
    }
}
