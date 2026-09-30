package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The identity surface of every advertised tool, asserted once instead of per tool.
 *
 * ## Why this file exists, and why it is not a sixth point assertion
 *
 * The tools whose actor comes from the application-bound session all carry the
 * same rule — no `actor_session_id` in the schema, because the schema is
 * documentation for the model and not an authorization boundary. Until now that
 * rule was pinned by POINT assertions, one tool at a time
 * (`AgentToolsGoalCompleteTest` for `supervise_descendants`,
 * `AgentToolsRestartDescendantTest` for `restart_descendant`,
 * `AgentToolsDeleteSubtreeTest` for `delete_subtree`). Three tools were checked
 * because three people happened to think of them; `message_child` and
 * `message_peer` were not, and nothing anywhere said so.
 *
 * A per-tool assertion cannot answer "is this true of the surface"; it can only
 * answer "is this true of the tool I remembered". The failure mode it leaves
 * open is exactly the one worth closing: a NEW tool ships with an identity
 * field, every existing point assertion stays green, and the advertisement is
 * wrong until someone thinks to write the next point assertion.
 *
 * So this is ONE invariant over EVERY advertised definition, and it covers the
 * gate combinations rather than only the default ones — a tool that is absent
 * by default (`goal_complete` without an active goal, `restart_descendant`
 * without a restartable descendant, the memory tools with memory off,
 * `read_image` without vision) would otherwise never be examined at all.
 *
 * ## What "advertised" means here: the provider-visible JSON, not the Kotlin map
 *
 * The assertion reads the THREE provider converters' real output —
 * `toAnthropicJson().input_schema.properties`,
 * `toGeminiJson().parameters.properties`,
 * `toOpenAIJson().function.parameters.properties` — instead of reading
 * `AgentToolDefinition.parameters`.
 *
 * That is deliberate and strictly stronger. `parameters` is only one of the
 * inputs to what the model finally sees: `schemaProperties` also merges
 * `SCHEDULING_PARAMS` into every tool's property map, so a field injected
 * through THAT path is invisible to a `parameters.containsKey` check while
 * being fully advertised to the model. Asserting on the converter output covers
 * every layer between the definition and the wire, including layers that do not
 * exist yet, and it does not encode my belief about which layer injects what.
 *
 * `required` and Gemini's `propertyOrdering` are read from the same JSON, for
 * the same reason: a name that a provider renders into `required` is a name the
 * model sees.
 *
 * ## What this invariant does NOT check, on purpose
 *
 * **It does not scan `description`.** Free prose must stay free to DISCUSS
 * identity — `restart_descendant`'s own `child_session_id` description says
 * "the acting session is always the current one and cannot be set here", which
 * is the correct advertisement and would be a false positive for any text
 * match. Matching text would trade a real signal for a noisy one, and a noisy
 * invariant gets weakened or deleted the first time it cries wolf (that is a
 * recorded failure mode in this repo, not a hypothetical).
 *
 * **It does not claim the absent field would be honored if it were present.**
 * This file pins the ADVERTISEMENT. Whether a forged field has an effect is a
 * separate property, pinned behaviourally in
 * `AgentToolExecutorIdentityForgeryTest`.
 */
class AgentToolsIdentitySurfaceTest {

