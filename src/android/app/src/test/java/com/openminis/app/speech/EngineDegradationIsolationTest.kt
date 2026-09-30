package com.openminis.app.speech

import com.openminis.app.shared.KotlinSourceText
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-voice-entry-always-available] Pins the degradation semantics the
 * voice UI now relies on.
 *
 * Two properties matter:
 *  1. Degradation is PER-ENGINE — a poisoned system engine must not mask a
 *     working provider engine, because availability is the `any` aggregate the
 *     manager publishes.
 *  2. Degradation is REVERSIBLE — it used to be permanent for the process
 *     lifetime with no reset path, so one transient failure (mic held by
 *     another app, permission not yet granted, provider since configured)
 *     disabled voice input until the app restarted.
 *
 * ## Why this file calls production instead of restating it
 *
 * The fake engine used to hand-roll the degradation state (`private var degraded`
 * plus `!degraded && installed`) and the aggregation was a `private fun
 * anyAvailable(vararg …) = e.any { it.isAvailable }` copy of what
 * `refreshAvailability()` publishes. Between them, the only code that decides
 * what the mic button shows was never executed by this file: either real
 * engine could have lost [clearDegraded], or kept its degraded flag forever, and
 * every assertion here stayed green.
 *
 * Both rules are production symbols now — `EngineDegradationState` is the state
 * both real engines are built on, `anyEngineAvailable` is what
 * `refreshAvailability()` publishes — so the fake below is a fake ENGINE with no
 * logic of its own, not a fake RULE.
 */
class EngineDegradationIsolationTest {

    /**
     * An engine whose entire behaviour is a probe plus the production degradation
     * state: `isAvailable` is whatever production says it is.
     */
    private class FakeEngine(
        override val id: String,
        probe: () -> Boolean,
    ) : SpeechRecognitionEngine {
        val degradation = EngineDegradationState(probe)
        override val displayName: String = id
        override val supportsPartialResults: Boolean = false
        override val supportedLocales: List<Locale> = emptyList()
        override val isAvailable: Boolean get() = degradation.isAvailable
        override fun start(locale: Locale, listener: SpeechRecognitionEngine.Listener) {}
        override fun stop() {}
        override fun cancel() {}
        override fun markDegraded() = degradation.mark()
        override fun clearDegraded() = degradation.clear()
    }

    @Test
    fun `a degraded system engine does not mask a working provider engine`() {
        val system = FakeEngine("system") { true }
        val provider = FakeEngine("provider") { true }

        system.markDegraded()

        assertFalse("system must report itself unavailable", system.isAvailable)
        assertTrue("provider is untouched", provider.isAvailable)
        assertTrue(
            "overall availability must survive on the provider engine",
            anyEngineAvailable(listOf(system, provider)),
        )
    }

    @Test
    fun `overall availability only drops when every engine is out`() {
        val system = FakeEngine("system") { true }
        // No ASR provider configured — the common case on a stock device.
        val provider = FakeEngine("provider") { false }

        assertTrue(anyEngineAvailable(listOf(system, provider)))
        system.markDegraded()
        assertFalse(
            "with no provider configured, a degraded system engine leaves nothing",
            anyEngineAvailable(listOf(system, provider)),
        )
    }

    @Test
    fun `degradation is reversible so a transient failure is not permanent`() {
        val system = FakeEngine("system") { true }
        system.markDegraded()
        assertFalse(system.isAvailable)

        // The user re-entering voice mode / picking the engine clears it.
        system.clearDegraded()
        assertTrue(
            "a retry must give the engine another chance — degradation used to " +
                "have no reset path at all",
            system.isAvailable,
        )
    }

    @Test
    fun `clearing degradation cannot resurrect an engine that is not installed`() {
        // Reset must not override the underlying probe: a provider that is
        // still unconfigured stays unavailable.
        val provider = FakeEngine("provider") { false }
        provider.markDegraded()
        provider.clearDegraded()
        assertFalse(provider.isAvailable)
    }

    // ─── properties a hand-rolled flag cannot be asked ───────────────────────

    /**
     * A degraded engine must not run its probe AT ALL.
     *
     * The old fake could not be asked this: it compared a captured boolean
     * (`!degraded && installed`), so "the probe ran" was not an observable event.
     * Production takes the probe as a lambda, which is what makes the property —
     * and the cost behind it — assertable: while an engine is degraded, rendering
     * the mic button must not re-query the package manager or the provider
     * repository.
     */
    @Test
    fun `a degraded engine does not run its probe at all`() {
        var probes = 0
        val engine = FakeEngine("system") { probes++; true }

        assertTrue(engine.isAvailable)
        assertEquals("the probe runs while healthy", 1, probes)

        engine.markDegraded()
        assertFalse(engine.isAvailable)
        assertEquals("a degraded engine must not run its probe", 1, probes)

        engine.clearDegraded()
        assertTrue(engine.isAvailable)
        assertEquals("a cleared engine gets another probe", 2, probes)
    }

    /**
     * The aggregate reads the engine list it is handed, live: an engine that
     * registers (or degrades) later is part of the NEXT verdict. A `vararg` copy
     * snapshots its arguments at the call site, so this property could not be
     * written against it — and the manager's engine list is exactly a list that
     * changes while the app runs.
     */
    @Test
    fun `the aggregate reads the engine list it is given, live`() {
        val engines = mutableListOf<SpeechRecognitionEngine>(FakeEngine("system") { false })
        assertFalse(anyEngineAvailable(engines))

        val provider = FakeEngine("provider") { true }
        engines += provider
        assertTrue("an engine registered later must count", anyEngineAvailable(engines))

        provider.markDegraded()
        assertFalse("…and must drop out again once it degrades", anyEngineAvailable(engines))
    }

    /**
     * A fake engine cannot be asked this: the manager must PUBLISH the shared
     * aggregate rather than keep its own inline copy of `engines.any { … }`.
     * Two implementations of "is voice input available" is the defect this file
     * is about — only the wiring says which one the UI sees.
     */
    @Test
    fun `the manager publishes the extracted aggregate`() {
        val source = source("speech/SpeechRecognitionManager.kt")
        val body = KotlinSourceText.bracedBlock(source, "refreshAvailability")
            ?: throw AssertionError("refreshAvailability not found in SpeechRecognitionManager.kt")
        assertTrue(
            "refreshAvailability must publish anyEngineAvailable(engines), got: ${body.trim()}",
            body.contains("anyEngineAvailable(engines)"),
        )
        assertFalse(
            "an inline `engines.any { it.isAvailable }` copy came back",
            body.contains("engines.any"),
        )
    }

    private fun source(relativePath: String): String =
        sequenceOf(
            File("app/src/main/java/com/openminis/app/$relativePath"),
            File("src/main/java/com/openminis/app/$relativePath"),
        ).firstOrNull { it.isFile }?.readText()
            ?: error("cannot locate $relativePath from ${File(".").absolutePath}")
}
