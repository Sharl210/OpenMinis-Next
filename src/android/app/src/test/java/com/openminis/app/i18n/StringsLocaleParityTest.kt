package com.openminis.app.i18n

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-strings-locale-parity] Every string the app can show must exist in
 * every shipped translation, not only in the default locale.
 *
 * Requirement (request.md:23, verbatim): 「加入的资源也要支持多国语言啊不是直接硬编码」
 * — "the resources you add must support multiple languages too, not be hardcoded".
 * That requirement has two halves and this file guards the second one: putting a
 * string in `values/strings.xml` is necessary but not sufficient, because a locale
 * that omits the key silently falls back to English while the code looks correct.
 *
 * WHAT THIS ASSERTS, and why it is written this way:
 *
 *  1. `values/strings.xml` declares every `R.string.*` the Kotlin source references.
 *     A miss here is also a compile error; this reports it as one readable list
 *     instead of one `e:` line per call site.
 *  2. Every `values-<locale>/strings.xml` is a SUPERSET of `values/strings.xml`.
 *     Not "close to", not "no worse than before" — a superset. This is the
 *     executable form of 「资源要支持多国语言」: add a key to the default locale and
 *     this test goes red until the key exists in all 16 translations.
 *  3. Format placeholders survive translation. A translator dropping `%2$d` or
 *     renumbering `%1$s` compiles fine and crashes (or prints the wrong number) at
 *     runtime, so the placeholder multiset of a translation must equal the
 *     default's.
 *  4. No locale directory escapes the check by simply not being listed.
 *
 * WHY THERE IS AN ALLOWLIST AT ALL, AND WHY IT IS EMPTY:
 * Some strings could be legitimately untranslatable — a bare product name, a lone
 * symbol. Rather than special-case that inside the comparison (which hides the
 * decision), [untranslatedByDesign] makes each exemption an explicit, reviewed
 * line with a reason, and its keys are still required to EXIST in every locale so
 * the superset rule keeps working. It is currently empty because no key needed one:
 * the four symbol-only strings (`token_usage_cache_hit_rate_unavailable`,
 * `token_usage_runtime_unavailable`, and the two `chat_queue_*_badge` codes) are
 * declared per locale with the identical text, which is the correct translation of
 * "—" and of an all-caps wire code, and declaring them is exactly what rule 2 asks
 * for. An entry here is a decision to stop translating a string — it is not a
 * dumping ground for keys nobody got around to translating.
 */
class StringsLocaleParityTest {

    /**
     * Keys whose text is intentionally identical in every locale, each with the
     * reason it must not be translated. Empty today; the mechanism exists so that
     * adding one is a visible, justified edit rather than a quiet gap.
     */
    private val untranslatedByDesign: Map<String, String> = emptyMap()

