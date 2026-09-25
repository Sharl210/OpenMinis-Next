package com.openminis.app.feature.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoundInjectionTest {
    @Test
    fun `start prompt runs on first call then periodic interval`() {
        val settings = RoundInjectionSettings(
            startPrompt = "start",
            periodicPrompt = "periodic",
            interval = 3,
        )
        var state = RoundInjectionState()
        val calls = mutableListOf<String?>()
        repeat(7) {
            val result = RoundInjectionPolicy.beforeModelCall(state, settings)
            state = result.first
            calls += result.second.prompt
        }
        assertEquals(listOf("start", null, null, "periodic", null, null, "periodic"), calls)
        assertEquals(7, state.totalModelCalls)
        assertEquals(7, state.lastInjectionCall)
    }

    @Test
    fun `without start prompt periodic prompt begins at interval`() {
        val settings = RoundInjectionSettings(
            injectOnStart = true,
            startPrompt = "",
            periodicPrompt = "periodic",
            interval = 3,
        )
        var state = RoundInjectionState()
        repeat(2) {
            val result = RoundInjectionPolicy.beforeModelCall(state, settings)
            state = result.first
            assertNull(result.second.prompt)
        }
        val third = RoundInjectionPolicy.beforeModelCall(state, settings)
        assertEquals("periodic", third.second.prompt)
    }

    @Test
    fun `disabled settings do not advance the counter`() {
        val result = RoundInjectionPolicy.beforeModelCall(
            RoundInjectionState(totalModelCalls = 4, lastInjectionCall = 4),
            RoundInjectionSettings(enabled = false, periodicPrompt = "x"),
        )
        assertEquals(4, result.first.totalModelCalls)
        assertNull(result.second.prompt)
        assertTrue(result.second.invocation == 4)
    }
}
