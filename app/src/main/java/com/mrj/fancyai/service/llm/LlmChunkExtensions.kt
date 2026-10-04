package com.mrj.fancyai.service.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

internal fun LlmTerminal.asException(): LlmEngineException = LlmEngineException(
    error = error.takeUnless { it == LlmError.NONE } ?: LlmError.INTERNAL,
    message = detail.takeIf(String::isNotBlank) ?: "Local model operation failed",
)

internal suspend fun Flow<LlmChunk>.complete(onPartialFailure: ((Throwable) -> Unit)? = null): LlmChunk {
    val text = StringBuilder()
    val channels = mutableMapOf<String, StringBuilder>()
    var requestId = 0L
    try {
        collect { chunk ->
            requestId = chunk.requestId
            text.append(chunk.text)
            chunk.channels.forEach { (name, value) ->
                channels.getOrPut(name, ::StringBuilder).append(value)
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        if (text.isBlank()) throw failure
        onPartialFailure?.invoke(failure)
    }
    return LlmChunk(requestId = requestId, channels = channels.mapValues { it.value.toString() }, text = text.toString())
}
