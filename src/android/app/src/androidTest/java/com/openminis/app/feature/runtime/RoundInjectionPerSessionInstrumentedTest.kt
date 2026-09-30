package com.openminis.app.feature.runtime

import android.content.Context
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [T-android-round-injection-per-session] The per-session contract of round
 * injection, on a real device.
 *
 * WHY THIS EXISTS. The requirement is explicit that the injection is per
 * conversation and reaches the whole tree:
 *
 * > "加入 round inject 插件能力：对全部会话（**包含各级子会话**）生效的提示词注入。
 * >  新增单独设置项和子页面，包含会话开始时注入、周期性注入、触发轮次；开始时和
 * >  周期性分别开关，**默认周期 50 轮**。"  (`request.md:11`)
 *
 * The existing `RoundInjectionTest` covers only the **pure** policy
 * (`RoundInjectionPolicy.beforeModelCall`) — three cases over one function. That
 * leaves the part the requirement is actually about untested: the **per-session
 * counter**, its durability, and whether a sub-agent session (the "各级子会话" half)
 * gets its own counter rather than sharing the parent's.
 *
 * Those three things live in `RoundInjectionCoordinator`, which needs a real
 * `Context` for `SharedPreferences` — which is precisely why the JVM suite cannot
 * reach them and why they had no coverage. This test runs on the device for that
 * reason, not because it needs a screen.
 *
 * A NOTE ON THE PREFERENCE NAMES. `STATE_PREFS` / `SETTINGS_PREFS` are private
 * constants of the production classes, so they are mirrored here as literals.
 * `preferenceStoresAreWired` asserts they still resolve to the same stores the
 * coordinator writes, so a rename fails loudly instead of leaving this file
 * quietly testing an empty sandbox (the "green test that verifies nothing"
 * failure mode this project has hit repeatedly).
 */
@RunWith(AndroidJUnit4::class)
class RoundInjectionPerSessionInstrumentedTest {

    private lateinit var context: Context
    private lateinit var statePrefs: SharedPreferences
    private lateinit var settingsPrefs: SharedPreferences
    private var stateBackup: Map<String, *> = emptyMap<String, Any>()
    private var settingsBackup: Map<String, *> = emptyMap<String, Any>()

    private fun coordinator() = RoundInjectionCoordinator(context)
    private fun settingsApi() = RoundInjectionSettingsPrefs(context)

