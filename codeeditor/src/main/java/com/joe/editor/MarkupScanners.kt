package com.joe.editor

/** HTML / XML scanner with embedded <script> and <style> support. */
internal class MarkupScanner(private val html: Boolean, private val embed: (String) -> Language?) {
    fun scan(t: CharSequence): ScanResult {
        val n = t.length
        val tokens = ArrayList<Token>()
        val issues = ArrayList<Diagnostic>()
        fun add(a: Int, b: Int, type: TokenType) { if (b > a) tokens.add(Token(a, b, type)) }
        var i = 0
        while (i < n) {
            val c = t[i]
            if (c == '<') {
                if (t.at(i, "<!--")) {
                    val e = t.indexOf("-->", i + 4)
                    val end = if (e < 0) n else e + 3
                    add(i, end, TokenType.Comment)
                    if (e < 0) issues.add(Diagnostic(i, minOf(i + 4, n), "Unterminated comment"))
                    i = end; continue
                }
                if (t.at(i, "<![CDATA[")) {
                    val e = t.indexOf("]]>", i)
                    val end = if (e < 0) n else e + 3
                    add(i, end, TokenType.Str); i = end; continue
                }
                if (t.at(i, "<!") || t.at(i, "<?")) {
                    val e = t.indexOf('>', i)
                    val end = if (e < 0) n else e + 1
                    add(i, end, TokenType.Meta); i = end; continue
                }
                val closing = i + 1 < n && t[i + 1] == '/'
                val nameStart = i + (if (closing) 2 else 1)
                if (nameStart < n && t[nameStart].isLetter()) {
                    add(i, nameStart, TokenType.Punct)
                    var j = nameStart
                    while (j < n && (t[j].isLetterOrDigit() || t[j] == '-' || t[j] == ':' || t[j] == '_' || t[j] == '.')) j++
                    val name = t.subSequence(nameStart, j).toString()
                    add(nameStart, j, TokenType.Tag)
                    while (j < n && t[j] != '>') {
                        val ch = t[j]
                        when {
                            ch.isWhitespace() -> j++
                            ch == '/' -> { add(j, j + 1, TokenType.Punct); j++ }
                            ch == '"' || ch == '\'' -> {
                                var e = j + 1
                                while (e < n && t[e] != ch) e++
                                if (e < n) e++
                                add(j, e, TokenType.Str); j = e
                            }
                            ch == '=' -> { add(j, j + 1, TokenType.Operator); j++ }
                            ch == '<' -> break
                            else -> {
                                var e = j
                                while (e < n && !t[e].isWhitespace() && t[e] != '=' && t[e] != '>' && t[e] != '/' && t[e] != '"' && t[e] != '\'' && t[e] != '<') e++
                                if (e == j) e++
                                add(j, e, TokenType.Attr); j = e
                            }
                        }
                    }
                    var selfClose = false
                    if (j < n && t[j] == '>') {
                        selfClose = j > 0 && t[j - 1] == '/'
                        add(j, j + 1, TokenType.Punct); j++
                    }
                    i = j
                    val lower = name.lowercase()
                    if (!closing && !selfClose && (lower == "script" || lower == "style")) {
                        val e = t.indexOf("</$lower", i, ignoreCase = true)
                        val end = if (e < 0) n else e
                        val lang = embed(lower)
                        if (lang != null && end > i) {
                            val sub = lang.scan(t.subSequence(i, end))
                            val off = i
                            sub.tokens.forEach { tokens.add(Token(it.start + off, it.end + off, it.type)) }
                            sub.issues.forEach { issues.add(it.copy(start = it.start + off, end = it.end + off)) }
                        }
                        i = end
                    }
                    continue
                }
            }
            if (c == '&') {
                var j = i + 1
                while (j < n && j - i < 12 && (t[j].isLetterOrDigit() || t[j] == '#')) j++
                if (j < n && t[j] == ';' && j > i + 1) { add(i, j + 1, TokenType.Escape); i = j + 1; continue }
            }
            i++
        }
        return ScanResult(tokens, issues)
    }
}

