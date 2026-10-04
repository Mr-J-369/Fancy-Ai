package com.mrj.fancyai.service.llm

import com.mrj.fancyai.ui.settings.SystemPrompt

/** Cloud turn assembly. Collects all optional context; CloudContextPolicy fits it to the known window. */
internal object CloudAssembly {
    fun systemInstruction(macros: MacroBus, instructions: List<String>, imageInstruction: String? = null): String =
        (listOf(com.mrj.fancyai.ui.settings.readAssistantInstruction(macros.context)) + (imageInstruction?.let(::listOf) ?: instructions))
            .asSequence().filter(String::isNotBlank).joinToString("\n\n", transform = macros::text)

    fun compile(
        macros: MacroBus,
        savedPrompt: SystemPrompt? = null,
        message: String,
        instructions: List<String> = emptyList(),
        context: List<String> = emptyList(),
        optionalContext: List<String> = emptyList(),
        imagePath: String? = null,
        includeIdentity: Boolean = true,
        triggerMessage: String = message,
    ): AssistantTurn {
        val imageInstruction = ImagePrompt.requestedInstruction(macros, triggerMessage)
        return if (imageInstruction != null) {
            AssistantTurn(systemInstruction(macros, emptyList(), imageInstruction), LlmInput(text = triggerMessage), imageRequested = true)
        } else {
            val system = systemInstruction(macros, listOf(savedPrompt?.instruction.orEmpty()) + instructions)
            val facts = buildList {
                if (includeIdentity) {
                    addAll(AssistantProtocol.identityContext(macros))
                }
                addAll(context.asSequence().filter(String::isNotBlank).map(macros::text))
            }
            val action = message.takeIf(String::isNotBlank)?.let { "Current action:\n${macros.text(it)}" }.orEmpty()
            val optional = optionalContext.asSequence().filter(String::isNotBlank).map(macros::text).toList()
            AssistantTurn(
                systemInstruction = system,
                input = LlmInput(
                    text = if (facts.isEmpty()) action else "Current context:\n${facts.joinToString("\n\n")}\n\n$action",
                    imagePath = imagePath,
                    context = optional,
                ),
            )
        }
    }
}
