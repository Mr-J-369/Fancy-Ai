package com.mrj.fancyai.engine

data class LocalGenerationMetrics(
    val timeToFirstTokenMilliseconds: Double,
    val promptTokenCount: Int,
    val generatedTokenCount: Int,
    val promptTokensPerSecond: Double,
    val decodeTokensPerSecond: Double,
    val actualContextTokens: Int? = null,
)
