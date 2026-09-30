package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-android-scrollbtn-turn-walk] Pins the up-button's turn-walk selection rule,
 * ported from iOS `scrollToPreviousUserTurn` (dcdec3c5).
 *
 * The composable itself needs a LazyListState + live layout, so this exercises
 * the pure decision the handler makes: given the message the viewport is
 * currently showing and the id we last jumped to, which user turn do we target?
 * That rule is the whole user-visible behaviour — "first tap goes to this turn,
 * repeated taps walk further back, and the first turn is a floor".
 *
 * The rule itself is the PRODUCTION top-level `resolvePreviousUserTurnTarget`
 * (and `chatRowKeyMessageId`), called from `ChatScreen`. This file deliberately
 * holds no copy of it: a copy keeps passing after the real rule breaks.
 *
 * Two device-discovered bugs are pinned here as regression cases:
 *  - `chatRowKeyMessageId` parsing: row keys look like "mdblock:<id>:text_<id>_0:1",
 *    so taking everything after the FIRST colon yields "<id>:text_..." which
 *    never matches a message id. Every tap then fell back to the oldest loaded
 *    turn and the walk never advanced.
 *  - the windowed-transcript fallback: `messages` is a TAIL WINDOW, so the top
 *    row can belong to an unloaded message. Falling back to index 0 anchored on
 *    the oldest loaded turn; the correct fallback is the newest message.
 */
class UpButtonTurnWalkTest {

    private data class Msg(val id: String, val role: String)

    /**
     * Feeds the PRODUCTION rule instead of a copy of it:
     * [resolvePreviousUserTurnTarget] is the same top-level function that
     * `ChatScreen`'s `scrollToPreviousUserTurn` calls, and
     * [chatRowKeyMessageId] is the same row-key parse it uses. This helper only
     * adapts the fixture onto those parameters.
     *
     * The fixture models "the human's own turn" as `role == "user"`; the
     * production predicate (`ChatMessage.isManualHumanUser`) additionally checks
     * `provenance`, which this fixture has no field for.
     */
    private fun pickTarget(
        messages: List<Msg>,
        topRowKey: String?,
        lastJumpedUserId: String?,
        fullyVisibleUserIds: Set<String> = emptySet(),
    ): String? = resolvePreviousUserTurnTarget(
        messages = messages,
        idOf = { it.id },
        isUserTurn = { it.role == "user" },
        topMessageId = chatRowKeyMessageId(topRowKey),
        lastJumpedUserId = lastJumpedUserId,
        fullyVisibleUserIds = fullyVisibleUserIds,
    )

    /** u1 a1 u2 a2 u3 a3 — three turns, assistant reply after each. */
    private val convo = listOf(
        Msg("u1", "user"), Msg("a1", "assistant"),
        Msg("u2", "user"), Msg("a2", "assistant"),
        Msg("u3", "user"), Msg("a3", "assistant"),
    )

    @Test
    fun `first tap anchors to the current turn rather than stepping back`() {
        // Viewport is showing a3, i.e. inside turn 3. The first tap must land on
        // u3 — NOT u2 — so the user sees the start of the turn they're reading.
        val target = pickTarget(convo, topRowKey = "mdblock:a3", lastJumpedUserId = null)
        assertEquals("u3", target)
    }

    @Test
    fun `a repeated tap walks one turn further back`() {
        // Same viewport, but we already jumped to u3 — now step to u2.
        val target = pickTarget(convo, topRowKey = "user:u3", lastJumpedUserId = "u3")
        assertEquals("u2", target)
    }

    @Test
    fun `walking continues turn by turn`() {
        assertEquals("u1", pickTarget(convo, topRowKey = "user:u2", lastJumpedUserId = "u2"))
    }

    @Test
    fun `the first turn is a floor - no overscroll past it`() {
        // Already anchored on the oldest turn: stay there instead of walking off
        // the front of the conversation.
        val target = pickTarget(convo, topRowKey = "user:u1", lastJumpedUserId = "u1")
        assertEquals("u1", target)
    }

