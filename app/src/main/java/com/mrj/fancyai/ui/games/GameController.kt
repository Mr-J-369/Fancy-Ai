package com.mrj.fancyai.ui.games

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
import com.mrj.fancyai.service.llm.LlmExchange
import com.mrj.fancyai.service.llm.LlmRequest
import com.mrj.fancyai.service.llm.MacroBus
import com.mrj.fancyai.service.llm.generatePromptImage
import com.mrj.fancyai.service.llm.llmErrorResource
import com.mrj.fancyai.service.memory.CharacterMemory
import com.mrj.fancyai.ui.characters.CharacterCard
import com.mrj.fancyai.ui.lorebook.lorebookContext
import com.mrj.fancyai.ui.profile.userProfile
import com.mrj.fancyai.ui.settings.LlmSettings
import com.mrj.fancyai.ui.settings.LlmSettingsStore
import com.mrj.fancyai.ui.settings.runtime
import com.mrj.fancyai.ui.settings.sessionConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

internal class GameController(
    private val app: Context,
    private val scope: CoroutineScope,
    private val feedback: (Int) -> Unit,
) {
    private val instructionPreferences = app.getSharedPreferences("game_instructions", Context.MODE_PRIVATE)
    private val draftPreferences = app.getSharedPreferences("game_draft", Context.MODE_PRIVATE)
    var renderingImage by mutableStateOf(false)
        private set

    private var memorySessionId = UUID.randomUUID().toString()
    private var memoryCharacterId: String? = null
    var runtime: LlmEngineClient? = null
    private var completedTurns = 0
    private var history = emptyList<LlmExchange>()
    var characters by mutableStateOf<List<CharacterCard>>(emptyList())
    var profile by mutableStateOf(userProfile(app))
    var rosterLoading by mutableStateOf(value = true)
    var selectedGame by mutableStateOf<GameDefinition?>(null)
        private set
    var instruction by mutableStateOf("")
        private set
    var openingInstruction by mutableStateOf("")
        private set
    var replyInstruction by mutableStateOf("")
        private set
    var selectedCharacter by mutableStateOf<CharacterCard?>(null)
    var active by mutableStateOf(value = false)
    var preparing by mutableStateOf(value = false)
    var imageProgress by mutableIntStateOf(0)
    var generating by mutableStateOf(value = false)
    var messages by mutableStateOf<List<GameMessage>>(emptyList())
    var draft by mutableStateOf("")
    var generationJob: Job? = null
    var showExit by mutableStateOf(value = false)

    fun selectGame(game: GameDefinition) {
        selectedGame = game
        instruction = instructionPreferences.getString("${game.id}.rules", app.getString(game.rules)).orEmpty()
        openingInstruction = instructionPreferences.getString("${game.id}.opening", game.opening).orEmpty()
        replyInstruction = instructionPreferences.getString("${game.id}.format", game.replyFormat).orEmpty()
    }

    fun updateInstructions(rules: String, opening: String, format: String) {
        val game = selectedGame ?: return
        instruction = rules
        openingInstruction = opening
        replyInstruction = format
        instructionPreferences.edit {
            putString("${game.id}.rules", rules)
            putString("${game.id}.opening", opening)
            putString("${game.id}.format", format)
        }
    }

    fun updateDraft(value: String) {
        val game = selectedGame ?: return
        val character = selectedCharacter ?: return
        draft = value
        draftPreferences.edit {
            val key = "${game.id}:${character.id}"
            if (value.isEmpty()) remove(key) else putString(key, value)
        }
    }

    fun resetGame() {
        generationJob?.cancel()
        runtime?.cancel()
        runtime?.close()
        runtime = null
        active = false
        selectedGame = null
        messages = emptyList()
        completedTurns = 0
        history = emptyList()
        forgetMemorySession()
        draft = ""
    }

    private fun forgetMemorySession() {
        val sessionId = memorySessionId
        val characterId = memoryCharacterId
        memorySessionId = UUID.randomUUID().toString()
        memoryCharacterId = null
        if (characterId != null) {
            scope.launch { CharacterMemory(app, characterId).deleteSession("games:$sessionId") }
        }
    }

    fun send(opening: Boolean = false) {
        val game = selectedGame ?: return
        val character = selectedCharacter ?: return
        if (generating || preparing || (!opening && !active)) return
        val bus = MacroBus(app, character, profile)
        val modelInput = if (opening) bus.text(openingInstruction) else draft.trim()
        if (modelInput.isBlank()) return
        val turn = prepareTurn(opening, game, character, bus, modelInput)
        generating = true
        generationJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                playTurn(turn)
            } finally {
                renderingImage = false
                preparing = false
                generating = false
                generationJob = null
            }
        }
    }

    private fun prepareTurn(
        opening: Boolean,
        game: GameDefinition,
        character: CharacterCard,
        bus: MacroBus,
        modelInput: String,
    ): PendingTurn {
        if (opening) {
            preparing = true
            forgetMemorySession()
            memoryCharacterId = character.id
            messages = emptyList()
            completedTurns = 0
            history = emptyList()
            draft = draftPreferences.getString("${game.id}:${character.id}", "").orEmpty()
        } else {
            updateDraft("")
        }
        val contextQuery = (messages.takeLast(4).map(GameMessage::text) + modelInput).joinToString("\n")
        val assistantId = UUID.randomUUID().toString()
        val additions = buildList {
            if (!opening) add(GameMessage(role = GameRole.USER, text = modelInput))
            add(GameMessage(id = assistantId, role = GameRole.ASSISTANT))
        }
        messages += additions
        return PendingTurn(game, character, bus, modelInput, contextQuery, assistantId, memorySessionId, instruction, replyInstruction)
    }

    private suspend fun playTurn(turn: PendingTurn) {
        val activeRuntime = runtime ?: LlmEngineClient(app).also { runtime = it }
        val settings = LlmSettingsStore.snapshot(app) ?: run {
            feedback(R.string.games_engine_required)
            return
        }
        val contextBlocks = withContext(Dispatchers.IO) { lorebookContext(app, turn.character, turn.contextQuery) }
        val memory = CharacterMemory(app, turn.character.id)
        val memories = memory.recall(turn.modelInput, newConversation = completedTurns == 0)
        val compiled = AssistantProtocol.compile(
            settings.runtime,
            macros = turn.bus,
            instructions = listOf(turn.instruction, turn.replyInstruction),
            message = turn.modelInput,
            context = listOf(turn.bus.text("Current activity: ${app.getString(turn.game.title)} with {{user}}.")),
            optionalContext = listOf(turn.bus.text(contextBlocks)) + memories,
        )
        val request = LlmRequest(settings.sessionConfig(systemInstruction = compiled.systemInstruction, history = if (compiled.imageRequested) emptyList() else history), compiled.input)
        active = true
        preparing = false
        val text = streamReply(activeRuntime, request, settings, turn, compiled.imageRequested)
        history = (history + LlmExchange(compiled.input, turn.bus.text(text))).takeLast(settings.memory.historyLimit.coerceAtLeast(0))
        val turnIndex = completedTurns++
        memory.collect(
            sessionId = "games:$memorySessionId",
            turnIndex = turnIndex,
            timestamp = System.currentTimeMillis(),
            messages = messages.filter { it.text.isNotBlank() }.takeLast(4).map {
                val speaker = if (it.role == GameRole.USER) profile.name.ifBlank { "User" } else turn.character.name
                "$speaker: ${it.text}"
            },
        )
        val scenePrompt = ImagePrompt.split(if (compiled.imageRequested) text else turn.bus.text(text), imageOnly = compiled.imageRequested).second ?: return
        messages = messages.map { message ->
            if (message.id == turn.assistantId) message.copy(imagePrompt = scenePrompt) else message
        }
        renderingImage = true
        imageProgress = 0
        try {
            val image = generatePromptImage(app, scenePrompt, characterId = turn.character.id) { imageProgress = it }
            messages = messages.map { message ->
                if (message.id == turn.assistantId) message.copy(imagePath = image.relativeTo(app.filesDir).path, imagePrompt = scenePrompt) else message
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            feedback(llmErrorResource(failure, R.string.aura_generation_failed))
        }
    }

    private suspend fun streamReply(
        activeRuntime: LlmEngineClient,
        request: LlmRequest,
        settings: LlmSettings,
        turn: PendingTurn,
        imageRequested: Boolean,
    ): String {
        val text = StringBuilder()
        val thoughts = StringBuilder()
        try {
            activeRuntime.generate(request).collect { chunk ->
                text.append(chunk.text)
                chunk.channels.values.forEach(thoughts::append)
                messages = messages.map { message ->
                    if (message.id == turn.assistantId) message.copy(text = ImagePrompt.split(turn.bus.text(text.toString()), imageOnly = imageRequested).first, thoughtProcess = thoughts.toString()) else message
                }
            }
        } catch (cancelled: CancellationException) {
            if (text.isNotBlank() && active && memorySessionId == turn.sessionId) {
                history = (history + LlmExchange(request.input, turn.bus.text(text.toString()))).takeLast(settings.memory.historyLimit.coerceAtLeast(0))
            }
            throw cancelled
        }
        return text.toString()
    }


    private class PendingTurn(
        val game: GameDefinition,
        val character: CharacterCard,
        val bus: MacroBus,
        val modelInput: String,
        val contextQuery: String,
        val assistantId: String,
        val sessionId: String,
        val instruction: String,
        val replyInstruction: String,
    )


}

