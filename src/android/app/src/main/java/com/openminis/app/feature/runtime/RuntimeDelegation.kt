package com.openminis.app.feature.runtime

/** A user-requested delegated run parsed from a composer command. */
data class RuntimeDelegationRequest(
    val prompt: String,
    val mode: DelegationMode = DelegationMode.TRADITIONAL,
    val provider: String? = null,
    val model: String? = null,
    val note: String = "",
    val capabilities: Set<String> = RuntimeModelSnapshot.DEFAULT_CAPABILITIES,
)

/**
 * Parses the deliberately small command surface used by the chat composer.
 *
 * Examples:
 *   /subagent inspect the runtime tree
 *   /team --model=claude-sonnet --note="review this" inspect the runtime tree
 *   /team --capabilities=text_input,reasoning summarize this
 */
object RuntimeDelegationParser {
    private const val TEAM = "/team"
    private const val SUBAGENT = "/subagent"
    private const val DELEGATE = "/delegate"

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
        return RuntimeDelegationRequest(
            prompt = prompt,
            mode = if (command == TEAM) DelegationMode.TEAM else DelegationMode.TRADITIONAL,
            provider = options["provider"]?.trim()?.ifBlank { null },
            model = options["model"]?.trim()?.ifBlank { null },
            note = options["note"]?.trim().orEmpty(),
            capabilities = capabilities,
        )
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
