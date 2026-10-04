# CodeForge

[![](https://jitpack.io/v/Loopoop/codeEditor.svg)](https://jitpack.io/#Loopoop/codeEditor)

An Ace-style code editor for Jetpack Compose. Pure Kotlin, no WebView.

**Languages (36):** Kotlin, Gradle (KTS + Groovy), Java, JavaScript, TypeScript, JSON, TOML, HTML, XML, CSS/SCSS/Less, PHP, Rust, Go, C, C++, C#, Python, Swift, Dart, Ruby, Lua, Shell, SQL, YAML, Markdown, R, Julia, Haskell, Scala, Elixir, Erlang, PowerShell, Perl, Nix, plain text.

**Features:** syntax highlighting, line numbers, code folding, built-in semantic analysis & LSP bridge, viewport virtualization, auto-indent, auto-close brackets/quotes/HTML tags, smart backspace, completion (keywords, snippets, document words, scope symbols), error/warning squiggles (bracket, tag, JSON and semantic scope checks), bracket matching, find & replace (regex, case, whole word), undo/redo, comment toggle, duplicate/move/delete line, 5 themes, mobile symbol bar, hardware-keyboard shortcuts.

## Install (JitPack)

```kotlin
// settings.gradle.kts
dependencyResolutionManagement { repositories { maven("https://jitpack.io") } }

// app/build.gradle.kts
implementation("com.github.Loopoop:CodeForge:main-SNAPSHOT")
```

Or include the `:codeeditor` module directly: `include(":codeeditor")` and `implementation(project(":codeeditor"))`.

---

## Quick Start

```kotlin
val state = remember { CodeEditorState("fun main() {\n    println(\"Hello CodeForge!\")\n}", Languages.Kotlin) }

CodeEditor(
    state = state,
    modifier = Modifier.fillMaxSize(),
    theme = EditorThemes.OneDark,
)
```

---

## Detailed Usage Guide

### 1. State Management & Content Updates

```kotlin
// Reading and replacing content
val currentText = state.text
state.setText(newFileContent, resetHistory = true)  // replaces text and clears undo stack

// Auto-detect language by file name or extension
state.language = Languages.forFileName("MainActivity.kt") // or Languages.Rust, Languages.Python, etc.

// Programmatic cursor movement and selection
state.gotoLine(line = 25)              // scroll & position cursor on line 25
state.selectLine()                     // selects the line under the caret
state.selectAll()                      // selects the full document
state.typeText("// inserted header\n") // inserts text at caret position
```

### 2. Configuration (`EditorConfig`)

Customize behavior, fonts, indentation, and performance settings:

```kotlin
state.config = state.config.copy(
    fontSize = 15.sp,
    fontFamily = FontFamily.Monospace,           // must be monospaced
    showLineNumbers = true,
    highlightCurrentLine = true,
    bracketMatching = true,
    wordWrap = false,
    readOnly = false,
    useTabs = false,
    indentSize = 4,                              // 4 spaces per indent level
    autoIndent = true,
    autoCloseBrackets = true,
    autoCloseTags = true,
    autoComplete = true,
    completionMinChars = 1,
    completionStyle = CompletionStyle.Popup,     // CompletionStyle.Popup (IDE) or CompletionStyle.Bar (Mobile)
    diagnosticsEnabled = true,
    diagnosticsDelayMs = 300,
    codeFoldingEnabled = true,
    semanticAnalysisEnabled = true,
    viewportVirtualization = true,
    largeFileThreshold = 2000,
)
```

### 3. Themes

Built-in themes: `Darcula`, `OneDark`, `Monokai`, `Dracula`, `GitHubLight`.

```kotlin
CodeEditor(
    state = state,
    theme = EditorThemes.Dracula,
    showSymbolBar = true,                        // mobile symbol bar at the bottom
    showStatusBar = true,                        // line/col & error/warning counts status bar
)
```

### 4. Programmatic Find & Replace

```kotlin
state.openSearch(replace = true)               // opens find & replace bar
state.searchQuery = "oldFunction"
state.replaceText = "newFunction"
state.caseSensitive = true
state.useRegex = false
state.findNext(forward = true)
state.replaceCurrent()
state.replaceAll()
state.closeSearch()
```

### 5. Code Folding API

Code folding automatically supports bracket blocks (`{ ... }`, `[ ... ]`), HTML/XML tags (`<tag> ... </tag>`), block comments (`/* ... */`), and indentation blocks:

```kotlin
// Gutter interaction: Tap the ▼ / ▶ icons in the line-number gutter

// Programmatic Folding API
state.toggleFold(line = 10)                    // toggles fold at line 10
state.fold(startLine = 10, endLine = 45)       // folds range from line 10 to 45
state.unfold(startLine = 10, endLine = 45)     // unfolds range
state.foldAll()                                // folds all foldable regions in document
state.unfoldAll()                              // expands all folded regions
```

Folded regions track their code when edits add or remove lines elsewhere. Replacing the
document with `setText` clears folds so they cannot accidentally apply to unrelated code.

### 6. Semantic Analysis & Custom Linters

CodeForge includes a built-in semantic analyzer for scope tracking, unused identifier detection, and duplicate symbol checks:

```kotlin
// Combine built-in completion with semantic completion
state.completionProvider = CompositeCompletionProvider(
    DefaultCompletionProvider,
    SemanticCompletionProvider(),
)

// Plug in a custom background linter / static analyzer
state.diagnosticsProvider = DiagnosticsProvider { text, language ->
    // runs off the main thread on Dispatchers.Default
    listOf(
        Diagnostic(start = 0, end = 5, message = "Custom warning", severity = Severity.Warning)
    )
}
```

### 7. Connecting to a Language Server (LSP)

Use `StandardLspClient` to connect CodeForge to an external Language Server binary (Kotlin, Rust Analyzer, gopls, Pyright, etc.):

```kotlin
val lspProcess = ProcessBuilder("rust-analyzer").start()
val lspClient = StandardLspClient(lspProcess.inputStream, lspProcess.outputStream)

// Initialize LSP session
coroutineScope.launch {
    lspClient.initialize("file:///path/to/project")
    lspClient.didOpen("file:///path/to/project/main.rs", "rust", fileContent)
}

// Wire LSP diagnostics and completions into CodeEditorState
state.diagnosticsProvider = LspDiagnosticsProvider(lspClient, "file:///path/to/project/main.rs")
state.completionProvider = LspCompletionProvider(lspClient, "file:///path/to/project/main.rs")
```

### 8. Custom Languages

```kotlin
val Toml = codeLanguage(
    id = "toml", name = "TOML", extensions = listOf("toml"),
    spec = LanguageSpec(
        lineComments = listOf("#"),
        keywords = setOf("true", "false"),
    ),
)
Languages.register(Toml)
```

---

## Keyboard Shortcuts

| Shortcut                                | Action                      |
| --------------------------------------- | --------------------------- |
| **Ctrl/Cmd + Z**                        | Undo                        |
| **Ctrl/Cmd + Shift + Z / Ctrl/Cmd + Y** | Redo                        |
| **Ctrl/Cmd + F**                        | Open Find                   |
| **Ctrl/Cmd + H**                        | Open Find & Replace         |
| **Ctrl/Cmd + / **                       | Toggle comment              |
| **Ctrl/Cmd + D**                        | Duplicate line(s)           |
| **Ctrl/Cmd + Shift + K**                | Delete line(s)              |
| **Ctrl/Cmd + Space**                    | Trigger completion          |
| **Ctrl/Cmd + [ / ]**                    | Outdent / Indent            |
| **Ctrl/Cmd + Shift + [ / ]**            | Fold / Unfold current block |
| **Alt + ↑ / ↓**                         | Move line(s) up / down      |
| **Tab / Shift + Tab**                   | Indent / Outdent            |

---

## Troubleshooting Guide

### 1. Performance & Large File Rendering

- **Symptom:** Editor stutters or delays when typing in large files (10,000+ lines).
- **Solution:** Ensure `viewportVirtualization = true` in `EditorConfig`. Verify that `maxHighlightLength` is kept within reasonable limits (default `400_000` chars). For files exceeding `largeFileThreshold` (2,000 lines), CodeForge automatically uses incremental token scanning and viewport line clipping.

### 2. Caret / Text Misalignment

- **Symptom:** Selection highlights, cursor position, or line numbers appear horizontally shifted.
- **Solution:** Always set `fontFamily` in `EditorConfig` to a monospaced font family (e.g., `FontFamily.Monospace` or a custom monospaced `FontFamily`). Proportional fonts (like Arial or Roboto) cause character measurement mismatch.

### 3. Code Folding Icons Not Showing in Gutter

- **Symptom:** Chevron `▼` / `▶` icons do not appear in the gutter.
- **Solution:** Check that `config.showLineNumbers = true` and `config.codeFoldingEnabled = true`. Folding icons appear on lines where multi-line brackets (`{ ... }`, `[ ... ]`), tags, or block comments begin.

### 4. Diagnostics Squiggles / Error Warnings Not Displayed

- **Symptom:** Red/yellow squiggles or error icons do not render under code.
- **Solution:** Ensure `config.diagnosticsEnabled = true`. Note that diagnostics run with a slight debounce (`config.diagnosticsDelayMs`, default 300ms) to preserve typing fluidness. If using custom `diagnosticsProvider`, ensure it returns valid 0-indexed character offsets within `0..text.length`.

### 5. Keyboard Events Intercepted / Soft Keyboard Conflicts

- **Symptom:** Hardware keyboard shortcuts (like Ctrl+Z or Tab) do not trigger when focused.
- **Solution:** Call `state.requestFocus()` when the editor is displayed. CodeForge handles preview key events via Compose `onPreviewKeyEvent`. Make sure outer parent layouts do not consume key events before reaching `CodeEditor`.

### 6. Auto-Completion Popup Clipped on Mobile Screens

- **Symptom:** The caret completion popup extends beyond screen edges on small devices.
- **Solution:** Set `config = config.copy(completionStyle = CompletionStyle.Bar)` when running on small screen form factors. This displays completions in a scrollable mobile-optimized bottom bar instead of a caret popup.

### 7. LSP Diagnostics or Completion Requests Hanging

- **Symptom:** LSP diagnostics or completions fail to populate.
- **Solution:** Ensure the LSP background process streams (`InputStream` and `OutputStream`) are open and unblocked. Always call `lspClient.initialize()` before sending document sync commands (`didOpen` / `didChange`).
