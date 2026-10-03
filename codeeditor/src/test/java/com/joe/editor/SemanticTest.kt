package com.joe.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticTest {

    @Test
    fun testSemanticAnalyzerDetectsSymbolsAndDiagnostics() {
        val code = """
            val unusedVar = 42
            val duplicateVar = 10
            val duplicateVar = 20
            fun greet() {
                val x = 1
            }
        """.trimIndent()

        val lang = Languages.Kotlin
        val scan = lang.scan(code)
        val analyzer = SemanticAnalyzer()
        val result = analyzer.analyze(code, lang, scan)

        // Duplicate variable warning/error
        val hasDuplicateError = result.diagnostics.any { it.message.contains("already declared") }
        assertTrue("Should report duplicate variable error", hasDuplicateError)

        // Unused variable warning
        val hasUnusedWarning = result.diagnostics.any { it.message.contains("never used") }
        assertTrue("Should report unused variable warning", hasUnusedWarning)

        // Symbols detected
        val symbols = result.symbols
        assertTrue("Should extract declared symbols", symbols.isNotEmpty())
        assertNotNull("Should find 'greet' function symbol", symbols.firstOrNull { it.name == "greet" && it.kind == SymbolKind.Function })
    }

    @Test
    fun testSemanticCompletionProvider() {
        val code = """
            val myNumber = 100
            val myName = "Test"
            fun compute() {}
        """.trimIndent()

        val lang = Languages.Kotlin
        val analyzer = SemanticAnalyzer()
        val provider = SemanticCompletionProvider(analyzer)

        val context = CompletionContext(code, code.length, "my", lang)
        val completions = provider.complete(context)

        assertTrue("Should suggest 'myNumber' and 'myName'", completions.any { it.label == "myNumber" })
        assertTrue("Should suggest 'myName'", completions.any { it.label == "myName" })
    }
}
