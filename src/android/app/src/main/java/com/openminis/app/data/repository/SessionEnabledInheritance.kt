package com.openminis.app.data.repository

import com.openminis.app.feature.runtime.RuntimeSessionNode

/**
 * Read-time inheritance for per-session skill / MCP enablement.
 *
 * A sub-agent session is derived from the conversation that spawned it, so its
 * switches must mirror its ancestors': an ancestor turning a skill or MCP server
 * off must make it unavailable to the whole tree below, immediately.
 *
 * Two properties that shape this design:
 *
 *  - **Top-down only.** Overrides live in `session_skill_overrides` /
 *    `mcp_session_overrides`, keyed by session id. A child writing its own row
 *    therefore can never move its parent's value — a lower layer's change only
 *    reaches itself and its own descendants. Nothing extra is needed to get
 *    that behaviour; it falls out of resolving from the reader's own session
 *    upward.
 *  - **Live, not snapshotted.** Resolution walks the parent chain on *every*
 *    read. Nothing is copied at spawn time, so an ancestor toggled after the
 *    child was created is visible to the child's very next read, and a child
 *    created later still sees the current ancestor state.
 *
 * Precedence: `override(session)` → `override(parent)` → … → `override(root)` →
 * global default → `false`.
 *
 * Deliberately pure: no Android, no SQLite, no runtime tree. The caller injects
 * the three lookups, which keeps the whole chain testable on the plain JVM and —
 * because [SkillRepository] and [MCPRepository] share this one function — keeps
 * the two chains from drifting into subtly different rules.
 */

/**
 * Safety ceiling on how many ancestors one resolution may visit.
 *
 * Cycling is already impossible to loop on (each visited id is recorded once,
 * see [resolveInheritedEnabled]), but a corrupted or unexpectedly deep tree must
 * not let a settings read turn into an unbounded walk. The value sits well above
 * any depth the runtime tree itself allows.
 */
const val MAX_INHERITED_SESSION_CHAIN_DEPTH: Int = 256

/**
 * Resolve whether entry described by the lookup lambdas is enabled for
 * [sessionId], walking upward from the session itself.
 *
 * @param sessionId the session the value is read *for* (a sub-agent session
 *   inherits from its ancestors through this walk).
 * @param parentOf parent session id, or `null` at the top of the chain (also
 *   `null` for a session the runtime tree does not know).
 * @param overrideOf this session's own override, or `null` when it has none —
 *   `null` is what makes the walk continue to the next ancestor, so the three
 *   states "explicitly on", "explicitly off" and "not set" stay distinguishable.
 * @param globalDefault the app-wide default, or `null` when the entry is gone.
 * @return the first value found walking upward, else the global default, else
 *   `false`.
 */
fun resolveInheritedEnabled(
    sessionId: String,
    parentOf: (String) -> String?,
    overrideOf: (String) -> Boolean?,
    globalDefault: () -> Boolean?,
): Boolean {
    var current: String? = sessionId
    val visited = HashSet<String>()
    var walked = 0
    while (current != null && walked < MAX_INHERITED_SESSION_CHAIN_DEPTH && visited.add(current)) {
        overrideOf(current)?.let { return it }
        current = parentOf(current)
        walked++
    }
    return globalDefault() ?: false
}

/**
 * What [sessionId] would resolve to if it had no override row of its own — its
 * ancestors' nearest override, else the global default. `null` means the entry
 * itself is unknown to the app.
 *
 * This is the value [resolveInheritedEnabled] falls back to once the session's
 * own row is skipped, so a writer can ask "would this session already have the
 * value the user just chose?" and leave no row if so. Keeping that decision
 * here — rather than inlined per repository — is what stops the skill and MCP
 * write paths from drifting apart.
 */
fun resolveInheritedValue(
    sessionId: String,
    parentOf: (String) -> String?,
    overrideOf: (String) -> Boolean?,
    globalDefault: () -> Boolean?,
): Boolean? {
    val global = globalDefault() ?: return null
    val parentId = parentOf(sessionId) ?: return global
    return resolveInheritedEnabled(parentId, parentOf, overrideOf, globalDefault)
}

/**
 * Whether a session's toggle needs a row of its own.
 *
 * Writing a row that merely repeats the inherited value looks harmless but is
 * not: it pins the session at that value, so the ancestor stops reaching it —
 * exactly the "an ancestor's change must reach the bottom of the tree"
 * guarantee. Returning `false` here leaves the row unwritten so the session
 * keeps following the level that actually supplies the value; the row is only
 * written when the session genuinely diverges (or when the entry is unknown, in
 * which case the value is the session's own business).
 */
fun shouldPinSessionOverride(enabled: Boolean, inheritedValue: Boolean?): Boolean =
    inheritedValue == null || enabled != inheritedValue

/**
 * Fold a runtime-tree node list into a `sessionId -> parentSessionId` index, the
 * map form of [resolveInheritedEnabled]'s `parentOf` lookup.
 *
 * Two wrinkles of the runtime tree are handled here rather than at every call
 * site:
 *
 *  - A conversation that runs again gets a *new* root node named
 *    `<sessionId>#run-<n>` (`RuntimeSessionTree.createRoot`), while chat
 *    sessions, overrides and children are all keyed by the plain session id.
 *    Every run node is folded back onto its conversation, so a child of a second
 *    run still walks into the conversation instead of stopping one hop short and
 *    losing the root's overrides.
 *  - A link pointing at a node absent from the snapshot is dropped. The session
 *    then reads as "no parent" and the walk ends at the global default, which is
 *    the required safe fallback.
 */
internal fun sessionParentIndex(nodes: List<RuntimeSessionNode>): Map<String, String> {
    val known = HashSet<String>(nodes.size * 2)
    for (node in nodes) {
        known.add(node.id)
        known.add(canonicalRuntimeSessionId(node.id))
    }
    val index = HashMap<String, String>(nodes.size)
    for (node in nodes) {
        val parentId = node.parentId ?: continue
        if (parentId !in known) continue
        index.putIfAbsent(canonicalRuntimeSessionId(node.id), canonicalRuntimeSessionId(parentId))
    }
    return index
}

/**
 * `<sessionId>#run-<n>` → `<sessionId>`; anything else is returned unchanged.
 * The suffix is only stripped when it really is the runtime tree's numeric run
 * marker, so a session id that merely contains the text is left alone.
 */
internal fun canonicalRuntimeSessionId(runtimeNodeId: String): String {
    val marker = "#run-"
    val markerIndex = runtimeNodeId.lastIndexOf(marker)
    if (markerIndex <= 0) return runtimeNodeId
    val generation = runtimeNodeId.substring(markerIndex + marker.length)
    if (generation.isEmpty() || !generation.all { it in '0'..'9' }) return runtimeNodeId
    return runtimeNodeId.substring(0, markerIndex)
}
