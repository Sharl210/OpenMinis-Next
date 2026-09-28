package com.openminis.app.service

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.openminis.app.feature.runtime.RuntimeDelegationRequest
import com.openminis.app.feature.runtime.RuntimeModelSnapshot
import com.openminis.app.feature.runtime.RuntimeSessionCoordinator
import com.openminis.app.feature.runtime.RuntimeStopReport
import com.openminis.app.feature.runtime.RuntimeTreeConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton that tracks two independent kinds of "session is alive":
 *
 *  - `activeSessions`: at least one [com.openminis.app.ui.chat.ChatViewModel.streamJob]
 *    is in flight (LLM call, tool execution).
 *  - `presentSessions`: the user is sitting on a chat screen. Presence is UI
 *    context only and never keeps the foreground service alive by itself.
 *  - `activeChildSessions` / `waitingChildSessions`: child work is aggregated
 *    under its root without changing the root ChatViewModel's active set.
 *
 * The foreground service runs only while the runtime activity aggregate is
 * non-empty: a root stream, a child stream, or an explicit root waiting for
 * child work. Presence alone must not create a stale RUNNING notification.
 */
object SessionActivityTracker {

    private const val TAG = "SessionTracker"

    private val _activeSessions = MutableStateFlow<Set<String>>(emptySet())
    val activeSessions: StateFlow<Set<String>> = _activeSessions.asStateFlow()

    /**
     * T166: sessions the user is currently *present in* (composing /
     * reading), distinct from [activeSessions] which tracks streaming.
     * Drives the foreground service so the process stays at adj=200
     * the entire time the user is inside a chat — not just while the
     * stream is in flight. Without this, hitting Home from a chat
     * drops the process to adj=700 (LAST) and a Pixel 4a will reclaim
     * within minutes under any memory pressure, forcing a full
     * Activity rebuild on return.
     */
    private val _presentSessions = MutableStateFlow<Set<String>>(emptySet())
    val presentSessions: StateFlow<Set<String>> = _presentSessions.asStateFlow()

    /**
     * Child work is tracked separately from [activeSessions]. A child must not
     * make the parent ChatViewModel's send/stop state look like a second root
     * stream, but it still keeps the runtime status surface alive.
     *
     * The map is parent session id -> currently running child ids. Nested
     * children use their own parent id, so callers can represent a whole tree
     * without ever inserting child ids into the root active set.
     */
    private val _activeChildSessions = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val activeChildSessions: StateFlow<Map<String, Set<String>>> = _activeChildSessions.asStateFlow()

    /** Explicit parent waits for child work; this is not inferred from presence. */
    private val _waitingChildSessions = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val waitingChildSessions: StateFlow<Map<String, Set<String>>> = _waitingChildSessions.asStateFlow()

    /** Human-readable aggregate for notifications/overlay consumers. */
    private val _waitingChildStatusText = MutableStateFlow<String?>(null)
    val waitingChildStatusText: StateFlow<String?> = _waitingChildStatusText.asStateFlow()

    /**
     * Snapshot consumed by background surfaces. Only this aggregate may decide
     * whether a task-status notification/overlay represents live work.
     */
    data class RuntimeActivitySnapshot(
        val activeRootSessions: Set<String> = emptySet(),
        val activeChildSessions: Map<String, Set<String>> = emptyMap(),
        val waitingChildSessions: Map<String, Set<String>> = emptyMap(),
        val runtimeSessionCount: Int = 0,
        val hasRuntimeActivity: Boolean = false,
        val waitingForChildren: Boolean = false,
        val waitingText: String? = null,
    )

    private val _runtimeActivity = MutableStateFlow(RuntimeActivitySnapshot())
    val runtimeActivity: StateFlow<RuntimeActivitySnapshot> = _runtimeActivity.asStateFlow()

    // child id -> root session id. This keeps nested child work aggregated at
    // the root instead of inflating the notification count per implementation
    // detail.
    private val childRootIds = mutableMapOf<String, String>()
    private val waitingRootIds = mutableMapOf<String, String>()

    private val _currentToolStatus = MutableStateFlow("Idle")
    val currentToolStatus: StateFlow<String> = _currentToolStatus.asStateFlow()

