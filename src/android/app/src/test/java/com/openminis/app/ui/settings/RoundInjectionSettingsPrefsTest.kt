package com.openminis.app.ui.settings

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.openminis.app.feature.runtime.RoundInjectionSettings
import com.openminis.app.feature.runtime.RoundInjectionSettingsPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoundInjectionSettingsPrefsTest {
    @Test
    fun `defaults enable injection and use fifty call interval`() {
        val loaded = RoundInjectionSettingsPrefs(TestContext()).load()

        assertTrue(loaded.enabled)
        assertTrue(loaded.injectOnStart)
        assertTrue(loaded.periodicEnabled)
        assertEquals("", loaded.startPrompt)
        assertEquals("", loaded.periodicPrompt)
        assertEquals(50, loaded.interval)
    }

    @Test
    fun `save and load preserve settings while clamping interval`() {
        val prefs = RoundInjectionSettingsPrefs(TestContext())
        prefs.save(
            RoundInjectionSettings(
                enabled = false,
                injectOnStart = false,
                startPrompt = "start",
                periodicEnabled = true,
                periodicPrompt = "periodic",
                interval = RoundInjectionSettingsPrefs.MAX_INTERVAL + 1,
            ),
        )

        val loaded = prefs.load()
        assertEquals(false, loaded.enabled)
        assertEquals(false, loaded.injectOnStart)
        assertEquals("start", loaded.startPrompt)
        assertEquals(true, loaded.periodicEnabled)
        assertEquals("periodic", loaded.periodicPrompt)
        assertEquals(RoundInjectionSettingsPrefs.MAX_INTERVAL, loaded.interval)
    }

    @Test
    fun `invalid stored interval is clamped to minimum`() {
        val context = TestContext()
        context.preferences.edit().putInt("interval", 0).commit()

        assertEquals(
            RoundInjectionSettingsPrefs.MIN_INTERVAL,
            RoundInjectionSettingsPrefs(context).load().interval,
        )
    }

    private class TestContext : ContextWrapper(null) {
        val preferences = MemoryPreferences()

        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
    }

    private class MemoryPreferences : SharedPreferences {
        private val values = linkedMapOf<String, Any?>()
        private val listeners = linkedSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

        override fun getAll(): Map<String, *> = values.toMap()
        override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = values[key] as? Set<String> ?: defValues
        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
            listeners += listener
        }
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
            listeners -= listener
        }

        private inner class Editor : SharedPreferences.Editor {
            private val updates = linkedMapOf<String, Any?>()
            private var clear = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor = apply {
                updates[key] = value
            }
            override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor = apply {
                updates[key] = values
            }
            override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply {
                updates[key] = value
            }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply {
                updates[key] = value
            }
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply {
                updates[key] = value
            }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply {
                updates[key] = value
            }
            override fun remove(key: String): SharedPreferences.Editor = apply {
                updates[key] = REMOVED
            }
            override fun clear(): SharedPreferences.Editor = apply { clear = true }
            override fun commit(): Boolean {
                if (clear) values.clear()
                updates.forEach { (key, value) ->
                    if (value === REMOVED) values.remove(key) else values[key] = value
                    listeners.forEach { listener -> listener.onSharedPreferenceChanged(this@MemoryPreferences, key) }
                }
                return true
            }
            override fun apply() { commit() }
        }

        private companion object {
            val REMOVED = Any()
        }
    }
}
