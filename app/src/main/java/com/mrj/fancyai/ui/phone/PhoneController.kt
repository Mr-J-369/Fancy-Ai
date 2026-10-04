package com.mrj.fancyai.ui.phone

import android.Manifest
import android.content.Context
import android.os.SystemClock
import android.speech.SpeechRecognizer
import android.util.AtomicFile
import androidx.annotation.RequiresPermission
import androidx.annotation.StringRes
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.mrj.fancyai.R
import com.mrj.fancyai.service.llm.AssistantProtocol
import com.mrj.fancyai.service.llm.AssistantTurn
import com.mrj.fancyai.service.llm.ImagePrompt
import com.mrj.fancyai.service.llm.LlmEngineClient
import com.mrj.fancyai.service.llm.LlmExchange
import com.mrj.fancyai.service.llm.LlmRequest
import com.mrj.fancyai.service.llm.MacroBus
import com.mrj.fancyai.service.llm.generatePromptImage
import com.mrj.fancyai.service.memory.CharacterMemory
import com.mrj.fancyai.service.voice.VoiceTtsEngine
import com.mrj.fancyai.ui.characters.CharacterCard
import com.mrj.fancyai.ui.kit.capitalizeFirstVisibleLetter
import com.mrj.fancyai.ui.lorebook.lorebookContext
import com.mrj.fancyai.ui.profile.UserProfile
import com.mrj.fancyai.ui.settings.LlmSettings
import com.mrj.fancyai.ui.settings.LlmSettingsStore
import com.mrj.fancyai.ui.settings.VoiceEngineFactory
import com.mrj.fancyai.ui.settings.runtime
import com.mrj.fancyai.ui.settings.sessionConfig
import com.mrj.fancyai.util.writeAtomicFile
import com.mrj.fancyai.voice.SpeechRecognitionException
import com.mrj.fancyai.voice.TtsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

private const val PHONE_INSTRUCTION =
    "You are {{char}} speaking with {{user}} on a phone call. Reply only with the words you would say aloud. " +
    "Stay in character and keep the conversation natural. Do not include narration, scene descriptions, stage " +
    "directions, internal thoughts, speaker labels, or scene_prompt blocks. Do not write the other person's replies; " +
    "leave them room to answer."

private val phoneJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

internal enum class PhoneCallStatus { INTERRUPTED, ENDED, CANCELLED, FAILED }

@Serializable
internal data class PhoneHistoryEntry(
    val id: String = "",
    val characterId: String = "",
    val name: String = "",
    val startedAt: Long = 0,
    val durationSeconds: Long = 0,
    val status: PhoneCallStatus = PhoneCallStatus.INTERRUPTED,
)

@Serializable
internal data class PhoneTurn(val speaker: String = "", val text: String = "", val thoughtProcess: String = "", val imagePath: String? = null)

internal fun savePhoneHistory(context: Context, entry: PhoneHistoryEntry) {
    val directory = File(context.filesDir, "phone-history")
    directory.mkdirs()
    val file = File(directory, "${entry.id}.json")
    writeAtomicFile(file, phoneJson.encodeToString(entry).toByteArray(Charsets.UTF_8))
}

internal fun readPhoneHistory(context: Context): List<PhoneHistoryEntry> {
    val directory = File(context.filesDir, "phone-history")
    val files = directory.listFiles().orEmpty()
    return files.filter { it.extension == "json" }.map { file ->
        phoneJson.decodeFromString<PhoneHistoryEntry>(AtomicFile(file).readFully().toString(Charsets.UTF_8))
            .copy(id = file.nameWithoutExtension)
    }.sortedByDescending { it.startedAt }
}