    /**
     * [T-android-live-update-completed] `SystemClock.elapsedRealtime()` at the
     * moment the LAST active session finished, i.e. when [activeSessions] went
     * non-empty → empty. Null while anything is still streaming, and reset to
     * null by [setActive] so a fresh run never inherits a stale finish stamp.
     *
     * Why this exists: the foreground service keeps running after the task ends
     * (the user is still *present* in the chat — see [shouldRunService]), so the
     * ongoing notification / Android 16 Live Update chip stays on screen. Its
     * elapsed timer was computed from the SERVICE start time, which meant it
     * kept ticking up long after the agent stopped working — users reported the
     * dynamic island showing a running task that had already completed.
     *
     * With this stamp the notification can freeze the timer at the real task
     * duration and swap in a completed icon + label. Mirrors iOS
     * `AgentActivityAttributes.ContentState.finishedAt`, which drives the same
     * "static total run time" resting state there.
     */
    private val _lastTaskFinishedAtMs = MutableStateFlow<Long?>(null)
    val lastTaskFinishedAtMs: StateFlow<Long?> = _lastTaskFinishedAtMs.asStateFlow()

    /**
     * [T-android-live-update-completed] `SystemClock.elapsedRealtime()` at the
     * moment the CURRENT run began — i.e. when [activeSessions] went empty →
     * non-empty. Null while nothing is running.
     *
     * The foreground service's own `startTimeMs` is stamped once in onCreate and
     * covers the whole *presence* window (the service outlives individual tasks),
     * so it answers "how long have you been in this chat", not "how long did this
     * task take". Anchoring the notification's elapsed time here instead makes
     * the displayed duration mean the run, and makes consecutive runs in one
     * sitting each start from zero rather than accumulating.
     */
    private val _currentRunStartedAtMs = MutableStateFlow<Long?>(null)
    val currentRunStartedAtMs: StateFlow<Long?> = _currentRunStartedAtMs.asStateFlow()

    /**
     * T-bg-overlay phase 1: tool name currently dispatched to the agent
     * (e.g. "shell_execute", "browser_use"). null when no tool is in
     * flight (idle, or between tool calls within a turn). The FGS
     * notification reads this to render a tool-specific icon + display
     * label without parsing [currentToolStatus]'s freeform string.
     */
    private val _currentToolName = MutableStateFlow<String?>(null)
    val currentToolName: StateFlow<String?> = _currentToolName.asStateFlow()

    /**
     * [T-android-overlay-tool-title] Model-supplied `tool_title` for the
     * tool currently in flight (e.g. "Open Baidu home page", "Take screenshot of
     * current page"). Null when the model didn't supply one OR no tool is
     * running. The overlay capsule and notification prefer this over the
     * static per-tool label ("Browser", "Shell", …) so users see the
     * actual intent of the call rather than just the tool kind.
     *
     * Populated from the dispatch loop in [com.openminis.app.ui.chat.ChatViewModel]
     * by reading the `tool_title` arg uniformly for ALL tools — so
     * browser_use (which has no per-tool status override) surfaces the
     * title alongside shell_execute and friends.
     */
    private val _currentToolTitle = MutableStateFlow<String?>(null)
    val currentToolTitle: StateFlow<String?> = _currentToolTitle.asStateFlow()

    /**
     * T-bg-overlay phase 1: true while a tool call is actively executing
     * (between dispatch and result). Drives the notification's
     * indeterminate progress bar so the user can tell at a glance whether
     * Minis is "between turns" (false → no progress) vs "doing something"
     * (true → spinning bar).
     */
    private val _isToolRunning = MutableStateFlow(false)
    val isToolRunning: StateFlow<Boolean> = _isToolRunning.asStateFlow()

    /**
     * T-overlay-glyph-typed-outcome: typed outcome of the most recently
     * completed tool call. Replaces the old text-sniffing heuristic in
     * [ToolOverlayController] (which read stale "Running: foo" status
     * text and always inferred success). Set by [clearToolRunning];
     * defaults to [ToolOutcome.Unknown] until any tool finishes.
     */
    private val _lastToolOutcome = MutableStateFlow(ToolOutcome.Unknown)
    val lastToolOutcome: StateFlow<ToolOutcome> = _lastToolOutcome.asStateFlow()

