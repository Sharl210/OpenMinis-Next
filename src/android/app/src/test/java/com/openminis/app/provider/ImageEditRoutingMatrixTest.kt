package com.openminis.app.provider

import com.openminis.app.data.model.ProviderType
import com.openminis.app.sandbox.offload.ImageRouteEndpoint
import com.openminis.app.sandbox.offload.imagesRouteEndpoint
import com.openminis.app.shared.KotlinSourceText
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-image-edit-endpoint] Decision-table coverage for which
 * minis-model-use calls reach /images/edits after image editing was wired up.
 *
 * ## Why this file calls production instead of restating it
 *
 * This class used to carry a `private fun route(...)` described in its own
 * comment as "transcribed line-for-line from the function". Every assertion
 * below ran against that copy, so the file could not have gone red if the
 * handler had relaxed the OAuth guard, dropped openRouter/xAI from the
 * allowlist, or started sending a mixed text+image model to /images/edits —
 * it pinned a *description* of the routing contract and called it protection.
 *
 * The gates are now `imagesRouteEndpoint` (with `isPureImageGeneratorModel`) in
 * the handler's own file, and `tryImageGenerationRoute` routes through them, so
 * the table below exercises the shipped decision. `[the handler routes through
 * the extracted gate]` is what keeps that true.
 *
 * Transport itself is covered for real (MockWebServer) in OpenAIEditImageTest.
 */
class ImageEditRoutingMatrixTest {

    private enum class Route {
        /** Images API, text-to-image. */
        GENERATE,

        /** Images API, image-to-image (the newly added path). */
        EDIT,

        /** Declines the images route → caller falls through to chat completions. */
        CHAT_FALLTHROUGH,
    }

