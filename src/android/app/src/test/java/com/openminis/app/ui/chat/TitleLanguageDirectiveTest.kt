package com.openminis.app.ui.chat

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The localization half of the title prompt.
 *
 * `request.md:246` makes the title prompt user-editable, which raises a question
 * the editable prompt itself does not answer: the app already forces the title
 * into the user's UI language. Where does that live — inside the editable prompt
 * (so an edit can break or duplicate it), or beside it?
 *
 * Read from the source: beside it. [titleLanguageDirective] is appended to the
 * title **user** prompt at both call sites
 * ([ChatViewModel.generateSessionTitleIfNeeded], [SessionListViewModel]), and it
 * is the only localized text on the path. The editable system prompt is never
 * touched by it. These tests pin that split, plus the language resolution —
 * `zh` is the case that needed code, because bare `zh` renders the same for
 * Simplified and Traditional while the title style does not.
 *
 * Pure and Android-free: [Locale] is injectable, so no device and no clock are
 * involved. Nothing here can prove what a real model does with the directive;
 * it proves only what the request carries.
 */
class TitleLanguageDirectiveTest {

    // ── 1. It is appended to the user prompt, it is not the system prompt ──

    @Test
    fun `the directive is a prompt suffix, not a system prompt`() {
        val directive = titleLanguageDirective(Locale.US)

        assertTrue(
            "the call sites do `append(titleLanguageDirective())` on the user prompt, " +
                "so the directive has to start on its own paragraph",
            directive.startsWith("\n\n"),
        )
        assertNotEquals(
            "the localized directive must not be confused with the editable system prompt",
            TITLE_GEN_SYSTEM_PROMPT,
            directive,
        )
        assertFalse(
            "the directive must not restate the JSON contract — that is the system prompt's job",
            directive.contains("\"title\""),
        )
    }

    @Test
    fun `the directive is bilingual and names the interface language`() {
        val directive = titleLanguageDirective(Locale.US)

        assertTrue("English half", directive.contains("app interface language is"))
        assertTrue("Chinese half", directive.contains("\u754C\u9762\u8BED\u8A00"))
        assertTrue("it must state the language code", directive.contains("\"en\""))
        assertTrue("and the human-readable name", directive.contains("(English)"))
    }

    @Test
    fun `the editable system prompt never absorbs the localization directive`() {
        // The two are separate by construction: the selector takes no Locale, so
        // however the user edits it, no interface language can leak in — and no
        // edit can suppress the directive, because it is not part of the string
        // the user edits.
        val edited = "MY OWN TITLE RULES"
        assertEquals(edited, effectiveTitleSystemPrompt(edited))
        assertFalse(effectiveTitleSystemPrompt(edited).contains("app interface language"))
        assertFalse(effectiveTitleSystemPrompt(null).contains("app interface language"))
        assertFalse(effectiveCompactionSystemPrompt(null).contains("app interface language"))
    }

    // ── 2. Chinese: the case that needs code ─────────────────────────────

    @Test
    fun `mainland and Taiwanese Chinese resolve differently`() {
        val simplified = titleLanguageDirective(Locale.forLanguageTag("zh-CN"))
        val traditional = titleLanguageDirective(Locale.forLanguageTag("zh-TW"))

        assertTrue(simplified.contains("\"zh-Hans\""))
        assertTrue(simplified.contains("\u7B80\u4F53\u4E2D\u6587"))
        assertTrue(traditional.contains("\"zh-Hant\""))
        assertTrue(traditional.contains("\u7E41\u9AD4\u4E2D\u6587"))
        assertNotEquals(
            "bare `zh` renders identically for both scripts, so the two directives must differ",
            simplified,
            traditional,
        )
    }

    @Test
    fun `every traditional-Chinese region is treated as traditional`() {
        val expected = titleLanguageDirective(Locale.forLanguageTag("zh-TW"))
        for (country in listOf("TW", "HK", "MO")) {
            assertEquals(
                "zh-$country",
                expected,
                titleLanguageDirective(Locale.forLanguageTag("zh-$country")),
            )
        }
    }

    @Test
    fun `an explicit script wins over the region default`() {
        assertTrue(titleLanguageDirective(Locale.forLanguageTag("zh-Hant")).contains("\"zh-Hant\""))
        assertTrue(titleLanguageDirective(Locale.forLanguageTag("zh-Hans")).contains("\"zh-Hans\""))
        assertTrue(
            "script must beat a mainland region — the tag says Traditional, so it is Traditional",
            titleLanguageDirective(Locale.forLanguageTag("zh-Hant-CN")).contains("\"zh-Hant\""),
        )
    }

    // ── 3. Everything else, and the never-throws fallback ────────────────

    @Test
    fun `a language with a known display name uses it`() {
        val cases = mapOf(
            "en" to "English",
            "ja" to "\u65E5\u672C\u8A9E / Japanese",
            "ko" to "\uD55C\uAD6D\uC5B4 / Korean",
            "fr" to "Fran\u00E7ais / French",
            "de" to "Deutsch / German",
            "pt" to "Portugu\u00EAs / Portuguese",
            "ru" to "\u0420\u0443\u0441\u0441\u043A\u0438\u0439 / Russian",
        )

        for ((code, human) in cases) {
            val directive = titleLanguageDirective(Locale.forLanguageTag(code))
            assertTrue("$code must be named as the interface language", directive.contains("\"$code\""))
            assertTrue("$code must carry $human", directive.contains("($human)"))
        }
    }

    @Test
    fun `a region is ignored for a language with no regional script split`() {
        assertEquals(
            "pt-BR is still pt; only Chinese splits on region here",
            titleLanguageDirective(Locale.forLanguageTag("pt")),
            titleLanguageDirective(Locale.forLanguageTag("pt-BR")),
        )
    }

    @Test
    fun `a language with no display name falls back to the code`() {
        val directive = titleLanguageDirective(Locale.forLanguageTag("xx"))

        assertTrue(directive.contains("\"xx\""))
        assertTrue("the human name degrades to the code rather than to an empty string", directive.contains("(xx)"))
    }

    @Test
    fun `an unparseable or empty locale falls back to English instead of throwing`() {
        for (locale in listOf(Locale.ROOT, Locale.forLanguageTag(""), Locale.forLanguageTag("!!"))) {
            val directive = titleLanguageDirective(locale)
            assertTrue(directive.contains("\"en\""))
            assertTrue(directive.contains("(English)"))
        }
    }

    @Test
    fun `the directive is pure - same locale in, same string out`() {
        val locale = Locale.forLanguageTag("ja")
        assertEquals(titleLanguageDirective(locale), titleLanguageDirective(locale))
    }
}
