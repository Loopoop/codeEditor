package com.joe.editor

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/**
 * A foldable region in the document (from [startLine] to [endLine]).
 */
data class FoldRegion(
    val startLine: Int,
    val endLine: Int,
    val startOffset: Int,
    val endOffset: Int,
    val placeholder: String = "...",
) {
    val lineRange: IntRange get() = startLine..endLine
}

/**
 * Result of folding text: the visually transformed text and the offset mapping back to original text.
 */
data class FoldResult(
    val transformedText: String,
    val offsetMapping: OffsetMapping,
    val foldedLineRanges: Set<IntRange>,
)

object CodeFolding {

    /**
     * Finds all potential foldable regions (brackets, tags, block comments, indentation blocks) in [text].
     */
    fun findFoldableRegions(
        text: String,
        language: Language,
        scan: ScanResult,
        lineStarts: IntArray,
    ): List<FoldRegion> {
        if (text.isEmpty() || lineStarts.size < 2) return emptyList()
        val regions = ArrayList<FoldRegion>()
        val textLen = text.length

        // Helper to convert offset to line
        fun lineOf(offset: Int): Int {
            val idx = java.util.Arrays.binarySearch(lineStarts, offset)
            return if (idx >= 0) idx else -idx - 2
        }

        // 1. Bracket pairs e.g. { ... }, [ ... ] spanning multiple lines
        if (language.pairs.isNotEmpty()) {
            val openStack = ArrayList<Pair<Char, Int>>()
            for (i in text.indices) {
                if (!scan.isCode(i)) continue
                val c = text[i]
                if (language.pairs.containsKey(c)) {
                    openStack.add(c to i)
                } else if (language.closers.contains(c)) {
                    val matchingOpen = language.pairs.entries.firstOrNull { it.value == c }?.key
                    val idx = openStack.indexOfLast { it.first == matchingOpen }
                    if (idx >= 0) {
                        val (_, startOff) = openStack.removeAt(idx)
                        val startLn = lineOf(startOff)
                        val endLn = lineOf(i)
                        if (endLn > startLn) {
                            val placeholder = when (matchingOpen) {
                                '{' -> " { ... } "
                                '[' -> " [ ... ] "
                                '(' -> " ( ... ) "
                                else -> " ... "
                            }
                            regions.add(FoldRegion(startLn, endLn, startOff, i + 1, placeholder))
                        }
                    }
                }
            }
        }

        // 2. Block comments e.g. /* ... */ spanning multiple lines
        for (tk in scan.tokens) {
            if (tk.type == TokenType.Comment || tk.type == TokenType.DocComment) {
                val startLn = lineOf(tk.start)
                val endLn = lineOf(tk.end)
                if (endLn > startLn) {
                    regions.add(FoldRegion(startLn, endLn, tk.start, tk.end, "/* ... */"))
                }
            }
        }

        // 3. Markup tags e.g. <div ...> ... </div> spanning multiple lines
        if (language.isMarkup) {
            val stack = ArrayList<Pair<String, Int>>()
            var i = 0
            while (i < textLen) {
                val lt = text.indexOf('<', i)
                if (lt < 0) break
                if (text.startsWith("<!--", lt)) {
                    val e = text.indexOf("-->", lt + 4)
                    val end = if (e < 0) textLen else e + 3
                    val startLn = lineOf(lt)
                    val endLn = lineOf(end)
                    if (endLn > startLn) {
                        regions.add(FoldRegion(startLn, endLn, lt, end, "<!-- ... -->"))
                    }
                    i = end
                    continue
                }
                var j = lt + 1
                val closing = text.getOrNull(j) == '/'
                if (closing) j++
                val ns = j
                while (j < textLen && (text[j].isLetterOrDigit() || text[j] == '-' || text[j] == ':' || text[j] == '_' || text[j] == '.')) j++
                if (j > ns && text[ns].isLetter()) {
                    val name = text.substring(ns, j)
                    var k = j
                    while (k < textLen && text[k] != '>') k++
                    if (k < textLen) {
                        val selfClosing = text[k - 1] == '/'
                        if (closing) {
                            val idx = stack.indexOfLast { it.first.equals(name, ignoreCase = true) }
                            if (idx >= 0) {
                                val (_, startLt) = stack.removeAt(idx)
                                val startLn = lineOf(startLt)
                                val endLn = lineOf(k)
                                if (endLn > startLn) {
                                    regions.add(FoldRegion(startLn, endLn, startLt, k + 1, "<$name> ... </$name>"))
                                }
                            }
                        } else if (!selfClosing) {
                            stack.add(name to lt)
                        }
                        i = k + 1
                        continue
                    }
                }
                i = lt + 1
            }
        }

        // Remove duplicates/overlaps and sort by startLine
        return regions.distinctBy { it.startLine to it.endLine }.sortedBy { it.startLine }
    }