    private companion object {
        /**
         * Identity-ish parameter names that must never reach the model.
         *
         * The first six are the forgery surface: a name the model could write to
         * claim to be another session. They are enumerated rather than expressed as
         * a string match on "actor"/"initiator"/"executor", because a substring
         * rule would also condemn legitimate names that merely CONTAIN those words
         * (`target_session_id`, `child_session_id` are targets, not actors — see the
         * positive anchor below) and a rule that cannot tell an actor from a target
         * is not a rule this codebase can keep.
         */
        val FORBIDDEN_IDENTITY_NAMES: List<String> = listOf(
            "actor_session_id",
            "actor",
            "initiator_session_id",
            "initiator",
            "executor_session_id",
            "executor",
            // The seventh name is not a forgery surface in the same sense — it is a
            // session id offered as a PARAMETER — and one tool legitimately declares
            // it. See [EXEMPT_TOOL] and the exemption test at the bottom.
            "session_id",
        )

        /**
         * The one tool allowed to declare `session_id`, with its reason.
         *
         * `browser_devtools` takes a session id as a SCOPE TRIPLE
         * (session, tab, page) and is the opposite design from the tools above: the
         * argument is read and then CHECKED against the application-bound session,
         * with a mismatch refused as `DENIED`
         * (`ChatViewModel.executeBrowserDevToolsTool`), and the scope is re-checked
         * a second time by `BrowserDevToolsTools.authorize` before any WebView call.
         * It is "supplied and validated", not "supplied and ignored" — a different
         * mechanism that happens to use the same word.
         *
         * The exemption is not taken on trust: `the session-id exemption is still
         * real and still needed` asserts the tool is present and still declares the
         * field, so the exemption cannot silently become dead code, and a NEW tool
         * cannot inherit it by accident.
         */
        const val EXEMPT_TOOL = "browser_devtools"

        /** The fields that keep the exemption from turning into over-tightening. */
        val TARGET_FIELDS_THAT_MUST_SURVIVE: Map<String, String> = mapOf(
            "stop_descendant" to "target_session_id",
            "delete_subtree" to "target_session_id",
            "message_child" to "target_session_id",
            "message_peer" to "target_session_id",
            AgentTools.RESTART_DESCENDANT_TOOL_NAME to "child_session_id",
        )

        /**
         * Tools whose actor is the bound session, so each MUST appear in the sweep.
         *
         * Without this, the invariant has a silent hole: delete a tool from
         * `makeAgentTools` and every remaining assertion stays green because the
         * offending definition is simply never visited. Naming the expected members
         * makes absence itself a failure.
         */
        val BOUND_IDENTITY_TOOLS: Set<String> = setOf(
            "supervise_descendants",
            "stop_descendant",
            AgentTools.RESTART_DESCENDANT_TOOL_NAME,
            "delete_subtree",
            "message_child",
            "message_peer",
            "conversation_query",
        )
    }

    // ─── the sweep ───────────────────────────────────────────────────────

    /**
     * Every definition the app can advertise, labelled with the gate combination
     * that produced it so a failure names the surface rather than only the tool.
     *
     * All 2^5 gate combinations, because absence-by-default is exactly how a tool
     * escapes a point assertion.
     */
    private fun advertisedSurface(): List<Pair<String, AgentToolDefinition>> = buildList {
        for (image in listOf(false, true)) {
            for (vision in listOf(false, true)) {
                for (memory in listOf(false, true)) {
                    for (goal in listOf(false, true)) {
                        for (restart in listOf(false, true)) {
                            val label = "makeAgentTools(image=$image vision=$vision memory=$memory " +
                                "goal=$goal restart=$restart)"
                            AgentTools.makeAgentTools(
                                supportsImageInput = image,
                                visionGroupConfigured = vision,
                                memoryEnabled = memory,
                                goalActive = goal,
                                restartAvailable = restart,
                            ).forEach { add("$label/${it.name}" to it) }
                        }
                    }
                }
            }
        }
        AgentTools.makeChildAgentTools().forEach { add("makeChildAgentTools/${it.name}" to it) }
    }

    private fun JSONObject.nameSet(): Set<String> = keys().asSequence().toSet()

