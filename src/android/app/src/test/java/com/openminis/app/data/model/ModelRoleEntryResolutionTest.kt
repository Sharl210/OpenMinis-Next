package com.openminis.app.data.model

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `plans/ULW-2026-09-25-01/request.md:217` — four classes of model, each with its
 * own setting, and one of them has a fallback the others do not:
 *
 *   主代理模型…它肯定是唯一的，因为同一时间只能用一个模型去对话
 *   子代理模型…它可以是一个集合
 *   标题生成模型也要可以单独设置，这个也是一个唯一性的模型
 *   还有一个对话压缩模型，如果不设置的话…对应绘画正在用的模型自行去压缩，如果没设置的话
 *
 * [ModelRoleRequestSnapshot] is the id-level half of that contract — what the
 * settings picked, captured at request time so a later settings change cannot
 * rewrite history. The entry-level half (does that id still resolve to a usable
 * entry?) lives in [ModelRoleSelectionResolver] and is covered separately.
 *
 * Two things this file exists for, both of which a config round-trip test cannot
 * see:
 *
 *  - the COMPACTION fallback is real (`? : primaryEntryId`), and its *absence* of
 *    a fallback when there is no primary is the honest degradation;
 *  - TITLE has no such fallback, so the two "unique model" roles are not
 *    interchangeable — collapsing them is exactly how four roles become three.
 */
class ModelRoleEntryResolutionTest {

    // ── 1. Four settings, four independent pointers ──────────────────────

    @Test
    fun `the four role pointers resolve to four different entries`() {
        val config = ProviderConfig(
            primaryModelEntryId = "primary",
            agentLoopModelEntryIds = mutableListOf("child-a", "child-b"),
            titleModelEntryId = "title",
            compactionModelEntryId = "compaction",
        )
        val snapshot = ModelRoleRequestSnapshot.from(config)

        assertEquals("primary", snapshot.primaryEntryId)
        assertEquals("title", snapshot.titleEntryId)
        assertEquals("compaction", snapshot.compactionEntryId)
        assertEquals("compaction wins over the primary fallback", "compaction", snapshot.effectiveCompactionEntryId())
        assertEquals(listOf("child-a", "child-b"), snapshot.childEntryIds)
    }

    @Test
    fun `moving one pointer moves only that pointer`() {
        val config = ProviderConfig(
            primaryModelEntryId = "primary",
            agentLoopModelEntryIds = mutableListOf("child-a"),
            titleModelEntryId = "title",
            compactionModelEntryId = "compaction",
        )
        val before = ModelRoleRequestSnapshot.from(config)

        config.titleModelEntryId = "title-2"
        val afterTitle = ModelRoleRequestSnapshot.from(config)
        assertEquals("title-2", afterTitle.titleEntryId)
        assertEquals(before.primaryEntryId, afterTitle.primaryEntryId)
        assertEquals(before.compactionEntryId, afterTitle.compactionEntryId)
        assertEquals(before.childEntryIds, afterTitle.childEntryIds)

        config.compactionModelEntryId = "compaction-2"
        val afterCompaction = ModelRoleRequestSnapshot.from(config)
        assertEquals("compaction-2", afterCompaction.compactionEntryId)
        assertEquals("title-2", afterCompaction.titleEntryId)
        assertEquals(before.primaryEntryId, afterCompaction.primaryEntryId)
    }

    @Test
    fun `the primary is one entry and the children are a set`() {
        // request.md:217 spells the difference out: one model in a conversation at
        // a time for the primary, a collection for the children. The shapes say
        // so — a String? against a List<String> — and a change that turned one
        // into the other would be a silent role collapse.
        val config = ProviderConfig(
            primaryModelEntryId = "primary",
            agentLoopModelEntryIds = mutableListOf("child-a", "child-b", "child-c"),
        )
        val snapshot = ModelRoleRequestSnapshot.from(config)

        assertEquals("primary", snapshot.primaryEntryId)
        assertEquals(3, snapshot.childEntryIds.size)
    }

    @Test
    fun `the child collection keeps its first-seen order and drops duplicates`() {
        // The order is what the child picker shows and what the parent agent
        // chooses from, so it is part of the contract; the de-duplication is the
        // snapshot's own guard against a config that lists the same entry twice.
        val config = ProviderConfig(
            agentLoopModelEntryIds = mutableListOf("b", "a", "b", "c", "a"),
        )

        assertEquals(listOf("b", "a", "c"), ModelRoleRequestSnapshot.from(config).childEntryIds)
    }

    // ── 2. The compaction fallback — request.md:217's boundary ───────────

    @Test
    fun `an unset compaction model falls back to the conversation's own model`() {
        val config = ProviderConfig(primaryModelEntryId = "primary")

        assertNull(config.compactionModelEntryId)
        assertEquals("primary", ModelRoleRequestSnapshot.from(config).effectiveCompactionEntryId())
    }

    @Test
    fun `with neither compaction nor primary set the fallback yields nothing`() {
        // Honest degradation, not a guess: there is nothing to fall back TO. The
        // caller records the provider's own model instead of inventing an entry.
        // Pinned because "falls back to the primary" must not be read as
        // "always returns something".
        val snapshot = ModelRoleRequestSnapshot.from(ProviderConfig())

        assertNull(snapshot.primaryEntryId)
        assertNull(snapshot.compactionEntryId)
        assertNull(snapshot.effectiveCompactionEntryId())
    }

