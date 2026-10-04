package com.mrj.fancyai.ui.characters

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.mrj.fancyai.R
import com.mrj.fancyai.service.llm.AssistantProtocol
import com.mrj.fancyai.service.llm.ImagePrompt
import com.mrj.fancyai.service.llm.LlmEngineClient
import com.mrj.fancyai.service.llm.LlmRequest
import com.mrj.fancyai.service.llm.MacroBus
import com.mrj.fancyai.service.llm.SocialPostJson
import com.mrj.fancyai.service.llm.complete
import com.mrj.fancyai.service.llm.generatePromptImage
import com.mrj.fancyai.ui.settings.LlmSettingsStore
import com.mrj.fancyai.ui.settings.runtime
import com.mrj.fancyai.ui.settings.sessionConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

internal class RootCreatorController(private val context: Context) {
    private val runtime = LlmEngineClient(context)
    private val preferences = context.getSharedPreferences(ROOT_CREATOR_PREFERENCES, Context.MODE_PRIVATE)
    private var generationRun = 0
    private var generationJob: Job? = null
    var thoughtProcess by mutableStateOf(preferences.getString("thought_process", "").orEmpty())
        private set
    var idea by mutableStateOf(preferences.getString(KEY_IDEA, "").orEmpty())
        private set
    var imagePath by mutableStateOf<String?>(null)
        private set
    var hasDraft by mutableStateOf(hasCharacterDraft(context, ROOT_CREATOR_DRAFT_KEY))
        private set
    var generating by mutableStateOf(false)
        private set
    var error by mutableIntStateOf(0)
        private set

    fun updateIdea(value: String) {
        idea = value
        preferences.edit { putString(KEY_IDEA, value) }
    }

    fun stopGeneration() {
        generationRun++
        runtime.cancel()
        generationJob?.cancel()
        generationJob = null
        generating = false
    }

    suspend fun writeDraft(): Boolean {
        if (generating) {
            stopGeneration()
            return false
        }
        val request = idea.trim()
        if (request.isEmpty()) {
            error = R.string.root_creator_idea_required
            return false
        }
        val run = ++generationRun
        generationJob = currentCoroutineContext()[Job]
        thoughtProcess = ""
        imagePath = null
        preferences.edit { remove("thought_process") }
        generating = true
        error = 0
        return try {
            val settings = withContext(Dispatchers.IO) { LlmSettingsStore.snapshot(context) }
            if (settings == null) {
                if (run == generationRun) error = R.string.root_creator_engine_required
                return false
            }
            val turn = AssistantProtocol.compile(
                settings.runtime,
                macros = MacroBus(context, character = null),
                message = request,
                instructions = listOf(
                    "Create a reusable character card, not a roleplay reply or conversation. In personality, description, scene, and first_message, refer to the character as {{char}} and the user as {{user}}. Preserve these exact placeholders in the output.",
                    "Output plain-text fields only in this exact format with NO preface, greeting, introductory remarks, commentary, thinking, or markdown code fences:\n" +
                        "name: Character name only\n" +
                        "handle: Social handle starting with @\n" +
                        "personality: Core personality traits\n" +
                        "description: Character identity, voice, background, and dynamic with the user; use {{char}} and {{user}} no names\n" +
                        "scene: Current setting and situation\n" +
                        "first_message: Opening speech or action to start the roleplay\n" +
                        "appearance: Concise comma-separated visual tags: age, gender, skin, eyes, hair style/color, facial features, build and outfit (no narrative, biography, or dialogue)",
                ),
                includeIdentity = false,
            )
            val generated = runtime.generate(LlmRequest(
                config = settings.sessionConfig(turn.systemInstruction),
                input = turn.input,
                thinking = false,
            )).complete()
            if (run == generationRun) {
                thoughtProcess = generated.channels.values.joinToString("\n\n")
                preferences.edit { putString("thought_process", thoughtProcess) }
            }
            val (body, scene) = ImagePrompt.split(generated.text, imageOnly = turn.imageRequested)
            if (turn.imageRequested) {
                val image = scene?.let { generatePromptImage(context, it, characterId = null) }
                if (run == generationRun) imagePath = image?.path
                return false
            }
            val draft = parseCharacterDraft(body)
            withContext(NonCancellable) {
                withContext(Dispatchers.IO) { saveCharacterDraft(context, ROOT_CREATOR_DRAFT_KEY, draft) }
                if (run == generationRun) hasDraft = true
            }
            run == generationRun
        } finally {
            if (run == generationRun) {
                generationJob = null
                generating = false
            }
        }
    }

    fun close() {
        stopGeneration()
        runtime.close()
    }
}

private val creatorJsonFormat = Json { ignoreUnknownKeys = true }

internal fun parseCharacterDraft(raw: String): CharacterDraft {
    val parsed = SocialPostJson.parse(raw)
    val decoded = parsed?.let { runCatching { creatorJsonFormat.decodeFromJsonElement(CharacterDraft.serializer(), it) }.getOrNull() }
    if (decoded != null && decoded.name.isNotBlank()) {
        return decoded.copy(
            name = decoded.name.trim(),
            handle = decoded.handle.trim().trimStart('@').takeIf(String::isNotBlank)?.let { "@$it" }.orEmpty(),
            personality = decoded.personality.trim(),
            description = decoded.description.trim(),
            scene = decoded.scene.trim(),
            firstMessage = decoded.firstMessage.trim(),
            appearance = decoded.appearance.trim(),
        )
    }
    val rawName = SocialPostJson.field(raw, "name", parsed).orEmpty().trim()
    val cleanName = rawName.lineSequence().firstOrNull(String::isNotBlank)
        ?.substringBefore(" Age:")?.substringBefore(" **Age:")?.substringBefore(" age:")?.trim().orEmpty()
    return CharacterDraft(
        name = cleanName,
        handle = SocialPostJson.field(raw, "handle", parsed).orEmpty().trim().trimStart('@')
            .takeIf(String::isNotBlank)?.let { "@$it" }.orEmpty(),
        personality = SocialPostJson.field(raw, "personality", parsed).orEmpty().trim(),
        description = SocialPostJson.text(raw, "description", parsed),
        scene = (SocialPostJson.field(raw, "scene", parsed) ?: SocialPostJson.field(raw, "scenario", parsed)).orEmpty().trim(),
        firstMessage = (SocialPostJson.field(raw, "first_message", parsed)
            ?: SocialPostJson.field(raw, "first message", parsed)
            ?: SocialPostJson.field(raw, "greeting", parsed)).orEmpty().trim(),
        appearance = SocialPostJson.field(raw, "appearance", parsed).orEmpty().trim(),
    )
}

internal const val ROOT_CREATOR_DRAFT_KEY = "root_creator"
private const val ROOT_CREATOR_PREFERENCES = "root_creator"
private const val KEY_IDEA = "idea"