    /**
     * Keys whose translation is legitimately the same glyph sequence as English,
     * each with the reason. Distinct from [untranslatedByDesign]: that one waives
     * the *superset* requirement, this one waives "must differ from English" —
     * keeping them apart is what makes each decision readable.
     *
     * How this list was built (and how to audit it): every entry below is a string
     * whose English text IS its correct translation, not a string nobody got to.
     * Two families qualify:
     *
     *  - **CLDR short unit abbreviations.** Spanish, French, Portuguese, Filipino,
     *    Malay and Romanian abbreviate hour/minute/second as `h`/`min`/`s` — the
     *    same glyphs English uses. `%1$d h %2$d min` is genuinely the Spanish and
     *    French rendering; forcing a difference would mean writing `ore`/`heures`,
     *    which is *less* idiomatic for a compact runtime badge.
     *  - **`Version` in German and French** — the German and French word for
     *    "version" is `Version`. Rewriting it to satisfy a difference check would
     *    introduce a bug, not fix one.
     *
     * Deliberately NOT listed: `settings_dynamic_island` in Filipino and Russian,
     * which this test originally caught — those had untranslated `(Live Updates)`
     * next to a translated product name, which is a real gap. Borrowed product
     * terms (`Dynamic Island`, `Token`, `OAuth`, `MiniMax`, `Kimi`) stay English
     * *inside* the translation and are not what this check looks at: it compares
     * the whole body, so a body that contains any target-language text passes.
     */
    private val naturallyIdenticalByDesign: Map<String, String> = mapOf(
        "token_usage_runtime_hours_minutes" to
            "CLDR short h/min IS h/min in es/fr/ro/pt-rBR/fil; 'ore'/'heures' is less idiomatic",
        "token_usage_runtime_minutes_seconds" to
            "CLDR short min/s IS min/s in es/fr/pt-rBR/fil/ms",
        "about_version_format" to
            "'Version' IS the German and French word for version",
        "browser_settings_ua_mobile_chrome" to
            "de and fil use 'Mobile'/'Desktop' as bare borrowings throughout " +
            "(webpreview_mobile_site = 'Mobile Website' / 'Mobile site'), so inflecting " +
            "only here would be the inconsistency",
        "browser_settings_ua_desktop_chrome" to
            "same borrowing as browser_settings_ua_mobile_chrome; de already writes " +
            "webpreview_desktop_site = 'Desktop-Website'",
        "offload_perm_dialog_tool_label" to
            "fil keeps 'Tool' as its established borrowed term - memory_section_tool_activity " +
            "('Aktibidad ng Tool'), stop_tool ('Ihinto ang tool'), mcp_section_footer " +
            "('panlabas na tool'); 'Kasangkapan' appears nowhere in the file",
        "mcp_form_headers_placeholder" to
            "an HTTP header line - 'Authorization: Bearer \$TOKEN' is the same notation in " +
            "every locale, and a translated header would no longer be a valid header",
        "thinking_fmt_deepseek_title" to
            "'thinking + reasoning_effort' names two Anthropic API fields verbatim",
        "thinking_fmt_qwen_title" to
            "'enable_thinking + budget' names two Qwen API fields verbatim",
    )

    /**
     * Is this value the kind of string a translator is expected to rewrite?
     *
     * Calibrated on this tree, which matters more than elegance here. The obvious
     * rule — "identical to English ⇒ untranslated" — flags ~1900 entries and is
     * useless: `app_name` ("Minis"), `ok` ("OK"), `api_key` ("API key"),
     * `mcp_transport_sse` ("SSE") and every `shizuku_cap_*` (Android capability
     * identifiers) are *correctly* identical. A guard with that false-positive rate
     * gets ignored, and a guard that gets ignored is worse than no guard.
     *
     * A third, symbol-based screen runs first — URL scheme, braces, `@flag`,
     * escaped newline, and a whitespace-free `=` body — on the theory that config
     * samples are the same notation in every language. That theory holds; the
     * *implementation* of it did not, because the `=` screen was unconditional and
     * therefore swallowed prose that merely happens to contain one. It is now
     * conditional (see the comment on the rule) and the two keys it was hiding
     * are reported.
     *
     * The two conditions below are what separates the real class from that noise:
     *  - a run of ≥3 letters, so pure symbols/numbers/format strings drop out; and
     *  - NOT (a plain Latin/punctuation body of ≤2 words), which drops product
     *    names, wire codes, single technical nouns and `%1$d min`-style strings.
     *
     * Applied here it selects exactly the 22 keys that were genuinely untranslated
     * (`chat_queued_withdraw` = "Withdraw queued message", `settings_dynamic_island`
     * = "Dynamic Island (Live Updates)", …) and nothing else. The keys it still
     * reports are all strings whose English is the correct target-language text,
     * and each is listed with its reason in [naturallyIdenticalByDesign].
     */
    private fun looksTranslatable(body: String): Boolean {
        if (!Regex("[A-Za-z]{3,}").containsMatchIn(body)) return false
        // Config/code samples: a URL scheme, JSON braces, a bare `key=value`
        // token, an `@flag`, or an escaped newline. These are notation, not
        // prose — an endpoint is the same endpoint in every language.
        if (Regex("https?://|[{}]|@|\\\\n").containsMatchIn(body)) return false
        // `=` is deliberately NOT in that list any more, and that narrowing IS
        // the fix: the blanket rule "body contains `=` ⇒ notation" is what let
        // `shizuku_ready_subtitle` ('version=%1$d, uid=%2$d') and
        // `shizuku_ready_subtitle_sui` keep shipping English to es/fr/ja/ko
        // (+zh-rTW for the first) while this guard stayed green — the same
        // failure the file header warns about, a guard that runs but watches the
        // wrong axis. Both bodies are prose badges, not config: their single word
        // IS translated in 11 and 12 locales respectively (`Version=` in de,
        // `bersyon=`/`versi=`/`wersja=`/`versão=`/`версия=`/`เวอร์ชัน=`/`sürüm=`,
        // `版本=` in zh), which is exactly the evidence a translator rewrites them.
        // The exemption `=` still earns is narrower: a body with NO whitespace at
        // all is a bare token (`GITHUB_TOKEN=$GITHUB_TOKEN`, `key=value`) and is
        // skipped; a body punctuated by spaces is a sentence and stays inspected.
        if (body.contains('=') && body.none { it.isWhitespace() }) return false
        val plainLatin = Regex("[A-Za-z0-9 %$#/{}.\\-—…×·()+_*]*")
        if (plainLatin.matches(body) && body.trim().split(Regex("\\s+")).size <= 2) return false
        return true
    }