    /**
     * The allowlist lives in production; here we only name the types the matrix
     * talks about. Anything else is resolved to a REAL constant outside the
     * allowlist (or to null when the name is not a provider type at all) — both
     * must decline.
     */
    private fun providerTypeOf(name: String): ProviderType? = when (name) {
        "openAI" -> ProviderType.openAI
        "openRouter" -> ProviderType.openRouter
        "xAI" -> ProviderType.xAI
        else -> ProviderType.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    /** Production's verdict, read through the same function the handler calls. */
    private fun route(
        outputs: Set<String>,
        inputImageCount: Int,
        isOpenAICompatibleProvider: Boolean = true,
        providerType: String = "openAI",
        isOAuth: Boolean = false,
    ): Route {
        val kind = imagesRouteEndpoint(
            outputs = outputs,
            isOpenAICompatibleProvider = isOpenAICompatibleProvider,
            providerType = providerTypeOf(providerType),
            isOAuthCredential = isOAuth,
            inputImageCount = inputImageCount,
        )
        return when (kind) {
            ImageRouteEndpoint.CHAT_FALLTHROUGH -> Route.CHAT_FALLTHROUGH
            ImageRouteEndpoint.GENERATIONS -> Route.GENERATE
            ImageRouteEndpoint.EDITS -> Route.EDIT
        }
    }

    // ── Forward: the bug being fixed ─────────────────────────────────────────

    @Test
    fun `pure image generator with one reference image routes to edits`() {
        // This exact combination previously returned image_edit_not_supported.
        assertEquals(Route.EDIT, route(outputs = setOf("image"), inputImageCount = 1))
    }

    @Test
    fun `pure image generator with multiple reference images routes to edits`() {
        assertEquals(Route.EDIT, route(outputs = setOf("image"), inputImageCount = 3))
    }

    @Test
    fun `openRouter and xAI image instances also reach edits`() {
        for (pt in listOf("openRouter", "xAI")) {
            assertEquals(
                "providerType=$pt should reach edits",
                Route.EDIT,
                route(outputs = setOf("image"), inputImageCount = 1, providerType = pt),
            )
        }
    }

    // ── Reverse: previously-working behavior must be untouched ───────────────

    @Test
    fun `pure image generator without reference images still routes to generations`() {
        assertEquals(Route.GENERATE, route(outputs = setOf("image"), inputImageCount = 0))
    }

    @Test
    fun `mixed text and image model with reference image falls through to chat`() {
        // Must NOT be taken over by the new edit path — chat forwards the image
        // to the model, which is the right channel for a model that talks back.
        assertEquals(
            Route.CHAT_FALLTHROUGH,
            route(outputs = setOf("text", "image"), inputImageCount = 1),
        )
    }

    @Test
    fun `mixed text and image model without reference image still uses images route`() {
        assertEquals(
            Route.GENERATE,
            route(outputs = setOf("text", "image"), inputImageCount = 0),
        )
    }

    @Test
    fun `text-only model with reference image is untouched by the image route`() {
        // e.g. GPT-5.5 as the catalog actually declares it: output = [text].
        assertEquals(Route.CHAT_FALLTHROUGH, route(outputs = setOf("text"), inputImageCount = 1))
    }

    @Test
    fun `oauth instance with reference image never reaches edits`() {
        // Codex OAuth tokens lack the images scope — routing them at /images/edits
        // would 401. The OAuth guard must still short-circuit first.
        assertEquals(
            Route.CHAT_FALLTHROUGH,
            route(outputs = setOf("image"), inputImageCount = 1, isOAuth = true),
        )
    }

    @Test
    fun `non openai-compatible provider with reference image is unaffected`() {
        assertEquals(
            Route.CHAT_FALLTHROUGH,
            route(
                outputs = setOf("image"),
                inputImageCount = 1,
                isOpenAICompatibleProvider = false,
            ),
        )
        // Also covers an OpenAI-shaped provider object on a non-allowlisted type.
        assertEquals(
            Route.CHAT_FALLTHROUGH,
            route(outputs = setOf("image"), inputImageCount = 1, providerType = "anthropic"),
        )
    }

    // ── Properties a transcribed table cannot be asked ───────────────────────

    private val allowlisted =
        listOf(ProviderType.openAI, ProviderType.openRouter, ProviderType.xAI)

    /**
     * The invariant over the WHOLE input space, instead of over the cases someone
     * remembered to list: whichever guard combination is fed in, the images route
     * is EDITS exactly when input images are present, and GENERATIONS exactly
     * when they are not — for a model that can only generate images.
     *
     * A transcription enumerates eight hand-picked points and cannot be asked
     * about the 120th; this sweep covers outputs × image count × compatibility ×
     * credential × provider type, so a guard that is ORDER-dependent (e.g. one
     * that only checks "is there an image" after the OAuth guard) shows up.
     */
    @Test
    fun `over the whole input space the images route edits exactly when images are present`() {
        val outside = ProviderType.entries.firstOrNull { it !in allowlisted }
        val types: List<ProviderType?> = allowlisted + listOf(outside, null)
        val outputSets = listOf(setOf("image"), setOf("text"), setOf("image", "text"), emptySet<String>())

        var edits = 0
        var generations = 0
        for (outputs in outputSets) {
            for (images in 0..2) {
                for (compatible in listOf(true, false)) {
                    for (oauth in listOf(true, false)) {
                        for (type in types) {
                            val at = "outputs=$outputs images=$images compatible=$compatible " +
                                "oauth=$oauth providerType=${type?.name}"
                            val kind = imagesRouteEndpoint(outputs, compatible, type, oauth, images)
                            when (kind) {
                                ImageRouteEndpoint.EDITS -> {
                                    edits++
                                    assertTrue("$at — EDITS without input images", images > 0)
                                    assertTrue("$at — EDITS for a model that talks back", "text" !in outputs)
                                    assertTrue("$at — EDITS for an OpenAI-incompatible provider", compatible)
                                    assertTrue("$at — EDITS on a non-allowlisted type", type in allowlisted)
                                    assertFalse("$at — EDITS through an OAuth credential", oauth)
                                }

                                ImageRouteEndpoint.GENERATIONS -> {
                                    generations++
                                    assertEquals("$at — GENERATIONS with input images", 0, images)
                                    assertTrue("$at — GENERATIONS for an OpenAI-incompatible provider", compatible)
                                    assertTrue("$at — GENERATIONS on a non-allowlisted type", type in allowlisted)
                                    assertFalse("$at — GENERATIONS through an OAuth credential", oauth)
                                }

                                ImageRouteEndpoint.CHAT_FALLTHROUGH -> Unit
                            }

                            // The declared contract, restated as a predicate: a
                            // pure image generator on an allowlisted, non-OAuth,
                            // OpenAI-compatible instance ALWAYS takes the images
                            // route, and its endpoint follows the input images.
                            val reachable = compatible && type in allowlisted && !oauth && "image" in outputs
                            if (reachable) {
                                val expected = when {
                                    images > 0 && "text" in outputs -> ImageRouteEndpoint.CHAT_FALLTHROUGH
                                    images > 0 -> ImageRouteEndpoint.EDITS
                                    else -> ImageRouteEndpoint.GENERATIONS
                                }
                                assertEquals(at, expected, kind)
                            }
                        }
                    }
                }
            }
        }
        assertTrue("the sweep must reach both endpoints", edits > 0 && generations > 0)
    }

    /**
     * A copy of the gate in a test file cannot be asked this: the handler must
     * reach the SAME function the tests drive, and must not re-derive the rules
     * inline. Without it the extraction could be reverted and this whole file
     * would quietly stop describing the shipped routing again.
     */
    @Test
    fun `the handler routes through the extracted gate`() {
        val source = source("sandbox/offload/ModelUseOffloadHandler.kt")
        val body = KotlinSourceText.bracedBlock(source, "tryImageGenerationRoute")
            ?: throw AssertionError("tryImageGenerationRoute not found in ModelUseOffloadHandler.kt")
        assertTrue(
            "tryImageGenerationRoute must decide through imagesRouteEndpoint(...)",
            body.contains("imagesRouteEndpoint("),
        )
        assertTrue(
            "…and must pick the images endpoint from that same verdict",
            body.contains("ImageRouteEndpoint.EDITS"),
        )
        assertFalse(
            "the inline pure-generator test came back — that copy is the defect",
            body.contains("\"image\" in outputs && \"text\" !in outputs"),
        )
    }

    private fun source(relativePath: String): String =
        sequenceOf(
            File("app/src/main/java/com/openminis/app/$relativePath"),
            File("src/main/java/com/openminis/app/$relativePath"),
        ).firstOrNull { it.isFile }?.readText()
            ?: error("cannot locate $relativePath from ${File(".").absolutePath}")
}
