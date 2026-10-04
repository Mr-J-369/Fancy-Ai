package com.mrj.fancyai.ui.settings

import com.mrj.fancyai.engine.LocalLlmRuntime
import com.mrj.fancyai.service.llm.LlmExchange
import com.mrj.fancyai.service.llm.LlmRuntime
import com.mrj.fancyai.service.llm.LlmSessionConfig

/** llama.cpp session construction. Owns the llama field mapping end to end. */
internal fun llamaSession(
    selected: SelectedEngine,
    local: GenerationSettings,
    memory: MemorySettings,
    systemInstruction: String,
    openingMessage: String,
    history: List<LlmExchange>,
): LlmSessionConfig {
    check(selected.model.runtime == LocalLlmRuntime.LLAMA) { "llama.cpp builder received ${selected.model.runtime}" }
    return LlmSessionConfig(
        modelPath = selected.model.path,
        runtime = LlmRuntime.LLAMA,
        liteRtBackend = selected.liteRtBackend,
        llamaBackend = selected.llamaBackend,
        llamaOffloadLayers = selected.llamaOffloadLayers,
        cpuThreads = selected.cpuThreads,
        promptThreads = selected.promptThreads,
        contextTokens = selected.contextTokens,
        speculativeDecoding = selected.speculativeDecoding,
        batchTokens = selected.batchTokens,
        microBatchTokens = selected.microBatchTokens,
        flashAttention = selected.flashAttention,
        quantizedKvCache = selected.quantizedKvCache,
        cacheTypeK = selected.cacheTypeK,
        cacheTypeV = selected.cacheTypeV,
        cpuRepack = selected.cpuRepack,
        useMmap = selected.useMmap,
        systemInstruction = systemInstruction,
        openingMessage = openingMessage,
        history = history,
        historyLimit = memory.historyLimit,
        temperature = local.temperature,
        dynamicTemperature = local.dynamicTemperature,
        topK = local.topK,
        topP = local.topP,
        minP = local.minP,
        maxOutputTokens = local.maxOutputTokens,
        repetitionPenalty = local.repetitionPenalty,
        presencePenalty = local.presencePenalty,
        frequencyPenalty = local.frequencyPenalty,
        penaltyWindow = local.penaltyWindow,
        noRepeatNgramSize = 0,
        noRepeatNgramWindow = 0,
    )
}
