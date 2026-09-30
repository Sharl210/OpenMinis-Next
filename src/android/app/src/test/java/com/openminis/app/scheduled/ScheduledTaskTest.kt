package com.openminis.app.scheduled

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledTaskTest {
    private fun task(
        mode: ScheduledTargetMode = ScheduledTargetMode.NewSession,
        repeat: ScheduledRepeatMode = ScheduledRepeatMode.DAILY,
        enabled: Boolean = true,
        days: Set<Int> = emptySet(),
    ) = ScheduledTask(
        label = "test",
        timeOfDayHour = 9,
        timeOfDayMinute = 30,
        repeatMode = repeat,
        customDays = days,
        prompt = "hello",
        targetMode = mode,
        enabled = enabled,
        createdAt = 0L,
    )

    @Test
    fun `target modes round trip and malformed values degrade to new session`() {
        assertEquals(ScheduledTargetMode.NewSession, ScheduledTargetMode.decode(ScheduledTargetMode.NewSession.encode()))
        assertEquals(ScheduledTargetMode.AppendToSession("session"), ScheduledTargetMode.decode(ScheduledTargetMode.AppendToSession("session").encode()))
        assertEquals(ScheduledTargetMode.RerunMessage("session", "message"), ScheduledTargetMode.decode(ScheduledTargetMode.RerunMessage("session", "message").encode()))
        assertEquals(ScheduledTargetMode.NewSession, ScheduledTargetMode.decode("unknown"))
        assertEquals(ScheduledTargetMode.NewSession, ScheduledTargetMode.decode("RERUN:missing"))
    }

    @Test
    fun `disabled and empty custom schedules have no trigger`() {
        assertNull(task(enabled = false).nextTriggerMs(now = 1_700_000_000_000L))
        assertNull(task(repeat = ScheduledRepeatMode.CUSTOM).nextTriggerMs(now = 1_700_000_000_000L))
    }

    @Test
    fun `task json preserves target mode and run history`() {
        val value = task(ScheduledTargetMode.RerunMessage("s", "m")).copy(
            runHistory = listOf(ScheduledRun(100L, "s", "done", true)),
        )
        val restored = ScheduledTask.fromJson(value.toJson())
        assertEquals(value.targetMode, restored.targetMode)
        assertEquals(value.runHistory, restored.runHistory)
        assertTrue(restored.enabled)
    }
}
