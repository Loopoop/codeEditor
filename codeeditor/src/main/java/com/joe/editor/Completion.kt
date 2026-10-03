package com.joe.editor

enum class CompletionKind { Keyword, Type, Function, Variable, Snippet, Word }

data class CompletionItem(
    val label: String,
    val insertText: String = label,
    val kind: CompletionKind = CompletionKind.Word,
    val detail: String? = null,
    /** Caret position inside [insertText] after insertion (default: end, or the `$0` marker). */
    val cursorOffset: Int? = null,
)

class CompletionContext(val text: String, val cursor: Int, val prefix: String, val language: Language)

/** Implement to plug in your own completion source (LSP, symbol index...). */
fun interface CompletionProvider {
    fun complete(context: CompletionContext): List<CompletionItem>
}

class CompositeCompletionProvider(private vararg val providers: CompletionProvider) : CompletionProvider {
    override fun complete(context: CompletionContext): List<CompletionItem> =
        providers.flatMap { it.complete(context) }.distinctBy { it.label }
}

/** Keywords + types + builtins + snippets + words already in the document. */
object DefaultCompletionProvider : CompletionProvider {
    private class Scored(val item: CompletionItem, val score: Int, val rank: Int)

    private fun score(candidate: String, prefix: String): Int {
        if (prefix.isEmpty()) return 1
        if (candidate.startsWith(prefix)) return 0
        if (candidate.startsWith(prefix, ignoreCase = true)) return 1
        var pi = 0
        for (ch in candidate) if (pi < prefix.length && ch.equals(prefix[pi], ignoreCase = true)) pi++
        return if (pi == prefix.length) 2 else -1
    }

    override fun complete(context: CompletionContext): List<CompletionItem> {
        val p = context.prefix
        val lang = context.language
        val found = LinkedHashMap<String, Scored>()

        for (s in lang.snippets) {
            val sc = score(s.trigger, p)
            if (sc >= 0) found[s.trigger] = Scored(
                CompletionItem(s.trigger, s.body, CompletionKind.Snippet, s.description), sc, 0,
            )
        }
        for ((w, type) in lang.vocabulary) {
            if (w == p || found.containsKey(w)) continue
            val sc = score(w, p)
            if (sc >= 0) {
                val kind = when (type) {
                    TokenType.Keyword -> CompletionKind.Keyword
                    TokenType.Type -> CompletionKind.Type
                    TokenType.Builtin -> CompletionKind.Function
                    else -> CompletionKind.Variable
                }
                found[w] = Scored(CompletionItem(w, w, kind), sc, 1)
            }
        }
        // words from the document
        val t = context.text
        var i = 0
        var count = 0
        while (i < t.length && count < 6000) {
            if (lang.isWordChar(t[i]) && !t[i].isDigit()) {
                var j = i + 1
                while (j < t.length && lang.isWordChar(t[j])) j++
                val w = t.substring(i, j)
                if (w.length >= 3 && w != p && !found.containsKey(w)) {
                    val sc = score(w, p)
                    if (sc >= 0) { found[w] = Scored(CompletionItem(w, w, CompletionKind.Word), sc, 2); count++ }
                }
                i = j
            } else i++
        }
        return found.values
            .sortedWith(compareBy<Scored>({ it.score }, { it.rank }, { it.item.label.length }, { it.item.label }))
            .take(40)
            .map { it.item }
    }
}
