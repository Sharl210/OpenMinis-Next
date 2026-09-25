package com.openminis.app.ui.settings

import android.content.Context
import android.content.SharedPreferences

/** Persistent, UI-owned preferences for Agent execution behavior. */
class AgentBehaviorSettingsPrefs(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    fun load(): AgentBehaviorSettings = AgentBehaviorSettings(
        workStepDisplay = WorkStepDisplay.fromStored(prefs.getString(KEY_WORK_STEP_DISPLAY, null)),
        recursionDepth = prefs.getInt(KEY_RECURSION_DEPTH, DEFAULT_RECURSION_DEPTH)
            .coerceIn(MIN_RECURSION_DEPTH, MAX_RECURSION_DEPTH),
        parallelAgentLimit = prefs.getInt(KEY_PARALLEL_AGENT_LIMIT, DEFAULT_PARALLEL_AGENT_LIMIT)
            .coerceIn(MIN_PARALLEL_AGENTS, MAX_PARALLEL_AGENTS),
        defaultSendStrategy = DefaultSendStrategy.fromStored(
            prefs.getString(KEY_DEFAULT_SEND_STRATEGY, null),
        ),
        compactThresholdTokens = prefs.getInt(
            KEY_COMPACT_THRESHOLD_TOKENS,
            DEFAULT_COMPACT_THRESHOLD_TOKENS,
        ).coerceIn(MIN_COMPACT_THRESHOLD_TOKENS, MAX_COMPACT_THRESHOLD_TOKENS),
        autoRetryEnabled = prefs.getBoolean(KEY_AUTO_RETRY_ENABLED, DEFAULT_AUTO_RETRY_ENABLED),
        maxRetryAttempts = normalizeMaxRetryAttempts(
            prefs.getInt(KEY_MAX_RETRY_ATTEMPTS, DEFAULT_MAX_RETRY_ATTEMPTS),
        ),
    )

    fun save(settings: AgentBehaviorSettings) {
        prefs.edit()
            .putString(KEY_WORK_STEP_DISPLAY, settings.workStepDisplay.name)
            .putInt(KEY_RECURSION_DEPTH, settings.recursionDepth.coerceIn(MIN_RECURSION_DEPTH, MAX_RECURSION_DEPTH))
            .putInt(KEY_PARALLEL_AGENT_LIMIT, settings.parallelAgentLimit.coerceIn(MIN_PARALLEL_AGENTS, MAX_PARALLEL_AGENTS))
            .putString(KEY_DEFAULT_SEND_STRATEGY, settings.defaultSendStrategy.name)
            .putInt(
                KEY_COMPACT_THRESHOLD_TOKENS,
                settings.compactThresholdTokens.coerceIn(MIN_COMPACT_THRESHOLD_TOKENS, MAX_COMPACT_THRESHOLD_TOKENS),
            )
            .putBoolean(KEY_AUTO_RETRY_ENABLED, settings.autoRetryEnabled)
            .putInt(KEY_MAX_RETRY_ATTEMPTS, normalizeMaxRetryAttempts(settings.maxRetryAttempts))
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "agent_behavior_settings"
        private const val KEY_WORK_STEP_DISPLAY = "work_step_display"
        private const val KEY_RECURSION_DEPTH = "recursion_depth"
        private const val KEY_PARALLEL_AGENT_LIMIT = "parallel_agent_limit"
        private const val KEY_DEFAULT_SEND_STRATEGY = "default_send_strategy"
        private const val KEY_COMPACT_THRESHOLD_TOKENS = "compact_threshold_tokens"
        private const val KEY_AUTO_RETRY_ENABLED = "auto_retry_enabled"
        private const val KEY_MAX_RETRY_ATTEMPTS = "max_retry_attempts"

        const val DEFAULT_RECURSION_DEPTH = 2
        const val DEFAULT_PARALLEL_AGENT_LIMIT = 5
        const val DEFAULT_COMPACT_THRESHOLD_TOKENS = 150
        const val DEFAULT_AUTO_RETRY_ENABLED = true
        const val DEFAULT_MAX_RETRY_ATTEMPTS = 10

        const val MIN_RECURSION_DEPTH = 0
        const val MAX_RECURSION_DEPTH = 2
        const val MIN_PARALLEL_AGENTS = 1
        const val MAX_PARALLEL_AGENTS = 150
        const val MIN_COMPACT_THRESHOLD_TOKENS = 0
        const val MAX_COMPACT_THRESHOLD_TOKENS = 150
        const val MIN_RETRY_ATTEMPTS = -1
        const val MAX_RETRY_ATTEMPTS = 150

        /**
         * -1 = unlimited, 0 = disabled, positive = bounded retries.
         * Values below -1 are invalid and degrade to disabled; -1 is never
         * coerced into an ordinary positive attempt count.
         */
        fun normalizeMaxRetryAttempts(value: Int): Int = when {
            value == -1 -> -1
            value < 0 -> 0
            else -> value.coerceAtMost(MAX_RETRY_ATTEMPTS)
        }
    }
}

data class AgentBehaviorSettings(
    val workStepDisplay: WorkStepDisplay = WorkStepDisplay.SUMMARY,
    val recursionDepth: Int = AgentBehaviorSettingsPrefs.DEFAULT_RECURSION_DEPTH,
    val parallelAgentLimit: Int = AgentBehaviorSettingsPrefs.DEFAULT_PARALLEL_AGENT_LIMIT,
    val defaultSendStrategy: DefaultSendStrategy = DefaultSendStrategy.QUEUE,
    val compactThresholdTokens: Int = AgentBehaviorSettingsPrefs.DEFAULT_COMPACT_THRESHOLD_TOKENS,
    val autoRetryEnabled: Boolean = AgentBehaviorSettingsPrefs.DEFAULT_AUTO_RETRY_ENABLED,
    val maxRetryAttempts: Int = AgentBehaviorSettingsPrefs.DEFAULT_MAX_RETRY_ATTEMPTS,
)
