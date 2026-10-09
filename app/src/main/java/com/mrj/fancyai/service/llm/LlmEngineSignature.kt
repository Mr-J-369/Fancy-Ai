package com.mrj.fancyai.service.llm

import com.mrj.fancyai.engine.LiteRtBackend
import com.mrj.fancyai.engine.LlamaBackend

internal data class EngineSignature(
    val runtime: LlmRuntime,
    val modelPath: String,
    val liteRtBackend: LiteRtBackend,
    val llamaBackend: LlamaBackend,
    val llamaOffloadLayers: Int,
    val cpuThreads: Int,
    val promptThreads: Int,
    val contextTokens: Int,
    val speculativeDecoding: Boolean,
    val batchTokens: Int,
    val microBatchTokens: Int,
    val flashAttention: Boolean?,
    val quantizedKvCache: Boolean,
    val cacheTypeK: String = "f16",
    val cacheTypeV: String = "f16",
    val cpuRepack: Boolean,
    val useMmap: Boolean,
    val benchmarking: Boolean,
)

internal fun isHardMemoryPressure(totalBytes: Long, availableBytes: Long): Boolean {
    if (totalBytes <= 0L) return false
    val usedBytes = totalBytes - availableBytes.coerceIn(0L, totalBytes)
    val usedRatio = usedBytes.toDouble() / totalBytes.toDouble()
    return usedRatio >= 0.95
}
