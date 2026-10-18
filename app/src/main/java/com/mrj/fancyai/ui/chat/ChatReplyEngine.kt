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
import com.mrj.fancyai.service.llm.LlmTokenEstimate
import com.mrj.fancyai.service.llm.MacroBus
import com.mrj.fancyai.service.llm.generatePromptImage
import com.mrj.fancyai.service.llm.llmErrorResource
import android.util.Log
import com.mrj.fancyai.BuildConfig
import com.mrj.fancyai.service.vision.VisionClient
import com.mrj.fancyai.service.vision.VisionRequest
import com.mrj.fancyai.ui.lorebook.lorebookContext
import com.mrj.fancyai.ui.settings.SelectedCloudEngine
import com.mrj.fancyai.ui.settings.DEFAULT_OUTPUT_TOKENS
import com.mrj.fancyai.ui.settings.GenerationSettings
import com.mrj.fancyai.ui.settings.SelectedEngine
import com.mrj.fancyai.ui.settings.activeSystemPrompt
import com.mrj.fancyai.ui.settings.imageSettings
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

private const val DEFAULT_RESERVE_TOKENS = 512
private const val CONTEXT_MARGIN_TOKENS = 64
private const val PER_MESSAGE_OVERHEAD = 4
private const val TRIM_TARGET_RATIO = 0.7

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
    val chatSession = LlmEngineClient.captureForImage()
    val kvPath = kvTmpPath()
    val kvSaved = (imageInstruction != null) && (chatSession != null) && engine.saveKvCache(kvPath)
    try {
        val assembleStarted = SystemClock.elapsedRealtime()
        val scenarioContext = character.scene.takeIf(String::isNotBlank)?.let { "Scenario:\n${macros.text(it)}" }
        val systemInstructions = if (imageInstruction != null) {
            AssistantProtocol.identityContext(macros) + listOfNotNull(scenarioContext)
        } else {
            listOf(instruction) + AssistantProtocol.identityContext(macros) +
                listOfNotNull(scenarioContext, character.systemPrompt.takeIf(String::isNotBlank))
        }
        val systemText = AssistantProtocol.systemInstruction(settings.runtime, macros, systemInstructions, imageInstruction)
        var transcriptStart = prepared.transcriptStart.coerceIn(0, prepared.turns.lastIndex)
        val (history, turn, base) = withContext(Dispatchers.IO) {
            val base = input(prepared, prepared.turns.lastIndex, retrieve = imageInstruction == null)
            val turn = if (imageInstruction != null) {
                LlmInput(text = base.text, imagePath = base.imagePath, toolsJson = "")
            } else LlmInput(
                text = listOfNotNull(
                    current.userImageDescription?.takeUnless { nativeVision }?.let { "Image description:\n$it" },
                    macros.text(base.text),
                ).joinToString("\n\n"),
                imagePath = base.imagePath,
                context = base.context.map(macros::text),
                toolsJson = "",
            )
            val examples = exampleExchanges(macros, profile.name.ifBlank { "User" }, character.name, character.exampleMessages)
            var exampleStart = 0

            val local = settings.engine as? SelectedEngine
            contextUsage = 0 to 0
            if ((cloud == null) && (local != null)) {
                val maxOutput = (settings.generation as? GenerationSettings)?.maxOutputTokens?.takeIf { it > 0 }
                    ?: DEFAULT_OUTPUT_TOKENS
                val budget = local.contextTokens - maxOf(DEFAULT_RESERVE_TOKENS, maxOutput) - CONTEXT_MARGIN_TOKENS
                val target = (budget * TRIM_TARGET_RATIO).toLong()
                val historyLimit = settings.memory.historyLimit.coerceAtLeast(0)
                val limitStart = (prepared.turns.lastIndex - historyLimit).coerceAtLeast(0)
                if (transcriptStart < limitStart) transcriptStart = limitStart
                val keepReasoning = settings.memory.preserveThinking
                fun reasoningOf(past: ChatTurn): String =
                    if (keepReasoning) past.channels["reasoning"].orEmpty() else ""
                val firstIndex = transcriptStart
                val exBase = 3
                val turnBase = exBase + examples.size * 2
                val countTexts = buildList {
                    add(systemText)
                    add(firstMessage)
                    add(turn.textWithContext())
                    examples.forEach { (input, assistant) ->
                        add(input.text)
                        add(assistant)
                    }
                    for (index in firstIndex until prepared.turns.lastIndex) {
                        val (user, assistant, _, _, _, _, _, _, _, _, sentUser, sentAssistant) = prepared.turns[index]
                        add(sentUser.ifEmpty { user })
                        add(sentAssistant.ifEmpty { assistant })
                        add(reasoningOf(prepared.turns[index]))
                    }
                }
                val counted = if (windowed) null else engine.countTokens(countTexts)
                fun piece(offset: Int, fallback: String): Int =
                    counted?.getOrNull(offset) ?: LlmTokenEstimate.estimateTokens(fallback)
                val fixed = piece(0, systemText) +
                    piece(1, firstMessage) +
                    piece(2, turn.textWithContext()) +
                    (3 * PER_MESSAGE_OVERHEAD)
                fun estimate(from: Int): Long {
                    var tokens = fixed.toLong()
                    examples.forEachIndexed { exampleIndex, (input, assistant) ->
                        if (exampleIndex >= exampleStart) {
                            tokens += piece(exBase + exampleIndex * 2, input.text) +
                                piece(exBase + exampleIndex * 2 + 1, assistant) +
                                (2 * PER_MESSAGE_OVERHEAD)
                        }
                    }
                    for (index in from until prepared.turns.lastIndex) {
                        val slot = (index - firstIndex) * 3 + turnBase
                        val past = prepared.turns[index]
                        val userText = past.sentUser.ifEmpty { past.user }
                        val assistantText = past.sentAssistant.ifEmpty { past.assistant }
                        tokens += piece(slot, userText) +
                            piece(slot + 1, assistantText) +
                            piece(slot + 2, reasoningOf(past)) +
                            (2 * PER_MESSAGE_OVERHEAD)
                    }
                    return tokens
                }
                if (estimate(transcriptStart) > budget) {
                    if (fixed > budget) {
                        throw IllegalStateException("Prompt needs $fixed tokens, context holds $budget. Shorten the system prompt or raise context size.")
                    }
                    while ((exampleStart < examples.size) && (estimate(transcriptStart) > target)) {
                        ++exampleStart
                    }
                    val before = transcriptStart
                    while ((transcriptStart < prepared.turns.lastIndex) && (estimate(transcriptStart) > target)) {
                        ++transcriptStart
                    }
                    if (BuildConfig.DEBUG) Log.i("Chat", "turn budget droppedExampleMessages=$exampleStart droppedHistoryMessages=${transcriptStart - before} start=$transcriptStart")
                }
                val keptTurns = (prepared.turns.lastIndex - transcriptStart) + (examples.size - exampleStart)
                contextUsage = (estimate(transcriptStart) * 100 / local.contextTokens).coerceIn(0L, 100L).toInt() to keptTurns
            }
            val start = when {
                windowed -> maxOf((prepared.turns.lastIndex - settings.memory.historyLimit).coerceAtLeast(0), transcriptStart)
                cloud == null -> transcriptStart
                else -> 0
            }
            if (cloud != null) {
                val window = cloud.contextLength ?: 0
                if (window > 0) {
                    var total = (LlmTokenEstimate.estimateTokens(systemText) +
                        LlmTokenEstimate.estimateTokens(firstMessage) +
                        LlmTokenEstimate.estimateTokens(turn.textWithContext()) + 12).toLong()
                    total += examples.sumOf {
                        (LlmTokenEstimate.estimateTokens(it.input.text) + LlmTokenEstimate.estimateTokens(it.assistant) + 8).toLong()
                    }
                    for (index in 0 until prepared.turns.lastIndex) {
                        val past = prepared.turns[index]
                        total += LlmTokenEstimate.estimateTokens(past.sentUser.ifEmpty { past.user }) +
                            LlmTokenEstimate.estimateTokens(past.sentAssistant.ifEmpty { past.assistant }) + 8
                    }
                    contextUsage = (total * 100 / window).coerceIn(0, 100).toInt() to prepared.turns.lastIndex
                    if (BuildConfig.DEBUG) Log.i("Chat", "turn usage prompt~$total ctx=$window")
                } else {
                    contextUsage = 0 to prepared.turns.lastIndex
                }
            }
            val history = (start until prepared.turns.lastIndex).map { index ->
                val pastTurn = prepared.turns[index]
                val reasoning = if (settings.memory.preserveThinking) pastTurn.channels["reasoning"].orEmpty() else ""
                if (pastTurn.sentUser.isNotEmpty()) {
                    LlmExchange(input = LlmInput(text = pastTurn.sentUser), assistant = pastTurn.sentAssistant, reasoning = reasoning)
                } else {
                    val stored = pastTurn.modelInput
                    if (stored != null) {
                        LlmExchange(
                            input = engineInput(stored),
                            assistant = pastTurn.assistant,
                            reasoning = reasoning,
                        )
                    } else {
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
                            reasoning = reasoning,
                        )
                    }
                }
            }
            Triple(if (imageInstruction != null) emptyList() else examples.drop(exampleStart) + history, turn, base)
        }
        val engineTurn = engineInput(turn).let { sent ->
            if (imageInstruction != null) sent
            else character.postHistoryInstructions.takeIf(String::isNotBlank)?.let { trailer ->
                sent.copy(text = sent.textWithContext() + "\n\n" + macros.text(trailer), context = emptyList())
            } ?: sent
        }
        current = current.copy(
            modelInput = base,
            imageRequested = imageInstruction != null,
            sentUser = engineTurn.textWithContext(),
        )
        prepared = prepared.copy(turns = prepared.turns.dropLast(1) + current, transcriptStart = transcriptStart)
        replaceConversation(prepared)
        persistNow(prepared.id)
        if (BuildConfig.DEBUG) Log.i("Chat", "turn historyMs=${SystemClock.elapsedRealtime() - assembleStarted} historyTurns=${history.size}")
        val effectiveSettings = if (imageInstruction != null) settings.imageSettings() else settings
        val request = LlmRequest(
            config = effectiveSettings.sessionConfig(
                systemText,
                if (imageInstruction == null) firstMessage else "",
                history,
            ),
            input = engineTurn,
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
        if (imageInstruction != null) {
            chatSession?.takeIf { (client, _) -> client === engine }?.let { (client, config) ->
                runCatching {
                    client.prepare(config)
                    if (kvSaved) client.loadKvCache(kvPath)
                }
            }
        }
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
                    assistant = text.toString(),
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
            sentAssistant = text.toString(),
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
    val previous = conversation.turns.take(index).takeLast(4).flatMap { listOf(it.user, it.replyText) }
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

private val EXAMPLE_START = Regex("<\\s*start\\s*>", RegexOption.IGNORE_CASE)

internal fun exampleExchanges(macros: MacroBus, userName: String, charName: String, raw: String): List<LlmExchange> {
    if (raw.isBlank()) return emptyList()
    val out = mutableListOf<LlmExchange>()
    var user: StringBuilder? = null
    var assistant = StringBuilder()
    fun speaker(line: String): Pair<Boolean, String>? {
        val low = line.lowercase()
        val marks = buildList {
            add("{{user}}" to true)
            add("{{char}}" to false)
            if (userName.isNotBlank()) add("$userName:".lowercase() to true)
            if (charName.isNotBlank()) add("$charName:".lowercase() to false)
            add("user:" to true)
        }
        for ((mark, isUser) in marks) {
            if (low.startsWith(mark)) {
                var body = line.substring(mark.length).trim()
                if (body.startsWith(":")) body = body.drop(1).trim()
                if (body.isNotEmpty()) return isUser to body
                return null
            }
        }
        return null
    }
    fun flush() {
        val userText = user?.toString()?.trim().orEmpty()
        val assistantText = assistant.toString().trim()
        if (userText.isNotEmpty() || assistantText.isNotEmpty()) {
            out.add(LlmExchange(LlmInput(macros.text(userText)), macros.text(assistantText)))
        }
        user = null
        assistant = StringBuilder()
    }
    for (block in EXAMPLE_START.split(raw)) {
        for (rawLine in block.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val (isUser, body) = speaker(line) ?: continue
            if (isUser) {
                flush()
                user = StringBuilder(body)
            } else {
                if (user == null) continue
                if (assistant.isNotEmpty()) assistant.append('\n')
                assistant.append(body)
            }
        }
    }
    flush()
    return out
}

internal suspend fun ChatController.describe(path: String): String {
    val model = context.getSharedPreferences("vision", Context.MODE_PRIVATE).getString("model", null)
        ?.takeIf { File(it).isFile } ?: throw MissingVisionModel()
    val image = contentImageFile(context, path) ?: throw FileNotFoundException(path)
    phase = ChatController.Phase.READING_IMAGE
    val client = VisionClient(context)
    vision = client
    return try {
        client.describe(VisionRequest(model, image.path, "Describe the image, including visible details and readable text."))
    } finally {
        withContext(NonCancellable) { client.close() }
        vision = null
    }
}
