package com.openminis.app.data.repository

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-vision-group-gate] The bound group id must be compared literally — an id that
 * differs only in case is a different id, not the same group.
 *
 * ## The seam
 *
 * Every fixture in `VisionGroupGateTest` pairs the bound `visionGroupId` with a group
 * record whose id is either character-for-character identical (`"g1"` vs `"g1"`) or
 * plainly different (`"deleted-group"` vs `"g1"`), so folding the comparison to
 * case-insensitive refuses and accepts exactly the same inputs as the real rule.
 * Measured on a widened copy of `resolveVisionCandidatesIn`
 * (`it.id == gid` → `it.id.equals(gid, ignoreCase = true)`; production source copied
 * to /tmp, `K2JVMCompiler` + JUnitCore): `VisionGroupGateTest` stayed
 * **`OK (11 tests)`, 0 red**, while this file goes red.
 *
 * ## Honest note on realism
 *
 * This widening is one notch weaker than the sibling-borrow case in
 * [VisionGroupSiblingBorrowDiscriminatorTest]: the outsider here is not a foreign
 * record but another SPELLING of the same id. It is still worth pinning, because
 * "compare ids case-insensitively" is a real refactor that lands silently and would
 * light up `read_image` for a session whose configured group is not the one being
 * read — but a reviewer who finds the id-casing scenario implausible for this
 * product can delete this file without weakening the other discriminator.
 */
class VisionGroupIdCasefoldDiscriminatorTest {

    private fun group(id: String, members: List<String>) =
        ModelGroup(id = id, name = "G $id", memberEntryIds = members.toMutableList())

    @Test
    fun `a bound group id differing only in case is not the same group`() {
        val recorded = group("g1", listOf("e1"))
        val cfg = ProviderConfig(
            instances = mutableListOf(
                ProviderInstance(
                    id = "p1",
                    label = "p1",
                    providerType = ProviderType.openAI,
                    credentialType = ProviderCredential.apiKey,
                    isEnabled = true,
                ),
            ),
            modelEntries = mutableListOf(
                ModelEntry(
                    providerInstanceId = "p1",
                    baseModel = LLMModel(
                        id = "model-e1",
                        displayName = "Model e1",
                        provider = "openai",
                        inputModalities = listOf("text", "image"),
                    ),
                    uuid = "e1",
                ),
            ),
            modelGroups = mutableListOf(recorded),
            visionGroupId = "G1",   // differs only in case
        )

        // ── premises ────────────────────────────────────────────────────────
        assertEquals("the config holds exactly one group record", 1, cfg.modelGroups.size)
        assertEquals("that record's id is the lowercase g1", "g1", recorded.id)
        assertEquals("the pointer spells it uppercase G1", "G1", cfg.visionGroupId)
        assertNotEquals("the two differ character-for-character — that IS the premise", "g1", "G1")
        assertTrue("the group really can serve (it has a usable member)", recorded.memberEntryIds.isNotEmpty())

        // ── control (green under BOTH rules; cannot replace the discriminator) ──
        assertEquals(
            "control: the literally-identical pointer resolves to e1 under either rule",
            listOf("e1"),
            resolveVisionCandidatesIn(cfg.copy(visionGroupId = "g1")).map { it.second.id },
        )

        // ── discriminator ───────────────────────────────────────────────────
        assertEquals(
            "an id that is not character-for-character the same must not resolve — the " +
                "comparison is literal, not case-folded",
            emptyList<String>(),
            resolveVisionCandidatesIn(cfg).map { it.second.id },
        )
    }
}
