package com.openminis.app.data.repository

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.model.RoutingStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-vision-group-gate] The discriminating case [VisionGroupGateTest] structurally
 * cannot see: a bound group that can serve nothing must stay empty instead of
 * borrowing a member from ANOTHER group record of the same config.
 *
 * ## The seam
 *
 * Every "cannot serve" fixture in `VisionGroupGateTest` — dangling entry, dangling
 * instance, disabled instance, non-vision model, empty member list — contains
 * exactly ONE group record, so "fall back to some other group" and the real rule
 * refuse the same way. Measured against widened copies of `resolveVisionCandidatesIn`
 * (production source copied to /tmp, one clause loosened per run, `K2JVMCompiler` +
 * JUnitCore, 11 existing tests):
 *
 * | widened rule | `VisionGroupGateTest` | this file |
 * |---|---|---|
 * | bound group serves nothing → borrow a sibling group record's members | `OK (11 tests)` — **0 red** | 1 red |
 * | `it.id == gid` → `it.id.equals(gid, ignoreCase = true)` | `OK (11 tests)` — **0 red** | 1 red |
 * | (for contrast) drop `hasImageInput` / drop `isEnabled` / fall back to the first entry / first instance / first group / `none` keeps all / no dedup / reversed load-balance rotation / dedup by instance | 1–2 red each | — |
 *
 * The widening is not hypothetical: "the bound group is empty, use whatever group
 * we have" is exactly the shape a well-meaning "make it work when the group is
 * misconfigured" change takes, and it would advertise `read_image` to a session
 * whose configured group cannot read anything — the TOO LOOSE incident recorded in
 * `VisionGroupGateTest`'s header.
 *
 * ## Shape, and why the premises are asserted
 *
 * The two group records are in the SAME config, neither is an ancestor of the other,
 * and neither is a member of the other's subtree. The premises below are asserted
 * rather than assumed, so a fixture that stops being discriminating (the pointer no
 * longer pointing at the empty group, the sibling no longer being able to serve)
 * fails loudly instead of degrading into a silently green "the fixture was wrong".
 */
class VisionGroupSiblingBorrowDiscriminatorTest {

    private fun instance(id: String) = ProviderInstance(
        id = id,
        label = id,
        providerType = ProviderType.openAI,
        credentialType = ProviderCredential.apiKey,
        isEnabled = true,
    )

    private fun entry(uuid: String, instanceId: String) = ModelEntry(
        providerInstanceId = instanceId,
        baseModel = LLMModel(
            id = "model-$uuid",
            displayName = "Model $uuid",
            provider = "openai",
            inputModalities = listOf("text", "image"),
        ),
        uuid = uuid,
    )

    @Test
    fun `a bound group that can serve nothing must not borrow a sibling group's members`() {
        // The bound group: a real record with no members at all, so it can serve nothing.
        val bound = ModelGroup(id = "g-bound", name = "Bound", memberEntryIds = mutableListOf())
        // Another group record in the same config: it CAN serve, but it is not the bound one.
        val sibling = ModelGroup(
            id = "g-sibling",
            name = "Sibling",
            memberEntryIds = mutableListOf("e1"),
            strategy = RoutingStrategy.fallback,
        )
        val cfg = ProviderConfig(
            instances = mutableListOf(instance("p1")),
            modelEntries = mutableListOf(entry("e1", "p1")),
            modelGroups = mutableListOf(bound, sibling),
            visionGroupId = "g-bound",
        )

        // ── premises: asserted, not assumed ─────────────────────────────────
        assertEquals("both group records must live in ONE config", 2, cfg.modelGroups.size)
        assertTrue(
            "the bound group must be a real record (not a typo'd id)",
            cfg.modelGroups.any { it.id == "g-bound" },
        )
        assertNotEquals("the group that would be borrowed must be a different record", "g-bound", "g-sibling")
        assertTrue("the bound group exists but has zero members", bound.memberEntryIds.isEmpty())
        assertEquals("the pointer really does name the bound group", "g-bound", cfg.visionGroupId)
        assertTrue(
            "neither group is the other's ancestor or subtree member",
            sibling.memberEntryIds.none { it == bound.id } &&
                bound.memberEntryIds.none { it == sibling.id },
        )
        assertEquals("the borrowable group's member entry really exists in the same config", 1, cfg.modelEntries.size)
        assertEquals("and its provider instance is there too", "p1", cfg.modelEntries[0].providerInstanceId)

        // ── control ─────────────────────────────────────────────────────────
        // This only shows the sibling group IS servable, i.e. that the widened rule
        // would have something to borrow. It holds under BOTH rules, so it cannot
        // stand in for the discriminator below.
        assertEquals(
            "control: pointing at the sibling group resolves to its member under either rule",
            listOf("e1"),
            resolveVisionCandidatesIn(cfg.copy(visionGroupId = "g-sibling")).map { it.second.id },
        )

        // ── discriminator ───────────────────────────────────────────────────
        val got = resolveVisionCandidatesIn(cfg)
        assertEquals(
            "a bound group that can serve nothing must judge EXACTLY empty — borrowing another " +
                "group record's members is the widening this pins",
            emptyList<String>(),
            got.map { it.second.id },
        )
        assertTrue(
            "in particular the sibling's entry e1 must not appear (its presence means the " +
                "verdict came from a group that was never bound)",
            got.none { it.second.id == "e1" },
        )
    }
}
