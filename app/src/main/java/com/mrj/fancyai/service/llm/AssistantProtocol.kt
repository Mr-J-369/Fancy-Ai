package com.mrj.fancyai.service.llm

import com.mrj.fancyai.ui.settings.SystemPrompt

/** The complete, engine-independent contents of one assistant turn. */
internal data class AssistantTurn(
    val systemInstruction: String,
    val input: LlmInput,
    val imageRequested: Boolean = false,
)

/** Runtime dispatch for turn assembly; each runtime owns its budget and input shape. */
internal object AssistantProtocol {
    fun systemInstruction(
        runtime: LlmRuntime,
        macros: MacroBus,
        instructions: List<String>,
        imageInstruction: String? = null,
    ): String = when (runtime) {
        LlmRuntime.LLAMA -> LlamaAssembly.systemInstruction(macros, instructions, imageInstruction)
        LlmRuntime.LITERT -> LiteRtAssembly.systemInstruction(macros, instructions, imageInstruction)
        LlmRuntime.MNN -> MnnAssembly.systemInstruction(macros, instructions, imageInstruction)
        LlmRuntime.CLOUD -> CloudAssembly.systemInstruction(macros, instructions, imageInstruction)
    }

    fun compile(
        runtime: LlmRuntime,
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
        return when (runtime) {
            LlmRuntime.LLAMA -> LlamaAssembly.compile(macros, savedPrompt, message, instructions, context, optionalContext, imagePath, triggerMessage)
            LlmRuntime.LITERT -> LiteRtAssembly.compile(macros, savedPrompt, message, instructions, context, optionalContext, imagePath, triggerMessage)
            LlmRuntime.MNN -> MnnAssembly.compile(macros, savedPrompt, message, instructions, context, optionalContext, imagePath, triggerMessage)
            LlmRuntime.CLOUD -> CloudAssembly.compile(macros, savedPrompt, message, instructions, context, optionalContext, imagePath, includeIdentity, triggerMessage)
        }
    }

    internal const val CHARACTER_CONTEXT = "Character:\nName: {{char}}\nAppearance: {{char.appearance}}\nPersonality: {{char.personality}}\nDescription: {{char.description}}"
    internal const val USER_CONTEXT = "User:\nName: {{user.name}}\nDescription: {{user.description}}\nAppearance: {{user.appearance}}"

    fun identityContext(macros: MacroBus): List<String> = listOf(
        macros.text(CHARACTER_CONTEXT),
        macros.text(USER_CONTEXT),
    )
}