    /**
     * The parameter names a provider actually renders for one definition.
     *
     * Read from the converters' output, never from `parameters` — see the class
     * KDoc for why the weaker source would miss `SCHEDULING_PARAMS`.
     *
     * The final `.getJSONObject("properties")` is load-bearing and was the first
     * real defect this file caught in ITSELF. The three converters wrap the
     * parameter map in a schema container, so stopping one step early reads the
     * CONTAINER's keys — `[type, properties, required]` — which contains no
     * parameter name at all and therefore contains no identity field either. The
     * invariant would have passed vacuously and shipped looking like a guard.
     * The two self-check tests below ("the exemption is still real", "target and
     * child fields survive") are what made the mistake visible: they assert names
     * that MUST be present, so an emptily-passing reader turns them red instead of
     * quietly agreeing with it.
     */
    private fun providerVisibleParams(definition: AgentToolDefinition): Map<String, Set<String>> {
        val anthropic = definition.toAnthropicJson()
            .getJSONObject("input_schema")
            .getJSONObject("properties")
        val gemini = definition.toGeminiJson()
            .getJSONObject("parameters")
            .getJSONObject("properties")
        val openai = definition.toOpenAIJson()
            .getJSONObject("function")
            .getJSONObject("parameters")
            .getJSONObject("properties")
        return mapOf(
            "anthropic" to anthropic.nameSet(),
            "gemini" to gemini.nameSet(),
            "openai" to openai.nameSet(),
        )
    }

    /**
     * The names a provider renders into `required` / `propertyOrdering`.
     *
     * These live on the schema CONTAINER (not inside `properties`), which is why
     * this reader and [providerVisibleParams] descend differently — the two field
     * groups sit at different levels of the same JSON.
     */
    private fun providerDeclaredNames(definition: AgentToolDefinition): Map<String, Set<String>> {
        fun strings(array: JSONArray?): Set<String> {
            if (array == null) return emptySet()
            return (0 until array.length()).map { array.getString(it) }.toSet()
        }

        val anthropic = definition.toAnthropicJson().getJSONObject("input_schema")
        val gemini = definition.toGeminiJson().getJSONObject("parameters")
        val openai = definition.toOpenAIJson()
            .getJSONObject("function")
            .getJSONObject("parameters")
        return mapOf(
            "anthropic.required" to strings(anthropic.optJSONArray("required")),
            "gemini.required" to strings(gemini.optJSONArray("required")),
            "gemini.propertyOrdering" to strings(gemini.optJSONArray("propertyOrdering")),
            "openai.required" to strings(openai.optJSONArray("required")),
        )
    }

    /**
     * The invariant: NO advertised tool, on ANY gate combination, on ANY of the
     * three provider renderings, offers a name the model could use to claim to be
     * another session.
     *
     * One test rather than one per tool, and one failure message listing every
     * offender rather than failing on the first: the question being asked is about
     * the SURFACE, so the answer has to be the whole surface.
     */
    @Test
    fun `no advertised tool surfaces an identity parameter`() {
        val surface = advertisedSurface()
        val offenders = mutableListOf<String>()

        for ((label, definition) in surface) {
            if (definition.name == EXEMPT_TOOL) continue
            val byProvider = providerVisibleParams(definition)
            val byDeclaration = providerDeclaredNames(definition)
            for (forbidden in FORBIDDEN_IDENTITY_NAMES) {
                byProvider.forEach { (provider, names) ->
                    if (forbidden in names) {
                        offenders += "$label renders parameter '$forbidden' to $provider"
                    }
                }
                byDeclaration.forEach { (where, names) ->
                    if (forbidden in names) {
                        offenders += "$label declares '$forbidden' in $where"
                    }
                }
            }
        }

        assertTrue(
            "the advertised tool surface must never offer a caller-supplied identity: " +
                offenders.joinToString("; "),
            offenders.isEmpty(),
        )

        // The sweep must have actually swept something. An empty or truncated
        // surface would make the assertion above vacuously true — the classic
        // "green because nothing ran" failure.
        assertTrue(
            "the sweep visited ${surface.size} definitions; it must visit the real surface",
            surface.size >= 20,
        )
    }

