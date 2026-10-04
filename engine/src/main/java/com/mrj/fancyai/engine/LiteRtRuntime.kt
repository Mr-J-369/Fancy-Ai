package com.mrj.fancyai.engine

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.NoRepeatNgramConfig
import com.google.ai.edge.litertlm.RepetitionPenaltyConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import java.io.Closeable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

enum class LiteRtBackend { CPU, GPU }

class LiteRtRuntime private constructor(
    private val engine: Engine,
    private val benchmarking: Boolean,
) : Closeable {
    private var conversation: Conversation? = null

    @Synchronized
    fun openConversation(config: ConversationConfig) {
        conversation?.close()
        conversation = engine.createConversation(config)
    }

    fun send(
        contents: Contents,
        options: LiteRtGenerationOptions,
    ): Flow<Message> = callbackFlow {
        val active = activeConversation()
        val finished = CompletableDeferred<Unit>()
        var started = false
        try {
            // LiteRT 0.17's Flow overload drops messages when its buffer fills.
            // Its native callback runs on a worker, so bounded delivery can wait.
            active.sendMessageAsync(
                contents = contents,
                callback = object : MessageCallback {
                    override fun onMessage(message: Message) {
                        trySendBlocking(message)
                    }

                    override fun onDone() {
                        finished.complete(Unit)
                        close()
                    }

                    override fun onError(throwable: Throwable) {
                        finished.complete(Unit)
                        close(throwable)
                    }
                },
                repetitionPenaltyConfig = options.repetitionPenaltyConfig(),
                noRepeatNgramConfig = options.noRepeatNgramConfig(),
                maxOutputToken = options.maxOutputTokens,
                thinkingConfig = ThinkingConfig(enableThinking = options.thinking),
            )
            started = true
            awaitClose {}
        } finally {
            if (started && !finished.isCompleted) {
                withContext(NonCancellable) {
                    active.cancelProcess()
                    // Do not let the service reuse or close this conversation
                    // until native generation reports that it has stopped.
                    finished.await()
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    @OptIn(ExperimentalApi::class)
    @Synchronized
    fun generationMetrics(): LocalGenerationMetrics {
        check(benchmarking) { "LiteRT benchmark metrics were not enabled" }
        val benchmark = activeConversation().getBenchmarkInfo()
        return LocalGenerationMetrics(
            timeToFirstTokenMilliseconds = benchmark.timeToFirstTokenInSecond * 1_000.0,
            promptTokenCount = benchmark.lastPrefillTokenCount,
            generatedTokenCount = benchmark.lastDecodeTokenCount,
            promptTokensPerSecond = benchmark.lastPrefillTokensPerSecond,
            decodeTokensPerSecond = benchmark.lastDecodeTokensPerSecond,
        )
    }

    @Synchronized
    fun cancel() {
        conversation?.cancelProcess()
    }

    @Synchronized
    override fun close() {
        conversation?.close()
        conversation = null
        engine.close()
    }

    @Synchronized
    private fun activeConversation(): Conversation = checkNotNull(conversation) {
        "Conversation is not open"
    }

    companion object {
        @OptIn(ExperimentalApi::class)
        suspend fun open(
            engineConfig: EngineConfig,
            speculativeDecoding: Boolean = false,
            benchmarking: Boolean = false,
        ): LiteRtRuntime = withContext(Dispatchers.IO) {
            val backends = listOfNotNull(
                engineConfig.backend,
                engineConfig.visionBackend,
                engineConfig.audioBackend,
            )
            require(backends.all { (it is Backend.CPU) || (it is Backend.GPU) }) {
                "Fancy supports LiteRT-LM CPU and GPU backends."
            }

            val engine = Engine(engineConfig)
            try {
                val previousBenchmark = ExperimentalFlags.enableBenchmark
                val previousSpeculative = ExperimentalFlags.enableSpeculativeDecoding
                ExperimentalFlags.enableBenchmark = benchmarking
                ExperimentalFlags.enableSpeculativeDecoding = speculativeDecoding
                try {
                    engine.initialize()
                } finally {
                    ExperimentalFlags.enableBenchmark = previousBenchmark
                    ExperimentalFlags.enableSpeculativeDecoding = previousSpeculative
                }
                LiteRtRuntime(engine, benchmarking)
            } catch (error: Throwable) {
                engine.close()
                throw error
            }
        }
    }
}

data class LiteRtGenerationOptions(
    val thinking: Boolean,
    val maxOutputTokens: Int,
    val repetitionPenalty: Float,
    val presencePenalty: Float,
    val frequencyPenalty: Float,
    val penaltyWindow: Int,
    val noRepeatNgramSize: Int,
    val noRepeatNgramWindow: Int,
) {
    private fun hasRepetitionControl(): Boolean =
        (repetitionPenalty != 1f) || (presencePenalty != 0f) || (frequencyPenalty != 0f)

    fun repetitionPenaltyConfig(): RepetitionPenaltyConfig? = if (hasRepetitionControl()) {
        RepetitionPenaltyConfig(
            repetitionPenalty = repetitionPenalty.takeUnless { it == 1f },
            presencePenalty = presencePenalty.takeUnless { it == 0f },
            frequencyPenalty = frequencyPenalty.takeUnless { it == 0f },
            windowSize = penaltyWindow.takeIf { it > 0 },
        )
    } else {
        null
    }

    fun noRepeatNgramConfig(): NoRepeatNgramConfig? = noRepeatNgramSize.takeIf { it > 0 }?.let {
        NoRepeatNgramConfig(
            noRepeatNgramSize = it,
            windowSize = noRepeatNgramWindow.takeIf { window -> window > 0 },
        )
    }
}
