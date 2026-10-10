package com.mrj.fancyai.ui.chat

import com.mrj.fancyai.service.llm.ImagePrompt
import com.mrj.fancyai.service.llm.AssistantProtocol
import android.content.Context
import android.os.SystemClock
import com.mrj.fancyai.R
import com.mrj.fancyai.service.llm.CloudLlmRuntime
import com.mrj.fancyai.service.llm.CloudProvider
import com.mrj.fancyai.service.llm.LlmExchange
import com.mrj.fancyai.service.llm.LlmEngineClient
import com.mrj.fancyai.service.llm.LlmInput
import com.mrj.fancyai.service.llm.LlmRequest
import com.mrj.fancyai.service.llm.LlmRuntime
import com.mrj.fancyai.service.llm.generatePromptImage
import com.mrj.fancyai.service.llm.llmErrorResource
import android.util.Log
import com.mrj.fancyai.BuildConfig
import com.mrj.fancyai.service.vision.VisionClient
import com.mrj.fancyai.service.vision.VisionRequest
import com.mrj.fancyai.ui.lorebook.lorebookContext
import com.mrj.fancyai.ui.settings.SelectedCloudEngine
import com.mrj.fancyai.ui.settings.activeSystemPrompt
import com.mrj.fancyai.ui.settings.runtime
import com.mrj.fancyai.ui.settings.sessionConfig
import com.mrj.fancyai.util.contentImageFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException

private suspend fun ChatController.prepareVision(
    conversation: ChatConversation,
    firstRetained: Int,
    cloud: SelectedCloudEngine?,
): ChatConversation {
    var prepared = conversation
    val needsCheck = (!cloudVisionChecked) && (cloud != null) && (cloud.provider != CloudProvider.CUSTOM)
    val hasImages = prepared.turns.asSequence().drop(firstRetained).any { it.userImagePath != null }
    if (needsCheck && hasImages) {
        nativeVision = CloudLlmRuntime().fetchModels(cloud.provider, cloud.apiKey, cloud.baseUrl)
            .firstOrNull { it.id == cloud.model }?.supportsVision == true
        cloudVisionChecked = true
    }
    for (index in firstRetained until prepared.turns.size) {
        val past = prepared.turns[index]
        val path = past.userImagePath ?: past.modelInput?.imagePath ?: continue
        if (nativeVision || past.userImageDescription != null) continue
        val description = describe(path)
        val turns = prepared.turns.toMutableList()
        turns[index] = turns[index].copy(userImageDescription = description)
        prepared = prepared.copy(turns = turns)
        replaceConversation(prepared)
        persistNow(prepared.id)
    }
    return prepared
}

