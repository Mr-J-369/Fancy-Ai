package com.mrj.fancyai.ui.phone

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mrj.fancyai.data.db.entity.CharacterEntity
import com.mrj.fancyai.data.db.entity.MessageEntity
import com.mrj.fancyai.data.repository.MediaRepository
import com.mrj.fancyai.data.repository.MessengerRepository
import com.mrj.fancyai.data.repository.SettingsRepository
import com.mrj.fancyai.domain.inference.ChatTemplate
import com.mrj.fancyai.domain.inference.ChatTurn
import com.mrj.fancyai.domain.inference.LlamaEngine
import com.mrj.fancyai.domain.inference.PromptBuilder
import com.mrj.fancyai.domain.inference.PromptContext
import com.mrj.fancyai.domain.inference.UserProfile
import com.mrj.fancyai.service.VoiceService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class PhoneViewModel(
    private val charId: String,
    private val messengerRepository: MessengerRepository,
    private val mediaRepository: MediaRepository,
    private val settingsRepository: SettingsRepository,
    private val llamaEngine: LlamaEngine,
    private val voiceService: VoiceService
) : ViewModel() {

    private val _character = MutableStateFlow<CharacterEntity?>(null)
    val character: StateFlow<CharacterEntity?> = _character.asStateFlow()

    init {
        viewModelScope.launch {
            messengerRepository.getCharacter(charId).collectLatest { _character.value = it }
        }
    }

    fun resolveAvatar(ref: String?): File? = ref?.let { mediaRepository.resolveToFile(it) }

    var isCallActive by mutableStateOf(false)
        private set

    var isListening by mutableStateOf(false)
        private set

    var userText by mutableStateOf("")
        private set

    var aiResponse by mutableStateOf("")
        private set

    var callDuration by mutableStateOf(0)
        private set

    private var callStartTime = 0L

    fun startCall() {
        isCallActive = true
        callStartTime = System.currentTimeMillis()

        viewModelScope.launch {
            voiceService.isListening.collect { listening ->
                isListening = listening
            }
        }

        viewModelScope.launch {
            voiceService.recognizedText.collect { text ->
                userText = text
                processUserInput(text)
            }
        }
    }

    fun endCall() {
        isCallActive = false
        voiceService.stopListening()
        voiceService.stopSpeaking()
    }

    fun startListening() {
        voiceService.startListening()
    }

    fun stopListening() {
        voiceService.stopListening()
    }

    private fun processUserInput(text: String) {
        viewModelScope.launch {
            if (text.isBlank()) return@launch

            isListening = false
            aiResponse = ""

            try {
                val character = messengerRepository.getCharacter(charId).first()
                if (character == null) {
                    aiResponse = "No character to call."
                    return@launch
                }

                val history = messengerRepository.getMessages(charId).first()
                    .takeLast(settingsRepository.historyCap)
                    .map {
                        val role = if (it.sender == "user") ChatTurn.Role.USER else ChatTurn.Role.ASSISTANT
                        ChatTurn(role, it.text)
                    }
                val dossier = messengerRepository.getDossier(charId).first()?.dossierJson
                val memories = messengerRepository.getMemories(charId).first().map { it.text }

                val prompt = PromptBuilder.build(
                    context = PromptContext.CHAT,
                    character = character,
                    user = UserProfile(settingsRepository.userName, settingsRepository.userBio),
                    userText = text,
                    history = history,
                    dossierJson = dossier,
                    memories = memories,
                    globalGuidelines = settingsRepository.systemPrompt,
                    template = ChatTemplate.from(settingsRepository.chatTemplate)
                )

                val reply = StringBuilder()
                llamaEngine.generateStream(
                    prompt = prompt,
                    maxTokens = settingsRepository.maxTokens,
                    temperature = settingsRepository.temperature,
                    topK = settingsRepository.topK,
                    topP = settingsRepository.topP
                ).collect { token ->
                    reply.append(token)
                    aiResponse = reply.toString()
                }

                val answer = reply.toString().trim()
                if (answer.isNotEmpty()) {
                    voiceService.speak(answer)

                    messengerRepository.insertMessage(
                        MessageEntity(
                            id = UUID.randomUUID().toString(),
                            charId = charId,
                            sender = "user",
                            text = text,
                            type = "text",
                            timestamp = System.currentTimeMillis()
                        )
                    )
                    messengerRepository.insertMessage(
                        MessageEntity(
                            id = UUID.randomUUID().toString(),
                            charId = charId,
                            sender = "ai",
                            text = answer,
                            type = "text",
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }

                userText = ""
            } catch (e: Exception) {
                aiResponse = "Error: ${e.message}"
            }
        }
    }

    override fun onCleared() {
        voiceService.stopListening()
        voiceService.stopSpeaking()
        super.onCleared()
    }
}
