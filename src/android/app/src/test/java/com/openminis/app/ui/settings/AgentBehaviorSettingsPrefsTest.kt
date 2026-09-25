package com.openminis.app.ui.settings

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentBehaviorSettingsPrefsTest {
    @Test
    fun `retry budget keeps unlimited disabled and bounded meanings`() {
        assertEquals(-1, AgentBehaviorSettingsPrefs.normalizeMaxRetryAttempts(-1))
        assertEquals(0, AgentBehaviorSettingsPrefs.normalizeMaxRetryAttempts(0))
        assertEquals(7, AgentBehaviorSettingsPrefs.normalizeMaxRetryAttempts(7))
        assertEquals(150, AgentBehaviorSettingsPrefs.normalizeMaxRetryAttempts(151))
        assertEquals(0, AgentBehaviorSettingsPrefs.normalizeMaxRetryAttempts(-2))
    }

    @Test
    fun `save and load preserve all three retry budget states`() {
        val context = TestContext()
        val prefs = AgentBehaviorSettingsPrefs(context)
        listOf(-1, 0, 12).forEach { budget ->
            prefs.save(AgentBehaviorSettings(maxRetryAttempts = budget))
            assertEquals(budget, prefs.load().maxRetryAttempts)
        }
    }

    @Test
    fun `numeric settings share maximum of one hundred fifty`() {
        assertEquals(150, AgentBehaviorSettingsPrefs.MAX_PARALLEL_AGENTS)
        assertEquals(150, AgentBehaviorSettingsPrefs.MAX_COMPACT_THRESHOLD_TOKENS)
        assertEquals(150, AgentBehaviorSettingsPrefs.MAX_RETRY_ATTEMPTS)
    }

    private class TestContext : ContextWrapper(null) {
        private val preferences = MemoryPreferences()

        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
    }

    private class MemoryPreferences : SharedPreferences {
        private val values = linkedMapOf<String, Any?>()
        override fun getAll(): Map<String, *> = values.toMap()
        override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = values[key] as? Set<String> ?: defValues
        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val updates = linkedMapOf<String, Any?>()
            override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { updates[key] = value }
            override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor = apply { updates[key] = values }
            override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { updates[key] = value }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { updates[key] = value }
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { updates[key] = value }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { updates[key] = value }
            override fun remove(key: String): SharedPreferences.Editor = apply { updates.remove(key) }
            override fun clear(): SharedPreferences.Editor = apply { updates.clear(); values.clear() }
            override fun commit(): Boolean {
                updates.forEach { (key, value) -> values[key] = value }
                return true
            }

            override fun apply() {
                updates.forEach { (key, value) -> values[key] = value }
            }
        }
    }
}
