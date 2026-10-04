package com.mrj.fancyai.ui.binder

import android.content.Context
import androidx.annotation.StringRes
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
import com.mrj.fancyai.service.llm.complete
import com.mrj.fancyai.service.llm.generatePromptImage
import com.mrj.fancyai.service.llm.llmErrorResource
import com.mrj.fancyai.ui.aura.deleteAuraResult
import com.mrj.fancyai.ui.characters.CharacterCard
import com.mrj.fancyai.ui.characters.availableCharacters
import com.mrj.fancyai.ui.characters.parseCharacterDraft
import com.mrj.fancyai.ui.characters.toCard
import com.mrj.fancyai.ui.characters.saveGeneratedCharacter
import com.mrj.fancyai.ui.settings.LlmSettingsStore
import com.mrj.fancyai.ui.settings.runtime
import com.mrj.fancyai.ui.settings.sessionConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

internal class BinderController(
    private val app: Context,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val feedback: (String) -> Unit,
) {
    private var runtime: LlmEngineClient? = null
    var preferences by mutableStateOf(readBinderPreferences(app))
    var thoughtProcess by mutableStateOf("")
        private set
    var imageProgress by mutableIntStateOf(0)
    var generation by mutableStateOf(BinderGeneration.Idle)
    private var generationJob: Job? = null
    private var generationRun = 0
    var match by mutableStateOf<BinderMatch?>(null)
    private var sessionNames = emptySet<String>()
    var sessionMatches by mutableIntStateOf(0)
    var editing by mutableStateOf(!preferences.configured)
    var savingMatch by mutableStateOf(false)
    var exitRequested by mutableStateOf(false)

    fun updatePreferences(value: BinderPreferences) {
        preferences = value
        app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_CONFIGURED, value.configured)
            putString(KEY_INTEREST, value.interest.key)
            putInt(KEY_MINIMUM_AGE, value.minimumAge)
            putInt(KEY_MAXIMUM_AGE, value.maximumAge)
            putString(KEY_STYLE, value.style.key)
            putString(KEY_NATIONALITY, value.nationality)
            putString(KEY_TRAITS, value.traits.joinToString("\n"))
            putString(KEY_CUSTOM_TRAIT, value.customTrait)
        }
    }

    fun generateMatch() {
        if (generation.busy || savingMatch) return
        updatePreferences(preferences.copy(configured = true))
        editing = false
        val settled = preferences
        val run = ++generationRun
        imageProgress = 0
        generation = if (match == null) BinderGeneration.Writing else BinderGeneration.Rendering
        generationJob = scope.launch {
            try {
                val settings = withContext(Dispatchers.IO) { LlmSettingsStore.snapshot(app) } ?: run {
                    if (run == generationRun) feedback(app.getString(R.string.binder_engine_required))
                    return@launch
                }
                val active = runtime ?: LlmEngineClient(app)
                runtime = active
                val existing = match
                val candidateAndScene = if (existing == null) {
                    generateCandidate(settled, settings, active, run)?.also { match = it.first } ?: return@launch
                } else {
                    existing to null
                }
                val candidate = candidateAndScene.first
                val scenePrompt = candidateAndScene.second
                val card = candidate.card
                val macros = MacroBus(app, card.toCard(candidate.characterId), profile = null)
                generation = BinderGeneration.Rendering
                val image = generatePromptImage(
                    app, macros.text(scenePrompt ?: card.appearance), characterId = null, archive = false,
                ) { if (run == generationRun) imageProgress = it }
                if (run == generationRun) match = candidate.copy(portrait = image)
                else check(deleteAuraResult(image))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (run == generationRun) feedback(app.getString(llmErrorResource(failure, R.string.chat_engine_failed)))
            } finally {
                if (run == generationRun) {
                    generation = BinderGeneration.Idle
                    generationJob = null
                }
            }
        }
    }

    private suspend fun generateCandidate(
        settled: BinderPreferences,
        settings: com.mrj.fancyai.ui.settings.LlmSettings,
        active: LlmEngineClient,
        run: Int,
    ): Pair<BinderMatch, String?>? {
        val names = sessionNames
        val existingNames = withContext(Dispatchers.IO) {
            (availableCharacters(app).map(CharacterCard::name) + names)
                .asSequence().map(String::trim).filter(String::isNotBlank).distinctBy(String::lowercase).toList()
        }
        val age = (settled.minimumAge..settled.maximumAge).random()
        val turn = AssistantProtocol.compile(
            settings.runtime,
            macros = MacroBus(app, character = null, profile = null),
            message = "Create a Binder dating match.",
            instructions = listOf(
                "Binder is a dating app. Create a distinct adult stranger with their own interests, desires, boundaries, and life. Write a self-contained dating bio about the character. They do not know the reader. Do not address or mention the user, personalize the bio to the reader, or imply a prior connection or relationship.",
                "Output plain-text fields in this exact format with NO preface, greeting, introductory remarks, commentary, thinking, or markdown code fences:\n" +
                    "name: Full name only (e.g. First Last)\n" +
                    "handle: Social handle starting with @\n" +
                    "description: Dating bio in 2 to 3 sentences about their life, personality, and interests; refer to the character using {{char}}.\n" +
                    "appearance: Concise visual tags for skin, eyes, hair, facial features, and build (no biography or dialogue).\n\n" +
                    "End with a short description of the scene in this exact format:\n\n" +
                    "<scene_prompt>{{char.appearance}}, [scene detail], [outfit], [location], [action], [POV angle], [camera effects]</scene_prompt>",
            ),
            context = buildList {
                add("Look: $age-year-old ${settled.interest.key}, ${settled.style.key}")
                if (settled.nationality.isNotBlank()) add("Nationality: ${settled.nationality}")
                if (settled.traits.isNotEmpty()) add("Personality: ${settled.traits.joinToString(", ")}")
                if (existingNames.isNotEmpty()) add("Names already used: ${existingNames.takeLast(12).joinToString(", ")}")
            },
            includeIdentity = false,
        )
        thoughtProcess = ""
        val response = active.generate(LlmRequest(
            config = settings.sessionConfig(turn.systemInstruction),
            input = turn.input,
            thinking = false,
        )).complete()
        if (run != generationRun) return null
        thoughtProcess = response.channels.values.joinToString("\n\n")
        val (body, scene) = ImagePrompt.split(response.text)
        val card = parseCharacterDraft(body).let { draft ->
            draft.copy(
                personality = settled.traits.joinToString(", "),
                description = if (settled.nationality.isBlank()) draft.description else
                    "Nationality: ${settled.nationality}. ${draft.description}",
                appearance = listOf(
                    "$age-year-old ${settled.interest.key}".takeUnless { draft.appearance.contains("$age", ignoreCase = true) },
                    settled.nationality.takeUnless { it.isBlank() || draft.appearance.contains(it, ignoreCase = true) },
                    settled.style.key.takeUnless { draft.appearance.contains(it, ignoreCase = true) },
                    draft.appearance,
                ).filter { !it.isNullOrBlank() }.joinToString(", "),
                scene = "Matched on dating app",
                firstMessage = "",
            )
        }
        sessionNames += card.name.lowercase(Locale.ROOT)
        return BinderMatch(card, age, settled.interest, settled.traits) to scene
    }

    fun stopGeneration() {
        if (!generation.busy) return
        generationRun++
        generationJob?.cancel()
        generationJob = null
        runtime?.cancel()
        generation = BinderGeneration.Idle
        imageProgress = 0
    }

    fun close() {
        stopGeneration()
        generationRun++
        runtime?.close()
        runtime = null
        if (match?.portrait?.let { !deleteAuraResult(it) } == true) return
        match = null
    }

    fun pass() {
        if (generation.busy || savingMatch) return
        if (match?.portrait?.let { !deleteAuraResult(it) } == true) return
        match = null
        thoughtProcess = ""
        editing = false
    }

    fun saveMatch(candidate: BinderMatch) {
        if (generation.busy || savingMatch) return
        savingMatch = true
        scope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    synchronized(com.mrj.fancyai.ui.settings.ProAccess.charactersLock) {
                        saveGeneratedCharacter(app, candidate.card, candidate.portrait, candidate.characterId)
                    }
                }
                candidate.portrait?.let { image ->
                    image.delete()
                    File(image.parentFile, "${image.nameWithoutExtension}.json").delete()
                }
                match = null
                editing = false
                sessionMatches++
                feedback(app.getString(R.string.binder_match_saved, saved.name))
            } finally {
                savingMatch = false
            }
        }
    }

}