    @Test
    fun `no locale simply repeats the English text for a translatable string`() {
        val default = declaredStrings(File(resourcesRoot(), "values/strings.xml"))

        // Report by KEY, not by locale - the same discipline as
        // `every shipped locale declares every key of the default locale` below.
        // One key still carrying English in all 16 locales is a single decision
        // (translate it, or exempt it with a reason), but a per-locale list turns
        // it into sixteen near-identical entries where the one thing you need -
        // which key, and how many locales - is the thing you cannot see. The
        // locale count per line is what separates "a global gap" from "one
        // translation somebody skipped".
        val identicalByKey = sortedMapOf<String, MutableList<String>>()
        for (dir in localeDirs()) {
            val declared = declaredStrings(File(dir, "strings.xml"))
            for ((key, body) in declared) {
                if (key in naturallyIdenticalByDesign) continue
                val english = default[key] ?: continue
                if (body != english) continue
                if (!looksTranslatable(english)) continue
                identicalByKey.getOrPut(key) { mutableListOf() }.add(dir.name)
            }
        }
        if (identicalByKey.isEmpty()) return

        val report = buildString {
            append(identicalByKey.size).append(" key(s) still carry the English text in ")
            append(identicalByKey.values.flatten().distinct().size)
            append(" locale(s), so the user sees English after switching language. ")
            append("Translate them, or - only when the English text IS the correct ")
            append("translation - add the key to naturallyIdenticalByDesign with a reason:\n")
            for ((key, dirs) in identicalByKey) {
                append("  - ").append(key).append("  [English in ").append(dirs.size).append(": ")
                append(dirs.joinToString(", ")).append("]\n")
            }
        }
        assertTrue(report, identicalByKey.isEmpty())
    }

    private fun resourcesRoot(): File {
        val working = File(System.getProperty("user.dir"))
        return generateSequence(working) { it.parentFile }
            .take(8)
            .map { File(it, "src/main/res") }
            .firstOrNull { File(it, "values/strings.xml").isFile }
            ?: error("could not locate app src/main/res from ${working.absolutePath}")
    }

