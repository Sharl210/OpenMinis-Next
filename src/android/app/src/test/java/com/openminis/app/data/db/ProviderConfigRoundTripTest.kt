package com.openminis.app.data.db

import com.openminis.app.data.model.FallbackStrategy
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.model.RoutingStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-persisted-enum-decode] The Room snapshot round trip, and what happens
 * to an enum name this build does not recognise.
 *
 * ## Why this file exists
 *
 * `ProviderConfigMapping.toSnapshot` / `toProviderConfig` had **zero** test
 * references. Two neighbouring files documented that as deliberate —
 * `ModelGroupReorderTest` and `ProviderReorderTest` both say "Persistence needs no
 * separate assertion: ProviderConfigMapping writes...". That reasoning covers *what
 * the mapping writes*; it does not cover *what it reads back*, which is where the
 * hazard is.
 *
 * ## The hazard
 *
 * The reader fetches four enums straight off persisted rows. Three of them used
 * `valueOf`, which **throws** on a name this build does not know — while two other
 * enums in the very same function (`ThinkingLevel`, `ImageEndpointMode`) were
 * deliberately made non-throwing, with a KDoc stating the rule outright: "Use this
 * for every 'read from persisted data' path." `ProviderType.decoded()` already
 * existed for exactly this and was bypassed.
 *
 * A persisted name can come from a NEWER build — this repo has already extended
 * `RoutingStrategy` once, when `none` was added — or from an iOS package, which is
 * how the sibling `RestoreProviderTypeToleranceTest` came to exist. If the reader
 * throws, control passes to the whole-document mirror decode, which throws
 * identically on the same bytes (asserted below), so a value this build does not
 * know could take the entire config with it.
 *
 * ## What is asserted
 *
 *  - known values round-trip unchanged, through the real write path;
 *  - an unknown value does **not** throw, and lands where the design says;
 *  - the coercion targets agree with the JSON mirror wherever the mirror can coerce
 *    at all, so the two readers cannot disagree about the same bytes;
 *  - the write side persists enum **names**, so the reader's `decoded()` calls can
 *    never meet an ordinal.
 */
class ProviderConfigRoundTripTest {

    /**
     * Mirrors the production mirror's [Json] for config payloads — including
     * `encodeDefaults`, which is load-bearing here: without it a field whose value
     * equals its default is omitted from the payload, and a rewrite that assumes the
     * key is present silently matches nothing.
     */
    private val mirrorJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    private val blobJson = Json

    // --- fixtures -----------------------------------------------------------

    private fun instance(
        id: String,
        type: ProviderType = ProviderType.openAI,
        cred: ProviderCredential = ProviderCredential.apiKey,
    ) = ProviderInstance(
        id = id,
        label = id,
        providerType = type,
        credentialType = cred,
        isEnabled = true,
    )

    private fun entry(uuid: String, instanceId: String) = ModelEntry(
        providerInstanceId = instanceId,
        baseModel = LLMModel(
            id = "model-$uuid",
            displayName = "Model $uuid",
            provider = "openai",
            inputModalities = listOf("text"),
            outputModalities = listOf("text"),
        ),
        uuid = uuid,
    )

    private fun group(
        strategy: RoutingStrategy = RoutingStrategy.fallback,
        fallback: FallbackStrategy = FallbackStrategy.default,
    ) = ModelGroup(
        id = "g1",
        name = "Primary",
        memberEntryIds = mutableListOf("e1"),
        strategy = strategy,
        fallbackStrategy = fallback,
    )

    private fun config(
        strategy: RoutingStrategy = RoutingStrategy.fallback,
        fallback: FallbackStrategy = FallbackStrategy.default,
        type: ProviderType = ProviderType.openAI,
        cred: ProviderCredential = ProviderCredential.apiKey,
    ) = ProviderConfig(
        instances = mutableListOf(instance("p1", type, cred)),
        modelEntries = mutableListOf(entry("e1", "p1")),
        modelGroups = mutableListOf(group(strategy, fallback)),
        defaultPrimaryGroupId = "g1",
    )

    private fun roundTrip(source: ProviderConfig): ProviderConfig =
        source.toSnapshot(blobJson).toProviderConfig(blobJson)

