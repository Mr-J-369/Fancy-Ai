package com.mrj.fancyai.service.llm

import com.mrj.fancyai.ui.settings.SystemPrompt

/** llama.cpp turn assembly. Sends messages as-is; llama.cpp handles formatting via Jinja. */
internal object LlamaAssembly {
    fun systemInstruction(macros: MacroBus, instructions: List<String>, imageInstruction: String? = null): String {
        return (listOf(com.mrj.fancyai.ui.settings.readAssistantInstruction(macros.context)) + instructions + listOfNotNull(imageInstruction))
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
            val system = systemInstruction(macros, AssistantProtocol.identityContext(macros), imageInstruction)
            AssistantTurn(systemInstruction = system, input = LlmInput(text = macros.text(triggerMessage), imagePath = imagePath), imageRequested = true)
        } else {
            val system = systemInstruction(macros, listOf(savedPrompt?.instruction.orEmpty()) + instructions + context + optionalContext)
            AssistantTurn(
                systemInstruction = system,
                input = LlmInput(text = macros.text(message), imagePath = imagePath),
            )
        }
    }
}