private suspend fun ChatController.handleSceneImage(scenePrompt: String, characterId: String): String? {
    return try {
        phase = ChatController.Phase.GENERATING_IMAGE
        val image = generatePromptImage(context, scenePrompt, characterId = characterId) { progress -> imageProgress = progress }
        withContext(NonCancellable + Dispatchers.IO) {
            saveGeneratedChatImage(context, image)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        reportError(llmErrorResource(failure, R.string.aura_generation_failed))
        null
    }
}

internal suspend fun ChatController.reply(
    conversation: ChatConversation,
    thinking: Boolean,
) {
    phase = ChatController.Phase.LOADING
    val turnStarted = SystemClock.elapsedRealtime()
    val (_, instruction) = activeSystemPrompt(context)
    val cloud = settings.engine as? SelectedCloudEngine
    val windowed = (cloud == null) && (settings.runtime == LlmRuntime.LITERT)
    val firstRetained = if (windowed) (conversation.turns.size - settings.memory.historyLimit - 1).coerceAtLeast(0) else 0
    var prepared = prepareVision(conversation, firstRetained, cloud)
    if (BuildConfig.DEBUG) Log.i("Chat", "turn visionMs=${SystemClock.elapsedRealtime() - turnStarted} turns=${conversation.turns.size}")
    var current = prepared.turns.last()
    val imageInstruction = ImagePrompt.requestedInstruction(macros, current.user)
    // Trigger-phrase image turns open an image-only session that replaces the live chat KV
    // cache; capture it first so it can be rebuilt afterwards. Normal turns extend the
    // session and need no restore.
    val chatSession = LlmEngineClient.captureForImage()
    try {
        val assembleStarted = SystemClock.elapsedRealtime()
        val (history, turn, base) = if (imageInstruction != null) {
            val clean = LlmInput(text = current.user)
            Triple(emptyList(), clean, clean)
        } else withContext(Dispatchers.IO) {
            val start = if (windowed) (prepared.turns.lastIndex - settings.memory.historyLimit).coerceAtLeast(0) else 0
            val history = (start until prepared.turns.lastIndex).map { index ->
                val pastTurn = prepared.turns[index]
                val reply = pastTurn.replyText.ifBlank { pastTurn.assistant }
                val scene = pastTurn.imagePromptText
                val assistantContent = if ((scene != null) && instruction.contains("scene_prompt", ignoreCase = true)) {
                    "$reply\n\n<scene_prompt>$scene</scene_prompt>"
                } else {
                    reply
                }
                LlmExchange(
                    input = engineInput(input(prepared, index, retrieve = false)),
                    assistant = macros.text(assistantContent),
                )
            }
            val base = input(prepared, prepared.turns.lastIndex, retrieve = true)
            val turn = LlmInput(
                text = listOfNotNull(
                    current.userImageDescription?.takeUnless { nativeVision }?.let { "Image description:\n$it" },
                    macros.text(base.text),
                ).joinToString("\n\n"),
                imagePath = base.imagePath,
                context = base.context.map(macros::text),
                toolsJson = "",
            )
            Triple(history, turn, base)
        }
        current = current.copy(modelInput = base, imageRequested = imageInstruction != null)
        prepared = prepared.copy(turns = prepared.turns.dropLast(1) + current)
        replaceConversation(prepared)
        persistNow(prepared.id)
        if (BuildConfig.DEBUG) Log.i("Chat", "turn historyMs=${SystemClock.elapsedRealtime() - assembleStarted} historyTurns=${history.size}")
        val scenarioContext = character.scene.takeIf(String::isNotBlank)?.let { "Scenario:\n${macros.text(it)}" }
        val systemInstructions = listOf(instruction) + AssistantProtocol.identityContext(macros) + listOfNotNull(scenarioContext)
        val request = LlmRequest(
            config = settings.sessionConfig(
                AssistantProtocol.systemInstruction(
                    settings.runtime,
                    macros,
                    systemInstructions,
                    imageInstruction,
                ),
                if (imageInstruction == null) firstMessage else "",
                history,
            ),
            input = engineInput(turn),
            thinking = thinking,
        )
        streamReply(prepared, current, request) { current = it }
        currentCoroutineContext().ensureActive()
        replaceConversation(prepared.copy(turns = prepared.turns.dropLast(1) + current))
        persistNow(prepared.id)
        current.imagePromptText?.let { scenePrompt ->
            handleSceneImage(scenePrompt, character.id)?.let { path ->
                current = current.copy(imagePath = path)
            }
        }
    } finally {
        if (imageInstruction != null) scheduleChatSessionRestore(chatSession)
        replaceConversation(prepared.copy(turns = prepared.turns.dropLast(1) + current))
        phase = ChatController.Phase.IDLE
    }
}

private suspend fun ChatController.streamReply(
    prepared: ChatConversation,
    initial: ChatTurn,
    request: LlmRequest,
    onCurrent: (ChatTurn) -> Unit,
) {
    phase = ChatController.Phase.GENERATING
    val generateStarted = SystemClock.elapsedRealtime()
    var firstTokenMs = -1L
    val text = StringBuilder()
    val channels = mutableMapOf<String, StringBuilder>()
    var lastPublished = 0L
    var current = initial
    try {
        engine.generate(request).collect { (_, chunkText, chunkChannels) ->
            if (firstTokenMs < 0) firstTokenMs = SystemClock.elapsedRealtime() - generateStarted
            text.append(chunkText)
            chunkChannels.forEach { (name, value) -> channels.getOrPut(name) { StringBuilder() }.append(value) }
            val now = SystemClock.elapsedRealtime()
            if ((now - lastPublished) >= 50L) {
                current = current.copy(
                    assistant = if (initial.imageRequested) text.toString() else macros.text(text.toString()),
                    channels = channels.mapValues { (_, value) -> value.toString() },
                )
                onCurrent(current)
                replaceConversation(prepared.copy(turns = prepared.turns.dropLast(1) + current))
                lastPublished = now
            }
        }
    } finally {
        if (BuildConfig.DEBUG) Log.i("Chat", "turn firstTokenMs=$firstTokenMs generateMs=${SystemClock.elapsedRealtime() - generateStarted}")
        current = current.copy(
            assistant = if (initial.imageRequested) text.toString() else macros.text(text.toString()),
            channels = channels.mapValues { (_, value) -> value.toString() },
        )
        onCurrent(current)
    }
}

internal suspend fun ChatController.input(conversation: ChatConversation, index: Int, retrieve: Boolean): LlmInput {
    val (user, _, _, _, userImagePath, userImageDescription, modelInput) = conversation.turns[index]
    if (!retrieve) {
        val userText = user.ifBlank { context.getString(R.string.vision_image) }
        val imagePath = userImagePath ?: modelInput?.imagePath
        if (nativeVision) return LlmInput(text = macros.text(userText), imagePath = imagePath)
        val description = userImageDescription?.takeIf(String::isNotBlank)
        val text = if ((description != null) && !userText.contains(description)) {
            "$userText\n\nImage description:\n$description"
        } else userText
        return LlmInput(text = macros.text(text), imagePath = null)
    }
    val previous = conversation.turns.take(index).takeLast(2).flatMap { listOf(it.user, it.replyText) }
    val query = (previous.ifEmpty { listOf(firstMessage) } + user)
        .joinToString("\n")
    val loreStarted = SystemClock.elapsedRealtime()
    val lore = macros.text(lorebookContext(context, character, query))
    val recallStarted = SystemClock.elapsedRealtime()
    val memories = memory.recall(user, newConversation = index == 0)
    if (BuildConfig.DEBUG) Log.i("Chat", "turn loreMs=${recallStarted - loreStarted} recallMs=${SystemClock.elapsedRealtime() - recallStarted}")
    return LlmInput(
        text = user.ifBlank { context.getString(R.string.vision_image) },
        imagePath = userImagePath.takeIf { nativeVision },
        context = listOfNotNull(lore.takeIf(String::isNotBlank)) + memories,
    )
}

internal suspend fun ChatController.rememberReply(conversation: ChatConversation) {
    if (conversation.turns.lastOrNull()?.replyText.isNullOrBlank()) return
    val userName = profile.name.ifBlank { "User" }
    memory.collect(
        conversation.id,
        conversation.turns.lastIndex,
        conversation.updatedAt,
        conversation.turns.takeLast(2).flatMap { turn ->
            listOf("$userName: ${turn.user}", "${character.name}: ${turn.replyText}")
        },
    )
}

internal fun ChatController.engineInput(input: LlmInput): LlmInput {
    val image = input.imagePath ?: return input
    return input.copy(imagePath = contentImageFile(context, image)?.path ?: throw FileNotFoundException(image))
}

internal suspend fun ChatController.describe(path: String): String {
    val model = context.getSharedPreferences("vision", Context.MODE_PRIVATE).getString("model", null)
        ?.takeIf { File(it).isFile } ?: throw MissingVisionModel()
    val image = contentImageFile(context, path) ?: throw FileNotFoundException(path)
    phase = ChatController.Phase.READING_IMAGE
    engine.unload()
    val client = VisionClient(context)
    vision = client
    return try {
        client.describe(VisionRequest(model, image.path, "Describe the image, including visible details and readable text."))
    } finally {
        withContext(NonCancellable) { client.close() }
        vision = null
    }
}
