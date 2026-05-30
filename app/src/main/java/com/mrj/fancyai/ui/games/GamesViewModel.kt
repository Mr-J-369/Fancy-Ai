package com.mrj.fancyai.ui.games

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mrj.fancyai.data.db.entity.CharacterEntity
import com.mrj.fancyai.data.repository.MediaRepository
import com.mrj.fancyai.data.repository.MessengerRepository
import com.mrj.fancyai.data.repository.SettingsRepository
import com.mrj.fancyai.domain.inference.ChatTemplate
import com.mrj.fancyai.domain.inference.ChatTurn
import com.mrj.fancyai.domain.inference.LlamaEngine
import com.mrj.fancyai.domain.inference.PromptBuilder
import com.mrj.fancyai.domain.inference.PromptContext
import com.mrj.fancyai.domain.inference.UserProfile
import com.mrj.fancyai.service.ChatMessage
import com.mrj.fancyai.service.CloudLlmService
import com.mrj.fancyai.service.ImageService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

/**
 * The game catalogue, ported from the legacy games.js. Each game carries its own opening
 * prompt; [ADVENTURE] also wraps the player's actions so the model advances a branching story.
 */
enum class GameType(
    val title: String,
    val icon: String,
    val desc: String,
    val opening: String
) {
    ADVENTURE("World Adventure", "🗺️", "Choose-your-own-adventure story.",
        "Start a Choose Your Own Adventure story. Set the scene briefly (1-2 sentences) and offer my first choices. Format them clearly like: 1. [Choice One] 2. [Choice Two]. Keep it short and punchy. End with 'flux prompt:' and a vivid description of the scene."),
    DICE_DUEL("Dice Duel RPG", "⚔️", "Narrative combat with AI.",
        "We are entering a Dice Duel (RPG battle). Describe our encounter briefly (1-2 sentences) and prepare for combat. Keep it short. End with 'flux prompt:' describing the scene."),
    TACTICAL("Tactical Command", "♟️", "Narrative chess-like duel.",
        "We are playing Tactical Command (a chess-like strategy game). Set the scene briefly (1-2 sentences). You are the opposing commander — describe your opening move concisely."),
    TRUTH_DARE("Truth or Dare", "🎭", "Classic game with visual dares.",
        "We are playing Truth or Dare. Introduce yourself in character and ask me to choose: TRUTH or DARE. Keep it short and welcoming."),
    TWO_TRUTHS("Two Truths & A Lie", "🕵️", "Spot the lie.",
        "We are playing 'Two Truths and a Lie'. Tell me three short facts about yourself, one of which is a lie. Format: 1. [Fact 1] 2. [Fact 2] 3. [Fact 3]. One sentence each. Do not reveal the lie yet."),
    ORACLE("The Oracle", "🔮", "Fortune reading and tarot.",
        "I come to you for a reading. Read my fortune or pull a tarot card. Keep it short and mystical (1-2 sentences). End with 'flux prompt:' describing the card."),
    WOULD_YOU_RATHER("Would You Rather", "🤔", "Tough either/or choices.",
        "Let's play Would You Rather. Give me two difficult options. Format: 1. [Option One] 2. [Option Two]. Keep it short.");

    /** Wraps a player action into the per-game instruction sent to the model. */
    fun actionPrompt(action: String): String = when (this) {
        ADVENTURE -> "[GAME ACTION: Choose Your Own Adventure] The player chooses: \"$action\". Advance the story 2-3 sentences and present 2-4 new numbered choices. You may end with 'flux prompt:' to visualize the new scene."
        TRUTH_DARE -> "[GAME ACTION: Truth or Dare] $action. If this is a DARE, describe a bold, in-character dare and end with 'flux prompt:' depicting it."
        else -> "[GAME ACTION: $title] $action"
    }
}