    /** Raw column replaced — how an unknown name reaches the reader. */
    private fun snapshotWithRawStrategy(raw: String): ProviderConfigSnapshot =
        config().toSnapshot(blobJson).let { snap ->
            snap.copy(groups = snap.groups.map { it.copy(strategy = raw) })
        }

    private fun snapshotWithRawFallback(raw: String): ProviderConfigSnapshot =
        config().toSnapshot(blobJson).let { snap ->
            snap.copy(groups = snap.groups.map { it.copy(fallbackStrategy = raw) })
        }

    private fun snapshotWithInstance(
        rewrite: (ProviderInstanceEntity) -> ProviderInstanceEntity,
    ): ProviderConfigSnapshot =
        config().toSnapshot(blobJson).let { snap ->
            snap.copy(instances = snap.instances.map(rewrite))
        }

    // --- known values survive the round trip --------------------------------

    @Test
    fun `every routing strategy survives a snapshot round trip`() {
        RoutingStrategy.entries.forEach { strategy ->
            assertEquals(
                "strategy $strategy must survive toSnapshot/toProviderConfig unchanged",
                strategy,
                roundTrip(config(strategy = strategy)).modelGroups.single().strategy,
            )
        }
    }

    @Test
    fun `every fallback strategy survives a snapshot round trip`() {
        FallbackStrategy.entries.forEach { fb ->
            assertEquals(
                "fallback strategy $fb must survive unchanged",
                fb,
                roundTrip(config(fallback = fb)).modelGroups.single().fallbackStrategy,
            )
        }
    }

    @Test
    fun `provider type and credential type survive a snapshot round trip`() {
        ProviderType.entries.forEach { type ->
            assertEquals(
                "provider type $type must survive unchanged",
                type,
                roundTrip(config(type = type)).instances.single().providerType,
            )
        }
        ProviderCredential.entries.forEach { cred ->
            assertEquals(
                "credential type $cred must survive unchanged",
                cred,
                roundTrip(config(cred = cred)).instances.single().credentialType,
            )
        }
    }

    // --- the JSON mirror's answer, derived rather than restated -------------

    /**
     * What the mirror does with an unrecognised value for one field: encode a real
     * config, swap that field's wire value, decode it back.
     *
     * Derived at runtime on purpose. If the mirror's coercion changes — a property
     * becomes nullable, `coerceInputValues` is dropped, a default moves — this helper
     * moves with it and the assertions below follow. A hard-coded expectation would
     * keep passing while the two readers drifted apart.
     */
    private fun mirrorAnswerFor(rewrite: (String) -> String): ProviderConfig {
        val encoded = mirrorJson.encodeToString(ProviderConfig.serializer(), config())
        val rewritten = rewrite(encoded)
        assertTrue("the rewrite must actually change the payload", rewritten != encoded)
        return mirrorJson.decodeFromString(ProviderConfig.serializer(), rewritten)
    }

    // --- unknown values must not throw, and must match the mirror -----------

    /**
     * Only the enums carrying a Kotlin default can be coerced by the mirror —
     * `coerceInputValues` needs a default (or nullability) to coerce *into*.
     * `ModelGroup.strategy` and `.fallbackStrategy` both default, so these two have
     * a mirror answer to agree with.
     */
    @Test
    fun `an unknown persisted strategy does not throw and lands where the mirror lands`() {
        val bogus = "priority" // a strategy a NEWER build could add

        val fromDb = snapshotWithRawStrategy(bogus).toProviderConfig(blobJson)
        val fromMirror = mirrorAnswerFor {
            it.replace("\"strategy\":\"fallback\"", "\"strategy\":\"$bogus\"")
        }

        assertEquals(
            "the database reader must coerce an unknown strategy exactly where the JSON " +
                "mirror coerces it, or the two readers disagree about the same bytes",
            fromMirror.modelGroups.single().strategy,
            fromDb.modelGroups.single().strategy,
        )
        assertEquals(
            "unknown must land on the narrow routing rule, not `none` (a silent pin) and " +
                "not `loadBalance` (silent rotation)",
            RoutingStrategy.fallback,
            fromDb.modelGroups.single().strategy,
        )
    }

