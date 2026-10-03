package com.joe.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.max
import kotlin.math.min

/**
 * A full-featured code editor: syntax highlighting, line numbers, auto-indent, auto-close,
 * completion, diagnostics, find/replace, undo/redo, bracket matching and a mobile symbol bar.
 */
@Composable
fun CodeEditor(
    state: CodeEditorState,
    modifier: Modifier = Modifier,
    theme: EditorTheme = EditorThemes.Darcula,
    showSymbolBar: Boolean = true,
    showStatusBar: Boolean = true,
) {
    LaunchedEffect(state.text, state.language, state.config.diagnosticsEnabled) {
        state.runDiagnostics()
    }
    Column(modifier.background(theme.background)) {
        if (state.searchVisible) FindReplaceBar(state, theme)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            EditorSurface(state, theme)
        }
        if (state.config.completionStyle == CompletionStyle.Bar && state.completionVisible) {
            CompletionBar(state, theme)
        }
        if (showSymbolBar && !state.config.readOnly) SymbolBar(state, theme)
        if (showStatusBar) StatusBar(state, theme)
    }
}

// ---------------------------------------------------------------------------------------------
// Syntax highlighting
// ---------------------------------------------------------------------------------------------

private class SyntaxTransformation(
    private val state: CodeEditorState,
    private val language: Language,
    private val theme: EditorTheme,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val s = text.text
        val foldRes = state.foldResult
        val foldTransformed = foldRes.transformedText
        val mapping = foldRes.offsetMapping

        val scan = state.scanOf(s, language)
        val tokens = if (state.config.viewportVirtualization) {
            ViewportVirtualization.filterTokensForViewport(scan.tokens, state.activeViewportRange)
        } else scan.tokens

        val styled = buildAnnotatedString {
            append(foldTransformed)
            for (t in tokens) {
                val style = theme.spanStyle(t.type) ?: continue
                val origA = t.start.coerceIn(0, s.length)
                val origB = t.end.coerceIn(0, s.length)
                val transA = mapping.originalToTransformed(origA).coerceIn(0, foldTransformed.length)
                val transB = mapping.originalToTransformed(origB).coerceIn(0, foldTransformed.length)
                if (transB > transA) addStyle(style, transA, transB)
            }
        }
        return TransformedText(styled, mapping)
    }
}

// ---------------------------------------------------------------------------------------------
// Editor surface
// ---------------------------------------------------------------------------------------------