    private fun kotlinSources(): List<File> {
        val working = File(System.getProperty("user.dir"))
        val srcDir = generateSequence(working) { it.parentFile }
            .take(8)
            .map { File(it, "src/main/java") }
            .firstOrNull { it.isDirectory }
            ?: error("could not locate src/main/java")
        return srcDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    /** The text of every `<string name="…">…</string>`, keyed by name. */
    private fun declaredStrings(stringsXml: File): Map<String, String> =
        Regex("<string\\s[^>]*\\bname=\"([A-Za-z0-9_]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .findAll(stringsXml.readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    private fun localeDirs(): List<File> =
        resourcesRoot().listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("values-") && File(it, "strings.xml").isFile }
            .sortedBy { it.name }

    /**
     * Every `R.string.<name>` the Kotlin source references.
     *
     * The negative lookbehind for `android.` matters: `android.R.string.copy` and
     * `android.R.string.selectAll` are framework strings (the terminal's context
     * menu uses them). Counting them as app keys produced two phantom "missing from
     * values/" hits on the first run.
     */
    private fun referencedKeys(): Set<String> {
        val pattern = Regex("(?<!android\\.)\\bR\\.string\\.([A-Za-z0-9_]+)")
        val out = mutableSetOf<String>()
        for (file in kotlinSources()) {
            for (match in pattern.findAll(file.readText())) out.add(match.groupValues[1])
        }
        return out
    }

    /** `%1$s` / `%2$d` / `%3$f` tokens in declaration order. */
    private fun placeholders(body: String): List<String> =
        Regex("%(\\d+)\\$([sdf])").findAll(body).map { it.groupValues[1] + "$" + it.groupValues[2] }.sorted().toList()

    @Test
    fun `the default locale declares every string the source references`() {
        val referenced = referencedKeys()
        assertTrue("no R.string references found — the scanner is broken", referenced.isNotEmpty())
        val declared = declaredStrings(File(resourcesRoot(), "values/strings.xml")).keys
        val missing = (referenced - declared).sorted()
        assertTrue("values/strings.xml is missing referenced keys: $missing", missing.isEmpty())
    }

    @Test
    fun `every shipped locale declares every key of the default locale`() {
        val default = declaredStrings(File(resourcesRoot(), "values/strings.xml")).keys
        assertTrue("the default locale looks empty — the parser is broken", default.size > 500)

        val locales = localeDirs()
        assertTrue("no values-<locale> directories with strings.xml found", locales.isNotEmpty())

        // Report by KEY, not by locale. The common failure is one newly added
        // default string missing from many locales at once, and a per-locale list
        // truncated to the first few keys turns "one key x 16 locales" into
        // sixteen near-identical lines where the actual key is invisible. Grouping
        // by key makes the delta — this is what you have to translate, and here is
        // everything it is absent from — one line per missing key, complete, with
        // no truncation.
        val missingByKey = sortedMapOf<String, MutableList<String>>()
        for (dir in locales) {
            val declared = declaredStrings(File(dir, "strings.xml")).keys
            for (key in default - declared - untranslatedByDesign.keys) {
                missingByKey.getOrPut(key) { mutableListOf() }.add(dir.name)
            }
        }
        if (missingByKey.isEmpty()) return

        val report = buildString {
            append(missingByKey.size).append(" key(s) from values/strings.xml are missing in ")
            append(missingByKey.values.flatten().distinct().size).append(" locale(s)")
            append(" (add the translations, or an explicitly justified entry in untranslatedByDesign):\n")
            for ((key, dirs) in missingByKey) {
                append("  - ").append(key).append("  [missing in ").append(dirs.size).append(": ")
                append(dirs.joinToString(", ")).append("]\n")
            }
        }
        assertEquals(report, emptyList<String>(), missingByKey.keys.toList())
    }

    @Test
    fun `every translation keeps the default locale format placeholders`() {
        val root = resourcesRoot()
        val default = declaredStrings(File(root, "values/strings.xml"))
        val problems = mutableListOf<String>()

        for (dir in localeDirs()) {
            val translated = declaredStrings(File(dir, "strings.xml"))
            for ((key, body) in default) {
                val local = translated[key] ?: continue
                val want = placeholders(body)
                val got = placeholders(local)
                if (want != got) {
                    problems += "${dir.name}/$key: default=$want translation=$got"
                }
            }
        }
        assertEquals(
            "a translation changed the format placeholders of its default string, " +
                "which crashes or misprints at runtime:",
            emptyList<String>(),
            problems,
        )
    }

    @Test
    fun `no locale string carries unescaped XML markup`() {
        // A raw `&` or `<` inside a <string> body is either a broken entity or a
        // truncated tag; both make Android's resource compiler fail or render
        // literally. Catches hand-edited translation files.
        val problems = mutableListOf<String>()
        val entity = Regex("&(amp|lt|gt|quot|apos|#\\d+|#x[0-9A-Fa-f]+);")
        for (dir in localeDirs() + listOf(File(resourcesRoot(), "values"))) {
            val file = File(dir, "strings.xml")
            for ((key, body) in declaredStrings(file)) {
                val stripped = entity.replace(body, "")
                // A raw `>` is legal in XML text; a raw `&` or `<` is not, and
                // means either a broken entity or a half-written tag.
                if (stripped.contains("&") || stripped.contains("<")) {
                    problems += "${dir.name}/$key"
                }
            }
        }
        assertEquals("these strings contain unescaped XML markup: $problems", emptyList<String>(), problems)
    }

    @Test
    fun `no string body carries an unescaped apostrophe`() {
        // [T-android-strings-escaped-apostrophe] Android is not XML here: aapt2
        // treats an unescaped `'` inside a <string> body as a quote delimiter, so
        // the body is exactly as valid XML while being invalid *resource* XML.
        // Real breakage this guards, on this project: a French translation wrote
        // `n'est` next to correctly escaped `s\'est` on the same line; every XML
        // check stayed green, `:app:testDebugUnitTest` never even started because
        // `mergeDebugResources` fails upstream of it, and the resulting
        // `Failed to flatten XML ... Invalid unicode escape sequence` blocked the
        // whole build queue while pointing at the wrong cause. That is the failure
        // class this file exists for: a guard that runs but watches the wrong axis.
        //
        // The one legal exception: when the ENTIRE body is wrapped in double
        // quotes, aapt2 takes the body literally and apostrophes need no escape.
        // 15 strings in this tree rely on that, so the exception is required and
        // is applied per-body, not per-file.
        val problems = mutableListOf<String>()
        for (dir in localeDirs() + listOf(File(resourcesRoot(), "values"))) {
            for ((key, body) in declaredStrings(File(dir, "strings.xml"))) {
                if (body.length > 1 && body.startsWith("\"") && body.endsWith("\"")) continue
                val raw = Regex("(?<!\\\\)'").findAll(body).count()
                if (raw > 0) problems += "${dir.name}/$key (x$raw)"
            }
        }
        assertEquals(
            "these strings have an unescaped apostrophe; write \\' or wrap the whole value in " +
                "double quotes (aapt2 rejects the raw form at resource-merge time, which is " +
                "upstream of every unit test):",
            emptyList<String>(),
            problems,
        )
    }

    @Test
    fun `no resource file declares the same key twice`() {
        val problems = mutableListOf<String>()
        for (dir in localeDirs() + listOf(File(resourcesRoot(), "values"))) {
            val file = File(dir, "strings.xml")
            val names = Regex("<string\\s[^>]*\\bname=\"([A-Za-z0-9_]+)\"")
                .findAll(file.readText()).map { it.groupValues[1] }.toList()
            for (name in names.toSet()) {
                val count = names.count { it == name }
                if (count > 1) problems += "${dir.name}/$name x$count"
            }
        }
        assertEquals("duplicate <string> names found: $problems", emptyList<String>(), problems)
    }

    @Test
    fun `every locale directory with strings is covered by these checks`() {
        val known = localeDirs().map { it.name }.toSet() + setOf("values")
        val unaccounted = resourcesRoot().listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("values") && File(it, "strings.xml").isFile }
            .map { it.name }
            .filterNot { it in known }
            .sorted()
        assertTrue("these locales have strings.xml but escaped the parity check: $unaccounted", unaccounted.isEmpty())
    }

