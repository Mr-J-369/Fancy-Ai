package com.mrj.fancyai.vision

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import java.io.Closeable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class VisionRuntime private constructor(
    private val engine: Engine,
    private val conversation: Conversation,
) : Closeable {
    private var closed = false

    fun project(imagePath: String, prompt: String): Flow<String> =
        conversation.sendMessageAsync(
            contents = Contents.of(Content.ImageFile(imagePath), Content.Text(prompt)),
            maxOutputToken = MAX_OUTPUT_TOKENS,
            thinkingConfig = ThinkingConfig(enableThinking = false),
        ).map { message ->
            message.contents.contents
                .filterIsInstance<Content.Text>()
                .joinToString(separator = "") { it.text }
        }.filter(String::isNotEmpty)

    @Synchronized
    fun cancel() {
        if (!closed) conversation.cancelProcess()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        conversation.close()
        engine.close()
    }

    companion object {
        suspend fun open(
            modelPath: String,
            cacheDirectory: String,
        ): VisionRuntime = withContext(Dispatchers.IO) {
            val engine = Engine(
                EngineConfig(
                    modelPath = modelPath,
                    backend = Backend.CPU(),
                    visionBackend = Backend.CPU(),
                    maxNumTokens = MAX_CONTEXT_TOKENS,
                    maxNumImages = 1,
                    cacheDir = cacheDirectory,
                ),
            )
            try {
                engine.initialize()
                val conversation = engine.createConversation(
                    ConversationConfig(
                        samplerConfig = SamplerConfig(
                            topK = 20,
                            topP = 0.9,
                            temperature = 0.2,
                        ),
                        maxOutputToken = MAX_OUTPUT_TOKENS,
                    ),
                )
                VisionRuntime(engine, conversation)
            } catch (failure: Throwable) {
                engine.close()
                throw failure
            }
        }

        private const val MAX_CONTEXT_TOKENS = 2_048
        private const val MAX_OUTPUT_TOKENS = 1_024
    }
}
