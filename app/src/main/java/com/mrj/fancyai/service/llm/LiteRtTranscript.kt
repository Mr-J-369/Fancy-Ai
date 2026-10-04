package com.mrj.fancyai.service.llm

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message

/** LiteRT conversation assembly. System instruction stays separate per ConversationConfig. */
object LiteRtTranscript {
    fun systemContents(config: LlmSessionConfig): Contents? =
        config.systemInstruction.takeIf(String::isNotBlank)?.let(Contents::of)

    fun initialMessages(config: LlmSessionConfig): List<Message> = buildList {
        config.openingMessage.takeIf(String::isNotBlank)?.let {
            add(Message.model(it))
        }
        config.history.forEach { exchange ->
            val text = exchange.input.textWithContext()
            val imagePath = exchange.input.imagePath
            add(
                Message.user(
                    if (imagePath == null) Contents.of(text)
                    else Contents.of(Content.ImageFile(imagePath), Content.Text(text)),
                ),
            )
            exchange.assistant.takeIf(String::isNotBlank)?.let {
                add(Message.model(it))
            }
        }
    }
}