    @Test
    fun `the allowlist has no stale entries`() {
        // An allowlist row for a key that no longer exists (or is no longer needed)
        // is dead weight that silently widens the exemption; report it.
        val default = declaredStrings(File(resourcesRoot(), "values/strings.xml")).keys
        val referenced = referencedKeys()
        val stale = untranslatedByDesign.keys.filterNot { it in default && it in referenced }.sorted()
        assertTrue("untranslatedByDesign entries that no longer exist or are unreferenced: $stale", stale.isEmpty())
    }

    @Test
    fun `the identical-by-design allowlist has no stale entries`() {
        // Same discipline as above, applied to naturallyIdenticalByDesign: an
        // exemption that stopped being needed (because the string got translated)
        // must be deleted, not left behind. A row is "needed" only while some
        // locale still carries the English text for that key.
        val default = declaredStrings(File(resourcesRoot(), "values/strings.xml"))
        val stale = mutableListOf<String>()
        for (key in naturallyIdenticalByDesign.keys) {
            val english = default[key]
            if (english == null) { stale += "$key (not in values/strings.xml)"; continue }
            val stillIdentical = localeDirs().any { dir ->
                declaredStrings(File(dir, "strings.xml"))[key] == english
            }
            if (!stillIdentical) stale += "$key (no locale repeats the English text any more)"
            if (!looksTranslatable(english)) stale += "$key (the guard no longer inspects this string)"
        }
        assertTrue(
            "naturallyIdenticalByDesign entries that are no longer needed - each one is an " +
                "exemption nobody is using, and left in place it would hide a future gap: $stale",
            stale.isEmpty(),
        )
    }
}
