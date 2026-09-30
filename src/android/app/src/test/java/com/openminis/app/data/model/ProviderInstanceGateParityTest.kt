package com.openminis.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-provider-type-parity] Two spellings of the same provider must get
 * the same verdict from every per-type gate.
 *
 * `.openAI` with `useResponsesAPI = true` and `ProviderType.openAIResponses` are
 * the SAME provider — iOS spells the pair `forceResponsesAPI` on `.openAI`, and
 * this app models the type as exactly that flag forced on. So every gate that
 * asks "what can this provider do?" has to answer identically for the two, or a
 * user who restored an iOS package gets behaviour that depends only on which
 * spelling their instance happens to carry.
 *
 * That is what happened: the type was added, and two gates kept excluding it
 * with a KDoc that said "Android has no `openAIResponses` type" — a claim that
 * was true when it was written and was never revisited. The gates now include it
 * and this test is the invariant that keeps the two spellings from drifting
 * apart again: it asserts the PAIR, not either side alone, so a future edit that
 * touches one spelling and not the other turns red here.
 *
 * [ProviderInstance.supportsAzureMode] is deliberately NOT part of the aligned
 * set — see the last test, which pins the known gap so that closing it is a
 * deliberate, visible change rather than a silent one.
 */
class ProviderInstanceGateParityTest {

    private fun instance(
        type: ProviderType,
        credential: ProviderCredential = ProviderCredential.apiKey,
        baseURL: String? = "https://relay.example.com/v1",
        useResponsesAPI: Boolean = false,
    ) = ProviderInstance(
        id = "test-${type.name}",
        label = "Test ${type.name}",
        providerType = type,
        credentialType = credential,
        customBaseURL = baseURL,
        useResponsesAPI = useResponsesAPI,
    )

    /** The `.openAI` spelling of what `openAIResponses` means. */
    private fun openAIWithResponsesFlag(
        credential: ProviderCredential = ProviderCredential.apiKey,
        baseURL: String? = "https://relay.example.com/v1",
    ) = instance(ProviderType.openAI, credential, baseURL, useResponsesAPI = true)

    private fun openAIResponses(
        credential: ProviderCredential = ProviderCredential.apiKey,
        baseURL: String? = "https://relay.example.com/v1",
    ) = instance(ProviderType.openAIResponses, credential, baseURL)

    // ─── the invariant ───────────────────────────────────────────────────

    @Test
    fun `empty-key validity is the same for both spellings`() {
        // The field case: a keyless self-hosted relay restored from iOS.
        assertEquals(
            "the Responses spelling must not be judged misconfigured while the flag spelling is fine",
            openAIWithResponsesFlag().allowsEmptyAPIKey,
            openAIResponses().allowsEmptyAPIKey,
        )
        assertTrue(
            "a custom-base Responses instance with no stored key is a valid configuration",
            openAIResponses().allowsEmptyAPIKey,
        )
    }

    @Test
    fun `image-endpoint picker visibility is the same for both spellings`() {
        assertEquals(
            "the picker must not depend on which spelling the instance carries",
            openAIWithResponsesFlag().supportsImageEndpointSetting,
            openAIResponses().supportsImageEndpointSetting,
        )
        assertTrue(openAIResponses().supportsImageEndpointSetting)
    }

    // ─── the guard that keeps the widening safe ──────────────────────────
    //
    // Both gates only ever widen within "third-party compatible endpoint"
    // configurations; the official-host guard is a different clause, and these
    // pin that it did not move.

    @Test
    fun `an official endpoint still never accepts an empty key, in either spelling`() {
        assertEquals(
            "no custom base URL → both spellings must still require a key",
            openAIWithResponsesFlag(baseURL = null).allowsEmptyAPIKey,
            openAIResponses(baseURL = null).allowsEmptyAPIKey,
        )
        assertFalse(openAIResponses(baseURL = null).allowsEmptyAPIKey)
        assertFalse(
            "a blank base URL counts as no custom base URL",
            openAIResponses(baseURL = "   ").allowsEmptyAPIKey,
        )
    }

    @Test
    fun `OAuth instances never accept an empty key, in either spelling`() {
        assertEquals(
            openAIWithResponsesFlag(credential = ProviderCredential.oauth).allowsEmptyAPIKey,
            openAIResponses(credential = ProviderCredential.oauth).allowsEmptyAPIKey,
        )
        assertFalse(openAIResponses(credential = ProviderCredential.oauth).allowsEmptyAPIKey)
    }

    /**
     * The empty-key type gate, enumerated. Adding a type here by accident (e.g.
     * "OpenAI-compatible, so probably fine") is what would send an
     * unauthenticated request to a vendor that does not accept one.
     */
    @Test
    fun `only the OpenAI-family and Anthropic types may be configured without a key`() {
        val allowed = setOf(
            ProviderType.openAI,
            ProviderType.openAIResponses,
            ProviderType.anthropic,
        )
        for (type in ProviderType.entries) {
            assertEquals(
                "empty-key validity of $type",
                type in allowed,
                instance(type).allowsEmptyAPIKey,
            )
        }
    }

    /**
     * The image-endpoint picker's type gate, enumerated — the three
     * OpenAI-compatible types plus the Responses spelling of one of them.
     */
    @Test
    fun `the image-endpoint picker is offered only for OpenAI-compatible types`() {
        val offered = setOf(
            ProviderType.openAI,
            ProviderType.openAIResponses,
            ProviderType.openRouter,
            ProviderType.xAI,
        )
        for (type in ProviderType.entries) {
            assertEquals(
                "image-endpoint picker for $type",
                type in offered,
                instance(type).supportsImageEndpointSetting,
            )
        }
    }

    // ─── the gap that is NOT closed ──────────────────────────────────────

    /**
     * Pinned on purpose, in the failing direction: the Azure toggle is still
     * `.openAI`-only, so an iOS-restored `openAIResponses` instance does not
     * get it even though `ProviderFactory` drives that branch identically. The
     * KDoc on `supportsAzureMode` states the same thing and gives the reason
     * (Azure's deployments-path routing on a Responses instance has not been
     * exercised against a real endpoint).
     *
     * If someone closes the gap, this test goes red and they must delete it
     * with the verification in hand — which is the point: the difference stays
     * a recorded decision instead of decaying into an inconsistency.
     */
    @Test
    fun `the Azure toggle is still OpenAI-only, and that difference is deliberate`() {
        assertEquals(
            "the flag spelling keeps the Azure toggle",
            true,
            openAIWithResponsesFlag().supportsAzureMode,
        )
        assertEquals(
            "the Responses spelling does NOT — see the KDoc: unverified against a real Azure endpoint",
            false,
            openAIResponses().supportsAzureMode,
        )
    }
}