    /**
     * Snapshot of the most recently completed tool's identity + status
     * line. Captured by [clearToolRunning] right before the live
     * [currentToolName] / [currentToolTitle] / [currentToolStatus] are
     * wiped, so the floating overlay can surface what the agent just did
     * after the run ends (e.g. "browser_use — Completed" + "Opened Google.com
     * in system Chrome") instead of a bare "Done". Cleared on
     * [dismissOverlay] and on [setActive] so a fresh run starts blank.
     */
    private val _lastToolName = MutableStateFlow<String?>(null)
    val lastToolName: StateFlow<String?> = _lastToolName.asStateFlow()

    private val _lastToolTitle = MutableStateFlow<String?>(null)
    val lastToolTitle: StateFlow<String?> = _lastToolTitle.asStateFlow()

    private val _lastToolStatus = MutableStateFlow<String?>(null)
    val lastToolStatus: StateFlow<String?> = _lastToolStatus.asStateFlow()

    /**
     * [T-android-overlay-reply-status-34599] Truncated excerpt of the
     * most recent assistant reply for the currently-tracked session.
     * Published by ChatViewModel via [publishLastReply] right before
     * [setInactive] so the overlay can show "what did Minis just say".
     * Null when no reply has been observed yet this session-cycle;
     * cleared when a fresh session goes active (so the previous
     * session's reply doesn't bleed into a newly-started turn).
     */
    private val _lastReplyExcerpt = MutableStateFlow<String?>(null)
    val lastReplyExcerpt: StateFlow<String?> = _lastReplyExcerpt.asStateFlow()

    /**
     * [T-android-overlay-reply-status-34599] Session ID associated with
     * [lastReplyExcerpt] and the current activity. Drives the
     * "tap-overlay → open chat" intent in [ToolOverlayController] by
     * synthesising a `minis://session/<id>` deep-link the existing
     * DeepLinkHandler already understands.
     */
    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    /** Max chars of the assistant reply we surface in the overlay. */
    private const val REPLY_EXCERPT_MAX = 72

    /**
     * [T-android-overlay-hide-camera] True while the user has launched the
     * system camera (ACTION_IMAGE_CAPTURE) from inside Minis and we're
     * waiting on the ActivityResult callback. The overlay observer in
     * [AgentForegroundService] gates `shouldShow` on this flag so the
     * floating capsule doesn't obstruct the camera viewfinder — Minis is
     * technically backgrounded during the capture (the camera Activity is
     * on top), which would otherwise satisfy the bg-only show rule from
     * #451. Cleared in the camera launcher's result callback (success,
     * cancel, or launch-failure) so a fresh bg event after the user
     * returns reactivates the overlay normally.
     */
    private val _cameraSuppressActive = MutableStateFlow(false)
    val cameraSuppressActive: StateFlow<Boolean> = _cameraSuppressActive.asStateFlow()

    fun setCameraSuppressActive(active: Boolean) {
        _cameraSuppressActive.value = active
    }

    private var appContext: Context? = null
    private var runtimeCoordinator: RuntimeSessionCoordinator? = null

    /** Runtime work, not UI presence, determines whether the service stays alive. */
    private fun shouldRunService(): Boolean = _runtimeActivity.value.hasRuntimeActivity

    private fun rootFor(sessionId: String): String =
        childRootIds[sessionId] ?: sessionId

    private fun recomputeRuntimeActivity() {
        val previous = _runtimeActivity.value
        val activeChildren = _activeChildSessions.value
        val waitingChildren = _waitingChildSessions.value
        val activeChildRoots = activeChildren.flatMap { (parent, children) ->
            children.map { child -> childRootIds[child] ?: rootFor(parent) }
        }.toSet()
        val waitingRoots = waitingChildren.keys.map { sessionId ->
            waitingRootIds[sessionId] ?: rootFor(sessionId)
        }.toSet()
        val roots = _activeSessions.value + activeChildRoots + waitingRoots
        val waitingCount = waitingChildren.values.sumOf { it.size }
        val waitingText = if (waitingRoots.isEmpty()) {
            null
        } else if (waitingCount == 1) {
            "Waiting for sub-agent"
        } else {
            "Waiting for $waitingCount sub-agents"
        }
        _waitingChildStatusText.value = waitingText
        val next = RuntimeActivitySnapshot(
            activeRootSessions = _activeSessions.value,
            activeChildSessions = activeChildren,
            waitingChildSessions = waitingChildren,
            runtimeSessionCount = roots.size,
            hasRuntimeActivity = roots.isNotEmpty(),
            waitingForChildren = waitingRoots.isNotEmpty(),
            waitingText = waitingText,
        )
        _runtimeActivity.value = next
        if (!previous.hasRuntimeActivity && next.hasRuntimeActivity) {
            _lastTaskFinishedAtMs.value = null
            _currentRunStartedAtMs.value = SystemClock.elapsedRealtime()
        } else if (previous.hasRuntimeActivity && !next.hasRuntimeActivity) {
            _lastTaskFinishedAtMs.value = SystemClock.elapsedRealtime()
        }
    }