internal suspend fun savePhoneTurns(context: Context, callId: String, turns: List<PhoneTurn>) {
    try {
        withContext(Dispatchers.IO) {
            val file = File(File(context.filesDir, "phone-history"), "$callId.turns")
            writeAtomicFile(file, phoneJson.encodeToString(turns).toByteArray(Charsets.UTF_8))
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        com.mrj.fancyai.util.AppLog.write(android.util.Log.ERROR, "Phone", "Conversation write failed", failure)
    }
}

internal fun readPhoneTurns(context: Context, callId: String): List<PhoneTurn> {
    val file = File(File(context.filesDir, "phone-history"), "$callId.turns")
    if (!file.exists()) return emptyList()
    return phoneJson.decodeFromString(AtomicFile(file).readFully().toString(Charsets.UTF_8))
}

internal class PhoneHistoryController(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    val entries = mutableStateOf<List<PhoneHistoryEntry>?>(null)
    val failed = mutableStateOf(false)
    val removing = mutableStateOf(false)

    suspend fun load() {
        runCatching { withContext(Dispatchers.IO) { readPhoneHistory(context) } }
            .onSuccess {
                entries.value = it
                failed.value = false
            }
            .onFailure { failure ->
                if (failure is CancellationException) throw failure
                com.mrj.fancyai.util.AppLog.write(android.util.Log.ERROR, "Phone", "History load failed", failure)
                failed.value = true
            }
    }

    fun remove(entry: PhoneHistoryEntry, onFinished: () -> Unit) {
        removing.value = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    listOf("turns", "json").forEach { extension ->
                        File(File(context.filesDir, "phone-history"), "${entry.id}.$extension").delete()
                    }
                }
                if (entry.characterId.isNotBlank()) {
                    CharacterMemory(context, entry.characterId).deleteSession("phone:${entry.id}")
                }
                entries.value = entries.value?.filterNot { it.id == entry.id }
                onFinished()
            } finally {
                removing.value = false
            }
        }
    }
}


internal suspend fun speakUnlessCallEnded(tts: TtsEngine, text: String) {
    try {
        tts.speak(text)
    } catch (cancelled: CancellationException) {
        if (currentCoroutineContext()[Job]?.isActive == false) throw cancelled
    }
}

internal enum class PhonePhase(@param:StringRes val label: Int) {
    Ready(R.string.phone_status_ready), Preparing(R.string.phone_status_preparing),
    Listening(R.string.voice_listening), Thinking(R.string.phone_status_thinking),
    Speaking(R.string.phone_status_speaking), Muted(R.string.phone_status_muted),
    Error(R.string.phone_status_ended),
}

internal const val PHONE_PREFERENCES = "phone"
internal const val KEY_PHONE_SEARCH = "search"

