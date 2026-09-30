package com.openminis.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * T180-bg-notif: persistence for background-related toggles. Currently
 * only "Task Notifications" lives here; iOS exposes the same toggle in
 * `EnhancedBackgroundSettingsView` bound to
 * `BackgroundKeepAliveManager.backgroundNotificationsEnabled`.
 *
 * Default value is `true` to match iOS, where the toggle ships ON so
 * Live Activity and task-completion notifications work out-of-the-box
 * on first install. The user can opt out from Settings.
 */
class BackgroundSettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _taskNotificationsEnabled =
        MutableStateFlow(prefs.getBoolean(KEY_TASK_NOTIFICATIONS, DEFAULT_TASK_NOTIFICATIONS))

    /**
     * Live state of the toggle. Compose surfaces collect this so flipping
     * the switch in Settings is reflected immediately at every consumer
     * (notifier, FG service status text, etc).
     */
    val taskNotificationsEnabled: StateFlow<Boolean> =
        _taskNotificationsEnabled.asStateFlow()

    fun setTaskNotificationsEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_TASK_NOTIFICATIONS, value).apply()
        _taskNotificationsEnabled.value = value
    }

    /**
     * T-bg-overlay phase 2: "show floating tool-status overlay while the
     * app is backgrounded" toggle. Defaults to OFF — the overlay needs
     * SYSTEM_ALERT_WINDOW which is a separate system permission flow, so
     * we won't surface anything until the user opts in.
     */
    private val _backgroundOverlayEnabled =
        MutableStateFlow(prefs.getBoolean(KEY_BG_OVERLAY_ENABLED, false))
    val backgroundOverlayEnabled: StateFlow<Boolean> =
        _backgroundOverlayEnabled.asStateFlow()

    fun setBackgroundOverlayEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_BG_OVERLAY_ENABLED, value).apply()
        _backgroundOverlayEnabled.value = value
    }

    /**
     * [T-android-dynamic-island] "Show live status on the dynamic island"
     * toggle (Android 16 Live Updates). Defaults to OFF — the capability only
     * exists on Android 16+ with the per-app grant, and when ON it REPLACES the
     * floating overlay (mutual exclusion in AgentForegroundService.applyOverlayState),
     * so we don't want it silently changing behavior on upgrade. Reactive:
     * flipping it re-drives the FG service's combined flow so the overlay
     * appears/disappears without an app restart.
     */
    private val _dynamicIslandEnabled =
        MutableStateFlow(prefs.getBoolean(KEY_DYNAMIC_ISLAND_ENABLED, false))
    val dynamicIslandEnabled: StateFlow<Boolean> =
        _dynamicIslandEnabled.asStateFlow()

    fun setDynamicIslandEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_DYNAMIC_ISLAND_ENABLED, value).apply()
        _dynamicIslandEnabled.value = value
    }

    /**
     * Last persisted overlay position (window x/y in pixels) from the
     * previous drag. -1 means "no remembered position — let the overlay
     * controller pick a default near the bottom-left, 10 dp from each
     * edge" ([T-bg-overlay-polish]).
     */
    fun getOverlayX(): Int = prefs.getInt(KEY_BG_OVERLAY_X, -1)
    fun getOverlayY(): Int = prefs.getInt(KEY_BG_OVERLAY_Y, -1)
    fun setOverlayPosition(x: Int, y: Int) {
        prefs.edit().putInt(KEY_BG_OVERLAY_X, x).putInt(KEY_BG_OVERLAY_Y, y).apply()
    }

    /**
     * [T-android-config-prefs-mismatch-background] Out-of-band writers — most
     * importantly `minis-config set background.notifications`, which writes the
     * SharedPreferences file directly — never call the setters above. The
     * StateFlows were seeded once at construction, so such a write would be
     * accepted (`ok: true`) while every consumer kept reading the cached value
     * until the process restarted: `BackgroundTaskNotifier` and
     * `ConfigConfirmNotifier` gate on `taskNotificationsEnabled.value`, and the
     * Settings switch collects it.
     *
     * That is the second half of the same defect: binding the right key makes
     * the STORED value correct, and this listener makes it take effect live.
     *
     * All three keys of this store are covered, and the other two are not
     * decoration. `ConfigBuiltins` binds `background.dynamicIsland` to this same
     * file and key and writes it through `PrefsBoolField` — i.e. directly, never
     * through the setter below — while `setDynamicIslandEnabled`'s own KDoc and
     * `AgentForegroundService`'s combined flow both promise the toggle takes
     * effect without an app restart. Leaving the key out kept that promise broken
     * for every write made through `minis-config`: stored correctly, `ok: true`,
     * live behaviour unchanged. The same holds for the overlay key, which no
     * config path writes yet — and the two are MUTUALLY EXCLUSIVE in
     * `applyOverlayState`, so a stale one decides the exclusion wrongly instead of
     * merely repainting late.
     *
     * The listener is held in a field on purpose — the platform stores listeners
     * weakly.
     */
    private val prefsListener = object : SharedPreferences.OnSharedPreferenceChangeListener {
        override fun onSharedPreferenceChanged(
            sharedPreferences: SharedPreferences?,
            key: String?,
        ) {
            when (key) {
                KEY_TASK_NOTIFICATIONS -> _taskNotificationsEnabled.value =
                    prefs.getBoolean(KEY_TASK_NOTIFICATIONS, DEFAULT_TASK_NOTIFICATIONS)

                // Same literal as the field initialiser above, on purpose: this
                // listener and that initialiser are the only two places the
                // default exists, and they must agree.
                KEY_BG_OVERLAY_ENABLED -> _backgroundOverlayEnabled.value =
                    prefs.getBoolean(KEY_BG_OVERLAY_ENABLED, false)

                KEY_DYNAMIC_ISLAND_ENABLED -> _dynamicIslandEnabled.value =
                    prefs.getBoolean(KEY_DYNAMIC_ISLAND_ENABLED, false)
            }
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    companion object {
        // [T-android-config-prefs-mismatch-background] `internal`, not `private`:
        // ConfigBuiltins' `background.notifications` field binds to THESE symbols
        // (and the test asserts against them). Hand-typing the file name or the key
        // there is exactly what made `minis-config set background.notifications
        // false` a silent no-op — it wrote `background_notifications_enabled` into
        // this same file while every reader below uses `taskNotificationsEnabled`.
        internal const val PREFS_NAME = "background_settings"
        internal const val KEY_TASK_NOTIFICATIONS = "taskNotificationsEnabled"
        internal const val DEFAULT_TASK_NOTIFICATIONS = true
        private const val KEY_BG_OVERLAY_ENABLED = "backgroundOverlayEnabled"
        private const val KEY_BG_OVERLAY_X = "backgroundOverlayX"
        private const val KEY_BG_OVERLAY_Y = "backgroundOverlayY"
        private const val KEY_DYNAMIC_ISLAND_ENABLED = "dynamicIslandEnabled"
    }
}