internal data class BinderPreferences(
    val configured: Boolean = false,
    val interest: BinderInterest = BinderInterest.Female,
    val minimumAge: Int = DEFAULT_MINIMUM_AGE,
    val maximumAge: Int = DEFAULT_MAXIMUM_AGE,
    val style: BinderStyle = BinderStyle.Photoreal,
    val nationality: String = "",
    val traits: List<String> = emptyList(),
    val customTrait: String = "",
)

internal data class BinderMatch(
    val card: com.mrj.fancyai.ui.characters.CharacterDraft,
    val age: Int,
    val identity: BinderInterest,
    val tags: List<String>,
    val portrait: File? = null,
    val characterId: String = java.util.UUID.randomUUID().toString(),
)


internal enum class BinderGeneration {
    Idle, Writing, Rendering;

    val busy: Boolean get() = this != Idle
}

internal enum class BinderInterest(val key: String, @param:StringRes val label: Int) {
    Female("female", R.string.identity_female),
    Male("male", R.string.identity_male);

}

internal enum class BinderStyle(val key: String, @param:StringRes val label: Int) {
    Photoreal("Photoreal", R.string.binder_style_photoreal),
    Anime("Anime", R.string.binder_style_anime);

}

internal const val MINIMUM_AGE = 18
internal const val MAXIMUM_AGE = 50
internal const val DEFAULT_MINIMUM_AGE = 21
internal const val DEFAULT_MAXIMUM_AGE = 39
internal const val MAXIMUM_TRAITS = 5
internal const val FIELD_LIMIT = 80
internal const val TRAIT_LIMIT = 32

