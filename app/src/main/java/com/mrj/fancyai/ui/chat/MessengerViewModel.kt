package com.mrj.fancyai.ui.chat

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mrj.fancyai.data.db.entity.CharacterEntity
import com.mrj.fancyai.data.db.entity.DossierEntity
import com.mrj.fancyai.data.db.entity.MemoryEntity
import com.mrj.fancyai.data.db.entity.MessageEntity
import com.mrj.fancyai.data.repository.InboxItem
import com.mrj.fancyai.data.repository.MessengerRepository
import com.mrj.fancyai.data.repository.SettingsRepository
import com.mrj.fancyai.data.repository.MediaRepository
import com.mrj.fancyai.domain.inference.ChatTemplate
import com.mrj.fancyai.domain.inference.ChatTurn
import com.mrj.fancyai.domain.inference.LlamaEngine
import com.mrj.fancyai.domain.inference.ModelLoader
import com.mrj.fancyai.domain.inference.PromptBuilder
import com.mrj.fancyai.domain.inference.PromptContext
import com.mrj.fancyai.domain.inference.UserProfile
import com.mrj.fancyai.service.AutoGenerationService
import com.mrj.fancyai.service.ChatMessage
import com.mrj.fancyai.service.CloudLlmService
import com.mrj.fancyai.service.ImageService
import com.mrj.fancyai.util.CharacterCardParser
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.InputStream
import java.util.UUID

