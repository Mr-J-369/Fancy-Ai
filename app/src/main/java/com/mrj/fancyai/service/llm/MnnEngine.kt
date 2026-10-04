package com.mrj.fancyai.service.llm

import com.mrj.fancyai.engine.LocalGenerationMetrics
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** MNN service ownership. Translates transport config and drives generation for MNN. */
internal fun mnnOptions(config: LlmSessionConfig, cacheDir: File): String = buildJsonObject {
    val backendType = if (config.llamaBackend.name.equals("OPENCL", ignoreCase = true)) "opencl" else "cpu"
    put("backend_type", backendType)
    put("thread_num", config.cpuThreads.coerceAtLeast(1))
    put("precision", "low")
    put("use_mmap", config.useMmap)
    put("kvcache_mmap", value = false)
    put("use_cached_mmap", value = false)
    val tmpDir = cacheDir.resolve("mnn_llm").apply {
        mkdirs()
        listFiles { file -> file.name.endsWith("sync.static") }?.forEach { it.delete() }
    }
    put("tmp_path", tmpDir.absolutePath + "/")
    put("reuse_kv", value = true)
    put("prompt_cache", value = true)
    put("async", value = false)
    put("chunk", 128)
    val attentionMode = when {
        !config.quantizedKvCache -> 8
        (config.cacheTypeV == "q8_0") || (config.cacheTypeV == "q4_0") -> 10
        else -> 9
    }
    put("attention_mode", attentionMode)
    put("max_all_tokens", config.contextTokens.takeIf { it > 0 } ?: 4096)
    put("speculative_type", if (config.speculativeDecoding) "lookahead" else "")
    put("timeout_ms", -1)
    put("ngram_factor", 1f)
    put("sampler_type", "mixed")
    val samplerList = if (config.temperature == 0f) {
        listOf("penalty", "greedy")
    } else {
        listOf("penalty", "topK", "tfs", "typical", "topP", "min_p", "temperature")
    }
    put("mixed_samplers", JsonArray(samplerList.map(::JsonPrimitive)))
    put("temperature", config.temperature)
    put("top_k", config.topK)
    put("top_p", config.topP)
    put("min_p", config.minP)
    put("tfs_z", 1f)
    put("typical", 1f)
    put("repetition_penalty", config.repetitionPenalty)
    put("presence_penalty", config.presencePenalty.coerceAtLeast(0f))
    put("frequency_penalty", config.frequencyPenalty.coerceAtLeast(0f))
    put("penalty_window", config.penaltyWindow)
}.toString()

internal suspend fun LlmEngineService.executeMnn(
    session: LlmSessionConfig,
    input: LlmInput,
    thinking: Boolean,
    requestId: Long,
    clientCallback: ILlmEngineCallback,
): LocalGenerationMetrics? {
    val runtime = checkNotNull(mnnRuntime) { "MNN session is not open" }
    val imagePath = input.imagePath
    require(imagePath.isNullOrBlank()) { "Use the shared vision pipeline for MNN image input" }
    runtime.send(input.textWithContext(), thinking, session.maxOutputTokens).collect { (text, channel) ->
        emitTextChunks(requestId, text, channel, clientCallback)
    }
    return if (session.benchmarking) runtime.generationMetrics() else null
}
