package com.mrj.fancyai.ui.settings

import com.mrj.fancyai.engine.LiteRtBackend
import com.mrj.fancyai.engine.LlamaBackend
import com.mrj.fancyai.engine.LocalLlmModel
import com.mrj.fancyai.engine.LocalLlmRuntime
import com.mrj.fancyai.service.llm.CloudProvider
import com.mrj.fancyai.service.llm.LlmExchange
import com.mrj.fancyai.service.llm.LlmRuntime
import com.mrj.fancyai.service.llm.LlmSessionConfig

internal const val DEFAULT_TEMPERATURE = 0.8f
internal const val DEFAULT_DYNAMIC_TEMPERATURE = 0f
internal const val DEFAULT_TOP_K = 10
internal const val DEFAULT_TOP_P = 0.95f
internal const val DEFAULT_MIN_P = 0.05f
internal const val DEFAULT_OUTPUT_TOKENS = 512
internal const val DEFAULT_REPETITION_PENALTY = 1.05f
internal const val DEFAULT_PRESENCE_PENALTY = 0f
internal const val DEFAULT_FREQUENCY_PENALTY = 0f
internal const val DEFAULT_PENALTY_WINDOW = 0
internal const val DEFAULT_NO_REPEAT_NGRAM = 0
internal const val DEFAULT_NO_REPEAT_WINDOW = 0
internal const val DEFAULT_HISTORY_LIMIT = 20

internal sealed interface EngineChoice {
    val name: String
}

internal data class SelectedEngine(
    val model: LocalLlmModel,
    val liteRtBackend: LiteRtBackend,
    val llamaBackend: LlamaBackend,
    val llamaOffloadLayers: Int,
    val cpuThreads: Int,
    val promptThreads: Int,
    val contextTokens: Int,
    val speculativeDecoding: Boolean,
    val batchTokens: Int,
    val microBatchTokens: Int,
    val flashAttention: Boolean?,
    val quantizedKvCache: Boolean,
    val cacheTypeK: String = "f16",
    val cacheTypeV: String = "f16",
    val cpuRepack: Boolean,
    val useMmap: Boolean,
) : EngineChoice {
    override val name: String get() = model.name
}

internal data class SelectedCloudEngine(
    val provider: CloudProvider,
    val apiKey: String,
    val baseUrl: String,
    val model: String,
    val supportedGenerationParameters: Set<String>,
    val structuredOutput: Boolean = false,
    /** Reported context window in tokens. Null means unknown; budgets fall back to fixed caps. */
    val contextLength: Int? = null,
    /** USD per 1M input/output tokens. Display only. */
    val priceInput: Double? = null,
    val priceOutput: Double? = null,
) : EngineChoice {
    override val name: String get() = model
}

internal sealed interface ActiveGenerationSettings

internal data class GenerationSettings(
    val temperature: Float = DEFAULT_TEMPERATURE,
    val dynamicTemperature: Float = DEFAULT_DYNAMIC_TEMPERATURE,
    val topK: Int = DEFAULT_TOP_K,
    val topP: Float = DEFAULT_TOP_P,
    val minP: Float = DEFAULT_MIN_P,
    val maxOutputTokens: Int = DEFAULT_OUTPUT_TOKENS,
    val repetitionPenalty: Float = DEFAULT_REPETITION_PENALTY,
    val presencePenalty: Float = DEFAULT_PRESENCE_PENALTY,
    val frequencyPenalty: Float = DEFAULT_FREQUENCY_PENALTY,
    val penaltyWindow: Int = DEFAULT_PENALTY_WINDOW,
    val noRepeatNgramSize: Int = DEFAULT_NO_REPEAT_NGRAM,
    val noRepeatNgramWindow: Int = DEFAULT_NO_REPEAT_WINDOW,
) : ActiveGenerationSettings

internal data class CloudGenerationSettings(
    val maxOutputTokens: Int = DEFAULT_OUTPUT_TOKENS,
    val temperature: Float? = null,
    val topK: Int? = null,
    val topP: Float? = null,
    val minP: Float? = null,
    val repetitionPenalty: Float? = null,
    val presencePenalty: Float? = null,
    val frequencyPenalty: Float? = null,
) : ActiveGenerationSettings

internal enum class GenerationTarget { LITERT, LLAMA, CLOUD, MNN }

internal data class MemorySettings(
    val historyLimit: Int = DEFAULT_HISTORY_LIMIT,
)

internal val LlmSettings.runtime: LlmRuntime
    get() = when (val selected = engine) {
        is SelectedEngine -> when (selected.model.runtime) {
            LocalLlmRuntime.LITERT -> LlmRuntime.LITERT
            LocalLlmRuntime.LLAMA -> LlmRuntime.LLAMA
            LocalLlmRuntime.MNN -> LlmRuntime.MNN
        }
        is SelectedCloudEngine -> LlmRuntime.CLOUD
    }

internal data class LlmSettings(
    val engine: EngineChoice,
    val generation: ActiveGenerationSettings,
    val memory: MemorySettings,
)

internal fun LlmSettings.sessionConfig(
    systemInstruction: String,
    openingMessage: String = "",
    history: List<LlmExchange> = emptyList(),
): LlmSessionConfig = when (val selected = engine) {
    is SelectedEngine -> {
        val local = checkNotNull(generation as? GenerationSettings) {
            "Local engine received cloud generation settings"
        }
        when (selected.model.runtime) {
            LocalLlmRuntime.LITERT -> liteRtSession(selected, local, memory, systemInstruction, openingMessage, history)
            LocalLlmRuntime.LLAMA -> llamaSession(selected, local, memory, systemInstruction, openingMessage, history)
            LocalLlmRuntime.MNN -> mnnSession(selected, local, memory, systemInstruction, openingMessage, history)
        }
    }
    is SelectedCloudEngine -> {
        val cloud = checkNotNull(generation as? CloudGenerationSettings) {
            "Cloud engine received local generation settings"
        }
        cloudSession(selected, cloud, memory, systemInstruction, openingMessage, history)
    }
}
