package com.mrj.fancyai.service.llm

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.mrj.fancyai.engine.LiteRtBackend
import com.mrj.fancyai.engine.LiteRtGenerationOptions
import com.mrj.fancyai.engine.LiteRtRuntime
import com.mrj.fancyai.engine.LocalGenerationMetrics
import kotlin.random.Random

/** LiteRT service ownership. Translates transport config and drives generation for LiteRT. */
internal val LlmSessionConfig.effectiveSpeculativeDecoding: Boolean
    get() = speculativeDecoding && ((runtime != LlmRuntime.LITERT) || (topK == 1))

internal fun LlmSessionConfig.liteRtConversationConfig(): ConversationConfig = ConversationConfig(
    systemInstruction = LiteRtTranscript.systemContents(this),
    initialMessages = LiteRtTranscript.initialMessages(this),
    samplerConfig = SamplerConfig(
        topK = topK,
        topP = topP.toDouble(),
        temperature = temperature.toDouble(),
        // A new request must not restart LiteRT's default seed-0 sequence.
        seed = if (benchmarking) 0 else Random.nextInt(),
    ),
    maxOutputToken = maxOutputTokens,
)

internal suspend fun LlmEngineService.openLiteRtRuntime(config: LlmSessionConfig): LiteRtRuntime {
    val backend = when (config.liteRtBackend) {
        LiteRtBackend.CPU -> Backend.CPU(config.cpuThreads.takeIf { it > 0 })
        LiteRtBackend.GPU -> Backend.GPU()
    }
    return LiteRtRuntime.open(
        EngineConfig(
            modelPath = config.modelPath,
            backend = backend,
            visionBackend = backend.takeIf { config.liteRtVision },
            maxNumTokens = config.contextTokens.takeIf { it > 0 },
            maxNumImages = 1.takeIf { config.liteRtVision },
            cacheDir = cacheDir.absolutePath,
        ),
        speculativeDecoding = config.effectiveSpeculativeDecoding,
        benchmarking = config.benchmarking,
    )
}

internal suspend fun LlmEngineService.executeLiteRt(
    session: LlmSessionConfig,
    input: LlmInput,
    thinking: Boolean,
    requestId: Long,
    clientCallback: ILlmEngineCallback,
): LocalGenerationMetrics? {
    val runtime = checkNotNull(liteRtRuntime) { "LiteRT session is not open" }
    val text = input.textWithContext()
    val imagePath = input.imagePath
    val contents = if (imagePath.isNullOrBlank()) {
        Contents.of(text)
    } else {
        Contents.of(Content.ImageFile(imagePath), Content.Text(text))
    }
    runtime.send(
        contents = contents,
        options = LiteRtGenerationOptions(
            thinking = thinking,
            maxOutputTokens = session.maxOutputTokens,
            repetitionPenalty = session.repetitionPenalty,
            presencePenalty = session.presencePenalty,
            frequencyPenalty = session.frequencyPenalty,
            penaltyWindow = session.penaltyWindow,
            noRepeatNgramSize = session.noRepeatNgramSize,
            noRepeatNgramWindow = session.noRepeatNgramWindow,
        ),
    ).collect { message -> emitMessage(requestId, message, clientCallback) }
    return if (session.benchmarking) runtime.generationMetrics() else null
}
