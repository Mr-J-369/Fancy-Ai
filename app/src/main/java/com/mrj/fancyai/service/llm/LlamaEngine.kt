package com.mrj.fancyai.service.llm

import com.mrj.fancyai.engine.LlamaEngineConfig
import com.mrj.fancyai.engine.LlamaGenerationOptions
import com.mrj.fancyai.engine.LocalGenerationMetrics

/** llama.cpp service ownership. Translates transport config and drives generation for llama. */
internal fun LlmSessionConfig.llamaEngineConfig() = LlamaEngineConfig(
    modelPath = modelPath,
    contextTokens = contextTokens.takeIf { it > 0 } ?: 4_096,
    batchTokens = batchTokens,
    microBatchTokens = microBatchTokens,
    decodeThreads = cpuThreads,
    promptThreads = promptThreads,
    flashAttention = flashAttention,
    cacheTypeK = cacheTypeK,
    cacheTypeV = cacheTypeV,
    useMmap = useMmap,
    cpuRepack = cpuRepack,
    backend = llamaBackend,
    offloadLayers = llamaOffloadLayers,
)

internal suspend fun LlmEngineService.executeLlama(
    session: LlmSessionConfig,
    input: LlmInput,
    thinking: Boolean,
    requestId: Long,
    clientCallback: ILlmEngineCallback,
): LocalGenerationMetrics? {
    val runtime = checkNotNull(llamaRuntime) { "llama.cpp session is not open" }
    runtime.send(
        text = input.textWithContext(),
        thinking = thinking,
        options = LlamaGenerationOptions(
            maxOutputTokens = session.maxOutputTokens,
            temperature = session.temperature,
            dynamicTemperature = session.dynamicTemperature,
            topK = session.topK,
            topP = session.topP,
            minP = session.minP,
            repetitionPenalty = session.repetitionPenalty,
            presencePenalty = session.presencePenalty,
            frequencyPenalty = session.frequencyPenalty,
            penaltyWindow = session.penaltyWindow,
            benchmarking = session.benchmarking,
            responseSchema = input.responseSchema,
            toolsJson = input.toolsJson,
        ),
    ).collect { (text, channel) ->
        emitTextChunks(requestId, text, channel, clientCallback)
    }
    return if (session.benchmarking) runtime.generationMetrics() else null
}
