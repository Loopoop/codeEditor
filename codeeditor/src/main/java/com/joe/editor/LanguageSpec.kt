package com.joe.editor

/** Declarative description of a C-like / scripting language. Build your own and wrap it with [codeLanguage]. */
data class LanguageSpec(
    val keywords: Set<String> = emptySet(),
    val types: Set<String> = emptySet(),
    val builtins: Set<String> = emptySet(),
    val literals: Set<String> = emptySet(),
    val lineComments: List<String> = emptyList(),
    val blockComments: List<Pair<String, String>> = emptyList(),
    val nestedBlockComments: Boolean = false,
    val stringQuotes: String = "\"'",
    val multilineQuotes: List<String> = emptyList(),
    val rawMultiline: Boolean = false,
    val annotationPrefix: Char? = null,
    val variablePrefix: Char? = null,
    val identStartExtra: String = "",
    val identPartExtra: String = "",
    val caseInsensitive: Boolean = false,
    val typeHeuristic: Boolean = true,
    val operators: String = "+-*/%=<>!&|^~?:",
    val specialTokens: List<Pair<String, TokenType>> = emptyList(),
    val lifetimes: Boolean = false,
    val macroBang: Boolean = false,
    val hashAttributes: Boolean = false,
    val preprocessor: Boolean = false,
    val regexLiterals: Boolean = false,
    val stringKeys: Boolean = false,
    val keyColon: Boolean = false,
) {
    fun vocabulary(): List<Pair<String, TokenType>> =
        keywords.map { it to TokenType.Keyword } + types.map { it to TokenType.Type } +
            builtins.map { it to TokenType.Builtin } + literals.map { it to TokenType.Literal }
}

internal fun CharSequence.at(i: Int, p: String): Boolean {
    if (i < 0 || i + p.length > length) return false
    for (k in p.indices) if (this[i + k] != p[k]) return false
    return true
}

internal class SpecScanner(private val s: LanguageSpec) {
    private val blocks = s.blockComments.sortedByDescending { it.first.length }
    private val lines = s.lineComments.sortedByDescending { it.length }
    private val multis = s.multilineQuotes.sortedByDescending { it.length }
    private val specials = s.specialTokens.sortedByDescending { it.first.length }
    private val regexPrev = "(,=:[!&|?{};+-*%<>~^"
    private val regexWords = setOf("return", "typeof", "case", "in", "of", "delete", "void", "throw", "new", "else")

    private fun nextNonSpace(t: CharSequence, from: Int): Int {
        var k = from
        while (k < t.length && (t[k] == ' ' || t[k] == '\t')) k++
        return k
    }

    private fun isIdentStart(c: Char) = c.isLetter() || c == '_' || s.identStartExtra.indexOf(c) >= 0
    private fun isIdentPart(c: Char) = c.isLetterOrDigit() || c == '_' || s.identPartExtra.indexOf(c) >= 0

