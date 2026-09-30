package com.openminis.app.identity

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-next-identity] The Next fork ships a different applicationId from
 * upstream (`com.openminis.next` vs `com.openminis.app`), so both builds can be
 * installed side by side on one device instead of fighting over
 * `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.
 *
 * Requirement (request.md:154, verbatim): "由于我们也把这个应用改名了，所以，对应的应用中的
 * 一些老旧的一些，不管是 logo 还是标识，就是原有的旧标识都要改成我们这个新版的，包括里面的
 * 包名也是一样的，都改一下".
 *
 * WHY THIS TEST EXISTS. The applicationId is not written in one place. Gradle owns
 * it, but the platform requires the *same* identity to be repeated as a literal
 * string in files that Gradle cannot substitute:
 *
 *  - `res/xml/shortcuts.xml` — `android:targetPackage` is a raw package name, not a
 *    manifest placeholder, so it cannot be `${applicationId}`.
 *  - `AndroidManifest.xml` — the custom `…action.OPEN_WEBAPP` action literal, which is
 *    also mirrored in `WebAppActivity.ACTION_OPEN_WEBAPP`.
 *
 * Every one of those copies is silent when it drifts: the app compiles, installs and
 * launches, and the only symptom is that a long-pressed app icon quietly does nothing
 * or that a pinned shortcut cannot be resolved. No compiler and no existing test in
 * this repo can see it, which is exactly why it is asserted here.
 *
 * THE TRAP THIS TEST ALSO GUARDS: `android:targetPackage` and `android:targetClass`
 * are NOT two spellings of the same thing.
 *
 *  - `targetPackage` is the **applicationId** — the installed package identity.
 *  - `targetClass`  is the **fully-qualified class name**, i.e. `namespace` + class.
 *
 * This module deliberately keeps `namespace = "com.openminis.app"` while changing
 * `applicationId` to `"com.openminis.next"`. That is the whole point of the minimal
 * change: thousands of Kotlin `package`/`import` statements and the generated `R`
 * class keep their existing namespace, so the rename costs one Gradle line instead of
 * a repository-wide rewrite. Consequently `targetClass` MUST stay
 * `com.openminis.app.MainActivity` even though `targetPackage` becomes
 * `com.openminis.next`. "Unifying" the two — a very tempting cleanup — produces a
 * class name that does not exist and a shortcut that fails silently. Do not do it.
 */
class AppIdentityConsistencyTest {

    private companion object {
        /** Pinned deliberately: changing the shipped identity must be a conscious edit. */
        const val EXPECTED_APPLICATION_ID = "com.openminis.next"

        /**
         * Pinned too. If this ever changes, thousands of `package`/`import` lines and
         * every `R` reference move with it, and the `targetClass` assertions below
         * change meaning — so it, too, should never move by accident.
         */
        const val EXPECTED_NAMESPACE = "com.openminis.app"

        const val LEGACY_APP_NAME = "Minis"
        const val EXPECTED_APP_NAME = "Minis-Next"
    }

    // ---------------------------------------------------------------- locating

    /**
     * The `app` module directory, found by walking up from the Gradle test working
     * directory (`<module>` for a module test task, the repo root in some IDEs).
     * Same walk-up idiom as [com.openminis.app.i18n.StringsLocaleParityTest].
     */
    private fun moduleDir(): File {
        val working = File(System.getProperty("user.dir"))
        return generateSequence(working) { it.parentFile }
            .take(8)
            .firstOrNull {
                File(it, "build.gradle.kts").isFile && File(it, "src/main/AndroidManifest.xml").isFile
            }
            ?: error("could not locate the app module from ${working.absolutePath}")
    }

    private val gradleFile: File get() = File(moduleDir(), "build.gradle.kts")
    private val manifestFile: File get() = File(moduleDir(), "src/main/AndroidManifest.xml")
    private val shortcutsFile: File get() = File(moduleDir(), "src/main/res/xml/shortcuts.xml")
    private val webAppActivityFile: File
        get() = File(moduleDir(), "src/main/java/com/openminis/app/webapp/WebAppActivity.kt")

    // --------------------------------------------------------------- parsing

    /** Gradle DSL assignment, e.g. `applicationId = "com.openminis.next"`. */
    private fun gradleProperty(name: String): String {
        val match = Regex("""^\s*$name\s*=\s*"([^"]+)"""", RegexOption.MULTILINE)
            .find(gradleFile.readText())
            ?: error("`$name = \"…\"` not found in ${gradleFile.absolutePath}")
        return match.groupValues[1]
    }

    private fun attributeValues(file: File, attribute: String): List<String> =
        Regex("""android:$attribute="([^"]+)"""")
            .findAll(file.readText())
            .map { it.groupValues[1] }
            .toList()

    private fun kotlinConstant(name: String, file: File): String {
        val match = Regex("""const val $name = "([^"]+)"""")
            .find(file.readText())
            ?: error("`const val $name` not found in ${file.absolutePath}")
        return match.groupValues[1]
    }

    private val valuesDirs: List<File>
        get() = File(moduleDir(), "src/main/res").listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("values") }
            .sortedBy { it.name }

    private fun appNameIn(stringsXml: File): String? =
        Regex("""<string name="app_name">([^<]*)</string>""")
            .find(stringsXml.readText())
            ?.groupValues?.get(1)

    // ------------------------------------------------------------- identity

    @Test
    fun `applicationId is the Next identity and is deliberately not the namespace`() {
        val applicationId = gradleProperty("applicationId")
        val namespace = gradleProperty("namespace")

        assertEquals("the shipped applicationId changed — this is a user-visible identity", EXPECTED_APPLICATION_ID, applicationId)
        assertEquals("namespace must NOT follow applicationId (see the class KDoc)", EXPECTED_NAMESPACE, namespace)
        assertNotEquals(
            "applicationId and namespace must differ: shortcuts.xml writes targetPackage from the " +
                "applicationId and targetClass from the namespace, and keeping them distinct is what lets " +
                "the Next rename avoid touching every Kotlin package/import statement",
            namespace,
            applicationId,
        )
    }

    @Test
    fun `every shortcut targetPackage equals the applicationId`() {
        val applicationId = gradleProperty("applicationId")
        val targetPackages = attributeValues(shortcutsFile, "targetPackage")

        assertTrue(
            "no android:targetPackage found in ${shortcutsFile.name} — the scanner is broken",
            targetPackages.isNotEmpty(),
        )
        val drifted = targetPackages.filterNot { it == applicationId }
        assertTrue(
            "${shortcutsFile.name} targets package(s) $drifted but the app is installed as \"$applicationId\". " +
                "A pinned app-icon shortcut would resolve to a package that does not exist and fail silently on tap.",
            drifted.isEmpty(),
        )
    }

    @Test
    fun `every shortcut targetClass uses the namespace and never the applicationId`() {
        val namespace = gradleProperty("namespace")
        val applicationId = gradleProperty("applicationId")
        val targetClasses = attributeValues(shortcutsFile, "targetClass")

        assertTrue(
            "no android:targetClass found in ${shortcutsFile.name} — the scanner is broken",
            targetClasses.isNotEmpty(),
        )
        assertTrue(
            "targetClass must be the class's fully-qualified name (namespace + class), so it is expected to be " +
                "\"$namespace.MainActivity\", not derived from the applicationId \"$applicationId\"",
            targetClasses.all { it == "$namespace.MainActivity" },
        )
        assertTrue(
            "targetClass was changed to follow the applicationId ($applicationId). That class does not exist — " +
                "MainActivity is compiled into the namespace package, so the shortcut would silently break. " +
                "targetPackage follows the applicationId; targetClass follows the namespace.",
            targetClasses.none { it.startsWith("$applicationId.") },
        )
    }

    @Test
    fun `the OPEN_WEBAPP action agrees between the manifest, the Kotlin constant and the applicationId`() {
        val applicationId = gradleProperty("applicationId")

        val manifestActions = Regex("""<action android:name="([^"]*action\.OPEN_WEBAPP)"""")
            .findAll(manifestFile.readText())
            .map { it.groupValues[1] }
            .toList()
        assertEquals(
            "AndroidManifest.xml should declare exactly one OPEN_WEBAPP intent-filter action",
            1,
            manifestActions.size,
        )
        val fromManifest = manifestActions.single()
        val fromKotlin = kotlinConstant("ACTION_OPEN_WEBAPP", webAppActivityFile)

        assertEquals(
            "AndroidManifest.xml declares \"$fromManifest\" but WebAppActivity.ACTION_OPEN_WEBAPP is " +
                "\"$fromKotlin\". ShortcutPinner builds an Intent with the Kotlin constant; the manifest " +
                "intent-filter matches the literal. If they drift the pinned WebApp shortcut can no longer " +
                "be resolved by the activity.",
            fromManifest,
            fromKotlin,
        )
        assertEquals(
            "the app-owned action should carry the shipped package identity",
            "$applicationId.action.OPEN_WEBAPP",
            fromManifest,
        )
    }

    @Test
    fun `no resource xml carries the legacy applicationId as a package name`() {
        // targetClass is the ONLY legitimate place for the namespace string to appear in
        // res/xml. Everything else there is an installed-package identity and must have
        // moved to the Next applicationId.
        val legacy = EXPECTED_NAMESPACE
        val resXml = File(moduleDir(), "src/main/res/xml")
        val offenders = mutableListOf<String>()
        for (file in resXml.listFiles().orEmpty().filter { it.isFile && it.extension == "xml" }) {
            for (line in file.readLines()) {
                if (!line.contains(legacy)) continue
                if (line.contains("android:targetClass=")) continue
                offenders += "${file.name}: ${line.trim()}"
            }
        }
        assertTrue(
            "these res/xml lines still carry the legacy package identity (only targetClass may): $offenders",
            offenders.isEmpty(),
        )
    }

    // -------------------------------------------------------------- app name

    @Test
    fun `every locale declares the identical Next app name`() {
        val declaring = valuesDirs.mapNotNull { dir ->
            val strings = File(dir, "strings.xml")
            if (!strings.isFile) return@mapNotNull null
            appNameIn(strings)?.let { dir.name to it }
        }

        assertTrue("no values*/strings.xml declares app_name", declaring.isNotEmpty())
        val names = declaring.map { it.second }.toSet()
        assertEquals(
            "locales disagree on app_name: $declaring. A locale left at the legacy name would show the OLD app " +
                "name on that device language, which defeats the rename. Translate the brand name verbatim, do " +
                "not translate it.",
            setOf(EXPECTED_APP_NAME),
            names,
        )
    }

    @Test
    fun `the default and Chinese locales both ship the Next app name`() {
        val res = File(moduleDir(), "src/main/res")
        for (dirName in listOf("values", "values-zh")) {
            val strings = File(res, "$dirName/strings.xml")
            assertTrue("$dirName/strings.xml is missing", strings.isFile)
            assertEquals(
                "$dirName/strings.xml must declare app_name",
                EXPECTED_APP_NAME,
                appNameIn(strings),
            )
        }
    }

    @Test
    fun `the app name is no longer the legacy name`() {
        val default = File(moduleDir(), "src/main/res/values/strings.xml")
        val name = appNameIn(default)
        assertEquals(EXPECTED_APP_NAME, name)
        assertNotEquals("the app is still named \"$LEGACY_APP_NAME\", i.e. indistinguishable from upstream", LEGACY_APP_NAME, name)
        assertTrue("the Next app name should carry the Next marker: \"$name\"", name!!.contains("Next"))
    }
}
