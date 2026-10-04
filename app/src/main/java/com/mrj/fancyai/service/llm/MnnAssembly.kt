package com.mrj.fancyai.service.llm

import com.mrj.fancyai.ui.settings.SystemPrompt

/** MNN turn assembly. Sends messages as-is; MNN handles formatting internally. */
internal object MnnAssembly {
    fun systemInstruction(macros: MacroBus, instructions: List<String>, imageInstruction: String? = null): String {
        return (listOf(com.mrj.fancyai.ui.settings.readAssistantInstruction(macros.context)) + (imageInstruction?.let(::listOf) ?: instructions))
            .asSequence().filter(String::isNotBlank).joinToString("\n\n", transform = macros::text)
    }

    fun compile(
        macros: MacroBus,
        savedPrompt: SystemPrompt? = null,
        message: String,
        instructions: List<String> = emptyList(),
        context: List<String> = emptyList(),
        optionalContext: List<String> = emptyList(),
        imagePath: String? = null,
        triggerMessage: String = message,
    ): AssistantTurn {
        val imageInstruction = ImagePrompt.requestedInstruction(macros, triggerMessage)
        return if (imageInstruction != null) {
            AssistantTurn(systemInstruction(macros, emptyList(), imageInstruction), LlmInput(text = triggerMessage), imageRequested = true)
        } else {
            val system = systemInstruction(macros, listOf(savedPrompt?.instruction.orEmpty()) + instructions + context + optionalContext)
            AssistantTurn(
                systemInstruction = system,
                input = LlmInput(text = macros.text(message), imagePath = imagePath),
            )
        }
    }
}