internal data class GameDefinition(
    val id: String,
    @param:StringRes val title: Int,
    val symbol: Int,
    @param:StringRes val tagline: Int,
    @param:StringRes val rules: Int,
    @param:StringRes val placeholder: Int,
    val opening: String,
    val replyFormat: String,
)

internal val GAMES = listOf(
    GameDefinition(
        id = "adventure",
        title = R.string.games_adventure_title,
        symbol = R.drawable.ic_spark,
        tagline = R.string.games_adventure_tagline,
        rules = R.string.games_adventure_rules,
        placeholder = R.string.games_adventure_placeholder,
        opening = "Begin the adventure with {{user}}.",
        replyFormat = "Adventure turn format: scene and consequences, followed by 2–4 numbered choices. A custom player move is also supported.",
    ),
    GameDefinition(
        id = "dice_duel",
        title = R.string.games_dice_title,
        symbol = R.drawable.ic_dice,
        tagline = R.string.games_dice_tagline,
        rules = R.string.games_dice_rules,
        placeholder = R.string.games_dice_placeholder,
        opening = "Begin the battle and invite {{user}}'s first move.",
        replyFormat = "Dice battle turn format: encounter or action, dice roll and outcome, current battle state, then the player’s next move.",
    ),
    GameDefinition(
        id = "tactical",
        title = R.string.games_tactical_title,
        symbol = R.drawable.ic_rook,
        tagline = R.string.games_tactical_tagline,
        rules = R.string.games_tactical_rules,
        placeholder = R.string.games_tactical_placeholder,
        opening = "Set the battlefield for {{char}} and {{user}}.",
        replyFormat = "Tactical turn format: positions and current state, the action and its consequences, then the next command. Carry positions forward from previous turns.",
    ),
    GameDefinition(
        id = "truth_dare",
        title = R.string.games_truth_title,
        symbol = R.drawable.ic_diamond,
        tagline = R.string.games_truth_tagline,
        rules = R.string.games_truth_rules,
        placeholder = R.string.games_truth_placeholder,
        opening = "Start the round with {{user}} choosing TRUTH or DARE.",
        replyFormat = "Truth or dare turn format: current choice or challenge, response to the previous turn, and whose turn comes next.",
    ),
    GameDefinition(
        id = "two_truths",
        title = R.string.games_two_truths_title,
        symbol = R.drawable.ic_three,
        tagline = R.string.games_two_truths_tagline,
        rules = R.string.games_two_truths_rules,
        placeholder = R.string.games_two_truths_placeholder,
        opening = "Deal the first two-truths-and-a-lie round about {{char}} for {{user}}.",
        replyFormat = "Two truths and a lie round: three numbered statements with two truths and one hidden lie, then the player’s guess. After the guess, reveal the lie and explain the result before the next round.",
    ),
    GameDefinition(
        id = "oracle",
        title = R.string.games_oracle_title,
        symbol = R.drawable.ic_moon,
        tagline = R.string.games_oracle_tagline,
        rules = R.string.games_oracle_rules,
        placeholder = R.string.games_oracle_placeholder,
        opening = "Begin {{user}}'s reading with a question or a card.",
        replyFormat = "Oracle turn format: question or drawn card, followed by its reading in the current context.",
    ),
    GameDefinition(
        id = "would_you_rather",
        title = R.string.games_rather_title,
        symbol = R.drawable.ic_swap,
        tagline = R.string.games_rather_tagline,
        rules = R.string.games_rather_rules,
        placeholder = R.string.games_rather_placeholder,
        opening = "Present the first would-you-rather round to {{user}}.",
        replyFormat = "Would you rather round: two numbered options and the player’s choice. After a choice, include the character’s reaction and answer, followed by the next pair.",
    ),
)

internal enum class GameRole { USER, ASSISTANT }

internal data class GameMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: GameRole,
    val text: String = "",
    val imagePath: String? = null,
    val thoughtProcess: String = "",
    val imagePrompt: String = "",
)