    private fun refreshServiceAfterRuntimeChange(wasActive: Boolean) {
        when {
            !shouldRunService() && wasActive -> stopService()
            shouldRunService() -> if (wasActive) updateService() else startServiceIfNeeded()
        }
    }

    /**
     * T50: per-session stream-cancel callbacks. Each ChatViewModel
     * registers its own [com.openminis.app.ui.chat.ChatViewModel.cancelStream]
     * here when [setActive] is called and unregisters in [setInactive].
     * The foreground service's notification "Stop" action calls
     * [cancelAllActiveStreams] which iterates this map — without it the
     * notification can only kill itself, leaving streamJobs running until
     * the OS reclaims the process. Held under the same Map lock as the
     * activeSessions flow so callers don't observe a transient state where
     * the session is "active" but has no canceller.
     */
    private val streamCancellers = mutableMapOf<String, () -> Unit>()

    /**
     * T180-bg-notif: per-session "task is finishing — was it cancelled?"
     * flag, set by [setInactive]'s callers via [setInactiveError] when
     * the streamJob unwinds because of an error (vs a clean completion).
     * Drives the success/error variant of the completion notification.
     * Cleared as soon as the listener has fired.
     */
    private val pendingErrorFlag = mutableSetOf<String>()

    /**
     * T180-bg-notif: completion listener. Wired in MinisApp.onCreate to
     * a [com.openminis.app.notification.BackgroundTaskNotifier] so when
     * an agent loop ends while the app is backgrounded, the user gets a
     * tap-to-open-session notification — mirrors iOS
     * `BackgroundKeepAliveManager.postBackgroundTaskNotification` (L274).
     *
     * Held as a single-slot setter rather than a list because there's
     * exactly one notifier per process. Keeping the tracker free of
     * direct ChatRepository / Notifier dependencies preserves its
     * "pure session-tracking" responsibility.
     */
    private var completionListener: ((sessionId: String, isError: Boolean) -> Unit)? = null

    /** Root streams that ended while child work was still live/waiting. */
    private val pendingTreeCompletions = mutableMapOf<String, Boolean>()

    fun setCompletionListener(listener: ((sessionId: String, isError: Boolean) -> Unit)?) {
        completionListener = listener
    }

    /**
     * [T-android-overlay-reply-status-34599] Push the most recent
     * assistant reply for [sessionId] into the overlay surface. The
     * text is collapsed to a single line and truncated to
     * [REPLY_EXCERPT_MAX] chars with an ellipsis when over budget so
     * the overlay capsule doesn't blow out horizontally. No-op when the
     * text is blank — we don't want to render an empty bubble that
     * looks like a UI bug.
     */
    fun publishLastReply(sessionId: String, fullText: String?) {
        val collapsed = fullText
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.joinToString(" ")
            ?.takeIf { it.isNotBlank() } ?: return
        val excerpt = if (collapsed.length > REPLY_EXCERPT_MAX) {
            collapsed.substring(0, REPLY_EXCERPT_MAX).trimEnd() + "…"
        } else {
            collapsed
        }
        _currentSessionId.value = sessionId
        _lastReplyExcerpt.value = excerpt
    }

    /**
     * [T-android-overlay-reply-status-34599] User explicitly dismissed
     * the floating overlay (X button or tap-to-open-chat). Clears the
     * lingered reply state so the overlay observer in
     * [AgentForegroundService] flips its `shouldShow` predicate to
     * false and pulls the view down. The session activity itself stays
     * untouched — the agent loop continues; the user just chose to
     * stop being notified about it.
     */
    fun dismissOverlay() {
        _lastReplyExcerpt.value = null
        _lastToolOutcome.value = ToolOutcome.Unknown
        _lastToolName.value = null
        _lastToolTitle.value = null
        _lastToolStatus.value = null
    }

