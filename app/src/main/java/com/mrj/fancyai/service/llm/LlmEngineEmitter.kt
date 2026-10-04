package com.mrj.fancyai.service.llm

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Message

internal const val MAX_ERROR_DETAIL = 500
internal const val MAX_CHANNEL_NAME_CHARS = 128
internal const val MAX_IPC_CHUNK_CHARS = 16_384

internal fun emitMessage(
    requestId: Long,
    message: Message,
    clientCallback: ILlmEngineCallback,
) {
    val text = message.contents.contents.asSequence()
        .filterIsInstance<Content.Text>()
        .joinToString(separator = "") { it.text }
    emitTextChunks(requestId, text, channelName = null, clientCallback)
    message.channels.forEach { (name, value) ->
        emitTextChunks(
            requestId = requestId,
            text = value,
            channelName = name.take(MAX_CHANNEL_NAME_CHARS),
            clientCallback = clientCallback,
        )
    }
}

internal fun emitTextChunks(
    requestId: Long,
    text: String,
    channelName: String?,
    clientCallback: ILlmEngineCallback,
) {
    var start = 0
    while (start < text.length) {
        val end = (start + MAX_IPC_CHUNK_CHARS).coerceAtMost(text.length)
        val part = text.substring(start, end)
        clientCallback.onChunk(
            if (channelName == null) {
                LlmChunk(requestId, part, emptyMap())
            } else {
                LlmChunk(requestId, "", mapOf(channelName to part))
            },
        )
        start = end
    }
}
