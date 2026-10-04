package com.mrj.fancyai.service.llm

import android.content.Context
import com.mrj.fancyai.R

internal object ImagePrompt {
    fun requestedInstruction(macros: MacroBus, message: String): String? {
        val preferences = macros.context.getSharedPreferences("assistant_protocol", Context.MODE_PRIVATE)
        val requested = preferences.getString("image_triggers", macros.context.getString(R.string.instructions_image_triggers_default)).orEmpty()
            .lineSequence().map(String::trim).filter(String::isNotEmpty)
            .any { message.contains(it, ignoreCase = true) }
        if (!requested) return null
        return macros.text(preferences.getString("requested_image_instruction", macros.context.getString(R.string.instructions_requested_image_default)).orEmpty())
    }

    private val TAGGED_SCENE_BLOCK = Regex(
        """<\s*(?:scene[_\s]+prompt|scene|image[_\s]+prompt)\s*[:\-\u2013\u2014=]?\s*>(.*?)(?:<\s*/\s*(?:scene[_\s]+prompt|scene|image[_\s]+prompt)\s*>|$)""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    private val INLINE_TAGGED_SCENE_BLOCK = Regex(
        """<\s*(?:scene[_\s]+prompt|scene|image[_\s]+prompt)\s*[:\-\u2013\u2014=]\s*(.*?)(?:>|$)""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    private val BRACKET_SCENE_BLOCK = Regex(
        """\[\s*(?:scene[_\s]+prompt|scene|image[_\s]+prompt)\s*[:\-\u2013\u2014=]?\s*](.*?)(?:\[\s*/\s*(?:scene[_\s]+prompt|scene|image[_\s]+prompt)\s*]|$)""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    private val INLINE_BRACKET_SCENE_BLOCK = Regex(
        """\[\s*(?:scene[_\s]+prompt|scene|image[_\s]+prompt)\s*[:\-\u2013\u2014=]\s*(.*?)(?:]|$)""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    private val UNTAGGED_SCENE_BLOCK = Regex(
        """(?:\r?\n|\A)\s*(?:\*{1,2}|_{1,2})?(?:scene[_\s]+prompt|scene\s+description|image[_\s]+prompt|scene)(?:\*{1,2}|_{1,2})?\s*[:\-\u2013\u2014=]\s*(.+)$""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    private val STREAMING_PREFIXES = listOf(
        "<scene_prompt",
        "<scene prompt",
        "<scene",
        "<image_prompt",
        "[scene_prompt",
        "[scene prompt",
        "[scene",
        "scene_prompt:",
        "scene prompt:",
        "**scene_prompt**:",
        "**scene prompt**:",
        "scene_prompt",
        "scene prompt",
    )

    fun split(output: String, imageOnly: Boolean = false): Pair<String, String?> {
        val tagged = TAGGED_SCENE_BLOCK.find(output)
        if (tagged != null) {
            val prompt = cleanPrompt(tagged.groupValues[1])
            if (imageOnly) return "" to prompt
            val text = output.substring(0, tagged.range.first).trim()
            return text to prompt
        }

        val inlineTagged = INLINE_TAGGED_SCENE_BLOCK.find(output)
        if (inlineTagged != null) {
            val prompt = cleanPrompt(inlineTagged.groupValues[1])
            if (imageOnly) return "" to prompt
            val text = output.substring(0, inlineTagged.range.first).trim()
            return text to prompt
        }

        val bracket = BRACKET_SCENE_BLOCK.find(output)
        if (bracket != null) {
            val prompt = cleanPrompt(bracket.groupValues[1])
            if (imageOnly) return "" to prompt
            val text = output.substring(0, bracket.range.first).trim()
            return text to prompt
        }

        val inlineBracket = INLINE_BRACKET_SCENE_BLOCK.find(output)
        if (inlineBracket != null) {
            val prompt = cleanPrompt(inlineBracket.groupValues[1])
            if (imageOnly) return "" to prompt
            val text = output.substring(0, inlineBracket.range.first).trim()
            return text to prompt
        }

        val untagged = UNTAGGED_SCENE_BLOCK.find(output)
        if (untagged != null) {
            val prompt = cleanPrompt(untagged.groupValues[1])
            if (imageOnly) return "" to prompt
            val text = output.substring(0, untagged.range.first).trim()
            return text to prompt
        }

        if (imageOnly) return "" to cleanPrompt(output).takeIf(String::isNotEmpty)

        val trimmedForStream = stripPartialStream(output)
        return trimmedForStream to null
    }

    private fun cleanPrompt(raw: String): String =
        raw.replace(Regex("""<\s*/?\s*(?:scene[_\s]+prompt|scene|image[_\s]+prompt)\s*>|\[\s*/?\s*(?:scene[_\s]+prompt|scene|image[_\s]+prompt)\s*]""", RegexOption.IGNORE_CASE), "")
            .trimEnd { (it == '*') || (it == '_') || (it == '>') || (it == ']') || (it == ')') || it.isWhitespace() }
            .trimStart { (it == '*') || (it == '_') || (it == '<') || (it == '[') || (it == '(') || (it == ':') || it.isWhitespace() }
            .trim()

    private fun stripPartialStream(output: String): String {
        for (delimiter in listOf('<', '[', '\n')) {
            val start = output.lastIndexOf(delimiter)
            if (start >= 0) {
                val candidate = output.substring(start).trimStart()
                if (STREAMING_PREFIXES.any { it.startsWith(candidate, ignoreCase = true) }) {
                    return output.substring(0, start).trimEnd()
                }
            }
        }
        return output
    }
}