    /**
     * Initialize with application context. Must be called once at app startup.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        runtimeCoordinator = runCatching {
            RuntimeSessionCoordinator.open(context.applicationContext)
        }.onFailure {
            Log.w(TAG, "Runtime tree restore failed: ${it.message}")
        }.getOrNull()
    }

    /**
     * Marks a session as active. Starts the foreground service if this is
     * the first active session. [onStop], when supplied, is the agent
     * loop's cancel callback — captured here so the notification's Stop
     * action can fan out to every running session.
     */
    fun setActive(sessionId: String, onStop: (() -> Unit)? = null) {
        val wasIdle = !shouldRunService()
        _activeSessions.value = _activeSessions.value + sessionId
        if (onStop != null) {
            synchronized(streamCancellers) { streamCancellers[sessionId] = onStop }
        }
        // [T-android-overlay-reply-status-34599] Track which session is
        // driving the overlay so the tap-to-open intent lands in the
        // right chat. Clear the previous reply excerpt so the user
        // doesn't briefly see a stale reply attached to a fresh run.
        _currentSessionId.value = sessionId
        _lastReplyExcerpt.value = null
        _lastToolName.value = null
        _lastToolTitle.value = null
        _lastToolStatus.value = null
        // A run starts on the empty runtime-tree -> non-empty edge. This is
        // deliberately not based on presentSessions or child count so a chat
        // screen cannot restart a completed timer.
        runtimeCoordinator?.startRoot(sessionId)
        recomputeRuntimeActivity()
        Log.d(TAG, "Session activated: $sessionId (roots: ${_activeSessions.value.size})")
        refreshServiceAfterRuntimeChange(wasIdle)
    }

    /**
     * Marks a session as inactive. Stops the foreground service if no
     * sessions remain active *and* the user is no longer present in any
     * chat.
     */
    fun setInactive(sessionId: String) {
        val wasActive = sessionId in _activeSessions.value
        val wasRuntimeActive = shouldRunService()
        _activeSessions.value = _activeSessions.value - sessionId
        synchronized(streamCancellers) { streamCancellers.remove(sessionId) }
        val wasError = synchronized(pendingErrorFlag) { pendingErrorFlag.remove(sessionId) }
        Log.d(TAG, "Session deactivated: $sessionId (roots: ${_activeSessions.value.size})")

        if (_activeSessions.value.isEmpty()) {
            _currentToolStatus.value = "Idle"
            _currentToolName.value = null
            _currentToolTitle.value = null
            _isToolRunning.value = false
            if (wasError) _lastToolOutcome.value = ToolOutcome.Error
        }
        recomputeRuntimeActivity()
        refreshServiceAfterRuntimeChange(wasRuntimeActive)
        // Child work may still be active after a root stream ends. Defer the
        // completion callback until the whole root tree is idle.
        if (wasActive) {
            val rootId = rootFor(sessionId)
            if (hasRuntimeActivityForRoot(rootId)) {
                runtimeCoordinator?.waitForChildren(sessionId)
                pendingTreeCompletions[rootId] =
                    (pendingTreeCompletions[rootId] == true) || wasError
            } else {
                runtimeCoordinator?.finishRoot(sessionId, wasError)
                completionListener?.invoke(sessionId, wasError)
            }
        }
    }

    /**
     * Start a delegated child without making it a second root in the chat UI.
     * The parent root is persisted even when the command came from an idle
     * composer, so the child has a durable tree parent and foreground activity.
     */
    fun beginDelegatedChild(
        parentSessionId: String,
        childSessionId: String,
        request: RuntimeDelegationRequest,
        model: RuntimeModelSnapshot,
        parentModel: RuntimeModelSnapshot = model,
    ): Boolean {
        require(parentSessionId.isNotBlank()) { "parentSessionId must not be blank" }
        require(childSessionId.isNotBlank()) { "childSessionId must not be blank" }
        val wasIdle = !shouldRunService()
        runtimeCoordinator?.startRoot(parentSessionId, parentModel)
        val started = runtimeCoordinator?.delegate(parentSessionId, childSessionId, request, model) == true
        if (!started) return false
        childRootIds[childSessionId] = rootFor(parentSessionId)
        val next = _activeChildSessions.value.toMutableMap()
        next[parentSessionId] = next[parentSessionId].orEmpty() + childSessionId
        _activeChildSessions.value = next
        recomputeRuntimeActivity()
        refreshServiceAfterRuntimeChange(wasIdle)
        return true
    }

