package com.joe.editor

enum class Severity { Error, Warning, Info }

data class Diagnostic(
    val start: Int,
    val end: Int,
    val message: String,
    val severity: Severity = Severity.Error,
)

/** Plug in your own linter / LSP bridge. Runs off the main thread. */
fun interface DiagnosticsProvider {
    suspend fun diagnose(text: String, language: Language): List<Diagnostic>
}

object Diagnostics {
    /** Built-in checks: unterminated strings/comments, bracket balance, JSON syntax, HTML/XML tag matching, TODO notes. */
    fun analyze(text: String, language: Language, scan: ScanResult): List<Diagnostic> {
        val out = ArrayList<Diagnostic>()
        when {
            language.id == "json" -> JsonValidator.validate(text)?.let { out.add(it) }
            language.isMarkup -> {
                out.addAll(scan.issues)
                out.addAll(MarkupChecker.check(text, language.id == "html"))
            }
            else -> {
                out.addAll(scan.issues)
                if (language.checkBrackets && language.pairs.isNotEmpty()) {
                    out.addAll(BracketChecker.check(text, scan, language))
                }
            }
        }
        for (tk in scan.tokens) {
            if (tk.type != TokenType.Comment && tk.type != TokenType.DocComment) continue
            val seg = text.substring(tk.start, tk.end)
            for (kw in listOf("TODO", "FIXME", "HACK", "XXX")) {
                val idx = seg.indexOf(kw)
                if (idx >= 0) {
                    out.add(Diagnostic(tk.start + idx, tk.start + idx + kw.length, "$kw note", Severity.Info))
                    break
                }
            }
        }
        return normalize(out, text.length)
    }

    fun normalize(list: List<Diagnostic>, len: Int): List<Diagnostic> {
        if (len == 0) return emptyList()
        return list.map {
            var s = it.start.coerceIn(0, len)
            if (s >= len) s = len - 1
            val e = it.end.coerceIn(s + 1, len)
            it.copy(start = s, end = e)
        }.sortedBy { it.start }.take(500)
    }
}

internal object BracketChecker {
    fun check(text: String, scan: ScanResult, lang: Language): List<Diagnostic> {
        val out = ArrayList<Diagnostic>()
        val open = lang.pairs
        val close = lang.closers
        val stack = ArrayList<Int>()
        val skip = BooleanArray(text.length)
        for (t in scan.tokens) {
            if (t.type == TokenType.Str || t.type == TokenType.Comment || t.type == TokenType.DocComment) {
                for (k in t.start until minOf(t.end, text.length)) skip[k] = true
            }
        }
        for (i in text.indices) {
            if (skip[i]) continue
            val c = text[i]
            if (open.containsKey(c)) {
                stack.add(i)
            } else if (close.contains(c)) {
                if (stack.isEmpty()) {
                    out.add(Diagnostic(i, i + 1, "Unmatched '$c'"))
                } else {
                    val o = stack[stack.size - 1]
                    if (open[text[o]] == c) {
                        stack.removeAt(stack.size - 1)
                    } else {
                        out.add(Diagnostic(i, i + 1, "Expected '${open[text[o]]}' but found '$c'"))
                        val idx = stack.indexOfLast { open[text[it]] == c }
                        if (idx >= 0) {
                            while (stack.size > idx + 1) {
                                val u = stack.removeAt(stack.size - 1)
                                out.add(Diagnostic(u, u + 1, "Unclosed '${text[u]}'"))
                            }
                            stack.removeAt(stack.size - 1)
                        }
                    }
                }
            }
            if (out.size > 50) break
        }
        for (u in stack) out.add(Diagnostic(u, u + 1, "Unclosed '${text[u]}'"))
        return out
    }
}

internal object MarkupChecker {
    private val VOID = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")
    private val OPTIONAL_END = setOf("p", "li", "dt", "dd", "tr", "td", "th", "thead", "tbody", "tfoot", "option", "optgroup", "colgroup", "body", "html", "head")

