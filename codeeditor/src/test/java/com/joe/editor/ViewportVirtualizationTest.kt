package com.joe.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewportVirtualizationTest {

    @Test
    fun testComputeVisibleRange() {
        val lineStarts = IntArray(100) { it * 10 }
        val textLength = 1000

        val range = ViewportVirtualization.computeVisibleRange(
            scrollY = 300f,
            viewportHeight = 200f,
            lineStarts = lineStarts,
            textLength = textLength,
            textLayout = null,
            bufferLines = 10,
        )

        assertTrue(range.firstVisibleLine >= 0)
        assertTrue(range.lastVisibleLine < 100)
        assertTrue(range.firstVisibleLine <= range.lastVisibleLine)
    }

    @Test
    fun testFilterTokensForViewport() {
        val tokens = listOf(
            Token(0, 10, TokenType.Keyword),
            Token(20, 30, TokenType.Variable),
            Token(50, 60, TokenType.Num),
            Token(80, 90, TokenType.Str),
        )

        val range = ViewportRange(2, 5, 15, 65)
        val filtered = ViewportVirtualization.filterTokensForViewport(tokens, range)

        assertEquals(2, filtered.size)
        assertEquals(TokenType.Variable, filtered[0].type)
        assertEquals(TokenType.Num, filtered[1].type)
    }
}
