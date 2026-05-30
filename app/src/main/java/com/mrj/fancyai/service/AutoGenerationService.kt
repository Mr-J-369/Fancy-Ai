package com.mrj.fancyai.service

import android.util.Log
import com.mrj.fancyai.data.repository.SettingsRepository
import com.mrj.fancyai.domain.inference.ChatTemplate
import com.mrj.fancyai.domain.inference.ChatTurn
import com.mrj.fancyai.domain.inference.LlamaEngine
import kotlinx.coroutines.flow.toList

/**
 * Background AI tasks that run "off-stage" from the chat UI — social post generation,
 * post replies and Living Dossier evolution. These use whatever LLM provider is selected
 * in Settings (on-device or cloud), via the same routing as chat, but with their own
 * self-contained prompts so they don't carry a character's chat persona into a task.
 */
class AutoGenerationService(
    private val llamaEngine: LlamaEngine,
    private val settingsRepository: SettingsRepository,
    private val cloudLlmService: CloudLlmService
) {
    /**
     * A generated social post broken into its parts. [imagePrompt] is the text after a
     * "flux prompt:" line, used to generate the attached photo.
     */
    data class SocialContent(
        val text: String,
        val title: String? = null,
        val subreddit: String? = null,
        val imagePrompt: String? = null
    )

    /**
     * Generates a structured, in-character post. The model decides the actual content; for
     * photo platforms it appends a `flux prompt:` line we use to generate the image, and for
     * Rebbit it picks a fitting subreddit from [subredditChoices] (informed by [identity], so a
     * character's persona/memory steers where it posts). All content is produced by the user's
     * configured model at runtime.
     */
    suspend fun generatePost(
        charName: String,
        persona: String,
        platform: String,
        subredditChoices: List<String> = emptyList(),
        identity: String = ""
    ): SocialContent {
        val system = """
            You are $charName.
            $persona
            Stay completely in character. Output ONLY what is requested, no preamble or quotes.
        """.trimIndent()

        val userPrompt = when (platform) {
            "ustagram" -> """
                Write a short first-person caption for a photo you're posting (1-2 sentences, a few hashtags).
                Then, on a new line, describe the photo for an image generator.
                Format exactly:
                <caption>
                flux prompt: <visual description of the photo>
            """.trimIndent()
            "rebbit" -> buildString {
                appendLine("Write a Rebbit post as yourself, then describe the attached photo for an image generator.")
                if (subredditChoices.isNotEmpty()) {
                    appendLine("Pick the ONE subreddit that best fits you from: ${subredditChoices.joinToString(", ")}.")
                }
                if (identity.isNotBlank()) appendLine("About you: $identity")
                appendLine("Format exactly, each on its own line:")
                appendLine("subreddit: <one subreddit from the list>")
                appendLine("title: <short title>")
                appendLine("body: <1-3 sentences>")
                appendLine("flux prompt: <visual description of the photo>")
            }
            else -> "Write a short post for \"Y\" (a microblog) as yourself — one or two sentences."
        }

        Log.d(TAG, "Generating $platform post for $charName")
        val raw = complete(system, userPrompt, maxTokens = 320).trim()
        return parseSocial(raw, platform, charName, subredditChoices)
    }

    /** Legacy text-only entry point (used by replies/fallbacks). */
    suspend fun generateSocialPost(charName: String, platform: String, persona: String): String =
        generatePost(charName, persona, platform).let { it.title?.let { t -> "$t\n${it.text}" } ?: it.text }

    private val fluxRegex = Regex("""\*{0,3}\s*flux\s*prompt\s*:\s*\*{0,3}""", RegexOption.IGNORE_CASE)

    /** Splits a raw model reply into caption/title/body/subreddit/image-prompt parts. */
    private fun parseSocial(raw: String, platform: String, charName: String, choices: List<String>): SocialContent {
        // Pull the image prompt off the end first (shared by ustagram + rebbit).
        val fluxMatch = fluxRegex.find(raw)
        val beforeFlux = (if (fluxMatch != null) raw.substring(0, fluxMatch.range.first) else raw).trim()
        val imagePrompt = fluxMatch?.let { raw.substring(it.range.last + 1).trim().removeSurrounding("[", "]").trim() }
            ?.takeIf { it.isNotBlank() }

        fun lineValue(key: String): String? =
            Regex("""(?im)^\s*$key\s*:\s*(.+)$""").find(beforeFlux)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }

        return when (platform) {
            "rebbit" -> {
                val sub = lineValue("subreddit")?.let { s ->
                    val norm = if (s.startsWith("r/")) s else "r/${s.trim().trimStart('/')}"
                    choices.firstOrNull { it.equals(norm, ignoreCase = true) } ?: norm
                } ?: choices.randomOrNull() ?: "r/${charName.lowercase()}"
                val title = lineValue("title") ?: beforeFlux.lineSequence().firstOrNull { it.isNotBlank() }?.take(100) ?: ""
                val body = lineValue("body")
                    ?: beforeFlux.lineSequence().filterNot { it.contains(':') }.joinToString(" ").trim()
                SocialContent(text = body.ifBlank { title }, title = title, subreddit = sub, imagePrompt = imagePrompt)
            }
            "ustagram" -> SocialContent(text = beforeFlux.ifBlank { "✨" }, imagePrompt = imagePrompt)
            else -> SocialContent(text = beforeFlux.ifBlank { "…" })
        }
    }

    /** Generates a short in-character reply by [charName] to a social post. */
    suspend fun generateReply(charName: String, persona: String, platform: String, postContent: String): String {
        val system = """
            You are $charName.
            $persona
            You are leaving a short comment/reply on a $platform post. Stay completely in character.
            Keep it to one or two sentences. Output only the comment — no preamble, no quotes.
        """.trimIndent()
        val result = complete(system, "The post says:\n\"$postContent\"\n\nWrite your reply now.", maxTokens = 120).trim()
        return result.ifEmpty { "Nice!" }
    }

    /**
     * Rewrites the character's Living Dossier from recent history and returns a JSON
     * object string ({relationship, user_traits, world_facts, milestones}). The caller
     * is responsible for validating and persisting it.
     */
    suspend fun evolveDossier(charName: String, chatHistory: String, currentDossier: String): String {
        val evolutionPrompt = """
            You are the "Evolution Engine" for $charName.
            Analyze the recent conversation history and update the character's LIVING DOSSIER.

            [CURRENT DOSSIER]
            $currentDossier

            [RECENT HISTORY]
            $chatHistory

            [YOUR TASK]
            Return a VALID JSON object representing the NEW state of the Dossier.
            Use this exact JSON structure template:
            {
              "relationship": "current tier",
              "user_traits": { "key": "value" },
              "world_facts": { "key": "value" },
              "milestones": ["event 1", "event 2"]
            }

            Rules:
            1. OVERWRITE: Update variables if they have changed.
            2. ADD: Create new specific keys for new important facts. Use generic descriptors instead of proper names.
            3. PRUNE: Remove variables that are no longer relevant.
            4. SYNTHESIZE: Group similar info into descriptive values.
            5. CONCISE: Keep the entire JSON object under 1000 characters.
            6. NO PROPER NAMES: Use "you", "me", "the user", or physical descriptions.

            Return ONLY the JSON object. No preamble or markdown code blocks.
        """.trimIndent()

        val system = """
            You are a high-performance DATA PROCESSING ENGINE.
            You do not roleplay. You follow instructions exactly and return structured data.
        """.trimIndent()
        Log.d(TAG, "Evolving dossier for $charName")
        return complete(system, evolutionPrompt, maxTokens = 512)
    }

    /**
     * Runs a full (non-streamed) completion using the configured provider — on-device
     * llama.cpp or a cloud/HTTP OpenAI-compatible endpoint, mirroring the chat dispatch.
     */
    private suspend fun complete(system: String, userPrompt: String, maxTokens: Int): String {
        val provider = settingsRepository.llmProvider
        return if (provider == "llama") {
            val prompt = ChatTemplate.from(settingsRepository.chatTemplate)
                .render(system, listOf(ChatTurn(ChatTurn.Role.USER, userPrompt)))
            llamaEngine.generateStream(
                prompt = prompt,
                maxTokens = maxTokens,
                temperature = settingsRepository.temperature,
                topK = settingsRepository.topK,
                topP = settingsRepository.topP
            ).toList().joinToString("")
        } else {
            val messages = listOf(
                ChatMessage("system", system),
                ChatMessage("user", userPrompt)
            )
            cloudLlmService.chatStream(
                provider = provider,
                apiKey = settingsRepository.apiKey,
                baseUrl = settingsRepository.customBackendUrl,
                model = settingsRepository.cloudModel,
                messages = messages,
                temperature = settingsRepository.temperature,
                maxTokens = maxTokens
            ).toList().joinToString("")
        }
    }

    companion object {
        private const val TAG = "AutoGeneration"
    }
}
