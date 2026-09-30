package com.openminis.app.ui.sessions

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.RoutingStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [R11-F1] / [T-android-regenerate-title-submodel] The order a title request
 * walks its candidate models in — the rule `SessionListViewModel` calls at its
 * manual Regenerate site.
 *
 * ## Why this file exists
 *
 * `titleCandidateLadderIn` was already extracted for exactly this purpose: its
 * own KDoc says "so production code and the JVM tests call the SAME function",
 * and the call site repeats that the ordering, the T334 modality filter and the
 * RoutingStrategy gate "live in titleCandidateLadderIn, so the tests call the
 * same function production calls". No test called it — the promise was in two
 * comments and nowhere in the suite, so the ladder's two field-visible rules
 * (a dedicated title sub-model must lead; `RoutingStrategy.none` must stop the
 * walk from failing past a pinned member) could regress unnoticed.
 *
 * Everything here runs the production function; the fixtures are plain data
 * classes, which is why no repository is needed.
 */
class TitleCandidateLadderTest {

    // ─── fixtures ────────────────────────────────────────────────────────

    private fun entry(uuid: String, modelId: String = uuid, outputs: List<String>? = listOf("text")) =
        ModelEntry(
            providerInstanceId = "p1",
            baseModel = LLMModel(
                id = modelId,
                displayName = modelId,
                provider = "openai",
                outputModalities = outputs,
            ),
            uuid = uuid,
        )

    private fun group(id: String, members: List<String>, strategy: RoutingStrategy) =
        ModelGroup(
            id = id,
            name = id,
            memberEntryIds = members.toMutableList(),
            strategy = strategy,
        )

    private fun ladder(
        entries: List<ModelEntry>,
        groups: List<ModelGroup> = emptyList(),
        sessionModelId: String? = null,
        subEntry: ModelEntry? = null,
    ) = titleCandidateLadderIn(entries, groups, sessionModelId, subEntry).map { it.id }

    // ─── the order ───────────────────────────────────────────────────────

    @Test
    fun `without a sub-model the session's own model leads`() {
        val a = entry("a")
        val b = entry("b")
        val c = entry("c")
        assertEquals(listOf("b", "a", "c"), ladder(listOf(a, b, c), sessionModelId = "b"))
    }

    @Test
    fun `a dedicated title sub-model leads, then the session model, then the rest`() {
        val a = entry("a")
        val b = entry("b")
        val cheap = entry("cheap")
        assertEquals(
            listOf("cheap", "b", "a"),
            ladder(listOf(a, b, cheap), sessionModelId = "b", subEntry = cheap),
        )
    }

    @Test
    fun `an unknown session model leaves the ladder in catalog order`() {
        val a = entry("a")
        val b = entry("b")
        assertEquals(listOf("a", "b"), ladder(listOf(a, b), sessionModelId = "gone"))
    }

    @Test
    fun `a sub-model that is not eligible is ignored, not prepended`() {
        val a = entry("a")
        val b = entry("b")
        val tts = entry("tts-1")
        // Same entry object, but it cannot be a title model — the ladder must not
        // lead with it (a title request to a TTS model answers with HTTP 400).
        assertEquals(listOf("b", "a"), ladder(listOf(a, b, tts), sessionModelId = "b", subEntry = tts))
    }

    // ─── the T334 modality filter ────────────────────────────────────────

    @Test
    fun `non-chat models are filtered out of the ladder`() {
        val chat = entry("chat")
        val byOutput = entry("by-output", outputs = listOf("audio"))
        val byId = listOf(
            entry("tts-1"),
            entry("voiceclone-x"),
            entry("voicedesign-x"),
            entry("embedding-3"),
            entry("embed-english"),
            entry("whisper-large"),
            entry("gpt-image-1"),
            entry("video-gen"),
        )
        val nullOutputs = entry("null-outputs", outputs = null)
        val emptyOutputs = entry("empty-outputs", outputs = emptyList())
        val multi = entry("text-and-audio", outputs = listOf("text", "audio"))

        val got = ladder(listOf(chat) + byId + listOf(byOutput, nullOutputs, emptyOutputs, multi))
        // Order is preserved for the survivors; undeclared outputs count as chat.
        assertEquals(listOf("chat", "null-outputs", "empty-outputs", "text-and-audio"), got)
        for (dropped in byId + listOf(byOutput)) {
            assertTrue("${dropped.id} must not be a title candidate", dropped.id !in got)
        }
    }

