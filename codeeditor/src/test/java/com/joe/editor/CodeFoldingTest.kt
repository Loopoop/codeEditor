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
    fun testCodeEditorStateFoldingToggle() {
        val code = "fun main() {\n    val x = 1\n}"
        val state = CodeEditorState(code, Languages.Kotlin)

        assertTrue(state.config.codeFoldingEnabled)
        val regions = state.foldableRegions
        assertTrue(regions.isNotEmpty())

        assertFalse(state.isLineFolded(0))
        state.toggleFold(0)
        assertTrue(state.isLineFolded(0))

        state.toggleFold(0)
        assertFalse(state.isLineFolded(0))
    }
}
