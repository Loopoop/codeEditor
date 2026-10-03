package com.joe.editor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class SymbolKind { Variable, Function, Class, Interface, Parameter, Field, Import }

data class SemanticSymbol(
    val name: String,
    val kind: SymbolKind,
    val start: Int,
    val end: Int,
    val scopeLevel: Int,
    val type: String? = null,
    val doc: String? = null,
)

data class SymbolReference(
    val name: String,
    val start: Int,
    val end: Int,
    val declaration: SemanticSymbol? = null,
)

class SemanticScope(
    val level: Int,
    val parent: SemanticScope? = null,
) {
    val symbols = mutableListOf<SemanticSymbol>()
    val references = mutableListOf<SymbolReference>()

    fun findSymbol(name: String): SemanticSymbol? {
        return symbols.firstOrNull { it.name == name } ?: parent?.findSymbol(name)
    }
}

class SemanticAnalysisResult(
    val symbols: List<SemanticSymbol>,
    val references: List<SymbolReference>,
    val diagnostics: List<Diagnostic>,
    private val scopeTree: SemanticScope,
) {
    fun getDefinitionAt(offset: Int): SemanticSymbol? {
        val ref = references.firstOrNull { offset >= it.start && offset <= it.end }
        if (ref != null) return ref.declaration
        return symbols.firstOrNull { offset >= it.start && offset <= it.end }
    }

    fun getHoverInfoAt(offset: Int): String? {
        val sym = getDefinitionAt(offset) ?: return null
        val typeStr = if (sym.type != null) ": ${sym.type}" else ""
        val docStr = if (sym.doc != null) "\n\n${sym.doc}" else ""
        return "(${sym.kind.name.lowercase()}) ${sym.name}$typeStr$docStr"
    }

    fun getSymbolsInScope(offset: Int): List<SemanticSymbol> {
        fun collect(scope: SemanticScope, list: MutableList<SemanticSymbol>) {
            list.addAll(scope.symbols)
            if (scope.parent != null) collect(scope.parent, list)
        }
        val result = mutableListOf<SemanticSymbol>()
        collect(scopeTree, result)
        return result.distinctBy { it.name }
    }
}

class SemanticAnalyzer {

