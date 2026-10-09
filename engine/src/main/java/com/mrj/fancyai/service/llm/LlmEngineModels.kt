package com.mrj.fancyai.service.llm

import android.os.Parcelable
import com.mrj.fancyai.engine.LiteRtBackend
import com.mrj.fancyai.engine.LlamaBackend
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

enum class LlmRuntime { LITERT, LLAMA, CLOUD }

enum class CloudProvider { DEEPINFRA, OPENROUTER, CUSTOM }

enum class LlmTerminalReason { COMPLETED, CANCELLED, FAILED }

enum class LlmError {
    NONE,
    INVALID_REQUEST,
    MODEL_LOAD,
    CONTEXT_EXHAUSTED,
    OUT_OF_MEMORY,
    PROCESS_DIED,
    MEMORY_PRESSURE,
    INTERNAL,
    NETWORK,
    AUTHENTICATION,
    RATE_LIMIT,
}

enum class LlamaOffload(val layers: Int) {
    AUTOMATIC(-2),
    ALL(-1),
    NONE(0),
}

@Serializable
@Parcelize
data class LlmInput(
    val text: String = "",
    val imagePath: String? = null,
    val context: List<String> = emptyList(),
    /** Output structure and image controls for this turn only; never replayed as history. */
    @Transient val responseFormat: String = "",
    @Transient val responseSchema: String = "",
    @Transient val toolsJson: String = "",
) : Parcelable {
    /** Render at the engine boundary; per-turn context and format leave the action intact. */
    fun textWithContext(): String = (context + text + responseFormat)
        .asSequence()
        .filter(String::isNotBlank)
        .joinToString("\n\n")
}

@Parcelize
data class LlmExchange(
    val input: LlmInput,
    val assistant: String,
) : Parcelable

@Parcelize
data class LlmSessionConfig(
    val modelPath: String,
    /** Zero delegates CPU thread selection to runtimes that support automatic selection. */
    val cpuThreads: Int,
    /** Zero keeps the model/runtime context default. */
    val contextTokens: Int,
    val speculativeDecoding: Boolean,
    val systemInstruction: String,
    val openingMessage: String,
    val history: List<LlmExchange>,
    val historyLimit: Int,
    val temperature: Float,
    val topK: Int,
    val topP: Float,
    val maxOutputTokens: Int,
    val repetitionPenalty: Float,
    val presencePenalty: Float,
    val frequencyPenalty: Float,
    /** Zero uses the runtime's full penalty history; LiteRT counts only tokens generated in this reply. */
    val penaltyWindow: Int,
    val noRepeatNgramSize: Int,
    /** LiteRT only. Zero checks all tokens generated in this reply for repeated n-grams. */
    val noRepeatNgramWindow: Int,
    val runtime: LlmRuntime = LlmRuntime.LITERT,
    /** llama.cpp prompt-processing threads. Zero uses half the physical cores, rounded down with a minimum of one. */
    val promptThreads: Int = 0,
    val batchTokens: Int = 512,
    val microBatchTokens: Int = 512,
    /** Null lets llama.cpp select flash attention automatically. */
    val flashAttention: Boolean? = null,
    val quantizedKvCache: Boolean = false,
    val cacheTypeK: String = "f16",
    val cacheTypeV: String = "f16",
    val cpuRepack: Boolean = true,
    val useMmap: Boolean = true,
    val dynamicTemperature: Float = 0f,
    val minP: Float = 0.05f,
    val cloudProvider: CloudProvider = CloudProvider.DEEPINFRA,
    val cloudBaseUrl: String = "",
    val cloudApiKey: String = "",
    val cloudModel: String = "",
    val cloudGenerationParameters: Map<String, String> = emptyMap(),
    val cloudStructuredOutput: Boolean = false,
    val liteRtBackend: LiteRtBackend = LiteRtBackend.CPU,
    /** LiteRT only. Vision encoder setup is requested only for models that include one. */
    val liteRtVision: Boolean = false,
    val llamaBackend: LlamaBackend = LlamaBackend.CPU,
    /** Automatic is -2, all layers is -1, CPU/none is 0, and positive values are exact layer counts. */
    val llamaOffloadLayers: Int = LlamaOffload.NONE.layers,
    /** Enables runtime counters for the dedicated local benchmark workload. */
    val benchmarking: Boolean = false,
) : Parcelable

/** Complete, immutable caller-owned input for one generation. */
data class LlmRequest(
    val config: LlmSessionConfig,
    val input: LlmInput,
    val thinking: Boolean = false,
)

@Parcelize
data class LlmChunk(
    val requestId: Long,
    val text: String,
    val channels: Map<String, String>,
) : Parcelable

@Parcelize
data class LlmPerformanceMetrics(
    val timeToFirstTokenMilliseconds: Double,
    val promptTokenCount: Int,
    val generatedTokenCount: Int,
    val promptTokensPerSecond: Double,
    val decodeTokensPerSecond: Double,
    val totalGenerationMilliseconds: Double,
    val peakPssKilobytes: Long,
    val minimumAvailableMemoryBytes: Long,
    val actualContextTokens: Int? = null,
) : Parcelable

@Parcelize
data class LlmTerminal(
    val requestId: Long,
    val reason: LlmTerminalReason,
    val error: LlmError = LlmError.NONE,
    /** Bounded engineering detail. Localized user copy remains in the main process. */
    val detail: String = "",
    val performance: LlmPerformanceMetrics? = null,
) : Parcelable

class LlmEngineException(
    val error: LlmError,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
