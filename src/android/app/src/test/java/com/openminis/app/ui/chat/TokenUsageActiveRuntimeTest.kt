package com.openminis.app.ui.chat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-token-usage-active-runtime] Pins the Token Usage sheet's Runtime row.
 *
 * The requirement is explicit that this number is how long the conversation
 * actually RAN — "实际在跑的时间，不是这个对话创建了多久的时间" — so every test
 * here is written to be WRONG under the tempting `now - createdAt`
 * implementation. The counter-proof (documented with the change) is: replace
 * [activeDurationMillis] with `System.currentTimeMillis() - rows.first()
 * .createdAtMillis` and this class goes red on the first two tests, because the
 * fixtures are dated far in the past while their real work is tens of seconds.
 *
 * These are pure-JVM assertions on the aggregation/formatting rule; that the
 * sheet renders the result is Compose plumbing the rule does not depend on.
 */
class TokenUsageActiveRuntimeTest {

    /** 2024-07-15T00:00:00Z. Any fixed past instant works — the point is that the
     *  wall clock during the test run is far away from the fixtures, so a
     *  wall-clock implementation cannot accidentally look right. */
    private val t0 = 1_721_001_600_000L

    private val second = 1_000L
    private val minute = 60 * second
    private val hour = 60 * minute
    private val day = 24 * hour

    private val strings = RuntimeFormatStrings(
        hoursMinutes = "%1\$d 小时 %2\$d 分",
        minutesSeconds = "%1\$d 分 %2\$d 秒",
        minutes = "%1\$d 分",
        lessThanMinute = "不到 1 分",
    )

    /** Rows are ordered as the DB returns them; the role decides what each one marks. */
    private fun userRow(at: Long) = ActiveTimeRow(createdAtMillis = at, role = "user")
    private fun turnEndRow(at: Long) = ActiveTimeRow(createdAtMillis = at, role = "assistant")

    /** What the rejected "created until now" implementation would have reported. */
    private fun wallClockSince(sessionCreatedAt: Long): Long =
        System.currentTimeMillis() - sessionCreatedAt

    @Test
    fun `active runtime counts only the spans the app was running`() {
        // Turn 1: 30 s of real work. Then the user walks away for three days.
        // Turn 2: 20 s of real work. Active runtime is 50 s, not three days.
        val rows = listOf(
            userRow(t0),
            turnEndRow(t0 + 30 * second),
            userRow(t0 + 3 * day),
            turnEndRow(t0 + 3 * day + 20 * second),
        )

        assertEquals(50 * second, activeDurationMillis(rows))
    }

    @Test
    fun `active runtime is not the wall clock gap since creation`() {
        val rows = listOf(
            userRow(t0),
            turnEndRow(t0 + 30 * second),
            userRow(t0 + 3 * day),
            turnEndRow(t0 + 3 * day + 20 * second),
        )
        val wallClock = wallClockSince(t0)

        // Anti-counter-proof: this is the number `now - createdAt` would return,
        // three days later, and we assert the real value is not that.
        assertTrue("fixture must sit far in the past, wall clock was $wallClock", wallClock > 30 * day)
        assertEquals(50 * second, activeDurationMillis(rows))
        assertNotEquals(wallClock, activeDurationMillis(rows))
        assertTrue(activeDurationMillis(rows) < wallClock / 1000)
    }

    @Test
    fun `a session with no finished turn reports zero even when it is old`() {
        // Only a human message: nothing has finished running yet, so the answer
        // is zero — whereas `now - createdAt` would claim days of activity.
        assertEquals(0L, activeDurationMillis(listOf(userRow(t0))))
        assertEquals(0L, activeDurationMillis(listOf(userRow(t0), userRow(t0 + 2 * hour))))
        assertTrue(wallClockSince(t0) > 30 * day)
    }

    @Test
    fun `a single finished turn is bounded by the row that started it`() {
        // The span starts at the human message, so time the user spent typing
        // before sending is not part of the runtime.
        val rows = listOf(userRow(t0), turnEndRow(t0 + 90 * second))
        assertEquals(90 * second, activeDurationMillis(rows))
    }

    @Test
    fun `overlapping spans merge instead of being counted twice`() {
        // A backwards clock adjustment made the second span start before the
        // first one ended. Union = 90 s; a naive sum would report 120 s.
        val rows = listOf(
            userRow(t0),
            turnEndRow(t0 + 60 * second),
            userRow(t0 + 30 * second),
            turnEndRow(t0 + 90 * second),
        )

        assertEquals(90 * second, activeDurationMillis(rows))
    }

    @Test
    fun `runtime is rendered in human units`() {
        assertEquals("不到 1 分", formatActiveDuration(0L, strings))
        assertEquals("不到 1 分", formatActiveDuration(59 * second + 999, strings))
        assertEquals("1 分", formatActiveDuration(60 * second, strings))
        assertEquals("43 分 12 秒", formatActiveDuration(43 * minute + 12 * second, strings))
        assertEquals("2 小时 13 分", formatActiveDuration(2 * hour + 13 * minute, strings))
        assertEquals("26 小时 3 分", formatActiveDuration(26 * hour + 3 * minute, strings))
        // Defensive: a negative or unknown duration must never render as a
        // nonsense number, it degrades to the smallest honest label.
        assertEquals("不到 1 分", formatActiveDuration(-1L, strings))
    }

