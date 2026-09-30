package com.openminis.app.feature.runtime

import android.content.Context
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject

/** User-facing configuration for DSH-compatible periodic prompt injection. */
data class RoundInjectionSettings(
    val enabled: Boolean = true,
    val injectOnStart: Boolean = true,
    val startPrompt: String = "",
    val periodicEnabled: Boolean = true,
    val periodicPrompt: String = "",
    val interval: Int = RoundInjectionSettingsPrefs.DEFAULT_INTERVAL,
)

data class RoundInjectionState(
    val totalModelCalls: Int = 0,
    val lastInjectionCall: Int? = null,
)

data class RoundInjectionDecision(
    val invocation: Int,
    val prompt: String?,
    val isStartPrompt: Boolean = false,
)

/** Pure policy; keeps timing semantics testable without Android or a provider. */
object RoundInjectionPolicy {
    fun beforeModelCall(
        state: RoundInjectionState,
        settings: RoundInjectionSettings,
    ): Pair<RoundInjectionState, RoundInjectionDecision> {
        if (!settings.enabled) {
            return state to RoundInjectionDecision(state.totalModelCalls, null)
        }
        val invocation = state.totalModelCalls + 1
        val startPrompt = settings.startPrompt.trim()
        val periodicPrompt = settings.periodicPrompt.trim()
        val start = settings.injectOnStart && invocation == 1 && startPrompt.isNotEmpty()
        val periodicDue = settings.periodicEnabled && periodicPrompt.isNotEmpty() &&
            if (state.lastInjectionCall == null) {
                invocation >= settings.interval.coerceAtLeast(1)
            } else {
                invocation - state.lastInjectionCall >= settings.interval.coerceAtLeast(1)
            }
        val prompt = when {
            start -> startPrompt
            periodicDue -> periodicPrompt
            else -> null
        }
        val nextState = RoundInjectionState(
            totalModelCalls = invocation,
            lastInjectionCall = if (prompt != null) invocation else state.lastInjectionCall,
        )
        return nextState to RoundInjectionDecision(
            invocation = invocation,
            prompt = prompt,
            isStartPrompt = start,
        )
    }
}

/**
 * The history shaping an injected prompt performs, as a pure top-level function.
 *
 * ## Why this is top-level and `internal`
 *
 * Two production call sites hand the result of this straight to a provider as the
 * turn's messages: `ChatViewModel`'s per-round injection and `RuntimeChildRunner`'s
 * child-agent injection. Until this extraction, the only way to execute the shape
 * was `RoundInjectionCoordinator.appendToHistory`, whose constructor reads
 * SharedPreferences — unreachable from a JVM unit test, which is why the string
 * `appendToHistory` appeared **zero** times under `src/test` and `src/androidTest`.
 *
 * The extraction changes no behaviour and adds no call path: `appendToHistory`
 * delegates here, and both production sites still reach it through that single
 * method. It exists so the shape below can be executed by a test rather than
 * described by one.
 *
 * ## The shape, and which half is load-bearing
 *
 * The appended turn is a `USER` turn — it is an instruction for this turn, not a
 * system rule. The bridge is the part that is easy to delete and expensive to
 * lose: several providers reject (and others silently mis-attribute) a second
 * `USER` message immediately following a `USER` message, so an `ASSISTANT`
 * acknowledgement is inserted between them. The bridge is in-memory only; it is
 * never persisted into the transcript.
 */
internal fun appendInjectedPromptToHistory(history: MutableList<LLMMessage>, prompt: String) {
    if (history.lastOrNull()?.role == LLMMessage.Role.USER) {
        history += LLMMessage(
            role = LLMMessage.Role.ASSISTANT,
            content = RoundInjectionCoordinator.ROLE_BRIDGE,
            contentParts = listOf(AgentContentPart.Text(RoundInjectionCoordinator.ROLE_BRIDGE)),
        )
    }
    history += LLMMessage(
        role = LLMMessage.Role.USER,
        content = prompt,
        contentParts = listOf(AgentContentPart.Text(prompt)),
    )
}

