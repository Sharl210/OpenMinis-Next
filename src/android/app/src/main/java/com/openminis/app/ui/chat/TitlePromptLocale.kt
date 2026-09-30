package com.openminis.app.ui.chat

import java.util.Locale

/**
 * Shared system prompt for session title generation. Used by BOTH the auto
 * path (ChatViewModel.generateSessionTitleIfNeeded) and the manual Regenerate
 * path (SessionListViewModel.regenerateTitle) so the two never drift. Matches
 * iOS callSubModelForTitle's system prompt verbatim.
 *
 * Callers pass this as the bare `systemPrompt`; for OAuth Anthropic instances
 * AnthropicProvider.resolveSystemPrompt force-prepends the Claude Code prefix
 * block at the provider layer (and strips a caller-supplied one), so callers
 * do NOT need to prepend it themselves.
 */
internal const val TITLE_GEN_SYSTEM_PROMPT: String =
    "You generate concise titles for conversations. You MUST respond with a single valid JSON object: {\"title\": \"...\", \"category\": \"...\"}. No other text."

/**
 * The prompt a compaction request carries when the user has not edited one.
 *
 * Single definition on purpose. This text used to exist TWICE — the Settings
 * editor showed a one-line placeholder while the runtime actually sent the long
 * structured prompt below — so the "reset to default" button and the editor's
 * displayed default described a prompt the model never saw. `request.md:246`
 * makes the compaction prompt user-editable, which makes the displayed default
 * part of the contract: what the editor shows and what the model receives must
 * be the same string.
 *
 * A `val` rather than a `const val` because the literal needs `trimIndent()`.
 */
internal val COMPACTION_DEFAULT_SYSTEM_PROMPT: String = """
    You are a context compaction engine. Your summary will REPLACE the original messages in the conversation context window. The agent will read your summary as past context, then proceed based on the user's NEXT message — your summary is background, not a standing work order. Write the summary in the same language the user used in the conversation.

    MUST PRESERVE (never omit or shorten):
    - All file paths, directory names, URLs, UUIDs, and identifiers — copy verbatim
    - Commands executed and their outcomes (success/failure/output)
    - What was requested and what was done (record as past events, not as ongoing goals)
    - Key decisions made and their rationale
    - Errors encountered and how they were resolved
    - Important constraints, rules, or user preferences mentioned
    - Any tool calls and their results that affect current state

    STRUCTURE:
    1. Start with a one-line description of what the conversation was about (use past tense — "User asked X, agent did Y", NOT "Goal: X").
    2. Then a concise narrative of what happened, preserving technical details.
    3. End with a "What had been done so far" section listing completed work — NOT a "todo" or "pending" list. Do not invent ongoing objectives or carry-over tasks from old turns; if the user wants to continue, they will say so in their next message.

    PRIORITIZE recent context over older history — recent decisions and recent file/path references are most useful for continuity.

    Do NOT translate or alter code snippets, file paths, identifiers, or error messages. Be concise but never lose information the agent needs.
""".trimIndent()

/**
 * The system prompt a title request actually carries.
 *
 * `request.md:246` makes the prompt itself user-editable:
 *   「标题生成和压缩模型。他们不仅仅只是支持模型的选择，还要去支持对应的提示词的编辑」
 *
 * The choice lives in one function rather than inline at each call site for the
 * same reason the default constant is shared: the auto path
 * ([com.openminis.app.ui.chat.ChatViewModel.generateSessionTitleIfNeeded]) and
 * the manual Regenerate path
 * ([com.openminis.app.ui.sessions.SessionListViewModel.regenerateTitle]) must
 * not drift. A blank edit means "unset", not "send nothing" — an empty system
 * prompt would silently drop the JSON-shape instruction the title parser
 * depends on.
 */
internal fun effectiveTitleSystemPrompt(edited: String?): String =
    edited?.takeIf { it.isNotBlank() } ?: TITLE_GEN_SYSTEM_PROMPT

/** The compaction counterpart of [effectiveTitleSystemPrompt]. */
internal fun effectiveCompactionSystemPrompt(edited: String?): String =
    edited?.takeIf { it.isNotBlank() } ?: COMPACTION_DEFAULT_SYSTEM_PROMPT

/**
 * Build the bilingual language directive appended to the title-generation
 * user prompt so the model produces a title in the user's UI language even
 * when the conversation contents are in another language.
 *
 * Resolution: `Locale.getDefault()` — the app does not currently expose an
 * in-app language override. If parsing the locale ever fails, falls back to
 * "en" / "English" rather than throwing, so title generation never breaks
 * over a malformed locale.
 */
internal fun titleLanguageDirective(locale: Locale = Locale.getDefault()): String {
    val (code, human) = resolveTitleLanguageCode(locale)
    return buildString {
        append("\n\n")
        append("The user's app interface language is \"").append(code)
            .append("\" (").append(human).append("). Generate the title primarily in this language. ")
        append("If the conversation content is in a different language, you may keep proper nouns ")
        append("from it, but the overall title language should match the interface language.\n")
        append("用户的 App 界面语言是 \"").append(code).append("\"（").append(human).append("）。")
        append("请优先使用该语言生成标题。如果对话内容是其他语言，可保留专有名词，但标题整体语言应与界面语言一致。")
    }
}

private fun resolveTitleLanguageCode(locale: Locale): Pair<String, String> {
    return try {
        val lang = locale.language.takeIf { it.isNotEmpty() } ?: return "en" to "English"
        // Distinguish Simplified vs Traditional Chinese — both render as "zh"
        // bare, but title style differs meaningfully between them.
        if (lang == "zh") {
            val script = locale.script
            val region = locale.country
            val isTraditional = script.equals("Hant", ignoreCase = true)
                || region in setOf("TW", "HK", "MO")
            return if (isTraditional) "zh-Hant" to "繁體中文 / Traditional Chinese"
            else "zh-Hans" to "简体中文 / Simplified Chinese"
        }
        val human = humanReadable[lang] ?: locale.getDisplayLanguage(Locale.ENGLISH)
            .ifEmpty { lang }
        lang to human
    } catch (_: Exception) {
        "en" to "English"
    }
}

private val humanReadable: Map<String, String> = mapOf(
    "en" to "English",
    "ja" to "日本語 / Japanese",
    "ko" to "한국어 / Korean",
    "fr" to "Français / French",
    "de" to "Deutsch / German",
    "es" to "Español / Spanish",
    "it" to "Italiano / Italian",
    "pt" to "Português / Portuguese",
    "ru" to "Русский / Russian",
    "ar" to "العربية / Arabic",
    "hi" to "हिन्दी / Hindi",
    "vi" to "Tiếng Việt / Vietnamese",
    "th" to "ไทย / Thai",
    "id" to "Bahasa Indonesia / Indonesian",
    "tr" to "Türkçe / Turkish",
    "nl" to "Nederlands / Dutch",
    "pl" to "Polski / Polish",
)
