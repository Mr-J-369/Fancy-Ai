package com.mrj.fancyai.service.llm

import com.mrj.fancyai.ui.settings.SystemPrompt

/** LiteRT turn assembly. Sends messages as-is; LiteRT handles formatting internally. */
internal object LiteRtAssembly {
    fun systemInstruction(macros: MacroBus, instructions: List<String>, imageInstruction: String? = null): String {
        val base = (listOf(com.mrj.fancyai.ui.settings.readAssistantInstruction(macros.context)) + (imageInstruction?.let(::listOf) ?: instructions))
            .asSequence().filter(String::isNotBlank).joinToString("\n\n", transform = macros::text)
        if (base.contains("scene_prompt", ignoreCase = true) && (imageInstruction == null)) {
            return "$base\n\nCRITICAL INSTRUCTION: You must ALWAYS conclude your reply with a visual scene prompt for image generation, enclosed in <scene_prompt>...</scene_prompt>. Never finish your reply without writing the <scene_prompt> block.\n\nExample reply format:\nDialogue and action here.\n\n<scene_prompt>{{char.appearance}}, [outfit], [location], [action], [POV angle], [camera effects]</scene_prompt>"
        }
        return base
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