    @Test
    fun `session total is the input row plus the output row, cache included`() {
        val input = 1_000L
        val cacheRead = 500L
        val cacheWrite = 100L
        val output = 300L
        val inputWithCache = input + cacheRead + cacheWrite

        // Exactly the two rows the panel prints, added up — a total that cannot
        // drift from the rows a user can verify by hand.
        assertEquals(1_900L, sessionTotalTokens(inputWithCache, output))
    }

    @Test
    fun `token units are the agent topology units`() {
        // The requirement asks for the topology's units. These assertions call
        // AgentTopology's formatter directly, so re-introducing a second local
        // implementation cannot silently satisfy them.
        assertEquals("0", formatAgentTokens(0L))
        assertEquals("999", formatAgentTokens(999L))
        assertEquals("1.5K", formatAgentTokens(1_500L))
        assertEquals("200K", formatAgentTokens(200_000L))
        assertEquals("238.4K", formatAgentTokens(238_400L))
        assertEquals("1M", formatAgentTokens(999_950L))
    }

    @Test
    fun `the shared formatter rejects negative input, so the panel must coerce`() {
        // Contract of the reused formatter: it THROWS on a negative count, and
        // the sheet calls it from inside composition — an exception there takes
        // the whole sheet down. The panel therefore routes every count through a
        // coercing wrapper; this pins the reason it must exist.
        var threw = false
        try {
            formatAgentTokens(-1L)
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue("formatAgentTokens is expected to reject negatives", threw)
    }


    @Test
    fun `only an assistant row ends a turn`() {
        // [audit] This pins the single decision the feature rests on. It used to
        // be an inline `row.role == "assistant"` at the ChatViewModel call site,
        // and an adversarial audit showed that flipping THAT literal to "user"
        // (or to a constant) left every test green while making the panel report
        // the idle time BETWEEN turns — the exact opposite of the requirement.
        assertTrue(isTurnEndRow("assistant"))
        assertFalse(isTurnEndRow("user"))
        assertFalse(isTurnEndRow("system"))
        assertFalse(isTurnEndRow(""))
        assertEquals("assistant", TURN_END_ROLE)
    }

    @Test
    fun `a tool-result row is a user row and must not end a turn`() {
        // Tool results are persisted with role "user" (persistToolResultMessage).
        // If someone ever "tidies" that to an assistant role, the span between a
        // tool call and its own result would be reported as a finished turn.
        val rows = listOf(
            ActiveTimeRow(t0, "user"),
            ActiveTimeRow(t0 + 30 * second, "assistant"),
            ActiveTimeRow(t0 + 3 * day, "user"),      // tool result of the next turn
            ActiveTimeRow(t0 + 3 * day + 20 * second, "assistant"),
        )

        assertEquals(50 * second, activeDurationMillis(rows))
    }

    @Test
    fun `an unknown runtime is not the same as a zero runtime`() {
        // The panel's SessionTokenStats.activeMillis is nullable so safe mode —
        // where the message table cannot be read at all — reports "unknown"
        // instead of asserting "it never ran" next to perfectly readable token
        // totals. A zero-length duration is a DIFFERENT, legitimate answer.
        val unknown: Long? = null
        val measuredZero: Long? = activeDurationMillis(listOf(userRow(t0), userRow(t0 + hour)))

        assertTrue("a measured zero is a value", measuredZero == 0L)
        assertTrue("an unknown duration is not a measured zero", unknown != measuredZero)
    }

    @Test
    fun `english and chinese both declare every new runtime string with the same format args`() {
        val requiredKeys = listOf(
            "token_usage_total_tokens",
            "token_usage_section_runtime",
            "token_usage_runtime_active",
            "token_usage_runtime_hours_minutes",
            "token_usage_runtime_minutes_seconds",
            "token_usage_runtime_minutes",
            "token_usage_runtime_under_minute",
            "token_usage_runtime_unavailable",
        )
        val workingDir = File(System.getProperty("user.dir") ?: ".")
        val res = generateSequence(workingDir) { it.parentFile }
            .take(8)
            .map { File(it, "src/main/res") }
            .firstOrNull { File(it, "values/strings.xml").isFile }
        assertTrue("could not locate app src/main/res", res != null)

        val default = File(res!!, "values/strings.xml").readText()
        val chinese = File(res, "values-zh/strings.xml").readText()
        for (key in requiredKeys) {
            val pattern = Regex("<string name=\"$key\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            val defaultValue = pattern.find(default)?.groupValues?.get(1)
            val chineseValue = pattern.find(chinese)?.groupValues?.get(1)
            assertTrue("values is missing $key", defaultValue != null)
            assertTrue("values-zh is missing $key", chineseValue != null)
            val expectedArgs = when (key) {
                "token_usage_runtime_hours_minutes" -> 2
                "token_usage_runtime_minutes_seconds" -> 2
                "token_usage_runtime_minutes" -> 1
                else -> 0
            }
            assertEquals("$key format args in values", expectedArgs, formatArgCount(defaultValue!!))
            assertEquals("$key format args in values-zh", expectedArgs, formatArgCount(chineseValue!!))
        }
    }

    /** Number of distinct `%N$s` / `%N$d` placeholders, or -1 when unnumbered args are used. */
    private fun formatArgCount(value: String): Int {
        if (Regex("%[^0-9$%]").containsMatchIn(value)) return -1
        return Regex("%\\d+\\$[a-zA-Z]").findAll(value).map { it.value }.toSet().size
    }

    // ---- [T-android-token-runtime-process-clamp] kill-then-resume ----------------

    @Test
    fun `downtime after the process was killed is not counted as work`() {
        // The defect this pins: nothing is persisted at the moment of death, so
        // the first assistant row written after the app comes back is STILL
        // adjacent to the row from before the shutdown. The naive span is the
        // whole overnight gap — measured as "23 小时 0 分" on a turn that really
        // ran ~10 seconds.
        val t0 = 1_700_000_000_000L
        val overnight = 23 * hour
        val rows = listOf(
            userRow(t0),                                     // day 1: user sends
            ActiveTimeRow(t0 + 5 * second, "user"),          // tool result landed
            turnEndRow(t0 + 5 * second + overnight),         // day 2: reply, after resume
        )
        val reported = activeDurationMillis(
            rows,
            // The process that wrote the reply started 10 seconds before it.
            processStartMillis = t0 + 5 * second + overnight - 10 * second,
        )
        assertEquals(
            "only the 10s this process actually worked may be counted",
            10 * second,
            reported,
        )
    }

    @Test
    fun `without a process start the measurement degrades instead of inventing a clamp`() {
        // Unknown process start must NOT silently apply some default bound.
        val t0 = 1_700_000_000_000L
        val rows = listOf(userRow(t0), turnEndRow(t0 + 7 * second))
        assertEquals(7 * second, activeDurationMillis(rows))
        assertEquals(7 * second, activeDurationMillis(rows, processStartMillis = null))
    }

    @Test
    fun `a span entirely inside a past process is left alone`() {
        // The boundary that must NOT be clamped. This row predates the current
        // process, so it belongs to an earlier one; clamping it to the current
        // process start would erase real work rather than remove downtime.
        val t0 = 1_700_000_000_000L
        val rows = listOf(userRow(t0), turnEndRow(t0 + 4 * hour))
        val reported = activeDurationMillis(
            rows,
            processStartMillis = t0 + 10 * hour,   // this process started long after
        )
        assertEquals(
            "a genuine 4-hour past turn must survive a later process start",
            4 * hour,
            reported,
        )
    }

    @Test
    fun `the clamp never produces a zero or negative span`() {
        // If the end row is outside this process but the clamp were applied
        // blindly, start could exceed end. That must not surface as a negative
        // duration.
        val t0 = 1_700_000_000_000L
        val rows = listOf(userRow(t0), turnEndRow(t0 + 2 * second))
        val reported = activeDurationMillis(rows, processStartMillis = t0 + 9_999_999L)
        assertTrue("must not go negative: $reported", reported >= 0L)
        assertEquals(2 * second, reported)
    }

    @Test
    fun `a long single turn is not truncated by the clamp`() {
        // The reason this uses a process-start FACT instead of a maximum-span
        // threshold: a legitimately long turn must still be reported in full. The
        // process was already running when the turn began, so nothing is clamped.
        //
        // (First draft of this test passed a process start 3 seconds before the
        // end row while asserting the full 90 minutes — the two halves contradicted
        // each other, and the function was right to report 3 seconds.)
        val t0 = 1_700_000_000_000L
        val longTurn = 90 * minute
        val rows = listOf(userRow(t0), turnEndRow(t0 + longTurn))
        assertEquals(
            longTurn,
            activeDurationMillis(rows, processStartMillis = t0 - second),
        )
        // And the same turn, on a process that really did start 3s before the end,
        // is correctly reported as 3s: this is the clamp doing its job, not a
        // truncation policy.
        assertEquals(
            3 * second,
            activeDurationMillis(rows, processStartMillis = t0 + longTurn - 3 * second),
        )
    }

    @Test
    fun `clamping applies per span and leaves earlier same-process work intact`() {
        // Two turns in this process: the first is fully inside it, the second
        // crosses the kill boundary. Only the second may be shortened.
        val t0 = 1_700_000_000_000L
        val gap = 20 * hour
        val rows = listOf(
            userRow(t0),
            turnEndRow(t0 + 30 * second),                    // turn 1: 30s, real
            userRow(t0 + gap),                               // after resume
            turnEndRow(t0 + gap + 12 * second),              // turn 2: 12s, real
        )
        val processStart = t0 + gap
        assertEquals(
            30 * second + 12 * second,
            activeDurationMillis(rows, processStartMillis = processStart),
        )
    }
}
