package com.openminis.app.data

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wiring tripwires for the "one concept, one judgement" rule.
 *
 * ## What these are, and what they are NOT
 *
 * These are TEXT assertions (source-reading) and are labelled as such: they read
 * the production files off disk and assert that a decision has exactly one
 * implementation and that the duplicated copies have not come back. They are not
 * behaviour tests and they must never be described as proving behaviour — the
 * behaviour of each decision is pinned in the test that can execute it
 * ([com.openminis.app.data.repository.VisionGroupGateTest],
 * `RcloneBackendCatalogFieldsTest`, `RestoreProviderTypeToleranceTest`,
 * `ContextOffloadDecisionTest`).
 *
 * They exist because the residue of this defect class is a *copy that no runtime
 * test can reach*:
 *   - `RcloneDestinationsViewModel` is an AndroidViewModel (Context + RcloneBridge
 *     native), so its inline copy of the secret-key table could only ever be
 *     caught by reading the file;
 *   - a guard clause inside `ProviderFactory.create` cannot be mutated by a test
 *     without a harness, but "the guard is gone" is exactly the regression to
 *     watch for (that is how `ProviderType.isUsable` became a getter nothing
 *     called);
 *   - `ProviderRepository`'s old second Vision-Group rule was a two-line
 *     re-implementation; the tripwire is what keeps it from being pasted back.
 *
 * A failure here means "look at the wiring", not "the behaviour broke".
 */
class SingleJudgementWiringTest {

    private fun source(relativePath: String): String =
        sequenceOf(
            File("app/src/main/java/com/openminis/app/$relativePath"),
            File("src/main/java/com/openminis/app/$relativePath"),
        ).firstOrNull { it.isFile }?.readText()
            ?: error("cannot locate $relativePath from ${File(".").absolutePath}")

    // ─── provider type usability ─────────────────────────────────────────

    /**
     * `ProviderType.isUsable` must have a production reader: it is the single
     * source for "this build cannot drive this type", and before the guard was
     * added the classification lived only in a `when` arm inside the factory
     * while the getter itself was called by nothing but tests.
     */
    @Test
    fun `the factory decides drivability by asking isUsable`() {
        val factory = source("provider/ProviderFactory.kt")
        assertTrue(
            "ProviderFactory.create must refuse !isUsable before building anything — otherwise " +
                "isUsable is a getter with no production reader again",
            factory.contains("if (!instance.providerType.isUsable)"),
        )
    }

    // ─── rclone secret key ───────────────────────────────────────────────

    /**
     * The secret-key name was written out three times: the catalog's declared
     * field, the store's mapping, and an inline "s3 vs everything else" guess at
     * two call sites in the destinations ViewModel. Only the catalog may hold it.
     */
    @Test
    fun `the rclone secret key name is not restated at the call sites`() {
        val vm = source("ui/settings/backup/RcloneDestinationsViewModel.kt")
        assertTrue(
            "the destinations ViewModel must ask the store which field holds the secret",
            vm.contains("RcloneRemoteStore.secretKeyFor("),
        )
        assertFalse(
            "an inline 's3 vs the rest' secret-key table came back — that copy is the defect",
            vm.contains("if (backend == \"s3\")") || vm.contains("if (b.remote.backend == \"s3\")"),
        )
        assertFalse(
            "the ViewModel must not name a backend's secret parameter at all",
            vm.contains("\"secret_access_key\""),
        )
    }

    @Test
    fun `the store delegates the secret key name to the catalog`() {
        val store = source("backup/remote/RcloneRemoteStore.kt")
        // The expression body only: the next function in the companion legitimately
        // mentions "s3" (secretNeedsObscuring), and reading past this one would make
        // the assertions below about the wrong code.
        // `""` on purpose: `substringAfter` with no sentinel returns the ENTIRE
        // file when the delimiter is absent, so the slice would quietly become
        // "the first three lines of the file" — package plus imports — and the
        // assertions below would be measuring nothing. With the sentinel a missing
        // marker yields an empty `body` and a red test.
        val body = store.substringAfter("fun secretKeyFor(backend: String): String", "")
            .lines().take(3).joinToString("\n")
        assertTrue(
            "secretKeyFor must read the catalog's declared secret field, got: ${body.trim().take(200)}",
            body.contains("RcloneBackendCatalog.secretField(backend)"),
        )
        assertTrue(
            "a backend outside the catalog still needs a key name (its secret must reach the " +
                "encrypted store rather than being dropped)",
            body.contains("?: \"pass\""),
        )
        assertFalse(
            "secretKeyFor must not re-list a backend's secret parameter",
            body.contains("\"s3\"") || body.contains("\"secret_access_key\""),
        )
    }

    // ─── vision group gate ───────────────────────────────────────────────

    /**
     * One verdict, three names — `ProviderRepository.hasVisionGroupConfigured`,
     * `ProviderRepository.resolveVisionCandidates` and
     * `VisionGroupResolver.isConfigured` — and the middle one is the only
     * implementation. The repository used to carry a second, weaker rule ("a
     * group is bound and still exists") that would have advertised `read_image`
     * for a group that can serve nothing.
     */
    @Test
    fun `the vision group gate has exactly one implementation`() {
        val resolver = source("tools/VisionGroupResolver.kt")
        val repo = source("data/repository/ProviderRepository.kt")

        assertTrue(
            "VisionGroupResolver.isConfigured must delegate to the repository's verdict",
            resolver.contains("repo.hasVisionGroupConfigured()"),
        )
        assertTrue(
            "ProviderRepository.hasVisionGroupConfigured must delegate to the candidate resolution",
            repo.contains("fun hasVisionGroupConfigured(): Boolean = resolveVisionCandidates().isNotEmpty()"),
        )
        assertTrue(
            "the pure decision must exist as its own function so it can be tested without a Context",
            repo.contains("internal fun resolveVisionCandidatesIn("),
        )
        assertFalse(
            "the weak copy ('a group is bound and still exists') came back — it answers true for a " +
                "group whose members are all disabled, dangling or non-vision",
            repo.contains("return _config.value.modelGroups.any { it.id == gid }"),
        )
    }
}
