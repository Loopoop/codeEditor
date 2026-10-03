package com.joe.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeFoldingTest {

    @Test
    fun testFindFoldableRegionsBrackets() {
        val code = """
            fun main() {
                val a = 1
                val b = 2
            }
        """.trimIndent()

        val lang = Languages.Kotlin
        val scan = lang.scan(code)
        val lineStarts = intArrayOf(0, 13, 27, 41)

        val regions = CodeFolding.findFoldableRegions(code, lang, scan, lineStarts)
        assertTrue("Should detect at least one foldable bracket region", regions.isNotEmpty())
        assertEquals(0, regions[0].startLine)
        assertEquals(3, regions[0].endLine)
    }

    @Test
    fun testFindFoldableRegionsComments() {
        val code = "/*\n * Comment line 1\n * Comment line 2\n */\nval x = 1"
        val lang = Languages.Kotlin
        val scan = lang.scan(code)
        val lineStarts = intArrayOf(0, 3, 21, 39, 44)

        val regions = CodeFolding.findFoldableRegions(code, lang, scan, lineStarts)
        assertTrue("Should detect comment block region", regions.isNotEmpty())
        assertEquals(0, regions[0].startLine)
        assertEquals(3, regions[0].endLine)
    }

    @Test
    fun testFindFoldableRegionsIndentation() {
        val code = "def foo():\n    x = 1\n    y = 2\nprint('done')"
        val lang = Languages.Python
        val scan = lang.scan(code)
        val lineStarts = intArrayOf(0, 11, 21, 31)

        val regions = CodeFolding.findFoldableRegions(code, lang, scan, lineStarts)
        assertTrue("Should detect indentation region for python", regions.isNotEmpty())
        assertEquals(0, regions[0].startLine)
        assertEquals(2, regions[0].endLine)
    }

    @Test
    fun testCreateFoldResultOffsetMapping() {
        val text = "line0\nline1\nline2\nline3"
        val lineStarts = intArrayOf(0, 6, 12, 18)
        val region = FoldRegion(1, 2, 6, 17, "...")
        val allRegions = listOf(region)
        val active = setOf(1..2)

        val result = CodeFolding.createFoldResult(text, lineStarts, active, allRegions)
        assertEquals("line0\n...\nline3", result.transformedText)

        // Mapping test: original index 0 ('l') -> transformed index 0
        assertEquals(0, result.offsetMapping.originalToTransformed(0))

        // Transformed back to original
        assertEquals(0, result.offsetMapping.transformedToOriginal(0))
    }

    @Test
    fun testNestedFoldRegionsHandling() {
        val text = "fun outer() {\n    fun inner() {\n        val x = 1\n    }\n}"
        val lang = Languages.Kotlin
        val scan = lang.scan(text)
        val lineStarts = intArrayOf(0, 14, 32, 50, 56)

        val regions = CodeFolding.findFoldableRegions(text, lang, scan, lineStarts)
        assertTrue("Should detect outer and inner fold regions", regions.size >= 2)

        // Activating both outer and inner region
        val activeRanges = setOf(0..4, 1..3)
        val result = CodeFolding.createFoldResult(text, lineStarts, activeRanges, regions)

        // Outer region takes precedence, nested region is safely skipped without corrupting offsets
        assertTrue("Transformed text should contain placeholder", result.transformedText.contains("{ ... }"))
    }

    @Test
    fun testCodeEditorStateFoldingToggleAndAll() {
        val code = "fun main() {\n    val x = 1\n}\n\nfun foo() {\n    val y = 2\n}"
        val state = CodeEditorState(code, Languages.Kotlin)

        assertTrue(state.config.codeFoldingEnabled)
        val regions = state.foldableRegions
        assertEquals(2, regions.size)

        assertFalse(state.isLineFolded(0))
        state.toggleFold(0)
        assertTrue(state.isLineFolded(0))

        state.toggleFold(0)
        assertFalse(state.isLineFolded(0))

        state.foldAll()
        assertTrue(state.isLineFolded(0))
        assertTrue(state.isLineFolded(4))

        state.unfoldAll()
        assertFalse(state.isLineFolded(0))
        assertFalse(state.isLineFolded(4))
    }
}