    fun scan(t: CharSequence): ScanResult {
        val n = t.length
        val tokens = ArrayList<Token>(n / 5 + 8)
        val issues = ArrayList<Diagnostic>()
        var i = 0
        var prev = '\u0000'
        var prevWord = ""

        fun add(a: Int, b: Int, type: TokenType, code: Boolean = true) {
            if (b <= a) return
            tokens.add(Token(a, b, type))
            if (code) { prev = t[b - 1]; prevWord = "" }
        }

        while (i < n) {
            val c = t[i]
            if (c.isWhitespace()) { i++; continue }

            // special tokens (e.g. <?php)
            val sp = specials.firstOrNull { t.at(i, it.first) }
            if (sp != null) { add(i, i + sp.first.length, sp.second); i += sp.first.length; continue }

            // block comments
            val bc = blocks.firstOrNull { t.at(i, it.first) }
            if (bc != null) {
                var j = i + bc.first.length
                var depth = 1
                var closed = false
                while (j < n) {
                    if (s.nestedBlockComments && t.at(j, bc.first)) { depth++; j += bc.first.length }
                    else if (t.at(j, bc.second)) { depth--; j += bc.second.length; if (depth == 0) { closed = true; break } }
                    else j++
                }
                val doc = bc.first == "/*" && t.at(i, "/**") && !t.at(i, "/**/")
                add(i, j, if (doc) TokenType.DocComment else TokenType.Comment, code = false)
                if (!closed) issues.add(Diagnostic(i, minOf(i + bc.first.length, n), "Unterminated comment"))
                i = j; continue
            }

            // line comments
            val lc = lines.firstOrNull { t.at(i, it) }
            if (lc != null) {
                var j = i
                while (j < n && t[j] != '\n') j++
                val doc = lc == "//" && ((t.at(i, "///") && !t.at(i, "////")) || t.at(i, "//!"))
                add(i, j, if (doc) TokenType.DocComment else TokenType.Comment, code = false)
                i = j; continue
            }

            // multi-line strings
            val mq = multis.firstOrNull { t.at(i, it) }
            if (mq != null) {
                var j = i + mq.length
                var closed = false
                while (j < n) {
                    if (!s.rawMultiline && t[j] == '\\') { j += 2; continue }
                    if (t.at(j, mq)) { j += mq.length; closed = true; break }
                    j++
                }
                if (j > n) j = n
                add(i, j, TokenType.Str)
                if (!closed) issues.add(Diagnostic(i, minOf(i + mq.length, n), "Unterminated string literal"))
                i = j; continue
            }

            // single-line strings / chars
            if (s.stringQuotes.indexOf(c) >= 0) {
                if (c == '\'' && s.lifetimes && i + 1 < n && (t[i + 1].isLetter() || t[i + 1] == '_')) {
                    var j = i + 1
                    while (j < n && (t[j].isLetterOrDigit() || t[j] == '_')) j++
                    if (j >= n || t[j] != '\'') { add(i, j, TokenType.Meta); i = j; continue }
                }
                var j = i + 1
                var closed = false
                while (j < n) {
                    val d = t[j]
                    if (d == '\\') { j += 2; continue }
                    if (d == c) { j++; closed = true; break }
                    if (d == '\n') break
                    j++
                }
                if (j > n) j = n
                val isKey = s.stringKeys && closed && nextNonSpace(t, j).let { it < n && t[it] == ':' }
                add(i, j, if (isKey) TokenType.Property else TokenType.Str)
                if (!closed) issues.add(Diagnostic(i, minOf(i + 1, n), "Unterminated string literal"))
                i = j; continue
            }

            // Rust attributes #[...]
            if (s.hashAttributes && c == '#' && (t.at(i, "#[") || t.at(i, "#!["))) {
                var j = t.indexOf('[', i)
                var depth = 0
                while (j < n) {
                    if (t[j] == '[') depth++
                    else if (t[j] == ']') { depth--; if (depth == 0) { j++; break } }
                    j++
                }
                add(i, minOf(j, n), TokenType.Meta); i = minOf(j, n); continue
            }

            // C preprocessor
            if (s.preprocessor && c == '#') {
                var j = i + 1
                while (j < n && (t[j] == ' ' || t[j] == '\t')) j++
                val ws = j
                while (j < n && t[j].isLetter()) j++
                val word = t.subSequence(ws, j).toString()
                add(i, j, TokenType.Meta)
                if (word == "include" || word == "import") {
                    val k = nextNonSpace(t, j)
                    if (k < n && t[k] == '<') {
                        var e = k
                        while (e < n && t[e] != '>' && t[e] != '\n') e++
                        if (e < n && t[e] == '>') e++
                        add(k, e, TokenType.Str); j = e
                    }
                }
                i = j; continue
            }

            // regex literal (JS)
            if (c == '/' && s.regexLiterals && (prev == '\u0000' || regexPrev.indexOf(prev) >= 0 || prevWord in regexWords)) {
                var j = i + 1
                var inClass = false
                var ok = false
                while (j < n) {
                    val d = t[j]
                    if (d == '\n') break
                    if (d == '\\') { j += 2; continue }
                    if (d == '[') inClass = true
                    else if (d == ']') inClass = false
                    else if (d == '/' && !inClass) { ok = true; j++; break }
                    j++
                }
                if (ok) {
                    while (j < n && t[j].isLetter()) j++
                    add(i, j, TokenType.Str); i = j; continue
                }
            }

            // annotations / decorators
            if (s.annotationPrefix == c && i + 1 < n && isIdentStart(t[i + 1])) {
                var j = i + 1
                while (j < n && (isIdentPart(t[j]) || t[j] == '.')) j++
                add(i, j, TokenType.Meta); i = j; continue
            }

            // variables ($name, ${name})
            if (s.variablePrefix == c && i + 1 < n && (isIdentStart(t[i + 1]) || t[i + 1] == '{')) {
                var j = i + 1
                if (t[j] == '{') { while (j < n && t[j] != '}' && t[j] != '\n') j++; if (j < n && t[j] == '}') j++ }
                else while (j < n && isIdentPart(t[j])) j++
                add(i, j, TokenType.Variable); i = j; continue
            }

            // numbers
            if (c.isDigit() || (c == '.' && i + 1 < n && t[i + 1].isDigit())) {
                val hex = t.at(i, "0x") || t.at(i, "0X")
                var j = i + 1
                while (j < n) {
                    val d = t[j]
                    if (d.isLetterOrDigit() || d == '_') j++
                    else if (d == '.' && j + 1 < n && t[j + 1].isDigit()) j++
                    else if ((d == '+' || d == '-') && !hex && (t[j - 1] == 'e' || t[j - 1] == 'E')) j++
                    else break
                }
                add(i, j, TokenType.Num); i = j; continue
            }

            // identifiers / keywords
            if (isIdentStart(c)) {
                var j = i + 1
                while (j < n && isIdentPart(t[j])) j++
                val w = t.subSequence(i, j).toString()
                if (s.macroBang && j < n && t[j] == '!' && !(j + 1 < n && t[j + 1] == '=')) {
                    add(i, j + 1, TokenType.Builtin); i = j + 1; continue
                }
                val key = if (s.caseInsensitive) w.lowercase() else w
                val k = nextNonSpace(t, j)
                val type = when {
                    key in s.keywords -> TokenType.Keyword
                    key in s.literals -> TokenType.Literal
                    key in s.types -> TokenType.Type
                    key in s.builtins -> TokenType.Builtin
                    s.keyColon && k < n && t[k] == ':' && (k + 1 >= n || t[k + 1].isWhitespace()) -> TokenType.Property
                    s.typeHeuristic && w[0].isUpperCase() && w.any { it.isLowerCase() } -> TokenType.Type
                    k < n && t[k] == '(' -> TokenType.Func
                    s.typeHeuristic && w.length > 1 && w[0].isUpperCase() && w.all { it.isUpperCase() || it.isDigit() || it == '_' } -> TokenType.Literal
                    else -> TokenType.Plain
                }
                add(i, j, type); prevWord = w
                i = j; continue
            }

            // operators
            if (s.operators.indexOf(c) >= 0) {
                var j = i + 1
                while (j < n && s.operators.indexOf(t[j]) >= 0 &&
                    !(t[j] == '/' && j + 1 < n && (t[j + 1] == '/' || t[j + 1] == '*'))
                ) j++
                add(i, j, TokenType.Operator); i = j; continue
            }

            if ("{}()[];,.".indexOf(c) >= 0) { add(i, i + 1, TokenType.Punct); i++; continue }
            prev = c
            i++
        }
        return ScanResult(tokens, issues)
    }
}
