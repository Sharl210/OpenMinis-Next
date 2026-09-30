package com.openminis.app.data.repository

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.model.RoutingStrategy
import com.openminis.app.tools.ReadImageTool
import com.openminis.app.ui.chat.mainAgentTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-vision-group / GH#182] The Vision Group gate: one verdict, and the
 * `read_image` exposure it decides.
 *
 * ## Why this file calls production code instead of restating it
 *
 * `ProviderRepository` cannot be constructed off-device (Context + Room +
 * EncryptedSharedPreferences), so the neighbouring test for the *model-group*
 * resolution rule (`GroupResolveCredentialFilterTest`) re-implements the filter
 * in the test itself — which means a change to the production filter leaves that
 * test green. That trap is exactly what this area's sister defect was made of
 * ("Provisioning… the same concept decided in several places, only the copy
 * asserted"), so this file does the opposite: `resolveVisionCandidatesIn` is the
 * real decision the repository and `VisionGroupResolver.isConfigured` both read,
 * and every assertion below runs it.
 *
 * [Repository entry] `ProviderRepository.hasVisionGroupConfigured()` — the name
 * that used to carry a second, weaker copy of this rule — is
 * `resolveVisionCandidates().isNotEmpty()`, and `resolveVisionCandidates()`
 * delegates to exactly the function every assertion here calls. So exercising
 * `resolveVisionCandidatesIn` IS exercising the repository's verdict, one hop
 * short of the repository object.
 *
 * That hop is not skipped, it is covered elsewhere and for a real reason: the
 * previous version of this file reached the repository object via
 * `Unsafe.allocateInstance` + reflection into `_config`/`_configLoaded`. That
 * never worked — `sun.misc.Unsafe` does not resolve on the Android unit-test
 * compile classpath, so the whole FILE failed to compile and took the entire
 * `compileDebugUnitTestKotlin` task down with it (every other agent's unit tests
 * included). A test that cannot compile protects nothing. The wiring it was
 * trying to prove — "the repository and `VisionGroupResolver.isConfigured` both
 * read this one function" — is pinned by `SingleJudgementWiringTest`'s
 * "the vision group gate has exactly one implementation", which reads the
 * production sources directly and needs no repository instance.
 *
 * ## The two field incidents this pins, in both directions
 *
 * 1. TOO STRICT (recorded in VisionGroupResolver, `[T-vision-group-gate-too-strict]`):
 *    a credential probe answered "can this call succeed right now" instead of "is
 *    this model capable", and when it came back false for an incidental reason
 *    the whole `read_image` tool vanished from the tools array — the model could
 *    not act and could not explain why. Pinned by
 *    [a member counts without any stored credential].
 * 2. TOO LOOSE (the copy this change removed): "a group is bound and still
 *    exists" answered true for a group whose members are all disabled, dangling
 *    or non-vision, so `read_image` would be advertised for a model that cannot
 *    read anything. Pinned by [a bound group that can serve nothing is not
 *    configured].
 */
class VisionGroupGateTest {

    // ─── fixtures ────────────────────────────────────────────────────────

    private fun instance(id: String, enabled: Boolean = true) = ProviderInstance(
        id = id,
        label = id,
        providerType = ProviderType.openAI,
        credentialType = ProviderCredential.apiKey,
        isEnabled = enabled,
    )

    /** [sees] drives the model's declared input modalities, i.e. `hasImageInput`. */
    private fun entry(uuid: String, instanceId: String, sees: Boolean) = ModelEntry(
        providerInstanceId = instanceId,
        baseModel = LLMModel(
            id = "model-$uuid",
            displayName = "Model $uuid",
            provider = "openai",
            inputModalities = if (sees) listOf("text", "image") else listOf("text"),
        ),
        uuid = uuid,
    )

    private fun group(
        id: String,
        members: List<String>,
        strategy: RoutingStrategy = RoutingStrategy.fallback,
    ) = ModelGroup(id = id, name = "Vision $id", memberEntryIds = members.toMutableList(), strategy = strategy)

    private fun config(
        instances: List<ProviderInstance>,
        entries: List<ModelEntry>,
        groups: List<ModelGroup>,
        visionGroupId: String?,
    ) = ProviderConfig(
        instances = instances.toMutableList(),
        modelEntries = entries.toMutableList(),
        modelGroups = groups.toMutableList(),
        visionGroupId = visionGroupId,
    )

    /**
     * The gate's verdict, read through the same production decision the
     * repository's entry point delegates to. See the class KDoc for why this is
     * the repository's verdict and not a copy of it.
     */
    private fun configured(config: ProviderConfig): Boolean =
        resolveVisionCandidatesIn(config).isNotEmpty()

    /** Whether the model would actually see the tool, given that verdict. */
    private fun readImageAdvertised(visionGroupConfigured: Boolean): Boolean =
        mainAgentTools(
            supportsImageInput = false,
            visionGroupConfigured = visionGroupConfigured,
            memoryEnabled = true,
            goalActive = false,
            coordinator = null,
            actorSessionId = "root",
        ).any { it.name == ReadImageTool.NAME }

    // ─── the live verdict ────────────────────────────────────────────────

    @Test
    fun `a usable group resolves and reports configured`() {
        val cfg = config(
            instances = listOf(instance("p1")),
            entries = listOf(entry("e1", "p1", sees = true)),
            groups = listOf(group("g1", listOf("e1"))),
            visionGroupId = "g1",
        )
        assertEquals(listOf("e1"), resolveVisionCandidatesIn(cfg).map { it.second.id })
        assertTrue("a bound group with an enabled vision model IS configured", configured(cfg))
    }

    @Test
    fun `a bound group that can serve nothing is not configured`() {
        // Each shape below answered TRUE under the removed copy ("bound and the
        // group still exists") while the real gate answered no — i.e. read_image
        // would have been advertised to a model that cannot read anything.
        val cases = mapOf(
            "no members at all" to config(
                instances = listOf(instance("p1")),
                entries = listOf(entry("e1", "p1", sees = true)),
                groups = listOf(group("g1", emptyList())),
                visionGroupId = "g1",
            ),
            "member entry is dangling" to config(
                instances = listOf(instance("p1")),
                entries = listOf(entry("e1", "p1", sees = true)),
                groups = listOf(group("g1", listOf("gone"))),
                visionGroupId = "g1",
            ),
            "member instance is dangling" to config(
                instances = listOf(instance("p1")),
                entries = listOf(entry("e1", "p2", sees = true)),
                groups = listOf(group("g1", listOf("e1"))),
                visionGroupId = "g1",
            ),
            "member instance is disabled" to config(
                instances = listOf(instance("p1", enabled = false)),
                entries = listOf(entry("e1", "p1", sees = true)),
                groups = listOf(group("g1", listOf("e1"))),
                visionGroupId = "g1",
            ),
            "member model cannot see images" to config(
                instances = listOf(instance("p1")),
                entries = listOf(entry("e1", "p1", sees = false)),
                groups = listOf(group("g1", listOf("e1"))),
                visionGroupId = "g1",
            ),
        )
        for ((why, cfg) in cases) {
            assertTrue("no candidates expected when $why", resolveVisionCandidatesIn(cfg).isEmpty())
            assertFalse("must not report configured when $why", configured(cfg))
        }
    }

    @Test
    fun `no bound group is not configured`() {
        val cfg = config(
            instances = listOf(instance("p1")),
            entries = listOf(entry("e1", "p1", sees = true)),
            groups = listOf(group("g1", listOf("e1"))),
            visionGroupId = null,
        )
        assertFalse(configured(cfg))
    }

    @Test
    fun `a pointer to a group that no longer exists is not configured`() {
        val cfg = config(
            instances = listOf(instance("p1")),
            entries = listOf(entry("e1", "p1", sees = true)),
            groups = listOf(group("g1", listOf("e1"))),
            visionGroupId = "deleted-group",
        )
        assertFalse(configured(cfg))
    }

    /**
     * The 1st field incident, from the other side: capability, not credential
     * availability. `ProviderConfig` is the only input to this verdict, so a
     * member with no API key anywhere cannot be filtered out here — the failure
     * it would produce (a request that fails) is surfaced loudly by
     * `VisionGroupResolver.describe` instead of by removing the tool.
     */
    @Test
    fun `a member counts without any stored credential`() {
        val cfg = config(
            instances = listOf(instance("p1")),
            entries = listOf(entry("e1", "p1", sees = true)),
            groups = listOf(group("g1", listOf("e1"))),
            visionGroupId = "g1",
        )
        assertTrue("capability is the question; credentials are not part of it", configured(cfg))
    }

    @Test
    fun `a dangling member does not disqualify a good sibling`() {
        val cfg = config(
            instances = listOf(instance("p1"), instance("p2", enabled = false)),
            entries = listOf(entry("e1", "p1", sees = true), entry("e2", "p2", sees = true)),
            groups = listOf(group("g1", listOf("gone", "e2", "e1"))),
            visionGroupId = "g1",
        )
        assertEquals("only the usable member survives", listOf("e1"), resolveVisionCandidatesIn(cfg).map { it.second.id })
        assertTrue(configured(cfg))
    }

    // ─── behaviour preserved by the pure extraction ──────────────────────

    @Test
    fun `fallback keeps group order`() {
        val cfg = config(
            instances = listOf(instance("p1")),
            entries = listOf(entry("e1", "p1", sees = true), entry("e2", "p1", sees = true)),
            groups = listOf(group("g1", listOf("e2", "e1"), strategy = RoutingStrategy.fallback)),
            visionGroupId = "g1",
        )
        assertEquals(listOf("e2", "e1"), resolveVisionCandidatesIn(cfg).map { it.second.id })
    }

    @Test
    fun `loadBalance rotates the start and wraps`() {
        val cfg = config(
            instances = listOf(instance("p1")),
            entries = listOf(entry("e1", "p1", sees = true), entry("e2", "p1", sees = true), entry("e3", "p1", sees = true)),
            groups = listOf(group("g1", listOf("e1", "e2", "e3"), strategy = RoutingStrategy.loadBalance)),
            visionGroupId = "g1",
        )
        assertEquals(listOf("e1", "e2", "e3"), resolveVisionCandidatesIn(cfg, 0).map { it.second.id })
        assertEquals(listOf("e2", "e3", "e1"), resolveVisionCandidatesIn(cfg, 1).map { it.second.id })
        assertEquals(listOf("e1", "e2", "e3"), resolveVisionCandidatesIn(cfg, 3).map { it.second.id })
        // Negative seeds are used in production (hash-derived); abs() keeps them in range.
        assertEquals(listOf("e3", "e1", "e2"), resolveVisionCandidatesIn(cfg, -2).map { it.second.id })
    }

    @Test
    fun `none keeps only the first usable member`() {
        val cfg = config(
            instances = listOf(instance("p1")),
            entries = listOf(entry("e1", "p1", sees = true), entry("e2", "p1", sees = true)),
            groups = listOf(group("g1", listOf("e1", "e2"), strategy = RoutingStrategy.none)),
            visionGroupId = "g1",
        )
        assertEquals(listOf("e1"), resolveVisionCandidatesIn(cfg).map { it.second.id })
    }

    @Test
    fun `a member listed twice is only a candidate once`() {
        val cfg = config(
            instances = listOf(instance("p1")),
            entries = listOf(entry("e1", "p1", sees = true)),
            groups = listOf(group("g1", listOf("e1", "e1"))),
            visionGroupId = "g1",
        )
        assertEquals(listOf("e1"), resolveVisionCandidatesIn(cfg).map { it.second.id })
    }

    // ─── what the verdict is FOR: the tool table ─────────────────────────

    /**
     * The observable consequence, on the composition `ChatViewModel.agentTools`
     * actually calls: for a main model that cannot see images, `read_image`
     * exists exactly when the gate above says the group can serve one. This is
     * the hop the "gate too strict" incident broke, and the hop a too-loose
     * verdict would break by advertising a tool that can only fail.
     */
    @Test
    fun `a configured gate is what puts read_image in the model's table`() {
        assertFalse(
            "no native vision and no group → the model must not be offered the tool",
            readImageAdvertised(visionGroupConfigured = false),
        )
        assertTrue(
            "no native vision but a working group → the tool must be there",
            readImageAdvertised(visionGroupConfigured = true),
        )

        val usable = config(
            instances = listOf(instance("p1")),
            entries = listOf(entry("e1", "p1", sees = true)),
            groups = listOf(group("g1", listOf("e1"))),
            visionGroupId = "g1",
        )
        val unusable = config(
            instances = listOf(instance("p1")),
            entries = listOf(entry("e1", "p1", sees = true)),
            groups = listOf(group("g1", emptyList())),
            visionGroupId = "g1",
        )
        assertTrue("end to end: usable group → tool offered", readImageAdvertised(configured(usable)))
        assertFalse("end to end: group that can serve nothing → tool withheld", readImageAdvertised(configured(unusable)))
    }
}
