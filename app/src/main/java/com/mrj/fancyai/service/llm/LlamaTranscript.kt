package com.mrj.fancyai.service.llm

import com.mrj.fancyai.engine.LocalLlmMessage

/** llama.cpp conversation assembly. Owns role placement and per-turn rendering for llama. */
object LlamaTranscript {
    fun conversation(config: LlmSessionConfig): List<LocalLlmMessage> = buildList {
        config.systemInstruction.takeIf(String::isNotBlank)?.let {
            add(LocalLlmMessage("system", it))
        }
        config.openingMessage.takeIf(String::isNotBlank)?.let {
            add(LocalLlmMessage("assistant", it))
        }
        config.history.forEach { exchange ->
            add(LocalLlmMessage("user", exchange.input.textWithContext()))
            exchange.assistant.takeIf(String::isNotBlank)?.let {
                add(LocalLlmMessage("assistant", it))
            }
        }
    }
}