internal fun readBinderPreferences(context: Context): BinderPreferences {
    val values = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    val minimum = values.getInt(KEY_MINIMUM_AGE, DEFAULT_MINIMUM_AGE).coerceIn(MINIMUM_AGE, MAXIMUM_AGE)
    val maximum = values.getInt(KEY_MAXIMUM_AGE, DEFAULT_MAXIMUM_AGE).coerceIn(minimum, MAXIMUM_AGE)
    return BinderPreferences(
        configured = values.getBoolean(KEY_CONFIGURED, false),
        interest = BinderInterest.entries.firstOrNull { it.key == values.getString(KEY_INTEREST, null) } ?: BinderInterest.Female,
        minimumAge = minimum,
        maximumAge = maximum,
        style = BinderStyle.entries.firstOrNull { it.key == values.getString(KEY_STYLE, null) } ?: BinderStyle.Photoreal,
        nationality = values.getString(KEY_NATIONALITY, "").orEmpty(),
        traits = values.getString(KEY_TRAITS, "").orEmpty().lineSequence().filter(String::isNotBlank)
            .distinct().take(MAXIMUM_TRAITS).toList(),
        customTrait = values.getString(KEY_CUSTOM_TRAIT, "").orEmpty(),
    )
}

private const val PREFERENCES = "binder_settings"
private const val KEY_CONFIGURED = "configured"
private const val KEY_INTEREST = "interest"
private const val KEY_MINIMUM_AGE = "minimum_age"
private const val KEY_MAXIMUM_AGE = "maximum_age"
private const val KEY_STYLE = "style"
private const val KEY_TRAITS = "traits"
private const val KEY_CUSTOM_TRAIT = "custom_trait"

private const val KEY_NATIONALITY = "nationality"
