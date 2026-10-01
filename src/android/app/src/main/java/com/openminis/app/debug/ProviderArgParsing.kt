package com.openminis.app.debug

import com.openminis.app.data.model.FallbackStrategy
import com.openminis.app.data.model.RoutingStrategy

/**
 * [T-android-provider-arg-parse] Wire-string → enum parsing for the `provider.*`
 * and `provider.groups.*` RPC methods.
 *
 * These two live here, top-level and with no Android imports, so a plain JVM test
 * can drive the real table. They were `private` members of
 * [ProviderMutationMethods], which meant the only way to reach them was through a
 * Context, a live [com.openminis.app.data.repository.ProviderRepository] and a
 * JSONObject payload — so in practice nobody did, and the alias table below had
 * no test references at all.
 *
 * **This is not the same contract as `RoutingStrategy.decoded` / `FallbackStrategy.decoded`.**
 * Those read values that this build itself persisted, so an unrecognised string
 * means "written by another build" and the safe landing is the mirror's default.
 * These read strings a *caller* passed in — the debug RPC surface — where an
 * unrecognised name is a caller mistake, and the table also accepts spellings that
 * are never persisted (`noFallback`, `no_fallback`). Keeping them separate is
 * deliberate; do not merge them.
 *
 * Lenient by design: an unknown string falls back instead of throwing, because the
 * RPC surface is reachable from tooling that may be older or newer than this build.
 * `parseFallbackStrategyArg` narrows to [FallbackStrategy.default] rather than
 * widening to `always` — retrying on auth failures is not something an
 * unrecognised argument should silently opt the user into.
 */
internal fun parseStrategy(s: String): RoutingStrategy = when (s) {
    "none", "noFallback", "no_fallback" -> RoutingStrategy.none
    "loadBalance" -> RoutingStrategy.loadBalance
    else -> RoutingStrategy.fallback
}

internal fun parseFallback(s: String): FallbackStrategy = when (s) {
    "always" -> FallbackStrategy.always
    else -> FallbackStrategy.default
}
