package com.openminis.app.debug

import com.openminis.app.data.model.FallbackStrategy
import com.openminis.app.data.model.RoutingStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * [T-android-provider-arg-parse] The wire-string → enum tables behind the
 * `provider.*` / `provider.groups.*` RPC methods, driven against the real
 * functions.
 *
 * ## Why this file exists
 *
 * Both functions were `private` members of `ProviderMutationMethods`, so the only
 * way to reach them was through a Context, a live `ProviderRepository` and a
 * `JSONObject` payload — in practice nobody did, and the alias table below had no
 * test references at all. They are now top-level and free of Android imports, so a
 * plain JVM test can drive them.
 *
 * ## These are NOT the same contract as `RoutingStrategy.decoded`
 *
 * `decoded()` reads a value **this build itself persisted**, so an unrecognised
 * string means "written by another build" and the safe landing is whatever the JSON
 * mirror produces for the same bytes. These read a string a **caller passed in**,
 * where an unrecognised name is a caller mistake, and the table additionally
 * accepts spellings that are never persisted (`noFallback`, `no_fallback`).
 *
 * The two tables therefore disagree on those two spellings *by design*:
 * `parseStrategy("no_fallback")` is `none`, while `RoutingStrategy.decoded("no_fallback")`
 * is `fallback`. That divergence is asserted below, so that "these look like the
 * same thing, let us merge them" goes red instead of silently changing what a
 * persisted value means.
 */
class ProviderArgParsingTest {

    // ─── parseStrategy: the alias table ─────────────────────────────────────

    @Test
    fun `every documented spelling of no-fallback maps to none`() {
        // Three spellings for one intent: the canonical name plus the two forms
        // callers were observed to send. Dropping either alias silently turns a
        // "pin this provider" request into "rotate freely".
        for (spelling in listOf("none", "noFallback", "no_fallback")) {
            assertEquals(
                "\"$spelling\" must mean the user pinned the member",
                RoutingStrategy.none,
                parseStrategy(spelling),
            )
        }
    }

    @Test
    fun `the canonical names map to themselves`() {
        assertEquals(RoutingStrategy.fallback, parseStrategy("fallback"))
        assertEquals(RoutingStrategy.loadBalance, parseStrategy("loadBalance"))
    }

    @Test
    fun `an unrecognised strategy falls back rather than throwing`() {
        // The RPC surface is reachable from tooling that may be older or newer than
        // this build, so an unknown name must not take the call down.
        assertEquals(RoutingStrategy.fallback, parseStrategy("priority"))
        assertEquals(RoutingStrategy.fallback, parseStrategy(""))
        assertEquals(RoutingStrategy.fallback, parseStrategy("   "))
        assertEquals(RoutingStrategy.fallback, parseStrategy("None"))
    }

    @Test
    fun `strategy parsing is case-sensitive, and that is the contract`() {
        // Pinned because it is easy to "fix" by lowercasing everything, which would
        // also make `NONE` mean `none` — a spelling no client sends and no
        // documentation promises. If case-insensitivity is ever wanted, that is a
        // deliberate contract change and this assertion is where it should be
        // reconsidered.
        assertEquals(RoutingStrategy.fallback, parseStrategy("LoadBalance"))
        assertEquals(RoutingStrategy.none, parseStrategy("none"))
    }

    // ─── parseFallback: the direction that matters ──────────────────────────

    @Test
    fun `the canonical fallback names map to themselves`() {
        assertEquals(FallbackStrategy.always, parseFallback("always"))
        assertEquals(FallbackStrategy.default, parseFallback("default"))
    }

    /**
     * The direction is the whole point. `always` retries on **any** error,
     * including auth failures and bad requests; `default` retries only on rate
     * limiting and 5xx. An unrecognised argument must not silently opt the user
     * into the wider rule — that turns a typo into retry storms against a provider
     * that will never accept the request.
     */
    @Test
    fun `an unrecognised fallback narrows to default and never widens to always`() {
        for (junk in listOf("", " ", "ALWAYS", "aggressive", "yes", "true", "1")) {
            assertEquals(
                "\"$junk\" must not be read as `always`",
                FallbackStrategy.default,
                parseFallback(junk),
            )
            assertNotEquals(FallbackStrategy.always, parseFallback(junk))
        }
    }

    // ─── the two tables must stay distinct ─────────────────────────────────

    /**
     * `decoded()` is the persisted-data reader; `parseStrategy` is the
     * caller-argument reader. They overlap on the canonical names and diverge on
     * the aliases, which is correct — but only as long as nobody assumes they are
     * interchangeable. Asserting the divergence makes a future merge fail loudly
     * rather than quietly reinterpreting rows already on disk.
     */
    @Test
    fun `the alias spellings mean different things to the argument table and the persisted reader`() {
        assertEquals(
            "the argument table accepts the no-fallback aliases",
            RoutingStrategy.none,
            parseStrategy("no_fallback"),
        )
        assertEquals(
            "the persisted reader does not — it only knows canonical names, so an " +
                "alias in a stored row is an unknown value and lands on the default",
            RoutingStrategy.fallback,
            RoutingStrategy.decoded("no_fallback"),
        )
    }

    // ─── reachability of every enum constant ───────────────────────────────

    /**
     * A constant added to [RoutingStrategy] would be returned by neither table, so
     * it could never be selected through the RPC surface — silently unreachable
     * rather than obviously missing. This asserts reachability from the tables
     * themselves, so adding a case without teaching the parser about it goes red.
     */
    @Test
    fun `every routing strategy constant is reachable from the argument table`() {
        val inputs = listOf("none", "noFallback", "no_fallback", "fallback", "loadBalance")
        val reachable = inputs.map(::parseStrategy).toSet()

        assertEquals(
            "every RoutingStrategy constant must be selectable from some argument — " +
                "otherwise it exists in the enum and nowhere in the RPC surface",
            RoutingStrategy.entries.toSet(),
            reachable,
        )
    }

    @Test
    fun `every fallback strategy constant is reachable from the argument table`() {
        val inputs = listOf("always", "default")
        val reachable = inputs.map(::parseFallback).toSet()

        assertEquals(FallbackStrategy.entries.toSet(), reachable)
    }
}