    /** Five turns — needed for the clamp case, where a 3-turn fixture is too
     *  small for the fixed and unfixed rules to give different answers. */
    private val longConvo = (1..5).flatMap {
        listOf(Msg("u$it", "user"), Msg("a$it", "assistant"))
    }

    @Test
    fun `at the end of content the walk still advances instead of oscillating`() {
        // Device-observed stall (taps 6/7 of the 7-turn session): near the oldest
        // rows a LazyColumn CLAMPS, so the target never reaches the viewport top
        // and `currentAnchor` keeps recomputing to the OLDEST visible turn (u1)
        // even though we last jumped to u4. The base rule then sees
        // anchor(u1) != lastJumped(u4), re-targets u1, and the walk sticks there
        // forever. Continuing from lastJumped instead advances one turn per tap.
        val target = pickTarget(
            longConvo,
            topRowKey = "user:u1",
            lastJumpedUserId = "u4",
        )
        assertEquals("u3", target)
    }

    @Test
    fun `at the end of content a fresh tap does not skip ahead`() {
        // A FIRST tap must never step back: with no
        // lastJumped, the target is still the turn the user is reading.
        val target = pickTarget(
            convo,
            topRowKey = "user:u2",
            lastJumpedUserId = null,
        )
        assertEquals("u2", target)
    }

    @Test
    fun `a stale lastJumped that is not the current anchor re-anchors`() {
        // This is the post-drag state: lastJumpedUserId is reset to null, so the
        // next tap re-anchors to the current turn rather than continuing.
        val target = pickTarget(convo, topRowKey = "mdblock:a2", lastJumpedUserId = null)
        assertEquals("u2", target)
    }

    @Test
    fun `a turn already fully on screen is not a scroll target`() {
        // [T-android-fab-up-skip-visible] The anchor IS the turn already rendered
        // in the viewport, so targeting it reads as a dead tap: the transcript
        // barely moves and the user has to tap twice to go back one turn. The
        // walk must therefore continue to the first turn that is NOT fully
        // visible.
        val target = pickTarget(
            convo,
            topRowKey = "user:u3",
            lastJumpedUserId = null,
            fullyVisibleUserIds = setOf("u3"),
        )
        assertEquals("u2", target)
    }

    @Test
    fun `when every turn is already on screen the walk stops at the oldest`() {
        // scrollToItem clamps, so stopping at the first turn stays a harmless
        // no-op instead of an overscroll.
        val target = pickTarget(
            convo,
            topRowKey = "user:u3",
            lastJumpedUserId = null,
            fullyVisibleUserIds = setOf("u1", "u2", "u3"),
        )
        assertEquals("u1", target)
    }

    @Test
    fun `row keys with an id suffix still resolve to the message id`() {
        // Device-observed key shape. substringAfter(':') would return
        // "a3:text_a3_0:1" and match nothing.
        assertEquals("a3", chatRowKeyMessageId("mdblock:a3:text_a3_0:1"))
        assertEquals("u3", chatRowKeyMessageId("user:u3"))
        assertEquals("a3", chatRowKeyMessageId("thinking:a3"))
        assertNull(chatRowKeyMessageId("__resume_banner__"))
    }

    @Test
    fun `an unloaded top row anchors to the oldest loaded turn`() {
        // `messages` is a TAIL WINDOW and the viewport top is the OLDEST content
        // on screen, so a top row whose message isn't loaded means the user is
        // at/above the start of the window — anchor on the oldest loaded turn.
        val target = pickTarget(convo, topRowKey = "mdblock:NOT_LOADED", lastJumpedUserId = null)
        assertEquals("u1", target)
    }

    /**
     * The seek algorithm that was REMOVED from `ChatScreen` (stride upward from
     * the highest visible row), kept only as a record of its failure mode. It is
     * a historical algorithm and NOT production code, so no assertion on it can
     * ever guard a change in the app — it exists to document why the stride was
     * dropped, nothing more.
     */
    private fun legacyStrideSeek(
        rows: List<String>,
        viewport: IntRange,
        targetKey: String,
    ): Int? {
        var hi = viewport.last
        val stride = viewport.count().coerceAtLeast(1)
        var guard = 0
        while (guard++ < 60) {
            val next = (hi + stride).coerceAtMost(rows.lastIndex)
            if (next <= hi) return null
            val window = next..(next + stride - 1).coerceAtMost(rows.lastIndex)
            val found = rows.indexOf(targetKey)
            if (found in window) return found
            hi = window.last
        }
        return null
    }

