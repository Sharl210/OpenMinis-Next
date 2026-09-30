package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMResponse
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.feature.runtime.AgentRetryRunner
import com.openminis.app.provider.LLMProvider
import com.openminis.app.ui.chat.ChatViewModel.Companion.callCompactModelWithRetry
import com.openminis.app.ui.chat.ChatViewModel.Companion.callTitleModelWithRetry
import com.openminis.app.ui.chat.ChatViewModel.Companion.compactionInnerRetryFailure
import com.openminis.app.ui.chat.ChatViewModel.Companion.COMPACT_TIMEOUT_MAX_MS
import com.openminis.app.ui.chat.ChatViewModel.Companion.generateTitleUnderDeadline
import com.openminis.app.ui.chat.ChatViewModel.Companion.isRetryStopSignal
import com.openminis.app.ui.chat.ChatViewModel.Companion.TITLE_GEN_TIMEOUT_MS
import com.openminis.app.ui.settings.AgentBehaviorSettings
import com.openminis.app.ui.settings.agentRetryRunnerFromSettings
import com.openminis.app.ui.settings.agentRuntimeConfigFromSettings
import com.openminis.app.ui.settings.effectiveMaxRetryAttempts
import com.openminis.app.ui.settings.retryBudgetTotalAttempts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * [T-android-retry-governs-title-and-compaction] The user's automatic-retry
 * setting must actually govern session-title generation and context
 * compaction, not just the agent main loop.
 *
 * The requirement behind this file, verbatim from
 * `plans/ULW-2026-09-25-01/request.md:120`:
 *
 *   「压缩上下文和标题生成，他们都是要纳入自动重试的这个管控范围的……直接按
 *     我们的那个用户所设置的那个值，进行一个自动重试机制的重试」
 *
 * What the tests therefore have to PROVE is the call COUNT: a provider that
 * keeps failing must be called exactly `maxRetryAttempts + 1` times (one first
 * try plus the user's retry budget), one time when automatic retry is off or
 * the budget is 0, and more than any fixed cap when the budget is `-1`.
 *
 * The calls go through the real production entry points
 * ([callTitleModelWithRetry], [callCompactModelWithRetry]) and the real
 * settings → policy mapping, so a regression in either the wiring or the
 * mapping turns these red.
 */
class RetrySettingGovernsTitleAndCompactionTest {

    // ── Core acceptance: the user's value is the call count ─────────────

    @Test
    fun `title generation calls the provider maxRetryAttempts plus one times`() = runTest {
        val provider = CountingProvider()
        val failure = runCatching {
            callTitleModelWithRetry(
                provider = provider,
                prompt = "prompt",
                systemPrompt = "system",
                maxTokens = 100,
                runner = runnerFor(settings = autoRetry(maxRetryAttempts = 2)),
            )
        }.exceptionOrNull()

        assertNotNull("a permanently failing provider must surface its failure", failure)
        assertEquals("first try + 2 retries", 3, provider.calls.get())
    }

    @Test
    fun `title generation without a retry budget calls the provider once`() = runTest {
        val provider = CountingProvider()
        runCatching {
            callTitleModelWithRetry(provider, "prompt", "system", 100, runnerFor(autoRetry(maxRetryAttempts = 0)))
        }

        assertEquals(1, provider.calls.get())
    }

    @Test
    fun `title generation does not retry when the user switched auto retry off`() = runTest {
        val provider = CountingProvider()
        runCatching {
            callTitleModelWithRetry(
                provider,
                "prompt",
                "system",
                100,
                // A large positive budget, but the switch is off: the switch wins.
                runnerFor(settings = AgentBehaviorSettings(autoRetryEnabled = false, maxRetryAttempts = 150)),
            )
        }

        assertEquals("auto-retry off must be equivalent to maxAttempts = 0", 1, provider.calls.get())
    }

    @Test
    fun `title generation with always mode keeps going past any fixed cap`() = runTest {
        // Succeed only on the 6th call: the old hardcoded TITLE_MAX_ATTEMPTS = 3
        // could never have reached this, so passing proves there is no fixed
        // ceiling left on the title path.
        val provider = CountingProvider(succeedAfter = 5)
        val response = callTitleModelWithRetry(
            provider,
            "prompt",
            "system",
            100,
            runnerFor(autoRetry(maxRetryAttempts = -1)),
        )

        assertEquals(6, provider.calls.get())
        assertEquals("ok", response.text)
    }

    @Test
    fun `compaction calls the provider maxRetryAttempts plus one times`() = runTest {
        val provider = CountingProvider()
        val failure = runCatching {
            callCompactModelWithRetry(
                provider = provider,
                userMessage = "compact this",
                systemPrompt = "system",
                maxTokens = 1024,
                runner = runnerFor(autoRetry(maxRetryAttempts = 2)),
            )
        }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals("first try + 2 retries", 3, provider.calls.get())
    }

    @Test
    fun `compaction without a retry budget calls the provider once`() = runTest {
        val provider = CountingProvider()
        runCatching {
            callCompactModelWithRetry(provider, "compact this", "system", 1024, runnerFor(autoRetry(maxRetryAttempts = 0)))
        }

        assertEquals(1, provider.calls.get())
    }

    @Test
    fun `compaction does not retry when the user switched auto retry off`() = runTest {
        val provider = CountingProvider()
        runCatching {
            callCompactModelWithRetry(
                provider,
                "compact this",
                "system",
                1024,
                runnerFor(AgentBehaviorSettings(autoRetryEnabled = false, maxRetryAttempts = 150)),
            )
        }

        assertEquals(1, provider.calls.get())
    }

    @Test
    fun `compaction with always mode keeps going past any fixed cap`() = runTest {
        val provider = CountingProvider(succeedAfter = 4)
        val summary = callCompactModelWithRetry(
            provider,
            "compact this",
            "system",
            1024,
            runnerFor(autoRetry(maxRetryAttempts = -1)),
        )

        assertEquals("a fixed cap of 3 could not have reached the 5th call", 5, provider.calls.get())
        assertEquals("ok", summary.text)
    }

    // ── Layering: retry budget vs halving degradation ───────────────────

    @Test
    fun `a size caused failure is handed to the halving layer instead of being retried`() = runTest {
        // An over-length refusal, untyped — exactly what the halving layer
        // exists for. Even with `-1` (always) the inner loop must not repeat it,
        // or compaction would spin on an identical oversized request.
        val provider = CountingProvider(
            failure = { LLMError.ProviderError("context length exceeded") },
        )
        runCatching {
            callCompactModelWithRetry(
                provider,
                "compact this",
                "system",
                1024,
                runnerFor(autoRetry(maxRetryAttempts = -1)),
            )
        }

        assertEquals(1, provider.calls.get())
    }

    @Test
    fun `a transport failure arriving as a provider error is still retried`() = runTest {
        // shouldSplitOnError cannot tell this apart from an over-length
        // refusal, so the retry vocabulary decides: a 5xx is a retry, not a
        // reason to send a smaller request into a dead server.
        val provider = CountingProvider(
            failure = { LLMError.ProviderError("503 Service Unavailable") },
        )
        runCatching {
            callCompactModelWithRetry(
                provider,
                "compact this",
                "system",
                1024,
                runnerFor(autoRetry(maxRetryAttempts = 2)),
            )
        }

        assertEquals(3, provider.calls.get())
    }

    @Test
    fun `the compaction inner gate refuses size failures and accepts transport failures`() {
        val runner = runnerFor(autoRetry(maxRetryAttempts = 2))

        assertNull(
            "size-caused failures belong to the halving layer",
            compactionInnerRetryFailure(LLMError.ProviderError("context length exceeded"), runner),
        )
        assertNotNull(
            "transport failures belong to the retry loop",
            compactionInnerRetryFailure(LLMError.TransientError("502 Bad Gateway"), runner),
        )
        assertNotNull(
            "a 5xx wrapped in a provider error is transport, not size",
            compactionInnerRetryFailure(LLMError.ProviderError("503 Service Unavailable"), runner),
        )
    }

    @Test
    fun `cancellation is never retried by either path`() = runBlocking {
        val cancelled = CountingProvider(failure = { CancellationException("user stopped it") })
        val titleCalls = runCatching {
            callTitleModelWithRetry(cancelled, "prompt", "system", 100, runnerFor(autoRetry(maxRetryAttempts = -1)))
        }
        assertEquals(1, cancelled.calls.get())
        assertTrue(titleCalls.exceptionOrNull() is CancellationException)

        val cancelledCompact = CountingProvider(failure = { CancellationException("user stopped it") })
        val compactCalls = runCatching {
            callCompactModelWithRetry(
                cancelledCompact,
                "compact this",
                "system",
                1024,
                runnerFor(autoRetry(maxRetryAttempts = -1)),
            )
        }
        assertEquals(1, cancelledCompact.calls.get())
        assertTrue(compactCalls.exceptionOrNull() is CancellationException)
    }

    @Test
    fun `always mode is not a blanket retry - an explicit stop-retry signal still stops`() = runTest {
        // Pins the `-1` semantics both paths now inherit: unlimited for ordinary
        // failures, but auth/quota/"do not retry" signals are honoured.
        val provider = CountingProvider(failure = { LLMError.InvalidApiKey("bad key") })
        runCatching {
            callTitleModelWithRetry(provider, "prompt", "system", 100, runnerFor(autoRetry(maxRetryAttempts = -1)))
        }

        assertEquals(1, provider.calls.get())
    }

    @Test
    fun `a size caused failure keeps the halving layer even when its wording looks transient`() {
        // REGRESSION GUARD for the exact defect a first cut of
        // compactionInnerRetryFailure shipped: `shouldSplitOnError(...) &&
        // !runner.isRetryable(...)`. shouldSplitOnError returns TRUE for every
        // untyped ProviderError (how an over-length refusal normally arrives),
        // but runtimeFailureFor folds the provider text into the message, where
        // AgentRetryPolicy's retry keywords then match — so "…too large, please
        // try again" was considered retryable, the && went false, and the
        // oversized request was retried verbatim. Under the shipped default
        // (-1, always) the run's wall clock then fired before the halving layer
        // ran, and compaction failed where it used to rescue itself.
        val unbounded = runnerFor(autoRetry(maxRetryAttempts = -1))
        val bounded = runnerFor(autoRetry(maxRetryAttempts = 2))

        val sizePhrases = listOf(
            "context length exceeded",
            "your input exceeds the context window of this model",
            "request too large: please reduce and try again",
            "too many tokens for this model — try again",
            "prompt is too long, timeout-ish wording follows: timeout",
            "content is too long, supported temporarily unavailable",
        )
        for (phrase in sizePhrases) {
            val error = LLMError.ProviderError(phrase)
            assertNull(
                "a size-caused failure must go to halving, not to the retry loop: '$phrase'",
                compactionInnerRetryFailure(error, unbounded),
            )
            assertNull(
                "…and that must not depend on the user's budget: '$phrase'",
                compactionInnerRetryFailure(error, bounded),
            )
        }
    }

    @Test
    fun `an unbounded budget never starves the halving layer`() {
        // Untyped and ambiguous: the evidence cannot separate "too big" from
        // "server wobbled". Under -1 the inner loop would spend the whole run's
        // wall clock on it, and a cancellation is NOT caught by
        // generateCompactSummaryWithSplitting — so the halving layer would never
        // run at all. Ambiguity therefore resolves to halving when the budget is
        // unbounded, and to the retry loop when it is bounded (where an
        // exhausted budget still throws into the halving layer's own catch).
        val ambiguous = LLMError.ProviderError("upstream failure")
        assertNull(
            "unbounded: hand the ambiguous failure to halving",
            compactionInnerRetryFailure(ambiguous, runnerFor(autoRetry(maxRetryAttempts = -1))),
        )
        assertNotNull(
            "bounded: honour the user's retries first — halving still gets the throw afterwards",
            compactionInnerRetryFailure(ambiguous, runnerFor(autoRetry(maxRetryAttempts = 2))),
        )
    }

    @Test
    fun `a cancelled request is never retried even under always mode`() = runBlocking {
        // LLMError.Cancelled is an ordinary Exception (not a coroutine
        // CancellationException) whose message matches no stop-retry keyword, so
        // without an explicit stop signal `-1` would retry a user's cancel
        // forever.
        val provider = CountingProvider(failure = { LLMError.Cancelled() })
        val outcome = runCatching {
            callCompactModelWithRetry(
                provider,
                "compact this",
                "system",
                1024,
                runnerFor(autoRetry(maxRetryAttempts = -1)),
            )
        }

        assertTrue(outcome.exceptionOrNull() is LLMError.Cancelled)
        assertEquals("a cancel must stop immediately, not retry", 1, provider.calls.get())

        assertTrue(
            "LLMError.Cancelled is an ordinary Exception, so it needs its own stop signal",
            isRetryStopSignal(LLMError.Cancelled()),
        )
        assertFalse(isRetryStopSignal(LLMError.TransientError("503")))
        assertFalse(isRetryStopSignal(LLMError.ProviderError("boom")))
    }

    @Test
    fun `a cancelled title request is never retried even under always mode`() = runBlocking {
        val provider = CountingProvider(failure = { LLMError.Cancelled() })
        val outcome = runCatching {
            callTitleModelWithRetry(provider, "prompt", "system", 100, runnerFor(autoRetry(maxRetryAttempts = -1)))
        }

        assertTrue(outcome.exceptionOrNull() is LLMError.Cancelled)
        assertEquals(1, provider.calls.get())
    }

    @Test
    fun `the size phrase list has exactly one definition`() {
        val source = chatViewModelSource()
        val occurrences = Regex("""desc\.contains\("too many tokens"\)""").findAll(source).count()

        assertEquals(
            "a second copy of the size phrase list is the 'one concept, two definitions' defect",
            1,
            occurrences,
        )
        assertTrue(
            "isContextTooLargeError must delegate rather than re-list the phrases",
            source.contains("private fun isContextTooLargeError(error: Throwable): Boolean =\n        isSizeCausedFailure(error)"),
        )
    }

    // ── The wall clock that keeps an unbounded budget finite ────────────
    //
    // The shipped default is `autoRetryEnabled = true, maxRetryAttempts = -1`,
    // so "unbounded title retry" is the CONFIGURATION USERS ACTUALLY RUN, not a
    // hypothetical. Compaction bounds the same situation with `withTimeout`
    // (see COMPACT_TIMEOUT_MAX_MS); these tests hold title generation to the
    // same standard.

    @Test
    fun `the wall clock ends an always-retry title dispatch instead of retrying forever`() = runTest {
        // Real backoff (not the no-op sleep the other tests use) so the virtual
        // clock actually advances.
        val provider = CountingProvider(failure = { LLMError.TransientError("503 Service Unavailable") })
        val response = generateTitleUnderDeadline(
            provider = provider,
            prompt = "prompt",
            systemPrompt = "system",
            maxTokens = 100,
            runner = agentRetryRunnerFromSettings(autoRetry(maxRetryAttempts = -1)),
            timeoutMs = TITLE_GEN_TIMEOUT_MS,
        )

        assertNull("the dispatch must be abandoned, not answered", response)
        assertTrue(
            "it must keep retrying for a while (the user said always); calls=${provider.calls.get()}",
            provider.calls.get() > 1,
        )
        assertTrue(
            "and it must NOT be unbounded — the wall clock ends it; calls=${provider.calls.get()}",
            provider.calls.get() < 200,
        )
    }

    @Test
    fun `the wall clock is observable - hitting it reports the deadline`() = runTest {
        val events = mutableListOf<Long>()
        val response = generateTitleUnderDeadline(
            provider = CountingProvider(failure = { LLMError.TransientError("500") }),
            prompt = "prompt",
            systemPrompt = "system",
            maxTokens = 100,
            runner = agentRetryRunnerFromSettings(autoRetry(maxRetryAttempts = -1)),
            timeoutMs = TITLE_GEN_TIMEOUT_MS,
            onDeadline = { events += it },
        )

        assertNull(response)
        assertEquals(
            "a deadline that abandons the request silently is the 'swallowed failure' defect",
            listOf(TITLE_GEN_TIMEOUT_MS),
            events,
        )
    }

    @Test
    fun `the wall clock never shortens a bounded budget the user configured`() = runTest {
        // Complement of the test above: with a finite budget the retries must
        // run to completion even though their backoff adds real time — the
        // deadline must not cancel a retry the user asked for.
        //
        // The exhausted budget still THROWS (the wrapper only turns the timeout
        // into null), which is what lets the caller's own catch apply the
        // first-message fallback.
        val provider = CountingProvider()
        val outcome = runCatching {
            generateTitleUnderDeadline(
                provider = provider,
                prompt = "prompt",
                systemPrompt = "system",
                maxTokens = 100,
                runner = agentRetryRunnerFromSettings(autoRetry(maxRetryAttempts = 2)),
                timeoutMs = TITLE_GEN_TIMEOUT_MS,
                onDeadline = { error("a bounded budget must finish inside the wall clock") },
            )
        }

        assertTrue(
            "an exhausted budget must surface its failure, not be masked as a deadline",
            outcome.exceptionOrNull() is LLMError.TransientError,
        )
        assertEquals("first try + 2 retries, all inside the wall clock", 3, provider.calls.get())
    }

    @Test
    fun `a successful title dispatch reports no deadline`() = runTest {
        val events = mutableListOf<Long>()
        val response = generateTitleUnderDeadline(
            provider = CountingProvider(succeedAfter = 0),
            prompt = "prompt",
            systemPrompt = "system",
            maxTokens = 100,
            runner = agentRetryRunnerFromSettings(autoRetry(maxRetryAttempts = 2)),
            timeoutMs = TITLE_GEN_TIMEOUT_MS,
            onDeadline = { events += it },
        )

        assertEquals("ok", response?.text)
        assertTrue("no deadline may be reported on success", events.isEmpty())
    }

    @Test
    fun `the title wall clock is finite and sized above a real title request`() {
        assertTrue(
            "the wall clock must be a real bound; an effectively-infinite value is the defect it fixes",
            TITLE_GEN_TIMEOUT_MS in 1..COMPACT_TIMEOUT_MAX_MS,
        )
        // A real title request measured 22-51s (see the GH#210 comment on
        // applyFallbackTitleFromFirstMessage), so the bound has to clear one
        // full call plus backoff plus one retry. Anything under that would
        // silently cancel a retry the user explicitly configured.
        assertTrue(
            "TITLE_GEN_TIMEOUT_MS=$TITLE_GEN_TIMEOUT_MS must clear call+backoff+call",
            TITLE_GEN_TIMEOUT_MS >= 110_000L,
        )
    }

    @Test
    fun `an abandoned title dispatch lands the first-message fallback`() {
        val source = chatViewModelSource()

        assertTrue(
            "the title call must go through the bounded wrapper",
            source.contains("generateTitleUnderDeadline("),
        )
        assertTrue(
            "an abandoned dispatch must be observable, not silent",
            source.contains("outcome=deadline-exceeded"),
        )
        // Wiring, not mere presence: an abandoned dispatch that writes nothing
        // would leave the session on "New Chat".
        val nullBranch = source.indexOf("if (response == null) {")
        assertTrue("the wrapper's null branch must exist", nullBranch >= 0)
        val branch = source.substring(nullBranch, source.indexOf("} else {", nullBranch))
        assertTrue(
            "the deadline branch must call the existing first-message fallback",
            branch.contains("applyFallbackTitleFromFirstMessage("),
        )
    }

    // ── Counter-proof: what the wall clock is actually holding back ─────
    //
    // This is the falsification of "the wall clock is load-bearing", expressed
    // as a green test rather than as a temporary edit to production code.
    //
    // The shared worktree makes a probe edit in ChatViewModel.kt unacceptably
    // expensive: a build window here runs 10-20 minutes (19 concurrent
    // gradle lock waiters at the time of writing), so a "wall clock removed"
    // probe would sit in app/src/main/ long enough for the concurrently running
    // audit agents to read it as the current implementation — the exact
    // contamination that made a sibling agent misdiagnose red tests three
    // times, and the reason it now does falsification in a /tmp harness.
    //
    // What CAN be shown without touching production, and is stronger anyway:
    // hand `generateTitleUnderDeadline` an effectively infinite deadline and
    // watch the always-retry dispatch fail to terminate. That establishes the
    // causal link directly — the deadline is the only thing that ends it — and
    // it pins the mechanism permanently instead of for the duration of one
    // probe. If someone ever removes the wall clock from the production default,
    // `the wall clock ends an always-retry title dispatch instead of retrying
    // forever` (which passes `TITLE_GEN_TIMEOUT_MS` itself) has nothing left to
    // terminate it and turns red.

    @Test
    fun `without a wall clock an always-retry dispatch does not terminate`() = runBlocking {
        val provider = CountingProvider(failure = { LLMError.TransientError("503 Service Unavailable") })
        // The harness's own bound stands in for "there is no wall clock": if the
        // dispatch were self-limiting, this bias would never be needed.
        val outcome = withTimeoutOrNull(3_000L) {
            generateTitleUnderDeadline(
                provider = provider,
                prompt = "prompt",
                systemPrompt = "system",
                maxTokens = 100,
                runner = agentRetryRunnerFromSettings(autoRetry(maxRetryAttempts = -1)),
                timeoutMs = Long.MAX_VALUE,
            )
        }

        assertNull(
            "with no wall clock the always-retry dispatch never returns; " +
                "the harness had to cut it off",
            outcome,
        )
        assertTrue(
            "and it was genuinely retrying, not stuck before the first call; calls=${provider.calls.get()}",
            provider.calls.get() > 1,
        )
    }

    // ── Regression net: the limit comes from settings, not a hardcoded 3 ─

    @Test
    fun `the title attempt limit is derived from the setting, not from the old hardcoded 3`() {
        assertEquals(8, retryBudgetTotalAttempts(autoRetry(maxRetryAttempts = 7).effectiveMaxRetryAttempts))
        assertEquals(1, retryBudgetTotalAttempts(autoRetry(maxRetryAttempts = 0).effectiveMaxRetryAttempts))
        assertEquals(
            Int.MAX_VALUE,
            retryBudgetTotalAttempts(autoRetry(maxRetryAttempts = -1).effectiveMaxRetryAttempts),
        )
        // A user asking for 2 retries must not silently get the old "3".
        assertEquals(3, retryBudgetTotalAttempts(autoRetry(maxRetryAttempts = 2).effectiveMaxRetryAttempts))
        assertEquals(
            "the value tracks the setting, so it cannot be the constant 3",
            12,
            retryBudgetTotalAttempts(autoRetry(maxRetryAttempts = 11).effectiveMaxRetryAttempts),
        )
    }

    @Test
    fun `the title dispatch cap is read from the shared settings mapping`() {
        val source = chatViewModelSource()
        val declarationStart = source.indexOf("private val titleMaxAttempts: Int")
        assertTrue("titleMaxAttempts must exist", declarationStart >= 0)
        val declaration = source.substring(declarationStart, source.indexOf("// [T-android-auto-grouping-injection]", declarationStart))

        assertTrue(
            "the dispatch cap must be derived from the user's setting",
            declaration.contains("retryBudgetTotalAttempts("),
        )
        assertTrue(
            "…and from the same settings object the retry policy uses",
            declaration.contains("effectiveMaxRetryAttempts"),
        )
    }

    @Test
    fun `no retry limit is hardcoded on the title path any more`() {
        val source = chatViewModelSource()

        assertFalse(
            "TITLE_MAX_ATTEMPTS = 3 is the defect this change removes",
            source.contains("TITLE_MAX_ATTEMPTS"),
        )
    }

    @Test
    fun `all three governed call sites share one settings to policy mapping`() {
        val source = chatViewModelSource()

        assertTrue(
            "the agent main loop builds its policy from the shared mapping",
            source.contains("agentRetryPolicyFromSettings(retrySettings)"),
        )
        assertTrue(
            "title generation and compaction build their runner from the shared mapping",
            source.contains("agentRetryRunnerFromSettings("),
        )
        assertFalse(
            "a second hand-built AgentRetryPolicy in ChatViewModel would be a second definition",
            source.contains("AgentRetryPolicy("),
        )
        assertFalse(
            "a second hand-built AgentRuntimeConfig in ChatViewModel would be a second definition",
            source.contains("AgentRuntimeConfig("),
        )

        // Behavioural half of the same claim: one settings object, one budget,
        // whatever the call site asks for.
        val enabled = AgentBehaviorSettings(autoRetryEnabled = true, maxRetryAttempts = 5)
        assertEquals(5, agentRuntimeConfigFromSettings(enabled).maxAttempts)
        assertEquals(5, enabled.effectiveMaxRetryAttempts)
        assertEquals(
            "both paths read the same field, so their budgets cannot diverge",
            agentRuntimeConfigFromSettings(enabled).maxAttempts,
            enabled.effectiveMaxRetryAttempts,
        )
        val disabled = enabled.copy(autoRetryEnabled = false)
        assertEquals("the off switch folds to 0 in exactly one place", 0, agentRuntimeConfigFromSettings(disabled).maxAttempts)
        assertEquals(0, disabled.effectiveMaxRetryAttempts)
    }

    @Test
    fun `both governed paths reject a provider failure the policy will not retry`() = runTest {
        // Guards the shared vocabulary: an auth failure must not be retried by
        // either path at a bounded budget (it is the policy, not each call
        // site, that decides).
        val titleProvider = CountingProvider(failure = { LLMError.InvalidApiKey("bad key") })
        runCatching {
            callTitleModelWithRetry(titleProvider, "prompt", "system", 100, runnerFor(autoRetry(maxRetryAttempts = 150)))
        }
        assertEquals(1, titleProvider.calls.get())

        val compactProvider = CountingProvider(failure = { LLMError.InvalidApiKey("bad key") })
        runCatching {
            callCompactModelWithRetry(compactProvider, "compact this", "system", 1024, runnerFor(autoRetry(maxRetryAttempts = 150)))
        }
        assertEquals(1, compactProvider.calls.get())
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun autoRetry(maxRetryAttempts: Int) =
        AgentBehaviorSettings(autoRetryEnabled = true, maxRetryAttempts = maxRetryAttempts)

    /** Production mapping, with the backoff skipped so tests do not sleep. */
    private fun runnerFor(settings: AgentBehaviorSettings): AgentRetryRunner =
        agentRetryRunnerFromSettings(settings, sleep = {}, onRetry = { _, _, _ -> })

    private fun chatViewModelSource(): String {
        val candidates = listOf(
            File("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt"),
            File("app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt"),
        )
        return candidates.firstOrNull { it.isFile }?.takeIf { it.length() > 0 }?.readText()
            ?: error("ChatViewModel.kt not found from ${File(".").absolutePath}")
    }
}

/**
 * Counts provider calls. Fails until [succeedAfter] calls have happened, so a
 * test can read the retry budget straight off the counter.
 */
private class CountingProvider(
    private val succeedAfter: Int = Int.MAX_VALUE,
    private val failure: () -> Throwable = { LLMError.TransientError("503 Service Unavailable") },
) : LLMProvider {

    override val name: String = "counting-fake"

    override var model: LLMModel = LLMModel(id = "fake-1", displayName = "Fake", provider = "fake")

    val calls = AtomicInteger(0)

    override suspend fun sendMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): LLMResponse {
        val attempt = calls.incrementAndGet()
        if (attempt > succeedAfter) return LLMResponse(text = "ok", stopReason = "stop", usage = null)
        throw failure()
    }

    override fun streamMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): Flow<LLMStreamChunk> = flowOf()
}
