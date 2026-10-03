package com.joe.editor

enum class TokenType {
    Plain, Keyword, Type, Func, Builtin, Literal, Str, Num, Comment, DocComment,
    Meta, Operator, Punct, Property, Variable, Tag, Attr, Escape
}

data class Token(val start: Int, val end: Int, val type: TokenType)

class ScanResult(val tokens: List<Token>, val issues: List<Diagnostic> = emptyList()) {
    /** Token type at [offset] (binary search; tokens are sorted and non-overlapping). */
    fun typeAt(offset: Int): TokenType {
        var lo = 0
        var hi = tokens.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val t = tokens[mid]
            if (offset < t.start) hi = mid - 1
            else if (offset >= t.end) lo = mid + 1
            else return t.type
        }
        return TokenType.Plain
    }

    fun isCode(offset: Int): Boolean = when (typeAt(offset)) {
        TokenType.Str, TokenType.Comment, TokenType.DocComment, TokenType.Escape -> false
        else -> true
    }

    companion object {
        val Empty = ScanResult(emptyList())
    }
}
