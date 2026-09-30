package com.openminis.app.ui.chat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-auto-retry-progress] The automatic retry mechanism has to be
 * visible *while it runs*, not only once it gives up (request.md:
 * "我们的自动重试机制在运行的时候，界面上也要有对应的这样的提示").
 *
 * Before this change the only retry feedback on screen was the message-level
 * `InlineErrorBanner` carrying a hardcoded English
 * `"<reason> — retrying (2/5)…"`, and the two StateFlows the ViewModel
 * published for exactly this wait (`autoRetryAttempt`,
 * `autoRetryCountdown`) had no reader in the whole repository.
 *
 * The state → row mapping is pure, so it is pinned directly here. The wiring
 * itself (a Compose row plus three `collectAsState` reads) has no JVM seam in
 * this module — `testImplementation` is junit + coroutines-test + mockwebserver
 * + org.json, with no Robolectric and no compose-ui-test — so
 * [AutoRetryIndicatorWiringTest] asserts against the sources instead, the same
 * way `RestoreSourceEntriesTest` and `ChatExporterRuntimeTreeSourceTest`
 * already do. That weakness is real and stated: it cannot prove the row is
 * visible, but it does go red the moment the wiring is deleted.
 */
class AutoRetryIndicatorTest {

    @Test
    fun `no row when no retry is scheduled`() {
        assertNull(
            "a settled turn must not paint a retry row",
            AutoRetryUiState.of(attempt = 0, maxAttempts = 5, secondsRemaining = 0),
        )
    }

    @Test
    fun `no row between scheduling the retry and the first backoff tick`() {
        // The ViewModel publishes the attempt and the first countdown value in
        // two separate statements, and the same pair is left behind if a turn
        // is cancelled while the failure is being handled. Neither may paint
        // "retrying in 0 s".
        assertNull(AutoRetryUiState.of(attempt = 2, maxAttempts = 5, secondsRemaining = 0))
        assertNull(AutoRetryUiState.of(attempt = 2, maxAttempts = 5, secondsRemaining = -1))
    }

    @Test
    fun `the wait carries the attempt, the budget and the seconds left`() {
        val state = AutoRetryUiState.of(attempt = 2, maxAttempts = 5, secondsRemaining = 3)!!
        assertEquals(2, state.attempt)
        assertEquals(5, state.maxAttempts)
        assertEquals(3, state.secondsRemaining)
        assertTrue(state.showsAttemptCap)
    }

    @Test
    fun `an unlimited budget drops the cap instead of rendering a nonsense total`() {
        // `maxRetryAttempts` is -1 for the app's "Unlimited" setting.
        val state = AutoRetryUiState.of(attempt = 7, maxAttempts = -1, secondsRemaining = 1)!!
        assertFalse("'attempt 7 of -1' must never be rendered", state.showsAttemptCap)
    }

    @Test
    fun `a disabled retry budget is not a cap either`() {
        // maxAttempts == 0 means auto-retry is off, so no wait is ever
        // scheduled; if that state ever reached the row it must not read
        // "attempt 1 of 0".
        val state = AutoRetryUiState.of(attempt = 1, maxAttempts = 0, secondsRemaining = 2)!!
        assertFalse(state.showsAttemptCap)
    }
}

/**
 * The row is only useful if something actually mounts it. Compose wiring has
 * no JVM seam in this module, so these assertions read the sources — the
 * structural-test convention this repository already uses where behaviour
 * would otherwise go uncovered.
 *
 * What it proves: the chat screen consumes the retry state, feeds it into the
 * row, and both locales carry the copy. What it does NOT prove: that the row
 * lands on screen (layout, clipping, scroll position are untested here).
 */
class AutoRetryIndicatorWiringTest {

    @Test
    fun `chat screen mounts the retry row from the view model state`() {
        val screen = source("ui/chat/ChatScreen.kt")

        assertTrue(
            "ChatScreen must read the in-flight attempt",
            screen.contains("viewModel.autoRetryAttempt.collectAsState()"),
        )
        assertTrue(
            "ChatScreen must read the backoff countdown",
            screen.contains("viewModel.autoRetryCountdown.collectAsState()"),
        )
        assertTrue(
            "ChatScreen must read the retry budget",
            screen.contains("viewModel.autoRetryMaxAttempts.collectAsState()"),
        )
        assertTrue(
            "ChatScreen must build the row state from the three flows",
            screen.contains("AutoRetryUiState") && screen.contains(".of(autoRetryAttempt"),
        )
        assertTrue(
            "ChatScreen must render the row",
            screen.contains("AutoRetryIndicator("),
        )
    }

    @Test
    fun `view model publishes the wait and tears it down again`() {
        val vm = source("ui/chat/ChatViewModel.kt")

        assertTrue(
            "the budget that scheduled the wait must be published",
            vm.contains("_autoRetryMaxAttempts.value = retrySettings.maxRetryAttempts"),
        )
        assertTrue(
            "the countdown must still be published once a second",
            vm.contains("_autoRetryCountdown.value"),
        )
        assertTrue(
            "every exit path must clear the row (success, exhaustion, cancel)",
            vm.contains("resetAutoRetryState()"),
        )
        // One definition plus four call sites: the backoff wait's `finally`,
        // the success path, the exhaustion path, and the turn tail (which is
        // the only one that runs when a cancel lands inside the retry
        // handler). Fewer than four means one of them lost its teardown.
        val teardowns = Regex("resetAutoRetryState\\(\\)").findAll(vm).count()
        assertTrue(
            "expected 5 occurrences (1 definition + 4 teardowns), found $teardowns",
            teardowns == 5,
        )
        assertFalse(
            "the per-attempt line must not go back onto the message error field",
            vm.contains("setTransientInlineError("),
        )
    }

    @Test
    fun `the row is localized rather than hardcoded`() {
        val indicator = source("ui/chat/AutoRetryIndicator.kt")

        assertTrue(
            "bounded budget must use the resource",
            indicator.contains("R.string.chat_auto_retry_progress"),
        )
        assertTrue(
            "unlimited budget must use the resource",
            indicator.contains("R.string.chat_auto_retry_progress_unlimited"),
        )
    }

    @Test
    fun `default and Chinese locales both carry the copy`() {
        for (locale in listOf("values", "values-zh")) {
            val xml = resource("$locale/strings.xml")
            val text = xml.readText()
            assertTrue(
                "$locale is missing chat_auto_retry_progress",
                text.contains("name=\"chat_auto_retry_progress\""),
            )
            assertTrue(
                "$locale is missing chat_auto_retry_progress_unlimited",
                text.contains("name=\"chat_auto_retry_progress_unlimited\""),
            )
        }
    }
}

private fun source(relativePath: String): String {
    val f = File("src/main/java/com/openminis/app/$relativePath")
        .takeIf { it.isFile }
        ?: File("app/src/main/java/com/openminis/app/$relativePath")
    assertTrue("expected to find ${f.path} relative to the app module", f.isFile)
    return f.readText()
}

private fun resource(relativePath: String): File {
    val f = File("src/main/res/$relativePath").takeIf { it.isFile }
        ?: File("app/src/main/res/$relativePath")
    assertTrue("expected to find ${f.path} relative to the app module", f.isFile)
    return f
}