    /** Finish a delegated child and close its temporary parent root when idle. */
    fun finishDelegatedChild(
        parentSessionId: String,
        childSessionId: String,
        failed: Boolean = false,
        report: RuntimeStopReport? = null,
    ) {
        val wasRuntimeActive = shouldRunService()
        runtimeCoordinator?.finishChild(childSessionId, failed, report)
        val next = _activeChildSessions.value.toMutableMap()
        next[parentSessionId]?.let { children ->
            val remaining = children - childSessionId
            if (remaining.isEmpty()) next.remove(parentSessionId) else next[parentSessionId] = remaining
        }
        _activeChildSessions.value = next
        childRootIds.remove(childSessionId)
        recomputeRuntimeActivity()
        refreshServiceAfterRuntimeChange(wasRuntimeActive)
        emitPendingCompletionIfIdle(rootFor(parentSessionId))
    }

    /**
     * Register a child as running under [parentSessionId]. Child ids remain
     * outside [activeSessions], so a child cannot alter the parent send state.
     * [rootSessionId] is optional for nested trees and defaults to the parent
     * root known to this tracker.
     */
    fun registerChild(
        parentSessionId: String,
        childSessionId: String,
        rootSessionId: String = rootFor(parentSessionId),
    ) {
        require(parentSessionId.isNotBlank()) { "parentSessionId must not be blank" }
        require(childSessionId.isNotBlank()) { "childSessionId must not be blank" }
        val wasIdle = !shouldRunService()
        childRootIds[childSessionId] = rootSessionId
        runtimeCoordinator?.startChild(parentSessionId, childSessionId)
        val next = _activeChildSessions.value.toMutableMap()
        val ids = next[parentSessionId].orEmpty().toMutableSet()
        ids.add(childSessionId)
        next[parentSessionId] = ids
        _activeChildSessions.value = next
        recomputeRuntimeActivity()
        refreshServiceAfterRuntimeChange(wasIdle)
    }

    /** Mark a child as no longer running without touching its parent root. */
    fun unregisterChild(parentSessionId: String, childSessionId: String) {
        val wasRuntimeActive = shouldRunService()
        val next = _activeChildSessions.value.toMutableMap()
        next[parentSessionId]?.let { currentIds ->
            val ids = currentIds.toMutableSet()
            ids.remove(childSessionId)
            if (ids.isEmpty()) next.remove(parentSessionId) else next[parentSessionId] = ids
        }
        _activeChildSessions.value = next
        runtimeCoordinator?.finishChild(childSessionId)
        childRootIds.remove(childSessionId)
        recomputeRuntimeActivity()
        refreshServiceAfterRuntimeChange(wasRuntimeActive)
        emitPendingCompletionIfIdle(rootFor(parentSessionId))
    }

    /**
     * Keep the root alive while it is explicitly waiting for child results.
     * Empty [childSessionIds] is allowed for callers that only know the parent;
     * the parent still exposes a single waiting state to notification surfaces.
     */
    fun setWaitingForChildren(
        parentSessionId: String,
        childSessionIds: Set<String> = emptySet(),
        rootSessionId: String = rootFor(parentSessionId),
    ) {
        require(parentSessionId.isNotBlank()) { "parentSessionId must not be blank" }
        val wasIdle = !shouldRunService()
        waitingRootIds[parentSessionId] = rootSessionId
        runtimeCoordinator?.waitForChildren(parentSessionId)
        _waitingChildSessions.value = _waitingChildSessions.value.toMutableMap().apply {
            put(parentSessionId, childSessionIds.toSet())
        }
        recomputeRuntimeActivity()
        refreshServiceAfterRuntimeChange(wasIdle)
    }

