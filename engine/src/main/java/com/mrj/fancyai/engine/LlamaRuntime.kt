package com.mrj.fancyai.engine

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn

enum class LlamaBackend(internal val nativeId: Int) {
    CPU(0),
    OPENCL(1),
    HEXAGON(2),
}

data class LlamaEngineConfig(
    val modelPath: String,
    /** Zero uses llama.cpp's model context, subject to automatic device fitting. */
    val contextTokens: Int,
    val batchTokens: Int,
    val microBatchTokens: Int,
    val decodeThreads: Int,
    val promptThreads: Int,
    val flashAttention: Boolean?,
    val cacheTypeK: String = "f16",
    val cacheTypeV: String = "f16",
    val useMmap: Boolean = true,
    val cpuRepack: Boolean,
    val backend: LlamaBackend,
    val offloadLayers: Int,
    /** Host-expert GPU cache in MiB (--moe-cache-mib). Zero disables it. */
    val moeCacheMiB: Int = 0,
    /** Hexagon HTP power mode (GGML_HEXAGON_POWER_MODE). Null uses upstream default. */
    val hexagonPowerMode: String? = null,
    /** Raw extra llama.cpp flags, applied after structured settings. */
    val additionalArgs: String = "",
)

data class LlamaGenerationOptions(
    val maxOutputTokens: Int,
    val temperature: Float,
    val dynamicTemperature: Float,
    val topK: Int,
    val topP: Float,
    val minP: Float,
    val repetitionPenalty: Float,
    val presencePenalty: Float,
    val frequencyPenalty: Float,
    val penaltyWindow: Int,
    val dryMultiplier: Float = 0f,
    val dryBase: Float = 1.75f,
    val dryAllowedLength: Int = 2,
    val dryPenaltyRange: Int = 64,
    val benchmarking: Boolean,
    val responseSchema: String = "",
    val toolsJson: String = "",
)

