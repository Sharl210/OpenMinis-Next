package com.openminis.app.ui.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ToolCallBatchSchedulerTest {
    @Test
    fun `independent calls execute concurrently and results preserve input order`() = runBlocking {
        val started = AtomicInteger()
        val bothStarted = CompletableDeferred<Unit>()
        val items = listOf(ToolBatchItem("a", value = "first"), ToolBatchItem("b", value = "second"))
        val outcomes = withTimeout(2_000) {
            ToolCallBatchScheduler.execute(items) { value ->
                if (started.incrementAndGet() == 2) bothStarted.complete(Unit)
                bothStarted.await()
                ToolBatchExecution(success = true, value = value.uppercase())
            }
        }
        assertEquals(listOf("a", "b"), outcomes.map { it.item.id })
        assertEquals(listOf("FIRST", "SECOND"), outcomes.map { it.value })
    }

    @Test
    fun `dependency waits for prerequisite and does not serialize unrelated calls`() = runBlocking {
        val completed = mutableSetOf<String>()
        // Input order deliberately puts the DEPENDENT first. Listing the calls the
        // other way round — "a" before "b" — is what makes this test unable to see
        // the gate at all: a scheduler that merely walks its input in order, with no
        // dependency logic whatsoever, would still have run "a" by the time "b"
        // starts (measured: removing the gate entirely kept the previous form of
        // this test green). With "b" first, "a" can only be ahead of it because a
        // dependency gate put it there.
        val items = listOf(
            ToolBatchItem("b", dependsOn = setOf("a"), value = "b"),
            ToolBatchItem("a", value = "a"),
            ToolBatchItem("c", value = "c"),
        )
        var dependentRanBeforePrerequisite = false
        val outcomes = ToolCallBatchScheduler.execute(items) { value ->
            // Not load-bearing for the assertion below (input order alone is
            // enough): it makes the violation take real time, so a gate-less
            // scheduler that starts "b" while "a" is still running is reported
            // with a diagnosable snapshot rather than by luck of ordering.
            if (value == "a") delay(120)
            if (value == "b" && "a" !in completed) dependentRanBeforePrerequisite = true
            completed += value
            ToolBatchExecution(success = true, value = value)
        }
        // Asserted OUTSIDE the operation. An assertion thrown inside it is caught by
        // the scheduler's own `catch (error: Throwable)` and reported as a failed
        // tool call, which hides which assertion failed.
        assertFalse(
            "the dependent call must not start before its prerequisite finished: completed=$completed",
            dependentRanBeforePrerequisite,
        )
        assertEquals(listOf("b", "a", "c"), outcomes.map { it.item.id })
        assertEquals(setOf("a", "b", "c"), completed)
        assertTrue(outcomes.all { it.status == ToolBatchStatus.SUCCESS })
    }

    @Test
    fun `unknown dependency and cycle fail locally while unrelated work succeeds`() = runBlocking {
        val executed = mutableListOf<String>()
        val items = listOf(
            ToolBatchItem("unknown", dependsOn = setOf("missing"), value = "unknown"),
            ToolBatchItem("cycle-a", dependsOn = setOf("cycle-b"), value = "cycle-a"),
            ToolBatchItem("cycle-b", dependsOn = setOf("cycle-a"), value = "cycle-b"),
            ToolBatchItem("cycle-child", dependsOn = setOf("cycle-a"), value = "cycle-child"),
            ToolBatchItem("independent", value = "independent"),
        )
        val outcomes = ToolCallBatchScheduler.execute(items) { value ->
            executed += value
            ToolBatchExecution(success = true, value = value)
        }
        assertEquals(listOf("independent"), executed)
        assertEquals(ToolBatchStatus.FAILED, outcomes[0].status)
        assertEquals(ToolBatchStatus.FAILED, outcomes[1].status)
        assertEquals(ToolBatchStatus.FAILED, outcomes[2].status)
        assertEquals(ToolBatchStatus.BLOCKED, outcomes[3].status)
        assertEquals(ToolBatchStatus.SUCCESS, outcomes[4].status)
    }

    @Test
    fun `failure blocks only dependent calls`() = runBlocking {
        val executed = mutableListOf<String>()
        val outcomes = ToolCallBatchScheduler.execute(listOf(
            ToolBatchItem("bad", value = "bad"),
            ToolBatchItem("dependent", dependsOn = setOf("bad"), value = "dependent"),
            ToolBatchItem("free", value = "free"),
        )) { value ->
            executed += value
            ToolBatchExecution(success = value != "bad", value = value, error = "failed")
        }
        assertEquals(listOf("bad", "free"), executed)
        assertEquals(ToolBatchStatus.FAILED, outcomes[0].status)
        assertEquals(ToolBatchStatus.BLOCKED, outcomes[1].status)
        assertEquals(ToolBatchStatus.SUCCESS, outcomes[2].status)
    }

    @Test
    fun `cancelled call blocks only its dependents while unrelated work continues`() = runBlocking {
        val executed = mutableListOf<String>()
        val outcomes = ToolCallBatchScheduler.execute(listOf(
            ToolBatchItem("cancelled", value = "cancelled"),
            ToolBatchItem("dependent", dependsOn = setOf("cancelled"), value = "dependent"),
            ToolBatchItem("free", value = "free"),
        )) { value ->
            executed += value
            if (value == "cancelled") throw CancellationException("cancelled")
            ToolBatchExecution(success = true, value = value)
        }
        assertEquals(listOf("cancelled", "free"), executed)
        assertEquals(ToolBatchStatus.CANCELLED, outcomes[0].status)
        assertEquals(ToolBatchStatus.BLOCKED, outcomes[1].status)
        assertEquals(ToolBatchStatus.SUCCESS, outcomes[2].status)
    }

    @Test
    fun `dependency parser accepts one id or id list and rejects malformed values`() {
        assertEquals(ToolDependencyParseResult.Valid(setOf("call-a")), parseToolCallDependencies(JSONObject("""{"depends_on":"call-a"}""")))
        assertEquals(ToolDependencyParseResult.Valid(setOf("call-a", "call-b")), parseToolCallDependencies(JSONObject("""{"depends_on":["call-a","call-b"]}""")))
        assertTrue(parseToolCallDependencies(JSONObject("""{"depends_on":true}""")) is ToolDependencyParseResult.Invalid)
    }
}