    /** Inject a distinct prompt on the first call and every [interval] calls after. */
    private fun configure(start: String, periodic: String, interval: Int) {
        settingsApi().save(
            RoundInjectionSettings(
                enabled = true,
                injectOnStart = true,
                startPrompt = start,
                periodicEnabled = true,
                periodicPrompt = periodic,
                interval = interval,
            ),
        )
    }

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        statePrefs = context.applicationContext.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
        settingsPrefs =
            context.applicationContext.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        // This test writes to the app's REAL preference stores, so the user's own
        // round-injection settings are backed up and restored. A test that silently
        // wipes a developer's configuration is its own defect.
        stateBackup = HashMap(statePrefs.all)
        settingsBackup = HashMap(settingsPrefs.all)
        statePrefs.edit().clear().commit()
        settingsPrefs.edit().clear().commit()
    }

    @After
    fun tearDown() {
        restore(statePrefs, stateBackup)
        restore(settingsPrefs, settingsBackup)
    }

    private fun restore(prefs: SharedPreferences, backup: Map<String, *>) {
        val editor = prefs.edit().clear()
        for ((k, v) in backup) {
            when (v) {
                is String -> editor.putString(k, v)
                is Int -> editor.putInt(k, v)
                is Long -> editor.putLong(k, v)
                is Float -> editor.putFloat(k, v)
                is Boolean -> editor.putBoolean(k, v)
                is Set<*> -> editor.putStringSet(k, v.mapNotNull { it as? String }.toSet())
            }
        }
        editor.commit()
    }

    // --------------------------------------------------------------- wiring

    @Test
    fun preferenceStoresAreWired() {
        // Guards the mirrored constants above: if production renames a store, the
        // coordinator's writes must still land where this test looks.
        configure("start", "periodic", interval = 5)
        assertTrue(
            "settings did not land in \"$SETTINGS_PREFS\" — the mirrored constant is stale",
            settingsPrefs.contains("start_prompt"),
        )
        coordinator().beforeModelCall("wiring-probe")
        assertTrue(
            "coordinator state did not land in \"$STATE_PREFS\" — the mirrored constant is stale",
            statePrefs.all.keys.any { it.startsWith("session_") },
        )
    }

    // ------------------------------------------------- per-session contract

    @Test
    fun twoSessionsAdvanceIndependently() {
        configure("start", "periodic", interval = 50)
        val coordinator = coordinator()

        coordinator.beforeModelCall("session-a")
        coordinator.beforeModelCall("session-a")
        val third = coordinator.beforeModelCall("session-a")
        val firstOfB = coordinator.beforeModelCall("session-b")

        assertEquals("session A counted its own calls", 3, third.invocation)
        assertEquals(
            "session B must count from 1, not inherit A's 3",
            1,
            firstOfB.invocation,
        )
    }

    @Test
    fun stateSurvivesANewCoordinatorInstance() {
        // The counter is documented as durable. A per-instance map would pass a
        // same-instance test and fail here — and would also reset on every
        // recomposition of the chat screen.
        configure("start", "periodic", interval = 50)
        coordinator().beforeModelCall("durable")
        coordinator().beforeModelCall("durable")
        val thirdInstance = coordinator()
        assertEquals(3, thirdInstance.beforeModelCall("durable").invocation)
    }

    @Test
    fun resetOnlyAffectsItsOwnSession() {
        // [T-android-round-injection-reset-arithmetic] The expected count was
        // written for a call order this test does not perform, so the assertion
        // could only ever fail on a device.
        //
        // `RoundInjectionDecision.invocation` is a per-session sequence number
        // (`RoundInjectionPolicy.beforeModelCall` returns
        // `state.totalModelCalls + 1`), NOT a lifetime call count. `"keep"` is
        // called exactly three times here — twice at the top and once at the
        // bottom — so the last call is its 3rd, and `invocation` is 3. The old
        // expectation of 4 counted the two `beforeModelCall("drop")` lines as if
        // they advanced `"keep"`; they advance `"drop"`'s counter, which is
        // precisely the per-session property this test exists to pin.
        //
        // The injection settings do not move the number either. The start prompt
        // fires at invocation 1 of each session (including "drop" again after its
        // reset, since `reset` drops the whole record and the sequence restarts),
        // and the periodic prompt needs `invocation - lastInjectionCall >= 50`,
        // which five calls never reach. Neither changes `invocation`, which is all
        // the two assertions below read.
        configure("start", "periodic", interval = 50)
        val coordinator = coordinator()
        coordinator.beforeModelCall("keep")
        coordinator.beforeModelCall("keep")
        coordinator.beforeModelCall("drop")
        coordinator.beforeModelCall("drop")

        coordinator.reset("drop")

        assertEquals("the dropped session restarts from 1", 1, coordinator.beforeModelCall("drop").invocation)
        // "keep" was called twice above; this is its third call overall.
        assertEquals("the sibling session is untouched", 3, coordinator.beforeModelCall("keep").invocation)
    }

    // ------------------------------------- the "各级子会话" half of the req

    @Test
    fun aChildSessionGetsItsOwnStartInjectionOnItsFirstCall() {
        // request.md:11 demands the injection reach "全部会话（包含各级子会话）". The
        // child runner drives the same coordinator with its own child session id
        // (RuntimeChildRunner.kt), so a child's first model call must be treated as
        // a first call — not as a continuation of the parent's count.
        configure(start = "<child-start>", periodic = "", interval = 50)
        val coordinator = coordinator()

        // Parent burns 12 model calls first.
        repeat(12) { coordinator.beforeModelCall("parent-session") }

        val childFirst = coordinator.beforeModelCall("child-session")
        assertEquals("the child starts its own count", 1, childFirst.invocation)
        assertEquals(
            "the child receives the start prompt on its first call",
            "<child-start>",
            childFirst.prompt,
        )
        assertTrue("and it is labelled as the start injection", childFirst.isStartPrompt)
    }

    @Test
    fun aChildsPeriodicCountIsIndependentOfTheParents() {
        // A shared counter would fire the child's periodic injection far too early
        // (or never), depending on how much the parent had run. Pin the independence.
        //
        // [T-android-round-injection-false-alarm] The parent must stop ONE CALL
        // SHORT of its own threshold, or this test's own probe injects and the
        // sibling assertion reads the parent's prompt as if the child had seen it.
        // The old version made three parent calls with `interval = 3`, so the
        // parent's own `invocation >= interval` was already true on that third
        // call; `assertNull` then failed on a device with "expected null, but
        // was:<<tick>>" — the parent's tick, not the child's. Two parent calls plus
        // the two below-the-threshold child calls keep every probe below 3 while
        // still pinning the thing that matters: a SHARED counter would make the
        // child's first call the sequence's 3rd, and it would inject there.
        configure(start = "", periodic = "<tick>", interval = 3)
        val coordinator = coordinator()

        // Parent: calls 1 and 2, both one short of its own threshold of 3.
        assertNull("the parent's 1st call", coordinator.beforeModelCall("parent-session").prompt)
        assertNull("the parent's 2nd call is still below its own threshold", coordinator.beforeModelCall("parent-session").prompt)

        // The child's own sequence: 1, 2, 3 -> ticks on the 3rd.
        assertNull(coordinator.beforeModelCall("child-session").prompt)
        assertNull(coordinator.beforeModelCall("child-session").prompt)
        assertEquals(
            "the child's own third call is due, unaffected by the parent's 2 calls",
            "<tick>",
            coordinator.beforeModelCall("child-session").prompt,
        )
    }

    // ------------------------------------------------------- requirement data

    @Test
    fun theDefaultIntervalIsTheOneTheRequirementNames() {
        // request.md:11 — "默认周期 50 轮". Pinned as a number on purpose: this is
        // the kind of value that gets quietly retuned, and no other test would notice.
        assertEquals(50, RoundInjectionSettingsPrefs.DEFAULT_INTERVAL)
        assertEquals(
            "a fresh install must report the requirement's default, not just hold the constant",
            50,
            settingsApi().load().interval,
        )
    }

    @Test
    fun anOutOfRangeIntervalIsClampedInsteadOfFiringEveryCall() {
        // A stored 0 would make `invocation >= interval` true on EVERY call, i.e. the
        // periodic prompt would be injected on every single model call. The clamp is
        // what stands between a bad stored value and that outcome, so pin the clamp
        // rather than trusting the requirement's default.
        settingsPrefs.edit().putInt("interval", 0).commit()
        assertEquals(
            "a stored 0 must be clamped up to MIN_INTERVAL, not honoured",
            RoundInjectionSettingsPrefs.MIN_INTERVAL,
            settingsApi().load().interval,
        )

        settingsPrefs.edit().putInt("interval", Int.MAX_VALUE).commit()
        assertEquals(
            "an absurd value must be clamped down to MAX_INTERVAL",
            RoundInjectionSettingsPrefs.MAX_INTERVAL,
            settingsApi().load().interval,
        )
    }

    @Test
    fun periodicInjectionFiresAtTheConfiguredInterval() {
        configure(start = "", periodic = "<tick>", interval = 4)
        val coordinator = coordinator()
        val firedAt = mutableListOf<Int>()
        repeat(12) {
            val decision = coordinator.beforeModelCall("cadence")
            if (decision.prompt != null) firedAt += decision.invocation
        }
        assertEquals("every 4th call, starting at 4", listOf(4, 8, 12), firedAt)
    }

    private companion object {
        /** Mirrors `RoundInjectionCoordinator.STATE_PREFS` (private). */
        const val STATE_PREFS = "round_injection_runtime"

        /** Mirrors `RoundInjectionSettingsPrefs.PREFS_NAME` (private). */
        const val SETTINGS_PREFS = "round_injection_settings"
    }
}
