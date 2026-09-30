package com.openminis.app.shared

/**
 * Reading Kotlin **source text** in tests — one implementation, so that "what does
 * this file call?" and "what does this file merely mention?" cannot drift between
 * test classes.
 *
 * ## Why this exists
 *
 * Several source-shape tests grew their own copy of the same three helpers. Each
 * copy was written at a different time, so they disagree on the two decisions that
 * actually matter, and both mistakes were measured on a fake source tree:
 *
 *  1. **Comments and string literals.** A shape regex matched against the *raw* file
 *     is satisfied by prose. Measured: writing
 *     `// if (markStreamErrorOnFailure) SessionActivityTracker.markStreamError(…)`
 *     inside `resumeRun` and deleting the real branch kept the assertion green;
 *     likewise a commented-out `if (commitEditIfActive(text)) return` in the send
 *     funnel. So every shape match runs on [code].
 *  2. **What counts as reaching a symbol.** A scanner that only looks for `name(`
 *     misses a *function reference* (`val f = c::purgeSubtrees`), and one that skips
 *     import lines misses an *alias* (`import …purgeSubtrees as zap`). Both were
 *     measured green against the narrower scanners. So [references] matches the
 *     bare identifier and keeps import lines.
 *
 * Together the two rules give the four-way split this object encodes:
 *
 * | asking | use | because |
 * |---|---|---|
 * | does the code *call* it? | [references] on [code] | a mention in a comment or a string is not reachability |
 * | does the code *name* it as a literal? | `contains` on [noComments] | some names only exist as strings (tool names, JSON keys) |
 *
 * ## Contract
 *
 * [code] and [noComments] preserve length, offsets and newlines, so line numbers
 * taken from a mask are valid for the original text. Offsets are always computed on
 * a mask and sliced from the **original**, so failure messages show real code.
 */
object KotlinSourceText {

    /** One identifier occurrence: [file] is whatever the caller passed in, [line] is 1-based. */
    data class Reference(val file: String, val line: Int, val text: String) {
        override fun toString(): String = if (file.isEmpty()) "line $line" else "$file:$line"
    }

    /**
     * Comments and string/char literal *bodies* replaced by spaces.
     *
     * This is the mask to use for any "does the code do X" question.
     */
    fun code(text: String): String = blank(text, blankLiterals = true)

    /**
     * Comment bodies replaced by spaces, string literals kept.
     *
     * This is the mask to use for "does the code quote the name X" — a tool name or
     * a JSON key that legitimately exists only as a literal. It must **not** be used
     * to decide reachability: a name in a log line is not a call.
     */
    fun noComments(text: String): String = blank(text, blankLiterals = false)