    @Test
    fun `the compaction fallback ignores the title model`() {
        // The tempting wrong fallback. An unset compaction model must never
        // borrow the title model's entry: they are two request shapes with two
        // different prompts.
        val config = ProviderConfig(primaryModelEntryId = "primary", titleModelEntryId = "title")

        assertEquals("primary", ModelRoleRequestSnapshot.from(config).effectiveCompactionEntryId())
        assertNotEquals("title", ModelRoleRequestSnapshot.from(config).effectiveCompactionEntryId())
    }

    // ── 3. The title model has no fallback — the asymmetry ───────────────

    @Test
    fun `an unset title model does not fall back to the primary model`() {
        val config = ProviderConfig(primaryModelEntryId = "primary")
        val snapshot = ModelRoleRequestSnapshot.from(config)

        assertNull("the title role must not borrow the primary entry", snapshot.titleEntryId)
        assertEquals(
            "and it must not be smuggled in through the compaction fallback either",
            "primary",
            snapshot.effectiveCompactionEntryId(),
        )
        assertNotEquals(snapshot.titleEntryId, snapshot.effectiveCompactionEntryId())
    }

    @Test
    fun `the snapshot preserves the pointer verbatim, even a stale one`() {
        // The id level and the entry level disagree on purpose, and the
        // difference matters:
        //
        //  - the SNAPSHOT is a record of what the settings said, never a
        //    resolution. A pointer whose entry was since deleted is still the
        //    pointer the user picked, and it still WINS over the fallback —
        //    `compactionEntryId ?: primaryEntryId` is a null check, not a
        //    "is this entry still usable" check.
        //  - deciding whether that pointer resolves to a callable entry is
        //    ModelRoleSelectionResolver's job, and it answers null.
        //
        // Writing this test is how the distinction got pinned down: the first
        // version asserted the fallback would fire for a stale pointer. It does
        // not, and the assertion — not the production code — was wrong.
        val config = ProviderConfig(
            modelEntries = mutableListOf(entry("still-here")),
            primaryModelEntryId = "primary",
            titleModelEntryId = "deleted-entry",
            compactionModelEntryId = "also-deleted",
        )
        val snapshot = ModelRoleRequestSnapshot.from(config)

        assertEquals("deleted-entry", snapshot.titleEntryId)
        assertEquals("also-deleted", snapshot.compactionEntryId)
        assertEquals(
            "an explicit pointer wins over the fallback whether or not it still resolves",
            "also-deleted",
            snapshot.effectiveCompactionEntryId(),
        )
        assertNull(
            "a stale title pointer resolves to no entry — the legacy title subgroup is the fallback tier",
            ModelRoleSelectionResolver.explicitTitleEntry(config),
        )
        assertNull(
            "same for a stale compaction pointer",
            ModelRoleSelectionResolver.explicitCompactionEntry(config),
        )

        // …and with the pointer genuinely unset, the fallback does fire.
        val unset = ProviderConfig(
            modelEntries = mutableListOf(entry("still-here")),
            primaryModelEntryId = "primary",
        )
        assertEquals("primary", ModelRoleRequestSnapshot.from(unset).effectiveCompactionEntryId())
    }

    // ── 4. The resolution reaches the persisted request metadata ─────────

    @Test
    fun `the request metadata records both the explicit and the effective compaction entry`() {
        val config = ProviderConfig(primaryModelEntryId = "primary")
        val selection = ModelRoleRequestSnapshot.from(config)
        val actual = ActualModelRequestSnapshot.capture(ModelRole.COMPACTION, entry("primary"), "openAI")

        val partsJson = JSONArray().put(ModelRoleRequestSnapshot.metadataPart(actual, selection)).toString()
        val metadata = ModelRoleRequestSnapshot.metadataOf(partsJson)

        assertEquals("primary", metadata?.optString("primaryEntryId"))
        assertEquals(
            "the setting as the user left it — unset, recorded as unset",
            true,
            metadata?.isNull("compactionEntryId"),
        )
        assertEquals(
            "which model the request actually went out on must be recoverable from the metadata",
            "primary",
            metadata?.optString("effectiveCompactionEntryId"),
        )
    }

    @Test
    fun `the metadata keeps an explicit compaction entry apart from the fallback`() {
        val selection = ModelRoleRequestSnapshot.from(
            ProviderConfig(primaryModelEntryId = "primary", compactionModelEntryId = "compaction"),
        )
        val actual = ActualModelRequestSnapshot.capture(ModelRole.COMPACTION, entry("compaction"), "openAI")

        val partsJson = JSONArray().put(ModelRoleRequestSnapshot.metadataPart(actual, selection)).toString()
        val metadata = ModelRoleRequestSnapshot.metadataOf(partsJson)

        assertEquals("primary", metadata?.optString("primaryEntryId"))
        assertEquals("compaction", metadata?.optString("compactionEntryId"))
        assertEquals("compaction", metadata?.optString("effectiveCompactionEntryId"))
    }

    private fun entry(id: String) = ModelEntry(
        providerInstanceId = "instance-1",
        baseModel = LLMModel(id = "model-$id", displayName = "Model $id", provider = "test"),
        uuid = id,
    )
}