    fun analyze(text: String, language: Language, scan: ScanResult): SemanticAnalysisResult {
        val diagnostics = ArrayList<Diagnostic>()
        val allSymbols = ArrayList<SemanticSymbol>()
        val allReferences = ArrayList<SymbolReference>()

        val rootScope = SemanticScope(0)
        var currentScope = rootScope

        val tokens = scan.tokens
        var i = 0
        val n = tokens.size

        while (i < n) {
            val tk = tokens[i]
            val tokenText = text.substring(tk.start.coerceIn(0, text.length), tk.end.coerceIn(0, text.length))

            when {
                // Scope entry: opening brace '{'
                tk.type == TokenType.Punct && tokenText == "{" -> {
                    val newScope = SemanticScope(currentScope.level + 1, currentScope)
                    currentScope = newScope
                }

                // Scope exit: closing brace '}'
                tk.type == TokenType.Punct && tokenText == "}" -> {
                    val p = currentScope.parent
                    if (p != null) {
                        currentScope = p
                    }
                }

                // Import declaration e.g. import foo.bar
                tk.type == TokenType.Keyword && (tokenText == "import" || tokenText == "using" || tokenText == "include" || tokenText == "require") -> {
                    var j = i + 1
                    while (j < n && tokens[j].type != TokenType.Punct && tokens[j].type != TokenType.Keyword) {
                        if (tokens[j].type == TokenType.Plain || tokens[j].type == TokenType.Type) {
                            val name = text.substring(tokens[j].start, tokens[j].end)
                            val sym = SemanticSymbol(name, SymbolKind.Import, tokens[j].start, tokens[j].end, currentScope.level)
                            currentScope.symbols.add(sym)
                            allSymbols.add(sym)
                        }
                        j++
                    }
                    i = j - 1
                }

                // Function declaration e.g. fun name / function name / def name / fn name
                tk.type == TokenType.Keyword && (tokenText == "fun" || tokenText == "function" || tokenText == "def" || tokenText == "fn" || tokenText == "func") -> {
                    if (i + 1 < n && (tokens[i + 1].type == TokenType.Func || tokens[i + 1].type == TokenType.Plain)) {
                        val fnTk = tokens[i + 1]
                        val name = text.substring(fnTk.start, fnTk.end)
                        val sym = SemanticSymbol(name, SymbolKind.Function, fnTk.start, fnTk.end, currentScope.level)

                        // Check duplicate in same scope
                        if (currentScope.symbols.any { it.name == name && it.kind == SymbolKind.Function }) {
                            diagnostics.add(Diagnostic(fnTk.start, fnTk.end, "Redeclaration of function '$name'", Severity.Error))
                        } else {
                            currentScope.symbols.add(sym)
                            allSymbols.add(sym)
                        }
                        i++
                    }
                }

                // Class/Interface declaration e.g. class Name / interface Name / struct Name
                tk.type == TokenType.Keyword && (tokenText == "class" || tokenText == "interface" || tokenText == "struct" || tokenText == "enum") -> {
                    if (i + 1 < n && (tokens[i + 1].type == TokenType.Type || tokens[i + 1].type == TokenType.Plain)) {
                        val clsTk = tokens[i + 1]
                        val name = text.substring(clsTk.start, clsTk.end)
                        val kind = if (tokenText == "interface") SymbolKind.Interface else SymbolKind.Class
                        val sym = SemanticSymbol(name, kind, clsTk.start, clsTk.end, currentScope.level)
                        currentScope.symbols.add(sym)
                        allSymbols.add(sym)
                        i++
                    }
                }

                // Variable declaration e.g. val x / var x / let x / const x
                tk.type == TokenType.Keyword && (tokenText == "val" || tokenText == "var" || tokenText == "let" || tokenText == "const" || tokenText == "auto") -> {
                    if (i + 1 < n && (tokens[i + 1].type == TokenType.Plain || tokens[i + 1].type == TokenType.Variable || tokens[i + 1].type == TokenType.Property)) {
                        val varTk = tokens[i + 1]
                        val name = text.substring(varTk.start, varTk.end)

                        // Type annotation check e.g. val x: String
                        var varType: String? = null
                        if (i + 3 < n && text.substring(tokens[i + 2].start, tokens[i + 2].end) == ":") {
                            varType = text.substring(tokens[i + 3].start, tokens[i + 3].end)
                        }

                        val sym = SemanticSymbol(name, SymbolKind.Variable, varTk.start, varTk.end, currentScope.level, type = varType)

                        // Check duplicate in same scope
                        if (currentScope.symbols.any { it.name == name }) {
                            diagnostics.add(Diagnostic(varTk.start, varTk.end, "Variable '$name' is already declared in this scope", Severity.Error))
                        } else {
                            currentScope.symbols.add(sym)
                            allSymbols.add(sym)
                        }
                        i++
                    }
                }

                // Identifier usages / references
                (tk.type == TokenType.Plain || tk.type == TokenType.Func || tk.type == TokenType.Variable) && scan.isCode(tk.start) -> {
                    val name = tokenText
                    val isDecl = i > 0 && (tokens[i - 1].type == TokenType.Keyword)
                    if (!isDecl && name.length >= 2) {
                        val decl = currentScope.findSymbol(name)
                        val ref = SymbolReference(name, tk.start, tk.end, decl)
                        currentScope.references.add(ref)
                        allReferences.add(ref)
                    }
                }
            }
            i++
        }

        // Check for unused variables / functions
        for (sym in allSymbols) {
            if (sym.kind == SymbolKind.Variable || sym.kind == SymbolKind.Import) {
                val used = allReferences.any { it.name == sym.name && it.start > sym.end }
                if (!used) {
                    val label = if (sym.kind == SymbolKind.Import) "Import" else "Variable"
                    diagnostics.add(Diagnostic(sym.start, sym.end, "$label '${sym.name}' is never used", Severity.Warning))
                }
            }
        }

        return SemanticAnalysisResult(allSymbols, allReferences, diagnostics, currentScope)
    }
}

/** Built-in semantic diagnostics provider. */
object SemanticDiagnosticsProvider : DiagnosticsProvider {
    private val analyzer = SemanticAnalyzer()

    override suspend fun diagnose(text: String, language: Language): List<Diagnostic> = withContext(Dispatchers.Default) {
        val scan = language.scan(text)
        val result = analyzer.analyze(text, language, scan)
        result.diagnostics
    }
}

/** Built-in semantic completion provider offering symbols active in scope. */
class SemanticCompletionProvider(
    private val analyzer: SemanticAnalyzer = SemanticAnalyzer(),
) : CompletionProvider {

    override fun complete(context: CompletionContext): List<CompletionItem> {
        val scan = context.language.scan(context.text)
        val result = analyzer.analyze(context.text, context.language, scan)
        val symbols = result.getSymbolsInScope(context.cursor)
        val prefix = context.prefix

        return symbols
            .filter { it.name.startsWith(prefix, ignoreCase = true) && it.name != prefix }
            .map { sym ->
                val kind = when (sym.kind) {
                    SymbolKind.Function -> CompletionKind.Function
                    SymbolKind.Class, SymbolKind.Interface -> CompletionKind.Type
                    SymbolKind.Variable, SymbolKind.Parameter, SymbolKind.Field -> CompletionKind.Variable
                    SymbolKind.Import -> CompletionKind.Word
                }
                CompletionItem(
                    label = sym.name,
                    insertText = sym.name,
                    kind = kind,
                    detail = sym.type ?: sym.kind.name,
                )
            }
    }
}