class MessengerViewModel(
    private val repository: MessengerRepository,
    private val mediaRepository: com.mrj.fancyai.data.repository.MediaRepository,
    private val settingsRepository: SettingsRepository,
    private val llamaEngine: LlamaEngine,
    private val autoGenService: AutoGenerationService,
    private val modelLoader: ModelLoader,
    private val cloudLlmService: CloudLlmService,
    private val imageService: ImageService
) : ViewModel() {

    /** Surfaced to the chat UI so it can show "loading model…" before the first reply. */
    var modelLoading by mutableStateOf(false)
        private set

    /** True while a character "photo" is being generated, so the UI can show a status bubble. */
    var photoGenerating by mutableStateOf(false)
        private set

    /** True while a character avatar is being generated from its description. */
    var avatarGenerating by mutableStateOf(false)
        private set

    /**
     * Live generation speed in tokens/sec for the active reply. Updated while streaming and
     * retained afterwards so the chat header can show the last run's rate. Null until the
     * first reply of the session.
     */
    var tokensPerSec by mutableStateOf<Float?>(null)
        private set

    // Inbox state
    val inboxItems: Flow<List<InboxItem>> = repository.getInboxItems()

    // Active Chat state
    private val _messages = MutableStateFlow<List<MessageEntity>>(emptyList())
    val messages: StateFlow<List<MessageEntity>> = _messages.asStateFlow()

    private val _activeCharacter = MutableStateFlow<CharacterEntity?>(null)
    val activeCharacter: StateFlow<CharacterEntity?> = _activeCharacter.asStateFlow()

    private val _activeMemories = MutableStateFlow<List<MemoryEntity>>(emptyList())
    val activeMemories: StateFlow<List<MemoryEntity>> = _activeMemories.asStateFlow()

    private val _activeDossier = MutableStateFlow<DossierEntity?>(null)
    val activeDossier: StateFlow<DossierEntity?> = _activeDossier.asStateFlow()

    var streamingText by mutableStateOf("")
        private set

    var isLoading by mutableStateOf(false)
        private set

    /**
     * Collectors for the currently-open conversation. Each `loadConversation` would
     * otherwise leak a new set of forever-running collectors, and opening a second chat
     * left the previous character's collectors alive — all writing to the same StateFlows.
     * That race is what made messages, sends, and deletes land on the wrong character.
     * We cancel the prior set before subscribing to the new one.
     */
    private val conversationJobs = mutableListOf<Job>()

    fun loadConversation(charId: String) {
        // Lazily bring the local model online when a chat is opened (cloud providers
        // skip this). Loading a multi-GB model is why we don't do it on app launch.
        ensureLocalModelLoaded()

        // Tear down the previous conversation's collectors and clear stale state so the
        // new chat never momentarily shows another character's data.
        conversationJobs.forEach { it.cancel() }
        conversationJobs.clear()
        _activeCharacter.value = null
        _messages.value = emptyList()
        _activeMemories.value = emptyList()
        _activeDossier.value = null

        conversationJobs += viewModelScope.launch {
            repository.getCharacter(charId).collectLatest {
                _activeCharacter.value = it
            }
        }
        conversationJobs += viewModelScope.launch {
            repository.getMessages(charId).collectLatest {
                _messages.value = it
            }
        }
        conversationJobs += viewModelScope.launch {
            repository.getMemories(charId).collectLatest {
                _activeMemories.value = it
            }
        }
        conversationJobs += viewModelScope.launch {
            repository.getDossier(charId).collectLatest {
                _activeDossier.value = it
            }
        }
    }

    /** Loads the configured local model on demand (no-op for cloud providers or if already loaded). */
    private fun ensureLocalModelLoaded() {
        if (settingsRepository.llmProvider != "llama") return
        if (modelLoading || llamaEngine.isModelLoaded()) return
        modelLoading = true
        viewModelScope.launch {
            try {
                modelLoader.autoLoadLast()
            } finally {
                modelLoading = false
            }
        }
    }

    /** True when the active provider can accept image input (any cloud/HTTP provider). */
    val canAttachImages: Boolean get() = settingsRepository.llmProvider != "llama"

    fun sendMessage(userText: String) {
        val character = _activeCharacter.value ?: return
        if (userText.isBlank() || isLoading) return

        // Capture the conversation *before* the new turn so it becomes the prompt history.
        val priorHistory = _messages.value.toChatTurns(settingsRepository.historyCap)

        viewModelScope.launch {
            repository.insertMessage(
                MessageEntity(
                    id = UUID.randomUUID().toString(),
                    charId = character.id,
                    sender = "user",
                    text = userText,
                    type = "text",
                    timestamp = System.currentTimeMillis()
                )
            )
            runGeneration(character, userText, priorHistory)
        }
    }

    /**
     * Sends a message with an attached image to a vision-capable cloud model. The image is
     * stored (and shown in the thread) and forwarded as a base64 data URL on the user turn.
     * On-device llama has no VLM yet, so the image is kept in the thread but not sent to it.
     */
    fun sendMessageWithImage(userText: String, imageUri: android.net.Uri, context: android.content.Context) {
        val character = _activeCharacter.value ?: return
        if (isLoading) return

        val priorHistory = _messages.value.toChatTurns(settingsRepository.historyCap)

        viewModelScope.launch {
            val ref = mediaRepository.importImage(context, imageUri)
            if (ref == null) {
                insertError(character.id, "⚠️ Couldn't load that image.")
                return@launch
            }
            val now = System.currentTimeMillis()
            repository.insertMessage(
                MessageEntity(
                    id = UUID.randomUUID().toString(),
                    charId = character.id,
                    sender = "user",
                    text = ref,
                    type = "image",
                    timestamp = now
                )
            )
            if (userText.isNotBlank()) {
                repository.insertMessage(
                    MessageEntity(
                        id = UUID.randomUUID().toString(),
                        charId = character.id,
                        sender = "user",
                        text = userText,
                        type = "text",
                        timestamp = now + 1
                    )
                )
            }
            val dataUrl = if (canAttachImages) mediaRepository.resolveToJpegDataUrl(ref) else null
            runGeneration(character, userText, priorHistory, attachmentDataUrl = dataUrl)
        }
    }

    /**
     * Deletes a single message. Used by the message long-press menu and by regenerate.
     */
    fun deleteMessage(message: MessageEntity) {
        viewModelScope.launch { repository.deleteMessage(message) }
    }

    /** Deletes all currently-selected messages (multi-select). */
    fun deleteMessages(ids: Set<String>) {
        if (ids.isEmpty()) return
        val toDelete = _messages.value.filter { it.id in ids }
        viewModelScope.launch { toDelete.forEach { repository.deleteMessage(it) } }
    }

    /**
     * Regenerates an AI reply: removes the selected message, then re-runs generation
     * against the most recent user turn using the remaining history.
     */
    fun regenerate(message: MessageEntity) {
        val character = _activeCharacter.value ?: return
        if (isLoading) return
        viewModelScope.launch {
            repository.deleteMessage(message)
            val remaining = _messages.value.filter { it.id != message.id }
            val lastUser = remaining.filter { it.sender == "user" }.maxByOrNull { it.timestamp } ?: return@launch
            val history = remaining
                .filter { it.timestamp < lastUser.timestamp }
                .toChatTurns(settingsRepository.historyCap)
            runGeneration(character, lastUser.text, history)
        }
    }

    /** Core generation: streams a reply, persists it, and handles "flux prompt:" photos. */
    private suspend fun runGeneration(
        character: CharacterEntity,
        userText: String,
        priorHistory: List<ChatTurn>,
        attachmentDataUrl: String? = null
    ) {
            isLoading = true
            streamingText = ""

            val provider = settingsRepository.llmProvider
            val user = UserProfile(settingsRepository.userName, settingsRepository.userBio)
            val dossierJson = _activeDossier.value?.dossierJson
            val memories = _activeMemories.value.map { it.text }

            // On-device builds one templated prompt string; cloud/HTTP providers take an
            // OpenAI-style messages array assembled from the same PromptBuilder context.
            val tokenFlow: Flow<String> = if (provider == "llama") {
                val prompt = PromptBuilder.build(
                    context = PromptContext.CHAT,
                    character = character,
                    user = user,
                    userText = userText,
                    history = priorHistory,
                    dossierJson = dossierJson,
                    memories = memories,
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
                    context = PromptContext.CHAT,
                    character = character,
                    user = user,
                    dossierJson = dossierJson,
                    memories = memories,
                    globalGuidelines = settingsRepository.systemPrompt
                )
                val messages = buildList {
                    add(ChatMessage("system", system))
                    priorHistory.forEach { turn ->
                        val role = if (turn.role == ChatTurn.Role.USER) "user" else "assistant"
                        add(ChatMessage(role, PromptBuilder.applyMacros(turn.content, character.name, user.name)))
                    }
                    add(
                        ChatMessage(
                            role = "user",
                            content = PromptBuilder.applyMacros(userText, character.name, user.name),
                            images = listOfNotNull(attachmentDataUrl)
                        )
                    )
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
                val startNanos = System.nanoTime()
                var tokenCount = 0
                tokenFlow.collect { token ->
                    reply.append(token)
                    tokenCount++
                    val elapsedSec = (System.nanoTime() - startNanos) / 1_000_000_000f
                    if (elapsedSec > 0f) tokensPerSec = tokenCount / elapsedSec
                    streamingText = cleanReply(reply.toString())
                }
                streamingText = "" // reply is complete; hand off to the persisted message

                val raw = cleanReply(reply.toString())
                // Split off a trailing "flux prompt: …" photo request, if present. Tolerant
                // of markdown/spacing the model may add, e.g. "**Flux Prompt:**".
                val fluxRegex = Regex("""\*{0,3}\s*flux\s*prompt\s*:\s*\*{0,3}""", RegexOption.IGNORE_CASE)
                val fluxMatch = fluxRegex.find(raw)
                val text = (if (fluxMatch != null) raw.substring(0, fluxMatch.range.first) else raw).trim()
                val imagePrompt = fluxMatch?.let {
                    raw.substring(it.range.last + 1).trim().removeSurrounding("[", "]").trim()
                }?.takeIf { it.isNotBlank() }

                if (text.isNotEmpty()) {
                    repository.insertMessage(
                        MessageEntity(
                            id = UUID.randomUUID().toString(),
                            charId = character.id,
                            sender = "ai",
                            text = text,
                            type = "text",
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }

                if (!imagePrompt.isNullOrBlank()) {
                    generatePhoto(character.id, imagePrompt)
                }
            } catch (e: Exception) {
                Log.e("MessengerViewModel", "Generation failed", e)
                repository.insertMessage(
                    MessageEntity(
                        id = UUID.randomUUID().toString(),
                        charId = character.id,
                        sender = "ai",
                        text = "⚠️ ${e.message ?: "Generation failed"}",
                        type = "text",
                        timestamp = System.currentTimeMillis()
                    )
                )
            } finally {
                streamingText = ""
                isLoading = false
            }
    }

    /** Generates a character "photo" from a flux prompt and posts it as an image message. */
    private suspend fun generatePhoto(charId: String, prompt: String) {
        photoGenerating = true
        try {
            val params = mapOf(
                "width" to settingsRepository.imgWidth,
                "height" to settingsRepository.imgHeight,
                "steps" to settingsRepository.imgSteps,
                "cfg" to settingsRepository.imgCfg
            )
            imageService.generate(prompt, params, null)
                .onSuccess { gen ->
                    val ref = gen.ref
                    if (ref != null) {
                        repository.insertMessage(
                            MessageEntity(
                                id = UUID.randomUUID().toString(),
                                charId = charId,
                                sender = "ai",
                                text = ref,
                                type = "image",
                                timestamp = System.currentTimeMillis()
                            )
                        )
                    } else {
                        insertError(charId, "⚠️ Couldn't save the generated photo.")
                    }
                }
                .onFailure {
                    Log.e("MessengerViewModel", "Photo generation failed", it)
                    insertError(charId, "⚠️ Couldn't send photo: ${it.message ?: "generation failed"}")
                }
        } finally {
            photoGenerating = false
        }
    }

    private suspend fun insertError(charId: String, text: String) {
        repository.insertMessage(
            MessageEntity(
                id = UUID.randomUUID().toString(),
                charId = charId,
                sender = "ai",
                text = text,
                type = "text",
                timestamp = System.currentTimeMillis()
            )
        )
    }

    /** Resolves a stored `db:` image reference to a file for display. */
    fun resolveImageRef(ref: String): File? = mediaRepository.resolveToFile(ref)

    /**
     * Trims chat-template control tokens that some models emit as literal text when the
     * configured template doesn't exactly match the model (e.g. a ChatML template on a
     * Llama-3 model). Cuts at the first stop token, then drops any dangling fragment.
     */
    private fun cleanReply(raw: String): String {
        var s = raw
        val stops = listOf(
            "<|im_end|>", "<|eot_id|>", "<|end_of_text|>", "<|endoftext|>",
            "<|im_start|>", "<|start_header_id|>", "<end_of_turn>", "</s>"
        )
        for (stop in stops) {
            val idx = s.indexOf(stop)
            if (idx >= 0) s = s.substring(0, idx)
        }
        // Remove a trailing, incomplete special-token fragment like "<|" or "<|im_en".
        s = s.replace(Regex("<\\|[^>]*$"), "")
        return s.trim()
    }

    /**
     * Maps stored messages to template-agnostic turns, keeping only the most recent [cap]
     * in chronological (oldest-first) order. Messages arrive newest-first from the DB, and
     * image messages are excluded since their text is a `db:` reference, not prose.
     */
    private fun List<MessageEntity>.toChatTurns(cap: Int): List<ChatTurn> =
        filter { it.type != "image" }
            .sortedBy { it.timestamp }
            .takeLast(cap)
            .map {
                val role = if (it.sender == "user") ChatTurn.Role.USER else ChatTurn.Role.ASSISTANT
                ChatTurn(role, it.text)
            }

    /** Whether NSFW (and thus the Rebbit opt-in) is available; mirrors the global switch. */
    val nsfwEnabled: Boolean get() = settingsRepository.nsfwEnabled

    fun saveCharacter(character: CharacterEntity) {
        viewModelScope.launch {
            repository.saveCharacter(character)
        }
    }

    /** Toggles a single auto-post platform opt-in for the active character and persists it. */
    fun toggleAutoPost(platform: String, enabled: Boolean) {
        val char = _activeCharacter.value ?: return
        val updated = when (platform) {
            "ustagram" -> char.copy(autoPostUstagram = enabled)
            "rebbit" -> char.copy(autoPostRebbit = enabled)
            "y" -> char.copy(autoPostY = enabled)
            else -> return
        }
        viewModelScope.launch { repository.saveCharacter(updated) }
    }

    /**
     * Generates an avatar for the active character from its own description (persona + bio)
     * using the configured image backend, then stores it as the character's `avatarRef`.
     */
    fun generateAvatar() {
        val char = _activeCharacter.value ?: return
        if (avatarGenerating) return
        avatarGenerating = true
        viewModelScope.launch {
            try {
                val description = listOf(char.persona, char.bio)
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .joinToString(". ")
                val prompt = buildString {
                    append("portrait headshot of ${char.name}")
                    if (description.isNotBlank()) append(", $description")
                    append(", detailed face, high quality, photorealistic")
                }
                val params = mapOf(
                    "width" to settingsRepository.imgWidth,
                    "height" to settingsRepository.imgHeight,
                    "steps" to settingsRepository.imgSteps,
                    "cfg" to settingsRepository.imgCfg
                )
                imageService.generate(prompt, params, null)
                    .onSuccess { gen ->
                        val ref = gen.ref
                        if (ref != null) {
                            repository.saveCharacter(char.copy(avatarRef = ref))
                        } else {
                            Log.w("MessengerViewModel", "Avatar generated but could not be saved")
                        }
                    }
                    .onFailure { Log.e("MessengerViewModel", "Avatar generation failed", it) }
            } finally {
                avatarGenerating = false
            }
        }
    }

    fun deleteCharacter(charId: String) {
        viewModelScope.launch {
            repository.deleteCharacter(charId)
        }
    }

    /** Creates a brand-new character from manual form input. */
    fun createCharacter(name: String, handle: String, bio: String, persona: String) {
        val cleanName = name.trim().ifBlank { return }
        viewModelScope.launch {
            repository.saveCharacter(
                CharacterEntity(
                    id = UUID.randomUUID().toString(),
                    name = cleanName,
                    handle = handle.trim(),
                    bio = bio.trim(),
                    persona = persona.trim()
                )
            )
        }
    }

    fun clearChat(charId: String) {
        viewModelScope.launch {
            repository.clearChat(charId)
        }
    }

    fun deleteMemory(memoryId: String) {
        viewModelScope.launch {
            repository.deleteMemory(memoryId)
        }
    }

    fun addManualMemory(text: String, category: String = "important") {
        val charId = _activeCharacter.value?.id ?: return
        viewModelScope.launch {
            repository.saveMemory(
                MemoryEntity(
                    id = UUID.randomUUID().toString(),
                    charId = charId,
                    text = text,
                    category = category
                )
            )
        }
    }

    fun importCharacter(inputStream: InputStream, fileName: String, context: android.content.Context) {
        viewModelScope.launch {
            val parsed = CharacterCardParser.parse(context, inputStream, fileName) ?: return@launch
            
            var character = parsed.entity
            if (parsed.avatar != null) {
                val avatarUri = mediaRepository.saveBitmap(parsed.avatar, "avatar")
                character = character.copy(avatarRef = avatarUri.toString())
            }
            
            repository.saveCharacter(character)
        }
    }

    /** True while the AI is rewriting the active character's dossier. */
    var dossierEvolving by mutableStateOf(false)
        private set

    /** One-shot user-facing result of the last evolve (success/skip/error); UI clears it. */
    var evolveMessage by mutableStateOf<String?>(null)
        private set

    fun clearEvolveMessage() { evolveMessage = null }

    fun evolveActiveCharacterDossier() {
        val char = _activeCharacter.value ?: return
        if (dossierEvolving) return

        // Messages arrive newest-first from the DB; sort chronologically before taking the
        // most recent turns, or the engine gets the oldest 15 in reverse order.
        val recent = _messages.value
            .filter { it.type != "image" }
            .sortedBy { it.timestamp }
            .takeLast(15)
        if (recent.isEmpty()) {
            evolveMessage = "No conversation yet for ${char.name} to learn from."
            return
        }

        // On-device evolution can't run until the model is loaded.
        if (settingsRepository.llmProvider == "llama" && !llamaEngine.isModelLoaded()) {
            ensureLocalModelLoaded()
            evolveMessage = "Model is still loading — try again in a moment."
            return
        }

        val chatHistory = recent.joinToString("\n") { "${it.sender}: ${it.text}" }
        val current = _activeDossier.value?.dossierJson ?: "{}"

        dossierEvolving = true
        viewModelScope.launch {
            Log.d("MessengerViewModel", "Triggering dossier evolution for ${char.name}")
            try {
                val evolved = autoGenService.evolveDossier(char.name, chatHistory, current)
                val sanitized = sanitizeDossierJson(evolved)
                if (sanitized != null) {
                    repository.saveDossier(DossierEntity(charId = char.id, dossierJson = sanitized))
                    evolveMessage = "Memory updated."
                } else {
                    Log.w("MessengerViewModel", "Dossier evolution returned non-JSON; keeping existing dossier")
                    evolveMessage = "Couldn't read the AI's response — memory left unchanged."
                }
            } catch (e: Exception) {
                Log.e("MessengerViewModel", "Dossier evolution failed", e)
                evolveMessage = "Evolve failed: ${e.message ?: "unknown error"}"
            } finally {
                dossierEvolving = false
            }
        }
    }

    /** Extracts and validates a JSON object from a model reply; null if unparseable. */
    private fun sanitizeDossierJson(raw: String): String? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start == -1 || end <= start) return null
        val candidate = raw.substring(start, end + 1)
        return try {
            org.json.JSONObject(candidate)
            candidate
        } catch (e: Exception) {
            null
        }
    }
}
