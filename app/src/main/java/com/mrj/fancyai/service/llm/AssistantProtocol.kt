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
            LlmRuntime.CLOUD -> CloudAssembly.compile(macros, savedPrompt, message, instructions, context, optionalContext, imagePath, includeIdentity, triggerMessage)
        }
    }

    fun identityContext(macros: MacroBus): List<String> = buildList {
        val charName = macros.text("{{char}}").trim()
        val charLines = buildList {
            if (charName.isNotEmpty()) add("Name: $charName")
            macros.text("{{char.appearance}}").trim().takeIf(String::isNotEmpty)?.let { add("Appearance: $it") }
            macros.text("{{char.personality}}").trim().takeIf(String::isNotEmpty)?.let { add("Personality: $it") }
            macros.text("{{char.description}}").trim().takeIf(String::isNotEmpty)?.let { add("Description: $it") }
        }
        if (charLines.isNotEmpty()) {
            val header = if (charName.isNotEmpty()) "Character (you, assistant = $charName):" else "Character (you, assistant):"
            add("$header\n${charLines.joinToString("\n")}")
        }
        val userName = macros.text("{{user.name}}").trim().ifEmpty { "User" }
        val userLines = buildList {
            add("Name: $userName")
            macros.text("{{user.description}}").trim().takeIf(String::isNotEmpty)?.let { add("Description: $it") }
            macros.text("{{user.appearance}}").trim().takeIf(String::isNotEmpty)?.let { add("Appearance: $it") }
        }
        add("User (talking to you, user = $userName):\n${userLines.joinToString("\n")}")
    }

    fun relationshipContext(macros: MacroBus): String? =
        macros.relationshipsBlock().takeIf(String::isNotBlank)
}
