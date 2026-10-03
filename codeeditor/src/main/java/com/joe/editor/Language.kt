package com.joe.editor

data class Snippet(val trigger: String, val description: String, val body: String)

/**
 * A language definition: tokenizer + editing rules (comments, brackets, indentation) + completion vocabulary.
 * In snippet bodies, `$0` marks the final caret position and `\t` is replaced by the indent unit.
 */
open class Language(
    val id: String,
    val name: String,
    val extensions: List<String>,
    val lineComment: String? = null,
    val blockComment: Pair<String, String>? = null,
    val indentUnit: String = "    ",
    val indentAfter: Set<Char> = setOf('{', '(', '['),
    val pairs: Map<Char, Char> = mapOf('{' to '}', '(' to ')', '[' to ']'),
    val quotePairs: String = "\"'",
    val wordChars: String = "_",
    val isMarkup: Boolean = false,
    val checkBrackets: Boolean = true,
    val vocabulary: List<Pair<String, TokenType>> = emptyList(),
    val snippets: List<Snippet> = emptyList(),
    private val scanner: ((CharSequence) -> ScanResult)? = null,
) {
    val closers: Set<Char> = pairs.values.toSet()
    fun scan(text: CharSequence): ScanResult = scanner?.invoke(text) ?: ScanResult.Empty
    fun isWordChar(c: Char) = c.isLetterOrDigit() || wordChars.indexOf(c) >= 0
    override fun toString() = name
}

/** Create a language from a [LanguageSpec]. */
fun codeLanguage(
    id: String,
    name: String,
    extensions: List<String>,
    spec: LanguageSpec,
    indentUnit: String = "    ",
    lineComment: String? = spec.lineComments.firstOrNull(),
    blockComment: Pair<String, String>? = spec.blockComments.firstOrNull(),
    indentAfter: Set<Char> = setOf('{', '(', '['),
    pairs: Map<Char, Char> = mapOf('{' to '}', '(' to ')', '[' to ']'),
    quotePairs: String = "\"'",
    wordChars: String = "_",
    checkBrackets: Boolean = true,
    snippets: List<Snippet> = emptyList(),
): Language = Language(
    id, name, extensions, lineComment, blockComment, indentUnit, indentAfter, pairs, quotePairs,
    wordChars, false, checkBrackets, spec.vocabulary(), snippets, SpecScanner(spec)::scan,
)
