package com.openminis.app.feature.runtime

/** A user-requested delegated run parsed from a composer command. */
data class RuntimeDelegationRequest(
    val prompt: String,
    val mode: DelegationMode = DelegationMode.TRADITIONAL,
    val provider: String? = null,
    val model: String? = null,
    val note: String = "",
    val capabilities: Set<String> = RuntimeModelSnapshot.DEFAULT_CAPABILITIES,
    /**
     * Which of the dispatcher's own messages to offer the receiver, from
     * `--share=all` or `--share=0,7,9`. Null means nothing was shared, which is
     * the default and the previous behaviour.
     *
     * This is a request, not the context: it names a selection, and the
     * transcript never travels through it. See [RuntimeContextAttachments] for
     * why the reference form is the requirement and not an optimisation.
     */
    val contextShare: RuntimeContextShareRequest? = null,
)

/**
 * Parses the deliberately small command surface used by the chat composer.
 *
 * Examples:
 *   /subagent inspect the runtime tree
 *   /team --model=claude-sonnet --note="review this" inspect the runtime tree
 *   /team --capabilities=text_input,reasoning summarize this
 *   /subagent --share=all carry on from where I left off
 *   /subagent --share=0,7,9 review the decisions and the failure
 */
object RuntimeDelegationParser {
    private const val TEAM = "/team"
    private const val SUBAGENT = "/subagent"
    private const val DELEGATE = "/delegate"
    private const val SHARE_ALL = "all"

    fun isCommand(input: String): Boolean {
        val normalized = input.trim().replaceFirst('／', '/')
        return listOf(TEAM, SUBAGENT, DELEGATE).any { command ->
            normalized.equals(command, ignoreCase = true) ||
                normalized.startsWith("$command ", ignoreCase = true) ||
                normalized.startsWith("$command\t", ignoreCase = true)
        }
    }

    fun parse(input: String): RuntimeDelegationRequest? {
        val trimmed = input.trim().replaceFirst('／', '/')
        val command = when {
            trimmed.equals(TEAM, ignoreCase = true) || trimmed.startsWith("$TEAM ", ignoreCase = true) -> TEAM
            trimmed.equals(SUBAGENT, ignoreCase = true) || trimmed.startsWith("$SUBAGENT ", ignoreCase = true) -> SUBAGENT
            trimmed.equals(DELEGATE, ignoreCase = true) || trimmed.startsWith("$DELEGATE ", ignoreCase = true) -> DELEGATE
            else -> return null
        }
        val remainder = trimmed.substring(command.length).trim()
        if (remainder.isBlank()) return null

        val options = linkedMapOf<String, String>()
        val promptParts = mutableListOf<String>()
        tokenize(remainder).forEach { token ->
            if (token.startsWith("--") && token.contains('=')) {
                val split = token.substring(2).split('=', limit = 2)
                options[split[0].lowercase()] = split[1]
            } else {
                promptParts += token
            }
        }
        val prompt = promptParts.joinToString(" ").trim()
        if (prompt.isBlank()) return null
        val capabilities = options["capabilities"]
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            ?.toSet()
            ?.ifEmpty { RuntimeModelSnapshot.DEFAULT_CAPABILITIES }
            ?: RuntimeModelSnapshot.DEFAULT_CAPABILITIES
        // A malformed --share is a rejected command, not a silent "share
        // nothing": the dispatcher asked for something specific, and quietly
        // dropping it would deliver a child that cannot see what it was
        // promised. The composer then shows the usage line instead.
        val share = options["share"]?.let { raw -> parseShare(raw) ?: return null }
        return RuntimeDelegationRequest(
            prompt = prompt,
            mode = if (command == TEAM) DelegationMode.TEAM else DelegationMode.TRADITIONAL,
            provider = options["provider"]?.trim()?.ifBlank { null },
            model = options["model"]?.trim()?.ifBlank { null },
            note = options["note"]?.trim().orEmpty(),
            capabilities = capabilities,
            contextShare = share,
        )
    }

    /**
     * `all` (case-insensitive) or a comma-separated list of zero-based message
     * indices. Anything else — including an empty list or a negative index — is
     * null, which [parse] turns into a rejected command.
     */
    private fun parseShare(raw: String): RuntimeContextShareRequest? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        if (value.equals(SHARE_ALL, ignoreCase = true)) return RuntimeContextShareRequest.All
        val indices = value.split(',').map { it.trim() }
        if (indices.isEmpty() || indices.any { it.isEmpty() }) return null
        val parsed = indices.map { it.toIntOrNull() ?: return null }
        if (parsed.any { it < 0 }) return null
        return RuntimeContextShareRequest.Selected(parsed.distinct().sorted())
    }

    /** Small quote-aware tokenizer; this is not intended to be a shell parser. */
    private fun tokenize(value: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        value.forEach { char ->
            when {
                quote != null && char == quote -> quote = null
                quote == null && (char == '\'' || char == '"') -> quote = char
                quote == null && char.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        result += current.toString()
                        current.clear()
                    }
                }
                else -> current.append(char)
            }
        }
        if (current.isNotEmpty()) result += current.toString()
        return result
    }
}