@Composable
private fun EditorSurface(state: CodeEditorState, theme: EditorTheme) {
    val density = LocalDensity.current
    val config = state.config
    val language = state.language
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    val textMeasurer = rememberTextMeasurer()

    val lineHeightSp = config.fontSize * config.lineHeightMultiplier
    val textStyle = remember(config.fontSize, config.fontFamily, config.lineHeightMultiplier, theme) {
        TextStyle(
            color = theme.foreground,
            fontSize = config.fontSize,
            fontFamily = config.fontFamily,
            lineHeight = lineHeightSp,
        )
    }
    val gutterStyle = remember(config.fontSize, config.fontFamily, theme) {
        TextStyle(
            fontSize = config.fontSize * 0.9f,
            fontFamily = config.fontFamily,
            color = theme.gutterText,
        )
    }
    val syntax = remember(language, theme, state) { SyntaxTransformation(state, language, theme) }

    val lineCount = state.lineStarts.size
    val digits = max(2, lineCount.toString().length)
    val gutterWidth: Dp = if (config.showLineNumbers) with(density) {
        (config.fontSize.toDp() * 0.62f * digits) + 28.dp
    } else 0.dp

    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val viewportW = constraints.maxWidth
        val viewportH = constraints.maxHeight
        val contentHeightDp = with(density) { state.contentHeightPx.toDp() } + 160.dp

        // gutter background sits behind the scrolling content so it never moves horizontally
        if (config.showLineNumbers) {
            Box(Modifier.width(gutterWidth).fillMaxSize().background(theme.gutterBackground))
        }

        LaunchedEffect(vScroll.value, viewportH) {
            state.viewportScrollY = vScroll.value.toFloat()
            state.viewportHeightPx = viewportH.toFloat()
        }

        // keep the caret visible after edits / navigation
        LaunchedEffect(state.revealTick) {
            val layout = state.textLayout ?: return@LaunchedEffect
            val origOff = state.value.selection.end.coerceIn(0, state.value.text.length)
            val transOff = state.foldResult.offsetMapping.originalToTransformed(origOff)
            val clamped = transOff.coerceIn(0, max(0, layout.layoutInput.text.length))
            val r = layout.getCursorRect(clamped)
            val top = vScroll.value
            val lh = with(density) { lineHeightSp.toPx() }
            if (r.top < top) vScroll.scrollTo(max(0, (r.top - lh).toInt()))
            else if (r.bottom > top + viewportH) vScroll.scrollTo((r.bottom - viewportH + lh * 2).toInt())
            if (!config.wordWrap) {
                val gw = with(density) { gutterWidth.toPx() }
                val avail = viewportW - gw
                val left = hScroll.value
                if (r.left < left) hScroll.scrollTo(max(0, (r.left - 40).toInt()))
                else if (r.right > left + avail - 24) hScroll.scrollTo((r.right - avail + 80).toInt())
            }
        }

        Row(
            Modifier
                .fillMaxSize()
                .verticalScroll(vScroll),
        ) {
            if (config.showLineNumbers) {
                Canvas2(
                    Modifier
                        .width(gutterWidth)
                        .height(contentHeightDp)
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                val layout = state.textLayout ?: return@detectTapGestures
                                val vl = layout.getLineForVerticalPosition(offset.y)
                                if (vl in 0 until layout.lineCount) {
                                    val transStart = layout.getLineStart(vl)
                                    val origStart = state.foldResult.offsetMapping.transformedToOriginal(transStart)
                                    val origLine = lineOfOffset(state.lineStarts, origStart)
                                    if (state.isLineFoldStart(origLine)) {
                                        state.toggleFold(origLine)
                                    }
                                }
                            }
                        },
                ) {
                    drawGutter(state, theme, textMeasurer, gutterStyle, vScroll.value, viewportH, state.layoutVersion)
                }
            }
            val fieldBoxModifier = Modifier
                .weight(1f)
                .let { if (config.wordWrap) it else it.horizontalScroll(hScroll) }
                .padding(start = 8.dp)

            Box(fieldBoxModifier) {
                val minW = with(density) { (viewportW / 1f).toDp() } - gutterWidth - 8.dp
                CompositionLocalProvider(
                    LocalTextSelectionColors provides TextSelectionColors(theme.cursor, theme.selection),
                ) {
                    BasicTextField(
                        value = state.value,
                        onValueChange = state::onValueChange,
                        onTextLayout = state::onLayout,
                        textStyle = textStyle,
                        readOnly = config.readOnly,
                        cursorBrush = SolidColor(theme.cursor),
                        visualTransformation = syntax,
                        keyboardOptions = KeyboardOptions(
                            autoCorrect = false,
                            capitalization = KeyboardCapitalization.None,
                            keyboardType = KeyboardType.Ascii,
                        ),
                        //softWrap = config.wordWrap,
                        modifier = Modifier
                            .let {
                                if (config.wordWrap) it.fillMaxWidth().padding(end = 8.dp)
                                else it.widthIn(min = minW.coerceAtLeast(0.dp)).padding(end = 48.dp)
                            }
                            .heightIn(min = contentHeightDp)
                            .focusRequester(state.focusRequester)
                            .drawBehind {
                                drawEditorOverlays(state, theme, vScroll.value, viewportH, state.layoutVersion)
                            }
                            .onPreviewKeyEvent { handleKey(state, it) },
                    )
                }
            }
        }

        if (state.config.completionStyle == CompletionStyle.Popup && state.completionVisible) {
            CompletionPopup(state, theme, vScroll.value, hScroll.value, gutterWidth)
        }
    }
}

/** Thin wrapper so the gutter draws via a plain modifier (no Canvas dependency on foundation version). */
@Composable
private fun Canvas2(modifier: Modifier, onDraw: DrawScope.() -> Unit) {
    Box(modifier.drawBehind(onDraw))
}

// ---------------------------------------------------------------------------------------------
// Drawing
// ---------------------------------------------------------------------------------------------

private fun lineOfOffset(starts: IntArray, offset: Int): Int {
    if (starts.isEmpty()) return 0
    val idx = java.util.Arrays.binarySearch(starts, offset)
    val ln = if (idx >= 0) idx else -idx - 2
    return ln.coerceIn(0, starts.size - 1)
}

