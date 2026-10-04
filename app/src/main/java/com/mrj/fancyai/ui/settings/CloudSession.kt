package com.mrj.fancyai.ui.settings

import com.mrj.fancyai.service.llm.LlmExchange
import com.mrj.fancyai.service.llm.LlmRuntime
import com.mrj.fancyai.service.llm.LlmSessionConfig

/** Cloud session construction. Local sampling fields are unused server-side; generation travels via parameters. */
internal fun cloudSession(
    selected: SelectedCloudEngine,
    cloud: CloudGenerationSettings,
    memory: MemorySettings,
    systemInstruction: String,
    openingMessage: String,
    history: List<LlmExchange>,
): LlmSessionConfig = LlmSessionConfig(
    modelPath = "",
    runtime = LlmRuntime.CLOUD,
    cpuThreads = 0,
    // Known provider window, or zero when unreported. Policies budget from it when present.
    contextTokens = selected.contextLength?.takeIf { it > 0 } ?: 0,
    speculativeDecoding = false,
    systemInstruction = systemInstruction,
    openingMessage = openingMessage,
    history = history,
    historyLimit = memory.historyLimit,
    temperature = 1f,
    topK = 1,
    topP = 1f,
    minP = 0f,
    maxOutputTokens = cloud.maxOutputTokens,
    repetitionPenalty = 1f,
    presencePenalty = 0f,
    frequencyPenalty = 0f,
    penaltyWindow = 0,
    noRepeatNgramSize = 0,
    noRepeatNgramWindow = 0,
    cloudProvider = selected.provider,
    cloudStructuredOutput = selected.structuredOutput,
    cloudBaseUrl = selected.baseUrl,
    cloudApiKey = selected.apiKey,
    cloudModel = selected.model,
    cloudGenerationParameters = cloud.requestParameters(selected.supportedGenerationParameters),
)
