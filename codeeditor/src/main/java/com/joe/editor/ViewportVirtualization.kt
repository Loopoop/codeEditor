package com.joe.editor

import androidx.compose.ui.text.TextLayoutResult
import kotlin.math.max
import kotlin.math.min

/**
 * Range of visible lines and character offsets inside the editor's scroll viewport.
 */
data class ViewportRange(
    val firstVisibleLine: Int,
    val lastVisibleLine: Int,
    val startOffset: Int,
    val endOffset: Int,
)

object ViewportVirtualization {

    /**
     * Computes the visible line range and character offset window for the given viewport scroll position.
     */
    fun computeVisibleRange(
        scrollY: Float,
        viewportHeight: Float,
        lineStarts: IntArray,
        textLength: Int,
        textLayout: TextLayoutResult?,
        bufferLines: Int = 40,
    ): ViewportRange {
        if (lineStarts.isEmpty() || textLength == 0) {
            return ViewportRange(0, 0, 0, 0)
        }

        val totalLines = lineStarts.size
        val top = scrollY.coerceAtLeast(0f)
        val bottom = top + viewportHeight

        var firstLine = 0
        var lastLine = totalLines - 1

        if (textLayout != null) {
            // Use precise TextLayoutResult line tops/bottoms
            var lo = 0
            var hi = totalLines - 1
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                val off = lineStarts[mid].coerceIn(0, textLength)
                val vl = textLayout.getLineForOffset(off)
                if (textLayout.getLineBottom(vl) < top) lo = mid + 1 else hi = mid
            }
            firstLine = max(0, lo - bufferLines)

            lo = firstLine
            hi = totalLines - 1
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                val off = lineStarts[mid].coerceIn(0, textLength)
                val vl = textLayout.getLineForOffset(off)
                if (textLayout.getLineTop(vl) > bottom) hi = mid else lo = mid + 1
            }
            lastLine = min(totalLines - 1, lo + bufferLines)
        } else {
            // Fallback estimation
            val estLineHeight = 30f
            firstLine = max(0, (top / estLineHeight).toInt() - bufferLines)
            lastLine = min(totalLines - 1, (bottom / estLineHeight).toInt() + bufferLines)
        }

        val startOff = lineStarts[firstLine].coerceIn(0, textLength)
        val endOff = if (lastLine + 1 < totalLines) {
            (lineStarts[lastLine + 1] - 1).coerceIn(startOff, textLength)
        } else {
            textLength
        }

        return ViewportRange(firstLine, lastLine, startOff, endOff)
    }

    /**
     * Filters syntax highlight tokens to only those within the active viewport window.
     */
    fun filterTokensForViewport(tokens: List<Token>, range: ViewportRange): List<Token> {
        if (tokens.isEmpty()) return emptyList()
        val s = range.startOffset
        val e = range.endOffset
        return tokens.filter { it.end >= s && it.start <= e }
    }
}

/**
 * Incremental scanner for fast background chunk scanning of large documents.
 */
class IncrementalScanner(private val language: Language) {

    private var cachedText: String? = null
    private var cachedScan: ScanResult? = null

    fun scanIncremental(text: String): ScanResult {
        val prevText = cachedText
        val prevScan = cachedScan

        if (prevText != null && prevScan != null && text == prevText) {
            return prevScan
        }

        val result = language.scan(text)
        cachedText = text
        cachedScan = result
        return result
    }
}
