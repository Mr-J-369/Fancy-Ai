package com.mrj.fancyai.service.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Reads generated text fields without rejecting unstructured prose. */
internal object SocialPostJson {
    private val json = Json { isLenient = true; allowTrailingComma = true }
    private val escapes = mapOf(
        '"' to '"', '\'' to '\'', '\\' to '\\', '/' to '/',
        'n' to '\n', 'r' to '\r', 't' to '\t', 'b' to '\b', 'f' to '\u000C',
    )
    private val fieldLabel = Regex(
        "(?im)^[ \\t]*(?:#{1,6}[ \\t]+|[-*>][ \\t]+)?(?:\\*\\*)?" +
            "(r/|r/name|r|subreddit|title|post|body|#|hashtag|caption|reply|message|comment|text|content|name|handle|personality|description|bio|about|scene|scenario|first_message|first message|greeting|appearance|brief|lyrics|image_prompt|age|location|occupation|genre|style|track|song)" +
            "(?:\\*\\*)?[ \\t]*:[ \\t]*(?:\\*\\*)?[ \\t]*",
    )

    fun parse(response: String): JsonObject? {
        val text = ImagePrompt.split(response).first
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var quoted = false
        var escaped = false
        for (index in start until text.length) {
            val char = text[index]
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{' -> depth++
                '}' -> if (--depth == 0) {
                    return runCatching { json.parseToJsonElement(text.substring(start, index + 1)) as? JsonObject }.getOrNull()
                }
            }
        }
        return null
    }

    /** Extract available fields even when generation ends before the JSON is complete. */
    fun field(response: String, name: String, parsed: JsonObject? = parse(response)): String? {
        parsed?.entries?.firstOrNull { (key) -> key.equals(name, ignoreCase = true) }?.let { (_, entryValue) ->
            return (entryValue as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        }
        val text = ImagePrompt.split(response).first.trimStart()
        val match = Regex(
            "(?i)[{,]\\s*(?:[\"']" + Regex.escape(name) + "[\"']|" +
                Regex.escape(name) + ")\\s*:\\s*([\"']?)",
        ).find(text)
        if (match == null) {
            val reply = text.trim()
            val fence = Regex("(?i)^(`{3,}|~{3,})[ \\t]*(?:text|plaintext|markdown)?[ \\t]*\\n([\\s\\S]*?)\\n\\1$")
                .matchEntire(reply)
            val visible = fence?.groupValues?.get(2) ?: reply
            val codeBlocks = Regex("(?m)^(`{3,}|~{3,})[^\\n]*\\n[\\s\\S]*?^\\1[ \\t]*$")
                .findAll(visible).map { it.range }.toList()
            val labels = fieldLabel.findAll(visible)
                .filter { label -> codeBlocks.none { label.range.first in it } }.toList()
            val index = labels.indexOfFirst { it.groupValues[1].equals(name, ignoreCase = true) }
            if (index < 0) return null
            val start = labels[index].range.last + 1
            val end = labels.getOrNull(index + 1)?.range?.first ?: visible.length
            return visible.substring(start, end).trim()
        }
        val quote = match.groupValues[1].singleOrNull()
            ?: return text.substring(match.range.last + 1).substringBefore(',').substringBefore('}')
            .trim().takeIf { it.isNotEmpty() && (it !in setOf("null", "true", "false")) && (it.toDoubleOrNull() == null) }
        val result = StringBuilder()
        var index = match.range.last + 1
        while (index < text.length) {
            val char = text[index++]
            if (char == quote) return result.toString()
            if ((char != '\\') || (index == text.length)) {
                result.append(char)
                continue
            }
            val escaped = text[index++]
            val decoded = escapes[escaped]
            when {
                decoded != null -> result.append(decoded)
                escaped == 'u' -> {
                    val code = if ((index + 4) <= text.length) text.substring(index, index + 4).toIntOrNull(16) else null
                    if (code != null) {
                        result.append(code.toChar())
                        index += 4
                    } else {
                        result.append("\\u")
                    }
                }
                else -> result.append('\\').append(escaped)
            }
        }
        return result.toString()
    }

    /** Formatting must never discard a model's prose. */
    fun text(response: String, name: String, parsed: JsonObject? = parse(response)): String {
        field(response, name, parsed)?.takeIf(String::isNotBlank)?.let { return it }
        val alternatives = when (name) {
            "post", "caption" -> listOf("post", "body", "caption", "text", "content")
            "reply", "message", "comment" -> listOf("reply", "message", "comment", "text", "content")
            "description" -> listOf("description", "bio", "about")
            "brief" -> listOf("brief", "prompt", "description", "style")
            else -> emptyList()
        }
        for (alternative in alternatives) {
            if (alternative == name) continue
            field(response, alternative, parsed)?.takeIf(String::isNotBlank)?.let { return it }
        }
        return ImagePrompt.split(response).first.trim()
    }
}
