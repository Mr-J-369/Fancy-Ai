package com.mrj.fancyai.engine

import kotlinx.serialization.json.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn

/** Text inference using MNN, owned and serialized by the shared engine service. */
class MnnRuntime private constructor(private val handle: Long) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private var metrics: LocalGenerationMetrics? = null

    fun openConversation(messages: List<LocalLlmMessage>) {
        check(!closed.get())
        val array = buildJsonArray {
            messages.forEach { add(buildJsonObject {
                put("role", it.role)
                put("content", it.content)
            }) }
        }
        nativeSetMessages(handle, array.toString().toByteArray(Charsets.UTF_8))
    }

    fun send(text: String, thinking: Boolean, maxOutputTokens: Int): Flow<LocalLlmChunk> = channelFlow {
        check(!closed.get())
        metrics = null
        val receiver = object : NativeChunkReceiver {
            override fun onChunk(bytes: ByteArray, reasoning: Boolean) {
                val value = bytes.toString(Charsets.UTF_8)
                if (value.isNotEmpty()) {
                    trySendBlocking(
                        LocalLlmChunk(value, if (reasoning) "reasoning" else null),
                    ).getOrThrow()
                }
            }
        }
        nativePrepare(handle)
        channel.invokeOnClose { cancel() }
        val result = nativeGenerate(handle, text.toByteArray(Charsets.UTF_8), thinking, maxOutputTokens, receiver)
        if (result[0] == 1L) throw CancellationException("MNN generation cancelled")
        metrics = LocalGenerationMetrics(
            timeToFirstTokenMilliseconds = result[5] / 1_000.0,
            promptTokenCount = result[1].toInt(),
            generatedTokenCount = result[2].toInt(),
            promptTokensPerSecond = rate(result[1], result[3]),
            decodeTokensPerSecond = rate(result[2], result[4]),
        )
    }.flowOn(Dispatchers.IO)

    fun generationMetrics(): LocalGenerationMetrics = checkNotNull(metrics)

    @Synchronized
    fun cancel() { if (!closed.get()) nativeCancel(handle) }

    @Synchronized
    override fun close() { if (closed.compareAndSet(false, true)) nativeClose(handle) }

    private interface NativeChunkReceiver { fun onChunk(bytes: ByteArray, reasoning: Boolean) }
    private external fun nativeSetMessages(handle: Long, messages: ByteArray)
    private external fun nativeGenerate(handle: Long, input: ByteArray, thinking: Boolean, maxTokens: Int, receiver: NativeChunkReceiver): LongArray
    private external fun nativePrepare(handle: Long)
    private external fun nativeCancel(handle: Long)
    private external fun nativeClose(handle: Long)

    private fun rate(tokens: Long, microseconds: Long): Double =
        if ((tokens > 0) && (microseconds > 0)) (tokens * 1_000_000.0) / microseconds else 0.0

    companion object {
        init { System.loadLibrary("fancy_mnn_llm") }
        fun open(application: android.content.Context, modelPath: String, options: String, contextTokens: Int): MnnRuntime {
            val handle = nativeOpen(application, modelPath.toByteArray(Charsets.UTF_8), options.toByteArray(Charsets.UTF_8), contextTokens)
            check(handle != 0L) { "MNN returned an empty runtime" }
            return MnnRuntime(handle)
        }
        @JvmStatic private external fun nativeOpen(application: android.content.Context, path: ByteArray, options: ByteArray, context: Int): Long
    }
}