    @Test
    fun `the filter matches the model id case-insensitively`() {
        assertEquals(emptyList<String>(), ladder(listOf(entry("m", modelId = "QWEN-TTS-Plus"))))
        assertEquals(listOf("m"), ladder(listOf(entry("m", modelId = "QWEN-Plus"))))
    }

    // ─── the RoutingStrategy.none pin ([R11-F1]) ─────────────────────────

    @Test
    fun `a none-strategy member pins the walk so it cannot fail over past it`() {
        val a = entry("a")
        val pinned = entry("pinned")
        val c = entry("c")
        val groups = listOf(
            group("g-fallback", listOf("a"), RoutingStrategy.fallback),
            group("g-none", listOf("pinned"), RoutingStrategy.none),
        )
        // The walk reaches "pinned" and stops there — "c" is beyond it.
        assertEquals(listOf("a", "pinned"), ladder(listOf(a, pinned, c), groups))
    }

    @Test
    fun `a pinned member outside the ladder cannot pin anything`() {
        val a = entry("a")
        val b = entry("b")
        // The group is `none`, but its only member is not a title candidate.
        val groups = listOf(group("g-none", listOf("dropped"), RoutingStrategy.none))
        assertEquals(listOf("a", "b"), ladder(listOf(a, b, entry("dropped", outputs = listOf("audio"))), groups))
    }

    @Test
    fun `only members of a none group are pinned - an orphan ahead of one is kept`() {
        val orphan = entry("orphan")
        val pinned = entry("pinned")
        val past = entry("past")
        val groups = listOf(group("g-none", listOf("pinned"), RoutingStrategy.none))
        // "orphan" is in no group, so nothing constrains it (no group, no
        // promise) and it is walked; the walk stops AT "pinned", so "past" is
        // not.
        assertEquals(listOf("orphan", "pinned"), ladder(listOf(orphan, pinned, past), groups))
    }

    @Test
    fun `the pin is positional - it does not reorder the ladder`() {
        val first = entry("first")
        val pinned = entry("pinned")
        val groups = listOf(group("g-none", listOf("pinned"), RoutingStrategy.none))
        // Pinning truncates; it must not promote the pinned member to the front.
        assertEquals(listOf("first", "pinned"), ladder(listOf(first, pinned), groups))
    }

    // ─── a property a filtered/rebuilt ladder cannot satisfy ─────────────

    /**
     * The ladder carries the SAME [ModelEntry] objects the caller passed in, in
     * the input's relative order. A ladder rebuilt from ids (or copied) would
     * still compare equal by id while every downstream binding — which keys on
     * entry identity — pointed at a different object.
     */
    @Test
    fun `the ladder carries the input entries themselves`() {
        val a = entry("a")
        val cheap = entry("cheap")
        val c = entry("c")
        val out = titleCandidateLadderIn(listOf(a, cheap, c), emptyList(), sessionModelId = "a", subEntry = cheap)
        assertEquals(listOf("cheap", "a", "c"), out.map { it.id })
        assertSame(cheap, out[0])
        assertSame(a, out[1])
        assertSame(c, out[2])
    }

    @Test
    fun `the ladder is a stable subsequence of the input`() {
        val entries = listOf(entry("a"), entry("b"), entry("c"), entry("d", outputs = listOf("audio")))
        val out = titleCandidateLadderIn(entries, emptyList(), sessionModelId = "c", subEntry = null).map { it.id }
        assertEquals(listOf("c", "a", "b"), out)
        val known = entries.map { it.id }
        assertEquals("no entry may be invented or duplicated", out.distinct().size, out.size)
        assertTrue("every candidate must be one of the entries handed in", out.all { it in known })
        assertTrue("`d` is not a title candidate at all", "d" !in out)
    }
}