    /**
     * Applies folding to [text] based on active [activeFoldedRanges] and available [allRegions].
     */
    fun createFoldResult(
        text: String,
        lineStarts: IntArray,
        activeFoldedRanges: Set<IntRange>,
        allRegions: List<FoldRegion>,
    ): FoldResult {
        if (activeFoldedRanges.isEmpty() || text.isEmpty()) {
            return FoldResult(text, OffsetMapping.Identity, emptySet())
        }

        val activeRegions = allRegions.filter { reg ->
            activeFoldedRanges.any { it.first == reg.startLine && it.last == reg.endLine }
        }.sortedBy { it.startOffset }

        if (activeRegions.isEmpty()) {
            return FoldResult(text, OffsetMapping.Identity, emptySet())
        }

        // Build transformed text and offset mapping
        val sb = StringBuilder()
        val origToTrans = IntArray(text.length + 1)
        var transToOrig = ArrayList<Int>()

        var lastOrig = 0
        var currentTrans = 0

        for (region in activeRegions) {
            val start = region.startOffset.coerceIn(lastOrig, text.length)
            val end = region.endOffset.coerceIn(start, text.length)

            if (start > lastOrig) {
                val chunk = text.substring(lastOrig, start)
                sb.append(chunk)
                for (i in lastOrig until start) {
                    origToTrans[i] = currentTrans + (i - lastOrig)
                    transToOrig.add(i)
                }
                currentTrans += chunk.length
            }

            // Append placeholder
            val placeholder = region.placeholder
            sb.append(placeholder)
            for (i in start until end) {
                origToTrans[i] = currentTrans
            }
            for (ch in placeholder) {
                transToOrig.add(start)
            }
            currentTrans += placeholder.length
            lastOrig = end
        }

        if (lastOrig < text.length) {
            val chunk = text.substring(lastOrig)
            sb.append(chunk)
            for (i in lastOrig until text.length) {
                origToTrans[i] = currentTrans + (i - lastOrig)
                transToOrig.add(i)
            }
            currentTrans += chunk.length
        }
        origToTrans[text.length] = currentTrans
        transToOrig.add(text.length)

        val transToOrigArray = transToOrig.toIntArray()
        val transformedText = sb.toString()

        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                val clamped = offset.coerceIn(0, text.length)
                return origToTrans[clamped]
            }

            override fun transformedToOriginal(offset: Int): Int {
                val clamped = offset.coerceIn(0, transformedText.length)
                return transToOrigArray[clamped]
            }
        }

        val activeLineRanges = activeRegions.map { it.startLine..it.endLine }.toSet()
        return FoldResult(transformedText, mapping, activeLineRanges)
    }
}

/**
 * Visual Transformation that collapses folded code regions.
 */
class FoldingTransformation(
    private val foldResult: FoldResult,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (foldResult.offsetMapping === OffsetMapping.Identity) {
            return TransformedText(text, OffsetMapping.Identity)
        }
        val transformedStr = foldResult.transformedText
        return TransformedText(buildAnnotatedString { append(transformedStr) }, foldResult.offsetMapping)
    }
}
