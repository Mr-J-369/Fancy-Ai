package com.mrj.fancyai.engine

data class LocalLlmMessage(
    val role: String,
    val content: String,
)

data class LocalLlmChunk(
    val text: String,
    val channel: String? = null,
)