    /**
     * Drives the PRODUCTION seek rule [nextSeekProbeIndex] — the same top-level
     * function `ChatScreen`'s seek loop calls — over a stub row list. Each probe
     * makes rows `probe until probe + span` visible, exactly like
     * `tracedScrollToItem("FAB-UP/seek", probe, 0)` followed by reading the
     * viewport and continuing past the highest row now on screen.
     *
     * The sweep starts at row 0 on purpose: the target can sit ABOVE or BELOW
     * the current window (how far the anchor's row is from the viewport depends
     * on how tall the intervening tool / thinking / shell-output blocks are), so
     * the direction cannot be assumed, and a stride steps straight over a target
     * that sits just outside the window — the reported dead-tap bug.
     */
    private fun fullSweepSeek(rows: List<String>, span: Int, targetKey: String): Int? {
        var probe = 0
        var guard = 0
        while (probe <= rows.lastIndex && guard++ < 200) {
            val hi = (probe + span - 1).coerceAtMost(rows.lastIndex)
            (probe..hi).firstOrNull { rows[it] == targetKey }?.let { return it }
            val next = nextSeekProbeIndex(probe = probe, highestVisibleIndex = hi)
            if (next <= probe) return null
            probe = next
        }
        return null
    }

    @Test
    fun `the seek finds a target that sits just below the viewport`() {
        // Device repro (读屏 session, user's real flow): viewport on rows 0..8,
        // target user bubble at row 9 — one row BELOW the window. The old
        // stride-upward seek jumped 17 -> 41, stepped straight over row 9, and
        // returned null, which surfaced as a completely dead button.
        val rows = List(47) { "row$it" }.toMutableList().also { it[9] = "user:u2" }
        val viewport = 0..8

        assertNull(
            "legacy stride seek must miss it (recorded bug, not production code)",
            legacyStrideSeek(rows, viewport, "user:u2"),
        )
        assertEquals(
            9,
            fullSweepSeek(rows, span = viewport.count(), targetKey = "user:u2"),
        )
    }

    @Test
    fun `the seek still finds a target far above the viewport`() {
        // The other direction must keep working: oldest turn at the very last
        // row, viewport near the newest end.
        val rows = List(47) { "row$it" }.toMutableList().also { it[46] = "user:u1" }
        assertEquals(
            46,
            fullSweepSeek(rows, span = 9, targetKey = "user:u1"),
        )
    }

    @Test
    fun `a conversation with no user turns yields no target`() {
        val noUsers = listOf(Msg("a1", "assistant"), Msg("a2", "assistant"))
        assertNull(pickTarget(noUsers, topRowKey = "mdblock:a2", lastJumpedUserId = null))
    }

    /**
     * [T-android-scrollbtn-turn-walk] A rendered row can carry a `#n` dedupe
     * suffix: `ChatFlatItems.dedupe` falls back to `"<id>#$n"` when a message
     * would otherwise produce a duplicate LazyColumn key. The id segment of the
     * row key must therefore drop that suffix — the two anchor parsers already
     * did, the up/down walk parsers did not.
     *
     * Without the drop, the top row resolved to NO message at all, so the walk
     * fell back to `index 0` and anchored on the OLDEST loaded turn instead of
     * the turn actually on screen — the same class of bug as the original
     * "every tap lands on the first turn" report.
     */
    @Test
    fun `deduped row keys resolve to the underlying message id`() {
        assertEquals("u2", chatRowKeyMessageId("user:u2#2"))
        assertEquals("u2", chatRowKeyMessageId("mdblock:u2#2:text_u2_0:1"))
        assertEquals("u2", chatRowKeyMessageId("user:u2"))
        // End to end through the selection rule: the first tap must still land
        // on the turn the viewport is showing, not on the oldest turn.
        assertEquals("u3", pickTarget(convo, topRowKey = "user:u3#2", lastJumpedUserId = null))
    }
}