    /** Clear the explicit waiting state and allow a completed tree to stop FGS. */
    fun clearWaitingForChildren(parentSessionId: String) {
        if (parentSessionId !in _waitingChildSessions.value) return
        val wasRuntimeActive = shouldRunService()
        _waitingChildSessions.value = _waitingChildSessions.value.toMutableMap().apply {
            remove(parentSessionId)
        }
        val rootId = waitingRootIds.remove(parentSessionId) ?: rootFor(parentSessionId)
        runtimeCoordinator?.resume(parentSessionId)
        recomputeRuntimeActivity()
        refreshServiceAfterRuntimeChange(wasRuntimeActive)
        emitPendingCompletionIfIdle(rootId)
    }

    private fun hasRuntimeActivityForRoot(rootSessionId: String): Boolean {
        val activity = _runtimeActivity.value
        return rootSessionId in activity.activeRootSessions ||
            activity.activeChildSessions.any { (parent, children) ->
                children.any { child -> (childRootIds[child] ?: rootFor(parent)) == rootSessionId }
            } ||
            activity.waitingChildSessions.keys.any { parent ->
                (waitingRootIds[parent] ?: rootFor(parent)) == rootSessionId
            }
    }

    private fun emitPendingCompletionIfIdle(rootSessionId: String) {
        if (hasRuntimeActivityForRoot(rootSessionId)) return
        runtimeCoordinator?.finishRoot(rootSessionId)
        val error = pendingTreeCompletions.remove(rootSessionId) ?: return
        completionListener?.invoke(rootSessionId, error)
    }

    fun markStreamError(sessionId: String) {
        synchronized(pendingErrorFlag) { pendingErrorFlag.add(sessionId) }
    }

    /**
     * T166: records chat presence for UI/lifecycle consumers only. Presence
     * does not start or update the foreground service when no runtime work is
     * active; otherwise an idle chat could leave a stale RUNNING notification.
     */
    fun setPresent(sessionId: String) {
        if (sessionId in _presentSessions.value) return
        _presentSessions.value = _presentSessions.value + sessionId
        Log.d(TAG, "Presence set: $sessionId (present total: ${_presentSessions.value.size})")
        if (shouldRunService()) updateService()
    }

    /** Counterpart of [setPresent]; runtime activity remains independent. */
    fun setAbsent(sessionId: String) {
        if (sessionId !in _presentSessions.value) return
        _presentSessions.value = _presentSessions.value - sessionId
        Log.d(TAG, "Presence cleared: $sessionId (present total: ${_presentSessions.value.size})")
        if (shouldRunService()) updateService()
    }

    /** Clear UI presence markers without creating or stopping runtime work. */
    fun clearPresence() {
        if (_presentSessions.value.isEmpty()) return
        _presentSessions.value = emptySet()
        Log.d(TAG, "Presence cleared (all)")
        if (shouldRunService()) updateService()
    }

    /**
     * Invoke every registered stream-cancel callback. Called by
     * [AgentForegroundService] when the user taps the notification's
     * Stop action. Each VM's cancelStream() is responsible for ending
     * its streamJob + flipping canResume true (T13) so the user can
     * tap Resume later.
     *
     * Snapshot the map before iterating — the cancellers themselves
     * call back into [setInactive] which mutates [streamCancellers],
     * so iterating the live map would ConcurrentModificationException.
     */
    fun cancelAllActiveStreams() {
        val snapshot = synchronized(streamCancellers) { streamCancellers.values.toList() }
        Log.d(TAG, "cancelAllActiveStreams: dispatching to ${snapshot.size} session(s)")
        for (cancel in snapshot) {
            try {
                cancel()
            } catch (e: Exception) {
                Log.w(TAG, "stream canceller threw: ${e.message}")
            }
        }
    }

    /**
     * Returns whether a specific session is currently active.
     */
    fun isActive(sessionId: String): Boolean = sessionId in _activeSessions.value

    /**
     * Updates the current tool status displayed in the notification.
     * Legacy single-argument variant — leaves [currentToolName] and
     * [isToolRunning] untouched. New callers should prefer the overload
     * below so the notification can render a tool-specific icon and
     * progress indicator.
     */
    fun updateToolStatus(status: String) {
        _currentToolStatus.value = status
        if (_activeSessions.value.isNotEmpty()) {
            updateService()
        }
    }

