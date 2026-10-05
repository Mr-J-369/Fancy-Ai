package com.mrj.fancyai.ui.chat

import com.mrj.fancyai.service.llm.AssistantProtocol
import com.mrj.fancyai.service.llm.ImagePrompt
import com.mrj.fancyai.service.llm.LlmRequest
import android.Manifest
import android.content.Context
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mrj.fancyai.R
import com.mrj.fancyai.engine.LiteRtModel
import com.mrj.fancyai.service.llm.LlmEngineClient
import com.mrj.fancyai.service.llm.LlmInput
import com.mrj.fancyai.service.llm.MacroBus
import com.mrj.fancyai.service.llm.MacroException
import com.mrj.fancyai.service.llm.generatePromptImage
import com.mrj.fancyai.service.llm.llmErrorResource
import com.mrj.fancyai.service.memory.CharacterMemory
import com.mrj.fancyai.service.vision.VisionClient
import com.mrj.fancyai.service.vision.VisionException
import com.mrj.fancyai.ui.characters.CharacterCard
import com.mrj.fancyai.ui.kit.capitalizeFirstVisibleLetter
import com.mrj.fancyai.ui.profile.UserProfile
import com.mrj.fancyai.ui.settings.LlmSettings
import com.mrj.fancyai.ui.settings.LlmSettingsStore
import com.mrj.fancyai.ui.settings.SelectedEngine
import com.mrj.fancyai.ui.settings.VoiceEngineFactory
import com.mrj.fancyai.ui.settings.activeSystemPrompt
import com.mrj.fancyai.ui.settings.runtime
import com.mrj.fancyai.ui.settings.sessionConfig
import com.mrj.fancyai.ui.vision.messageResource
import com.mrj.fancyai.voice.TtsEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

