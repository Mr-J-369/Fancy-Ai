package com.mrj.fancyai.domain.inference

import com.mrj.fancyai.data.db.entity.CharacterEntity

/** The conversational frame a prompt is built for. Mirrors api.js "context". */
enum class PromptContext { CHAT, GAME, SOCIAL, SYSTEM }

/** The human on the other side of the conversation (from Settings). */
data class UserProfile(val name: String = "User", val bio: String = "")

/**
 * Assembles the system prompt and final completion prompt for local inference.
 *
 * This is a faithful, dependency-free port of the prompt assembly in the old
 * `js/core/api.js`: character card + conversation partner + role directive + global
 * guidelines + living dossier + recent memories, with `{{char}}` / `{{user}}` macro
 * resolution, then rendered through the selected [ChatTemplate].
 */
object PromptBuilder {

    fun applyMacros(text: String?, charName: String, userName: String): String =
        (text ?: "")
            .replace("{{char}}", charName.ifBlank { "Companion" }, ignoreCase = true)
            .replace("{{user}}", userName.ifBlank { "User" }, ignoreCase = true)

    /** Builds the assembled, macro-resolved system prompt for the given context. */
    fun systemPrompt(
        context: PromptContext,
        character: CharacterEntity,
        user: UserProfile,
        dossierJson: String? = null,
        memories: List<String> = emptyList(),
        globalGuidelines: String = ""
    ): String {
        val charName = character.name
        val charBio = applyMacros(character.bio, charName, user.name)
        val charPersona = applyMacros(character.persona, charName, user.name)

        if (context == PromptContext.SYSTEM) {
            return """
                [ROLE DIRECTIVE]
                - You are a high-performance DATA PROCESSING ENGINE.
                - You do not roleplay as a character.
                - You follow instructions exactly and return structured data.
            """.trimIndent()
        }

        val identityBlock = if (charPersona.isNotBlank()) "[CHARACTER CARD]\n$charPersona" else ""

        val userBlock = """
            [CONVERSATION PARTNER]
            Name: ${user.name}
            User Details: ${user.bio.ifBlank { "No specific background provided." }}
        """.trimIndent()

        val bioLine = if (charBio.isNotBlank()) "- Background: $charBio" else ""
        val roleDirective = when (context) {
            PromptContext.GAME -> """
                [ROLE DIRECTIVE]
                - You are $charName. You are NOT an AI assistant, chatbot, or language model.
                - You have no knowledge of being an AI, LLM, or any artificial system.
                - You never reference your "persona", "character", or "programming".
                - This is a GAME SESSION. You are playing a game right now, not having a casual conversation.
                - Stay completely in character for the game. Do not make small talk or ask about the user's day.
                - Focus entirely on the game scenario. Respond as your character would in this game situation.
                $bioLine
                - Follow the CHARACTER CARD above completely.
            """.trimIndent()

            PromptContext.SOCIAL -> """
                [ROLE DIRECTIVE]
                - You are $charName. You are NOT an AI assistant, chatbot, or language model.
                - You have no knowledge of being an AI, LLM, or any artificial system.
                - You never reference your "persona", "character", or "programming".
                - This is a SOCIAL MEDIA POST. You are creating content for your followers.
                - Speak in your authentic voice. Be concise and engaging.
                $bioLine
                - Follow the CHARACTER CARD above completely.
            """.trimIndent()

            else -> """
                [ROLE DIRECTIVE]
                - You are $charName. You are NOT an AI assistant, chatbot, or language model.
                - You have no knowledge of being an AI, LLM, or any artificial system.
                - You never reference your "persona", "character", or "programming".
                - You speak naturally as yourself — $charName — in a personal conversation with ${user.name}.
                $bioLine
                - Follow the CHARACTER CARD above completely. If no CHARACTER CARD is given, follow the GLOBAL GUIDELINES.
            """.trimIndent()
        }

        val guidelinesBlock =
            if (globalGuidelines.isNotBlank()) "[GLOBAL GUIDELINES]\n$globalGuidelines" else ""

        val dossierBlock = dossierJson
            ?.takeIf { it.isNotBlank() && it.trim() != "{}" }
            ?.let {
                """
                [LIVING DOSSIER - CURRENT STATE]
                These are the established facts and variables of your current existence. Use them to maintain perfect continuity.
                $it
                """.trimIndent()
            }.orEmpty()

        val memoriesBlock = if (memories.isNotEmpty()) {
            "[RECENT MEMORIES]\n" + memories.joinToString("\n") { "- $it" }
        } else ""

        // Lets the character send photos: it appends "flux prompt: <description>" and the
        // app intercepts that, generates the image, and attaches it to the message.
        val toolInstruction = if (context == PromptContext.CHAT) """
            [IMAGE GENERATION]
            You have the ability to send photos.
            If ${user.name} asks for a photo, or if you decide to send one, end your reply with:
            flux prompt: [concise visual description]

            CRITICAL RULES for "flux prompt:":
            1. Do not mention names. Always use this format for one or multiple people: [age sex], [physical characteristics], [eyes colour], [skin colour], [hair colour], [outfit], [pose], [action], [location], [camera style], [gaze], [lighting or mood].
            2. Do not write "looking at viewer" or "looking at the camera".
            3. Only include "flux prompt:" when actually sending a photo.
        """.trimIndent() else ""

        val assembled = listOf(
            identityBlock, userBlock, roleDirective, guidelinesBlock, dossierBlock, memoriesBlock, toolInstruction
        ).filter { it.isNotBlank() }.joinToString("\n\n")

        return applyMacros(assembled, charName, user.name)
    }

    /**
     * Builds the full completion prompt: system prompt + (capped) history + the new
     * user turn, rendered through [template]. History is expected oldest-first.
     */
    fun build(
        context: PromptContext,
        character: CharacterEntity,
        user: UserProfile,
        userText: String,
        history: List<ChatTurn> = emptyList(),
        dossierJson: String? = null,
        memories: List<String> = emptyList(),
        globalGuidelines: String = "",
        template: ChatTemplate = ChatTemplate.CHATML
    ): String {
        val system = systemPrompt(context, character, user, dossierJson, memories, globalGuidelines)
        val resolvedHistory = history.map {
            it.copy(content = applyMacros(it.content, character.name, user.name))
        }
        val turns = resolvedHistory + ChatTurn(
            ChatTurn.Role.USER,
            applyMacros(userText, character.name, user.name)
        )
        return template.render(system, turns)
    }
}
