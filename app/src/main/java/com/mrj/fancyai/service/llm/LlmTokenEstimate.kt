package com.mrj.fancyai.service.llm

import kotlin.math.ceil

/** Single owner of the tokenizer-agnostic length heuristic used before native tokenization. */
object LlmTokenEstimate {
    fun estimateTokens(text: String): Int {
        if (text.isBlank()) return 0
        val asciiEstimate = ceil(text.length / 3.0).toInt()
        val utf8Estimate = ceil(text.toByteArray(Charsets.UTF_8).size / 2.0).toInt()
        return maxOf(asciiEstimate, if (text.any { it.code > 0x7f }) utf8Estimate else 0)
    }
}
