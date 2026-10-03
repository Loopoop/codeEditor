package com.joe.editor

import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

enum class CompletionStyle { Bar, Popup }

data class EditorConfig(
    val fontSize: TextUnit = 14.sp,
    val lineHeightMultiplier: Float = 1.5f,
    val fontFamily: FontFamily = FontFamily.Monospace,
    val showLineNumbers: Boolean = true,
    val highlightCurrentLine: Boolean = true,
    val bracketMatching: Boolean = true,
    val wordWrap: Boolean = false,
    val readOnly: Boolean = false,
    val useTabs: Boolean = false,
    val indentSize: Int? = null,
    val autoIndent: Boolean = true,
    val autoCloseBrackets: Boolean = true,
    val autoCloseTags: Boolean = true,
    val autoComplete: Boolean = true,
    val completionMinChars: Int = 1,
    val completionStyle: CompletionStyle = CompletionStyle.Bar,
    val diagnosticsEnabled: Boolean = true,
    val diagnosticsDelayMs: Long = 350,
    val maxHighlightLength: Int = 400_000,
    val codeFoldingEnabled: Boolean = true,
    val semanticAnalysisEnabled: Boolean = true,
    val viewportVirtualization: Boolean = true,
    val largeFileThreshold: Int = 2000,
)

@Stable
class CodeEditorState(
    initialText: String = "",
    language: Language = Languages.PlainText,
    config: EditorConfig = EditorConfig(),
) {
    var value by mutableStateOf(TextFieldValue(initialText))
        private set
    var language by mutableStateOf(language)
    var config by mutableStateOf(config)
    var completionProvider: CompletionProvider = DefaultCompletionProvider
    var diagnosticsProvider: DiagnosticsProvider? = null
    val focusRequester = FocusRequester()

    val text: String get() = value.text

    // ---------------- layout bookkeeping ----------------
    var textLayout: TextLayoutResult? = null
        private set
    var layoutVersion by mutableIntStateOf(0)
        private set
    var contentHeightPx by mutableIntStateOf(0)
        private set
    var lineStarts by mutableStateOf(computeLineStarts(initialText))
        private set

    internal fun onLayout(r: TextLayoutResult) {
        textLayout = r
        contentHeightPx = r.size.height
        layoutVersion++
    }

    private fun computeLineStarts(s: String): IntArray {
        var count = 1
        for (ch in s) if (ch == '\n') count++
        val a = IntArray(count)
        var k = 1
        for (i in s.indices) if (s[i] == '\n') a[k++] = i + 1
        return a
    }

    // ---------------- scan cache ----------------
    private class Cached(val text: String, val language: Language, val result: ScanResult)

    @Volatile
    private var cache: Cached? = null

    internal fun scanOf(text: String, lang: Language): ScanResult {
        val c = cache
        if (c != null && c.language === lang && c.text == text) return c.result
        val r = if (text.length > config.maxHighlightLength) ScanResult.Empty else lang.scan(text)
        cache = Cached(text, lang, r)
        return r
    }

    // ---------------- cursor info ----------------
    val cursorLine: Int by derivedStateOf {
        val t = value.text
        val c = value.selection.start.coerceIn(0, t.length)
        var n = 0
        for (i in 0 until c) if (t[i] == '\n') n++
        n
    }
    val cursorColumn: Int by derivedStateOf {
        val t = value.text
        val c = value.selection.start.coerceIn(0, t.length)
        c - (t.lastIndexOf('\n', c - 1) + 1)
    }

    /** Offsets of the bracket pair around the caret, if any. */
    val bracketPair: Pair<Int, Int>? by derivedStateOf {
        if (!config.bracketMatching || !value.selection.collapsed) return@derivedStateOf null
        val t = value.text
        val cur = value.selection.start
        val lang = language
        if (lang.pairs.isEmpty()) return@derivedStateOf null
        val scan = scanOf(t, lang)
        for (idx in intArrayOf(cur - 1, cur)) {
            if (idx < 0 || idx >= t.length) continue
            val ch = t[idx]
            val isOpen = lang.pairs.containsKey(ch)
            if (!isOpen && !lang.closers.contains(ch)) continue
            if (!scan.isCode(idx)) continue
            val other = if (isOpen) lang.pairs[ch]!! else lang.pairs.entries.first { it.value == ch }.key
            var depth = 1
            var j = idx
            val step = if (isOpen) 1 else -1
            var guard = 0
            while (guard++ < 30000) {
                j += step
                if (j < 0 || j >= t.length) break
                val d = t[j]
                if (d != ch && d != other) continue
                if (!scan.isCode(j)) continue
                if (d == ch) depth++ else depth--
                if (depth == 0) return@derivedStateOf if (isOpen) idx to j else j to idx
            }
        }
        null
    }

    // ---------------- text API ----------------
    fun setText(newText: String, resetHistory: Boolean = true) {
        value = TextFieldValue(newText, TextRange(0))
        lineStarts = computeLineStarts(newText)
        foldedLineRanges = emptySet()
        if (resetHistory) { undoStack.clear(); redoStack.clear(); syncHistoryCounts() }
        diagnostics = emptyList()
        dismissCompletion()
    }

    fun indentUnit(): String {
        val c = config
        val sz = c.indentSize
        return when {
            c.useTabs -> "\t"
            sz != null -> " ".repeat(sz)
            else -> language.indentUnit
        }
    }

    private class Diff(val start: Int, val removed: Int, val inserted: Int)

    private fun diff(a: String, b: String): Diff {
        val max = minOf(a.length, b.length)
        var p = 0
        while (p < max && a[p] == b[p]) p++
        var s = 0
        while (s < max - p && a[a.length - 1 - s] == b[b.length - 1 - s]) s++
        return Diff(p, a.length - p - s, b.length - p - s)
    }

    fun onValueChange(new: TextFieldValue) {
        val old = value
        if (config.readOnly) {
            if (new.selection != old.selection) value = old.copy(selection = new.selection)
            return
        }
        if (new.text == old.text) {
            value = new
            if (new.selection != old.selection) dismissCompletion()
            return
        }
        commit(old, smartEdit(old, new), typing = true)
    }

    private fun commit(old: TextFieldValue, new: TextFieldValue, typing: Boolean) {
        val change = diff(old.text, new.text)
        val newLineStarts = computeLineStarts(new.text)
        val rebasedFolds = rebaseFoldedRanges(change, newLineStarts)
        pushUndo(old, new, change)
        shiftDiagnostics(change)
        value = new
        lineStarts = newLineStarts
        foldedLineRanges = rebasedFolds
        if (typing) refreshCompletion(false) else dismissCompletion()
    }

    private fun edit(newText: String, selection: TextRange) {
        val old = value
        commit(old, TextFieldValue(newText, TextRange(
            selection.start.coerceIn(0, newText.length), selection.end.coerceIn(0, newText.length),
        )), typing = false)
    }

    fun typeText(s: String) {
        if (config.readOnly) return
        val sel = value.selection
        val t = value.text.replaceRange(sel.min, sel.max, s)
        onValueChange(TextFieldValue(t, TextRange(sel.min + s.length)))
    }

    // ---------------- smart editing ----------------
    private fun quoteClosing(t: String, pos: Int, q: Char): Boolean {
        val ls = t.lastIndexOf('\n', pos - 1) + 1
        var n = 0
        for (i in ls until pos) if (t[i] == q && (i == 0 || t[i - 1] != '\\')) n++
        return n % 2 == 1
    }

    private fun smartEdit(old: TextFieldValue, new: TextFieldValue): TextFieldValue {
        val lang = language
        val cfg = config
        val ot = old.text
        val nt = new.text
        val d = diff(ot, nt)

        // typing a bracket/quote over a selection wraps it
        if (cfg.autoCloseBrackets && !old.selection.collapsed && d.inserted == 1 &&
            d.removed == old.selection.length && new.selection.collapsed
        ) {
            val at = old.selection.min
            val c = nt.getOrNull(at)
            if (c != null) {
                val close = lang.pairs[c] ?: if (lang.quotePairs.indexOf(c) >= 0) c else null
                if (close != null) {
                    val sel = ot.substring(at, old.selection.max)
                    return TextFieldValue(
                        ot.substring(0, at) + c + sel + close + ot.substring(old.selection.max),
                        TextRange(at + 1, at + 1 + sel.length),
                    )
                }
            }
        }

        // single character typed
        if (d.removed == 0 && d.inserted == 1 && new.selection.collapsed) {
            val pos = new.selection.start - 1
            if (pos >= 0 && pos < nt.length) {
                val c = nt[pos]
                val next = nt.getOrNull(pos + 1)
                val prev = nt.getOrNull(pos - 1)
                if (c == '\n') return smartEnter(new, pos)
                val isQuote = lang.quotePairs.indexOf(c) >= 0
                val isCloser = lang.closers.contains(c)

                // skip over an existing closer
                if (cfg.autoCloseBrackets && (isCloser || isQuote) && next == c &&
                    (!isQuote || quoteClosing(nt, pos, c))
                ) return TextFieldValue(ot, TextRange(pos + 1))

                // auto-close brackets and quotes
                if (cfg.autoCloseBrackets) {
                    val closeBr = lang.pairs[c]
                    if (closeBr != null) {
                        if (next == null || next.isWhitespace() || lang.closers.contains(next) || ",;.".indexOf(next) >= 0) {
                            return TextFieldValue(nt.substring(0, pos + 1) + closeBr + nt.substring(pos + 1), TextRange(pos + 1))
                        }
                    } else if (isQuote &&
                        (prev == null || !(prev.isLetterOrDigit() || prev == '\\' || prev == c)) &&
                        (next == null || !next.isLetterOrDigit()) &&
                        !quoteClosing(nt, pos, c)
                    ) {
                        return TextFieldValue(nt.substring(0, pos + 1) + c + nt.substring(pos + 1), TextRange(pos + 1))
                    }
                }

                // dedent a closing bracket typed on a blank line
                if (cfg.autoIndent && (c == '}' || c == ')' || c == ']') && !lang.isMarkup) {
                    val ls = nt.lastIndexOf('\n', pos - 1) + 1
                    val before = nt.substring(ls, pos)
                    if (before.isNotEmpty() && before.all { it == ' ' || it == '\t' }) {
                        val unit = indentUnit()
                        val trimmed = when {
                            before.endsWith(unit) -> before.removeSuffix(unit)
                            before.endsWith("\t") -> before.dropLast(1)
                            else -> before.dropLast(minOf(before.length, unit.length))
                        }
                        val res = nt.substring(0, ls) + trimmed + nt.substring(pos)
                        return TextFieldValue(res, TextRange(ls + trimmed.length + 1))
                    }
                }

                // auto-close HTML/XML tag
                if (cfg.autoCloseTags && lang.isMarkup && c == '>') {
                    val lt = nt.lastIndexOf('<', pos)
                    if (lt >= 0 && lt < pos && !nt.substring(lt, pos).contains('>')) {
                        val inner = nt.substring(lt + 1, pos)
                        if (inner.isNotEmpty() && inner[0].isLetter() && !inner.endsWith("/")) {
                            val name = inner.takeWhile { it.isLetterOrDigit() || it == '-' || it == ':' || it == '_' || it == '.' }
                            val void = lang.id == "html" && name.lowercase() in setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")
                            if (!void && !nt.startsWith("</$name", pos + 1)) {
                                return TextFieldValue(
                                    nt.substring(0, pos + 1) + "</$name>" + nt.substring(pos + 1), TextRange(pos + 1),
                                )
                            }
                        }
                    }
                }
            }
            return new
        }

        // single character deleted
        if (d.inserted == 0 && d.removed == 1 && new.selection.collapsed && new.selection.start == d.start) {
            val rc = ot.getOrNull(d.start)
            if (rc != null) {
                if (cfg.autoCloseBrackets) {
                    val close = lang.pairs[rc] ?: if (lang.quotePairs.indexOf(rc) >= 0) rc else null
                    if (close != null && nt.getOrNull(d.start) == close) {
                        return TextFieldValue(nt.removeRange(d.start, d.start + 1), TextRange(d.start))
                    }
                }
                if (rc == ' ' && cfg.autoIndent) {
                    val unit = indentUnit()
                    val ls = nt.lastIndexOf('\n', d.start - 1) + 1
                    val before = nt.substring(ls, d.start)
                    if (unit.all { it == ' ' } && before.isNotEmpty() && before.all { it == ' ' }) {
                        val extra = before.length % unit.length
                        if (extra > 0) return TextFieldValue(nt.removeRange(d.start - extra, d.start), TextRange(d.start - extra))
                    }
                }
            }
        }
        return new
    }

    private val openTag = Regex("<([A-Za-z][\\w:.-]*)(?:\\s[^<>]*)?>$")

    private fun smartEnter(new: TextFieldValue, pos: Int): TextFieldValue {
        if (!config.autoIndent) return new
        val lang = language
        val text = new.text
        val before = text.substring(0, pos)
        val after = text.substring(pos + 1)
        val ls = before.lastIndexOf('\n') + 1
        val line = before.substring(ls)
        val indent = line.takeWhile { it == ' ' || it == '\t' }
        val unit = indentUnit()
        val trimmed = before.trimEnd(' ', '\t')
        val last = trimmed.lastOrNull()
        var extra = ""
        var split = false

        if (lang.isMarkup) {
            val m = openTag.find(trimmed.takeLast(300))
            if (m != null && !trimmed.endsWith("/>")) {
                val name = m.groupValues[1].lowercase()
                val void = lang.id == "html" && name in setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")
                if (!void) { extra = unit; split = after.startsWith("</") }
            }
        } else if (last != null && last in lang.indentAfter) {
            val scan = scanOf(text, lang)
            if (trimmed.isNotEmpty() && scan.isCode(trimmed.length - 1)) {
                extra = unit
                split = lang.pairs[last] == after.firstOrNull()
            }
        }

        var commentPrefix = ""
        val bc = lang.blockComment
        if (extra.isEmpty() && bc != null && bc.first == "/*") {
            val tl = line.trimStart()
            if (tl.startsWith("/*") && !tl.contains("*/")) commentPrefix = " * "
            else if (tl.startsWith("*") && !tl.startsWith("*/")) commentPrefix = "* "
        }

        val mid = before + "\n" + indent + extra + commentPrefix
        return if (split) TextFieldValue(mid + "\n" + indent + after, TextRange(mid.length))
        else TextFieldValue(mid + after, TextRange(mid.length))
    }

    // ---------------- undo / redo ----------------
    private val undoStack = ArrayList<TextFieldValue>()
    private val redoStack = ArrayList<TextFieldValue>()
    var undoCount by mutableIntStateOf(0)
        private set
    var redoCount by mutableIntStateOf(0)
        private set
    private var lastEditTime = 0L
    private var lastKind = 0

    private fun syncHistoryCounts() { undoCount = undoStack.size; redoCount = redoStack.size }

    private fun pushUndo(old: TextFieldValue, new: TextFieldValue, d: Diff) {
        val kind = when {
            d.removed == 0 && d.inserted == 1 && new.text.getOrNull(d.start) != '\n' -> 1
            d.removed == 1 && d.inserted == 0 -> 2
            else -> 0
        }
        val now = System.currentTimeMillis()
        val coalesce = kind != 0 && kind == lastKind && now - lastEditTime < 800 && undoStack.isNotEmpty()
        if (!coalesce) {
            undoStack.add(old.copy(composition = null))
            if (undoStack.size > 300) undoStack.removeAt(0)
        }
        redoStack.clear()
        lastKind = kind
        lastEditTime = now
        syncHistoryCounts()
    }

    private fun restore(v: TextFieldValue) {
        val change = diff(value.text, v.text)
        val newLineStarts = computeLineStarts(v.text)
        val rebasedFolds = rebaseFoldedRanges(change, newLineStarts)
        shiftDiagnostics(change)
        value = v.copy(composition = null)
        lineStarts = newLineStarts
        foldedLineRanges = rebasedFolds
        lastKind = 0
        dismissCompletion()
        syncHistoryCounts()
        reveal()
    }

    fun undo() {
        if (undoStack.isEmpty() || config.readOnly) return
        redoStack.add(value)
        restore(undoStack.removeAt(undoStack.size - 1))
    }

    fun redo() {
        if (redoStack.isEmpty() || config.readOnly) return
        undoStack.add(value)
        restore(redoStack.removeAt(redoStack.size - 1))
    }

    // ---------------- line commands ----------------
    private class LineBlock(val start: Int, val end: Int)

    private fun lineBlock(): LineBlock {
        val t = value.text
        val s = value.selection.min
        var e = value.selection.max
        if (e > s && e > 0 && t[e - 1] == '\n') e--
        val start = t.lastIndexOf('\n', s - 1) + 1
        var end = t.indexOf('\n', e)
        if (end < 0) end = t.length
        return LineBlock(start, end)
    }

    fun indent() {
        if (config.readOnly) return
        val v = value
        val unit = indentUnit()
        if (v.selection.collapsed) { typeText(unit); return }
        val b = lineBlock()
        val seg = v.text.substring(b.start, b.end)
        val lines = seg.split('\n')
        val newSeg = lines.joinToString("\n") { if (it.isEmpty()) it else unit + it }
        val delta = newSeg.length - seg.length
        edit(v.text.replaceRange(b.start, b.end, newSeg), TextRange(v.selection.min + unit.length, v.selection.max + delta))
    }

    fun outdent() {
        if (config.readOnly) return
        val v = value
        val unit = indentUnit()
        val b = lineBlock()
        val seg = v.text.substring(b.start, b.end)
        var first = 0
        var total = 0
        val newSeg = seg.split('\n').mapIndexed { idx, line ->
            val remove = when {
                line.startsWith(unit) -> unit.length
                line.startsWith("\t") -> 1
                else -> line.takeWhile { it == ' ' }.length.coerceAtMost(unit.length)
            }
            if (idx == 0) first = remove
            total += remove
            line.substring(remove)
        }.joinToString("\n")
        if (total == 0) return
        val s = maxOf(b.start, v.selection.min - first)
        val e = if (v.selection.collapsed) s else maxOf(s, v.selection.max - total)
        edit(v.text.replaceRange(b.start, b.end, newSeg), TextRange(s, e))
    }

    fun toggleComment() {
        if (config.readOnly) return
        val v = value
        val b = lineBlock()
        val seg = v.text.substring(b.start, b.end)
        val lc = language.lineComment
        if (lc != null) {
            val lines = seg.split('\n')
            val nonBlank = lines.filter { it.isNotBlank() }
            if (nonBlank.isEmpty()) return
            val allCommented = nonBlank.all { it.trimStart().startsWith(lc) }
            var first = 0
            val out = if (allCommented) {
                lines.mapIndexed { idx, line ->
                    val at = line.indexOf(lc)
                    if (at < 0) line else {
                        var len = lc.length
                        if (line.getOrNull(at + len) == ' ') len++
                        if (idx == 0) first = -len
                        line.removeRange(at, at + len)
                    }
                }
            } else {
                val col = nonBlank.minOf { l -> l.takeWhile { it == ' ' || it == '\t' }.length }
                lines.mapIndexed { idx, line ->
                    if (line.isBlank()) line else {
                        if (idx == 0) first = lc.length + 1
                        line.substring(0, col) + lc + " " + line.substring(col)
                    }
                }
            }
            val newSeg = out.joinToString("\n")
            val delta = newSeg.length - seg.length
            val s = maxOf(b.start, v.selection.min + first)
            val e = if (v.selection.collapsed) s else v.selection.max + delta
            edit(v.text.replaceRange(b.start, b.end, newSeg), TextRange(s, e))
        } else {
            val bc = language.blockComment ?: return
            val ind = seg.takeWhile { it == ' ' || it == '\t' }
            val body = seg.trim()
            val newSeg = if (body.startsWith(bc.first) && body.endsWith(bc.second)) {
                ind + body.removePrefix(bc.first).removeSuffix(bc.second).trim()
            } else ind + bc.first + " " + body + " " + bc.second
            edit(v.text.replaceRange(b.start, b.end, newSeg), TextRange(b.start + newSeg.length))
        }
    }

    fun duplicateLines() {
        if (config.readOnly) return
        val v = value
        val b = lineBlock()
        val seg = v.text.substring(b.start, b.end)
        val t = v.text.substring(0, b.end) + "\n" + seg + v.text.substring(b.end)
        val shift = seg.length + 1
        edit(t, TextRange(v.selection.start + shift, v.selection.end + shift))
    }

    fun deleteLines() {
        if (config.readOnly) return
        val v = value
        val b = lineBlock()
        val t = v.text
        val (from, to) = when {
            b.end < t.length -> b.start to b.end + 1
            b.start > 0 -> b.start - 1 to b.end
            else -> b.start to b.end
        }
        edit(t.removeRange(from, to), TextRange(minOf(b.start, t.length - (to - from)).coerceAtLeast(0)))
    }

    fun moveLines(up: Boolean) {
        if (config.readOnly) return
        val v = value
        val t = v.text
        val b = lineBlock()
        val seg = t.substring(b.start, b.end)
        if (up) {
            if (b.start == 0) return
            val ps = t.lastIndexOf('\n', b.start - 2) + 1
            val prevLine = t.substring(ps, b.start - 1)
            val shift = prevLine.length + 1
            edit(t.substring(0, ps) + seg + "\n" + prevLine + t.substring(b.end),
                TextRange(v.selection.start - shift, v.selection.end - shift))
        } else {
            if (b.end >= t.length) return
            var ne = t.indexOf('\n', b.end + 1)
            if (ne < 0) ne = t.length
            val nextLine = t.substring(b.end + 1, ne)
            val shift = nextLine.length + 1
            edit(t.substring(0, b.start) + nextLine + "\n" + seg + t.substring(ne),
                TextRange(v.selection.start + shift, v.selection.end + shift))
        }
    }

    fun selectAll() { value = value.copy(selection = TextRange(0, value.text.length)) }

    fun selectLine() {
        val b = lineBlock()
        value = value.copy(selection = TextRange(b.start, minOf(b.end + 1, value.text.length)))
    }

    fun gotoLine(line: Int) {
        val starts = computeLineStarts(value.text)
        val idx = (line - 1).coerceIn(0, starts.size - 1)
        value = value.copy(selection = TextRange(starts[idx]))
        requestFocus()
        reveal()
    }

    fun moveCursor(delta: Int) {
        val c = (value.selection.start + delta).coerceIn(0, value.text.length)
        value = value.copy(selection = TextRange(c))
        dismissCompletion()
    }

    fun moveCursorLine(delta: Int) {
        val layout = textLayout ?: return
        val len = layout.layoutInput.text.length
        val cur = value.selection.start.coerceIn(0, len)
        val rect = layout.getCursorRect(cur)
        val y = if (delta < 0) rect.top - rect.height / 2 else rect.bottom + rect.height / 2
        val off = layout.getOffsetForPosition(Offset(rect.left, y.coerceAtLeast(0f)))
        value = value.copy(selection = TextRange(off.coerceIn(0, value.text.length)))
        dismissCompletion()
    }

    fun zoom(deltaSp: Float) {
        val s = (config.fontSize.value + deltaSp).coerceIn(8f, 32f)
        config = config.copy(fontSize = s.sp)
    }

    fun requestFocus() { runCatching { focusRequester.requestFocus() } }

    var revealTick by mutableIntStateOf(0)
        private set

    private fun reveal() { revealTick++ }

    // ---------------- completion ----------------
    var completions by mutableStateOf<List<CompletionItem>>(emptyList())
        private set
    var completionIndex by mutableIntStateOf(0)
        private set
    private var completionPrefixLen = 0
    val completionVisible: Boolean get() = completions.isNotEmpty()

    fun triggerCompletion() = refreshCompletion(true)

    fun dismissCompletion() { if (completions.isNotEmpty()) completions = emptyList() }

    fun moveCompletion(delta: Int) {
        val n = completions.size
        if (n == 0) return
        completionIndex = ((completionIndex + delta) % n + n) % n
    }

    private fun refreshCompletion(force: Boolean) {
        if (config.readOnly || (!force && !config.autoComplete)) { dismissCompletion(); return }
        val v = value
        if (!v.selection.collapsed) { dismissCompletion(); return }
        val t = v.text
        val cur = v.selection.start
        val lang = language
        var s = cur
        while (s > 0 && lang.isWordChar(t[s - 1])) s--
        val prefix = t.substring(s, cur)
        if (!force && prefix.length < config.completionMinChars) { dismissCompletion(); return }
        if (!force && cur > 0) {
            val ty = scanOf(t, lang).typeAt(cur - 1)
            if (ty == TokenType.Comment || ty == TokenType.DocComment) { dismissCompletion(); return }
        }
        var items = completionProvider.complete(CompletionContext(t, cur, prefix, lang))
        if (items.size == 1 && items[0].label == prefix && items[0].kind != CompletionKind.Snippet) items = emptyList()
        completionPrefixLen = prefix.length
        completions = items
        completionIndex = 0
    }

    fun acceptCompletion(item: CompletionItem? = completions.getOrNull(completionIndex)) {
        if (item == null || config.readOnly) return
        val v = value
        val t = v.text
        val cur = v.selection.start
        val start = (cur - completionPrefixLen).coerceAtLeast(0)
        val ls = t.lastIndexOf('\n', start - 1) + 1
        val indent = t.substring(ls, start).takeWhile { it == ' ' || it == '\t' }
        val unit = indentUnit()
        var body = item.insertText
        var caret = item.cursorOffset ?: body.length
        val marker = body.indexOf("$0")
        if (item.cursorOffset == null && marker >= 0) {
            body = body.removeRange(marker, marker + 2)
            caret = marker
        }
        fun fix(s: String) = s.replace("\t", unit).replace("\n", "\n$indent")
        val pre = fix(body.substring(0, caret.coerceIn(0, body.length)))
        val post = fix(body.substring(caret.coerceIn(0, body.length)))
        edit(t.substring(0, start) + pre + post + t.substring(cur), TextRange(start + pre.length))
    }

    // ---------------- diagnostics ----------------
    var diagnostics by mutableStateOf<List<Diagnostic>>(emptyList())
        private set

    val diagnosticAtCursor: Diagnostic? by derivedStateOf {
        val c = value.selection.start
        diagnostics.firstOrNull { c >= it.start && c <= it.end }
            ?: diagnostics.firstOrNull { it.severity == Severity.Error && cursorLine == lineOf(it.start) }
    }

    private fun lineOf(offset: Int): Int = lineOf(lineStarts, offset)

    private fun lineOf(starts: IntArray, offset: Int): Int {
        val idx = java.util.Arrays.binarySearch(starts, offset)
        val line = if (idx >= 0) idx else -idx - 2
        return line.coerceIn(0, starts.lastIndex)
    }

    private fun shiftDiagnostics(change: Diff) {
        if (diagnostics.isEmpty()) return
        val delta = change.inserted - change.removed
        val editEnd = change.start + change.removed
        diagnostics = diagnostics.mapNotNull { g ->
            when {
                g.end <= change.start -> g
                g.start >= editEnd -> g.copy(start = g.start + delta, end = g.end + delta)
                else -> null
            }
        }
    }

    val semanticAnalyzer = SemanticAnalyzer()
    var activeSemanticResult by mutableStateOf<SemanticAnalysisResult?>(null)

    suspend fun runDiagnostics() {
        if (!config.diagnosticsEnabled) {
            if (diagnostics.isNotEmpty()) diagnostics = emptyList()
            activeSemanticResult = null
            return
        }
        delay(config.diagnosticsDelayMs)
        val t = text
        val lang = language
        val extra = diagnosticsProvider
        val runSemantic = config.semanticAnalysisEnabled
        val result = withContext(Dispatchers.Default) {
            val scan = scanOf(t, lang)
            val base = Diagnostics.analyze(t, lang, scan)
            val semanticDiags = if (runSemantic) {
                val semResult = semanticAnalyzer.analyze(t, lang, scan)
                activeSemanticResult = semResult
                semResult.diagnostics
            } else emptyList()
            val ext = extra?.diagnose(t, lang).orEmpty()
            Diagnostics.normalize(base + semanticDiags + ext, t.length)
        }
        diagnostics = result
    }

    // ---------------- code folding ----------------
    var foldedLineRanges by mutableStateOf<Set<IntRange>>(emptySet())
        private set

    private fun rebaseFoldedRanges(change: Diff, newLineStarts: IntArray): Set<IntRange> {
        if (foldedLineRanges.isEmpty() || !config.codeFoldingEnabled) return emptySet()
        val regions = foldableRegions
        val delta = change.inserted - change.removed
        val editEnd = change.start + change.removed

        fun mapOffset(offset: Int): Int = when {
            offset < change.start -> offset
            offset >= editEnd -> offset + delta
            else -> change.start + change.inserted
        }

        return foldedLineRanges.mapNotNull { range ->
            val region = regions.firstOrNull {
                it.startLine == range.first && it.endLine == range.last
            } ?: return@mapNotNull null
            val start = lineOf(newLineStarts, mapOffset(region.startOffset))
            val endOffset = (region.endOffset - 1).coerceAtLeast(region.startOffset)
            val end = lineOf(newLineStarts, mapOffset(endOffset))
            if (end > start) start..end else null
        }.toSet()
    }

    val foldableRegions: List<FoldRegion> by derivedStateOf {
        if (!config.codeFoldingEnabled) emptyList()
        else CodeFolding.findFoldableRegions(value.text, language, scanOf(value.text, language), lineStarts)
    }

    val foldResult: FoldResult by derivedStateOf {
        if (!config.codeFoldingEnabled || foldedLineRanges.isEmpty()) FoldResult(value.text, androidx.compose.ui.text.input.OffsetMapping.Identity, emptySet())
        else CodeFolding.createFoldResult(value.text, lineStarts, foldedLineRanges, foldableRegions)
    }

    fun toggleFold(line: Int) {
        val region = foldableRegions.firstOrNull { it.startLine == line } ?: return
        val range = region.startLine..region.endLine
        foldedLineRanges = if (foldedLineRanges.contains(range)) {
            foldedLineRanges - setOf(range)
        } else {
            foldedLineRanges + setOf(range)
        }
    }

    fun fold(startLine: Int, endLine: Int) {
        foldedLineRanges = foldedLineRanges + setOf(startLine..endLine)
    }

    fun unfold(startLine: Int, endLine: Int) {
        foldedLineRanges = foldedLineRanges - setOf(startLine..endLine)
    }

    fun foldAll() {
        foldedLineRanges = foldableRegions.map { it.startLine..it.endLine }.toSet()
    }

    fun unfoldAll() {
        foldedLineRanges = emptySet()
    }

    fun isLineFolded(line: Int): Boolean =
        foldedLineRanges.any { line in it }

    fun isLineFoldStart(line: Int): Boolean =
        foldableRegions.any { it.startLine == line }

    // ---------------- semantic & definition info ----------------
    fun getDefinitionAtCursor(): SemanticSymbol? =
        activeSemanticResult?.getDefinitionAt(value.selection.start)

    fun getHoverInfoAtCursor(): String? =
        activeSemanticResult?.getHoverInfoAt(value.selection.start)

    // ---------------- viewport virtualization ----------------
    var viewportScrollY by mutableFloatStateOf(0f)
    var viewportHeightPx by mutableFloatStateOf(0f)

    val activeViewportRange: ViewportRange by derivedStateOf {
        if (!config.viewportVirtualization) ViewportRange(0, maxOf(0, lineStarts.size - 1), 0, value.text.length)
        else ViewportVirtualization.computeVisibleRange(viewportScrollY, viewportHeightPx, lineStarts, value.text.length, textLayout)
    }

    // ---------------- find & replace ----------------
    var searchVisible by mutableStateOf(false)
    var showReplace by mutableStateOf(false)
    var searchQuery by mutableStateOf("")
    var replaceText by mutableStateOf("")
    var caseSensitive by mutableStateOf(false)
    var wholeWord by mutableStateOf(false)
    var useRegex by mutableStateOf(false)

    fun openSearch(replace: Boolean = false) {
        searchVisible = true
        showReplace = replace
        val sel = value.selection
        if (!sel.collapsed && !value.text.substring(sel.min, sel.max).contains('\n')) {
            searchQuery = value.text.substring(sel.min, sel.max)
        }
    }

    fun closeSearch() { searchVisible = false; requestFocus() }

    private fun buildRegex(): Regex? {
        if (searchQuery.isEmpty()) return null
        var p = if (useRegex) searchQuery else Regex.escape(searchQuery)
        if (wholeWord) p = "\\b(?:$p)\\b"
        return try {
            Regex(p, if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
        } catch (e: Exception) { null }
    }

    val searchInvalid: Boolean get() = useRegex && searchQuery.isNotEmpty() && buildRegex() == null

    val searchMatches: List<IntRange> by derivedStateOf {
        if (!searchVisible || searchQuery.isEmpty()) emptyList() else {
            val rx = buildRegex()
            if (rx == null) emptyList()
            else rx.findAll(value.text).take(5000).map { it.range }.filter { it.last >= it.first }.toList()
        }
    }

    val currentMatchIndex: Int
        get() {
            val s = value.selection
            return searchMatches.indexOfFirst { it.first == s.min && it.last + 1 == s.max }
        }

    fun findNext(forward: Boolean = true) {
        val m = searchMatches
        if (m.isEmpty()) return
        val s = value.selection
        val idx = if (forward) {
            m.indexOfFirst { it.first >= s.max }.takeIf { it >= 0 } ?: 0
        } else {
            m.indexOfLast { it.last + 1 <= s.min }.takeIf { it >= 0 } ?: m.lastIndex
        }
        value = value.copy(selection = TextRange(m[idx].first, m[idx].last + 1))
        reveal()
    }

    fun replaceCurrent() {
        if (config.readOnly) return
        val rx = buildRegex() ?: return
        val s = value.selection
        if (currentMatchIndex < 0) { findNext(true); return }
        val selected = value.text.substring(s.min, s.max)
        val rep = if (useRegex) rx.replace(selected, replaceText) else replaceText
        edit(value.text.replaceRange(s.min, s.max, rep), TextRange(s.min + rep.length))
        findNext(true)
    }

    fun replaceAll() {
        if (config.readOnly) return
        val rx = buildRegex() ?: return
        val rep = if (useRegex) replaceText else Regex.escapeReplacement(replaceText)
        edit(rx.replace(value.text, rep), TextRange(0))
    }
}