internal class PhoneController(
    private val context: Context,
    private val character: CharacterCard,
    private val profile: UserProfile,
    private val userName: String,
    private val firstMessage: String,
    private val scope: CoroutineScope,
    private val onHistoryChanged: () -> Unit,
) {
    val stt = VoiceEngineFactory.stt(context)
    val phase: MutableState<PhonePhase> = mutableStateOf(PhonePhase.Ready)
    val muted: MutableState<Boolean> = mutableStateOf(false)
    val lastHeard: MutableState<String> = mutableStateOf("")
    val thoughtProcess: MutableState<String> = mutableStateOf("")
    val error: MutableIntState = mutableIntStateOf(0)
    private val runtimeRef = AtomicReference<LlmEngineClient?>()
    private val ttsRef = AtomicReference<TtsEngine?>()
    private val resumeListening = Channel<Unit>(Channel.CONFLATED)
    private var callRun = 0
    private var callJob: Job? = null

    private class CallSession(
        val history: PhoneHistoryEntry,
        val turns: MutableList<PhoneTurn>,
        val exchanges: MutableList<LlmExchange>,
        val macros: MacroBus,
        val memory: CharacterMemory,
        val settings: LlmSettings,
        val runtime: LlmEngineClient,
        val tts: VoiceTtsEngine,
        val connectedAt: Long,
    )

    fun closeSession() {
        callRun++
        callJob?.cancel()
        callJob = null
        stt.cancel()
        runtimeRef.get()?.cancel()
        ttsRef.getAndSet(null)?.let { it.stop(); it.release() }
        runtimeRef.getAndSet(null)?.close()
    }

    fun dispose() {
        closeSession()
        stt.release()
        resumeListening.close()
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun beginCall() {
        if (callJob?.isActive == true) return
        val run = callRun + 1
        callRun = run
        muted.value = false
        error.intValue = 0
        phase.value = PhonePhase.Preparing
        callJob = scope.launch { executeCall(run) }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private suspend fun executeCall(run: Int) {
        val history = PhoneHistoryEntry(UUID.randomUUID().toString(), character.id, character.name, System.currentTimeMillis())
        val turns = mutableListOf<PhoneTurn>()
        val macros = MacroBus(context, character, profile, userName)
        val memory = CharacterMemory(context, character.id)
        var historySaved = false
        var session: CallSession? = null
        try {
            withContext(Dispatchers.IO) { savePhoneHistory(context, history) }
            historySaved = true
            val activeSession = initializeCall(history, turns, macros, memory)
            session = activeSession
            if (firstMessage.isNotBlank()) {
                phase.value = PhonePhase.Speaking
                val greeting = firstMessage.capitalizeFirstVisibleLetter()
                turns += PhoneTurn(character.name, greeting)
                savePhoneTurns(context, history.id, turns)
                speakUnlessCallEnded(activeSession.tts, greeting)
            }
            runConversation(run, activeSession)
        } catch (cancelled: CancellationException) {
            if (currentCoroutineContext()[Job]?.isActive != false) throw cancelled
        } finally {
            finishCall(run, history, turns, session, historySaved)
        }
    }

    private suspend fun initializeCall(
        history: PhoneHistoryEntry,
        turns: MutableList<PhoneTurn>,
        macros: MacroBus,
        memory: CharacterMemory,
    ): CallSession {
        val settings = withContext(Dispatchers.IO) { checkNotNull(LlmSettingsStore.snapshot(context)) }
        val initialTurn = AssistantProtocol.compile(settings.runtime, macros = macros, instructions = listOf(PHONE_INSTRUCTION), message = "")
        val runtime = runtimeRef.get() ?: LlmEngineClient(context).also(runtimeRef::set)
        runtime.prepare(settings.sessionConfig(initialTurn.systemInstruction, firstMessage))
        val tts = VoiceEngineFactory.tts(context, character.id)
        ttsRef.set(tts)
        return CallSession(history, turns, mutableListOf(), macros, memory, settings, runtime, tts, SystemClock.elapsedRealtime())
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private suspend fun runConversation(run: Int, session: CallSession) {
        var userTurnIndex = 0
        while (currentCoroutineContext()[Job]?.isActive == true && run == callRun) {
            while (muted.value) {
                phase.value = PhonePhase.Muted
                resumeListening.receive()
            }
            phase.value = PhonePhase.Listening
            val heard = try {
                    stt.listen()
            } catch (failure: SpeechRecognitionException) {
                when (failure.errorCode) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> ""
                    else -> throw failure
                }
            } catch (cancelled: CancellationException) {
                if (currentCoroutineContext()[Job]?.isActive == false) throw cancelled
                ""
            }
            if (heard.isBlank()) continue
            val message = heard.capitalizeFirstVisibleLetter()
            lastHeard.value = message
            session.turns += PhoneTurn(userName, message)
            savePhoneTurns(context, session.history.id, session.turns)
            phase.value = PhonePhase.Thinking
            val contextBlocks = withContext(Dispatchers.IO) { session.macros.text(lorebookContext(context, character, message)) }
            val turnIndex = userTurnIndex++
            val memories = session.memory.recall(
                message,
                newConversation = turnIndex == 0,
            )
            val assistantTurn = AssistantProtocol.compile(
                session.settings.runtime,
                macros = session.macros,
                instructions = listOf(PHONE_INSTRUCTION),
                message = message,
                context = listOfNotNull(
                    character.scene.takeIf(String::isNotBlank)?.let { "Scenario:\n${session.macros.text(it)}" },
                ),
                optionalContext = listOf(contextBlocks) + memories,
            )
            runTurn(session, turnIndex, assistantTurn)
        }
    }

    private suspend fun runTurn(
        session: CallSession,
        turnIndex: Int,
        assistantTurn: AssistantTurn,
    ) {
        val request = LlmRequest(
            config = session.settings.sessionConfig(assistantTurn.systemInstruction, if (assistantTurn.imageRequested) "" else firstMessage, if (assistantTurn.imageRequested) emptyList() else session.exchanges.toList()),
            input = assistantTurn.input,
        )
        var completed = false
        var spokenLength = 0
        val reply = StringBuilder()
        val thoughts = StringBuilder()
        thoughtProcess.value = ""
        val replyIndex = session.turns.size
        var lastSavedAt = SystemClock.elapsedRealtime()
        try {
            session.tts.speak(session.runtime.generate(request).map { chunk ->
                reply.append(chunk.text)
                chunk.channels.values.forEach(thoughts::append)
                thoughtProcess.value = thoughts.toString()
                val visible = ImagePrompt.split(session.macros.text(reply.toString()), imageOnly = assistantTurn.imageRequested).first
                if (visible.isNotBlank() || thoughts.isNotBlank()) {
                    val turn = PhoneTurn(character.name, visible, thoughts.toString())
                    if (session.turns.size == replyIndex) session.turns.add(turn) else session.turns[replyIndex] = turn
                    if (SystemClock.elapsedRealtime() - lastSavedAt >= 1000L) {
                        savePhoneTurns(context, session.history.id, session.turns)
                        lastSavedAt = SystemClock.elapsedRealtime()
                    }
                }
                visible.drop(spokenLength).also { spokenLength = visible.length }
            }) { phase.value = PhonePhase.Speaking }
            completed = true
        } catch (cancelled: CancellationException) {
            if (currentCoroutineContext()[Job]?.isActive == false) throw cancelled
        } finally {
            if (reply.isNotBlank()) {
                session.exchanges += LlmExchange(assistantTurn.input, session.macros.text(reply.toString()))
                if (session.exchanges.size > session.settings.memory.historyLimit) session.exchanges.removeAt(0)
            }
            withContext(NonCancellable) { savePhoneTurns(context, session.history.id, session.turns) }
        }
        if (completed) {
            phase.value = PhonePhase.Thinking
            ImagePrompt.split(session.macros.text(reply.toString()), imageOnly = assistantTurn.imageRequested).second?.let {
                val image = generatePromptImage(context, it, characterId = character.id)
                val turn = PhoneTurn(character.name, ImagePrompt.split(session.macros.text(reply.toString()), imageOnly = assistantTurn.imageRequested).first,
                    thoughts.toString(), image.relativeTo(context.filesDir).path)
                if (session.turns.size == replyIndex) session.turns.add(turn) else session.turns[replyIndex] = turn
                withContext(NonCancellable) { savePhoneTurns(context, session.history.id, session.turns) }
            }
            session.memory.collect(
                sessionId = "phone:${session.history.id}",
                turnIndex = turnIndex,
                timestamp = System.currentTimeMillis(),
                messages = session.turns.takeLast(4).map { "${it.speaker}: ${it.text}" },
            )
        }
    }

    private suspend fun finishCall(
        run: Int,
        history: PhoneHistoryEntry,
        turns: List<PhoneTurn>,
        session: CallSession?,
        historySaved: Boolean,
    ) {
        if (historySaved) {
            val connectedAt = session?.connectedAt ?: 0L
            val duration = if (connectedAt == 0L) 0L else SystemClock.elapsedRealtime() - connectedAt
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching {
                    savePhoneTurns(context, history.id, turns)
                    savePhoneHistory(context, history.copy(durationSeconds = duration / 1000, status = when {
                        connectedAt == 0L -> PhoneCallStatus.CANCELLED
                        else -> PhoneCallStatus.ENDED
                    }))
                }.onFailure { failure ->
                    com.mrj.fancyai.util.AppLog.write(android.util.Log.ERROR, "Phone", "Call history write failed", failure)
                }
            }
            onHistoryChanged()
        }
        val tts = session?.tts
        ttsRef.compareAndSet(tts, null)
        tts?.release()
        if (run == callRun) callJob = null
    }

    fun toggleMute() {
        muted.value = !muted.value
        if (muted.value && phase.value == PhonePhase.Listening) stt.cancel()
        if (!muted.value) resumeListening.trySend(Unit)
    }

    fun stopSpeaking() {
        if (phase.value == PhonePhase.Speaking) ttsRef.get()?.stop()
    }
}