internal class ChatController(
    val context: Context,
    val character: CharacterCard,
    val profile: UserProfile,
    userName: String,
    val scope: CoroutineScope,
) {
    enum class Phase { IDLE, READING_IMAGE, LOADING, GENERATING, GENERATING_IMAGE }
    val busy: Boolean get() = loading || generating || rebuilding
    val firstMessage = MacroBus(context, character, profile, userName).text(character.firstMessage)
    var modelAvailable by mutableStateOf(false)
        internal set
    var phase by mutableStateOf(Phase.IDLE)
        internal set
    var imageProgress by mutableIntStateOf(0)
        internal set
    var runningCommandTurnIndex by mutableStateOf<Int?>(null)
        internal set
    var activeCommandHandle by mutableStateOf<com.mrj.fancyai.terminal.ActiveCommandHandle?>(null)
        internal set
    lateinit var settings: LlmSettings
    var engine = LlmEngineClient(context)
    val macros = MacroBus(context, character, profile, userName)
    val memory = CharacterMemory(context, character.id)
    var vision: VisionClient? = null
    var nativeVision = false
    var cloudVisionChecked = false
    val stt = VoiceEngineFactory.stt(context)
    val recognitionAvailable = VoiceEngineFactory.sttAvailable(context)
    val ttsRef = AtomicReference<TtsEngine?>()
    val savedConversations = mutableMapOf<String, ChatConversation>()
    val deletingConversations = mutableSetOf<String>()
    var modelName by mutableStateOf("")
    var conversations by mutableStateOf(emptyList<ChatConversation>())
    var activeChatId by mutableStateOf("")
    val activeConversation get() = conversations.firstOrNull { it.id == activeChatId }
    var input by mutableStateOf("")
    var listening by mutableStateOf(false)
    var speechDraft by mutableStateOf("")
    var speechError by mutableIntStateOf(0)
    var recognitionSession by mutableIntStateOf(0)
    var speechRun = 0
    var speechJob: Job? = null
    var speakingReply by mutableStateOf<SpeakingReply?>(null)
    var ttsError by mutableStateOf<SpeakingReply?>(null)
    var loading by mutableStateOf(true)
    var generating by mutableStateOf(false)
    var thinking by mutableStateOf(false)
    var generation: Job? = null
    var loadJob: Job? = null
    var showConversations by mutableStateOf(false)
    var rebuilding by mutableStateOf(false)
    var attachedImagePath by mutableStateOf<String?>(null)
    var importingImage by mutableStateOf(false)
    var attachmentError by mutableIntStateOf(0)
    var operationError by mutableIntStateOf(0)

    init {
        loadJob = scope.launch { load() }
    }

    fun reportError(message: Int) {
        operationError = message
        Log.w("Chat", context.applicationContext.getString(message))
    }

    fun logLlmError(failure: Throwable, fallback: Int) {
        com.mrj.fancyai.util.AppLog.write(Log.ERROR, "Chat", "LLM operation failed", failure)
        reportError(when (failure) {
            is VisionException -> failure.failure.messageResource()
            is MacroException -> failure.resource
            else -> llmErrorResource(failure, fallback)
        })
    }

    fun updateInput(value: String) {
        input = value
        speechError = 0
        operationError = 0
        saveChatDraft(context, character.id, activeChatId, value)
    }

    suspend fun persistNow(conversationId: String) {
        chatPersistenceLock.withLock {
            if (conversationId in deletingConversations) return
            val current = conversations.firstOrNull { it.id == conversationId } ?: return
            if (savedConversations[current.id] == current) return
            withContext(NonCancellable + Dispatchers.IO) {
                writeConversation(context, character.id, current, savedConversations[current.id])
            }
            savedConversations[current.id] = current
        }
    }

    fun startReply(
        conversation: ChatConversation,
        message: String,
        imagePath: String? = null,
        imageDescription: String? = null,
        modelInput: LlmInput? = null,
        rebuild: Boolean,
        editedUser: Boolean = false,
    ) {
        if (!modelAvailable) return
        if (loading || generating || rebuilding) return
        stopListening()
        stopSpeaking()
        val chatId = conversation.id
        val thinkForReply = thinking
        val replacedTurnCount = conversations.firstOrNull { it.id == chatId }?.turns?.size ?: 0
        val updated = conversation.copy(
            title = conversation.title.ifBlank {
                message.lineSequence().first().ifBlank { context.getString(R.string.vision_image) }.take(CHAT_TITLE_LIMIT)
            },
            updatedAt = System.currentTimeMillis(),
            turns = conversation.turns + ChatTurn(
                user = message,
                userImagePath = imagePath,
                userImageDescription = imageDescription,
                modelInput = modelInput,
            ),
        )
        replaceConversation(updated)
        generating = true

        generation = scope.launch {
            try {
                persistNow(updated.id)
                if (rebuild) {
                    memory.deleteSessionFrom(chatId, conversation.turns.size)
                    for (index in conversation.turns.size until replacedTurnCount) {
                        saveMessageEditDraft(context, character.id, chatId, index, user = false, draft = "")
                        if (index > conversation.turns.size || editedUser) {
                            saveMessageEditDraft(context, character.id, chatId, index, user = true, draft = "")
                        }
                    }
                } else {
                    input = ""
                    attachedImagePath = null
                    saveChatDraft(context, character.id, chatId, "")
                    withContext(NonCancellable + Dispatchers.IO) {
                        chatPersistenceLock.withLock { saveAttachmentDraft(context, character.id, chatId, null) }
                    }
                }
                reply(updated, thinkForReply)
                conversations.firstOrNull { it.id == chatId }?.let { completed ->
                    persistNow(completed.id)
                    rememberReply(completed)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                logLlmError(failure, R.string.chat_engine_failed)
            } finally {
                withContext(NonCancellable) {
                    try {
                        persistNow(chatId)
                    } finally {
                        generating = false
                    }
                }
            }
        }
    }

    fun generateReplyImage(index: Int) {
        val conversation = activeConversation ?: return
        val turn = conversation.turns.getOrNull(index) ?: return
        if (loading || generating || rebuilding) return
        stopListening()
        stopSpeaking()
        generating = true

        generation = scope.launch {
            try {
                var currentTurn = turn
                var prompt = currentTurn.imagePromptText?.takeIf(String::isNotBlank)
                if (prompt == null) {
                    phase = Phase.GENERATING
                    val preferences = context.getSharedPreferences("assistant_protocol", Context.MODE_PRIVATE)
                    val rawInstruction = preferences.getString(
                        "requested_image_instruction",
                        context.getString(R.string.instructions_requested_image_default),
                    ).orEmpty()
                    val imageInstruction = macros.text(rawInstruction)
                    val systemPrompt = AssistantProtocol.systemInstruction(settings.runtime, macros, emptyList(), imageInstruction)
                    val subject = currentTurn.replyText.ifBlank { currentTurn.user }
                    val request = LlmRequest(
                        config = settings.sessionConfig(systemPrompt, openingMessage = "", history = emptyList()),
                        input = LlmInput(text = subject),
                        thinking = false,
                    )
                    val output = StringBuilder()
                    engine.generate(request).collect { chunk ->
                        output.append(chunk.text)
                    }
                    val generatedPrompt = ImagePrompt.split(output.toString(), imageOnly = true).second?.takeIf(String::isNotBlank)
                        ?: subject
                    prompt = generatedPrompt
                    currentTurn = currentTurn.copy(
                        assistant = currentTurn.assistant + "\n\n<scene_prompt>$generatedPrompt</scene_prompt>",
                    )
                }
                phase = Phase.GENERATING_IMAGE
                val image = generatePromptImage(context, prompt, freshSeed = true, characterId = character.id) { imageProgress = it }
                val imagePath = withContext(Dispatchers.IO) { saveGeneratedChatImage(context, image) }
                withContext(NonCancellable) {
                    val current = conversations.firstOrNull { it.id == conversation.id } ?: return@withContext
                    val updated = current.copy(
                        updatedAt = System.currentTimeMillis(),
                        turns = current.turns.mapIndexed { i, item -> if (i == index) currentTurn.copy(imagePath = imagePath) else item },
                    )
                    replaceConversation(updated)
                    persistNow(updated.id)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                logLlmError(failure, R.string.aura_generation_failed)
            } finally {
                phase = Phase.IDLE
                generating = false
            }
        }
    }

    private var runningCommandJob: Job? = null

    fun executeSudoCommand(index: Int) {
        val conversation = activeConversation ?: return
        val turn = conversation.turns.getOrNull(index) ?: return
        val command = turn.sudoCommand ?: return
        if (runningCommandTurnIndex != null) return

        runningCommandTurnIndex = index

        val cleared = conversation.copy(
            updatedAt = System.currentTimeMillis(),
            turns = conversation.turns.mapIndexed { i, item ->
                if (i == index) item.copy(commandOutput = null, commandExitCode = null) else item
            },
        )
        replaceConversation(cleared)

        runningCommandJob = scope.launch {
            try {
                val workspace = com.mrj.fancyai.terminal.TerminalWorkspace.get(context)
                val result = com.mrj.fancyai.terminal.TerminalCommandRunner.execute(
                    environments = workspace.environments,
                    distribution = com.mrj.fancyai.terminal.LinuxDistribution.UBUNTU,
                    command = command,
                    onHandleReady = { handle -> activeCommandHandle = handle },
                    onOutput = { liveOutput ->
                        val current = conversations.firstOrNull { it.id == conversation.id } ?: return@execute
                        val updated = current.copy(
                            updatedAt = System.currentTimeMillis(),
                            turns = current.turns.mapIndexed { i, item ->
                                if (i == index) item.copy(commandOutput = liveOutput) else item
                            },
                        )
                        replaceConversation(updated)
                    },
                )
                withContext(NonCancellable) {
                    val current = conversations.firstOrNull { it.id == conversation.id } ?: return@withContext
                    val updated = current.copy(
                        updatedAt = System.currentTimeMillis(),
                        turns = current.turns.mapIndexed { i, item ->
                            if (i == index) item.copy(commandOutput = result.output, commandExitCode = result.exitCode) else item
                        },
                    )
                    replaceConversation(updated)
                    persistNow(updated.id)
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    val current = conversations.firstOrNull { it.id == conversation.id } ?: return@withContext
                    val turnOutput = current.turns.getOrNull(index)?.commandOutput
                    val finalOutput = if (turnOutput.isNullOrEmpty()) "[Command cancelled]" else "$turnOutput\n\n[Command cancelled]"
                    val updated = current.copy(
                        updatedAt = System.currentTimeMillis(),
                        turns = current.turns.mapIndexed { i, item ->
                            if (i == index) item.copy(commandOutput = finalOutput, commandExitCode = 130) else item
                        },
                    )
                    replaceConversation(updated)
                    persistNow(updated.id)
                }
                throw cancelled
            } catch (failure: Exception) {
                withContext(NonCancellable) {
                    val current = conversations.firstOrNull { it.id == conversation.id } ?: return@withContext
                    val updated = current.copy(
                        updatedAt = System.currentTimeMillis(),
                        turns = current.turns.mapIndexed { i, item ->
                            if (i == index) item.copy(commandOutput = failure.message ?: "Execution failed", commandExitCode = 1) else item
                        },
                    )
                    replaceConversation(updated)
                    persistNow(updated.id)
                }
            } finally {
                activeCommandHandle = null
                runningCommandTurnIndex = null
                runningCommandJob = null
            }
        }
    }

    fun sendCommandLine(line: String) {
        activeCommandHandle?.sendLine(line)
    }

    fun sendCommandInterrupt() {
        activeCommandHandle?.sendInterrupt() ?: cancelSudoCommand()
    }

    fun sendCommandUp() {
        activeCommandHandle?.sendUpArrow()
    }

    fun sendCommandDown() {
        activeCommandHandle?.sendDownArrow()
    }

    fun cancelSudoCommand() {
        activeCommandHandle?.sendInterrupt()
        runningCommandJob?.cancel()
        runningCommandJob = null
        activeCommandHandle = null
        runningCommandTurnIndex = null
    }

    fun stop() {
        generating = false
        phase = Phase.IDLE
        imageProgress = 0
        vision?.cancel()
        engine.cancel()
        generation?.cancel()
        generation = null
        cancelSudoCommand()
    }

    suspend fun load() {
        loading = true
        modelAvailable = false
        engine.close()
        vision?.close()
        vision = null
        engine = LlmEngineClient(context)
        cloudVisionChecked = false
        try {
            val stored = withContext(Dispatchers.IO) {
                val stored = readConversations(context, character.id).ifEmpty {
                    val empty = ChatConversation(id = UUID.randomUUID().toString())
                    writeConversation(context, character.id, empty)
                    listOf(empty)
                }
                settings = checkNotNull(LlmSettingsStore.snapshot(context))
                stored
            }
            conversations = stored
            savedConversations.clear()
            savedConversations.putAll(stored.associateBy(ChatConversation::id))
            val first = conversations.maxBy(ChatConversation::updatedAt)
            activeChatId = first.id
            input = readChatDraft(context, character.id, first.id)
            attachedImagePath = readAttachmentDraft(context, character.id, first.id)
            modelName = settings.engine.name
            nativeVision = ((settings.engine as? SelectedEngine)?.model as? LiteRtModel)?.supportsVision == true
            phase = Phase.LOADING
            try {
                engine.prepare(settings.sessionConfig(AssistantProtocol.systemInstruction(settings.runtime, macros, listOf(activeSystemPrompt(context).instruction)), firstMessage))
                modelAvailable = true
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                modelAvailable = false
                logLlmError(failure, R.string.chat_engine_failed)
            } finally {
                phase = Phase.IDLE
            }
        } finally {
            loading = false
        }
    }

    fun close() {
        loadJob?.cancel()
        stopSpeaking()
        stt.release()
        ttsRef.getAndSet(null)?.stop()
        stop()
        vision?.close()
        engine.close()
        modelAvailable = false
    }
}

internal fun ChatController.stopListening() {
    recognitionSession++
    listening = false
    stt.cancel()
}

@RequiresPermission(Manifest.permission.RECORD_AUDIO)
internal fun ChatController.startListening() {
    if (listening || loading || generating || rebuilding || importingImage || !recognitionAvailable || activeChatId.isEmpty()) return
    val session = recognitionSession + 1
    recognitionSession = session
    speechDraft = input
    speechError = 0
    listening = true
    scope.launch {
        try {
            val result = stt.listen()
            if ((session == recognitionSession) && result.isNotBlank()) {
                updateInput(appendSpeechToDraft(speechDraft, result))
            }
        } finally {
            if (session == recognitionSession) {
                listening = false
            }
        }
    }
}

internal fun ChatController.stopSpeaking() {
    speechRun++
    speechJob?.cancel()
    speechJob = null
    ttsRef.get()?.stop()
    speakingReply = null
    ttsError = null
}

internal fun ChatController.toggleSpeaking(index: Int, text: String) {
    val reply = SpeakingReply(activeChatId, index)
    if (speakingReply == reply) {
        stopSpeaking()
        return
    }
    stopSpeaking()
    val run = speechRun
    speakingReply = reply
    speechJob = scope.launch {
        try {
            val engine = ttsRef.get() ?: VoiceEngineFactory.tts(context, character.id).also(ttsRef::set)
            if (run != speechRun) return@launch
            engine.speak(text.capitalizeFirstVisibleLetter())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            if (run == speechRun) ttsError = reply
        } finally {
            if (run == speechRun) {
                speakingReply = null
                speechJob = null
            }
        }
    }
}