private fun DrawScope.drawGutter(
    state: CodeEditorState,
    theme: EditorTheme,
    measurer: TextMeasurer,
    style: TextStyle,
    scrollY: Int,
    viewportH: Int,
    @Suppress("UNUSED_PARAMETER") version: Int,
) {
    val layout = state.textLayout ?: return
    val starts = state.lineStarts
    val textLen = state.value.text.length
    val top = scrollY.toFloat()
    val bottom = top + viewportH
    val cursorLine = state.cursorLine
    val mapping = state.foldResult.offsetMapping

    val diagLines = HashMap<Int, Severity>()
    for (d in state.diagnostics) {
        val ln = lineOfOffset(starts, d.start.coerceIn(0, textLen))
        val prev = diagLines[ln]
        if (prev == null || d.severity.ordinal < prev.ordinal) diagLines[ln] = d.severity
    }

    val layoutLineCount = layout.lineCount
    val firstVisibleLine = layout.getLineForVerticalPosition(top.coerceAtLeast(0f))
        .coerceIn(0, layoutLineCount - 1)
    for (vl in firstVisibleLine until layoutLineCount) {
        val y = layout.getLineTop(vl)
        val lineH = layout.getLineBottom(vl) - y
        if (y > bottom) break

        val transStart = layout.getLineStart(vl)
        val origStart = mapping.transformedToOriginal(transStart)
        val origLine = lineOfOffset(starts, origStart)

        val active = origLine == cursorLine
        val label = (origLine + 1).toString()
        val m = measurer.measure(
            label,
            style.copy(color = if (active) theme.gutterActiveText else theme.gutterText),
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
        val x = size.width - m.size.width - 10.dp.toPx()
        drawText(m, topLeft = Offset(x, y + (lineH - m.size.height) / 2f))

        val sev = diagLines[origLine]
        if (sev != null) {
            drawCircle(theme.severityColor(sev), radius = 3.dp.toPx(), center = Offset(6.dp.toPx(), y + lineH / 2f))
        }

        if (state.config.codeFoldingEnabled && state.isLineFoldStart(origLine)) {
            val foldIcon = if (state.isLineFolded(origLine)) "▶" else "▼"
            val fm = measurer.measure(
                foldIcon,
                style.copy(color = theme.gutterActiveText, fontSize = style.fontSize * 0.8f),
                maxLines = 1,
            )
            drawText(fm, topLeft = Offset(14.dp.toPx(), y + (lineH - fm.size.height) / 2f))
        }
    }
}

private fun DrawScope.drawEditorOverlays(
    state: CodeEditorState,
    theme: EditorTheme,
    scrollY: Int,
    viewportH: Int,
    @Suppress("UNUSED_PARAMETER") version: Int,
) {
    val layout: TextLayoutResult = state.textLayout ?: return
    val textLen = state.value.text.length
    val starts = state.lineStarts
    val top = scrollY.toFloat()
    val bottom = top + viewportH
    val mapping = state.foldResult.offsetMapping
    val transLen = layout.layoutInput.text.length

    // current line
    if (state.config.highlightCurrentLine && state.value.selection.collapsed && starts.isNotEmpty()) {
        val ln = state.cursorLine.coerceIn(0, starts.size - 1)
        val s = starts[ln].coerceIn(0, textLen)
        val transS = mapping.originalToTransformed(s).coerceIn(0, transLen)
        val vl = layout.getLineForOffset(transS)
        val y1 = layout.getLineTop(vl)
        val y2 = layout.getLineBottom(vl)
        drawRect(theme.currentLine, topLeft = Offset(0f, y1), size = Size(size.width, y2 - y1))
    }

    // search matches
    if (state.searchVisible && state.searchQuery.isNotEmpty()) {
        val current = state.currentMatchIndex
        var drawn = 0
        state.searchMatches.forEachIndexed { idx, r ->
            if (drawn > 400) return@forEachIndexed
            if (r.first >= textLen) return@forEachIndexed
            val transStart = mapping.originalToTransformed(r.first).coerceIn(0, transLen)
            val transEnd = mapping.originalToTransformed(min(r.last + 1, textLen)).coerceIn(0, transLen)
            if (transEnd <= transStart) return@forEachIndexed
            val y = layout.getLineTop(layout.getLineForOffset(transStart))
            if (y > bottom || y < top - 400f) return@forEachIndexed
            drawPath(layout.getPathForRange(transStart, transEnd), if (idx == current) theme.searchActive else theme.searchMatch)
            drawn++
        }
    }

    // matching brackets
    state.bracketPair?.let { (a, b) ->
        for (idx in intArrayOf(a, b)) {
            if (idx < 0 || idx >= textLen) continue
            val transIdx = mapping.originalToTransformed(idx).coerceIn(0, transLen)
            val box = layout.getBoundingBox(transIdx)
            drawRect(theme.bracketMatch, topLeft = box.topLeft, size = box.size)
        }
    }

    // diagnostics squiggles
    var squiggles = 0
    for (d in state.diagnostics) {
        if (squiggles > 300) break
        val s = d.start.coerceIn(0, textLen)
        var e = d.end.coerceIn(0, textLen)
        if (e <= s) e = min(textLen, s + 1)
        if (e <= s) continue
        val transS = mapping.originalToTransformed(s).coerceIn(0, transLen)
        val transE = mapping.originalToTransformed(e).coerceIn(0, transLen)
        if (transE <= transS) continue
        val firstLine = layout.getLineForOffset(transS)
        val lastLine = layout.getLineForOffset(transE)
        val color = theme.severityColor(d.severity)
        for (vl in firstLine..lastLine) {
            val y = layout.getLineBottom(vl) - 2.dp.toPx()
            if (y < top || y - 200f > bottom) continue
            val segStart = max(transS, layout.getLineStart(vl))
            val segEnd = min(transE, layout.getLineEnd(vl, visibleEnd = true))
            if (segEnd <= segStart) continue
            val x1 = layout.getHorizontalPosition(segStart, true)
            val x2 = layout.getHorizontalPosition(segEnd, true)
            if (x2 - x1 < 1f) continue
            drawSquiggle(x1, x2, y, color)
            squiggles++
        }
    }
}

private fun DrawScope.drawSquiggle(x1: Float, x2: Float, y: Float, color: Color) {
    val step = 3.dp.toPx()
    val amp = 1.6.dp.toPx()
    val p = Path()
    p.moveTo(x1, y)
    var x = x1
    var up = true
    while (x < x2) {
        x = min(x + step, x2)
        p.lineTo(x, if (up) y - amp else y + amp)
        up = !up
    }
    drawPath(p, color, style = Stroke(width = 1.2.dp.toPx()))
}

// ---------------------------------------------------------------------------------------------
// Keyboard shortcuts (hardware keyboards + completion navigation)
// ---------------------------------------------------------------------------------------------

private fun handleKey(state: CodeEditorState, e: androidx.compose.ui.input.key.KeyEvent): Boolean {
    if (e.type != KeyEventType.KeyDown) return false
    val ctrl = e.isCtrlPressed || e.isMetaPressed
    val shift = e.isShiftPressed
    val alt = e.isAltPressed

    if (state.completionVisible) {
        when (e.key) {
            Key.DirectionDown -> { state.moveCompletion(1); return true }
            Key.DirectionUp -> { state.moveCompletion(-1); return true }
            Key.Enter, Key.NumPadEnter, Key.Tab -> { state.acceptCompletion(); return true }
            Key.Escape -> { state.dismissCompletion(); return true }
            else -> {}
        }
    }

    if (ctrl) {
        when (e.key) {
            Key.Z -> { if (shift) state.redo() else state.undo(); return true }
            Key.Y -> { state.redo(); return true }
            Key.F -> { state.openSearch(false); return true }
            Key.H -> { state.openSearch(true); return true }
            Key.Slash -> { state.toggleComment(); return true }
            Key.D -> { state.duplicateLines(); return true }
            Key.K -> if (shift) { state.deleteLines(); return true }
            Key.Spacebar -> { state.triggerCompletion(); return true }
            Key.LeftBracket -> { state.outdent(); return true }
            Key.RightBracket -> { state.indent(); return true }
            Key.L -> { state.selectLine(); return true }
            else -> {}
        }
    }
    if (alt) {
        when (e.key) {
            Key.DirectionUp -> { state.moveLines(true); return true }
            Key.DirectionDown -> { state.moveLines(false); return true }
            else -> {}
        }
    }
    if (e.key == Key.Tab && !ctrl && !alt) {
        if (shift) state.outdent() else state.indent()
        return true
    }
    if (e.key == Key.Escape && state.searchVisible) { state.closeSearch(); return true }
    return false
}

// ---------------------------------------------------------------------------------------------
// Completion UI
// ---------------------------------------------------------------------------------------------

private fun kindBadge(k: CompletionKind): Pair<String, TokenType> = when (k) {
    CompletionKind.Keyword -> "k" to TokenType.Keyword
    CompletionKind.Type -> "T" to TokenType.Type
    CompletionKind.Function -> "ƒ" to TokenType.Func
    CompletionKind.Variable -> "v" to TokenType.Variable
    CompletionKind.Snippet -> "≡" to TokenType.Str
    CompletionKind.Word -> "w" to TokenType.Plain
}

@Composable
private fun CompletionBar(state: CodeEditorState, theme: EditorTheme) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.completionIndex) {
        if (state.completions.isNotEmpty()) listState.animateScrollToItem(state.completionIndex.coerceIn(0, state.completions.size - 1))
    }
    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth().background(theme.popupBackground),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(state.completions) { idx, item ->
            val (badge, type) = kindBadge(item.kind)
            Row(
                Modifier
                    .background(if (idx == state.completionIndex) theme.popupSelected else Color.Transparent, RoundedCornerShape(6.dp))
                    .border(1.dp, theme.gutterText.copy(alpha = 0.25f), RoundedCornerShape(6.dp))
                    .clickable { state.acceptCompletion(item) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(badge, style = TextStyle(color = theme.colorOf(type), fontSize = 12.sp, fontFamily = FontFamily.Monospace))
                BasicText(
                    " " + item.label,
                    style = TextStyle(color = theme.foreground, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                    maxLines = 1,
                )
            }
        }
    }
}