internal object CssScanner {
    fun scan(t: CharSequence): ScanResult {
        val n = t.length
        val tokens = ArrayList<Token>()
        val issues = ArrayList<Diagnostic>()
        fun add(a: Int, b: Int, type: TokenType) { if (b > a) tokens.add(Token(a, b, type)) }
        fun identEnd(from: Int): Int {
            var j = from
            while (j < n && (t[j].isLetterOrDigit() || t[j] == '-' || t[j] == '_')) j++
            return j
        }
        fun nextNonSpace(from: Int): Int {
            var k = from
            while (k < n && t[k].isWhitespace()) k++
            return k
        }
        fun isDeclaration(colon: Int): Boolean {
            var k = colon + 1
            while (k < n) {
                val ch = t[k]
                if (ch == '{') return false
                if (ch == ';' || ch == '}') return true
                k++
            }
            return true
        }
        var i = 0
        var depth = 0
        var paren = 0
        var inValue = false
        while (i < n) {
            val c = t[i]
            when {
                c.isWhitespace() -> i++
                t.at(i, "/*") -> {
                    val e = t.indexOf("*/", i + 2)
                    val end = if (e < 0) n else e + 2
                    add(i, end, TokenType.Comment)
                    if (e < 0) issues.add(Diagnostic(i, minOf(i + 2, n), "Unterminated comment"))
                    i = end
                }
                t.at(i, "//") && paren == 0 -> {
                    var j = i
                    while (j < n && t[j] != '\n') j++
                    add(i, j, TokenType.Comment); i = j
                }
                c == '"' || c == '\'' -> {
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
                    add(i, j, TokenType.Str)
                    if (!closed) issues.add(Diagnostic(i, i + 1, "Unterminated string literal"))
                    i = j
                }
                c == '@' -> { val e = identEnd(i + 1); add(i, e, TokenType.Keyword); i = maxOf(e, i + 1) }
                c == '$' -> { val e = identEnd(i + 1); add(i, e, TokenType.Variable); i = maxOf(e, i + 1) }
                c == '{' -> { depth++; inValue = false; add(i, i + 1, TokenType.Punct); i++ }
                c == '}' -> { if (depth > 0) depth--; inValue = false; add(i, i + 1, TokenType.Punct); i++ }
                c == ';' -> { inValue = false; add(i, i + 1, TokenType.Punct); i++ }
                c == '(' -> { paren++; add(i, i + 1, TokenType.Punct); i++ }
                c == ')' -> { if (paren > 0) paren--; add(i, i + 1, TokenType.Punct); i++ }
                c == '#' -> {
                    val e = identEnd(i + 1)
                    add(i, e, if (inValue || paren > 0) TokenType.Num else TokenType.Meta)
                    i = maxOf(e, i + 1)
                }
                c == '.' && i + 1 < n && t[i + 1].isDigit() -> {
                    var j = i + 1
                    while (j < n && (t[j].isDigit() || t[j] == '.')) j++
                    while (j < n && (t[j].isLetter() || t[j] == '%')) j++
                    add(i, j, TokenType.Num); i = j
                }
                c == '.' && !inValue && i + 1 < n && (t[i + 1].isLetter() || t[i + 1] == '-' || t[i + 1] == '_') -> {
                    val e = identEnd(i + 1); add(i, e, TokenType.Type); i = e
                }
                c.isDigit() || (c == '-' && i + 1 < n && t[i + 1].isDigit()) -> {
                    var j = i + 1
                    while (j < n && (t[j].isDigit() || t[j] == '.')) j++
                    while (j < n && (t[j].isLetter() || t[j] == '%')) j++
                    add(i, j, TokenType.Num); i = j
                }
                c.isLetter() || c == '-' || c == '_' -> {
                    val e = identEnd(i)
                    val word = t.subSequence(i, e).toString()
                    val k = nextNonSpace(e)
                    val type = when {
                        word == "important" -> TokenType.Keyword
                        !inValue && depth > 0 && paren == 0 && k < n && t[k] == ':' && isDeclaration(k) -> TokenType.Property
                        k < n && t[k] == '(' -> TokenType.Func
                        inValue || paren > 0 -> TokenType.Plain
                        else -> TokenType.Tag
                    }
                    add(i, e, type); i = maxOf(e, i + 1)
                }
                c == ':' -> {
                    if (depth > 0 && paren == 0 && !inValue && isDeclaration(i)) {
                        inValue = true; add(i, i + 1, TokenType.Punct); i++
                    } else {
                        var j = i + 1
                        if (j < n && t[j] == ':') j++
                        add(i, j, TokenType.Punct)
                        val e = identEnd(j)
                        add(j, e, TokenType.Keyword); i = maxOf(e, j)
                    }
                }
                c == ',' -> { add(i, i + 1, TokenType.Punct); i++ }
                "+>~*=!/%".indexOf(c) >= 0 -> { add(i, i + 1, TokenType.Operator); i++ }
                else -> i++
            }
        }
        return ScanResult(tokens, issues)
    }
}

internal object MarkdownScanner {
    fun scan(t: CharSequence): ScanResult {
        val n = t.length
        val tokens = ArrayList<Token>()
        fun add(a: Int, b: Int, type: TokenType) { if (b > a) tokens.add(Token(a, b, type)) }
        var i = 0
        var fence = false
        while (i < n) {
            var e = i
            while (e < n && t[e] != '\n') e++
            val line = t.subSequence(i, e).toString()
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("```") || trimmed.startsWith("~~~") -> { add(i, e, TokenType.Str); fence = !fence }
                fence -> add(i, e, TokenType.Str)
                trimmed.startsWith("#") -> add(i, e, TokenType.Keyword)
                trimmed.startsWith(">") -> add(i, e, TokenType.Comment)
                else -> {
                    var j = line.length - trimmed.length
                    val m = Regex("^([-*+]|\\d+\\.)\\s").find(trimmed)
                    if (m != null) { add(i + j, i + j + m.groupValues[1].length, TokenType.Operator); j += m.groupValues[1].length }
                    while (j < line.length) {
                        val ch = line[j]
                        if (ch == '`') {
                            val k = line.indexOf('`', j + 1)
                            if (k > 0) { add(i + j, i + k + 1, TokenType.Str); j = k + 1 } else j++
                        } else if (ch == '[') {
                            val k = line.indexOf("](", j + 1)
                            val e2 = if (k > 0) line.indexOf(')', k + 2) else -1
                            if (e2 > 0) { add(i + j, i + k + 1, TokenType.Func); add(i + k + 1, i + e2 + 1, TokenType.Str); j = e2 + 1 } else j++
                        } else if (ch == '*' && line.startsWith("**", j)) {
                            val k = line.indexOf("**", j + 2)
                            if (k > 0) { add(i + j, i + k + 2, TokenType.Type); j = k + 2 } else j += 2
                        } else j++
                    }
                }
            }
            i = e + 1
        }
        return ScanResult(tokens)
    }
}
