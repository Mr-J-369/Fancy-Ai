package com.mrj.fancyai.ui.components

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.TextStyle

/**
 * Lightweight inline-markdown renderer used across the app for AI-generated text.
 * Supports ***bold-italic***, **bold**, __bold__, *italic*, _italic_, ~~strike~~ and
 * `code`. Faithful to the inline subset of the old OS.formatMarkdown.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current
) {
    val annotated = remember(text) { parseInlineMarkdown(text) }
    Text(text = annotated, modifier = modifier, color = color, style = style)
}

private val MARKDOWN_REGEX = Regex(
    """\*\*\*(.+?)\*\*\*|\*\*(.+?)\*\*|__(.+?)__|\*(.+?)\*|(?<![A-Za-z0-9])_(.+?)_(?![A-Za-z0-9])|~~(.+?)~~|`(.+?)`"""
)

fun parseInlineMarkdown(input: String): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    for (match in MARKDOWN_REGEX.findAll(input)) {
        if (match.range.first > cursor) {
            append(input.substring(cursor, match.range.first))
        }
        val g = match.groups
        when {
            g[1] != null -> withSpan(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic), g[1]!!.value)
            g[2] != null -> withSpan(SpanStyle(fontWeight = FontWeight.Bold), g[2]!!.value)
            g[3] != null -> withSpan(SpanStyle(fontWeight = FontWeight.Bold), g[3]!!.value)
            g[4] != null -> withSpan(SpanStyle(fontStyle = FontStyle.Italic), g[4]!!.value)
            g[5] != null -> withSpan(SpanStyle(fontStyle = FontStyle.Italic), g[5]!!.value)
            g[6] != null -> withSpan(SpanStyle(textDecoration = TextDecoration.LineThrough), g[6]!!.value)
            g[7] != null -> withSpan(
                SpanStyle(fontFamily = FontFamily.Monospace, background = Color.White.copy(alpha = 0.08f)),
                g[7]!!.value
            )
        }
        cursor = match.range.last + 1
    }
    if (cursor < input.length) append(input.substring(cursor))
}

private fun AnnotatedString.Builder.withSpan(span: SpanStyle, content: String) {
    pushStyle(span)
    append(content)
    pop()
}
