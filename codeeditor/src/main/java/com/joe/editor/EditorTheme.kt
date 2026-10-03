package com.joe.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle

class EditorTheme(
    val name: String,
    val dark: Boolean,
    val background: Color,
    val foreground: Color,
    val gutterBackground: Color,
    val gutterText: Color,
    val gutterActiveText: Color,
    val currentLine: Color,
    val selection: Color,
    val cursor: Color,
    val bracketMatch: Color,
    val searchMatch: Color,
    val searchActive: Color,
    val error: Color,
    val warning: Color,
    val info: Color,
    val popupBackground: Color,
    val popupSelected: Color,
    val tokens: Map<TokenType, Color>,
    val italicComments: Boolean = true,
) {
    private val styles: Map<TokenType, SpanStyle> = tokens.mapValues { (type, color) ->
        val italic = italicComments && (type == TokenType.Comment || type == TokenType.DocComment)
        SpanStyle(color = color, fontStyle = if (italic) FontStyle.Italic else null)
    }

    fun spanStyle(type: TokenType): SpanStyle? = styles[type]
    fun colorOf(type: TokenType): Color = tokens[type] ?: foreground
    fun severityColor(s: Severity): Color = when (s) {
        Severity.Error -> error
        Severity.Warning -> warning
        Severity.Info -> info
    }
}

object EditorThemes {
    private fun c(rgb: Long) = Color(0xFF000000L or rgb)

    private fun palette(
        keyword: Long, type: Long, func: Long, builtin: Long, literal: Long, str: Long, num: Long,
        comment: Long, doc: Long, meta: Long, operator: Long, punct: Long, property: Long,
        variable: Long, tag: Long, attr: Long, escape: Long,
    ): Map<TokenType, Color> = mapOf(
        TokenType.Keyword to c(keyword), TokenType.Type to c(type), TokenType.Func to c(func),
        TokenType.Builtin to c(builtin), TokenType.Literal to c(literal), TokenType.Str to c(str),
        TokenType.Num to c(num), TokenType.Comment to c(comment), TokenType.DocComment to c(doc),
        TokenType.Meta to c(meta), TokenType.Operator to c(operator), TokenType.Punct to c(punct),
        TokenType.Property to c(property), TokenType.Variable to c(variable), TokenType.Tag to c(tag),
        TokenType.Attr to c(attr), TokenType.Escape to c(escape),
    )

    val Darcula = EditorTheme(
        "Darcula", true, c(0x2B2B2B), c(0xA9B7C6), c(0x313335), c(0x606366), c(0xA4A3A3),
        Color(0x22FFFFFF), Color(0x66214283), c(0xBBBBBB), Color(0x663B514D), Color(0x6632593D), Color(0xAA7A8F2B),
        c(0xFF5555), c(0xE5B03B), c(0x6897BB), c(0x3C3F41), c(0x4B6EAF),
        palette(0xCC7832, 0x6FAFBD, 0xFFC66D, 0x8888C6, 0xCC7832, 0x6A8759, 0x6897BB, 0x808080, 0x629755,
            0xBBB529, 0xA9B7C6, 0xA9B7C6, 0x9876AA, 0x9876AA, 0xE8BF6A, 0xBABABA, 0xCC7832),
    )

    val OneDark = EditorTheme(
        "One Dark", true, c(0x282C34), c(0xABB2BF), c(0x21252B), c(0x4B5263), c(0xABB2BF),
        Color(0x1AFFFFFF), Color(0x663E4451), c(0x528BFF), Color(0x66528BFF), Color(0x664D78CC), Color(0xAAE5C07B),
        c(0xE06C75), c(0xE5C07B), c(0x61AFEF), c(0x21252B), c(0x2C313A),
        palette(0xC678DD, 0xE5C07B, 0x61AFEF, 0x56B6C2, 0xD19A66, 0x98C379, 0xD19A66, 0x5C6370, 0x7F848E,
            0xE5C07B, 0x56B6C2, 0xABB2BF, 0xE06C75, 0xE06C75, 0xE06C75, 0xD19A66, 0x56B6C2),
    )

    val Monokai = EditorTheme(
        "Monokai", true, c(0x272822), c(0xF8F8F2), c(0x2D2E27), c(0x75715E), c(0xF8F8F2),
        Color(0x22FFFFFF), Color(0x66494930), c(0xF8F8F0), Color(0x66666644), Color(0x66665500), Color(0xAAFFE792),
        c(0xF92672), c(0xFD971F), c(0x66D9EF), c(0x1E1F1C), c(0x49483E),
        palette(0xF92672, 0x66D9EF, 0xA6E22E, 0x66D9EF, 0xAE81FF, 0xE6DB74, 0xAE81FF, 0x75715E, 0x908B6B,
            0xA6E22E, 0xF92672, 0xF8F8F2, 0x66D9EF, 0xFD971F, 0xF92672, 0xA6E22E, 0xAE81FF),
    )

    val Dracula = EditorTheme(
        "Dracula", true, c(0x282A36), c(0xF8F8F2), c(0x21222C), c(0x6272A4), c(0xF8F8F2),
        Color(0x1AFFFFFF), Color(0x6644475A), c(0xF8F8F2), Color(0x6644475A), Color(0x66FFB86C), Color(0xAAFFB86C),
        c(0xFF5555), c(0xFFB86C), c(0x8BE9FD), c(0x21222C), c(0x44475A),
        palette(0xFF79C6, 0x8BE9FD, 0x50FA7B, 0x8BE9FD, 0xBD93F9, 0xF1FA8C, 0xBD93F9, 0x6272A4, 0x7B88B8,
            0x50FA7B, 0xFF79C6, 0xF8F8F2, 0x8BE9FD, 0xFFB86C, 0xFF79C6, 0x50FA7B, 0xFF79C6),
    )

    val GitHubLight = EditorTheme(
        "GitHub Light", false, c(0xFFFFFF), c(0x24292F), c(0xF6F8FA), c(0x8C959F), c(0x24292F),
        Color(0x14000000), Color(0x55B6E3FF), c(0x24292F), Color(0x55FFD33D), Color(0x55FFDF5D), Color(0xAAF2A93B),
        c(0xCF222E), c(0x9A6700), c(0x0969DA), c(0xFFFFFF), c(0xDDF4FF),
        palette(0xCF222E, 0x953800, 0x8250DF, 0x0550AE, 0x0550AE, 0x0A3069, 0x0550AE, 0x6E7781, 0x57606A,
            0x8250DF, 0x24292F, 0x24292F, 0x0550AE, 0x953800, 0x116329, 0x0550AE, 0x0550AE),
    )

    val all = listOf(Darcula, OneDark, Monokai, Dracula, GitHubLight)
}