/** Durable per-session counter. Settings are intentionally separate from counters. */
class RoundInjectionCoordinator(context: Context) {
    private val statePrefs = context.applicationContext.getSharedPreferences(
        STATE_PREFS,
        Context.MODE_PRIVATE,
    )
    private val settingsPrefs = RoundInjectionSettingsPrefs(context)

    @Synchronized
    fun beforeModelCall(sessionId: String): RoundInjectionDecision {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        val current = readState(sessionId)
        val (next, decision) = RoundInjectionPolicy.beforeModelCall(current, settingsPrefs.load())
        if (next != current) writeState(sessionId, next)
        return decision
    }

    /** Adds a model-visible user turn, inserting an in-memory role bridge when needed. */
    fun appendToHistory(history: MutableList<LLMMessage>, prompt: String) =
        appendInjectedPromptToHistory(history, prompt)

    fun reset(sessionId: String) {
        if (sessionId.isNotBlank()) statePrefs.edit().remove(key(sessionId)).apply()
    }

    private fun readState(sessionId: String): RoundInjectionState {
        val raw = statePrefs.getString(key(sessionId), null) ?: return RoundInjectionState()
        return runCatching {
            val json = JSONObject(raw)
            RoundInjectionState(
                totalModelCalls = json.optInt("totalModelCalls", 0).coerceAtLeast(0),
                lastInjectionCall = if (json.has("lastInjectionCall") && !json.isNull("lastInjectionCall")) {
                    json.optInt("lastInjectionCall").takeIf { it > 0 }
                } else {
                    null
                },
            )
        }.getOrDefault(RoundInjectionState())
    }

    private fun writeState(sessionId: String, state: RoundInjectionState) {
        val json = JSONObject().apply {
            put("totalModelCalls", state.totalModelCalls)
            if (state.lastInjectionCall == null) put("lastInjectionCall", JSONObject.NULL)
            else put("lastInjectionCall", state.lastInjectionCall)
        }
        statePrefs.edit().putString(key(sessionId), json.toString()).apply()
    }

    private fun key(sessionId: String): String = "session_$sessionId"

    companion object {
        private const val STATE_PREFS = "round_injection_runtime"
        const val ROLE_BRIDGE = "(Periodic prompt injection follows; treat it as a user instruction for this turn.)"

        fun partsJson(prompt: String): String =
            "[{\"type\":\"text\",\"value\":${JSONObject.quote(prompt)}}]"
    }
}

class RoundInjectionSettingsPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    fun load(): RoundInjectionSettings = RoundInjectionSettings(
        enabled = prefs.getBoolean(KEY_ENABLED, true),
        injectOnStart = prefs.getBoolean(KEY_INJECT_ON_START, true),
        startPrompt = prefs.getString(KEY_START_PROMPT, "").orEmpty(),
        periodicEnabled = prefs.getBoolean(KEY_PERIODIC_ENABLED, true),
        periodicPrompt = prefs.getString(KEY_PERIODIC_PROMPT, "").orEmpty(),
        interval = prefs.getInt(KEY_INTERVAL, DEFAULT_INTERVAL).coerceIn(MIN_INTERVAL, MAX_INTERVAL),
    )

    fun save(settings: RoundInjectionSettings) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putBoolean(KEY_INJECT_ON_START, settings.injectOnStart)
            .putString(KEY_START_PROMPT, settings.startPrompt)
            .putBoolean(KEY_PERIODIC_ENABLED, settings.periodicEnabled)
            .putString(KEY_PERIODIC_PROMPT, settings.periodicPrompt)
            .putInt(KEY_INTERVAL, settings.interval.coerceIn(MIN_INTERVAL, MAX_INTERVAL))
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "round_injection_settings"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_INJECT_ON_START = "inject_on_start"
        private const val KEY_START_PROMPT = "start_prompt"
        private const val KEY_PERIODIC_ENABLED = "periodic_enabled"
        private const val KEY_PERIODIC_PROMPT = "periodic_prompt"
        private const val KEY_INTERVAL = "interval"

        const val DEFAULT_INTERVAL = 50
        const val MIN_INTERVAL = 1
        const val MAX_INTERVAL = 100_000
    }
}