class GamesViewModel(
    private val messengerRepository: MessengerRepository,
    private val mediaRepository: MediaRepository,
    private val settingsRepository: SettingsRepository,
    private val llamaEngine: LlamaEngine,
    private val cloudLlmService: CloudLlmService,
    private val imageService: ImageService
) : ViewModel() {

    var activeGame by mutableStateOf<GameType?>(null)
    var gameCharacter by mutableStateOf<CharacterEntity?>(null)
        private set
    var availableCharacters by mutableStateOf<List<CharacterEntity>>(emptyList())
        private set
    var gameText by mutableStateOf("Ready to play?")
    var isThinking by mutableStateOf(false)
    var streamingText by mutableStateOf("")
    var gameImage by mutableStateOf<File?>(null)
        private set
    var generatingImage by mutableStateOf(false)
        private set

    private val gameHistory = mutableListOf<ChatTurn>()
    private val fluxRegex = Regex("""\*{0,3}\s*flux\s*prompt\s*:\s*\*{0,3}""", RegexOption.IGNORE_CASE)

    init {
        viewModelScope.launch {
            messengerRepository.getInboxItems().collectLatest { items ->
                availableCharacters = items.map { it.character }
                if (gameCharacter == null) gameCharacter = availableCharacters.firstOrNull()
            }
        }
    }

    fun selectGame(type: GameType) {
        activeGame = type
    }

    fun selectCharacter(character: CharacterEntity) {
        gameCharacter = character
    }

    fun resolveImage(ref: String): File? = mediaRepository.resolveToFile(ref)

    fun startGame() {
        val game = activeGame ?: return
        if (gameCharacter == null || isThinking) return
        isThinking = true
        gameText = "Initializing ${game.title}…"
        gameHistory.clear()
        gameImage = null
        viewModelScope.launch { runGameInference(game.opening) }
    }

    fun sendAction(action: String) {
        val game = activeGame ?: return
        if (gameCharacter == null || isThinking) return
        isThinking = true
        viewModelScope.launch { runGameInference(game.actionPrompt(action)) }
    }

    /** Provider-aware generation (on-device llama or any cloud/HTTP provider), mirroring chat. */
    private suspend fun runGameInference(userText: String) {
        val char = gameCharacter ?: return
        streamingText = ""

        val provider = settingsRepository.llmProvider
        val user = UserProfile(settingsRepository.userName, settingsRepository.userBio)

        val tokenFlow: Flow<String> = if (provider == "llama") {
            val prompt = PromptBuilder.build(
                context = PromptContext.GAME,
                character = char,
                user = user,
                userText = userText,
                history = gameHistory,
                globalGuidelines = settingsRepository.systemPrompt,
                template = ChatTemplate.from(settingsRepository.chatTemplate)
            )
            llamaEngine.generateStream(
                prompt = prompt,
                maxTokens = settingsRepository.maxTokens,
                temperature = settingsRepository.temperature,
                topK = settingsRepository.topK,
                topP = settingsRepository.topP
            )
        } else {
            val system = PromptBuilder.systemPrompt(
                context = PromptContext.GAME,
                character = char,
                user = user,
                globalGuidelines = settingsRepository.systemPrompt
            )
            val messages = buildList {
                add(ChatMessage("system", system))
                gameHistory.forEach { turn ->
                    val role = if (turn.role == ChatTurn.Role.USER) "user" else "assistant"
                    add(ChatMessage(role, PromptBuilder.applyMacros(turn.content, char.name, user.name)))
                }
                add(ChatMessage("user", PromptBuilder.applyMacros(userText, char.name, user.name)))
            }
            cloudLlmService.chatStream(
                provider = provider,
                apiKey = settingsRepository.apiKey,
                baseUrl = settingsRepository.customBackendUrl,
                model = settingsRepository.cloudModel,
                messages = messages,
                temperature = settingsRepository.temperature,
                maxTokens = settingsRepository.maxTokens
            )
        }

        try {
            val reply = StringBuilder()
            tokenFlow.collect { token ->
                reply.append(token)
                streamingText = stripFlux(reply.toString())
            }
            val raw = reply.toString()
            val text = stripFlux(raw)
            gameHistory += ChatTurn(ChatTurn.Role.USER, userText)
            gameHistory += ChatTurn(ChatTurn.Role.ASSISTANT, raw.trim())
            gameText = text.ifEmpty { "(no response — is a model loaded / provider configured?)" }
            streamingText = ""

            // Generate the scene image from any "flux prompt:" the model produced.
            val match = fluxRegex.find(raw)
            val imagePrompt = match?.let { raw.substring(it.range.last + 1).trim().removeSurrounding("[", "]").trim() }
                ?.takeIf { it.isNotBlank() }
            if (imagePrompt != null) generateSceneImage(imagePrompt)
        } catch (e: Exception) {
            gameText = "⚠️ ${e.message ?: "Game generation failed"}"
            streamingText = ""
        } finally {
            isThinking = false
        }
    }

    private suspend fun generateSceneImage(prompt: String) {
        generatingImage = true
        try {
            val params = mapOf(
                "width" to settingsRepository.imgWidth, "height" to settingsRepository.imgHeight,
                "steps" to settingsRepository.imgSteps, "cfg" to settingsRepository.imgCfg
            )
            imageService.generate(prompt, params, null).onSuccess { gen ->
                gen.ref?.let { gameImage = mediaRepository.resolveToFile(it) }
            }
        } finally {
            generatingImage = false
        }
    }

    private fun stripFlux(raw: String): String {
        val m = fluxRegex.find(raw) ?: return raw.trim()
        return raw.substring(0, m.range.first).trim()
    }

    fun exitGame() {
        activeGame = null
        gameText = "Ready to play?"
        streamingText = ""
        gameImage = null
        gameHistory.clear()
    }
}
