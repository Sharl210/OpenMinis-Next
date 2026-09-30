package com.openminis.app.config

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.openminis.app.data.repository.BackgroundSettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-config-prefs-mismatch-background] Behavioural counterpart to
 * [ChatConfigPrefsBindingTest].
 *
 * That test compares SOURCE TEXT, and two gaps let `background.*` through:
 *   1. its key universe is only the `const val KEY_*` literals declared in
 *      `AppearanceScreen.kt`, so a key owned by another screen/repository
 *      (`background_settings`, `minis_memory_prefs`) is never looked at; and
 *   2. it skips every registration that binds a shared constant, on the
 *      (correct) theory that a constant cannot drift — which means it never
 *      asks the prior question "does ANYTHING read this key at all?".
 *
 * So `minis-config set background.notifications false` answered `ok: true`
 * while `background_settings/background_notifications_enabled` was written and
 * the two notifiers kept reading `background_settings/taskNotificationsEnabled`.
 *
 * This test is behavioural: it drives the REAL registered field obtained from
 * the REAL registration function the app calls at boot, against an in-memory
 * SharedPreferences, and then reads back through the very objects the app's
 * consumers use. No source-text matching, so a mismatched key turns it red.
 */
class BackgroundConfigPrefsBindingTest {

    // ─── The reported defect: key alignment ──────────────────────────────

    @Test
    fun `the write lands in the store and key BackgroundSettingsRepository owns`() {
        val ctx = FakeContext()

        registeredField(ctx).write(ConfigValue.Bool(false))

        assertEquals(
            "background.notifications must persist to " +
                "${BackgroundSettingsRepository.PREFS_NAME}/" +
                "${BackgroundSettingsRepository.KEY_TASK_NOTIFICATIONS} — that is what " +
                "BackgroundTaskNotifier and ConfigConfirmNotifier actually read:",
            false,
            ctx.store(BackgroundSettingsRepository.PREFS_NAME)
                .getBoolean(BackgroundSettingsRepository.KEY_TASK_NOTIFICATIONS, true),
        )
    }

    // ─── The second layer: does the write change live behaviour? ─────────

    @Test
    fun `a config write flips the live state the notifiers gate on`() {
        val ctx = FakeContext()
        // Built FIRST on purpose. This is the app-scoped instance MinisApp
        // hands to BackgroundTaskNotifier / ConfigConfirmNotifier, and it
        // caches the value in a StateFlow at construction. A write that only
        // touches the prefs file leaves the notifiers reading the stale cache
        // until the process restarts — `ok: true`, behaviour unchanged.
        val repo = BackgroundSettingsRepository(ctx)
        assertTrue(
            "precondition: Task Notifications must ship ON (DEFAULT_TASK_NOTIFICATIONS)",
            repo.taskNotificationsEnabled.value,
        )

        registeredField(ctx).write(ConfigValue.Bool(false))

        assertEquals(
            "minis-config set background.notifications false must be visible to the " +
                "notifiers and to the Settings switch without a process restart:",
            false,
            repo.taskNotificationsEnabled.value,
        )
    }

    @Test
    fun `the dead key the builtin used to write is not honoured by the reader`() {
        // The two tests above pin the WRITE side. This one pins the READ side
        // against the opposite drift: if the repository were later widened to
        // also accept `background_notifications_enabled` ("legacy compat"), a
        // writer/reader key mismatch would start being tolerated and those two
        // tests would stay green — they only ever exercise the new key. Here the
        // legacy key is the ONLY thing on disk, so a reader that honours it
        // reports false while the correct reader reports the default true.
        val ctx = FakeContext()
        ctx.store(BackgroundSettingsRepository.PREFS_NAME)
            .edit()
            .putBoolean("background_notifications_enabled", false)
            .apply()

        assertTrue(
            "background_settings/background_notifications_enabled is the key the builtin used " +
                "to write and nothing reads; a reader that honours it re-opens the mismatch:",
            BackgroundSettingsRepository(ctx).taskNotificationsEnabled.value,
        )
    }

    // ─── Disposition of background.enhanced ──────────────────────────────

    @Test
    fun `the unread enhanced background key is not a writable no-op`() {
        // Android has no user-configurable "keep agent tasks running in the
        // background": AgentForegroundService is started unconditionally from
        // SessionActivityTracker.shouldRunService() and holds its own
        // PARTIAL_WAKE_LOCK. The key was ported from iOS, where
        // BackgroundKeepAliveManager.enhancedBackgroundEnabled really does gate
        // a keep-alive leg, but on Android it drove nothing — so
        // `minis-config set background.enhanced true` reported ok and changed
        // no behaviour at all. Re-adding the path before a real mechanism
        // exists must fail here.
        assertNull(
            "background.enhanced must not be registered while it has no Android " +
                "mechanism to drive; see the note in ConfigBuiltins.registerBackground",
            registry(FakeContext()).resolveField("background.enhanced"),
        )
    }

    @Test
    fun `the neighbouring background paths stay registered`() {
        val r = registry(FakeContext())
        assertEquals("background", r.resolveField("background.dynamicIsland")?.scope)
        assertEquals("background", r.resolveField("background.notifications")?.scope)
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    /** The real registration the app performs at boot, on a fake Context. */
    private fun registry(ctx: Context): ConfigRegistry {
        val r = ConfigRegistry()
        ConfigBuiltins.registerBackground(r, ctx)
        return r
    }

    private fun registeredField(ctx: Context) =
        registry(ctx).resolveField("background.notifications")
            ?: error("background.notifications is not registered")

    /** Per-name in-memory stores, so a wrong prefs FILE is caught as well. */
    private class FakeContext : ContextWrapper(null) {
        private val stores = linkedMapOf<String, MemoryPreferences>()

        fun store(name: String): MemoryPreferences =
            stores.getOrPut(name) { MemoryPreferences() }

        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            store(name)
    }

    private class MemoryPreferences : SharedPreferences {
        private val values = linkedMapOf<String, Any?>()
        private val listeners =
            linkedSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

        override fun getAll(): Map<String, *> = values.toMap()
        override fun getString(key: String, defValue: String?): String? =
            values[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
            values[key] as? Set<String> ?: defValues
        override fun getInt(key: String, defValue: Int): Int =
            values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long =
            values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float =
            values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            values[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener,
        ) {
            listeners += listener
        }

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener,
        ) {
            listeners -= listener
        }

        private inner class Editor : SharedPreferences.Editor {
            private val updates = linkedMapOf<String, Any?>()
            private var clear = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor =
                apply { updates[key] = value }

            override fun putStringSet(
                key: String,
                values: Set<String>?,
            ): SharedPreferences.Editor = apply { updates[key] = values }

            override fun putInt(key: String, value: Int): SharedPreferences.Editor =
                apply { updates[key] = value }

            override fun putLong(key: String, value: Long): SharedPreferences.Editor =
                apply { updates[key] = value }

            override fun putFloat(key: String, value: Float): SharedPreferences.Editor =
                apply { updates[key] = value }

            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor =
                apply { updates[key] = value }

            override fun remove(key: String): SharedPreferences.Editor =
                apply { updates[key] = REMOVED }

            override fun clear(): SharedPreferences.Editor = apply { clear = true }

            override fun commit(): Boolean {
                if (clear) values.clear()
                updates.forEach { (key, value) ->
                    if (value === REMOVED) values.remove(key) else values[key] = value
                    listeners.forEach { it.onSharedPreferenceChanged(this@MemoryPreferences, key) }
                }
                return true
            }

            override fun apply() {
                commit()
            }
        }

        private companion object {
            val REMOVED = Any()
        }
    }
}
