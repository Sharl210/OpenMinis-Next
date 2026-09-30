package com.openminis.app.shared

/**
 * Reading Kotlin **source text** in tests — one implementation, so that "what does
 * this file call?" and "what does this file merely mention?" cannot drift between
 * test classes.
 *
 * ## Why this exists
 *
 * Several source-shape tests grew their own copy of the same helpers. Each copy was
 * written at a different time, so they disagree on the two decisions that actually
 * matter, and both mistakes were measured on a fake source tree:
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
 *
 * ## What this can and cannot do
 *
 * A first version of this file was put through an adversarial review that attacked
 * it with Kotlin constructs rather than with the shapes it was written for. Three
 * findings were real and are fixed here, each with a regression sample in
 * [KotlinSourceTextTest]:
 *
 *  * **String templates were lexed as opaque text.** `"${json.getString("error")}"`
 *    leaked `error` out of the inner literal *and* swallowed real calls written
 *    after it on the same line (`"${failures.joinToString(", ")}"` reported zero
 *    references). Templates are now lexed as **code** — the expression inside `${…}`
 *    is scanned normally, nested literals included. This is also why [references]
 *    now sees calls inside templates.
 *  * **[bracedBlock] followed the first `{` after the marker**, so an
 *    expression-bodied declaration swallowed the *next* declaration's whole body
 *    (measured on `ChatViewModel.kt`: `fun sendMessage(text: String) = …` returned
 *    the body of the other `sendMessage`, 19,939 characters), and a lambda in a
 *    parameter default truncated the real body. The block is now located from the
 *    **end of the parameter list**, and an expression body ends at the first newline
 *    at bracket depth 0 that is not more-indented than the declaration.
 *  * **[parameterList] was blind to generics and extension receivers** — and worse,
 *    silently answered with a *different* same-named function's parameters. Both
 *    forms are matched now.
 *
 * Known limits, stated rather than hidden:
 *
 *  * [splitTopLevel] treats `<` as a type-argument opener only when it is **glued**
 *    to a name on the left and to a type on the right and has a matching `>` at the
 *    same depth. That is right for `Map<String, List<Int>>` and for `a < b`, but an
 *    expression like `f(a<b, c>d)` is still split as if `<` opened type arguments.
 *    Do not use this to split *expression* code.
 *  * A raw string ending in quotes (`""""`) is consumed to the end of the quote run;
 *    the leading quotes-before-the-terminator are treated as content, which matches
 *    Kotlin's rule for the common cases.
 *  * [bracedBlock] returns the **first** declaration matching the marker. When a
 *    file overloads a name, pass the full signature (parameter list included) so the
 *    marker identifies one declaration — see the note on that method.
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
    fun code(text: String): String = Scanner(text, blankLiterals = true).run()

    /**
     * Comment bodies replaced by spaces, string literals kept.
     *
     * This is the mask to use for "does the code quote the name X" — a tool name or
     * a JSON key that legitimately exists only as a literal. It must **not** be used
     * to decide reachability: a name in a log line is not a call.
     */
    fun noComments(text: String): String = Scanner(text, blankLiterals = false).run()

    /**
     * The lexer behind both masks.
     *
     * Blanking is expressed as "write a space over the original character" so that
     * length, offsets and newlines survive by construction — the invariant the
     * callers depend on when they slice the **original** text at an offset found in a
     * mask. Newlines are never overwritten.
     *
     * Comments are blanked in both modes. Literal bodies are blanked only in [code];
     * they are still *scanned* in both modes, because a `//` inside a string is not a
     * comment and a `'` inside a backtick identifier is not a char literal.
     */
    private class Scanner(private val src: String, private val blankLiterals: Boolean) {
        private val out = src.toCharArray()

        fun run(): String {
            var i = 0
            while (i < src.length) i = scan(i)
            return String(out)
        }

        /** Blanks the literal body at [j] when the mask erases literals; keeps newlines. */
        private fun erase(j: Int) {
            if (blankLiterals && src[j] != '\n') out[j] = ' '
        }

        /** Blanks unconditionally — used for comment text, which both masks remove. */
        private fun hardBlank(j: Int) {
            if (src[j] != '\n') out[j] = ' '
        }

        /** One unit of code. Returns the index just past it. */
        private fun scan(i: Int): Int {
            val c = src[i]
            val next = src.getOrNull(i + 1)
            return when {
                c == '/' && next == '/' -> lineComment(i)
                c == '/' && next == '*' -> blockComment(i)
                c == '"' && src.startsWith(TRIPLE, i) -> rawString(i)
                c == '"' -> string(i)
                c == '\'' -> charLiteral(i)
                c == '`' -> backtickIdentifier(i)
                else -> i + 1
            }
        }

        private fun lineComment(i: Int): Int {
            var j = i
            while (j < src.length && src[j] != '\n') {
                hardBlank(j)
                j++
            }
            return j
        }

        /**
         * A block comment. Kotlin's block comments **nest**, so the depth is counted:
         * stopping at the first closing delimiter would leak the tail of a nested
         * comment back into the mask as if it were code.
         */
        private fun blockComment(i: Int): Int {
            var j = i
            var depth = 0
            while (j < src.length) {
                when {
                    src.startsWith("/*", j) -> {
                        hardBlank(j)
                        hardBlank(j + 1)
                        depth++
                        j += 2
                    }

                    src.startsWith("*/", j) -> {
                        hardBlank(j)
                        hardBlank(j + 1)
                        depth--
                        j += 2
                        if (depth == 0) return j
                    }

                    else -> {
                        hardBlank(j)
                        j++
                    }
                }
            }
            return j
        }

        /**
         * `"…"`. The body is blanked, but `${…}` switches back to **code**: the
         * expression inside a template is executed by the program, so its calls are
         * reachability and its nested literals must be erased in turn.
         */
        private fun string(i: Int): Int {
            erase(i)
            var j = i + 1
            while (j < src.length) {
                val c = src[j]
                when {
                    c == '\\' -> {
                        erase(j)
                        if (j + 1 < src.length) erase(j + 1)
                        j += 2
                    }

                    c == '\n' -> return j // unterminated literal; stay lenient
                    c == '"' -> {
                        erase(j)
                        return j + 1
                    }

                    c == '$' && src.getOrNull(j + 1) == '{' -> {
                        erase(j)
                        erase(j + 1)
                        j = template(j + 2)
                    }

                    else -> {
                        erase(j)
                        j++
                    }
                }
            }
            return j
        }

        /** `"""…"""`. Templates work here too; nothing else is escaped. */
        private fun rawString(i: Int): Int {
            erase(i)
            erase(i + 1)
            erase(i + 2)
            var j = i + 3
            while (j < src.length) {
                if (src[j] == '"') {
                    var k = j
                    while (k < src.length && src[k] == '"') k++
                    if (k - j >= 3) {
                        for (t in j until k) erase(t)
                        return k
                    }
                }
                if (src[j] == '$' && src.getOrNull(j + 1) == '{') {
                    erase(j)
                    erase(j + 1)
                    j = template(j + 2)
                    continue
                }
                erase(j)
                j++
            }
            return j
        }

        /**
         * The expression of a `${…}` template, entered with [i] just past the `{`.
         * Body text is **code**: it is scanned normally, so nested literals are erased
         * and calls remain visible.
         */
        private fun template(i: Int): Int {
            var j = i
            var depth = 1
            while (j < src.length) {
                when {
                    src[j] == '{' -> {
                        depth++
                        j++
                    }

                    src[j] == '}' -> {
                        depth--
                        if (depth == 0) {
                            erase(j)
                            return j + 1
                        }
                        j++
                    }

                    else -> j = scan(j)
                }
            }
            return j
        }

        private fun charLiteral(i: Int): Int {
            erase(i)
            var j = i + 1
            if (j < src.length && src[j] == '\\') {
                erase(j)
                if (j + 1 < src.length) erase(j + 1)
                j += 2
            } else if (j < src.length) {
                erase(j)
                j++
            }
            if (j < src.length && src[j] == '\'') {
                erase(j)
                j++
            }
            return j
        }

        /**
         * `` `…` `` — an identifier, not a literal, so it stays in the mask. Scanning
         * it as a unit is what keeps an apostrophe inside the name
         * (`` fun `the user's values`() ``, which Kotlin accepts) from starting a char
         * literal that would swallow the rest of the line.
         */
        private fun backtickIdentifier(i: Int): Int {
            var j = i + 1
            while (j < src.length && src[j] != '`' && src[j] != '\n') j++
            return if (j < src.length && src[j] == '`') j + 1 else j
        }

        private companion object {
            const val TRIPLE = "\"\"\""
        }
    }

    /** 1-based line number of [offset] in [text]. */
    fun lineOf(text: String, offset: Int): Int =
        text.substring(0, offset.coerceIn(0, text.length)).count { it == '\n' } + 1

    /** Start of the line containing [offset]. */
    private fun lineStart(text: String, offset: Int): Int {
        var i = offset.coerceIn(0, text.length)
        while (i > 0 && text[i - 1] != '\n') i--
        return i
    }

    /** Leading spaces/tabs of the line containing [offset]. */
    private fun indentation(text: String, offset: Int): Int {
        var i = lineStart(text, offset)
        var n = 0
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) {
            n++
            i++
        }
        return n
    }

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
     *
     * The boundary test is Unicode-aware (`\p{L}\p{N}`), so an identifier like `café`
     * is matched whole instead of stopping at the first non-ASCII letter.
     */
    fun references(text: String, name: String, file: String = ""): List<Reference> {
        require(name.isNotEmpty()) { "pass a name, not the empty string — every boundary matches" }
        val masked = code(text)
        val pattern = Regex(
            "(?<![\\p{L}\\p{N}_])" + Regex.escape(name) + "(?![\\p{L}\\p{N}_])",
        )
        return pattern.findAll(masked).mapNotNull { m ->
            val before = masked.substring(0, m.range.first)
            val token = before.trimEnd().takeLastWhile { !it.isWhitespace() }
            if (token == "fun") return@mapNotNull null
            Reference(file, lineOf(text, m.range.first), text.substring(m.range.first, m.range.last + 1))
        }.toList()
    }

    /**
     * The whole declaration of the first [marker] — from the start of its line through
     * the end of its body — or `null` when it cannot be determined.
     *
     * **The body starts after the parameter list, not after the marker.** Following
     * the first `{` from the marker is wrong twice over, and both were measured:
     *
     *  * a lambda in a parameter default (`fun f(cb: () -> Unit = { }) { work() }`)
     *    truncated the block before `work()`;
     *  * an expression body swallowed the next declaration
     *    (`fun f() = 42` + `fun after() { purge() }` returned both), which on
     *    `ChatViewModel.kt` merged two different `sendMessage` overloads into one
     *    19,939-character block — so "must call X inside sendMessage" could be
     *    satisfied by the *other* function.
     *
     * An expression body ends at the first newline at bracket depth 0 whose next line
     * is not more indented than the declaration, which is what keeps a sibling
     * declaration out.
     *
     * **Overloads:** the *first* match wins, so pass a marker that identifies one
     * declaration — include the parameter list when a file overloads the name, e.g.
     * `"sendMessage(text: String, skipContextCheck: Boolean)"`. A bare name is only
     * safe when it is unique in the file.
     *
     * Returns `null` rather than a guess when the declaration has no determinable
     * body; callers should treat `null` as a failure, never as "empty".
     */
    fun bracedBlock(text: String, marker: String): String? {
        val masked = code(text)
        val at = declarationIndexOf(masked, marker)
        if (at < 0) return null

        val parenFrom = if (marker.endsWith("(")) at + marker.length - 1 else at
        val open = masked.indexOf('(', parenFrom)
        if (open < 0) return null
        val close = matchingBracket(masked, open, '(', ')') ?: return null

        var j = close + 1
        while (j < masked.length && masked[j].isWhitespace()) j++

        // An explicit return type may sit between the parameter list and the body.
        if (masked.getOrNull(j) == ':') {
            var k = j + 1
            var depth = 0
            while (k < masked.length) {
                val c = masked[k]
                when {
                    c == '(' || c == '[' || c == '{' || c == '<' -> depth++
                    c == ')' || c == ']' || c == '}' || c == '>' -> depth--
                    depth == 0 && (c == '{' || c == '=') -> break
                }
                k++
            }
            j = k
        }

        val start = lineStart(text, at)
        return when (masked.getOrNull(j)) {
            '{' -> {
                val end = matchingBracket(masked, j, '{', '}') ?: return null
                text.substring(start, end + 1)
            }

            '=' -> {
                val end = endOfExpression(masked, text, j, start) ?: return null
                text.substring(start, end)
            }

            else -> null
        }
    }

    /**
     * Where [marker] starts **as a declaration**, preferring a `fun` declaration over
     * an earlier call site.
     *
     * A bare marker is ambiguous in exactly the way that matters: `"resumeRun("`
     * appears first as a *call* from the trigger that schedules it, and only later as
     * the declaration — so searching for the marker alone found the call, failed to
     * see a body after the argument list, and returned `null` for a function that has
     * a perfectly good body 14,000 lines further down. Callers that pass a full
     * signature (`"sendMessage(text: String, skipContextCheck: Boolean)"`) are not
     * affected, but a bare name must not silently point at its own call sites.
     */
    private fun declarationIndexOf(masked: String, marker: String): Int {
        if (marker.contains("fun")) return masked.indexOf(marker)
        val asDeclaration = Regex(
            "fun\\s+" +
                "(?:<[^>]*>\\s*)?" +
                "(?:[\\w?.]+(?:<[^>]*>)?\\s*\\.\\s*)?" +
                Regex.escape(marker),
        ).find(masked)
        return asDeclaration?.range?.first ?: masked.indexOf(marker)
    }

    /**
     * Index of the character that closes [open], ignoring anything inside strings or
     * comments (which [code] has already blanked) and nothing else.
     */
    private fun matchingBracket(text: String, open: Int, oc: Char, cc: Char): Int? {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                oc -> depth++
                cc -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }

    /**
     * End index of an expression-body declaration that starts at [eq], stopping before
     * a sibling declaration. Never returns past a newline at depth 0 whose next line
     * is indented no deeper than [declStart]'s line.
     */
    private fun endOfExpression(masked: String, original: String, eq: Int, declStart: Int): Int? {
        val declIndent = indentation(original, declStart)
        var depth = 0
        var i = eq + 1
        var lastContent = eq + 1
        while (i < masked.length) {
            var consumed = false
            when (masked[i]) {
                '(', '[', '{' -> {
                    depth++
                    consumed = true
                }

                ')', ']', '}' -> {
                    depth--
                    consumed = true
                }

                '\n' -> if (depth <= 0) {
                    var j = i + 1
                    while (j < masked.length && masked[j].isWhitespace()) j++
                    if (j >= masked.length) return i
                    if (indentation(original, j) <= declIndent) return i
                }
            }
            if (!masked[i].isWhitespace()) lastContent = i + 1
            i++
            if (!consumed && i >= masked.length) break
        }
        return if (lastContent > eq + 1) lastContent else null
    }

    /**
     * The arguments of the first call to [marker] in [region], split on top-level
     * commas.
     *
     * [marker] is a **bare name**: whole-identifier checked, so `"f"` does not match
     * `if (…)` or `printf(…)` (which were measured returning the `if`'s condition and
     * `printf`'s arguments respectively). A marker containing `(` is rejected rather
     * than silently resolving to a different call.
     *
     * Parentheses are matched against [code], so a `)` inside a string cannot end the
     * call early, and the split ignores commas nested in another call — which is what
     * makes an argument *count* meaningful (`minOf(a, b)` is one argument).
     */
    fun arguments(region: String, marker: String): List<String> {
        require(!marker.contains('(')) {
            "pass a bare call name, not '$marker' — a marker with '(' would silently match a " +
                "different call's argument list"
        }
        require(marker.isNotEmpty()) { "pass a call name" }
        val masked = code(region)
        val at = Regex("(?<![\\p{L}\\p{N}_])" + Regex.escape(marker) + "\\s*\\(").find(masked)?.range?.first
            ?: throw AssertionError("no call to '$marker(' in the region being checked")
        val open = masked.indexOf('(', at)
        val close = matchingBracket(masked, open, '(', ')')
            ?: throw AssertionError("unbalanced parentheses in the call to '$marker('")
        return splitTopLevel(region.substring(open + 1, close))
    }

    /**
     * Splits an argument or parameter list on commas that are not nested in brackets.
     *
     * Angle brackets are counted too, so a generic type does not split
     * (`m: Map<String, List<Int>>, b: Int` is two, not three) — measured: ignoring
     * them made [parametersOfType] answer `[]` for exactly the types it was asked
     * about. Because `<` is also the less-than operator, a `<` counts only when it is
     * *glued* to a name on the left and to a type on the right **and** has a matching
     * `>` at the same depth; that is right for both `Map<String, List<Int>>` and
     * `a < b`. It is still wrong for expression code such as `f(a<b, c>d)` — use this
     * for declarations, not for expressions.
     */
    fun splitTopLevel(args: String): List<String> {
        val masked = code(args)
        val out = mutableListOf<String>()
        var depth = 0
        var angle = 0
        var start = 0
        for (i in masked.indices) {
            when (masked[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                '<' -> if (depth == 0 && opensTypeArguments(masked, i)) angle++
                '>' -> if (depth == 0 && angle > 0) angle--
                ',' -> if (depth == 0 && angle == 0) {
                    out += args.substring(start, i)
                    start = i + 1
                }
            }
        }
        out += args.substring(start)
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** See [splitTopLevel] for why this is a heuristic and where it is wrong. */
    private fun opensTypeArguments(s: String, i: Int): Boolean {
        val prev = s.getOrNull(i - 1) ?: return false
        if (!(prev.isLetterOrDigit() || prev == '_' || prev == '>' || prev == '?' || prev == '.')) return false
        val next = s.getOrNull(i + 1) ?: return false
        if (!(next.isLetterOrDigit() || next == '_' || next == '*' || next == '?' ||
                next == '@' || next == '(' || next == '[')
        ) {
            return false
        }
        var depth = 0
        var j = i + 1
        while (j < s.length) {
            when (s[j]) {
                '<' -> depth++
                '>' -> if (depth == 0) return true else depth--
                '(', ')', '{', '}', '[', ']', ';', '\n' -> if (depth == 0) return false
            }
            j++
        }
        return false
    }

    /**
     * The parameter list of the first declaration of [functionName] — generics before
     * the name (`fun <T> f(…)`), an extension receiver (`fun Flow<X>.f(…)`) and
     * generics on the name (`fun f<T>(…)`) all included — or `null`.
     *
     * Both of those forms used to return `null`, and the failure was worse than a
     * miss: when a plain same-named declaration followed, the *other* function's
     * parameters were returned silently.
     */
    fun parameterList(text: String, functionName: String): String? {
        val masked = code(text)
        val name = Regex.escape(functionName)
        val declaration = Regex(
            "fun\\s+" +
                "(?:<[^>]*>\\s*)?" + // fun <T> f(
                "(?:[\\w?.]+(?:<[^>]*>)?\\s*\\.\\s*)?" + // fun Receiver.f(
                name +
                "\\s*(?:<[^>]*>)?" + // fun f<T>(
                "\\s*\\(",
        )
        val m = declaration.find(masked) ?: return null
        val open = masked.indexOf('(', m.range.first)
        val close = matchingBracket(masked, open, '(', ')') ?: return null
        return text.substring(open + 1, close)
    }

    /**
     * Parameter names in [parameterList] whose declared type starts with [typePrefix].
     *
     * Modifiers that sit between the comma and the name — `vararg`, `suspend`,
     * `crossinline`, `noinline`, `val`/`var` for constructor parameters, and
     * annotations — are skipped. A type that itself contains the delimiter text is not
     * supported; pass the leading part of the type (`"AgentCapabilitySnapshot?"`), or
     * match on a distinctive prefix.
     */
    fun parametersOfType(parameterList: String, typePrefix: String): List<String> =
        splitTopLevel(parameterList).mapNotNull { param ->
            val m = PARAMETER.find(param.trim()) ?: return@mapNotNull null
            val (name, type) = m.destructured
            if (type.trim().startsWith(typePrefix)) name else null
        }

    private val PARAMETER = Regex(
        "^(?:@[\\w.]+(?:\\([^)]*\\))?\\s*)*" + // @Named("a,b")
            "(?:(?:vararg|crossinline|noinline|suspend|override|private|public|internal)\\s+)*" +
            "(?:(?:val|var)\\s+)?" +
            "([\\w`]+)\\s*:\\s*(.+)$",
        RegexOption.DOT_MATCHES_ALL,
    )
}