    fun check(text: String, html: Boolean): List<Diagnostic> {
        val out = ArrayList<Diagnostic>()
        val stack = ArrayList<Pair<String, Int>>()
        val n = text.length
        var i = 0
        fun unclosed(p: Pair<String, Int>) {
            if (html && p.first in OPTIONAL_END) return
            out.add(Diagnostic(p.second, p.second + 1 + p.first.length, "Unclosed <${p.first}>"))
        }
        while (i < n) {
            val lt = text.indexOf('<', i)
            if (lt < 0) break
            if (text.startsWith("<!--", lt)) {
                val e = text.indexOf("-->", lt + 4); i = if (e < 0) n else e + 3; continue
            }
            if (text.startsWith("<![CDATA[", lt)) {
                val e = text.indexOf("]]>", lt); i = if (e < 0) n else e + 3; continue
            }
            if (text.startsWith("<!", lt) || text.startsWith("<?", lt)) {
                val e = text.indexOf('>', lt); i = if (e < 0) n else e + 1; continue
            }
            var j = lt + 1
            val closing = text.getOrNull(j) == '/'
            if (closing) j++
            val ns = j
            while (j < n && (text[j].isLetterOrDigit() || text[j] == '-' || text[j] == ':' || text[j] == '_' || text[j] == '.')) j++
            if (j == ns || !text[ns].isLetter()) { i = lt + 1; continue }
            var name = text.substring(ns, j)
            if (html) name = name.lowercase()
            var k = j
            var q = '\u0000'
            while (k < n) {
                val c = text[k]
                if (q != '\u0000') { if (c == q) q = '\u0000' }
                else if (c == '"' || c == '\'') q = c
                else if (c == '>') break
                k++
            }
            if (k >= n) { out.add(Diagnostic(lt, minOf(lt + 1 + name.length, n), "Unterminated tag <$name")); break }
            val selfClosing = text[k - 1] == '/'
            i = k + 1
            if (closing) {
                val idx = stack.indexOfLast { it.first == name }
                if (idx < 0) {
                    out.add(Diagnostic(lt, k + 1, "Unexpected closing tag </$name>"))
                } else {
                    while (stack.size > idx + 1) unclosed(stack.removeAt(stack.size - 1))
                    stack.removeAt(stack.size - 1)
                }
            } else if (!selfClosing && !(html && name in VOID)) {
                if (html && name in OPTIONAL_END && stack.isNotEmpty() && stack[stack.size - 1].first == name) {
                    stack.removeAt(stack.size - 1)
                }
                stack.add(name to lt)
                if (html && (name == "script" || name == "style")) {
                    val e = text.indexOf("</$name", i, ignoreCase = true)
                    i = if (e < 0) n else e
                }
            }
            if (out.size > 50) break
        }
        for (p in stack) unclosed(p)
        return out
    }
}

internal object JsonValidator {
    private class Err(val pos: Int, message: String) : Exception(message)

    fun validate(text: String): Diagnostic? {
        if (text.isBlank()) return null
        return try {
            val p = P(text)
            p.ws(); p.value(0); p.ws()
            if (p.i < text.length) throw Err(p.i, "Unexpected data after JSON value")
            null
        } catch (e: Err) {
            Diagnostic(e.pos, e.pos + 1, e.message ?: "Invalid JSON")
        }
    }

    private class P(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun peek(): Char? = s.getOrNull(i)

        fun value(depth: Int) {
            if (depth > 200) throw Err(i, "Nesting too deep")
            val c = peek() ?: throw Err(s.length, "Unexpected end of JSON")
            when {
                c == '{' -> obj(depth)
                c == '[' -> arr(depth)
                c == '"' -> str()
                c == '-' || c.isDigit() -> num()
                s.startsWith("true", i) -> i += 4
                s.startsWith("false", i) -> i += 5
                s.startsWith("null", i) -> i += 4
                else -> throw Err(i, "Unexpected character '$c'")
            }
        }

        fun obj(depth: Int) {
            i++; ws()
            if (peek() == '}') { i++; return }
            while (true) {
                ws()
                if (peek() != '"') throw Err(i.coerceAtMost(s.length), "Expected property name in double quotes")
                str(); ws()
                if (peek() != ':') throw Err(i, "Expected ':' after property name")
                i++; ws(); value(depth + 1); ws()
                when (peek()) {
                    ',' -> { i++; ws(); if (peek() == '}') throw Err(i, "Trailing comma is not allowed in JSON") }
                    '}' -> { i++; return }
                    else -> throw Err(i, "Expected ',' or '}'")
                }
            }
        }

        fun arr(depth: Int) {
            i++; ws()
            if (peek() == ']') { i++; return }
            while (true) {
                ws(); value(depth + 1); ws()
                when (peek()) {
                    ',' -> { i++; ws(); if (peek() == ']') throw Err(i, "Trailing comma is not allowed in JSON") }
                    ']' -> { i++; return }
                    else -> throw Err(i, "Expected ',' or ']'")
                }
            }
        }

        fun str() {
            val start = i
            i++
            while (true) {
                if (i >= s.length) throw Err(start, "Unterminated string")
                val c = s[i]
                if (c == '"') { i++; return }
                if (c == '\\') {
                    i++
                    val e = s.getOrNull(i) ?: throw Err(start, "Unterminated string")
                    if ("\"\\/bfnrt".indexOf(e) < 0 && e != 'u') throw Err(i, "Invalid escape '\\$e'")
                    if (e == 'u') {
                        for (k in 1..4) {
                            val h = s.getOrNull(i + k)
                            if (h == null || !(h.isDigit() || h.lowercaseChar() in 'a'..'f')) throw Err(i, "Invalid unicode escape")
                        }
                        i += 4
                    }
                } else if (c < ' ') throw Err(i, "Control character in string")
                i++
            }
        }

        fun num() {
            val start = i
            if (peek() == '-') i++
            if (peek() == '0') i++
            else if (peek()?.isDigit() == true) while (peek()?.isDigit() == true) i++
            else throw Err(start, "Invalid number")
            if (peek() == '.') {
                i++
                if (peek()?.isDigit() != true) throw Err(start, "Invalid number")
                while (peek()?.isDigit() == true) i++
            }
            if (peek() == 'e' || peek() == 'E') {
                i++
                if (peek() == '+' || peek() == '-') i++
                if (peek()?.isDigit() != true) throw Err(start, "Invalid number")
                while (peek()?.isDigit() == true) i++
            }
        }
    }
}
