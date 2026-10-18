package com.mrj.fancyai.service.llm

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Message
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

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
    if (message.toolCalls.isNotEmpty()) {
        val calls = buildJsonArray {
            message.toolCalls.forEachIndexed { index, call ->
                addJsonObject {
                    put("id", "litert-$index")
                    put("name", call.name)
                    putJsonObject("arguments") {
                        call.arguments.forEach { (key, value) -> put(key, value.toJsonElement()) }
                    }
                }
            }
        }
        emitTextChunks(requestId, calls.toString(), "tool_call", clientCallback)
    }
}

private fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is String -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Map<*, *> -> buildJsonObject {
        @Suppress("UNCHECKED_CAST")
        (this@toJsonElement as Map<String, Any?>).forEach { (key, value) -> put(key, value.toJsonElement()) }
    }
    is Iterable<*> -> buildJsonArray { forEach { add(it.toJsonElement()) } }
    else -> JsonPrimitive(toString())
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