    /**
     * Absence of a tool must be a failure too, not a silent gap.
     *
     * `makeAgentTools` is gated; `BOUND_IDENTITY_TOOLS` names the tools whose actor
     * is the bound session. If one is ever dropped from the surface (renamed, or
     * lost behind a gate), the sweep above simply stops visiting it and stays
     * green — this asserts they are all reachable, on the combination that is
     * supposed to expose them.
     */
    @Test
    fun `every bound-identity tool is reachable so the sweep cannot silently skip it`() {
        val widest = AgentTools.makeAgentTools(
            supportsImageInput = true,
            visionGroupConfigured = true,
            memoryEnabled = true,
            goalActive = true,
            restartAvailable = true,
        ).map { it.name }.toSet()
        val child = AgentTools.makeChildAgentTools().map { it.name }.toSet()

        val reachable = widest + child
        val missing = BOUND_IDENTITY_TOOLS - reachable

        assertTrue(
            "these tools carry a bound identity but are not advertised, so the invariant " +
                "above never examines them: $missing",
            missing.isEmpty(),
        )
    }

    /**
     * The other direction: the names that are legitimately caller-supplied must
     * still be there.
     *
     * A rule that only forbids things ratchets: the cheapest way to make the
     * invariant above pass forever is to delete parameters the tools need. These
     * fields name the TARGET of an operation, not the actor, and they are the only
     * way a model can say which descendant it means. Guarding them keeps the
     * invariant honest about what it is protecting (identity) versus what it must
     * not touch (targeting).
     */
    @Test
    fun `target and child fields survive, because a target is not an actor`() {
        val definitions = advertisedSurface().associate { it.second.name to it.second }

        TARGET_FIELDS_THAT_MUST_SURVIVE.forEach { (tool, field) ->
            val definition = definitions[tool]
            assertTrue(
                "'$tool' is not advertised, so its '$field' parameter cannot be checked",
                definition != null,
            )
            val visible = providerVisibleParams(definition!!)
                .mapValues { (_, names) -> field in names }
            assertTrue(
                "'$tool' must keep advertising '$field' on every provider — the model has no " +
                    "other way to name the target, and the identity rule must not ratchet into " +
                    "removing it. Rendered: $visible",
                visible.values.all { it },
            )
        }
    }

    // ─── the exemption, kept honest ──────────────────────────────────────

    /**
     * The exemption must stay real and stay narrow.
     *
     * Both directions are asserted, because either one silently degrades into a
     * hole:
     *
     *  * if `browser_devtools` stops declaring `session_id`, the skip in the sweep
     *    above is dead code — and dead skips are how a rule quietly stops applying
     *    to the thing it was written for;
     *  * if the tool disappears, the skip covers nothing and a NEW tool named
     *    `browser_devtools` (or a renamed one) could inherit an exemption nobody
     *    re-argued.
     */
    @Test
    fun `the session-id exemption is still real and still needed`() {
        val exempt = advertisedSurface()
            .map { it.second }
            .firstOrNull { it.name == EXEMPT_TOOL }

        assertTrue(
            "'$EXEMPT_TOOL' is exempted from the identity invariant, so it must exist; " +
                "if it was removed or renamed, remove or rename the exemption with it",
            exempt != null,
        )
        assertTrue(
            "'$EXEMPT_TOOL' is exempted only because it CHECKS the session id it is given " +
                "(scope triple, refused on mismatch) — if it no longer declares session_id, " +
                "the exemption is stale and must be deleted",
            providerVisibleParams(exempt!!).values.all { "session_id" in it },
        )
    }

    /**
     * The exemption is exactly one tool wide.
     *
     * Counted across the WHOLE surface rather than read off
     * `FORBIDDEN_IDENTITY_NAMES`, so adding a second session-id-taking tool cannot
     * pass by editing the constant: it shows up here as a name that is not the
     * exempted one.
     */
    @Test
    fun `only the exempted tool may take a session id at all`() {
        val takers = advertisedSurface()
            .map { it.first to it.second }
            .filter { (_, definition) -> providerVisibleParams(definition).values.any { "session_id" in it } }
            .map { it.second.name }
            .toSet()

        assertEquals(
            "exactly one tool may take a session id, and it must be the exempted one — a second " +
                "one needs its own argument, not a widened exemption",
            setOf(EXEMPT_TOOL),
            takers,
        )
    }
}