private class CaretPopupPosition(private val anchor: IntOffset, private val gapPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = (anchorBounds.left + anchor.x).coerceIn(0, max(0, windowSize.width - popupContentSize.width))
        val below = anchorBounds.top + anchor.y + gapPx
        val y = if (below + popupContentSize.height > windowSize.height) {
            max(0, anchorBounds.top + anchor.y - popupContentSize.height - gapPx * 3)
        } else below
        return IntOffset(x, y)
    }
}

@Composable
private fun CompletionPopup(
    state: CodeEditorState,
    theme: EditorTheme,
    scrollY: Int,
    scrollX: Int,
    gutterWidth: Dp,
) {
    val density = LocalDensity.current
    val layout = state.textLayout ?: return
    val len = layout.layoutInput.text.length
    val r = layout.getCursorRect(state.value.selection.end.coerceIn(0, len))
    val gw = with(density) { gutterWidth.toPx() + 8.dp.toPx() }
    val anchor = IntOffset((gw + r.left - scrollX).toInt(), (r.bottom - scrollY).toInt())
    val gap = with(density) { 4.dp.roundToPx() }
    val listState = rememberLazyListState()
    LaunchedEffect(state.completionIndex) {
        if (state.completions.isNotEmpty()) listState.animateScrollToItem(state.completionIndex.coerceIn(0, state.completions.size - 1))
    }
    Popup(
        popupPositionProvider = remember(anchor, gap) { CaretPopupPosition(anchor, gap) },
        properties = PopupProperties(focusable = false, clippingEnabled = true),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .widthIn(min = 180.dp, max = 300.dp)
                .heightIn(max = 220.dp)
                .background(theme.popupBackground, RoundedCornerShape(8.dp))
                .border(1.dp, theme.gutterText.copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
        ) {
            itemsIndexed(state.completions) { idx, item ->
                val (badge, type) = kindBadge(item.kind)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (idx == state.completionIndex) theme.popupSelected else Color.Transparent)
                        .clickable { state.acceptCompletion(item) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(badge, style = TextStyle(color = theme.colorOf(type), fontSize = 12.sp, fontFamily = FontFamily.Monospace))
                    BasicText(
                        "  " + item.label,
                        style = TextStyle(color = theme.foreground, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Symbol bar / status bar / find bar
// ---------------------------------------------------------------------------------------------

@Composable
private fun BarButton(
    label: String,
    theme: EditorTheme,
    enabled: Boolean = true,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .padding(horizontal = 2.dp, vertical = 3.dp)
            .background(if (active) theme.popupSelected else Color.Transparent, RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            label,
            style = TextStyle(
                color = if (enabled) theme.foreground else theme.gutterText.copy(alpha = 0.5f),
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
            ),
        )
    }
}

private val SymbolKeys = listOf(
    "{", "}", "(", ")", "[", "]", "<", ">", ";", ":", "\"", "'", "=", "+", "-", "*", "/", "\\",
    "&", "|", "!", "?", "_", "#", "$", "%", "@", "`", ".", ",",
)

@Composable
private fun SymbolBar(state: CodeEditorState, theme: EditorTheme) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(theme.gutterBackground)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarButton("⇥", theme) { state.indent(); state.requestFocus() }
        BarButton("↶", theme, enabled = state.undoCount > 0) { state.undo() }
        BarButton("↷", theme, enabled = state.redoCount > 0) { state.redo() }
        BarButton("⌕", theme, active = state.searchVisible) { if (state.searchVisible) state.closeSearch() else state.openSearch(false) }
        BarButton("←", theme) { state.moveCursor(-1) }
        BarButton("→", theme) { state.moveCursor(1) }
        BarButton("↑", theme) { state.moveCursorLine(-1) }
        BarButton("↓", theme) { state.moveCursorLine(1) }
        for (k in SymbolKeys) BarButton(k, theme) { state.typeText(k) }
    }
}

@Composable
private fun StatusBar(state: CodeEditorState, theme: EditorTheme) {
    val errors = state.diagnostics.count { it.severity == Severity.Error }
    val warnings = state.diagnostics.count { it.severity == Severity.Warning }
    val msg = state.diagnosticAtCursor
    val small = TextStyle(color = theme.gutterActiveText, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    Row(
        Modifier.fillMaxWidth().background(theme.gutterBackground).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText("Ln ${state.cursorLine + 1}, Col ${state.cursorColumn + 1}", style = small)
        BasicText("   ${state.language.name}", style = small)
        if (errors > 0) BasicText("   ● $errors", style = small.copy(color = theme.error))
        if (warnings > 0) BasicText("   ▲ $warnings", style = small.copy(color = theme.warning))
        if (msg != null) {
            BasicText(
                "   " + msg.message,
                style = small.copy(color = theme.severityColor(msg.severity)),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun FindField(
    value: String,
    placeholder: String,
    theme: EditorTheme,
    invalid: Boolean,
    modifier: Modifier,
    onChange: (String) -> Unit,
    onEnter: () -> Unit,
) {
    Box(
        modifier
            .background(theme.background, RoundedCornerShape(6.dp))
            .border(1.dp, if (invalid) theme.error else theme.gutterText.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        if (value.isEmpty()) {
            BasicText(placeholder, style = TextStyle(color = theme.gutterText, fontSize = 13.sp, fontFamily = FontFamily.Monospace))
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = theme.foreground, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
            cursorBrush = SolidColor(theme.cursor),
            keyboardOptions = KeyboardOptions(autoCorrect = false, imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onEnter() }),
            modifier = Modifier.fillMaxWidth().onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && (it.key == Key.Enter || it.key == Key.NumPadEnter)) { onEnter(); true } else false
            },
        )
    }
}

@Composable
private fun FindReplaceBar(state: CodeEditorState, theme: EditorTheme) {
    Column(Modifier.fillMaxWidth().background(theme.gutterBackground).padding(horizontal = 6.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FindField(
                state.searchQuery, "Find", theme, state.searchInvalid,
                Modifier.weight(1f),
                onChange = { state.searchQuery = it },
                onEnter = { state.findNext(true) },
            )
            val total = state.searchMatches.size
            val cur = state.currentMatchIndex
            BasicText(
                if (state.searchQuery.isEmpty()) "" else if (total == 0) " 0/0" else " ${cur + 1}/$total",
                style = TextStyle(color = theme.gutterActiveText, fontSize = 11.sp, fontFamily = FontFamily.Monospace),
            )
            BarButton("▲", theme) { state.findNext(false) }
            BarButton("▼", theme) { state.findNext(true) }
            BarButton("✕", theme) { state.closeSearch() }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            BarButton("Aa", theme, active = state.caseSensitive) { state.caseSensitive = !state.caseSensitive }
            BarButton(".*", theme, active = state.useRegex) { state.useRegex = !state.useRegex }
            BarButton("\\b", theme, active = state.wholeWord) { state.wholeWord = !state.wholeWord }
            BarButton("⇄", theme, active = state.showReplace) { state.showReplace = !state.showReplace }
        }
        if (state.showReplace) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FindField(
                    state.replaceText, "Replace", theme, false,
                    Modifier.weight(1f),
                    onChange = { state.replaceText = it },
                    onEnter = { state.replaceCurrent() },
                )
                BarButton("Replace", theme, enabled = !state.config.readOnly) { state.replaceCurrent() }
                BarButton("All", theme, enabled = !state.config.readOnly) { state.replaceAll() }
            }
        }
    }
}
