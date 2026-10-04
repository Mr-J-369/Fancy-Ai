package com.mrj.fancyai.service.llm

import android.os.SystemClock
import com.mrj.fancyai.engine.LocalGenerationMetrics

internal suspend fun LlmEngineService.generateExecution(
    requestId: Long,
    input: LlmInput,
    thinking: Boolean,
    clientCallback: ILlmEngineCallback,
): LlmPerformanceMetrics? {
    val session = checkNotNull(currentSessionConfig) { "Engine session is not open" }
    val started = SystemClock.elapsedRealtimeNanos()
    var completed: Long
    val runtimeMetrics: LocalGenerationMetrics? = when (session.runtime) {
        LlmRuntime.LITERT -> executeLiteRt(session, input, thinking, requestId, clientCallback).also {
            completed = SystemClock.elapsedRealtimeNanos()
        }
        LlmRuntime.LLAMA -> executeLlama(session, input, thinking, requestId, clientCallback).also {
            completed = SystemClock.elapsedRealtimeNanos()
        }
        LlmRuntime.MNN -> executeMnn(session, input, thinking, requestId, clientCallback).also {
            completed = SystemClock.elapsedRealtimeNanos()
        }
        LlmRuntime.CLOUD -> error("Unsupported local runtime")
    }
    return runtimeMetrics?.let { metrics ->
        sampleBenchmarkMemory()
        LlmPerformanceMetrics(
            timeToFirstTokenMilliseconds = metrics.timeToFirstTokenMilliseconds,
            actualContextTokens = metrics.actualContextTokens,
            promptTokenCount = metrics.promptTokenCount,
            generatedTokenCount = metrics.generatedTokenCount,
            promptTokensPerSecond = metrics.promptTokensPerSecond,
            decodeTokensPerSecond = metrics.decodeTokensPerSecond,
            totalGenerationMilliseconds =
                (completed - started) / 1_000_000.0,
            peakPssKilobytes = benchmarkPeakPssKilobytes.get(),
            minimumAvailableMemoryBytes = benchmarkMinimumAvailableMemoryBytes.get(),
        )
    }
}
