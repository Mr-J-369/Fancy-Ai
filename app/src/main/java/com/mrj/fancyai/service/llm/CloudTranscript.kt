package com.mrj.fancyai.service.llm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Cloud conversation assembly. Owns OpenAI message structure including vision parts. */
object CloudTranscript {
    fun messagePayloads(
        config: LlmSessionConfig,
        input: LlmInput,
        imageUrl: (String) -> String,
    ): JsonArray = buildJsonArray {
        config.systemInstruction.takeIf(String::isNotBlank)?.let {
            add(buildJsonObject {
                put("role", "system")
                put("content", JsonPrimitive(it))
            })
        }
        config.openingMessage.takeIf(String::isNotBlank)?.let {
            add(buildJsonObject {
                put("role", "assistant")
                put("content", JsonPrimitive(it))
            })
        }
        config.history.forEach { exchange ->
            add(userPart(exchange.input, imageUrl))
            exchange.assistant.takeIf(String::isNotBlank)?.let {
                add(buildJsonObject {
                    put("role", "assistant")
                    put("content", JsonPrimitive(it))
                })
            }
        }
        add(userPart(input, imageUrl))
    }

    private fun userPart(input: LlmInput, imageUrl: (String) -> String) = buildJsonObject {
        put("role", "user")
        put("content", input.imagePath?.let { path ->
            buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", input.textWithContext())
                })
                add(buildJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject {
                        put("url", imageUrl(path))
                    })
                })
            }
        } ?: JsonPrimitive(input.textWithContext()))
    }
}