    @Test
    fun `an unknown persisted fallback strategy does not throw and lands where the mirror lands`() {
        val bogus = "aggressive"

        val fromDb = snapshotWithRawFallback(bogus).toProviderConfig(blobJson)
        val fromMirror = mirrorAnswerFor {
            it.replace("\"fallbackStrategy\":\"default\"", "\"fallbackStrategy\":\"$bogus\"")
        }

        assertEquals(
            "unknown fallback strategy must agree with the mirror",
            fromMirror.modelGroups.single().fallbackStrategy,
            fromDb.modelGroups.single().fallbackStrategy,
        )
        assertEquals(
            "widening an unknown value to `always` would retry on auth failures the user " +
                "never agreed to; the conservative direction is the only safe one",
            FallbackStrategy.default,
            fromDb.modelGroups.single().fallbackStrategy,
        )
    }

    @Test
    fun `an unknown persisted provider type does not throw`() {
        val bogus = "futureVendor" // a type a NEWER build or an iOS package could carry

        val fromDb = snapshotWithInstance { it.copy(providerType = bogus) }.toProviderConfig(blobJson)

        assertEquals(
            "`unsupported` is the documented target for an unrecognised provider type — " +
                "mirroring iOS `ProviderType.decoded(_:)` — and the instance stays visible as " +
                "one that cannot be driven, rather than vanishing",
            ProviderType.unsupported,
            fromDb.instances.single().providerType,
        )
    }

    @Test
    fun `an unknown persisted credential type does not throw`() {
        val bogus = "deviceCode"

        val fromDb = snapshotWithInstance { it.copy(credentialType = bogus) }.toProviderConfig(blobJson)

        assertFalse(
            "the reader must not have dropped the instance over an unknown value",
            fromDb.instances.isEmpty(),
        )
        assertEquals(
            "apiKey is the shape that carries its own secret, so it is the safe landing",
            ProviderCredential.apiKey,
            fromDb.instances.single().credentialType,
        )
    }

    /**
     * Why the two tests above matter, pinned rather than described.
     *
     * The whole-document decode — the one `ProviderRepository` falls back to when the
     * DB load fails — **still throws** on an unrecognised `providerType`. That is not
     * an oversight to fix here: `coerceInputValues` can only coerce into a property
     * that has a default or is nullable, and `ProviderInstance.providerType` is
     * neither, deliberately — the real tolerance lives one layer up in
     * `BackupImporter.parseProviderConfigLeniently`, which drops only the offending
     * instance. `RestoreProviderTypeToleranceTest` documents the field failure that
     * produced it.
     *
     * That is what makes the DB reader's own tolerance load-bearing: if
     * `toProviderConfig` threw on an unknown name, control would pass to a decoder
     * that throws identically — so one unknown value could take the whole config.
     * Coercing at the DB boundary means that hand-off never happens.
     *
     * Pinned as an assertion so a later change making the mirror tolerant turns this
     * red — the signal to revisit the reasoning above rather than let it go stale.
     */
    @Test
    fun `the whole-document mirror decode still throws, which is why the db reader must not`() {
        val bogus = "futureVendor"
        val encoded = mirrorJson.encodeToString(ProviderConfig.serializer(), config())
            .replace("\"providerType\":\"openAI\"", "\"providerType\":\"$bogus\"")
        assertTrue(
            "the rewrite must actually change the payload, or this test proves nothing",
            encoded.contains("\"providerType\":\"$bogus\""),
        )

        assertThrows(SerializationException::class.java) {
            mirrorJson.decodeFromString(ProviderConfig.serializer(), encoded)
        }
    }

    // --- the row the write side actually produces ---------------------------

    /**
     * The read side can only be exercised if the write side persists names. If a
     * future change started storing ordinals, every `decoded()` above would still
     * compile and every assertion in this file would still pass on freshly written
     * rows — while every row already on disk became unreadable. Asserting the wire
     * shape is what keeps the two halves coupled.
     */
    @Test
    fun `the write side persists enum names, not ordinals`() {
        val snap = config(strategy = RoutingStrategy.loadBalance).toSnapshot(blobJson)

        assertEquals("loadBalance", snap.groups.single().strategy)
        assertEquals("default", snap.groups.single().fallbackStrategy)
        assertEquals("openAI", snap.instances.single().providerType)
        assertEquals("apiKey", snap.instances.single().credentialType)
    }
}