    /**
     * T-bg-overlay phase 1: rich tool-status update. Pass [toolName] =
     * null + [isRunning] = false to clear (e.g. tool finished, between
     * turns). The FGS notification rebuilds when any of name / status /
     * running flag changes.
     */
    fun updateToolStatus(status: String, toolName: String?, isRunning: Boolean) {
        updateToolStatus(status, toolName, isRunning, toolTitle = null)
    }

    /**
     * [T-android-overlay-tool-title] Rich update that also carries the
     * model-supplied `tool_title`. When [toolTitle] is non-blank the
     * overlay label uses it directly (e.g. "Open Baidu home page") instead of the
     * static per-tool label ("Browser"). Pass null/blank to fall back to
     * the per-tool label (existing behavior).
     */
    fun updateToolStatus(status: String, toolName: String?, isRunning: Boolean, toolTitle: String?) {
        _currentToolStatus.value = status
        _currentToolName.value = toolName
        _currentToolTitle.value = toolTitle?.takeIf { it.isNotBlank() }
        _isToolRunning.value = isRunning
        // [T-overlay-glyph-typed-outcome] Starting a new tool clears the
        // previous outcome so the overlay glyph (which reads
        // lastToolOutcome) does not leak the prior result into the new
        // tool's "running" state.
        if (isRunning) _lastToolOutcome.value = ToolOutcome.Unknown
        if (_activeSessions.value.isNotEmpty()) {
            updateService()
        }
    }

    /**
     * T-bg-overlay phase 1: clear tool-running state without touching
     * [currentToolStatus]. Called when a tool block flips to
     * SUCCESS/FAILED/TIMEOUT/CANCELLED so the notification stops
     * showing an active progress bar.
     */
    fun clearToolRunning(outcome: ToolOutcome = ToolOutcome.Unknown) {
        if (!_isToolRunning.value && _currentToolName.value == null) return
        // Snapshot identity + status before wiping the live values so the
        // overlay's post-completion render has something concrete to show.
        // Only retain a status string when it's a real tool-status line
        // (not the "Idle" placeholder).
        _lastToolName.value = _currentToolName.value
        _lastToolTitle.value = _currentToolTitle.value
        _lastToolStatus.value = _currentToolStatus.value
            ?.takeIf { it.isNotBlank() && !it.equals("Idle", ignoreCase = true) }
        _currentToolName.value = null
        _currentToolTitle.value = null
        _isToolRunning.value = false
        _lastToolOutcome.value = outcome
        if (_activeSessions.value.isNotEmpty()) {
            updateService()
        }
    }

    private fun startServiceIfNeeded() {
        val context = appContext ?: run {
            Log.w(TAG, "Context not initialized, cannot start service")
            return
        }
        AgentForegroundService.startService(
            context,
            sessionCountForNotification(),
            statusForNotification(),
        )
    }

    private fun updateService() {
        val context = appContext ?: return
        AgentForegroundService.startService(
            context,
            sessionCountForNotification(),
            statusForNotification(),
        )
    }

    private fun sessionCountForNotification(): Int =
        _runtimeActivity.value.runtimeSessionCount.coerceAtLeast(1)

    /** Notification text is derived from runtime work, never from chat presence. */
    private fun statusForNotification(): String {
        val ctx = appContext
        val activity = _runtimeActivity.value
        if (activity.waitingForChildren) {
            return activity.waitingText ?: "Waiting for sub-agent"
        }
        val activeCount = activity.runtimeSessionCount
        if (activeCount <= 0) return "Idle"
        val tool = _currentToolStatus.value
        if (tool.isNotBlank() && !tool.equals("Idle", ignoreCase = true)) return tool
        return if (ctx != null) {
            if (activeCount == 1) {
                ctx.getString(com.openminis.app.R.string.notif_one_task_running)
            } else {
                ctx.getString(com.openminis.app.R.string.notif_n_tasks_running, activeCount)
            }
        } else {
            if (activeCount == 1) "1 task running" else "$activeCount tasks running"
        }
    }

    private fun stopService() {
        val context = appContext ?: run {
            Log.w(TAG, "Context not initialized, cannot stop service")
            return
        }
        AgentForegroundService.stopService(context)
        Log.d(TAG, "All sessions complete, service stopped")
    }
}