/** A single llama.cpp model/context owner. Calls are serialized by the engine service. */
class LlamaRuntime private constructor(private val handle: Long) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    @Volatile private var latestMetrics: LocalGenerationMetrics? = null

    fun openConversation(messages: List<LocalLlmMessage>) {
        check(!closed.get()) { "llama.cpp runtime is closed" }
        nativeSetMessages(
            handle,
            messages.map(LocalLlmMessage::role).toTypedArray(),
            messages.map(LocalLlmMessage::content).toTypedArray(),
            messages.map(LocalLlmMessage::reasoningContent).toTypedArray(),
        )
    }

    fun countTokens(text: String): Int {
        check(!closed.get()) { "llama.cpp runtime is closed" }
        return nativeCountTokens(handle, text)
    }

    fun saveKvCache(path: String): Boolean {
        check(!closed.get()) { "llama.cpp runtime is closed" }
        return nativeSaveKvCache(handle, path)
    }

    fun loadKvCache(path: String): Boolean {
        check(!closed.get()) { "llama.cpp runtime is closed" }
        return nativeLoadKvCache(handle, path)
    }

    fun send(
        text: String,
        thinking: Boolean,
        options: LlamaGenerationOptions,
    ): Flow<LocalLlmChunk> = channelFlow {
        check(!closed.get()) { "llama.cpp runtime is closed" }
        latestMetrics = null
        val receiver = object : NativeChunkReceiver {
            override fun onChunk(bytes: ByteArray, channel: Int) {
                val value = bytes.toString(Charsets.UTF_8)
                if (value.isNotEmpty()) {
                    trySendBlocking(
                        LocalLlmChunk(
                            text = value,
                            channel = when (channel) {
                                CHANNEL_REASONING -> REASONING_CHANNEL
                                CHANNEL_TOOL_CALL -> TOOL_CALL_CHANNEL
                                else -> null
                            },
                        ),
                    ).getOrThrow()
                }
            }
        }
        channel.invokeOnClose { cancel() }
        val result = nativeGenerate(
            handle = handle,
            text = text,
            thinking = thinking,
            maxOutputTokens = options.maxOutputTokens,
            temperature = options.temperature,
            dynamicTemperature = options.dynamicTemperature,
            topK = options.topK,
            topP = options.topP,
            minP = options.minP,
            repetitionPenalty = options.repetitionPenalty,
            presencePenalty = options.presencePenalty,
            frequencyPenalty = options.frequencyPenalty,
            penaltyWindow = options.penaltyWindow,
            dryMultiplier = options.dryMultiplier,
            dryBase = options.dryBase,
            dryAllowedLength = options.dryAllowedLength,
            dryPenaltyRange = options.dryPenaltyRange,
            benchmarking = options.benchmarking,
            responseSchema = options.responseSchema,
            toolsJson = options.toolsJson,
            receiver = receiver,
        )
        val status = result[RESULT_STATUS].toInt()
        if (status == RESULT_CANCELLED) throw CancellationException("llama.cpp generation cancelled")
        val promptTokens = result[RESULT_PROMPT_TOKENS].toInt()
        val generatedTokens = result[RESULT_GENERATED_TOKENS].toInt()
        latestMetrics = LocalGenerationMetrics(
            timeToFirstTokenMilliseconds = 0.0,
            actualContextTokens = result[RESULT_CONTEXT_TOKENS].toInt(),
            promptTokenCount = promptTokens,
            generatedTokenCount = generatedTokens,
            promptTokensPerSecond = 0.0,
            decodeTokensPerSecond = 0.0,
            graphReallocsSame = result[RESULT_REALLOCS_SAME].toInt(),
            graphReallocsDiff = result[RESULT_REALLOCS_DIFF].toInt(),
        )
        if (options.benchmarking) {
            val prefillNanoseconds = result[RESULT_PREFILL_NANOS]
            val decodeNanoseconds = result[RESULT_DECODE_NANOS]
            latestMetrics = checkNotNull(latestMetrics).copy(
                timeToFirstTokenMilliseconds = result[RESULT_TTFT_NANOS] / NANOS_PER_MILLISECOND,
                promptTokensPerSecond = rate(promptTokens, prefillNanoseconds),
                decodeTokensPerSecond = rate(generatedTokens, decodeNanoseconds),
            )
        }
    }.flowOn(Dispatchers.IO)

    fun generationMetrics(): LocalGenerationMetrics = checkNotNull(latestMetrics) {
        "llama.cpp generation metrics are unavailable"
    }

    @Synchronized
    fun cancel() {
        if (!closed.get()) nativeCancel(handle)
    }

    override fun close() {
        synchronized(this) {
            if (!closed.compareAndSet(false, true)) return
        }
        nativeClose(handle)
    }

    private interface NativeChunkReceiver {
        fun onChunk(bytes: ByteArray, channel: Int)
    }

    private external fun nativeSetMessages(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
        reasonings: Array<String>,
    )

    private external fun nativeCountTokens(
        handle: Long,
        text: String,
    ): Int

    private external fun nativeSaveKvCache(
        handle: Long,
        path: String,
    ): Boolean

    private external fun nativeLoadKvCache(
        handle: Long,
        path: String,
    ): Boolean

    private external fun nativeGenerate(
        handle: Long,
        text: String,
        thinking: Boolean,
        maxOutputTokens: Int,
        temperature: Float,
        dynamicTemperature: Float,
        topK: Int,
        topP: Float,
        minP: Float,
        repetitionPenalty: Float,
        presencePenalty: Float,
        frequencyPenalty: Float,
        penaltyWindow: Int,
        dryMultiplier: Float,
        dryBase: Float,
        dryAllowedLength: Int,
        dryPenaltyRange: Int,
        benchmarking: Boolean,
        responseSchema: String,
        toolsJson: String,
        receiver: NativeChunkReceiver,
    ): LongArray

    private external fun nativeCancel(handle: Long)

    private external fun nativeClose(handle: Long)

    companion object {
        init {
            System.loadLibrary("fancy_llama")
        }

        fun open(
            application: android.content.Context,
            nativeLibraryDirectory: String,
            cacheDirectory: String,
            config: LlamaEngineConfig,
        ): LlamaRuntime {
            val handle = nativeOpen(
                application = application,
                nativeLibraryDirectory = nativeLibraryDirectory,
                cacheDirectory = cacheDirectory,
                modelPath = config.modelPath,
                contextTokens = config.contextTokens,
                batchTokens = config.batchTokens,
                microBatchTokens = config.microBatchTokens,
                decodeThreads = config.decodeThreads,
                promptThreads = config.promptThreads,
                flashAttention = when (config.flashAttention) {
                    null -> -1
                    false -> 0
                    true -> 1
                },
                cacheTypeK = config.cacheTypeK,
                cacheTypeV = config.cacheTypeV,
                useMmap = config.useMmap,
                cpuRepack = config.cpuRepack,
                backend = config.backend.nativeId,
                offloadLayers = config.offloadLayers,
                moeCacheMiB = config.moeCacheMiB,
                hexagonPowerMode = config.hexagonPowerMode,
                additionalArgs = config.additionalArgs,
            )
            return LlamaRuntime(handle)
        }

        @JvmStatic
        private external fun nativeOpen(
            application: android.content.Context,
            nativeLibraryDirectory: String,
            cacheDirectory: String,
            modelPath: String,
            contextTokens: Int,
            batchTokens: Int,
            microBatchTokens: Int,
            decodeThreads: Int,
            promptThreads: Int,
            flashAttention: Int,
            cacheTypeK: String,
            cacheTypeV: String,
            useMmap: Boolean,
            cpuRepack: Boolean,
            backend: Int,
            offloadLayers: Int,
            moeCacheMiB: Int,
            hexagonPowerMode: String?,
            additionalArgs: String,
        ): Long

        private const val RESULT_CANCELLED = 1
        private const val RESULT_STATUS = 0
        private const val RESULT_PROMPT_TOKENS = 1
        private const val RESULT_GENERATED_TOKENS = 2
        private const val RESULT_PREFILL_NANOS = 3
        private const val RESULT_TTFT_NANOS = 4
        private const val RESULT_DECODE_NANOS = 5
        private const val RESULT_CONTEXT_TOKENS = 6
        private const val RESULT_REALLOCS_SAME = 7
        private const val RESULT_REALLOCS_DIFF = 8
        private const val NANOS_PER_MILLISECOND = 1_000_000.0
        private const val CHANNEL_REASONING = 1
        private const val CHANNEL_TOOL_CALL = 2
        private const val REASONING_CHANNEL = "reasoning"
        private const val TOOL_CALL_CHANNEL = "tool_call"

        internal fun rate(tokens: Int, nanoseconds: Long): Double = if ((tokens > 0) && (nanoseconds > 0L)) {
            (tokens.toDouble() * 1_000_000_000.0) / nanoseconds.toDouble()
        } else {
            0.0
        }
    }
}