    private fun blank(text: String, blankLiterals: Boolean): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val next = text.getOrNull(i + 1)
            when {
                c == '/' && next == '/' -> while (i < text.length && text[i] != '\n') {
                    out.append(' ')
                    i++
                }

                c == '/' && next == '*' -> {
                    out.append("  ")
                    i += 2
                    while (i < text.length && !(text[i] == '*' && text.getOrNull(i + 1) == '/')) {
                        out.append(if (text[i] == '\n') '\n' else ' ')
                        i++
                    }
                    if (i < text.length) {
                        out.append("  ")
                        i += 2
                    }
                }

                !blankLiterals -> {
                    out.append(c)
                    i++
                }

                c == '"' && text.startsWith("\"\"\"", i) -> {
                    out.append("   ")
                    i += 3
                    while (i < text.length && !text.startsWith("\"\"\"", i)) {
                        out.append(if (text[i] == '\n') '\n' else ' ')
                        i++
                    }
                    if (i < text.length) {
                        out.append("   ")
                        i += 3
                    }
                }

                c == '"' || c == '\'' -> {
                    val quote = c
                    out.append(' ')
                    i++
                    while (i < text.length && text[i] != quote && text[i] != '\n') {
                        if (text[i] == '\\') {
                            out.append(' ')
                            i++
                        }
                        out.append(' ')
                        i++
                    }
                    if (i < text.length && text[i] == quote) {
                        out.append(' ')
                        i++
                    }
                }

                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /** 1-based line number of [offset] in [text]. */
    fun lineOf(text: String, offset: Int): Int =
        text.substring(0, offset.coerceIn(0, text.length)).count { it == '\n' } + 1

    /**
     * Every occurrence of the identifier [name] that could *reach* the symbol, in
     * comment/string-free code — the declaration (`fun name(`) excluded, import
     * lines **included** (an alias makes the import the only place the name appears).
     *
     * Matching the bare identifier rather than `name(` is deliberate: it is what
     * catches a function reference. The consequence to keep in mind is that a
     * *mention* in a declaration of something else (a parameter named the same) also
     * matches, so callers should assert on the *set* of references rather than
     * merely "at least one".
     */
    fun references(text: String, name: String, file: String = ""): List<Reference> {
        val masked = code(text)
        return Regex("\\b" + Regex.escape(name) + "\\b").findAll(masked).mapNotNull { m ->
            val before = masked.substring(0, m.range.first)
            val token = before.trimEnd().takeLastWhile { !it.isWhitespace() }
            if (token == "fun") return@mapNotNull null
            Reference(file, lineOf(text, m.range.first), text.substring(m.range.first, m.range.last + 1))
        }.toList()
    }

    /**
     * The arguments of the first `marker(…)` in [region], split on top-level commas.
     *
     * Parentheses are matched against [code], so a `)` inside a string cannot end
     * the call early, and the split ignores commas nested in another call — which is
     * what makes an argument *count* meaningful (`minOf(a, b)` is one argument).
     */
    fun arguments(region: String, marker: String): List<String> {
        val masked = code(region)
        val at = masked.indexOf(marker)
        require(at >= 0) { "'$marker' not found in the region being checked" }
        val open = masked.indexOf('(', at + marker.length)
        require(open >= 0) { "no '(' after '$marker'" }
        var depth = 0
        for (i in open until masked.length) {
            when (masked[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return splitTopLevel(region.substring(open + 1, i))
                }
            }
        }
        throw AssertionError("unbalanced parentheses after '$marker'")
    }

    /** Splits an argument list on commas that are not nested in brackets or literals. */
    fun splitTopLevel(args: String): List<String> {
        val masked = code(args)
        val out = mutableListOf<String>()
        var depth = 0
        var start = 0
        for (i in masked.indices) {
            when (masked[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ',' -> if (depth == 0) {
                    out += args.substring(start, i)
                    start = i + 1
                }
            }
        }
        out += args.substring(start)
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * The whole declaration of the first [marker] — from the start of its line
     * through the `}` that closes its first block — or `null` when it is absent.
     *
     * Brace matching runs on [code], so `'{'` inside a character literal (which is
     * exactly how a brace-matching helper is written) does not unbalance it. A
     * marker whose declaration has no block (an expression-bodied member) yields the
     * text from its line start to the end of its first block, which is what callers
     * need in order to look inside the body.
     */
    fun bracedBlock(text: String, marker: String): String? {
        val masked = code(text)
        val at = masked.indexOf(marker)
        if (at < 0) return null
        val open = masked.indexOf('{', at)
        if (open < 0) return null
        var depth = 0
        for (i in open until masked.length) {
            when (masked[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        var lineStart = at
                        while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
                        return text.substring(lineStart, i + 1)
                    }
                }
            }
        }
        return null
    }

    /** The parameter list of the first `…fun <name>(…)` in [text], or `null`. */
    fun parameterList(text: String, functionName: String): String? {
        val masked = code(text)
        val m = Regex("fun\\s+" + Regex.escape(functionName) + "\\s*\\(").find(masked) ?: return null
        val open = masked.indexOf('(', m.range.first)
        var depth = 0
        for (i in open until masked.length) {
            when (masked[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return text.substring(open + 1, i)
                }
            }
        }
        return null
    }

    /** Parameter names in [parameterList] whose declared type matches [typePrefix]. */
    fun parametersOfType(parameterList: String, typePrefix: String): List<String> =
        splitTopLevel(parameterList)
            .filter { Regex("^[A-Za-z_][A-Za-z0-9_]*\\s*:\\s*" + Regex.escape(typePrefix)).containsMatchIn(it) }
            .map { it.substringBefore(':').trim() }
}
