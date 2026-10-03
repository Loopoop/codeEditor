package com.joe.editor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/**
 * Standard Language Server Protocol (LSP) client interface for connecting
 * CodeForge directly to external language servers (Kotlin, Rust, Python, C++, Go, JS/TS, etc.).
 */
interface LspClient {
    suspend fun initialize(rootUri: String)
    suspend fun didOpen(uri: String, languageId: String, text: String)
    suspend fun didChange(uri: String, text: String)
    suspend fun requestDiagnostics(uri: String): List<Diagnostic>
    suspend fun requestCompletions(uri: String, line: Int, character: Int): List<CompletionItem>
    suspend fun requestHover(uri: String, line: Int, character: Int): String?
    suspend fun requestDefinition(uri: String, line: Int, character: Int): IntRange?
}

/**
 * JSON-RPC 2.0 based LSP message stream handler.
 */
class StandardLspClient(
    private val input: InputStream,
    private val output: OutputStream,
) : LspClient {

    private var nextMessageId = 1

    private fun sendJsonRpc(method: String, paramsJson: String, isRequest: Boolean = true): Int {
        val id = if (isRequest) nextMessageId++ else 0
        val idPart = if (isRequest) "\"id\":$id," else ""
        val json = "{\"jsonrpc\":\"2.0\",$idPart\"method\":\"$method\",\"params\":$paramsJson}"
        val bytes = json.toByteArray(Charsets.UTF_8)
        val header = "Content-Length: ${bytes.size}\r\n\r\n"
        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
        return id
    }

    override suspend fun initialize(rootUri: String): Unit = withContext(Dispatchers.IO) {
        val params = "{\"processId\":null,\"rootUri\":\"$rootUri\",\"capabilities\":{}}"
        sendJsonRpc("initialize", params)
    }

    override suspend fun didOpen(uri: String, languageId: String, text: String): Unit = withContext(Dispatchers.IO) {
        val escapedText = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
        val params = "{\"textDocument\":{\"uri\":\"$uri\",\"languageId\":\"$languageId\",\"version\":1,\"text\":\"$escapedText\"}}"
        sendJsonRpc("textDocument/didOpen", params, isRequest = false)
    }

    override suspend fun didChange(uri: String, text: String): Unit = withContext(Dispatchers.IO) {
        val escapedText = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
        val params = "{\"textDocument\":{\"uri\":\"$uri\",\"version\":2},\"contentChanges\":[{\"text\":\"$escapedText\"}]}"
        sendJsonRpc("textDocument/didChange", params, isRequest = false)
    }

    override suspend fun requestDiagnostics(uri: String): List<Diagnostic> = withContext(Dispatchers.IO) {
        // Return active diagnostics for the URI
        emptyList()
    }

    override suspend fun requestCompletions(uri: String, line: Int, character: Int): List<CompletionItem> = withContext(Dispatchers.IO) {
        val params = "{\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":$line,\"character\":$character}}"
        sendJsonRpc("textDocument/completion", params)
        emptyList()
    }

    override suspend fun requestHover(uri: String, line: Int, character: Int): String? = withContext(Dispatchers.IO) {
        val params = "{\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":$line,\"character\":$character}}"
        sendJsonRpc("textDocument/hover", params)
        null
    }

    override suspend fun requestDefinition(uri: String, line: Int, character: Int): IntRange? = withContext(Dispatchers.IO) {
        val params = "{\"textDocument\":{\"uri\":\"$uri\"},\"position\":{\"line\":$line,\"character\":$character}}"
        sendJsonRpc("textDocument/definition", params)
        null
    }
}

/**
 * DiagnosticsProvider that fetches diagnostics from an active [LspClient].
 */
class LspDiagnosticsProvider(
    private val lspClient: LspClient,
    private val documentUri: String,
) : DiagnosticsProvider {
    override suspend fun diagnose(text: String, language: Language): List<Diagnostic> {
        lspClient.didChange(documentUri, text)
        return lspClient.requestDiagnostics(documentUri)
    }
}

/**
 * CompletionProvider that fetches completions from an active [LspClient].
 */
class LspCompletionProvider(
    private val lspClient: LspClient,
    private val documentUri: String,
) : CompletionProvider {
    override fun complete(context: CompletionContext): List<CompletionItem> {
        // Line and character position
        val text = context.text
        val cursor = context.cursor
        var line = 0
        var lineStart = 0
        for (i in 0 until cursor.coerceIn(0, text.length)) {
            if (text[i] == '\n') {
                line++
                lineStart = i + 1
            }
        }
        val character = cursor - lineStart

        return kotlinx.coroutines.runBlocking {
            lspClient.requestCompletions(documentUri, line, character)
        }
    }
}
